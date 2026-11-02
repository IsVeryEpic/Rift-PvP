package dev.riftpvp;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;

final class SafeTeleport {
   private static final Set<Material> BAD_FLOORS = new HashSet<>(
      Set.of(
         Material.BEDROCK,
         Material.CACTUS,
         Material.MAGMA_BLOCK,
         Material.FIRE,
         Material.SOUL_FIRE,
         Material.CAMPFIRE,
         Material.SOUL_CAMPFIRE,
         Material.POWDER_SNOW,
         Material.POINTED_DRIPSTONE
      )
   );
   private final RiftPvpPlugin plugin;
   private int activeRequests;

   SafeTeleport(RiftPvpPlugin var1) {
      this.plugin = var1;
   }

   boolean find(World var1, boolean var2, Consumer<Location> var3) {
      FileConfiguration var4 = this.plugin.getConfig();
      String var5 = var2 ? "matchmaking" : "rtp";
      int var6 = Math.max(0, var4.getInt(var5 + ".min-distance", var2 ? 200 : 10000));
      int var7 = Math.max(var6 + 1, var4.getInt(var5 + ".max-distance", var2 ? 5000 : 200000));
      // Matchmaking must not waste most of its searches outside a small world border.
      // This is especially important for Ranked's strict Plains-biome requirement.
      if (var2) {
         WorldBorder border = var1.getWorldBorder();
         Location spawn = var1.getSpawnLocation();
         Location center = border.getCenter();
         double half = border.getSize() / 2.0 - 16.0;
         double usableRadius = half - Math.max(Math.abs(spawn.getX() - center.getX()),
            Math.abs(spawn.getZ() - center.getZ()));
         if (usableRadius > var6 + 8) {
            var7 = Math.min(var7, Math.max(var6 + 1, (int)Math.floor(usableRadius)));
         }
      }
      int var8 = Math.min(128, Math.max(1, var4.getInt(var5 + ".max-location-attempts", 32)));
      int var9 = Math.max(1, var4.getInt(var5 + ".max-concurrent-teleports", 4));
      if (this.activeRequests >= var9) {
         return false;
      }

      this.activeRequests++;
      this.search(var1, var6, var7, var8, 0, var3);
      return true;
   }

   private void search(World var1, int var2, int var3, int var4, int var5, Consumer<Location> var6) {
      if (!this.plugin.isEnabled()) {
         this.finish(var6, null);
      } else if (var5 >= var4) {
         this.finish(var6, null);
      } else {
         ThreadLocalRandom var7 = ThreadLocalRandom.current();
         double var8 = Math.sqrt(var7.nextDouble((double)var2 * var2, (double)var3 * var3));
         double var10 = var7.nextDouble(0.0, Math.PI * 2);
         Location var12 = var1.getSpawnLocation();
         int var13 = (int)Math.round(var12.getX() + Math.cos(var10) * var8);
         int var14 = (int)Math.round(var12.getZ() + Math.sin(var10) * var8);
         if (!insideBorder(var1, var13, var14)) {
            this.scheduleNext(var1, var2, var3, var4, var5, var6);
         } else {
            CompletableFuture var15;
            try {
               var15 = var1.getChunkAtAsync(var13 >> 4, var14 >> 4, true);
            } catch (Exception var17) {
               this.scheduleNext(var1, var2, var3, var4, var5, var6);
               return;
            }

            var15.whenComplete((var9, var10x) -> {
               if (this.plugin.isEnabled()) {
                  Bukkit.getScheduler().runTask(this.plugin, () -> {
                     if (var10x == null && var9 != null && var1.isChunkLoaded(var13 >> 4, var14 >> 4)) {
                        Location var11 = findLanding(var1, var13, var14);
                        if (var11 == null) {
                           this.scheduleNext(var1, var2, var3, var4, var5, var6);
                        } else {
                           this.finish(var6, var11);
                        }
                     } else {
                        this.scheduleNext(var1, var2, var3, var4, var5, var6);
                     }
                  });
               }
            });
         }
      }
   }

