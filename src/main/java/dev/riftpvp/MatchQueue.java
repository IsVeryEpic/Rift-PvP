package dev.riftpvp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

final class MatchQueue implements Listener {
   private final RiftPvpPlugin plugin;
   private final Map<String, Deque<UUID>> queues = new HashMap<>();
   private final Map<UUID, String> queuedIn = new HashMap<>();
   private final Map<UUID, UUID> opponents = new HashMap<>();
   private final Set<UUID> preparing = new HashSet<>();
   private final Set<UUID> startProtected = new HashSet<>();
   private final Map<UUID, Location> matchCenters = new HashMap<>();
   // A single match pipeline is shared by RTPQ, duels and ranked. Only ranked gets a biome restriction.
   private final Set<UUID> rankedFightStarts = new HashSet<>();
   private final Map<UUID, Location> preparedCenters = new HashMap<>();
   private final Map<UUID, Location> preparedPartners = new HashMap<>();
   private final Map<UUID, ChunkReservation> chunkReservations = new HashMap<>();
   private record ChunkReservation(World world, int x, int z) {}

   MatchQueue(RiftPvpPlugin var1) {
      this.plugin = var1;
   }

   int waiting(String var1) {
      return this.queues.getOrDefault(var1, new ArrayDeque<>()).size();
   }

   UUID opponentOf(UUID id) { return opponents.get(id); }
   boolean isInActiveFight(UUID id) { return opponents.containsKey(id) && !preparing.contains(id) && plugin.practice().hasActiveFight(id); }
   void expireDisconnected(UUID id) { endPair(id); }

   boolean isQueued(UUID id) { return isQueuedAnywhere(id); }

   boolean busy(Player player) {
      UUID id = player.getUniqueId();
      return opponents.containsKey(id) || preparing.contains(id);
   }

   void leave(Player var1) {
      UUID var2 = var1.getUniqueId();
      if (this.removeFromQueue(var2)) {
         this.plugin.message(var1, "queue-left");
         this.plugin.sounds().play(var1, "queue-leave");
      } else {
         this.plugin.message(var1, "queue-not-waiting");
      }
   }

   /** Starts an invited or ranked 1v1 with the same spawn and equipment pipeline as RTPQ. */
   boolean challenge(Player first, Player second, String kit) {
      return challenge(first, second, kit, false);
   }

   boolean challenge(Player first, Player second, String kit, boolean rankedMatch) {
      if (!plugin.maintenance().allowNewMatch(null)) return false;
      if (first.equals(second) || !first.isOnline() || !second.isOnline()) return false;
      if (CustomKits.isCustom(kit) && !plugin.customKits().allowedInCustomMode(kit)) return false;
      if (CustomKits.isCustom(kit) ? !plugin.customKits().exists(kit) : !this.plugin.kits().hasKit(kit)) return false;
      UUID a = first.getUniqueId();
      UUID b = second.getUniqueId();
      if (this.opponents.containsKey(a) || this.opponents.containsKey(b) || this.preparing.contains(a) || this.preparing.contains(b)) return false;
      if (this.plugin.combat().tagged(first) || this.plugin.combat().tagged(second)) return false;
      if (CustomKits.isCustom(kit) && !plugin.customKits().prepare(first, second, kit)) return false;
      plugin.customKits().removeWaiting(a);
      plugin.customKits().removeWaiting(b);
      this.removeFromQueue(a);
      this.removeFromQueue(b);
      if (this.plugin.practice() != null) {
         this.plugin.practice().clearWaiting(a);
         this.plugin.practice().clearWaiting(b);
      }
      this.preparing.add(a);
      this.preparing.add(b);
      this.opponents.put(a, b);
      this.opponents.put(b, a);
      if (rankedMatch) this.rankedFightStarts.add(a);
      this.plugin.sounds().play(first, "match-found");
      this.plugin.sounds().play(second, "match-found");
      this.prepareFightLocation(kit, a, b, 0);
      return true;
   }

   void cancelForLobby(Player var1) {
      UUID var2 = var1.getUniqueId();
      this.removeFromQueue(var2);
      UUID var3 = this.opponents.get(var2);
      if (var3 != null) {
         Player var4 = Bukkit.getPlayer(var3);
         if (var4 != null && var4.isOnline()) {
            this.plugin.message(var4, "queue-cancelled");
         }
      }

      this.endPair(var2);
   }

