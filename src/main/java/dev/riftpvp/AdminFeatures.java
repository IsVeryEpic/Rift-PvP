package dev.riftpvp;

import java.util.List;
import java.util.ArrayList;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** OP-only feature switches. Every permission is checked on click, not just when opening. */
final class AdminFeatures implements Listener {
   private final RiftPvpPlugin plugin;
   private static final String[] PATHS = {
      "features.tpa", "features.ranked", "lobby.vip-flight-enabled",
      "features.rekit-alerts", "lobby.visibility-enabled", "cleanup.enabled"
   };
   private static final String[] LABELS = {
      "Player TPA Requests", "Ranked Matchmaking", "VIP Lobby Flight",
      "Nearby Kit Notifications", "Lobby Player Visibility", "Ground Entity Cleanup (60s)"
   };
   private static final int[] SLOTS = {10, 11, 12, 13, 14, 15};
   private static final int BUILDER_SLOT = 16;
   private static final String BUILDER_SETTING = "lobby.builder-hide-items-for";

   AdminFeatures(RiftPvpPlugin plugin) { this.plugin = plugin; }

   /** Per-operator preference: hide only plugin-issued lobby equipment, never building blocks. */
   boolean hidesLobbyItems(Player player) {
      return player.isOp() && plugin.getConfig().getStringList(BUILDER_SETTING)
          .contains(player.getUniqueId().toString());
   }

   private void setHidesLobbyItems(Player player, boolean hide) {
      List<String> hidden = new ArrayList<>(plugin.getConfig().getStringList(BUILDER_SETTING));
      String id = player.getUniqueId().toString();
      hidden.remove(id);
      if (hide) hidden.add(id);
      plugin.getConfig().set(BUILDER_SETTING, hidden);
      plugin.saveConfig();
      if (hide) {
         plugin.lobbyHotbar().remove(player);
         plugin.lobbyVisibility().removeHotbarItem(player);
         plugin.lobbyPerks().remove(player);
      } else {
         plugin.lobbyHotbar().refresh(player);
         plugin.lobbyVisibility().tick();
         plugin.lobbyPerks().tick();
      }
      player.updateInventory();
   }

   void open(Player player) {
      if (!player.isOp()) { player.sendMessage("§cAdmin feature controls require OP."); return; }
      Holder holder = new Holder();
      holder.inventory = Bukkit.createInventory(holder, 27, "§5§lRiftPvP §8| §fFeatures");
      draw(holder.inventory, player);
      player.openInventory(holder.inventory);
   }

   private void draw(Inventory inventory, Player player) {
      inventory.clear();
      for (int i = 0; i < PATHS.length; i++) {
         boolean enabled = plugin.getConfig().getBoolean(PATHS[i], true);
         ItemStack item = new ItemStack(enabled ? Material.LIME_DYE : Material.GRAY_DYE);
         ItemMeta meta = item.getItemMeta();
         if (meta != null) {
            meta.setDisplayName(RiftPvpPlugin.colors("&d" + LABELS[i] + " &8| " + (enabled ? "&aENABLED" : "&cDISABLED")));
            meta.setLore(List.of("§7Click to toggle. Saves immediately."));
            item.setItemMeta(meta);
         }
         inventory.setItem(SLOTS[i], item);
      }
      // This switch affects only the operator who clicks it, not every player's lobby.
      // It remains accessible using /riftpvp toggles even while the hotbar is hidden.
      // Draw it per viewer, not as a global feature flag.
      boolean hidden = hidesLobbyItems(player);
      ItemStack builder = new ItemStack(hidden ? Material.LIME_DYE : Material.GRAY_DYE);
      ItemMeta builderMeta = builder.getItemMeta();
      builderMeta.setDisplayName(RiftPvpPlugin.colors("&dHide My Lobby Items &8| " + (hidden ? "&aON" : "&cOFF")));
      builderMeta.setLore(List.of("§7Hide RiftPvP hotbar items and VIP lobby equipment while building.",
         "§7Only affects you. Building blocks are untouched.",
         "§7Use /riftpvp toggles to reopen this menu."));
      builder.setItemMeta(builderMeta);
      inventory.setItem(BUILDER_SLOT, builder);
   }

   @EventHandler public void click(InventoryClickEvent event) {
      if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
      event.setCancelled(true);
      if (!(event.getWhoClicked() instanceof Player player) || !player.isOp()) return;
      int raw = event.getRawSlot();
      if (raw == BUILDER_SLOT) {
         boolean hide = !hidesLobbyItems(player);
         setHidesLobbyItems(player, hide);
         plugin.staffAudit().log(player, "personal-lobby-items", hide ? "hidden" : "visible");
         draw(holder.inventory, player);
         player.sendMessage("§dYour lobby items: " + (hide ? "§cHidden" : "§aVisible"));
         return;
      }
      for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == raw) {
         String path = PATHS[i];
         boolean value = !plugin.getConfig().getBoolean(path, true);
         plugin.getConfig().set(path, value);
         plugin.saveConfig();
         plugin.staffAudit().log(player, "feature-toggle", path + "=" + value);
         plugin.discord().send("staff-actions", player.getName() + " changed " + path + " to " + value);
         draw(holder.inventory, player);
         player.sendMessage("§d" + LABELS[i] + " §7» " + (value ? "§aEnabled" : "§cDisabled"));
         return;
      }
   }

   @EventHandler public void drag(InventoryDragEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
   }

   private static final class Holder implements InventoryHolder {
      Inventory inventory;
      @Override public Inventory getInventory() { return inventory; }
   }
}
