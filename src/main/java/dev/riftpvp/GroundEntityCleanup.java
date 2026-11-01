package dev.riftpvp;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Trident;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.entity.Wither;
import org.bukkit.scheduler.BukkitTask;

/**
 * Configurable loaded-chunk-only ground cleanup for the three RTP dimensions.
 * Never removes players, NPCs, decorations, pets, named mobs, villagers, bosses
 * or mounted entities. Runs on the server thread for Bukkit entity safety.
 */
final class GroundEntityCleanup {
   private final RiftPvpPlugin plugin;
   private BukkitTask task;
   private BukkitTask warningTask;

   GroundEntityCleanup(RiftPvpPlugin plugin) { this.plugin = plugin; }

   void start() {
      stop();
      // The callback checks enabled each time, so the OP feature GUI can toggle it
      // without scheduling two simultaneous cleanup loops.
      long seconds = Math.max(10L, Math.min(86400L, plugin.getConfig().getLong("cleanup.interval-seconds", 60L)));
      int warningSeconds = Math.max(0, Math.min(30, plugin.getConfig().getInt("cleanup.warning-seconds", 10)));
      if (warningSeconds > 0 && warningSeconds < seconds) warningTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
         if (!plugin.getConfig().getBoolean("cleanup.enabled", true)) return;
         for (World world : Bukkit.getWorlds()) if (worldNames().contains(world.getName()))
            for (Player player : world.getPlayers()) player.sendMessage("§eRiftPvP: Ground cleanup in " + warningSeconds + " seconds. Pick up your items.");
      }, (seconds - warningSeconds) * 20L, seconds * 20L);
      task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
         if (!plugin.getConfig().getBoolean("cleanup.enabled", true)) return;
         Result result = sweep(false);
         if (plugin.getConfig().getBoolean("cleanup.log-results", false))
            plugin.getLogger().info("Ground cleanup: " + result.describe());
      }, seconds * 20L, seconds * 20L);
   }

   void stop() {
      if (task != null) task.cancel();
      if (warningTask != null) warningTask.cancel();
      warningTask = null;
      task = null;
   }

   void command(CommandSender sender, String[] args) {
      // /riftpvp cleanup is available to operators and console only.
      if (sender instanceof Player player && !player.isOp()) {
         sender.sendMessage("§cThis command requires OP.");
         return;
      }
      String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
      switch (action) {
         case "status" -> {
            sender.sendMessage("§dRiftPvP cleanup §7| "
               + (plugin.getConfig().getBoolean("cleanup.enabled", true) ? "§aEnabled" : "§cDisabled"));
            sender.sendMessage("§7Interval: §f" + Math.max(10L, plugin.getConfig().getLong("cleanup.interval-seconds", 60L))
               + "s §7| Loaded worlds: §f" + String.join(", ", worldNames()));
            sender.sendMessage("§7Categories: items=" + plugin.getConfig().getBoolean("cleanup.dropped-items", true)
               + ", XP=" + plugin.getConfig().getBoolean("cleanup.experience-orbs", true)
               + ", ground mobs=" + plugin.getConfig().getBoolean("cleanup.grounded-mobs", false)
               + ", grounded projectiles=" + plugin.getConfig().getBoolean("cleanup.grounded-projectiles", true));
         }
         case "preview", "now" -> {
            boolean preview = "preview".equals(action);
            Result result = sweep(preview);
            sender.sendMessage("§dRiftPvP cleanup §7| " + (preview ? "§ePreview: " : "§aRemoved: ") + "§f" + result.describe());
            if (preview) sender.sendMessage("§7No entities were removed. Use §f/riftpvp cleanup now §7to clear them.");
            else {
               plugin.getLogger().info("Manual ground cleanup by " + sender.getName() + ": " + result.describe());
               plugin.staffAudit().log(sender, "cleanup-now", result.describe());
            }
         }
         default -> sender.sendMessage("§cUsage: /riftpvp cleanup [status|preview|now]");
      }
   }

   private Set<String> worldNames() {
      Set<String> names = new LinkedHashSet<>();
      if (plugin.getConfig().isList("cleanup.worlds")) {
         for (String name : plugin.getConfig().getStringList("cleanup.worlds"))
            if (!name.isBlank()) names.add(name);
      }
      if (names.isEmpty()) {
         for (String dimension : new String[]{"overworld", "nether", "end"}) {
            String fallback = switch (dimension) {
               case "nether" -> "world_nether";
               case "end" -> "world_the_end";
               default -> "world";
            };
            String name = plugin.getConfig().getString("rtp.worlds." + dimension, fallback);
            if (name != null && !name.isBlank()) names.add(name);
         }
      }
      return names;
   }

   private Result sweep(boolean dryRun) {
      int worlds = 0, items = 0, xp = 0, mobs = 0, projectiles = 0;
      long itemMinTicks = Math.max(0L, Math.min(86400L,
         plugin.getConfig().getLong("cleanup.minimum-item-age-seconds", 30L))) * 20L;
      boolean clearItems = plugin.getConfig().getBoolean("cleanup.dropped-items", true);
      boolean clearXp = plugin.getConfig().getBoolean("cleanup.experience-orbs", true);
      boolean clearMobs = plugin.getConfig().getBoolean("cleanup.grounded-mobs", false);
      boolean clearProjectiles = plugin.getConfig().getBoolean("cleanup.grounded-projectiles", true);
      int protectedRadius = Math.max(0, plugin.getConfig().getInt("cleanup.player-protection-radius-blocks", 24));
      int lobbyRadius = Math.max(0, plugin.getConfig().getInt("cleanup.lobby-protection-radius-blocks", 48));
      for (String name : worldNames()) {
         World world = Bukkit.getWorld(name);
         if (world == null) continue; // Never load an unloaded world or its chunks just to clean.
         worlds++;
         for (Entity entity : world.getEntities()) {
            if (!entity.isValid() || protectedEntity(entity) || !entity.isOnGround()) continue;
            if (protectedRadius > 0 && world.getPlayers().stream().anyMatch(p ->
                  p.getLocation().distanceSquared(entity.getLocation()) < protectedRadius * protectedRadius)) continue;
            if (lobbyRadius > 0 && world.getName().equals(plugin.getConfig().getString("lobby.world", ""))) {
               Location lobby = new Location(world, plugin.getConfig().getDouble("lobby.x"),
                  plugin.getConfig().getDouble("lobby.y"), plugin.getConfig().getDouble("lobby.z"));
               if (entity.getLocation().distanceSquared(lobby) < lobbyRadius * lobbyRadius) continue;
            }
            if (entity instanceof Item) {
               if (!clearItems || entity.getTicksLived() < itemMinTicks) continue;
               items++;
            } else if (entity instanceof ExperienceOrb) {
               if (!clearXp) continue;
               xp++;
            } else if (entity instanceof Mob) {
               if (!clearMobs) continue;
               mobs++;
            } else if (entity instanceof Projectile) {
               if (!clearProjectiles) continue;
               projectiles++;
            } else continue;
            if (!dryRun) entity.remove();
         }
      }
      return new Result(worlds, items, xp, mobs, projectiles);
   }

   private boolean protectedEntity(Entity entity) {
      if (entity instanceof Player || entity instanceof Mannequin
          || entity instanceof ArmorStand || entity instanceof Villager
          || entity instanceof WanderingTrader || entity instanceof Wither
          || entity instanceof EnderDragon || entity instanceof Trident) return true;
      if (entity instanceof Tameable tameable && tameable.isTamed()) return true;
      if (entity.getVehicle() != null || !entity.getPassengers().isEmpty()) return true;
      if (entity.getCustomName() != null) return true;
      if (entity.hasMetadata("NPC") || entity.hasMetadata("CitizensNPC") || entity.hasMetadata("npc")) return true;
      for (String tag : entity.getScoreboardTags()) {
         String lower = tag.toLowerCase(Locale.ROOT);
         if (lower.contains("npc") || lower.equals("riftpvp-protected")
             || lower.equals("cleanup-protected") || lower.equals("citizens")) return true;
      }
      return entity.getPersistentDataContainer().getKeys().stream().anyMatch(key -> {
         String namespace = key.getNamespace().toLowerCase(Locale.ROOT);
         String localKey = key.getKey().toLowerCase(Locale.ROOT);
         return namespace.equals("citizens") || localKey.contains("npc")
            || localKey.equals("lobby-npc-id") || localKey.equals("cleanup-protected");
      });
   }

   private record Result(int worlds, int items, int xp, int mobs, int projectiles) {
      String describe() {
         return (items + xp + mobs + projectiles) + " entities across " + worlds + " loaded worlds"
            + " (items=" + items + ", XP=" + xp + ", mobs=" + mobs
            + ", projectiles=" + projectiles + ")";
      }
   }
}