   /** Pair compatible waiting fighters without removing incompatible players or looping forever. */
   void retryEligibleQueues() {
      if (plugin.maintenance().isActive()) return;
      for (String kit : new java.util.ArrayList<>(queues.keySet())) matchIfReady(kit);
   }

   private void matchIfReady(String kit) {
      if (!plugin.kitModes().allowed(kit, "queue") || plugin.maintenance().isActive()) return;
      Deque<UUID> waiting = queues.get(kit);
      if (waiting == null) return;
      boolean matched;
      do {
         matched = false;
         List<UUID> ids = new java.util.ArrayList<>(waiting);
         for (int i = 0; i < ids.size() && !matched; i++) {
            UUID firstId = ids.get(i);
            Player first = Bukkit.getPlayer(firstId);
            if (first == null || !first.isOnline() || busy(first)) {
               waiting.remove(firstId); queuedIn.remove(firstId); continue;
            }
            for (int j = i + 1; j < ids.size(); j++) {
               UUID secondId = ids.get(j);
               Player second = Bukkit.getPlayer(secondId);
               if (second == null || !second.isOnline() || busy(second)) {
                  waiting.remove(secondId); queuedIn.remove(secondId); continue;
               }
               if (!plugin.playerPreferences().pingCompatible(first, second)) continue;
               if (!challenge(first, second, kit)) continue;
               plugin.message(first, "queue-found", "kit", plugin.kits().name(kit));
               plugin.message(second, "queue-found", "kit", plugin.kits().name(kit));
               matched = true;
               break;
            }
         }
      } while (matched);
   }

   private void prepareFightLocation(String kit, UUID first, UUID second, int attempts) {
      if (!this.isPair(first, second) || !this.plugin.isEnabled()) return;
      Player a = Bukkit.getPlayer(first);
      Player b = Bukkit.getPlayer(second);
      if (a == null || b == null || !a.isOnline() || !b.isOnline()) { endPair(first); return; }
      World world = Bukkit.getWorld(this.plugin.getConfig().getString("matchmaking.world", "world"));
      if (world == null) {
         this.plugin.message(a, "queue-world-missing");
         this.plugin.message(b, "queue-world-missing");
         endPair(first);
         return;
      }
      boolean rankedMatch = rankedFightStarts.contains(first);
      int retries = rankedMatch
         ? plugin.getConfig().getInt("ranked.location-retries", 20)
         : plugin.getConfig().getInt("matchmaking.preload-retries", 8);
      if (attempts >= Math.max(1, Math.min(40, retries))) {
         if (rankedMatch) {
            a.sendMessage("§cRanked: Could not find two safe Plains spawn points. Please retry.");
            b.sendMessage("§cRanked: Could not find two safe Plains spawn points. Please retry.");
            plugin.getLogger().warning("Ranked pairing " + first + "/" + second
               + " exhausted safe Plains attempts in " + world.getName());
         } else {
            this.plugin.message(a, "queue-failed");
            this.plugin.message(b, "queue-failed");
         }
         endPair(first);
         return;
      }
      int height = Math.max(0, Math.min(16, plugin.getConfig().getInt("matchmaking.air-spawn-height", 5)));
      if (!plugin.safeTeleport().find(world, true, location -> {
         if (!isPair(first, second)) return;
         if (location == null || tooClose(location) || isSnowyFightLocation(location)
               || (rankedMatch && !isRankedPlains(location))
               || !SafeTeleport.hasClearDropColumn(location, height)) {
            prepareFightLocation(kit, first, second, attempts + 1);
            return;
         }
         Location partner = selectSecondSpot(location, height, rankedMatch);
         if (partner == null) {
            prepareFightLocation(kit, first, second, attempts + 1);
            return;
         }
         int cx = location.getBlockX() >> 4;
         int cz = location.getBlockZ() >> 4;
         // Ticket keeps the chosen destination loaded while the 3-2-1 countdown runs.
         if (!world.addPluginChunkTicket(cx, cz, plugin)) {
            prepareFightLocation(kit, first, second, attempts + 1);
            return;
         }
         preparedCenters.put(first, location);
         preparedPartners.put(first, partner);
         chunkReservations.put(first, new ChunkReservation(world, cx, cz));
         int seconds = Math.max(0, Math.min(30, plugin.getConfig().getInt("matchmaking.countdown-seconds", 3)));
         beginCountdown(kit, first, second, seconds);
      })) {
         if (rankedMatch && attempts < 5) {
            Bukkit.getScheduler().runTaskLater(plugin,
               () -> prepareFightLocation(kit, first, second, attempts + 1), 10L);
            return;
         }
         plugin.message(a, "rtp-busy");
         plugin.message(b, "rtp-busy");
         endPair(first);
      }
   }

