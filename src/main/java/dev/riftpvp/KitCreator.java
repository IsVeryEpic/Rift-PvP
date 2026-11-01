package dev.riftpvp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/** Admin-only void-themed kit creator and kit settings; never exposed in /kiteditor. */
final class KitCreator implements Listener {
   private final RiftPvpPlugin plugin;
   private final Map<UUID, Session> sessions = new HashMap<>();
   private final Map<UUID, DeletionRequest> pendingDelete = new HashMap<>();
   private record DeletionRequest(String kit, long expiresAt) {}

   KitCreator(RiftPvpPlugin plugin) { this.plugin = plugin; }

   void create(Player player, String id) {
      if (!allowed(player)) { player.sendMessage("§cOnly Admins and Founders can create server kits."); return; }
      if (!KitManager.safeId(id)) { player.sendMessage("§cUse /kitcreate <id> (letters, numbers, _ and - only)."); return; }
      if (plugin.getConfig().contains("kits." + id) || plugin.kits().hasKit(id)) {
         player.sendMessage("§cA kit named §f" + id + "§c already exists or was archived. Use §f/kitsettings " + id + "§c or §f/kitrestore " + id + "§c.");
         return;
      }
      ItemStack[] snapshot = capture(player.getInventory());
      ItemStack icon = player.getInventory().getItemInMainHand();
      open(player, id, snapshot, icon == null ? null : icon.clone(), false,
         !"endgame".equalsIgnoreCase(id));
   }

   void settings(Player player, String id) {
      if (!allowed(player)) { player.sendMessage("§cOnly Admins and Founders can edit kit settings."); return; }
      if (!plugin.kits().hasKit(id)) { plugin.message(player, "kit-not-loaded"); return; }
      open(player, id, plugin.kits().originalSlots(id), null, true,
         plugin.kits().isXpBottleCapEnabled(id));
   }

   void delete(Player player, String kit, boolean confirm) {
      if (!allowed(player)) { player.sendMessage("§cOnly Admin, Founder or OP can delete server kits."); return; }
      if (!KitManager.safeId(kit) || !plugin.kits().hasKit(kit)) {
         player.sendMessage("§cThat kit does not exist or is already archived."); return;
      }
      if (plugin.matchQueue().hasActiveMatches() || plugin.practice().hasActiveFights()) {
         player.sendMessage("§cFinish active matches before deleting a kit."); return;
      }
      if (confirm) {
         DeletionRequest request = pendingDelete.remove(player.getUniqueId());
         if (request == null || !request.kit.equals(kit) || System.currentTimeMillis() > request.expiresAt) {
            player.sendMessage("§cNo valid confirmation. Run /kitdelete " + kit + " first."); return;
         }
         // Queue cancellation is performed only after the archived state is saved.
         if (plugin.kits().archiveKit(kit)) {
            plugin.matchQueue().removeKitQueue(kit);
            plugin.practice().removeKitQueues(kit);
            player.sendMessage("§aKit §d" + kit + " §aarchived and removed from active menus and queues.");
            player.sendMessage("§7Saved kit data is preserved; use §f/kitrestore " + kit + "§7 to recover it.");
            plugin.staffAudit().log(player, "kit-delete", kit + " archived (restorable)");
         } else player.sendMessage("§cKit deletion failed; no changes made.");
         return;
      }
      pendingDelete.put(player.getUniqueId(), new DeletionRequest(kit, System.currentTimeMillis() + 30000));
      player.sendMessage("§cDelete kit §f" + kit + "§c? This will disable existing queues and custom kits based on it.");
      player.sendMessage("§eConfirm in 30 seconds: §f/kitdelete " + kit + " confirm");
      player.sendMessage("§7Data is archived, not permanently erased. /kitrestore can recover it.");
   }

   void restore(Player player, String kit) {
      if (!allowed(player)) { player.sendMessage("§cOnly Admin, Founder or OP can restore kits."); return; }
      if (plugin.kits().restoreArchivedKit(kit)) {
         player.sendMessage("§aRestored kit §d" + kit + "§a.");
         plugin.staffAudit().log(player, "kit-restore", kit);
      } else player.sendMessage("§cKit not archived or its saved data cannot be loaded.");
   }

