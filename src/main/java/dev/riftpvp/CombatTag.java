package dev.riftpvp;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class CombatTag implements Listener {
   private final RiftPvpPlugin plugin;
   private final Map<UUID, Long> tags = new HashMap<>();
   private final Map<UUID, Long> landingProtection = new HashMap<>();
   private final Set<UUID> logoutPenalties = new HashSet<>();
   private final File penaltyFile;

   CombatTag(RiftPvpPlugin var1) {
      this.plugin = var1;
      this.penaltyFile = new File(var1.getDataFolder(), "combat-logouts.yml");
      YamlConfiguration var2 = YamlConfiguration.loadConfiguration(this.penaltyFile);

      for (String var4 : var2.getStringList("penalties")) {
         try {
            this.logoutPenalties.add(UUID.fromString(var4));
         } catch (IllegalArgumentException var6) {
         }
      }

      Bukkit.getScheduler().runTaskTimer(var1, this::expireTags, 20L, 20L);
   }

   boolean tagged(Player var1) {
      return this.plugin.getConfig().getBoolean("combat.enabled", true) && this.remaining(var1) > 0;
   }

   int remaining(Player var1) {
      long var2 = this.tags.getOrDefault(var1.getUniqueId(), 0L);
      return Math.max(0, (int)Math.ceil((var2 - System.currentTimeMillis()) / 1000.0));
   }

   boolean mayUse(Player var1, String var2) {
      if (!this.tagged(var1)) {
         return true;
      }

      if (!this.plugin.getConfig().getBoolean("combat.block-" + var2, true)) {
         return true;
      }

      this.plugin.message(var1, "combat-blocked", "seconds", Integer.toString(this.remaining(var1)));
      return false;
   }

   /** Remove protection left by a previous teleport before an instant-start casual fight. */
   void clearArrival(Player player) {
      landingProtection.remove(player.getUniqueId());
   }

   void protectArrival(Player var1) {
      long var2 = Math.max(0, this.plugin.getConfig().getInt("matchmaking.arrival-protection-seconds", 3)) * 1000L;
      this.landingProtection.put(var1.getUniqueId(), System.currentTimeMillis() + var2);
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onProtectedDamage(EntityDamageEvent var1) {
      if (var1.getEntity() instanceof Player var2 && this.landingProtection.getOrDefault(var2.getUniqueId(), 0L) > System.currentTimeMillis()) {
         var1.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onPlayerHit(EntityDamageByEntityEvent var1) {
      if (this.plugin.getConfig().getBoolean("combat.enabled", true) && !(var1.getFinalDamage() <= 0.0) && var1.getEntity() instanceof Player var2) {
         Player var4 = resolveAttacker(var1.getDamager());
         if (var4 != null && !var4.equals(var2) && this.landingProtection.getOrDefault(var2.getUniqueId(), 0L) <= System.currentTimeMillis()) {
            this.tag(var2);
            this.tag(var4);
         }
      }
   }

   private static Player resolveAttacker(Entity var0) {
      if (var0 instanceof Player var1) {
         return var1;
      } else {
         return var0 instanceof Projectile var3 && var3.getShooter() instanceof Player var2 ? var2 : null;
      }
   }

   private void tag(Player var1) {
      boolean var2 = !this.tagged(var1);
      int var3 = Math.max(1, this.plugin.getConfig().getInt("combat.tag-seconds", 10));
      this.tags.put(var1.getUniqueId(), System.currentTimeMillis() + var3 * 1000L);
      if (var2 && this.plugin.getConfig().getBoolean("combat.notify-on-tag", true)) {
         this.plugin.message(var1, "combat-started", "seconds", Integer.toString(var3));
      }

      if (var2) {
         this.plugin.sounds().play(var1, "combat-start");
      }
   }

   private void expireTags() {
      long var1 = System.currentTimeMillis();
      Iterator var3 = this.tags.entrySet().iterator();

      while (var3.hasNext()) {
         Entry var4 = (Entry)var3.next();
         if ((Long)var4.getValue() <= var1) {
            var3.remove();
            Player var5 = Bukkit.getPlayer((UUID)var4.getKey());
            if (var5 != null && this.plugin.getConfig().getBoolean("combat.notify-when-expired", true)) {
               this.plugin.message(var5, "combat-ended");
            }
         }
      }

      this.landingProtection.entrySet().removeIf(var2 -> var2.getValue() <= var1);
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onCommand(PlayerCommandPreprocessEvent var1) {
      String var2 = var1.getMessage().substring(1).trim();
      if (!var2.isBlank()) {
         String[] var3 = var2.split("\\s+");
         String var4 = var3[0].toLowerCase(Locale.ROOT);
         String var5 = var4.contains(":") ? var4.substring(var4.indexOf(58) + 1) : var4;
         if (this.tagged(var1.getPlayer()) && this.blockedCommand(var5)) {
            var1.setCancelled(true);
            this.plugin.message(var1.getPlayer(), "combat-blocked", "seconds", Integer.toString(this.remaining(var1.getPlayer())));
         } else if ("kit".equals(var5) && this.plugin.getConfig().getBoolean("commands.prefer-rift-kit", true)) {
            var1.setCancelled(true);
            if (!this.plugin.ranks().hasPluginPermission(var1.getPlayer(), "riftpvp.kit")) {
               this.plugin.message(var1.getPlayer(), "no-permission");
            } else if (this.plugin.inLobby(var1.getPlayer().getLocation())) {
               var1.getPlayer().sendMessage("§c/kit is disabled in the lobby. Use /kiteditor.");
            } else if (var3.length >= 2) {
               this.plugin.equipKit(var1.getPlayer(), var3[1].toLowerCase(Locale.ROOT));
            } else {
               this.plugin.menus().openKits(var1.getPlayer());
            }
         }
      }
   }

   private boolean blockedCommand(String var1) {
      if ("lobby".equals(var1)) {
         return this.plugin.getConfig().getBoolean("combat.block-lobby", true);
      } else if (!this.plugin.getConfig().getStringList("combat.block-commands").contains(var1)) {
         return this.plugin.getConfig().getStringList("commands.aliases-for-kit-blocking").contains(var1);
      } else if ("heal".equals(var1)) {
         return this.plugin.getConfig().getBoolean("combat.block-heal", true);
      } else if ("kit".equals(var1) || "kits".equals(var1)) {
         return this.plugin.getConfig().getBoolean("combat.block-kit", true);
      } else if ("rtpq".equals(var1)) {
         return this.plugin.getConfig().getBoolean("combat.block-queue", true);
      } else {
         return "rtp".equals(var1) ? this.plugin.getConfig().getBoolean("combat.block-rtp", true) : true;
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent var1) {
      Player var2 = var1.getPlayer();
      this.landingProtection.remove(var2.getUniqueId());
      if (this.tagged(var2) && this.plugin.getConfig().getBoolean("combat.punish-combat-logout", false)) {
         this.logoutPenalties.add(var2.getUniqueId());
         this.plugin.sidebar().recordLogoutDeath(var2.getUniqueId());
         this.savePenalties();
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent var1) {
      Player var2 = var1.getPlayer();
      if (this.logoutPenalties.remove(var2.getUniqueId())) {
         var2.getInventory().clear();
         this.savePenalties();
         this.plugin.message(var2, "combat-logout");
      }
   }

   private void savePenalties() {
      YamlConfiguration var1 = new YamlConfiguration();
      var1.set("penalties", this.logoutPenalties.stream().map(UUID::toString).toList());

      try {
         var1.save(this.penaltyFile);
      } catch (IOException var3) {
         this.plugin.getLogger().warning("Could not save combat penalties: " + var3.getMessage());
      }
   }

   @EventHandler
   public void onDeath(PlayerDeathEvent var1) {
      Player var2 = var1.getEntity();
      this.plugin.sounds().play(var2, "death");
      if (this.plugin.getConfig().getBoolean("combat.clear-on-death", true)) {
         this.tags.remove(var2.getUniqueId());
      }

      Player var3 = var2.getKiller();
      if (var3 != null) {
         this.plugin.sounds().play(var3, "kill");
      }

      if (this.plugin.getConfig().getBoolean("combat.clear-on-kill", true) && var3 != null) {
         this.tags.remove(var3.getUniqueId());
      }
   }
}
