package dev.riftpvp;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Temporary VIP lobby equipment: never persists outside the protected lobby. */
final class LobbyPerks implements Listener {
   private final RiftPvpPlugin plugin;
   private final NamespacedKey key;

   LobbyPerks(RiftPvpPlugin plugin) {
      this.plugin = plugin;
      this.key = new NamespacedKey(plugin, "lobby_perk");
   }

   void tick() {
      if (!plugin.getConfig().getBoolean("lobby.vip-flight-enabled", true)) {
         for (Player player : Bukkit.getOnlinePlayers()) remove(player);
         return;
      }
      for (Player player : Bukkit.getOnlinePlayers()) {
         removeLegacyWindCharges(player);
         if (plugin.adminFeatures().hidesLobbyItems(player)) { remove(player); continue; }
         if (plugin.kitRoom().editing(player)) continue;
         if (plugin.inLobbyItems(player.getLocation()) && plugin.ranks().hasVipLobbyFlight(player)) replenish(player);
         else remove(player);
      }
   }

   private ItemStack marked(Material material, int count, String name, String tag) {
      ItemStack item = new ItemStack(material, count);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(RiftPvpPlugin.colors(name));
         meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, tag);
         if (meta instanceof Damageable && material == Material.ELYTRA) meta.setUnbreakable(true);
         item.setItemMeta(meta);
      }
      return item;
   }

   private boolean ours(ItemStack item) {
      return item != null && item.hasItemMeta()
         && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING);
   }

   private void replenish(Player player) {
      PlayerInventory inventory = player.getInventory();
      // Never overwrite other armour, including a player's own chestplate.
      ItemStack chest = inventory.getChestplate();
      if (chest == null || chest.getType().isAir() || ours(chest)) {
         inventory.setChestplate(marked(Material.ELYTRA, 1, "&d&lRift Wings &7(VIP Lobby)", "wings"));
      }
      supply(inventory, Material.FIREWORK_ROCKET, 64, 8, "&dInfinite Flight Rockets", "rocket");
   }

   private void supply(PlayerInventory inventory, Material material, int count, int preferred, String name, String tag) {
      ItemStack[] storage = inventory.getStorageContents();
      int existing = -1;
      for (int i = 0; i < storage.length; i++) {
         if (ours(storage[i]) && material == storage[i].getType()) { existing = i; break; }
      }
      int destination = (storage[preferred] == null || storage[preferred].getType().isAir()) ? preferred : -1;
      if (destination < 0 && existing != preferred) {
         for (int i = 9; i < storage.length; i++) {
            if (storage[i] == null || storage[i].getType().isAir()) { destination = i; break; }
         }
      }
      if (existing >= 0) {
         // Relocate only when a slot is free. Preserve the VIP item otherwise.
         if (existing != destination && destination >= 0) {
            inventory.setItem(existing, null);
            existing = destination;
         }
         inventory.setItem(existing, marked(material, count, name, tag));
      } else if (destination >= 0) {
         inventory.setItem(destination, marked(material, count, name, tag));
      }
   }

   // Clean up marked charges from older builds without touching legitimate
   // unmarked Wind Charges belonging to a player's ordinary inventory.
   private void removeLegacyWindCharges(Player player) {
      PlayerInventory inv = player.getInventory();
      for (int i = 0; i < inv.getStorageContents().length; i++) {
         ItemStack stack = inv.getItem(i);
         if (stack != null && stack.getType() == Material.WIND_CHARGE && ours(stack)) inv.setItem(i, null);
      }
      ItemStack off = inv.getItemInOffHand();
      if (off != null && off.getType() == Material.WIND_CHARGE && ours(off)) inv.setItemInOffHand(null);
      ItemStack cursor = player.getItemOnCursor();
      if (cursor.getType() == Material.WIND_CHARGE && ours(cursor)) player.setItemOnCursor(null);
   }

   void remove(Player player) {
      PlayerInventory inventory = player.getInventory();
      ItemStack[] storage = inventory.getStorageContents();
      for (int i = 0; i < storage.length; i++) if (ours(storage[i])) storage[i] = null;
      inventory.setStorageContents(storage);
      ItemStack[] armour = inventory.getArmorContents();
      boolean changed = false;
      for (int i = 0; i < armour.length; i++) if (ours(armour[i])) { armour[i] = null; changed = true; }
      if (changed) inventory.setArmorContents(armour);
      if (ours(inventory.getItemInOffHand())) inventory.setItemInOffHand(null);
      if (ours(player.getItemOnCursor())) player.setItemOnCursor(null);
   }

   @EventHandler public void onJoin(PlayerJoinEvent event) {
      Bukkit.getScheduler().runTaskLater(plugin, () -> { if (event.getPlayer().isOnline()) tick(); }, 20L);
   }

   @EventHandler public void onQuit(PlayerQuitEvent event) { remove(event.getPlayer()); }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onTeleport(PlayerTeleportEvent event) {
      if (plugin.inLobbyItems(event.getPlayer().getLocation()) && !plugin.inLobbyItems(event.getTo())) remove(event.getPlayer());
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
   public void onDrop(PlayerDropItemEvent event) {
      if (ours(event.getItemDrop().getItemStack())) event.setCancelled(true);
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
   public void onClick(InventoryClickEvent event) {
      if (!(event.getWhoClicked() instanceof Player player)) return;
      // Crafting inventories are NOT exempt: temporary rockets and wings must
      // not be placed into a grid or transformed into unmarked items.
      if (ours(event.getCursor()) || ours(event.getCurrentItem())) {
         // Do not move temporary lobby items into containers/menus or through shift-click.
         event.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
   public void onDrag(InventoryDragEvent event) {
      if (ours(event.getOldCursor()) && event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
         event.setCancelled(true);
      }
   }
}
