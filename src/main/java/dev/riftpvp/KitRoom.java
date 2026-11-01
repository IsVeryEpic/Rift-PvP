package dev.riftpvp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * A temporary, guarded Kit Room inventory. The top inventory is an endlessly
 * restocking *catalogue*; it never transfers actual items. The bottom inventory
 * belongs to an editing session, and the player's genuine gear is escrowed and
 * restored on exit. A crash-safe escrow record is written before mutation.
 */
final class KitRoom implements Listener {
   private static final int CONTENT_SLOTS = 41;
   private final RiftPvpPlugin plugin;
   private final File layoutsDir;
   private final File escrowDir;
   private final Map<UUID, Session> active = new HashMap<>();
   private final Map<UUID, YamlConfiguration> loaded = new HashMap<>();

   KitRoom(RiftPvpPlugin plugin) {
      this.plugin = plugin;
      layoutsDir = new File(plugin.getDataFolder(), "kit-room");
      escrowDir = new File(plugin.getDataFolder(), "kit-room-escrow");
      if (!layoutsDir.isDirectory() && !layoutsDir.mkdirs()) plugin.getLogger().warning("Cannot create Kit Room save folder");
      if (!escrowDir.isDirectory() && !escrowDir.mkdirs()) plugin.getLogger().warning("Cannot create Kit Room escrow folder");
   }

   boolean editing(Player player) { return active.containsKey(player.getUniqueId()); }

   /** If no custom room loadout is saved, preserve the old v1.3.3 slot permutation. */
   ItemStack[] apply(UUID owner, String kit, ItemStack[] fallback) {
      if (!plugin.kits().isKitRoomEnabled(kit)) return fallback;
      YamlConfiguration data = data(owner);
      int slot = Math.max(1, Math.min(3, data.getInt("active." + kit, 1)));
      ItemStack[] custom = readItems(data, "kits." + kit + "." + slot);
      if (custom == null || !valid(kit, custom)) return fallback;
      return copy(custom);
   }

   void open(Player player, String kit) {
      if (!plugin.kits().hasKit(kit) || active.containsKey(player.getUniqueId())) return;
      if (!plugin.kits().isKitRoomEnabled(kit)) {
         player.sendMessage(RiftPvpPlugin.colors("&cKit Room is disabled for this kit by staff."));
         return;
      }
      if (!plugin.inLobby(player.getLocation()) || !plugin.combat().mayUse(player, "kit")) {
         player.sendMessage(RiftPvpPlugin.colors("&cOpen the Kit Room in the lobby, outside combat."));
         return;
      }
      ItemStack[] base = defaultKitItems(kit);
      if (base == null) return;
      LinkedHashMap<String, ItemStack> catalogue = new LinkedHashMap<>();
      for (ItemStack item : base) {
         if (item == null || item.getType().isAir()) continue;
         catalogue.putIfAbsent(item.getType().name() + "#" + String.valueOf(item.getItemMeta()), item.clone());
      }
      if ("htk".equalsIgnoreCase(kit)) {
         ItemStack endStone = new ItemStack(Material.END_STONE, 64);
         catalogue.put("END_STONE#kitroom", endStone);
      }
      if (catalogue.size() > 45) {
         player.sendMessage(RiftPvpPlugin.colors("&cThis kit has too many unique items for the Kit Room."));
         return;
      }
      player.closeInventory();
      ItemStack[] original = snapshot(player);
      try {
         saveEscrow(player.getUniqueId(), original);
      } catch (IOException error) {
         player.sendMessage(RiftPvpPlugin.colors("&cKit Room cannot safely save your inventory. Please contact staff."));
         plugin.getLogger().warning("Kit Room escrow: " + error.getMessage());
         return;
      }
      int preset = Math.max(1, Math.min(3, data(player.getUniqueId()).getInt("active." + kit, 1)));
      ItemStack[] fallback = plugin.layouts().apply(player.getUniqueId(), kit, base);
      ItemStack[] draft = apply(player.getUniqueId(), kit, fallback);
      // Re-validate old source layouts against the new catalogue before showing them.
      if (!valid(kit, draft)) draft = copy(base);
      RoomHolder holder = new RoomHolder();
      holder.inventory = Bukkit.createInventory(holder, 54, RiftPvpPlugin.colors("&5Kit Room &8| &f" + plugin.kits().name(kit)));
      Session session = new Session(player.getUniqueId(), kit, preset, original, holder, new ArrayList<>(catalogue.values()));
      active.put(player.getUniqueId(), session);
      player.setItemOnCursor(null);
      restore(player, draft);
      redraw(session);
      player.openInventory(holder.inventory);
      player.sendMessage(RiftPvpPlugin.colors("&dKit Room &7» &fPick up items above, then place or drag them into your inventory below. &eShift-click &fto add directly."));
   }

