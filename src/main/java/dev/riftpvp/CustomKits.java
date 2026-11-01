package dev.riftpvp;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Six persistent player-owned, immutable-at-match-start loadouts.
 * A custom kit can only be captured as a SUBSET of a server-issued kit, never
 * from a creative inventory, an unrelated Survival inventory or lobby perks. */
final class CustomKits implements Listener {
   private final RiftPvpPlugin plugin;
   private final File directory;
   private final Map<UUID, Integer> hosts = new LinkedHashMap<>();
   private final Map<UUID, String> hostModes = new HashMap<>();
   private final Map<UUID, Set<UUID>> invited = new HashMap<>();
   private final Map<UUID, UUID> directJoin = new HashMap<>();
   private final Set<UUID> guests = new LinkedHashSet<>();
   private final Map<UUID, ItemStack[]> matchEquipment = new HashMap<>();
   private final Map<UUID, String> matchSource = new HashMap<>();
   private final Map<UUID, MenuHolder> menus = new HashMap<>();

   CustomKits(RiftPvpPlugin plugin) {
      this.plugin = plugin;
      this.directory = new File(plugin.getDataFolder(), "custom-kits");
      if (!directory.isDirectory() && !directory.mkdirs())
         plugin.getLogger().warning("Could not create custom-kits folder");
   }

   static boolean isCustom(String kit) { return kit != null && kit.startsWith("custom:"); }

   /** Server-issued reference; the owner UUID prevents impersonation or filename traversal. */
   static String id(UUID owner, int slot) { return "custom:" + owner + ":" + slot; }

   private record Ref(UUID owner, int slot) {}
   private Ref parse(String token) {
      if (!isCustom(token)) return null;
      String[] fields = token.split(":", -1);
      if (fields.length != 3) return null;
      try {
         int slot = Integer.parseInt(fields[2]);
         return slot >= 1 && slot <= 6 ? new Ref(UUID.fromString(fields[1]), slot) : null;
      } catch (IllegalArgumentException ignored) { return null; }
   }

   boolean exists(String token) {
      Ref ref = parse(token);
      return ref != null && load(ref.owner, ref.slot) != null;
   }