   private Location selectSecondSpot(Location first, int height, boolean rankedMatch) {
      World world = first.getWorld();
      int x = first.getBlockX(), z = first.getBlockZ();
      int spread = Math.max(2, Math.min(8, plugin.getConfig().getInt("matchmaking.separation-blocks", 3)));
      int[][] offsets = {{1, 0}, {0, 1}, {-1, 0}, {0, -1},
                         {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
      for (int distance = spread; distance <= 11; distance++) {
         for (int[] offset : offsets) {
            int nx = x + offset[0] * distance, nz = z + offset[1] * distance;
            // SafeTeleport.findLanding never forces a synchronous load of a new chunk.
            if ((nx >> 4) != (x >> 4) || (nz >> 4) != (z >> 4)) continue;
            Location second = SafeTeleport.findLanding(world, nx, nz);
            if (second != null && Math.abs(first.getY() - second.getY()) <= 2
                  && !isSnowyFightLocation(second) && (!rankedMatch || isRankedPlains(second))
                  && SafeTeleport.hasClearDropColumn(second, height))
               return second;
         }
      }
      return null;
   }

   private void releaseLocation(UUID first) {
      ChunkReservation ticket = chunkReservations.remove(first);
      if (ticket != null) ticket.world().removePluginChunkTicket(ticket.x(), ticket.z(), plugin);
      preparedCenters.remove(first);
      preparedPartners.remove(first);
   }

   private void beginCountdown(final String var1, final UUID var2, final UUID var3, final int var4) {
      if (var4 == 0) {
         this.startMatch(var1, var2, var3, 0);
      } else {
         (new BukkitRunnable() {
            private int remaining = var4;

            public void run() {
               if (MatchQueue.this.plugin.isEnabled() && MatchQueue.this.isPair(var2, var3)) {
                  Player var1x = Bukkit.getPlayer(var2);
                  Player var2x = Bukkit.getPlayer(var3);
                  if (var1x == null || var2x == null || !var1x.isOnline() || !var2x.isOnline()) {
                     this.cancel();
                     MatchQueue.this.endPair(var2);
                  } else if (this.remaining == 0) {
                     this.cancel();
                     String title = MatchQueue.this.rankedFightStarts.contains(var2) ? "§5§lRANKED" : "§d§lMATCH";
                     var1x.sendTitle(title, "§fFinding your arena...", 0, 30, 5);
                     var2x.sendTitle(title, "§fFinding your arena...", 0, 30, 5);
                     MatchQueue.this.startMatch(var1, var2, var3, 0);
                  } else {
                     String var3x = "§eTeleporting in §f§l" + this.remaining + "§e...";
                     boolean ranked = MatchQueue.this.rankedFightStarts.contains(var2);
                     String title = ranked ? "§5§lRANKED" : "§d§lMATCH";
                     var1x.sendTitle(title, var3x, 0, 25, 0);
                     var2x.sendTitle(title, var3x, 0, 25, 0);
                     String action = ranked ? "Ranked: teleporting in " : "Match: teleporting in ";
                     var1x.sendActionBar(Component.text(action + this.remaining + "s"));
                     var2x.sendActionBar(Component.text(action + this.remaining + "s"));
                     float var4x = 1.0F + (var4 - this.remaining) * 0.14F;
                     MatchQueue.this.plugin.sounds().play(var1x, "countdown", var4x);
                     MatchQueue.this.plugin.sounds().play(var2x, "countdown", var4x);
                     this.remaining--;
                  }
               } else {
                  this.cancel();
               }
            }
         }).runTaskTimer(this.plugin, 0L, 20L);
      }
   }

   private UUID pollOnline(Deque<UUID> var1) {
      while (!var1.isEmpty()) {
         UUID var2 = (UUID)var1.pollFirst();
         Player var3 = Bukkit.getPlayer(var2);
         if (var3 != null && var3.isOnline()) {
            return var2;
         }

         this.queuedIn.remove(var2);
      }

      return null;
   }

   private void startMatch(String kit, UUID first, UUID second, int retries) {
      if (!isPair(first, second)) return;
      Player a = Bukkit.getPlayer(first), b = Bukkit.getPlayer(second);
      if (a == null || b == null || !a.isOnline() || !b.isOnline()
            || plugin.combat().tagged(a) || plugin.combat().tagged(b)) { endPair(first); return; }
      if ((CustomKits.isCustom(kit) || plugin.getConfig().getBoolean("matchmaking.auto-equip-kit", true))
            && !(CustomKits.isCustom(kit) ? plugin.customKits().exists(kit) : plugin.kits().hasKit(kit))) {
         plugin.message(a, "kit-not-loaded"); plugin.message(b, "kit-not-loaded");
         endPair(first); return;
      }
      Location center = preparedCenters.get(first);
      Location partner = preparedPartners.get(first);
      if (center == null || partner == null || isSnowyFightLocation(center)
            || isSnowyFightLocation(partner)
            || (rankedFightStarts.contains(first) && (!isRankedPlains(center) || !isRankedPlains(partner)))) {
         releaseLocation(first);
         prepareFightLocation(kit, first, second, retries + 1);
         return;
      }
      int height = Math.max(0, Math.min(16, plugin.getConfig().getInt("matchmaking.air-spawn-height", 5)));
      if (!SafeTeleport.hasClearDropColumn(center, height)
            || !SafeTeleport.hasClearDropColumn(partner, height)) {
         releaseLocation(first);
         prepareFightLocation(kit, first, second, retries + 1);
         return;
      }
      executeTeleports(a, b, center, kit, retries, height);
   }

   private boolean tooClose(Location var1) {
      int var2 = Math.max(250, this.plugin.getConfig().getInt("matchmaking.location-separation-blocks", 250));
      // Keep all fights away from the configured lobby, even when the lobby shares a world.
      if (this.plugin.getConfig().isSet("lobby.world")
            && var1.getWorld().getName().equals(this.plugin.getConfig().getString("lobby.world"))) {
         double lobbyX = this.plugin.getConfig().getDouble("lobby.x");
         double lobbyZ = this.plugin.getConfig().getDouble("lobby.z");
         double dx = var1.getX() - lobbyX;
         double dz = var1.getZ() - lobbyZ;
         if (dx * dx + dz * dz < (double)var2 * var2) return true;
      }

      for (Location var4 : this.matchCenters.values()) {
         if (var4.getWorld().equals(var1.getWorld())) {
            double dx = var4.getX() - var1.getX();
            double dz = var4.getZ() - var1.getZ();
            if (dx * dx + dz * dz < (double)var2 * var2) return true;
         }
      }

      for (Location reserved : preparedCenters.values()) {
         if (reserved.getWorld().equals(var1.getWorld())) {
            double dx = reserved.getX() - var1.getX(), dz = reserved.getZ() - var1.getZ();
            if (dx * dx + dz * dz < (double)var2 * var2) return true;
         }
      }
      return false;
   }

   /** Snow and frozen biome restriction applies only to fight matchmaking, never normal /rtp. */
   private boolean isSnowyFightLocation(Location location) {
      String biome = String.valueOf(location.getBlock().getBiome()).toUpperCase(java.util.Locale.ROOT);
      return biome.contains("SNOW") || biome.contains("FROZEN")
         || biome.contains("ICE_SPIKES") || biome.contains("JAGGED_PEAKS")
         || biome.contains("GROVE");
   }

   /** Ranked fights are restricted to the explicitly configured, open Plains biomes. */
   private boolean isRankedPlains(Location location) {
      if (location == null || location.getWorld() == null) return false;
      String biome = String.valueOf(location.getBlock().getBiome()).toUpperCase(java.util.Locale.ROOT);
      if (biome.startsWith("MINECRAFT:")) biome = biome.substring("MINECRAFT:".length());
      List<String> allowed = plugin.getConfig().getStringList("ranked.allowed-biomes");
      if (allowed.isEmpty()) allowed = List.of("PLAINS", "SUNFLOWER_PLAINS");
      for (String entry : allowed) {
         if (biome.equals(entry.trim().toUpperCase(java.util.Locale.ROOT))) return true;
      }
      return false;
   }

   private void executeTeleports(Player var1, Player var2, Location var3, String var4, int var5, int var6) {
      if (this.isPair(var1.getUniqueId(), var2.getUniqueId()) && var1.isOnline() && var2.isOnline()) {
         if (!this.plugin.combat().tagged(var1) && !this.plugin.combat().tagged(var2)) {
            Location var7 = var1.getLocation();
            Location var8 = var2.getLocation();
            var1.closeInventory();
            var2.closeInventory();
            Location var9 = var3.clone();
            Location reservedPartner = preparedPartners.get(var1.getUniqueId());
            Location var10 = reservedPartner == null ? null : reservedPartner.clone();

            if (var10 != null) {
               this.matchCenters.put(var1.getUniqueId(), var3.clone());
               // Arrival immunity belongs to Ranked only. Casual duels and RTPQ have instant PvP.
               boolean rankedMatch = this.rankedFightStarts.contains(var1.getUniqueId());
               if (rankedMatch) {
                  this.plugin.combat().protectArrival(var1);
                  this.plugin.combat().protectArrival(var2);
               } else {
                  this.plugin.combat().clearArrival(var1);
                  this.plugin.combat().clearArrival(var2);
               }
               var9.add(0.0, var6, 0.0);
               var10.add(0.0, var6, 0.0);
               double var22 = var10.getX() - var9.getX();
               double var23 = var10.getZ() - var9.getZ();
               float var24 = (float)Math.toDegrees(Math.atan2(-var22, var23));
               var9.setYaw(var24);
               var10.setYaw(var24 + 180.0F);
               CompletableFuture var25 = var1.teleportAsync(var9, TeleportCause.PLUGIN);
               CompletableFuture var21 = var2.teleportAsync(var10, TeleportCause.PLUGIN);
               CompletableFuture.allOf(var25, var21).whenComplete((var9x, var10x) -> {
                  if (this.plugin.isEnabled()) {
                     Bukkit.getScheduler().runTask(this.plugin, () -> {
                        if (this.isPair(var1.getUniqueId(), var2.getUniqueId())) {
                           boolean var10xx = var10x == null && Boolean.TRUE.equals(var25.getNow(false)) && Boolean.TRUE.equals(var21.getNow(false));
                           if (!var10xx) {
                              if (var1.isOnline()) {
                                 var1.teleportAsync(var7);
                              }

                              if (var2.isOnline()) {
                                 var2.teleportAsync(var8);
                              }

                              this.plugin.message(var1, "queue-failed");
                              this.plugin.message(var2, "queue-failed");
                              this.endPair(var1.getUniqueId());
                           } else if (var1.isOnline() && var2.isOnline()) {
                              if (rankedMatch) {
                                 this.plugin.combat().protectArrival(var1);
                                 this.plugin.combat().protectArrival(var2);
                              } else {
                                 this.plugin.combat().clearArrival(var1);
                                 this.plugin.combat().clearArrival(var2);
                              }
                              boolean var11x = CustomKits.isCustom(var4) || this.plugin.getConfig().getBoolean("matchmaking.auto-equip-kit", true);
                              if (var11x) {
                                 if (!(CustomKits.isCustom(var4) ? plugin.customKits().exists(var4) : this.plugin.kits().hasKit(var4))) {
                                    this.failKitMatch(var1, var2, var7, var8);
                                    return;
                                 }

                                 try {
                                    boolean custom = CustomKits.isCustom(var4);
                                    boolean var12x = custom ? plugin.customKits().equip(var1, var4) : this.plugin.kits().equip(var1, var4);
                                    boolean var13x = custom ? plugin.customKits().equip(var2, var4) : this.plugin.kits().equip(var2, var4);
                                    if (!var12x || !var13x) {
                                       this.failKitMatch(var1, var2, var7, var8);
                                       return;
                                    }

                                    String kitName = CustomKits.isCustom(var4) ? plugin.customKits().displayName(var4) : plugin.kits().name(var4);
                                    this.plugin.message(var1, "kit-equipped", "kit", kitName);
                                    this.plugin.message(var2, "kit-equipped", "kit", kitName);
                                    this.plugin.sounds().play(var1, "kit");
                                    this.plugin.sounds().play(var2, "kit");
                                 } catch (RuntimeException var15x) {
                                    this.plugin.getLogger().warning("Failed to equip both players for match kit '" + var4 + "': " + var15x.getMessage());
                                    this.failKitMatch(var1, var2, var7, var8);
                                    return;
                                 }
                              }

                              int var16 = Math.max(0, Math.min(30, this.plugin.getConfig().getInt("matchmaking.slow-falling-seconds", 3)));
                              if (var6 > 0 && var16 > 0) {
                                 this.grantSlowFalling(var1, var2, var16);
                                 UUID var17 = var1.getUniqueId();
                                 UUID var14x = var2.getUniqueId();
                                 Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                                    if (this.isPair(var17, var14x) && var1.isOnline() && var2.isOnline()) {
                                       this.grantSlowFalling(var1, var2, var16);
                                    }
                                 }, 1L);
                              }

                              this.preparing.remove(var1.getUniqueId());
                              this.preparing.remove(var2.getUniqueId());
                              UUID releaseId = var1.getUniqueId();
                              Bukkit.getScheduler().runTaskLater(plugin, () -> releaseLocation(releaseId), 40L);
                              if (this.plugin.practice() != null) this.plugin.practice().started(var1, var2, var4);
                              if (rankedMatch) {
                                 this.plugin.message(var1, "queue-teleported");
                                 this.plugin.message(var2, "queue-teleported");
                              } else {
                                 var1.sendMessage("§aFight started! §7No hit delay or damage immunity.");
                                 var2.sendMessage("§aFight started! §7No hit delay or damage immunity.");
                              }
                              this.plugin.sounds().play(var1, "teleport");
                              this.plugin.sounds().play(var2, "teleport");
                              if (rankedMatch) {
                                 this.protectFightStart(var1, var2);
                              } else {
                                 var1.sendTitle("§a§lFIGHT!", "§7Good luck", 0, 16, 6);
                                 var2.sendTitle("§a§lFIGHT!", "§7Good luck", 0, 16, 6);
                                 this.plugin.sounds().play(var1, "match-start");
                                 this.plugin.sounds().play(var2, "match-start");
                              }
                              // Active duels/ranked fights have no fixed timeout. A 600-second
                              // timer previously expired valid fights even when both players were
                              // online and moving. MatchRecovery still protects crash/restart cases,
                              // and MatchReconnect owns actual disconnect expiration.
                           } else {
                              if (var1.isOnline()) {
                                 var1.teleportAsync(var7);
                              }

                              if (var2.isOnline()) {
                                 var2.teleportAsync(var8);
                              }

                              this.endPair(var1.getUniqueId());
                           }
                        }
                     });
                  }
               });
            } else if (var5 < 6) {
               this.startMatch(var4, var1.getUniqueId(), var2.getUniqueId(), var5 + 1);
            } else {
               this.plugin.message(var1, "queue-failed");
               this.plugin.message(var2, "queue-failed");
               this.endPair(var1.getUniqueId());
            }
         } else {
            this.endPair(var1.getUniqueId());
         }
      } else {
         this.endPair(var1.getUniqueId());
      }
   }