   private void redraw(Session session) {
      Inventory view = session.holder.inventory;
      view.clear();
      int index = 0;
      for (ItemStack item : session.catalogue) view.setItem(index++, item.clone());
      for (; index < 45; index++) view.setItem(index, new ItemStack(Material.BLACK_STAINED_GLASS_PANE));
      for (int i = 1; i <= 3; i++) {
         view.setItem(44 + i, labelled(i == session.preset ? Material.LIME_DYE : Material.GRAY_DYE,
            "&dPreset " + i + (i == session.preset ? " &a(Selected)" : "")));
      }
      view.setItem(49, labelled(Material.EMERALD_BLOCK, "&aSave & Exit"));
      view.setItem(50, labelled(Material.REDSTONE_BLOCK, "&cReset to Default"));
      view.setItem(51, labelled(Material.BARRIER, "&cClear Editable Inventory"));
      view.setItem(53, labelled(Material.ARROW, "&fBack to Kit Editor"));
   }

   private static ItemStack labelled(Material material, String name) {
      ItemStack item = new ItemStack(material);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) { meta.setDisplayName(RiftPvpPlugin.colors(name)); item.setItemMeta(meta); }
      return item;
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
   public void onClick(InventoryClickEvent event) {
      if (!(event.getView().getTopInventory().getHolder() instanceof RoomHolder holder)) return;
      event.setCancelled(true);
      if (!(event.getWhoClicked() instanceof Player player)) return;
      Session session = active.get(player.getUniqueId());
      if (session == null || session.holder != holder) return;
      if (event.getRawSlot() < 0) return;
      if (event.getRawSlot() < 45) {
         // The top inventory is a restocking catalogue, not a normal source inventory.
         // Give the player an approved COPY on their cursor so they can actually
         // click or drag it into their editable lower inventory. Shift-click still
         // offers the old quick-add behaviour without moving catalogue items.
         ClickType click = event.getClick();
         boolean quickAdd = click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
         if (!(click == ClickType.LEFT || click == ClickType.RIGHT || quickAdd)) return;
         int index = event.getRawSlot();
         if (index >= session.catalogue.size() || !empty(event.getCursor())) return;
         ItemStack added = session.catalogue.get(index).clone();
         if (click == ClickType.RIGHT) added.setAmount(1);
         else added.setAmount(Math.min(added.getMaxStackSize(), added.getAmount()));
         if (plugin.kits().isXpBottleCapEnabled(session.kit) && added.getType() == Material.EXPERIENCE_BOTTLE) {
            int remaining = 64 - KitManager.countXpBottles(snapshot(player));
            if (remaining <= 0) { player.sendMessage(RiftPvpPlugin.colors("&cThis kit is limited to 64 XP bottles.")); return; }
            added.setAmount(Math.min(added.getAmount(), remaining));
         }
         // Defer cursor/inventory mutations until the click event has completed.
         // This avoids Bukkit's container transaction overwriting the cursor.
         Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || active.get(player.getUniqueId()) != session
                  || player.getOpenInventory().getTopInventory().getHolder() != holder
                  || !empty(player.getItemOnCursor())) return;
            ItemStack copy = added.clone();
            if (plugin.kits().isXpBottleCapEnabled(session.kit) && copy.getType() == Material.EXPERIENCE_BOTTLE) {
               int remaining = 64 - KitManager.countXpBottles(snapshot(player));
               if (remaining <= 0) return;
               copy.setAmount(Math.min(copy.getAmount(), remaining));
            }
            if (quickAdd) {
               Map<Integer, ItemStack> leftover = player.getInventory().addItem(copy);
               if (!leftover.isEmpty()) player.sendMessage(RiftPvpPlugin.colors("&eYour inventory is full; some items were not added."));
            } else {
               player.setItemOnCursor(copy);
            }
            player.updateInventory();
         });
         return;
      }
      if (event.getRawSlot() < 54) {
         switch (event.getRawSlot()) {
            case 45, 46, 47 -> changePreset(player, session, event.getRawSlot() - 44);
            case 49 -> player.closeInventory();
            case 50 -> restore(player, defaultKitItems(session.kit));
            case 51 -> restore(player, new ItemStack[CONTENT_SLOTS]);
            case 53 -> { session.back = true; player.closeInventory(); }
            default -> { }
         }
         player.updateInventory();
         return;
      }
      // Only allow ordinary inventory rearrangement in the lower 36 player slots;
      // deny hotbar pickup with the top's cursor and all special swap actions.
      if (event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP) {
         event.setCurrentItem(null); // remove a selected kit-room item rather than spawning a world drop
         player.updateInventory();
      } else if ((event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT)
            && allowed(session.kit, event.getCursor())) {
         // Permit normal pickup/place/split/drag operations in the editable bottom inventory.
         // Check the cursor as well as stored items to avoid bypassing the XP cap.
         ItemStack cursor = event.getCursor();
         if (plugin.kits().isXpBottleCapEnabled(session.kit) && !empty(cursor)
               && cursor.getType() == Material.EXPERIENCE_BOTTLE
               && KitManager.countXpBottles(snapshot(player)) + cursor.getAmount() > 64) return;
         event.setCancelled(false);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
   public void onDrag(InventoryDragEvent event) {
      if (!(event.getView().getTopInventory().getHolder() instanceof RoomHolder)) return;
      // Drags in the bottom inventory are allowed only if the cursor is a permitted kit item.
      Player player = (Player) event.getWhoClicked();
      Session session = active.get(player.getUniqueId());
      ItemStack cursor = event.getOldCursor();
      if (session == null || event.getRawSlots().stream().anyMatch(i -> i < 54)
            || !allowed(session.kit, cursor)
            || (plugin.kits().isXpBottleCapEnabled(session.kit) && !empty(cursor)
                && cursor.getType() == Material.EXPERIENCE_BOTTLE
                && KitManager.countXpBottles(snapshot(player)) + cursor.getAmount() > 64)) {
         event.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
   public void onDrop(PlayerDropItemEvent event) {
      if (active.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
   public void onSwap(PlayerSwapHandItemsEvent event) {
      if (active.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
   }

   @EventHandler
   public void onTeleport(PlayerTeleportEvent event) {
      if (active.containsKey(event.getPlayer().getUniqueId())) event.getPlayer().closeInventory();
   }

   @EventHandler
   public void onClose(InventoryCloseEvent event) {
      if (!(event.getView().getTopInventory().getHolder() instanceof RoomHolder holder)) return;
      Player player = (Player) event.getPlayer();
      Session session = active.get(player.getUniqueId());
      if (session == null || session.holder != holder) return;
      close(player, session);
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      Session session = active.get(event.getPlayer().getUniqueId());
      if (session != null) close(event.getPlayer(), session);
      loaded.remove(event.getPlayer().getUniqueId());
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      // If the server crashed mid-edit, restore the real inventory from its escrow record.
      File escrow = escrowFile(event.getPlayer().getUniqueId());
      if (!escrow.isFile()) return;
      YamlConfiguration file = YamlConfiguration.loadConfiguration(escrow);
      ItemStack[] old = readItems(file, "items");
      if (old == null) {
         plugin.getLogger().severe("Invalid Kit Room escrow for " + event.getPlayer().getUniqueId() + "; retaining file for manual recovery");
         return;
      }
      restore(event.getPlayer(), old);
      if (!escrow.delete()) plugin.getLogger().warning("Could not delete restored Kit Room escrow: " + escrow.getName());
      event.getPlayer().sendMessage(RiftPvpPlugin.colors("&aRecovered your inventory after an interrupted Kit Room session."));
   }

   void closeAll() {
      for (Session session : List.copyOf(active.values())) {
         Player player = Bukkit.getPlayer(session.id);
         if (player != null) close(player, session);
      }
   }

   private void changePreset(Player player, Session session, int preset) {
      if (!saveCurrent(player, session)) return;
      session.preset = preset;
      YamlConfiguration data = data(session.id);
      data.set("active." + session.kit, preset);
      try { saveData(session.id, data); }
      catch (IOException e) { player.sendMessage(RiftPvpPlugin.colors("&cCould not select new preset.")); return; }
      ItemStack[] saved = readItems(data, "kits." + session.kit + "." + preset);
      if (saved == null || !valid(session.kit, saved)) saved = defaultKitItems(session.kit);
      restore(player, saved);
      redraw(session);
   }

   private boolean saveCurrent(Player player, Session session) {
      ItemStack[] draft = snapshot(player);
      if (!valid(session.kit, draft)) {
         player.sendMessage(RiftPvpPlugin.colors("&cKit Room: disallowed item or XP limit exceeded. Reverting this preset."));
         return false;
      }
      YamlConfiguration data = data(session.id);
      data.set("kits." + session.kit + "." + session.preset, Arrays.asList(copy(draft)));
      data.set("active." + session.kit, session.preset);
      try { saveData(session.id, data); return true; }
      catch (IOException error) {
         plugin.getLogger().warning("Kit Room saving failed: " + error.getMessage());
         player.sendMessage(RiftPvpPlugin.colors("&cCould not save Kit Room preset; previous preset retained."));
         return false;
      }
   }

   private void close(Player player, Session session) {
      if (active.remove(session.id) != session) return;
      boolean saved = saveCurrent(player, session);
      restore(player, session.original);
      if (saved) {
         File file = escrowFile(session.id);
         if (!file.delete() && file.exists()) plugin.getLogger().warning("Unable to delete Kit Room escrow " + file);
      } else {
         // Escrow remains on disk for crash recovery or staff-assisted restoration.
         player.sendMessage(RiftPvpPlugin.colors("&eKit Room inventory was restored; failed save was not applied."));
      }
      if (session.back && player.isOnline() && plugin.isEnabled()) {
         Bukkit.getScheduler().runTask(plugin, () -> plugin.layouts().open(player, session.kit));
      }
   }

   /** Saved legacy kits may contain extra XP bottles. The editor should open
    * with the actual capped amount players would receive, not an invalid base. */
   private ItemStack[] defaultKitItems(String kit) {
      ItemStack[] base = plugin.kits().originalSlots(kit);
      if (base != null && plugin.kits().isXpBottleCapEnabled(kit)) {
         KitManager.limitXpBottles(base, 64);
      }
      return base;
   }

   private boolean valid(String kit, ItemStack[] draft) {
      if (draft == null || draft.length != CONTENT_SLOTS) return false;
      if (plugin.kits().isXpBottleCapEnabled(kit) && KitManager.countXpBottles(draft) > 64) return false;
      for (ItemStack item : draft) if (!allowed(kit, item)) return false;
      return true;
   }

   private boolean allowed(String kit, ItemStack item) {
      if (item == null || item.getType().isAir()) return true;
      if (item.getAmount() <= 0 || item.getAmount() > item.getMaxStackSize()) return false;
      ItemStack[] origin = plugin.kits().originalSlots(kit);
      if (origin == null) return false;
      if ("htk".equalsIgnoreCase(kit) && item.getType() == Material.END_STONE && !item.hasItemMeta()) return true;
      for (ItemStack permitted : origin) if (permitted != null && item.isSimilar(permitted)) return true;
      return false;
   }

   private YamlConfiguration data(UUID id) {
      return loaded.computeIfAbsent(id, key -> YamlConfiguration.loadConfiguration(new File(layoutsDir, key + ".yml")));
   }

   private void saveData(UUID id, YamlConfiguration config) throws IOException {
      atomicWrite(new File(layoutsDir, id + ".yml"), config.saveToString());
   }

   private File escrowFile(UUID id) { return new File(escrowDir, id + ".yml"); }

   private void saveEscrow(UUID id, ItemStack[] items) throws IOException {
      YamlConfiguration data = new YamlConfiguration();
      data.set("items", Arrays.asList(copy(items)));
      atomicWrite(escrowFile(id), data.saveToString());
   }

   private static void atomicWrite(File file, String content) throws IOException {
      File temp = new File(file.getParentFile(), file.getName() + ".new");
      Files.writeString(temp.toPath(), content, StandardCharsets.UTF_8);
      try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException error) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
   }

   private static ItemStack[] readItems(YamlConfiguration source, String path) {
      List<?> values = source.getList(path);
      if (values == null || values.size() != CONTENT_SLOTS) return null;
      ItemStack[] items = new ItemStack[CONTENT_SLOTS];
      for (int i = 0; i < CONTENT_SLOTS; i++) {
         Object obj = values.get(i);
         if (obj != null && !(obj instanceof ItemStack)) return null;
         items[i] = obj == null ? null : ((ItemStack) obj).clone();
      }
      return items;
   }

   private static ItemStack[] copy(ItemStack[] items) {
      ItemStack[] result = new ItemStack[CONTENT_SLOTS];
      for (int i = 0; i < CONTENT_SLOTS; i++) result[i] = items[i] == null ? null : items[i].clone();
      return result;
   }

   private static ItemStack[] snapshot(Player player) {
      PlayerInventory inv = player.getInventory();
      ItemStack[] items = new ItemStack[CONTENT_SLOTS];
      ItemStack[] storage = inv.getStorageContents();
      for (int i = 0; i < 36; i++) items[i] = i < storage.length && storage[i] != null ? storage[i].clone() : null;
      items[36] = cloneItem(inv.getHelmet());
      items[37] = cloneItem(inv.getChestplate());
      items[38] = cloneItem(inv.getLeggings());
      items[39] = cloneItem(inv.getBoots());
      items[40] = cloneItem(inv.getItemInOffHand());
      return items;
   }

   private static ItemStack cloneItem(ItemStack item) { return item == null ? null : item.clone(); }

   private static boolean empty(ItemStack item) { return item == null || item.getType().isAir(); }

   private static void restore(Player player, ItemStack[] draft) {
      if (draft == null || draft.length != CONTENT_SLOTS) return;
      PlayerInventory inv = player.getInventory();
      inv.setStorageContents(Arrays.copyOf(copy(draft), 36));
      inv.setHelmet(cloneItem(draft[36]));
      inv.setChestplate(cloneItem(draft[37]));
      inv.setLeggings(cloneItem(draft[38]));
      inv.setBoots(cloneItem(draft[39]));
      inv.setItemInOffHand(cloneItem(draft[40]));
      player.setItemOnCursor(null);
      player.updateInventory();
   }

   private static final class RoomHolder implements InventoryHolder {
      Inventory inventory;
      @Override public Inventory getInventory() { return inventory; }
   }

   private static final class Session {
      final UUID id;
      final String kit;
      int preset;
      final ItemStack[] original;
      final RoomHolder holder;
      final List<ItemStack> catalogue;
      boolean back;
      Session(UUID id, String kit, int preset, ItemStack[] original, RoomHolder holder, List<ItemStack> catalogue) {
         this.id = id; this.kit = kit; this.preset = preset; this.original = original; this.holder = holder; this.catalogue = catalogue;
      }
   }
}
