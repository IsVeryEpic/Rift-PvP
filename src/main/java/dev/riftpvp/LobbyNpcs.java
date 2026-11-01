package dev.riftpvp;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.datacomponent.item.ResolvableProfile.Builder;
import io.papermc.paper.datacomponent.item.ResolvableProfile.SkinPatch;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerTextures.SkinModel;

final class LobbyNpcs implements Listener {
   private static final Set<String> KINDS = Set.of("queue", "rtp", "leaderboard", "editor", "heal", "ranked", "spectate", "hub");
   private final RiftPvpPlugin plugin;
   private final File file;
   private final NamespacedKey idKey;
   private final NamespacedKey actionKey;
   private final Map<String, LobbyNpcs.Spec> specs = new LinkedHashMap<>();

   LobbyNpcs(RiftPvpPlugin var1) {
      this.plugin = var1;
      this.file = new File(var1.getDataFolder(), "npcs.yml");
      this.idKey = new NamespacedKey(var1, "lobby-npc-id");
      this.actionKey = new NamespacedKey(var1, "lobby-npc-action");
      this.reload();
   }

   void reload() {
      this.specs.clear();
      YamlConfiguration var1 = YamlConfiguration.loadConfiguration(this.file);
      ConfigurationSection var2 = var1.getConfigurationSection("npcs");
      if (var2 != null) {
         for (String var4 : var2.getKeys(false)) {
            String var5 = "npcs." + var4 + ".";
            String var6 = normalizeKind(var1.getString(var5 + "kind", ""));
            String var7 = var1.getString(var5 + "world", "");
            if (validId(var4) && KINDS.contains(var6) && !var7.isEmpty()) {
               String var8 = var1.getString(var5 + "label", this.defaultLabel(var6));
               if ("leaderboard".equals(var6) && "&6&lTop Kills &7(Right-click)".equals(var8)) {
                  var8 = "&6&lLeaderboards &7(Right-click)";
               }

               if ("editor".equals(var6) && "&a&lKits &7(Right-click)".equals(var8)) {
                  var8 = this.defaultLabel("editor");
               }

               this.specs
                  .put(
                     var4,
                     new LobbyNpcs.Spec(
                        var4,
                        var6,
                        var7,
                        var1.getDouble(var5 + "x"),
                        var1.getDouble(var5 + "y"),
                        var1.getDouble(var5 + "z"),
                        (float)var1.getDouble(var5 + "yaw"),
                        var8
                     )
                  );
            }
         }
      }
   }

   void spawnSaved() {
      if (this.plugin.getConfig().getBoolean("npcs.enabled", true)) {
         for (LobbyNpcs.Spec var2 : new ArrayList<>(this.specs.values())) {
            this.ensureSpawned(var2);
         }
      }
   }

   Collection<String> describe() {
      return this.specs.values().stream().map(var0 -> var0.id + " = " + var0.kind + " (" + var0.world + ")").toList();
   }

   List<String> ids() {
      return List.copyOf(this.specs.keySet());
   }

   String create(String var1, Player var2) throws IOException {
      String var3 = normalizeKind(var1);
      if (!KINDS.contains(var3)) {
         return null;
      }

      int var4 = 1;

      while (this.specs.containsKey(var3 + var4)) {
         var4++;
      }

      String var5 = var3 + var4;
      Location var6 = this.placement(var2);
      LobbyNpcs.Spec var7 = new LobbyNpcs.Spec(
         var5, var3, var6.getWorld().getName(), var6.getX(), var6.getY(), var6.getZ(), var6.getYaw(), this.defaultLabel(var3)
      );
      this.specs.put(var5, var7);

      try {
         this.save();
      } catch (IOException var9) {
         this.specs.remove(var5);
         throw var9;
      }

      this.ensureSpawned(var7);
      return var5;
   }

   boolean remove(String var1) throws IOException {
      String var2 = var1.toLowerCase(Locale.ROOT);
      LobbyNpcs.Spec var3 = this.specs.remove(var2);
      if (var3 == null) {
         return false;
      }

      try {
         this.save();
      } catch (IOException var5) {
         this.specs.put(var2, var3);
         throw var5;
      }

      this.removeSpawned(var3);
      return true;
   }