   private void grantSlowFalling(Player var1, Player var2, int var3) {
      PotionEffect var4 = new PotionEffect(PotionEffectType.SLOW_FALLING, var3 * 20, 0, false, false, true);
      var1.addPotionEffect(var4, true);
      var2.addPotionEffect(var4, true);
   }

   private void failKitMatch(Player var1, Player var2, Location var3, Location var4) {
      if (var1.isOnline()) {
         var1.teleportAsync(var3);
      }

      if (var2.isOnline()) {
         var2.teleportAsync(var4);
      }

      this.plugin.message(var1, "kit-not-loaded");
      this.plugin.message(var2, "kit-not-loaded");
      this.endPair(var1.getUniqueId());
   }

   private boolean isPair(UUID var1, UUID var2) {
      return var2.equals(this.opponents.get(var1)) && var1.equals(this.opponents.get(var2));
   }

   private void endPair(UUID var1) {
      UUID var2 = this.opponents.remove(var1);
      if (plugin.customKits() != null) plugin.customKits().release(var1, var2);
      if (plugin.matchReconnect() != null) plugin.matchReconnect().clearPair(var1, var2);
      if (this.plugin.practice() != null) {
         this.plugin.practice().clearPending(var1, var2);
         // Keep an active fight record alive until PracticeFeatures handles death/quit.
         // Earlier code erased the match before the winner could be credited.
         if (!this.plugin.practice().hasActiveFight(var1)) this.plugin.practice().expireMatch(var1);
      }
      this.preparing.remove(var1);
      this.rankedFightStarts.remove(var1);
      this.startProtected.remove(var1);
      releaseLocation(var1);
      this.matchCenters.remove(var1);
      if (var2 != null) {
         this.opponents.remove(var2);
         this.preparing.remove(var2);
         this.rankedFightStarts.remove(var2);
         this.startProtected.remove(var2);
         releaseLocation(var2);
         this.matchCenters.remove(var2);
      }
   }

