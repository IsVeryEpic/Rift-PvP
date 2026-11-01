package dev.riftpvp;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

/** A crash/restart safety net for players who were wearing temporary fight kits. */
final class MatchRecovery implements Listener {
   private final RiftPvpPlugin plugin;
   private final File file;
   private final Set<UUID> active = new HashSet<>();

   MatchRecovery(RiftPvpPlugin plugin) {
      this.plugin = plugin;
      this.file = new File(plugin.getDataFolder(), "active-match-recovery.yml");
      for (String value : YamlConfiguration.loadConfiguration(file).getStringList("players")) {
         try { active.add(UUID.fromString(value)); } catch (IllegalArgumentException ignored) { }
      }
   }

   void started(UUID first, UUID second) {
      active.add(first);
      active.add(second);
      save();
   }

   void finished(UUID first, UUID second) {
      boolean changed = active.remove(first) | active.remove(second);
      if (changed) save();
   }

   private void save() {
      YamlConfiguration data = new YamlConfiguration();
      data.set("players", active.stream().map(UUID::toString).sorted().toList());
      try { data.save(file); }
      catch (IOException ex) { plugin.getLogger().warning("Failed to save match recovery: " + ex.getMessage()); }
   }

   @EventHandler public void onJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      if (active.contains(player.getUniqueId()) && !plugin.matchReconnect().pending(player.getUniqueId())) {
         Bukkit.getScheduler().runTaskLater(plugin, () -> recover(player), 2L);
      }
   }

   /** Recover a specific fight participant; never move players in unrelated active matches. */
   void recoverSoon(Player player) {
      if (!active.contains(player.getUniqueId())) return;
      Bukkit.getScheduler().runTaskLater(plugin, () -> recover(player), 2L);
   }

   void recoverOnline() {
      for (Player player : Bukkit.getOnlinePlayers()) if (active.contains(player.getUniqueId())) {
         Bukkit.getScheduler().runTaskLater(plugin, () -> recover(player), 2L);
      }
   }

   private void recover(Player player) {
      if (!player.isOnline() || !active.contains(player.getUniqueId()) || plugin.matchReconnect().pending(player.getUniqueId())) return;
      World world = Bukkit.getWorld(plugin.getConfig().getString("lobby.world", ""));
      if (world == null) {
         plugin.getLogger().warning("Could not recover " + player.getName() + " - lobby world not available.");
         return;
      }
      Location location = new Location(world, plugin.getConfig().getDouble("lobby.x"),
         plugin.getConfig().getDouble("lobby.y"), plugin.getConfig().getDouble("lobby.z"),
         (float)plugin.getConfig().getDouble("lobby.yaw"), (float)plugin.getConfig().getDouble("lobby.pitch"));
      player.teleportAsync(location).whenComplete((success, error) -> {
         if (!plugin.isEnabled()) return;
         Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || error != null || !Boolean.TRUE.equals(success)) return;
            plugin.lobbyInventoryReset().clearNow(player);
            active.remove(player.getUniqueId());
            save();
            player.sendMessage("§dRiftPvP §7restored you to the lobby after an interrupted match.");
         });
      });
   }
}