   boolean move(String var1, Player var2) throws IOException {
      String var3 = var1.toLowerCase(Locale.ROOT);
      LobbyNpcs.Spec var4 = this.specs.get(var3);
      if (var4 == null) {
         return false;
      }

      Location var5 = this.placement(var2);
      LobbyNpcs.Spec var6 = new LobbyNpcs.Spec(var3, var4.kind, var5.getWorld().getName(), var5.getX(), var5.getY(), var5.getZ(), var5.getYaw(), var4.label);
      this.specs.put(var3, var6);

      try {
         this.save();
      } catch (IOException var8) {
         this.specs.put(var3, var4);
         throw var8;
      }

      this.removeSpawned(var4);
      this.ensureSpawned(var6);
      return true;
   }

   boolean rename(String var1, String var2) throws IOException {
      String var3 = var1.toLowerCase(Locale.ROOT);
      LobbyNpcs.Spec var4 = this.specs.get(var3);
      if (var4 != null && !var2.isBlank() && var2.length() <= 80) {
         LobbyNpcs.Spec var5 = new LobbyNpcs.Spec(var3, var4.kind, var4.world, var4.x, var4.y, var4.z, var4.yaw, var2);
         this.specs.put(var3, var5);

         try {
            this.save();
         } catch (IOException var7) {
            this.specs.put(var3, var4);
            throw var7;
         }

         this.ensureSpawned(var5);
         return true;
      } else {
         return false;
      }
   }

   private static String normalizeKind(String var0) {
      String var1 = var0.toLowerCase(Locale.ROOT);

      return switch (var1) {
         case "rtpq", "matchmaking" -> "queue";
         case "rank", "competitive" -> "ranked";
         case "lobby", "menu", "riftmenu" -> "hub";
         case "spectator", "watch" -> "spectate";
         case "kit", "kits", "kiteditor" -> "editor";
         case "kills", "top", "leaderboard" -> "leaderboard";
         default -> var1;
      };
   }

   private String defaultLabel(String var1) {
      FileConfiguration var10000 = this.plugin.getConfig();
      String var10001 = "npcs.labels." + var1;

      return var10000.getString(var10001, switch (var1) {
         case "queue" -> "&d&lRTP Queue &7(Right-click)";
         case "rtp" -> "&b&lRandom TP &7(Right-click)";
         case "leaderboard" -> "&6&lLeaderboards &7(Right-click)";
         case "editor" -> "&a&lKit Editor &7(Right-click)";
         case "heal" -> "&c&lHeal &7(Right-click)";
         case "ranked" -> "&d&lRANKED &7(Right-click)";
         case "spectate" -> "&d&lSPECTATE &7(Right-click)";
         case "hub" -> "&d&lHUB &7(Right-click)";
         default -> "&fNPC";
      });
   }

   private static boolean validId(String var0) {
      return var0.matches("[a-z0-9_-]{1,32}");
   }

   private Location placement(Player var1) {
      Location var2 = var1.getLocation().clone();
      var2.setX(var2.getBlockX() + 0.5);
      var2.setZ(var2.getBlockZ() + 0.5);
      var2.setPitch(0.0F);
      var2.setYaw(var1.getLocation().getYaw());
      return var2;
   }