   private boolean isRankedCombatant(UUID id) {
      UUID partner = opponents.get(id);
      return rankedFightStarts.contains(id) || partner != null && rankedFightStarts.contains(partner);
   }

   /** Ranked-only hit protection and third-party PvP filtering. */
   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onFightDamage(EntityDamageByEntityEvent event) {
      if (!(event.getEntity() instanceof Player victim)) return;
      Player attacker = null;
      Entity source = event.getDamager();
      if (source instanceof Player p) attacker = p;
      else if (source instanceof Projectile projectile) {
         ProjectileSource shooter = projectile.getShooter();
         if (shooter instanceof Player p) attacker = p;
      }
      UUID victimId = victim.getUniqueId();
      // Only ranked fights use opponent-only hit filtering / anti-hit protection.
      // Normal duels and RTPQ must use vanilla PvP damage rules.
      if (!isRankedCombatant(victimId)
            && (attacker == null || !isRankedCombatant(attacker.getUniqueId()))) return;
      if (startProtected.contains(victimId) || attacker != null && startProtected.contains(attacker.getUniqueId())) {
         event.setCancelled(true);
         return;
      }
      if (attacker == null) return;
      UUID attackerId = attacker.getUniqueId();
      // During a managed duel/queue fight, only the assigned opponent may deal or receive PvP damage.
      if ((opponents.containsKey(victimId) || opponents.containsKey(attackerId))
            && !attackerId.equals(opponents.get(victimId))) event.setCancelled(true);
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onCountdownDamage(EntityDamageEvent event) {
      if (event.getEntity() instanceof Player player && startProtected.contains(player.getUniqueId()))
         event.setCancelled(true);
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onCountdownMove(PlayerMoveEvent event) {
      if (!startProtected.contains(event.getPlayer().getUniqueId()) || event.getTo() == null) return;
      Location from = event.getFrom(), to = event.getTo();
      if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
         // Keep falling and camera rotation possible, but lock horizontal movement.
         Location locked = to.clone();
         locked.setX(from.getX());
         locked.setZ(from.getZ());
         event.setTo(locked);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onCountdownUse(PlayerInteractEvent event) {
      if (startProtected.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onCountdownThrow(ProjectileLaunchEvent event) {
      if (event.getEntity().getShooter() instanceof Player player
            && startProtected.contains(player.getUniqueId())) event.setCancelled(true);
   }

   private void protectFightStart(Player first, Player second) {
      UUID a = first.getUniqueId(), b = second.getUniqueId();
      startProtected.add(a); startProtected.add(b);
      int seconds = Math.max(0, Math.min(10, plugin.getConfig().getInt("matchmaking.in-arena-countdown-seconds", 3)));
      if (seconds == 0) { startProtected.remove(a); startProtected.remove(b); return; }
      (new BukkitRunnable() {
         int left = seconds;
         @Override public void run() {
            if (!plugin.isEnabled() || !isPair(a, b) || !first.isOnline() || !second.isOnline()) {
               startProtected.remove(a); startProtected.remove(b);
               cancel(); return;
            }
            if (left == 0) {
               startProtected.remove(a); startProtected.remove(b);
               first.sendTitle("§a§lFIGHT!", "§7Good luck", 0, 16, 6);
               second.sendTitle("§a§lFIGHT!", "§7Good luck", 0, 16, 6);
               plugin.sounds().play(first, "match-start");
               plugin.sounds().play(second, "match-start");
               cancel(); return;
            }
            first.sendTitle("§d§l" + left, "§7Get ready...", 0, 22, 0);
            second.sendTitle("§d§l" + left, "§7Get ready...", 0, 22, 0);
            plugin.sounds().play(first, "countdown");
            plugin.sounds().play(second, "countdown");
            left--;
         }
      }).runTaskTimer(plugin, 0L, 20L);
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent var1) {
      UUID var2 = var1.getPlayer().getUniqueId();
      this.removeFromQueue(var2);
      // Keep fight state intact for the reconnect grace period. This listener
      // runs before PracticeFeatures.quit(), so pause here before any teardown.
      if (!this.plugin.matchReconnect().onDisconnect(var1.getPlayer())) this.endPair(var2);
   }

   @EventHandler
   public void onDeath(PlayerDeathEvent var1) {
      UUID var2 = var1.getEntity().getUniqueId();
      if (this.opponents.containsKey(var2) || this.preparing.contains(var2))
         this.plugin.markManagedDeath(var1.getEntity());
      this.removeFromQueue(var2);
      this.endPair(var2);
   }

   @EventHandler
   public void onWorldChange(PlayerChangedWorldEvent var1) {
      UUID var2 = var1.getPlayer().getUniqueId();
      if (!this.preparing.contains(var2) && !plugin.matchReconnect().frozen(var2)) {
         this.endPair(var2);
      }
   }

   boolean hasActiveMatches() {
      return !opponents.isEmpty() || !preparing.isEmpty();
   }

   void removeKitQueue(String kit) {
      Deque<UUID> removed = queues.remove(kit);
      if (removed == null) return;
      for (UUID id : removed) {
         if (kit.equals(queuedIn.get(id))) queuedIn.remove(id);
         Player player = Bukkit.getPlayer(id);
         if (player != null) player.sendMessage("§cThis kit was removed by staff; your queue was cancelled.");
      }
   }

   void clearWaitingQueues() {
      for (UUID id : new HashSet<>(queuedIn.keySet())) {
         Player p = Bukkit.getPlayer(id);
         if (p != null) p.sendMessage("§eMatchmaking paused for server maintenance; your queue was cleared.");
      }
      queues.clear(); queuedIn.clear();
   }

   void clearAll() {
      if (plugin.customKits() != null) {
         for (UUID uuid : new HashSet<>(opponents.keySet()))
            plugin.customKits().release(uuid, opponents.get(uuid));
      }
      this.queues.clear();
      this.queuedIn.clear();
      this.opponents.clear();
      this.preparing.clear();
      this.rankedFightStarts.clear();
      this.startProtected.clear();
      this.matchCenters.clear();
      for (UUID id : new HashSet<>(chunkReservations.keySet())) releaseLocation(id);
      preparedCenters.clear();
      preparedPartners.clear();
   }

   void join(Player var1, String var2) {
      if (plugin.customKits() != null && plugin.customKits().isWaiting(var1.getUniqueId())) {
         var1.sendMessage("§cLeave your custom kit host/queue with /customkit leave before joining a regular queue.");
         return;
      }
      if (!plugin.maintenance().allowNewMatch(var1)) return;
      if (!this.plugin.kitModes().check(var1, var2, "queue")) return;
      if (!this.plugin.kits().hasKit(var2)) {
         this.plugin.message(var1, "kit-not-loaded");
      } else if (this.plugin.combat().mayUse(var1, "queue")) {
         UUID var3 = var1.getUniqueId();
         if (!this.opponents.containsKey(var3) && !this.preparing.contains(var3)) {
            Deque var4 = this.queues.get(var2);
            if (var4 == null) {
               var4 = new ArrayDeque();
               this.queues.put(var2, var4);
            }

            if (var4.contains(var3)) {
               var4.remove(var3);
               this.plugin.message(var1, "queue-left");
               this.plugin.sounds().play(var1, "queue-leave");
            } else if (!this.plugin.getConfig().getBoolean("matchmaking.allow-multiple-queues", true) && this.isQueuedAnywhere(var3)) {
               this.plugin.message(var1, "queue-already");
            } else {
               var4.addLast(var3);
               if (plugin.playerPreferences().allows(var1, "queue-notifications"))
                  this.plugin.message(var1, "queue-joined", "kit", this.plugin.kits().name(var2));
               this.plugin.sounds().play(var1, "queue-join");
               this.matchIfReady(var2);
            }
         } else {
            this.plugin.message(var1, "queue-already");
         }
      }
   }

   private boolean removeFromQueue(UUID var1) {
      boolean var2 = false;

      for (Deque var4 : this.queues.values()) {
         if (var4.remove(var1)) {
            var2 = true;
         }
      }

      this.queuedIn.remove(var1);
      return var2;
   }

   private boolean isQueuedAnywhere(UUID var1) {
      Iterator var2 = this.queues.values().iterator();

      while (var2.hasNext()) {
         if (((Deque)var2.next()).contains(var1)) {
            return true;
         }
      }

      return false;
   }
}