   private boolean allowed(Player player) {
      return plugin.ranks().canManageKits(player);
   }

   private void open(Player player, String id, ItemStack[] contents, ItemStack icon, boolean settingsOnly, boolean capped) {
      Holder holder = new Holder();
      holder.inventory = Bukkit.createInventory(holder, 54,
         RiftPvpPlugin.colors("&5&lVOID FORGE &8| &d" + id));
      Session session = new Session(holder, id, cloneSlots(contents), icon, settingsOnly, capped,
         plugin.kits().isKitRoomEnabled(id));
      sessions.put(player.getUniqueId(), session);
      draw(session);
      player.openInventory(holder.inventory);
      player.sendMessage(RiftPvpPlugin.colors("&5&lRIFT &d» &fThe Void Forge is ready. Toggle XP limits, then confirm."));
   }

   private static ItemStack[] capture(PlayerInventory inventory) {
      ItemStack[] result = new ItemStack[41];
      ItemStack[] storage = inventory.getStorageContents();
      for (int i = 0; i < 36; i++) result[i] = copy(storage[i]);
      result[36] = copy(inventory.getHelmet());
      result[37] = copy(inventory.getChestplate());
      result[38] = copy(inventory.getLeggings());
      result[39] = copy(inventory.getBoots());
      result[40] = copy(inventory.getItemInOffHand());
      return result;
   }

   private static ItemStack[] cloneSlots(ItemStack[] items) {
      ItemStack[] out = new ItemStack[41];
      if (items != null) for (int i = 0; i < Math.min(41, items.length); i++) out[i] = copy(items[i]);
      return out;
   }

   private static ItemStack copy(ItemStack item) {
      return item == null || item.getType().isAir() ? null : item.clone();
   }

   private void draw(Session session) {
      Inventory inv = session.holder.inventory;
      inv.clear();
      ItemStack[] preview = cloneSlots(session.snapshot);
      boolean endgame = "endgame".equalsIgnoreCase(session.id);
      boolean capped = session.xpCapped && !endgame;
      if (capped) KitManager.limitXpBottles(preview, 64);
      for (int i = 0; i < 41; i++) {
         int slot = viewToSlot(i);
         if (slot >= 0) {
            ItemStack item = copy(preview[slot]);
            inv.setItem(i, item == null ? decorated(Material.GRAY_STAINED_GLASS_PANE, "&8Empty Slot") : item);
         }
      }
      for (int i = 41; i < 54; i++) {
         inv.setItem(i, decorated(i % 2 == 0 ? Material.PURPLE_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE, "&8✦ The Void ✦"));
      }
      inv.setItem(45, decorated(endgame ? Material.DRAGON_EGG : (capped ? Material.ENDER_EYE : Material.ECHO_SHARD),
         endgame ? "&d&lEndgame: Unlimited XP" : capped ? "&a&lXP Limit: ON" : "&c&lXP Limit: OFF",
         endgame ? "&7Endgame is always exempt." : "&7Click to toggle the 64-bottle limit.",
         "&7This applies when players equip this kit.",
         "&7The restriction is NOT part of /kiteditor."));
      inv.setItem(46, decorated(session.roomEnabled ? Material.CHEST : Material.BARRIER,
         session.roomEnabled ? "&a&lKit Room: ON" : "&c&lKit Room: OFF",
         "&7Click to enable/disable Kit Room for this kit.",
         "&7Default: Endgame and HTK only."));
      int original = KitManager.countXpBottles(session.snapshot);
      inv.setItem(47, decorated(Material.EXPERIENCE_BOTTLE, "&d&lXP Bottle Preview",
         "&7Original bottles: &f" + original,
         "&7Kit will supply: &b" + (capped ? Math.min(64, original) : original),
         capped ? "&5Extra bottles will not be saved/supplied." : "&5Unlimited for this kit."));
      inv.setItem(49, decorated(Material.CRYING_OBSIDIAN,
         session.settingsOnly ? "&a&lSAVE KIT SETTINGS" : "&a&lCREATE KIT",
         session.settingsOnly ? "&7XP limit and Kit Room toggle will change." : "&7Save kit, XP limit and Kit Room toggle.",
         "&dClick to confirm."));
      inv.setItem(53, decorated(Material.BARRIER, "&c&lCANCEL", "&7Keep your inventory unchanged."));
   }

