package dev.riftpvp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Per-observer player visibility in the lobby. Never hides players outside the lobby. */
final class LobbyVisibility implements Listener {
   private enum Mode { EVERYONE, PARTY, HIDDEN }
   private final RiftPvpPlugin plugin;
   private final File file;
   private final NamespacedKey key;
   private final Map<UUID, Mode> choices = new HashMap<>();

   LobbyVisibility(RiftPvpPlugin plugin) {
      this.plugin = plugin;
      this.file = new File(plugin.getDataFolder(), "lobby-visibility.yml");
      this.key = new NamespacedKey(plugin, "lobby_visibility");
      YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
      if (data.getConfigurationSection("players") != null) {
         for (String id : data.getConfigurationSection("players").getKeys(false)) {
            try { choices.put(UUID.fromString(id), Mode.valueOf(data.getString("players." + id, "EVERYONE"))); }
            catch (IllegalArgumentException ignored) { }
         }
      }
   }

   void tick() {
      if (!plugin.getConfig().getBoolean("lobby.visibility-enabled", true)) {
         for (Player viewer : Bukkit.getOnlinePlayers()) for (Player target : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(target)) viewer.showPlayer(plugin, target);
         }
         return;
      }
      for (Player viewer : Bukkit.getOnlinePlayers()) {
         boolean lobby = plugin.inLobbyItems(viewer.getLocation());
         if (!plugin.kitRoom().editing(viewer)) {
            removeItem(viewer); // Visibility is now accessed through the inventory shortcut / Rift Menu.
         }
         Mode mode = choices.getOrDefault(viewer.getUniqueId(), Mode.EVERYONE);
         for (Player target : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target)) continue;
            boolean hidden = lobby && plugin.inLobbyItems(target.getLocation())
               && (mode == Mode.HIDDEN || mode == Mode.PARTY && !plugin.practice().inSameParty(viewer, target));
            if (hidden) viewer.hidePlayer(plugin, target); else viewer.showPlayer(plugin, target);
         }
      }
   }

   private ItemStack button(Mode mode) {
      ItemStack item = new ItemStack(Material.SPYGLASS);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(RiftPvpPlugin.colors("&dVisibility: &f" + switch (mode) {
            case EVERYONE -> "Everyone";
            case PARTY -> "Party only";
            case HIDDEN -> "Hidden";
         }));
         meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
         item.setItemMeta(meta);
      }
      return item;
   }

   private boolean ours(ItemStack item) {
      return item != null && item.hasItemMeta()
         && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
   }

   private void giveItem(Player player) {
      int found = -1;
      for (int i = 0; i < 36; i++) if (ours(player.getInventory().getItem(i))) { found = i; break; }
      int slot = found >= 0 ? found : 5;
      ItemStack previous = player.getInventory().getItem(slot);
      if (previous == null || previous.getType().isAir() || ours(previous)) {
         Mode mode = choices.getOrDefault(player.getUniqueId(), Mode.EVERYONE);
         if (!ours(previous) || !previous.isSimilar(button(mode))) player.getInventory().setItem(slot, button(mode));
      }
   }

   void removeHotbarItem(Player player) { removeItem(player); }

   private void removeItem(Player player) {
      for (int i = 0; i < 36; i++) if (ours(player.getInventory().getItem(i))) player.getInventory().setItem(i, null);
      if (ours(player.getInventory().getItemInOffHand())) player.getInventory().setItemInOffHand(null);
      if (ours(player.getItemOnCursor())) player.setItemOnCursor(null);
   }

   boolean command(Player player, String[] args) {
      if (!plugin.getConfig().getBoolean("lobby.visibility-enabled", true)) {
         player.sendMessage("§cLobby player visibility controls are disabled."); return true;
      }
      if (!plugin.inLobbyItems(player.getLocation())) {
         player.sendMessage(RiftPvpPlugin.colors("&cVisibility can be changed from the lobby."));
         return true;
      }
      Mode chosen;
      if (args.length == 0) {
         Mode old = choices.getOrDefault(player.getUniqueId(), Mode.EVERYONE);
         chosen = Mode.values()[(old.ordinal() + 1) % 3];
      } else {
         chosen = switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "everyone", "all" -> Mode.EVERYONE;
            case "party" -> Mode.PARTY;
            case "hidden", "none" -> Mode.HIDDEN;
            default -> null;
         };
      }
      if (chosen == null) { player.sendMessage(RiftPvpPlugin.colors("&cUsage: /visibility [everyone|party|hidden]")); return true; }
      choices.put(player.getUniqueId(), chosen);
      save();
      tick();
      player.sendMessage(RiftPvpPlugin.colors("&dLobby visibility &7» &f" + chosen.name().toLowerCase(java.util.Locale.ROOT)));
      return true;
   }

   private void save() {
      YamlConfiguration data = new YamlConfiguration();
      choices.forEach((id, mode) -> data.set("players." + id, mode.name()));
      File temp = new File(file.getParentFile(), file.getName() + ".new");
      try {
         Files.writeString(temp.toPath(), data.saveToString(), StandardCharsets.UTF_8);
         try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
         catch (AtomicMoveNotSupportedException e) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
      } catch (IOException e) { plugin.getLogger().warning("Could not save lobby visibility: " + e.getMessage()); }
   }

   @EventHandler(priority = EventPriority.HIGHEST) public void onInteract(PlayerInteractEvent event) {
      if (event.getItem() == null || !ours(event.getItem())) return;
      event.setCancelled(true);
      command(event.getPlayer(), new String[0]);
   }

   @EventHandler(priority = EventPriority.HIGHEST) public void onDrop(PlayerDropItemEvent event) {
      if (ours(event.getItemDrop().getItemStack())) event.setCancelled(true);
   }

   @EventHandler(priority = EventPriority.HIGHEST) public void onClick(InventoryClickEvent event) {
      if (ours(event.getCurrentItem()) || ours(event.getCursor())) event.setCancelled(true);
   }

   @EventHandler public void onQuit(PlayerQuitEvent event) {
      removeItem(event.getPlayer());
      for (Player viewer : Bukkit.getOnlinePlayers()) {
         viewer.showPlayer(plugin, event.getPlayer());
         event.getPlayer().showPlayer(plugin, viewer);
      }
   }

   void close() {
      for (Player viewer : Bukkit.getOnlinePlayers()) {
         removeItem(viewer);
         for (Player other : Bukkit.getOnlinePlayers()) if (!viewer.equals(other)) viewer.showPlayer(plugin, other);
      }
      save();
   }
}