   boolean allowedInCustomMode(String token) {
      Ref ref = parse(token);
      if (ref == null) return false;
      Saved saved = load(ref.owner, ref.slot);
      return saved != null && plugin.kitModes().allowed(saved.source, "custom");
   }
   void removeHostsForSource(String source) {
      for (UUID id : new ArrayList<>(hosts.keySet())) {
         Integer slot = hosts.get(id);
         if (slot == null) continue;
         Saved saved = load(id, slot);
         if (saved != null && source.equals(saved.source)) {
            removeWaiting(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage("§cYour hosted custom kit is disabled for matchmaking by staff.");
         }
      }
   }

   String displayName(String token) {
      Ref ref = parse(token);
      if (ref == null) return "Unknown Custom Kit";
      String name = Bukkit.getOfflinePlayer(ref.owner).getName();
      return (name == null ? "Player" : name) + "'s Custom #" + ref.slot;
   }

   /** Called only by the match pipeline, and stores identical frozen gear for both fighters. */
   boolean prepare(Player a, Player b, String token) {
      Ref ref = parse(token);
      if (ref == null || !Set.of(a.getUniqueId(), b.getUniqueId()).contains(ref.owner)) return false;
      Saved saved = load(ref.owner, ref.slot);
      if (saved == null || !plugin.kitModes().allowed(saved.source, "custom")) return false;
      ItemStack[] items = duplicate(saved.items);
      if (plugin.kits().isXpBottleCapEnabled(saved.source)) KitManager.limitXpBottles(items, 64);
      matchEquipment.put(a.getUniqueId(), duplicate(items));
      matchEquipment.put(b.getUniqueId(), duplicate(items));
      matchSource.put(a.getUniqueId(), saved.source);
      matchSource.put(b.getUniqueId(), saved.source);
      return true;
   }

   void release(UUID one, UUID two) {
      matchEquipment.remove(one);
      matchSource.remove(one);
      if (two != null) {
         matchEquipment.remove(two);
         matchSource.remove(two);
      }
   }

   boolean equip(Player player, String token) {
      if (!isCustom(token)) return false;
      ItemStack[] items = matchEquipment.get(player.getUniqueId());
      String source = matchSource.get(player.getUniqueId());
      if (items == null || source == null) return false;
      // Match snapshots are frozen, but XP caps might have been tightened by staff.
      ItemStack[] applied = duplicate(items);
      if (plugin.kits().isXpBottleCapEnabled(source)) KitManager.limitXpBottles(applied, 64);
      PlayerInventory inv = player.getInventory();
      inv.clear();
      inv.setStorageContents(Arrays.copyOf(applied, 36));
      inv.setHelmet(applied[36]); inv.setChestplate(applied[37]);
      inv.setLeggings(applied[38]); inv.setBoots(applied[39]);
      inv.setItemInOffHand(applied[40]);
      player.setItemOnCursor(null);
      plugin.kits().clearEquippedKit(player);
      plugin.heal(player);
      player.updateInventory();
      return true;
   }

   private record Saved(String source, ItemStack[] items) {}
   private File file(UUID player) { return new File(directory, player + ".yml"); }

   private Saved load(UUID owner, int slot) {
      File f = file(owner);
      if (!f.isFile()) return null;
      YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
      String path = "slots." + slot + ".";
      String source = yaml.getString(path + "source", "");
      if (!KitManager.safeId(source) || !plugin.kits().hasKit(source)) return null;
      ItemStack[] result = new ItemStack[41];
      for (int i = 0; i < 41; i++) {
         Object value = yaml.get(path + "items." + i);
         if (value != null && !(value instanceof ItemStack)) return null;
         result[i] = value == null ? null : ((ItemStack)value).clone();
         if (result[i] != null && (result[i].getAmount() < 1 || result[i].getAmount() > result[i].getMaxStackSize())) return null;
      }
      // Treat serialized YAML as untrusted. Rebuild the exact loadout from the current trusted kit.
      ItemStack[] verified = validateAndRebuild(source, result);
      return verified == null ? null : new Saved(source, verified);
   }

   private static ItemStack[] duplicate(ItemStack[] original) {
      ItemStack[] result = new ItemStack[41];
      for (int i = 0; i < 41; i++)
         result[i] = original != null && i < original.length && original[i] != null ? original[i].clone() : null;
      return result;
   }

   private ItemStack[] capture(Player player) {
      PlayerInventory inv = player.getInventory();
      ItemStack[] all = new ItemStack[41];
      for (int i = 0; i < 36; i++) all[i] = copy(inv.getStorageContents()[i]);
      all[36] = copy(inv.getHelmet()); all[37] = copy(inv.getChestplate());
      all[38] = copy(inv.getLeggings()); all[39] = copy(inv.getBoots());
      all[40] = copy(inv.getItemInOffHand());
      return all;
   }

   private static ItemStack copy(ItemStack item) {
      return item == null || item.getType().isAir() ? null : item.clone();
   }

   /** Ignore wear and purely cosmetic trims; preserve enchants, potions and NBT. */
   private static ItemStack comparable(ItemStack item) {
      ItemStack copy = item.clone();
      copy.setAmount(1);
      ItemMeta meta = copy.getItemMeta();
      if (meta instanceof Damageable damage) damage.setDamage(0);
      if (meta instanceof ArmorMeta armor) armor.setTrim(null);
      if (meta != null) copy.setItemMeta(meta);
      return copy;
   }

   /** Rebuild every saved item FROM trusted server-kit templates, with player-chosen slots/quantity. */
   private ItemStack[] validateAndRebuild(String source, ItemStack[] proposed) {
      ItemStack[] original = plugin.kits().originalSlots(source);
      if (original == null) return null;
      ItemStack[] remaining = duplicate(original);
      ItemStack[] output = new ItemStack[41];
      if ("htk".equalsIgnoreCase(source) && plugin.kits().isKitRoomEnabled(source)) {
         // HTK's explicitly enabled catalogue includes up to 64 End Stone.
         for (int i = 0; i < remaining.length; i++) {
            if (remaining[i] == null) { remaining[i] = new ItemStack(Material.END_STONE, 64); break; }
         }
      }
      for (int i = 0; i < 41; i++) {
         ItemStack wanted = proposed[i];
         if (wanted == null) continue;
         if (!CustomKitSecurity.allowed(plugin, wanted)) return null;
         if (wanted.getAmount() < 1 || wanted.getAmount() > wanted.getMaxStackSize()) return null;
         boolean found = false;
         for (int j = 0; j < 41; j++) {
            ItemStack allowed = remaining[j];
            if (allowed == null || !CustomKitSecurity.allowed(plugin, allowed)
                  || !comparable(wanted).isSimilar(comparable(allowed))) continue;
            if (allowed.getAmount() < wanted.getAmount()) continue;
            ItemStack safe = allowed.clone();
            safe.setAmount(wanted.getAmount());
            output[i] = safe;
            int rest = allowed.getAmount() - wanted.getAmount();
            remaining[j] = rest == 0 ? null : allowed.clone();
            if (rest > 0) remaining[j].setAmount(rest);
            found = true;
            break;
         }
         if (!found) return null;
      }
      if (plugin.kits().isXpBottleCapEnabled(source) && KitManager.countXpBottles(output) > 64) return null;
      return output;
   }

   private void save(Player player, int slot) {
      if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
         player.sendMessage("§cOnly Survival/Adventure kit inventories can be saved."); return;
      }
      if (plugin.inLobby(player.getLocation()) || plugin.combat().tagged(player)
           || plugin.matchQueue().busy(player) || isWaiting(player.getUniqueId())
           || plugin.matchQueue().isQueued(player.getUniqueId()) || plugin.practice().isRankedQueued(player)) {
         player.sendMessage("§cEquip a server kit outside the lobby and leave combat/queues before saving."); return;
      }
      String source = plugin.kits().equippedKitId(player);
      if (source == null || !plugin.kits().hasKit(source)) {
         player.sendMessage("§cUse /kit <server-kit> outside the lobby, arrange its inventory, then save."); return;
      }
      ItemStack[] approved = validateAndRebuild(source, capture(player));
      if (approved == null) {
         player.sendMessage("§cCannot save: your inventory contains more items or different items than the server kit permits."); return;
      }
      File target = file(player.getUniqueId());
      YamlConfiguration yaml = YamlConfiguration.loadConfiguration(target);
      String path = "slots." + slot + ".";
      yaml.set(path + "source", source);
      for (int i = 0; i < 41; i++) yaml.set(path + "items." + i, approved[i]);
      try {
         File staged = new File(directory, player.getUniqueId() + ".tmp");
         yaml.save(staged);
         try { Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
         catch (AtomicMoveNotSupportedException ignored) { Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
         player.sendMessage("§aSaved custom kit §d#" + slot + "§a from §f" + plugin.kits().name(source) + "§a.");
      } catch (IOException ex) {
         plugin.getLogger().warning("Cannot save custom kit: " + ex.getMessage());
         player.sendMessage("§cSave failed; your previous custom kit is unchanged.");
      }
   }

   private void delete(Player player, int slot) {
      if (isWaiting(player.getUniqueId()) || plugin.matchQueue().busy(player)) {
         player.sendMessage("§cLeave your queue/match before deleting a saved kit."); return;
      }
      File f = file(player.getUniqueId());
      YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
      yaml.set("slots." + slot, null);
      try { yaml.save(f); player.sendMessage("§aDeleted custom kit #" + slot + "."); }
      catch (IOException ex) { player.sendMessage("§cCould not delete the kit."); }
   }

   private boolean available(Player player) {
      if (!plugin.combat().mayUse(player, "queue") || plugin.matchQueue().busy(player)
            || plugin.matchQueue().isQueued(player.getUniqueId()) || plugin.practice().isRankedQueued(player)
            || plugin.kitRoom().editing(player)) {
         player.sendMessage("§cLeave other queues, combat or an active fight before custom matchmaking."); return false;
      }
      return true;
   }

   void removeWaiting(UUID player) {
      hosts.remove(player); hostModes.remove(player); invited.remove(player);
      for (UUID guest : new ArrayList<>(directJoin.keySet())) {
         if (player.equals(directJoin.get(guest))) {
            directJoin.remove(guest);
            guests.remove(guest);
         }
      }
      guests.remove(player); directJoin.remove(player);
   }
   void clearWaitingOnly() {
      for (UUID id : new HashSet<>(hosts.keySet())) {
         Player p = Bukkit.getPlayer(id);
         if (p != null) p.sendMessage("§eCustom matchmaking paused for server maintenance.");
      }
      hosts.clear(); hostModes.clear(); invited.clear(); directJoin.clear(); guests.clear();
   }
   void close() {
      hosts.clear(); hostModes.clear(); invited.clear(); directJoin.clear(); guests.clear();
      matchEquipment.clear(); matchSource.clear(); menus.clear();
   }
   boolean isWaiting(UUID player) { return hosts.containsKey(player) || guests.contains(player); }

   private void host(Player player, int slot) { host(player, slot, "public"); }

   private void host(Player player, int slot, String mode) {
      if (!Set.of("public", "party", "private").contains(mode)) {
         player.sendMessage("§cHost mode: public, party or private"); return;
      }
      if (!exists(id(player.getUniqueId(), slot))) {
         player.sendMessage("§cSave custom kit #" + slot + " first using /customkit save " + slot); return;
      }
      if (!available(player)) return;
      if (!allowedInCustomMode(id(player.getUniqueId(), slot)) || !plugin.maintenance().allowNewMatch(player)) {
         player.sendMessage("§cCustom matches are disabled for that kit."); return;
      }
      removeWaiting(player.getUniqueId());
      hosts.put(player.getUniqueId(), slot);
      hostModes.put(player.getUniqueId(), mode);
      player.sendMessage("§aHosting custom kit §d#" + slot + "§a (" + mode + "). Use /customkit invite <player> or /customkit leave.");
      pair();
   }

   private void queue(Player player) {
      if (!available(player)) return;
      removeWaiting(player.getUniqueId());
      guests.add(player.getUniqueId());
      if (plugin.playerPreferences().allows(player, "queue-notifications"))
         player.sendMessage("§aSearching for a random player's custom kit. §7/customkit leave to cancel.");
      pair();
   }

   void retryEligibleHosts() { pair(); }

   private void pair() {
      if (plugin.maintenance().isActive()) return;
      List<UUID> randomizedHosts = new ArrayList<>(hosts.keySet());
      Collections.shuffle(randomizedHosts);
      for (UUID hostId : randomizedHosts) {
         Player host = Bukkit.getPlayer(hostId);
         if (host == null || !host.isOnline() || plugin.matchQueue().busy(host)
               || plugin.combat().tagged(host)) { hosts.remove(hostId); continue; }
         Integer slot = hosts.get(hostId);
         if (slot == null || !exists(id(hostId, slot)) || !allowedInCustomMode(id(hostId, slot))) { hosts.remove(hostId); continue; }
         List<UUID> randomizedGuests = new ArrayList<>(guests);
         Collections.shuffle(randomizedGuests);
         for (UUID guestId : randomizedGuests) {
            if (hostId.equals(guestId)) continue;
            UUID requestedHost = directJoin.get(guestId);
            String mode = hostModes.getOrDefault(hostId, "public");
            if (requestedHost == null && !"public".equals(mode)) continue;
            if (requestedHost != null && !requestedHost.equals(hostId)) continue;
            Player guest = Bukkit.getPlayer(guestId);
            if (guest == null || !guest.isOnline() || plugin.matchQueue().busy(guest)
                  || plugin.combat().tagged(guest)) { guests.remove(guestId); continue; }
            if (requestedHost != null && "private".equals(mode)
                  && !invited.getOrDefault(hostId, Set.of()).contains(guestId)) continue;
            if ("party".equals(mode) && !plugin.practice().inSameParty(host, guest)
                  && !invited.getOrDefault(hostId, Set.of()).contains(guestId)) continue;
            if (!plugin.playerPreferences().pingCompatible(host, guest)) continue;
            if (plugin.matchQueue().challenge(host, guest, id(hostId, slot))) {
               removeWaiting(hostId); removeWaiting(guestId);
               host.sendMessage("§aCustom match found against §f" + guest.getName() + "§a.");
               guest.sendMessage("§aCustom match found! §7Kit: §d" + displayName(id(hostId, slot)));
               break;
            }
         }
      }
   }

   void command(Player player, String[] args) {
      if (args.length == 0) { open(player); return; }
      String verb = args[0].toLowerCase(Locale.ROOT);
      if (verb.equals("list")) {
         player.sendMessage("§d§lYour 6 Custom Kit Slots");
         for (int i = 1; i <= 6; i++) {
            Saved s = load(player.getUniqueId(), i);
            player.sendMessage("§7#" + i + " §8» " + (s == null ? "§8Empty" : "§aSaved §7(" + plugin.kits().name(s.source) + ")"));
         }
         return;
      }
      if (verb.equals("leave")) { removeWaiting(player.getUniqueId()); player.sendMessage("§7Left custom kit matchmaking."); return; }
      if (verb.equals("invite") && args.length == 2) {
         Player target = Bukkit.getPlayerExact(args[1]);
         if (!hosts.containsKey(player.getUniqueId()) || target == null || target.equals(player)) {
            player.sendMessage("§cHost a kit first, then invite an online player."); return;
         }
         invited.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(target.getUniqueId());
         target.sendMessage("§d" + player.getName() + " invited you to their custom match. §f/customkit join " + player.getName());
         player.sendMessage("§aCustom kit invitation sent to " + target.getName());
         return;
      }
      if (verb.equals("join") && args.length == 2) {
         Player owner = Bukkit.getPlayerExact(args[1]);
         if (owner == null || !hosts.containsKey(owner.getUniqueId()) || owner.equals(player)) {
            player.sendMessage("§cThat player is not hosting a custom kit."); return;
         }
         String mode = hostModes.getOrDefault(owner.getUniqueId(), "public");
         if ("private".equals(mode) && !invited.getOrDefault(owner.getUniqueId(), Set.of()).contains(player.getUniqueId())
               || "party".equals(mode) && !plugin.practice().inSameParty(owner, player)
                  && !invited.getOrDefault(owner.getUniqueId(), Set.of()).contains(player.getUniqueId())) {
            player.sendMessage("§cThis custom host is restricted; ask for an invitation."); return;
         }
         if (!available(player)) return;
         removeWaiting(player.getUniqueId());
         guests.add(player.getUniqueId());
         directJoin.put(player.getUniqueId(), owner.getUniqueId());
         player.sendMessage("§aWaiting for custom match with §f" + owner.getName());
         pair();
         return;
      }
      if (verb.equals("host") && args.length == 3) {
         try { int slot = Integer.parseInt(args[1]); if (slot < 1 || slot > 6) { usage(player); return; }
            host(player, slot, args[2].toLowerCase(Locale.ROOT)); }
         catch (NumberFormatException error) { usage(player); }
         return;
      }
      if (verb.equals("queue")) { queue(player); return; }
      if (args.length != 2) { usage(player); return; }
      int slot;
      try { slot = Integer.parseInt(args[1]); }
      catch (NumberFormatException ignored) { usage(player); return; }
      if (slot < 1 || slot > 6) { usage(player); return; }
      switch (verb) {
         case "save" -> save(player, slot);
         case "delete" -> delete(player, slot);
         case "host" -> host(player, slot);
         default -> usage(player);
      }
   }

   private void usage(Player player) {
      player.sendMessage("§d/customkit §7- Open your six custom slots / matchmaking menu");
      player.sendMessage("§d/customkit save <1-6> §7- Save your equipped server kit's layout");
      player.sendMessage("§d/customkit host <1-6> [public|party|private] §7- Host your custom kit");
      player.sendMessage("§d/customkit invite <player> | join <host> §7- Invite or join a host");
      player.sendMessage("§d/customkit queue §7- Join a random custom host");
      player.sendMessage("§d/customkit list | delete <1-6> | leave");
      player.sendMessage("§d/duel <player> custom:<1-6> §7- Duel using your custom kit");
   }

   private static final class MenuHolder implements InventoryHolder {
      Inventory inventory;
      @Override public Inventory getInventory() { return inventory; }
   }

   void open(Player player) {
      MenuHolder holder = new MenuHolder();
      holder.inventory = Bukkit.createInventory(holder, 27, RiftPvpPlugin.colors("&5&lCustom Kit Matchmaking"));
      menus.put(player.getUniqueId(), holder);
      for (int i = 1; i <= 6; i++) {
         Saved s = load(player.getUniqueId(), i);
         holder.inventory.setItem(i + 9, button(s == null ? Material.GRAY_DYE : Material.NETHERITE_SWORD,
            "&dCustom #" + i, s == null ? "&8Empty - /customkit save " + i : "&aClick to host custom #" + i));
      }
      holder.inventory.setItem(21, button(Material.COMPASS, "&aFind Random Custom Host", "&7Click to queue for any player's custom kit"));
      holder.inventory.setItem(23, button(Material.BARRIER, "&cLeave Custom Queue", "&7Click to cancel"));
      holder.inventory.setItem(26, button(Material.BOOK, "&eUsage in Chat", "&7See /customkit help"));
      player.openInventory(holder.inventory);
   }

   private ItemStack button(Material material, String name, String desc) {
      ItemStack item = new ItemStack(material);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(RiftPvpPlugin.colors(name));
         meta.setLore(List.of(RiftPvpPlugin.colors(desc)));
         item.setItemMeta(meta);
      }
      return item;
   }

   @EventHandler public void click(InventoryClickEvent event) {
      if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
      event.setCancelled(true);
      if (!(event.getWhoClicked() instanceof Player player) || menus.get(player.getUniqueId()) != holder) return;
      if (event.getClickedInventory() != holder.inventory) return;
      int slot = event.getRawSlot();
      player.closeInventory();
      Bukkit.getScheduler().runTask(plugin, () -> {
         if (!player.isOnline()) return;
         if (slot >= 10 && slot <= 15) host(player, slot - 9);
         else if (slot == 21) queue(player);
         else if (slot == 23) command(player, new String[]{"leave"});
         else if (slot == 26) usage(player);
      });
   }

   @EventHandler public void drag(InventoryDragEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
   }

   @EventHandler public void quit(PlayerQuitEvent event) {
      removeWaiting(event.getPlayer().getUniqueId());
      menus.remove(event.getPlayer().getUniqueId());
   }
}