   private static int viewToSlot(int index) {
      if (index < 27) return index + 9;
      if (index < 36) return index - 27;
      if (index < 41) return index;
      return -1;
   }

   private static ItemStack decorated(Material material, String name, String... lines) {
      ItemStack item = new ItemStack(material);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(RiftPvpPlugin.colors(name));
         List<String> lore = new ArrayList<>();
         for (String line : lines) lore.add(RiftPvpPlugin.colors(line));
         meta.setLore(lore);
         item.setItemMeta(meta);
      }
      return item;
   }

   @EventHandler
   public void click(InventoryClickEvent event) {
      if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
      event.setCancelled(true);
      if (!(event.getWhoClicked() instanceof Player player)) return;
      Session session = sessions.get(player.getUniqueId());
      if (session == null || session.holder != holder || event.getClickedInventory() != holder.inventory) return;
      if (!allowed(player)) { player.closeInventory(); return; }
      int slot = event.getRawSlot();
      if (slot == 45) {
         if ("endgame".equalsIgnoreCase(session.id)) {
            player.sendMessage("§dEndgame always has unlimited XP bottles.");
            return;
         }
         session.xpCapped = !session.xpCapped;
         plugin.sounds().play(player, "npc-click");
         draw(session);
      } else if (slot == 46) {
         session.roomEnabled = !session.roomEnabled;
         plugin.sounds().play(player, "npc-click");
         draw(session);
      } else if (slot == 53) {
         player.closeInventory();
         player.sendMessage("§7Void Forge cancelled. No kit was changed.");
      } else if (slot == 49) {
         try {
            if (session.settingsOnly) {
               plugin.kits().setXpBottleCap(session.id, session.xpCapped);
               plugin.kits().setKitRoomEnabled(session.id, session.roomEnabled);
               plugin.kits().enforceEquippedXpLimits();
               player.sendMessage("§aSaved XP settings for §d" + session.id + "§a.");
            } else {
               if (!plugin.kits().saveCreatedKit(session.id, session.snapshot, session.icon, session.xpCapped)) {
                  player.sendMessage("§cThis kit already exists or its ID is invalid.");
                  return;
               }
               plugin.kits().setKitRoomEnabled(session.id, session.roomEnabled);
               player.sendMessage("§aCreated §d" + session.id + "§a! Available in /kit, /rtpq and /kiteditor.");
            }
            plugin.staffAudit().log(player, session.settingsOnly ? "kit-settings" : "kit-create",
               session.id + " xp-cap=" + session.xpCapped + " kit-room=" + session.roomEnabled);
            sessions.remove(player.getUniqueId());
            player.closeInventory();
            plugin.sounds().play(player, "kit");
         } catch (IOException | RuntimeException exception) {
            plugin.getLogger().warning("Void Forge could not save kit '" + session.id + "': " + exception.getMessage());
            player.sendMessage("§cUnable to save this kit. Check server logs.");
         }
      }
   }

   @EventHandler
   public void drag(InventoryDragEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
   }

   @EventHandler
   public void quit(PlayerQuitEvent event) {
      sessions.remove(event.getPlayer().getUniqueId());
      pendingDelete.remove(event.getPlayer().getUniqueId());
   }

   private static final class Holder implements InventoryHolder {
      private Inventory inventory;
      public Inventory getInventory() { return inventory; }
   }

   private static final class Session {
      private final Holder holder;
      private final String id;
      private final ItemStack[] snapshot;
      private final ItemStack icon;
      private final boolean settingsOnly;
      private boolean xpCapped;
      private boolean roomEnabled;
      private Session(Holder holder, String id, ItemStack[] snapshot, ItemStack icon, boolean settingsOnly, boolean xpCapped, boolean roomEnabled) {
         this.holder = holder;
         this.id = id;
         this.snapshot = snapshot;
         this.icon = icon;
         this.settingsOnly = settingsOnly;
         this.xpCapped = xpCapped;
         this.roomEnabled = roomEnabled;
      }
   }
}