   private void save() throws IOException {
      YamlConfiguration var1 = new YamlConfiguration();

      for (LobbyNpcs.Spec var3 : this.specs.values()) {
         String var4 = "npcs." + var3.id + ".";
         var1.set(var4 + "kind", var3.kind);
         var1.set(var4 + "world", var3.world);
         var1.set(var4 + "x", var3.x);
         var1.set(var4 + "y", var3.y);
         var1.set(var4 + "z", var3.z);
         var1.set(var4 + "yaw", var3.yaw);
         var1.set(var4 + "label", var3.label);
      }

      File var6 = new File(this.file.getParentFile(), "npcs.yml.new");
      Files.writeString(var6.toPath(), var1.saveToString(), StandardCharsets.UTF_8);

      try {
         Files.move(var6.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException var5) {
         Files.move(var6.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }
   }

   private Location location(LobbyNpcs.Spec var1) {
      World var2 = Bukkit.getWorld(var1.world);
      return var2 == null ? null : new Location(var2, var1.x, var1.y, var1.z, var1.yaw, 0.0F);
   }

   private void ensureSpawned(LobbyNpcs.Spec var1) {
      if (this.plugin.getConfig().getBoolean("npcs.enabled", true)) {
         Location var2 = this.location(var1);
         if (var2 != null) {
            World var3 = var2.getWorld();
            int var4 = var2.getBlockX() >> 4;
            int var5 = var2.getBlockZ() >> 4;
            Chunk var6 = var3.getChunkAt(var4, var5);
            Entity var7 = null;

            for (Entity var11 : var6.getEntities()) {
               if (var1.id.equals(this.idOf(var11))) {
                  if (var7 == null) {
                     var7 = var11;
                  } else {
                     var11.remove();
                  }
               }
            }

            if (var7 != null && !(var7 instanceof Mannequin)) {
               var7.remove();
               var7 = null;
            }

            if (var7 != null) {
               this.setupEntity(var7, var1);
            } else {
               EntityType var13 = this.configuredType();

               try {
                  this.setupEntity(var3.spawnEntity(var2, var13), var1);
               } catch (IllegalArgumentException | IllegalStateException var12) {
                  this.plugin.getLogger().warning("Could not spawn RiftPvP NPC " + var1.id + ": " + var12.getMessage());
               }
            }
         }
      }
   }

   private EntityType configuredType() {
      return EntityType.MANNEQUIN;
   }

   private void setupEntity(Entity var1, LobbyNpcs.Spec var2) {
      var1.setRotation(var2.yaw, 0.0F);
      var1.getPersistentDataContainer().set(this.idKey, PersistentDataType.STRING, var2.id);
      var1.setCustomName(RiftPvpPlugin.colors(var2.label));
      var1.setCustomNameVisible(true);
      var1.setInvulnerable(true);
      var1.setPersistent(true);
      var1.setGravity(false);
      var1.setGlowing(this.plugin.getConfig().getBoolean("npcs.glowing", false));
      var1.setSilent(true);
      if (var1 instanceof Mannequin var3) {
         var3.setImmovable(true);
         var3.setDescription(null);
         String var4 = this.plugin.getConfig().getString("npcs.skin.username", "bgps");
         if (var4 == null || var4.isBlank()) {
            var4 = "bgps";
         }

         Builder var5 = ResolvableProfile.resolvableProfile().name(var4);
         if (this.plugin.getConfig().getBoolean("npcs.skin.use-resource-pack", false)) {
            String var6 = this.plugin.getConfig().getString("npcs.skin.texture", "riftpvp:entity/bgps");

            try {
               SkinModel var7 = this.plugin.getConfig().getBoolean("npcs.skin.slim-arms", false) ? SkinModel.SLIM : SkinModel.CLASSIC;
               var5.skinPatch((SkinPatch)SkinPatch.skinPatch().body(Key.key(var6)).model(var7).build());
            } catch (IllegalArgumentException var8) {
               this.plugin.getLogger().warning("Invalid NPC skin texture key '" + var6 + "': " + var8.getMessage());
            }
         }

         var3.setProfile((ResolvableProfile)var5.build());
      }
   }

   private void removeSpawned(LobbyNpcs.Spec var1) {
      Location var2 = this.location(var1);
      if (var2 != null) {
         Chunk var3 = var2.getWorld().getChunkAt(var2.getBlockX() >> 4, var2.getBlockZ() >> 4);

         for (Entity var7 : var3.getEntities()) {
            if (var1.id.equals(this.idOf(var7))) {
               var7.remove();
            }
         }
      }
   }

   private String idOf(Entity var1) {
      return (String)var1.getPersistentDataContainer().get(this.idKey, PersistentDataType.STRING);
   }

   @EventHandler
   public void onChunkLoad(ChunkLoadEvent var1) {
      if (this.plugin.getConfig().getBoolean("npcs.enabled", true)) {
         List<LobbyNpcs.Spec> var2 = this.specs
            .values()
            .stream()
            .filter(
               var1x -> var1x.world.equals(var1.getWorld().getName())
                  && (int)Math.floor(var1x.x) >> 4 == var1.getChunk().getX()
                  && (int)Math.floor(var1x.z) >> 4 == var1.getChunk().getZ()
            )
            .toList();
         if (!var2.isEmpty()) {
            Bukkit.getScheduler().runTask(this.plugin, () -> var2.forEach(this::ensureSpawned));
         }
      }
   }

   @EventHandler
   public void onWorldLoad(WorldLoadEvent var1) {
      Bukkit.getScheduler().runTask(this.plugin, this::spawnSaved);
   }

   /**
    * Build-world mannequins may have been placed before this plugin and do not
    * contain lobby-npc-id PDC. Accept ONLY the three explicit labels inside
    * the configured lobby, without modifying or respawning the build entities.
    */
   private static String actionFromLabel(String label) {
      if (label == null || label.isBlank()) return null;
      String plain = ChatColor.stripColor(label.replaceAll("(?i)&[0-9a-fk-or]", ""));
      if (plain == null) return null;
      plain = plain.replaceAll("[^A-Za-z0-9]+", " ").trim().toLowerCase(Locale.ROOT);
      return switch (plain) {
         case "spectate", "spectate right click" -> "spectate";
         case "hub", "hub right click" -> "hub";
         case "ranked", "ranked right click" -> "ranked";
         default -> null;
      };
   }

   private String kindOf(Entity entity) {
      String id = idOf(entity);
      LobbyNpcs.Spec saved = id == null ? null : this.specs.get(id);
      if (saved != null) return saved.kind;
      if (!(entity instanceof Mannequin)) return null;
      // Never hijack mannequins outside the server's configured lobby.
      if (!this.plugin.inLobbyItems(entity.getLocation())) return null;
      String bound = entity.getPersistentDataContainer().get(this.actionKey, PersistentDataType.STRING);
      if (bound != null && KINDS.contains(bound)) return bound;
      String label = actionFromLabel(entity.getCustomName());
      if (label != null) return label;
      // Some prebuilt maps use a separate TextDisplay above the mannequin.
      for (Entity near : entity.getNearbyEntities(1.25, 3.0, 1.25)) {
         if (near instanceof TextDisplay text) {
            String detected = actionFromLabel(text.getText());
            if (detected != null) return detected;
            detected = actionFromLabel(text.getCustomName());
            if (detected != null) return detected;
         }
      }
      return null;
   }

   /** Optional explicit binding if a schematic's text cannot be detected. */
   boolean bindLooking(Player player, String requested) {
      String kind = normalizeKind(requested);
      if (!KINDS.contains(kind) && !"none".equals(kind)) return false;
      RayTraceResult hit = player.getWorld().rayTraceEntities(
          player.getEyeLocation(), player.getEyeLocation().getDirection(), 6.0,
          0.45, candidate -> candidate instanceof Mannequin);
      if (hit == null || !(hit.getHitEntity() instanceof Mannequin npc)) return false;
      if (!this.plugin.inLobbyItems(npc.getLocation())) return false;
      if ("none".equals(kind)) npc.getPersistentDataContainer().remove(this.actionKey);
      else npc.getPersistentDataContainer().set(this.actionKey, PersistentDataType.STRING, kind);
      return true;
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
   public void onInteract(PlayerInteractEntityEvent event) {
      String kind = kindOf(event.getRightClicked());
      if (kind == null) return;
      // Also cancels mannequin interactions so another plugin cannot change it.
      event.setCancelled(true);
      if (event.getHand() != EquipmentSlot.HAND) return;
      Player player = event.getPlayer();
      Bukkit.getScheduler().runTask(this.plugin, () -> {
         if (!player.isOnline() || !this.plugin.isEnabled()) return;
         String permission = switch (kind) {
            case "hub" -> "riftpvp.queue";
            case "ranked" -> "riftpvp.ranked";
            case "spectate" -> "riftpvp.spectate";
            case "queue" -> "riftpvp.queue";
            case "rtp" -> "riftpvp.rtp";
            case "leaderboard" -> "riftpvp.leaderboard";
            case "editor" -> "riftpvp.kiteditor";
            case "heal" -> "riftpvp.heal";
            default -> null;
         };
         if (permission == null || !this.plugin.ranks().hasPluginPermission(player, permission)) {
            player.sendMessage("§cYou don't have permission to use this NPC.");
            return;
         }
         this.plugin.sounds().play(player, "npc-click");
         switch (kind) {
            case "hub" -> this.plugin.riftMenu().open(player);
            case "ranked" -> this.plugin.menus().openRanked(player);
            case "spectate" -> this.plugin.menus().openSpectate(player);
            case "queue" -> this.plugin.menus().openQueue(player);
            case "rtp" -> this.plugin.menus().openRtp(player);
            case "leaderboard" -> this.plugin.menus().cycleLeaderboard(player);
            case "editor" -> this.plugin.menus().openEditorSelection(player);
            case "heal" -> player.performCommand("heal");
            default -> { }
         }
      });
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onDamage(EntityDamageEvent var1) {
      if (this.idOf(var1.getEntity()) != null) {
         var1.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onCombust(EntityCombustEvent var1) {
      if (this.idOf(var1.getEntity()) != null) {
         var1.setCancelled(true);
      }
   }

   private record Spec(String id, String kind, String world, double x, double y, double z, float yaw, String label) {
   }
}