   private void scheduleNext(World var1, int var2, int var3, int var4, int var5, Consumer<Location> var6) {
      if (this.plugin.isEnabled()) {
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.search(var1, var2, var3, var4, var5 + 1, var6), 1L);
      } else {
         this.finish(var6, null);
      }
   }

   private void finish(Consumer<Location> var1, Location var2) {
      this.activeRequests = Math.max(0, this.activeRequests - 1);
      var1.accept(var2);
   }

   private static boolean insideBorder(World var0, int var1, int var2) {
      WorldBorder var3 = var0.getWorldBorder();
      Location var4 = var3.getCenter();
      double var5 = var3.getSize() / 2.0 - 8.0;
      return Math.abs(var1 - var4.getX()) < var5 && Math.abs(var2 - var4.getZ()) < var5;
   }

   private static boolean safeFloor(Block var0) {
      Material var1 = var0.getType();
      return var1.isSolid() && !BAD_FLOORS.contains(var1) && !var0.isLiquid();
   }

   static boolean hasClearDropColumn(Location var0, int var1) {
      if (var0 == null || var0.getWorld() == null) {
         return false;
      }

      if (var1 <= 0) {
         return true;
      }

      World var2 = var0.getWorld();
      int var3 = var0.getBlockX();
      int var4 = var0.getBlockZ();
      int var5 = var0.getBlockY();
      if (var5 + var1 + 1 >= var2.getMaxHeight()) {
         return false;
      }

      for (int var6 = var5; var6 <= var5 + var1 + 1; var6++) {
         Block var7 = var2.getBlockAt(var3, var6, var4);
         Material var8 = var7.getType();
         if (!var7.isPassable()
            || var7.isLiquid()
            || var8 == Material.COBWEB
            || var8 == Material.POWDER_SNOW
            || var8 == Material.FIRE
            || var8 == Material.SOUL_FIRE
            || var8 == Material.SWEET_BERRY_BUSH) {
            return false;
         }
      }

      return true;
   }

   static Location findLanding(World var0, int var1, int var2) {
      if (!var0.isChunkLoaded(var1 >> 4, var2 >> 4)) {
         return null;
      }

      if (var0.getEnvironment() == Environment.NETHER) {
         for (int var6 = Math.min(121, var0.getMaxHeight() - 4); var6 > var0.getMinHeight() + 5; var6--) {
            Location var7 = floorLocation(var0, var1, var6, var2);
            if (var7 != null) {
               return var7;
            }
         }

         return null;
      } else {
         int var3 = var0.getHighestBlockYAt(var1, var2, HeightMap.MOTION_BLOCKING_NO_LEAVES);

         for (int var4 = var3; var4 >= Math.max(var3 - 4, var0.getMinHeight() + 2); var4--) {
            Location var5 = floorLocation(var0, var1, var4, var2);
            if (var5 != null) {
               return var5;
            }
         }

         return null;
      }
   }

   private static Location floorLocation(World var0, int var1, int var2, int var3) {
      if (var2 >= var0.getMinHeight() && var2 + 2 < var0.getMaxHeight()) {
         Block var4 = var0.getBlockAt(var1, var2, var3);
         Block var5 = var0.getBlockAt(var1, var2 + 1, var3);
         Block var6 = var0.getBlockAt(var1, var2 + 2, var3);
         if (!safeFloor(var4)) {
            return null;
         } else if (!var5.isPassable() || !var6.isPassable() || var5.isLiquid() || var6.isLiquid()) {
            return null;
         } else if (var5.getType() != Material.FIRE && var5.getType() != Material.SOUL_FIRE) {
            return var6.getType() != Material.FIRE && var6.getType() != Material.SOUL_FIRE ? new Location(var0, var1 + 0.5, var2 + 1.01, var3 + 0.5) : null;
         } else {
            return null;
         }
      } else {
         return null;
      }
   }
}
