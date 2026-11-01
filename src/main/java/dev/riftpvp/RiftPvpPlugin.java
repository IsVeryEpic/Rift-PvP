package dev.riftpvp;

import java.io.IOException;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.potion.PotionEffect;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class RiftPvpPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
   private KitManager kits;
   private SafeTeleport safeTeleport;
   private CombatTag combat;
   private MatchQueue matchQueue;
   private StatsSidebar sidebar;
   private RankManager ranks;
   private MenuHandler menus;
   private KitLayouts layouts;
   private KitRoom kitRoom;
   private LobbyPerks lobbyPerks;
   private LobbyVisibility lobbyVisibility;
   private PlayerPreferences playerPreferences;
   private MatchLedger matchLedger;
   private MatchRecovery matchRecovery;
   private AdminFeatures adminFeatures;
   private AdminPanel adminPanel;
   private LobbyNpcs npcs;
   private SoundEffects sounds;
   private VipTrims trims;
   private PracticeFeatures practice;
   private KitCreator kitCreator;
   private TeleportRequests teleportRequests;
   private StaffTools staffTools;
   private LobbyHotbar lobbyHotbar;
   private RiftMenu riftMenu;
   private LobbyInventoryReset lobbyInventoryReset;
   private CustomKits customKits;
   private GroundEntityCleanup groundEntityCleanup;
   private FightAnalytics fightAnalytics;
   private RankedIntegrity rankedIntegrity;
   private StaffAudit staffAudit;
   private DiscordBridge discord;
   private DataBackups dataBackups;
   private MaintenanceControl maintenance;
   private KitModes kitModes;
   private FirstJoinTutorial firstJoinTutorial;
   private MatchReconnect matchReconnect;
   private final Map<UUID, Long> rtpTimes = new HashMap<>();
   private final Set<UUID> teleporting = new HashSet<>();
   private final Set<UUID> pendingLobbyRespawns = new HashSet<>();

   public void onEnable() {
      try { new DataBackups(this).applyPendingRestoreAtStartup(); }
      catch (IOException error) {
         getLogger().severe("PENDING BACKUP RESTORE FAILED: " + error.getMessage() + ". Plugin disabled to protect data.");
         Bukkit.getPluginManager().disablePlugin(this);
         return;
      }
      this.saveDefaultConfig();
      this.migratePreviousDefaults();
      this.migrateNewFeatures();
      // The immediate-forfeit build shipped reconnect.enabled=false. Restore
      // the grace mechanic for existing installations once, without repeatedly
      // overriding an administrator's future preference.
      if (!getConfig().getBoolean("migrations.v1-3-4-restore-reconnect", false)) {
         getConfig().set("reconnect.enabled", true);
         getConfig().set("migrations.v1-3-4-restore-reconnect", true);
         saveConfig();
      }
      this.migrateNpcSkins();
      this.migrateHudFeatures();
      this.migrateNpcEditor();
      this.migrateLobbySettings();
      this.sounds = new SoundEffects(this);
      this.staffAudit = new StaffAudit(this);
      this.maintenance = new MaintenanceControl(this);
      this.dataBackups = new DataBackups(this);
      this.discord = new DiscordBridge(this);
      this.rankedIntegrity = new RankedIntegrity(this);
      this.kits = new KitManager(this);
      this.kitModes = new KitModes(this);
      this.safeTeleport = new SafeTeleport(this);
      this.sidebar = new StatsSidebar(this);
      this.ranks = new RankManager(this);
      this.combat = new CombatTag(this);
      this.matchQueue = new MatchQueue(this);
      this.customKits = new CustomKits(this);
      this.groundEntityCleanup = new GroundEntityCleanup(this);
      this.menus = new MenuHandler(this);
      this.layouts = new KitLayouts(this);
      this.kitRoom = new KitRoom(this);
      this.lobbyPerks = new LobbyPerks(this);
      this.lobbyVisibility = new LobbyVisibility(this);
      this.playerPreferences = new PlayerPreferences(this);
      this.matchLedger = new MatchLedger(this);
      this.fightAnalytics = new FightAnalytics(this);
      this.matchRecovery = new MatchRecovery(this);
      this.matchReconnect = new MatchReconnect(this);
      this.firstJoinTutorial = new FirstJoinTutorial(this);
      this.adminFeatures = new AdminFeatures(this);
      this.adminPanel = new AdminPanel(this);
      this.trims = new VipTrims(this);
      this.practice = new PracticeFeatures(this);
      this.kitCreator = new KitCreator(this);
      this.teleportRequests = new TeleportRequests(this);
      this.staffTools = new StaffTools(this);
      this.lobbyHotbar = new LobbyHotbar(this);
      this.lobbyInventoryReset = new LobbyInventoryReset(this);
      this.npcs = new LobbyNpcs(this);
      this.riftMenu = new RiftMenu(this);
      Bukkit.getPluginManager().registerEvents(this.riftMenu, this);
      PluginCommand hubCommand = this.getCommand("riftmenu");
      if (hubCommand != null) hubCommand.setExecutor(this.riftMenu);
      else getLogger().severe("Missing riftmenu command registration");

      for (Listener var2 : List.of(this.sidebar, this.ranks, this.combat, this.matchQueue, this.menus, this.layouts, this.kitRoom, this.lobbyPerks, this.lobbyVisibility, this.playerPreferences, this.matchRecovery, this.adminFeatures, this.adminPanel, this.trims, this.npcs, this.practice, this.kits, this.kitCreator, this.customKits, this.teleportRequests, this.staffTools, this.lobbyHotbar, this.lobbyInventoryReset, this.maintenance, this.firstJoinTutorial, this.matchReconnect)) {
         Bukkit.getPluginManager().registerEvents(var2, this);
      }
      Bukkit.getPluginManager().registerEvents(this, this);

      for (String var5 : List.of("kit", "rtpq", "rtp", "heal", "lobby", "riftpvp", "kiteditor", "kitcreate", "kitsettings", "kitdelete", "kitrestore", "kitmodes", "customkit", "tutorial", "leaderboard",
            "duel", "rematch", "ranked", "stats", "fightstats", "queueprefs", "spectate", "unspectate", "fightresult", "party", "tpa", "tpahere", "tpaccept", "tpdeny", "tpcancel", "visibility", "settings", "rank", "profile", "matchhistory", "matchinfo", "report", "match", "staff", "riftkick", "rtempban", "rtunban")) {
         PluginCommand var3 = this.getCommand(var5);
         if (var3 == null) {
            this.getLogger().severe("Missing command from plugin.yml: " + var5);
         } else {
            var3.setExecutor(this);
            var3.setTabCompleter(this);
         }
      }

      Bukkit.getOnlinePlayers().forEach(this.sidebar::refresh);
      this.matchRecovery.recoverOnline();
      Bukkit.getScheduler().runTaskLater(this, this.npcs::spawnSaved, 20L);
      Bukkit.getScheduler().runTaskTimer(this, () -> this.sidebar.tickHud(), 40L, Math.max(20L, this.getConfig().getLong("tablist.update-ticks", 40L)));
      Bukkit.getScheduler().runTaskTimer(this, () -> this.sidebar.save(), 1200L, 1200L);
      // Matchmaking must be re-evaluated as queued players' allowable Elo
      // separation increases, even if nobody new joins the queue.
      Bukkit.getScheduler().runTaskTimer(this, this.practice::retryRankedMatches, 100L, 100L);
      Bukkit.getScheduler().runTaskTimer(this, this.matchQueue::retryEligibleQueues, 100L, 100L);
      Bukkit.getScheduler().runTaskTimer(this, this.customKits::retryEligibleHosts, 100L, 100L);
      Bukkit.getScheduler().runTaskTimer(this, this.kits::enforceEquippedXpLimits, 10L, 10L);
      Bukkit.getScheduler().runTaskTimer(this, this.lobbyPerks::tick, 20L, 20L);
      Bukkit.getScheduler().runTaskTimer(this, this.lobbyVisibility::tick, 30L, 40L);
      Bukkit.getScheduler().runTaskTimer(this, this.lobbyHotbar::tick, 25L, 40L);
      this.groundEntityCleanup.start();
      this.dataBackups.start();
      this.getLogger()
         .info(
            "Rift PvP loaded " + this.kits.configuredIds().stream().filter(this.kits::hasKit).count() + " kits. Data files are isolated from Minecraft worlds."
         );
   }

   public void onDisable() {
      if (this.groundEntityCleanup != null) this.groundEntityCleanup.stop();
      if (this.sidebar != null) {
         this.sidebar.finishSessions();
      }

      if (this.matchQueue != null) {
         this.matchQueue.clearAll();
      }
      if (this.customKits != null) this.customKits.close();
      if (this.practice != null) this.practice.close();
      if (this.teleportRequests != null) this.teleportRequests.close();
      if (this.staffTools != null) this.staffTools.close();
      if (this.kitRoom != null) this.kitRoom.closeAll();
      if (this.lobbyPerks != null) Bukkit.getOnlinePlayers().forEach(this.lobbyPerks::remove);
      if (this.lobbyVisibility != null) this.lobbyVisibility.close();
      if (this.lobbyHotbar != null) Bukkit.getOnlinePlayers().forEach(this.lobbyHotbar::remove);
   }

   /**
    * Other plugins commonly register an OP-only /heal. Route the unqualified,
    * self-heal command to RiftPvP before their command permission is checked.
    * The regular RiftPvP permission and combat checks still run in onCommand.
    * /otherplugin:heal and /heal <player> keep their original behavior.
    */
   @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
   public void onPublicHeal(PlayerCommandPreprocessEvent event) {
      if (!this.getConfig().getBoolean("commands.prefer-rift-heal", true)) {
         return;
      }
      String input = event.getMessage().trim();
      if (!input.equalsIgnoreCase("/heal")) {
         return;
      }
      PluginCommand healCommand = this.getCommand("heal");
      if (healCommand == null) {
         return;
      }
      event.setCancelled(true);
      this.onCommand(event.getPlayer(), healCommand, "heal", new String[0]);
   }

   KitManager kits() {
      return this.kits;
   }

   CombatTag combat() {
      return this.combat;
   }

   PlayerPreferences playerPreferences() { return this.playerPreferences; }
   MatchRecovery matchRecovery() { return this.matchRecovery; }
   StaffTools staffTools() { return this.staffTools; }
   LobbyHotbar lobbyHotbar() { return this.lobbyHotbar; }
   AdminPanel adminPanel() { return this.adminPanel; }
   RiftMenu riftMenu() { return this.riftMenu; }
   LobbyInventoryReset lobbyInventoryReset() { return this.lobbyInventoryReset; }
   GroundEntityCleanup groundEntityCleanup() { return this.groundEntityCleanup; }
   CustomKits customKits() { return this.customKits; }
   LobbyVisibility lobbyVisibility() { return this.lobbyVisibility; }

   DataBackups dataBackups() { return dataBackups; }
   MaintenanceControl maintenance() { return maintenance; }
   KitModes kitModes() { return kitModes; }
   MatchReconnect matchReconnect() { return matchReconnect; }

   MatchQueue matchQueue() {
      return this.matchQueue;
   }

   SafeTeleport safeTeleport() {
      return this.safeTeleport;
   }

   StatsSidebar sidebar() {
      return this.sidebar;
   }

   RankManager ranks() {
      return this.ranks;
   }

   MenuHandler menus() {
      return this.menus;
   }

   KitLayouts layouts() {
      return this.layouts;
   }

   VipTrims trims() {
      return this.trims;
   }

   PracticeFeatures practice() {
      return this.practice;
   }

   LobbyNpcs npcs() {
      return this.npcs;
   }

   SoundEffects sounds() {
      return this.sounds;
   }

   private void migratePreviousDefaults() {
      if (!this.getConfig().getBoolean("migrations.v1-1-5-defaults", false)) {
         if (this.getConfig().getInt("combat.tag-seconds", 20) == 20) {
            this.getConfig().set("combat.tag-seconds", 10);
         }

         if (this.getConfig().getInt("rtp.max-distance", 1000000) == 1000000) {
            this.getConfig().set("rtp.max-distance", 200000);
         }

         ConfigurationSection var1 = this.getConfig().getDefaults() == null ? null : this.getConfig().getDefaults().getConfigurationSection("sounds");
         if (var1 != null) {
            for (String var3 : var1.getKeys(true)) {
               if (!var1.isConfigurationSection(var3)) {
                  String var4 = "sounds." + var3;
                  if (!this.getConfig().isSet(var4)) {
                     this.getConfig().set(var4, var1.get(var3));
                  }
               }
            }
         }

         this.getConfig().set("migrations.v1-1-5-defaults", true);
         this.saveConfig();
      }
   }

   private void migrateNewFeatures() {
      if (!this.getConfig().getBoolean("migrations.v1-2-0-features", false)) {
         Configuration var1 = this.getConfig().getDefaults();
         if (var1 != null) {
            for (String var3 : List.of(
               "menus.editor-title",
               "menus.editor-size",
               "menus.editor-lore",
               "menus.leaderboard-title",
               "menus.deaths-leaderboard-title",
               "npcs.enabled",
               "npcs.entity-type",
               "npcs.glowing",
               "npcs.labels.queue",
               "npcs.labels.rtp",
               "npcs.labels.leaderboard",
               "npcs.labels.kit",
               "npcs.labels.heal",
               "sounds.npc-click.name",
               "sounds.npc-click.volume",
               "sounds.npc-click.pitch"
            )) {
               if (!this.getConfig().isSet(var3)) {
                  this.getConfig().set(var3, var1.get(var3));
               }
            }
         }

         this.getConfig().set("migrations.v1-2-0-features", true);
         this.saveConfig();
      }
   }

   private void migrateNpcSkins() {
      if (!this.getConfig().getBoolean("migrations.v1-2-1-player-skins", false)) {
         this.getConfig().set("npcs.entity-type", "MANNEQUIN");
         Configuration var1 = this.getConfig().getDefaults();
         if (var1 != null) {
            for (String var3 : List.of("npcs.skin.username", "npcs.skin.use-resource-pack", "npcs.skin.texture", "npcs.skin.slim-arms")) {
               if (!this.getConfig().isSet(var3)) {
                  this.getConfig().set(var3, var1.get(var3));
               }
            }
         }

         this.getConfig().set("migrations.v1-2-1-player-skins", true);
         this.saveConfig();
      }
   }

   private void migrateHudFeatures() {
      if (!this.getConfig().getBoolean("migrations.v1-2-2-hud", false)) {
         if ("&6&lTop Kills &7(Right-click)".equals(this.getConfig().getString("npcs.labels.leaderboard"))) {
            this.getConfig().set("npcs.labels.leaderboard", "&6&lLeaderboards &7(Right-click)");
         }

         Configuration var1 = this.getConfig().getDefaults();
         if (var1 != null) {
            for (String var3 : List.of(
               "menus.playtime-leaderboard-title",
               "scoreboard.show-ping",
               "scoreboard.ping-label",
               "tablist.enabled",
               "tablist.subtitle",
               "tablist.footer",
               "tablist.update-ticks",
               "nametags.enabled",
               "npcs.labels.leaderboard"
            )) {
               if (!this.getConfig().isSet(var3)) {
                  this.getConfig().set(var3, var1.get(var3));
               }
            }
         }

         this.getConfig().set("migrations.v1-2-2-hud", true);
         this.saveConfig();
      }
   }

   private void migrateNpcEditor() {
      if (!this.getConfig().getBoolean("migrations.v1-2-3-npc-editor", false)) {
         String var1 = "npcs.labels.editor";
         if (!this.getConfig().isSet(var1)) {
            this.getConfig().set(var1, "&a&lKit Editor &7(Right-click)");
         }

         this.getConfig().set("migrations.v1-2-3-npc-editor", true);
         this.saveConfig();
      }
   }

   private void migrateLobbySettings() {
      if (!this.getConfig().getBoolean("migrations.v1-2-4-lobby", false)) {
         if (!this.getConfig().isSet("combat.block-lobby")) {
            this.getConfig().set("combat.block-lobby", true);
         }

         this.getConfig().set("migrations.v1-2-4-lobby", true);
         this.saveConfig();
      }
   }

   static String colors(String var0) {
      return ChatColor.translateAlternateColorCodes('&', var0 == null ? "" : var0);
   }

   void message(CommandSender var1, String var2, String... var3) {
      String var4 = this.getConfig().getString("messages.prefix", "&5Rift PvP &8» &r") + this.getConfig().getString("messages." + var2, "&e" + var2);

      for (byte var5 = 0; var5 + 1 < var3.length; var5 = (byte)(var5 + 2)) {
         var4 = var4.replace("%" + var3[var5] + "%", var3[var5 + 1]);
      }

      var1.sendMessage(colors(var4));
   }

   void heal(Player var1) {
      if (this.getConfig().getBoolean("heal.health", true)) {
         AttributeInstance var2 = var1.getAttribute(Attribute.MAX_HEALTH);
         if (var2 != null) {
            var1.setHealth(Math.max(1.0, var2.getValue()));
         }
      }

      var1.setFoodLevel(Math.max(0, Math.min(20, this.getConfig().getInt("heal.food-level", 20))));
      var1.setSaturation((float)Math.max(0.0, Math.min(20.0, this.getConfig().getDouble("heal.saturation", 20.0))));
      if (this.getConfig().getBoolean("heal.clear-absorption", true)) {
         var1.setAbsorptionAmount(0.0);
      }

      var1.setFireTicks(0);
   }

   KitRoom kitRoom() { return kitRoom; }
   LobbyPerks lobbyPerks() { return lobbyPerks; }
   MatchLedger matchLedger() { return matchLedger; }
   AdminFeatures adminFeatures() { return adminFeatures; }

   void equipKit(Player var1, String var2) {
      if (this.inLobby(var1.getLocation())) {
         var1.sendMessage(colors("&c/kit cannot be used in the lobby. Use /kiteditor instead."));
         return;
      }
      if (!this.ranks.hasPluginPermission(var1, "riftpvp.kit")) {
         this.message(var1, "no-permission");
      } else if (this.combat.mayUse(var1, "kit")) {
         if (!this.kits.hasKit(var2)) {
            this.message(var1, "kit-not-loaded");
         } else if (this.kits.equip(var1, var2)) {
            this.matchLedger.kitEvent(var1, var2, true);
            this.message(var1, "kit-equipped", "kit", this.kits.name(var2));
            this.sounds.play(var1, "kit");
         } else {
            this.message(var1, "kit-not-found");
         }
      } else this.matchLedger.kitEvent(var1, var2, false);
   }

   void randomTeleport(Player var1, String var2) {
      if (!this.ranks.hasPluginPermission(var1, "riftpvp.rtp")) {
         this.message(var1, "no-permission");
      } else if (this.getConfig().getBoolean("rtp.enabled", true) && this.combat.mayUse(var1, "rtp")) {
         String var3 = this.getConfig().getString("rtp.worlds." + var2.toLowerCase(Locale.ROOT));
         World var4 = var3 == null ? null : Bukkit.getWorld(var3);
         if (var4 == null) {
            this.message(var1, "rtp-world-missing");
         } else {
            UUID var5 = var1.getUniqueId();
            if (this.teleporting.contains(var5)) {
               this.message(var1, "rtp-busy");
            } else {
               long var6 = System.currentTimeMillis();
               int var8 = Math.max(0, this.getConfig().getInt("rtp.cooldown-seconds", 20));
               long var9 = this.rtpTimes.getOrDefault(var5, 0L) + var8 * 1000L - var6;
               if (var9 > 0L) {
                  this.message(var1, "rtp-cooldown", "seconds", Long.toString((var9 + 999L) / 1000L));
               } else {
                  this.teleporting.add(var5);
                  this.message(var1, "rtp-starting", "world", var2);
                  if (!this.safeTeleport.find(var4, false, var3x -> {
                     if (this.isEnabled()) {
                        if (!var1.isOnline() || !this.teleporting.contains(var5)) {
                           this.teleporting.remove(var5);
                        } else if (var3x == null) {
                           this.teleporting.remove(var5);
                           this.message(var1, "rtp-failed");
                        } else if (this.combat.tagged(var1)) {
                           this.teleporting.remove(var5);
                           this.combat.mayUse(var1, "rtp");
                        } else {
                           var1.teleportAsync(var3x).whenComplete((var3xx, var4x) -> {
                              if (this.isEnabled()) {
                                 Bukkit.getScheduler().runTask(this, () -> {
                                    this.teleporting.remove(var5);
                                    if (var4x == null && Boolean.TRUE.equals(var3xx)) {
                                       this.rtpTimes.put(var5, System.currentTimeMillis());
                                       this.message(var1, "rtp-success");
                                       this.sounds.play(var1, "teleport");
                                    } else {
                                       this.message(var1, "rtp-failed");
                                    }
                                 });
                              }
                           });
                        }
                     }
                  })) {
                     this.teleporting.remove(var5);
                     this.message(var1, "rtp-busy");
                  }
               }
            }
         }
      }
   }

   // Cancel all incoming damage at the actual protected lobby, including falls,
   // explosions, void damage and environmental sources. The larger horizontal
   // menu-item radius is intentionally NOT used for combat immunity.
   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
   public void onLobbyDamage(EntityDamageEvent event) {
      if (event.getEntity() instanceof Player player && inLobby(player.getLocation())) {
         event.setDamage(0.0);
         event.setCancelled(true);
         player.setFireTicks(0);
         player.setFallDistance(0f);
      }
   }

   boolean inLobby(Location location) {
      if (location == null || location.getWorld() == null || !getConfig().isSet("lobby.world")) return false;
      if (!location.getWorld().getName().equals(getConfig().getString("lobby.world"))) return false;
      Location lobby = new Location(location.getWorld(), getConfig().getDouble("lobby.x"),
         getConfig().getDouble("lobby.y"), getConfig().getDouble("lobby.z"));
      double radius = Math.max(8.0, getConfig().getDouble("lobby.protection-radius", 48.0));
      return lobby.distanceSquared(location) <= radius * radius;
   }

   /**
    * A separate horizontal boundary for temporary lobby hotbar/VIP items.
    * The much smaller lobby protection-radius still governs combat, kit use,
    * teleport restrictions and lobby inventory clearing.
    * Match locations are kept >=250 horizontal blocks from spawn; clamp the
    * item radius below that minimum so flight tools cannot reach a fight.
    */
   boolean inLobbyItems(Location location) {
      if (location == null || location.getWorld() == null || !getConfig().isSet("lobby.world")) return false;
      if (!location.getWorld().getName().equals(getConfig().getString("lobby.world"))) return false;
      double dx = location.getX() - getConfig().getDouble("lobby.x");
      double dz = location.getZ() - getConfig().getDouble("lobby.z");
      double radius = Math.max(8.0, Math.min(225.0, getConfig().getDouble("lobby.items-radius", 192.0)));
      return dx * dx + dz * dz <= radius * radius;
   }

   private void setLobby(Player var1) {
      Location var2 = var1.getLocation();
      this.getConfig().set("lobby.world", var2.getWorld().getName());
      this.getConfig().set("lobby.x", var2.getX());
      this.getConfig().set("lobby.y", var2.getY());
      this.getConfig().set("lobby.z", var2.getZ());
      this.getConfig().set("lobby.yaw", var2.getYaw());
      this.getConfig().set("lobby.pitch", var2.getPitch());
      this.saveConfig();
      this.staffAudit.log(var1, "set-lobby", var2.getWorld().getName());
      this.message(var1, "lobby-set");
   }

   private void teleportToLobby(Player var1) {
      if (this.combat.mayUse(var1, "lobby")) {
         if (!this.getConfig().isSet("lobby.world")) {
            this.message(var1, "lobby-unset");
         } else {
            World var2 = Bukkit.getWorld(this.getConfig().getString("lobby.world", ""));
            if (var2 == null) {
               this.message(var1, "lobby-world-missing");
            } else {
               Location var3 = new Location(
                  var2,
                  this.getConfig().getDouble("lobby.x"),
                  this.getConfig().getDouble("lobby.y"),
                  this.getConfig().getDouble("lobby.z"),
                  (float)this.getConfig().getDouble("lobby.yaw"),
                  (float)this.getConfig().getDouble("lobby.pitch")
               );
               if (!var1.teleport(var3, TeleportCause.COMMAND)) {
                  this.message(var1, "lobby-failed");
               } else {
                  this.practice.lobby(var1);
                  this.matchQueue.cancelForLobby(var1);
                  this.teleporting.remove(var1.getUniqueId());
                  this.lobbyInventoryReset.clearNow(var1);
                  this.message(var1, "lobby-success");
                  this.sounds.play(var1, "teleport");
               }
            }
         }
      }
   }

   void markManagedDeath(Player player) { pendingLobbyRespawns.add(player.getUniqueId()); }

   @EventHandler(priority = EventPriority.LOWEST)
   public void recordMatchDeath(PlayerDeathEvent event) {
      if (this.matchQueue != null && this.matchQueue.busy(event.getEntity())) {
         this.pendingLobbyRespawns.add(event.getEntity().getUniqueId());
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void returnMatchDeathToLobby(PlayerRespawnEvent event) {
      Player player = event.getPlayer();
      // Every death returns to the configured RiftPvP lobby, even outside a match.
      // Consume the match marker if present, but do not require it.
      this.pendingLobbyRespawns.remove(player.getUniqueId());
      World world = Bukkit.getWorld(this.getConfig().getString("lobby.world", ""));
      if (world == null) {
         this.getLogger().warning("Player died, but the configured lobby world is missing.");
         return;
      }
      Location lobby = new Location(world, getConfig().getDouble("lobby.x"), getConfig().getDouble("lobby.y"),
          getConfig().getDouble("lobby.z"), (float)getConfig().getDouble("lobby.yaw"),
          (float)getConfig().getDouble("lobby.pitch"));
      event.setRespawnLocation(lobby);
      Bukkit.getScheduler().runTaskLater(this, () -> {
         if (!player.isOnline()) return;
         this.lobbyInventoryReset.clearNow(player);
      }, 1L);
   }

   @EventHandler
   public void onPlayerWelcome(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      Bukkit.getScheduler().runTaskLater(this, () -> {
         if (!player.isOnline() || !inLobby(player.getLocation())) return;
         player.sendTitle("§d§lRIFTPVP", "§bYour next fight starts here.", 10, 65, 20);
         player.sendMessage(Component.text("✦ RIFTPVP  ", NamedTextColor.LIGHT_PURPLE)
            .append(Component.text("[FIGHT] ", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/rtpq")))
            .append(Component.text("[KIT EDITOR]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/kiteditor"))));
      }, 15L);
   }

   public boolean onCommand(CommandSender var1, Command var2, String var3, String[] var4) {
      String var5 = var2.getName().toLowerCase(Locale.ROOT);
      if ("match".equals(var5)) {
         if (!var1.isOp()) var1.sendMessage(colors("&cOnly OPs may inspect staff match evidence."));
         else if (var4.length == 2 && var4[0].equalsIgnoreCase("inspect")) matchLedger.inspect(var1, var4[1], true);
         else var1.sendMessage(colors("&cUsage: /match inspect <id>"));
         return true;
      }
      if ("riftpvp".equals(var5)) {
         this.adminCommand(var1, var4);
         return true;
      } else if (!(var1 instanceof Player var6)) {
         this.message(var1, "players-only");
         return true;
      } else {
         String permission = switch (var5) {
            case "rematch", "fightresult" -> "riftpvp.duel";
            case "unspectate" -> "riftpvp.spectate";
            case "rtpq" -> "riftpvp.queue";
            case "kitsettings" -> "riftpvp.kitcreate";
            case "kitmodes" -> "riftpvp.kitmanagement";
            case "tpahere", "tpaccept", "tpdeny", "tpcancel" -> "riftpvp.tpa";
            case "riftkick", "rtempban", "rtunban" -> "riftpvp.staff";
            default -> "riftpvp." + var5;
         };
         if (Set.of("kitcreate", "kitsettings", "kitdelete", "kitrestore", "kitmodes").contains(var5)
               ? !this.ranks.canManageKits(var6)
               : !this.ranks.hasPluginPermission(var6, permission)) {
            this.message(var6, "no-permission");
            return true;
         }

         switch (var5) {
            case "matchhistory":
               matchLedger.history(var6);
               break;
            case "matchinfo":
               if (var4.length != 1) var6.sendMessage(colors("&cUsage: /matchinfo <id>"));
               else matchLedger.inspect(var6, var4[0], false);
               break;
            case "report":
               if (var4.length < 2) var6.sendMessage(colors("&cUsage: /report <player|match-id> <reason>"));
               else matchLedger.report(var6, var4[0], String.join(" ", java.util.Arrays.copyOfRange(var4, 1, var4.length)));
               break;
            case "tutorial":
               this.firstJoinTutorial.show(var6);
               break;
            case "kitmodes":
               this.kitModes.command(var6, var4);
               break;
            case "customkit":
               if (this.maintenance.isActive() && var4.length > 0
                     && Set.of("host", "queue", "join", "invite").contains(var4[0].toLowerCase(Locale.ROOT))) {
                  this.maintenance.allowNewMatch(var6); break;
               }
               this.customKits.command(var6, var4);
               break;
            case "settings":
               this.playerPreferences.open(var6);
               break;
            case "queueprefs":
               this.playerPreferences.queueCommand(var6, var4);
               break;
            case "visibility":
               this.lobbyVisibility.command(var6, var4);
               break;
            case "staff", "riftkick", "rtempban", "rtunban":
               this.staffTools.command(var6, var5, var4);
               break;
            case "tpa", "tpahere", "tpaccept", "tpdeny", "tpcancel":
               if (this.getConfig().getBoolean("features.tpa", true)) this.teleportRequests.command(var6, var5, var4);
               else var6.sendMessage(colors("&cPlayer teleport requests are disabled by staff."));
               break;
            case "duel", "rematch", "ranked", "stats", "fightstats", "rank", "profile", "spectate", "unspectate", "fightresult", "party":
               if (this.maintenance.isActive() && (var5.equals("rematch") || var5.equals("duel") && (var4.length == 0 || !var4[0].equalsIgnoreCase("deny")) || var5.equals("ranked") && (var4.length == 0 || !List.of("leave", "top").contains(var4[0].toLowerCase(Locale.ROOT))))) {
                  this.maintenance.allowNewMatch(var6); break;
               }
               this.practice.command(var6, var5, var4);
               break;
            case "lobby":
               this.teleportToLobby(var6);
               break;
            case "kitcreate":
               if (var4.length != 1) {
                  var6.sendMessage(colors("&cUsage: /kitcreate <id> &7(ex: /kitcreate crystal)"));
               } else {
                  this.kitCreator.create(var6, var4[0].toLowerCase(Locale.ROOT));
               }
               break;
            case "kitsettings":
               if (var4.length != 1) {
                  var6.sendMessage(colors("&cUsage: /kitsettings <kit>"));
               } else {
                  this.kitCreator.settings(var6, var4[0].toLowerCase(Locale.ROOT));
               }
               break;
            case "kitdelete":
               if (var4.length < 1 || var4.length > 2 || var4.length == 2 && !"confirm".equalsIgnoreCase(var4[1]))
                  var6.sendMessage(colors("&cUsage: /kitdelete <kit> [confirm]"));
               else this.kitCreator.delete(var6, var4[0].toLowerCase(Locale.ROOT), var4.length == 2);
               break;
            case "kitrestore":
               if (var4.length != 1) var6.sendMessage(colors("&cUsage: /kitrestore <kit>"));
               else this.kitCreator.restore(var6, var4[0].toLowerCase(Locale.ROOT));
               break;
            case "kiteditor":
               if (var4.length > 0) {
                  this.layouts.open(var6, var4[0].toLowerCase(Locale.ROOT));
               } else {
                  this.menus.openEditorSelection(var6);
               }
               break;
            case "leaderboard":
               String var9 = var3.equalsIgnoreCase("topplaytime")
                  ? "playtime"
                  : (var3.equalsIgnoreCase("topdeaths") ? "deaths" : (var4.length > 0 ? var4[0] : "kills"));
               this.menus.openLeaderboardMode(var6, var9);
               this.sounds.play(var6, "npc-click");
               break;
            case "kit":
               if (this.inLobby(var6.getLocation())) {
                  var6.sendMessage(colors("&c/kit cannot be used in the lobby. Use /kiteditor instead."));
                  break;
               }
               if (var4.length > 0) {
                  this.equipKit(var6, var4[0].toLowerCase(Locale.ROOT));
               } else {
                  this.menus.openKits(var6);
               }
               break;
            case "rtpq":
               if (this.maintenance.isActive() && (var4.length == 0 || !List.of("leave", "cancel").contains(var4[0].toLowerCase(Locale.ROOT)))) {
                  this.maintenance.allowNewMatch(var6); break;
               }
               if (var4.length < 1 || !var4[0].equalsIgnoreCase("leave") && !var4[0].equalsIgnoreCase("cancel")) {
                  if (var4.length >= 1) {
                     this.matchQueue.join(var6, var4[0].toLowerCase(Locale.ROOT));
                  } else {
                     this.menus.openQueue(var6);
                  }
               } else {
                  this.matchQueue.leave(var6);
               }
               break;
            case "rtp":
               if (var4.length > 0) {
                  this.randomTeleport(var6, var4[0]);
               } else {
                  this.menus.openRtp(var6);
               }
               break;
            case "heal":
               if (this.getConfig().getBoolean("heal.enabled", true) && this.combat.mayUse(var6, "heal")) {
                  this.heal(var6);
                  this.message(var6, "heal-done");
                  this.sounds.play(var6, "heal");
               }
               break;
            default:
               return false;
         }

         return true;
      }
   }

   private void adminCommand(CommandSender var1, String[] var2) {
      if (var2.length == 0) {
         var1.sendMessage(colors("&dRift PvP &7| &f/kit, /rtpq, /rtp, /lobby, /heal, /kiteditor, /leaderboard [kills|deaths|playtime]"));
         var1.sendMessage(colors("&7Kit staff: /kitcreate, /kitsettings, /kitdelete, /kitrestore (Admin/Founder/OP)"));
         var1.sendMessage(colors("&7OP admin: /riftpvp setlobby, /riftpvp npc, /riftpvp kit, /riftpvp rank, /riftpvp cleanup, /riftpvp reload"));
      } else if (!this.ranks.isAdmin(var1)) {
         this.message(var1, "no-permission");
      } else {
         String var3 = var2[0].toLowerCase(Locale.ROOT);

         try {
            switch (var3) {
               case "maintenance":
                  this.maintenance.command(var1, var2);
                  break;
               case "backup":
                  this.dataBackups.command(var1, var2);
                  break;
               case "cleanup":
                  this.groundEntityCleanup.command(var1, var2);
                  break;
               case "toggles":
                  if (var1 instanceof Player admin) this.adminPanel.open(admin);
                  else var1.sendMessage("§cOpen the GUI in-game using an OP account.");
                  break;
               case "doctor":
                  this.doctor(var1);
                  break;
               case "audit":
                  this.staffAudit.recent(var1, var2.length > 1 && var2[1].matches("[0-9]{1,2}") ? Integer.parseInt(var2[1]) : 10);
                  break;
               case "integrity":
                  this.rankedIntegrity.report(var1);
                  break;
               case "reload":
                  this.staffAudit.log(var1, "config-reload", "reload request");
                  this.reloadConfig();
                  this.maintenance.reload();
                  this.groundEntityCleanup.start();
      this.dataBackups.start();
                  this.migratePreviousDefaults();
                  this.migrateNewFeatures();
                  this.migrateNpcSkins();
                  this.migrateHudFeatures();
                  this.migrateNpcEditor();
                  this.migrateLobbySettings();
                  this.kits.reload();
                  this.ranks.reload();
                  this.npcs.reload();
                  this.npcs.spawnSaved();
                  this.sidebar.refreshAll();
                  this.message(var1, "admin-reloaded");
                  break;
               case "kit":
                  this.kitAdmin(var1, var2);
                  break;
               case "rank":
                  this.rankAdmin(var1, var2);
                  break;
               case "npc":
                  this.npcAdmin(var1, var2);
                  break;
               case "setlobby":
                  if (var1 instanceof Player var6) {
                     this.setLobby(var6);
                  } else {
                     this.message(var1, "players-only");
                  }
                  break;
               default:
                  var1.sendMessage("Usage: /riftpvp <doctor|audit|integrity|backup|maintenance|toggles|cleanup|reload|setlobby|kit|rank|npc>");
            }
         } catch (IOException | IllegalArgumentException var7) {
            var1.sendMessage(ChatColor.RED + "Rift PvP: " + var7.getMessage());
            this.getLogger().warning("Admin command failed: " + var7.getMessage());
         }
      }
   }

   FightAnalytics fightAnalytics() { return fightAnalytics; }
   RankedIntegrity rankedIntegrity() { return rankedIntegrity; }
   StaffAudit staffAudit() { return staffAudit; }
   DiscordBridge discord() { return discord; }

   private void doctor(CommandSender sender) {
      sender.sendMessage(colors("&d&lRiftPvP Doctor &7| &fServer diagnostics"));
      String lobbyName = this.getConfig().getString("lobby.world", "");
      World lobby = Bukkit.getWorld(lobbyName);
      sender.sendMessage(colors(lobby == null ? "&cFAIL &7Lobby world is missing/unconfigured."
         : "&aPASS &7Lobby world exists: &f" + lobbyName));
      World matchWorld = Bukkit.getWorld(this.getConfig().getString("matchmaking.world", "world"));
      sender.sendMessage(colors(matchWorld == null ? "&cFAIL &7Matchmaking world is missing."
         : "&aPASS &7Matchmaking world available."));
      long count = this.kits.configuredIds().stream().filter(this.kits::hasKit).count();
      sender.sendMessage(colors(count == 0 ? "&cFAIL &7No loaded kits."
         : "&aPASS &7Loaded kits: &f" + count));
      sender.sendMessage(colors(this.getDataFolder().isDirectory() && this.getDataFolder().canWrite()
         ? "&aPASS &7Plugin data folder writable." : "&cFAIL &7Plugin data folder not writable."));
      sender.sendMessage(colors(this.getConfig().getInt("matchmaking.location-separation-blocks", 250) < 250
         ? "&eINFO &7Separation is enforced to minimum 250 blocks in code."
         : "&aPASS &7Match separation configured >=250."));
      sender.sendMessage(colors("&7Java: &f" + System.getProperty("java.version") + " &7| Online players: &f" + Bukkit.getOnlinePlayers().size()));
      sender.sendMessage(colors("&7Analytics active fights: &f" + fightAnalytics.activeCount()));
      sender.sendMessage(colors("&7Discord: " + (getConfig().getBoolean("discord.enabled", false)
         ? (getConfig().getString("discord.webhook-url", "").isBlank() ? "&cENABLED with no URL" : "&eConfigured (delivery not verified)")
         : "&7Disabled")));
      sender.sendMessage(colors("&7Maintenance: " + (maintenance.isActive() ? "&eACTIVE" : "&aOFF")
         + " &7| Restart needed: " + dataBackups.restartRequired()));
      sender.sendMessage(colors("&7Reconnect holds: &f" + matchReconnect.pendingCount()));
      sender.sendMessage(colors("&7Cleanup: " + (getConfig().getBoolean("cleanup.enabled", true) ? "&aEnabled" : "&7Disabled")
         + " &7| Mob removal: " + (getConfig().getBoolean("cleanup.grounded-mobs", false) ? "&cON" : "&aOFF")));
      File[] dataFiles = getDataFolder().listFiles((dir, name) -> name.endsWith(".yml"));
      sender.sendMessage(colors("&7Data files: &f" + (dataFiles == null ? "unavailable" : dataFiles.length)));
      sender.sendMessage(colors("&7Note: diagnostics do not verify runtime fight quality or external webhook delivery."));
   }

   private void kitAdmin(CommandSender var1, String[] var2) throws IOException {
      if (var2.length < 2) {
         var1.sendMessage("/riftpvp kit <list|save|import|icon> [id]");
      } else {
         String var3 = var2[1].toLowerCase(Locale.ROOT);
         if ("list".equals(var3)) {
            var1.sendMessage("Kits: " + String.join(", ", this.kits.configuredIds()));
         } else if (var2.length < 3) {
            var1.sendMessage("/riftpvp kit " + var3 + " <id>");
         } else {
            String var4 = var2[2].toLowerCase(Locale.ROOT);
            if (!KitManager.safeId(var4)) {
               var1.sendMessage(ChatColor.RED + "Invalid kit ID.");
            } else {
               switch (var3) {
                  case "save":
                     if (!(var1 instanceof Player var5)) {
                        this.message(var1, "players-only");
                        return;
                     }

                     if (this.kits.savePlayerKit(var5, var4)) {
                        this.message(var1, "kit-saved", "kit", var4);
                     }
                     break;
                  case "import":
                     if (this.kits.importKit(var4)) {
                        this.message(var1, "kit-imported", "kit", var4);
                     } else {
                        var1.sendMessage(
                           ChatColor.RED + "No KitsCratesPlus kit named " + var4 + " found in kit-imports/, KitsCratesPlus/kits/, or bundled defaults."
                        );
                     }
                     break;
                  case "icon":
                     if (!(var1 instanceof Player var6)) {
                        this.message(var1, "players-only");
                        return;
                     }

                     ItemStack var9 = var6.getInventory().getItemInMainHand();
                     if (var9.getType().isAir()) {
                        this.message(var1, "empty-hand");
                        return;
                     }

                     this.kits.setIcon(var4, var9);
                     this.message(var1, "icon-set", "kit", var4);
                     break;
                  default:
                     var1.sendMessage("/riftpvp kit <list|save|import|icon> [id]");
               }
            }
         }
      }
   }

   private void npcAdmin(CommandSender var1, String[] var2) throws IOException {
      if (var2.length < 2) {
         var1.sendMessage(colors("&dNPCs &7» &f/riftpvp npc create <queue|rtp|leaderboard|editor|heal|hub|ranked|spectate>"));
         var1.sendMessage(colors("&7/riftpvp npc list, remove <id>, move <id>, rename <id> <name>"));
         var1.sendMessage(colors("&7/riftpvp npc bind <hub|ranked|spectate> (look at existing NPC)"));
         var1.sendMessage(colors("&7/riftpvp npc skin <username>, skinmode <account|uploaded>"));
      } else {
         switch (var2[1].toLowerCase(Locale.ROOT)) {
            case "create":
               if (var1 instanceof Player var7) {
                  if (var2.length < 3) {
                     var1.sendMessage("Usage: /riftpvp npc create <queue|rtp|leaderboard|editor|heal|hub|ranked|spectate>");
                  } else {
                     String var9 = this.npcs.create(var2[2], var7);
                     var1.sendMessage(var9 == null ? colors("&cUnknown NPC type.") : colors("&aCreated &e" + var9 + "&a. Right-click it to use."));
                  }
               } else {
                  this.message(var1, "players-only");
               }
               break;
            case "list":
               Collection var5 = this.npcs.describe();
               var1.sendMessage(var5.isEmpty() ? colors("&7No NPCs created yet.") : colors("&dLobby NPCs &7» &f" + String.join(", ", var5)));
               break;
            case "remove":
            case "delete":
               if (var2.length < 3) {
                  var1.sendMessage("Usage: /riftpvp npc remove <id>");
               } else {
                  var1.sendMessage(this.npcs.remove(var2[2]) ? colors("&aNPC removed.") : colors("&cUnknown NPC ID."));
               }
               break;
            case "move":
               if (var1 instanceof Player var8) {
                  if (var2.length < 3) {
                     var1.sendMessage("Usage: /riftpvp npc move <id>");
                  } else {
                     var1.sendMessage(
                        this.npcs.move(var2[2], var8) ? colors("&aNPC moved to the block you're standing on, facing forward.") : colors("&cUnknown NPC ID.")
                     );
                  }
               } else {
                  this.message(var1, "players-only");
               }
               break;
            case "rename":
               if (var2.length < 4) {
                  var1.sendMessage("Usage: /riftpvp npc rename <id> <name with &colors>");
               } else {
                  var1.sendMessage(
                     this.npcs.rename(var2[2], String.join(" ", Arrays.copyOfRange(var2, 3, var2.length)))
                        ? colors("&aNPC name updated.")
                        : colors("&cUnknown NPC ID or invalid name.")
                  );
               }
               break;
            case "bind":
               if (var1 instanceof Player player) {
                  if (var2.length < 3) player.sendMessage("Usage: /riftpvp npc bind <hub|ranked|spectate|none>");
                  else player.sendMessage(this.npcs.bindLooking(player, var2[2])
                      ? colors("&aNPC interaction bound. Right-click it to test.")
                      : colors("&cCould not bind. Stand within 6 blocks, look directly at a mannequin in the configured lobby, and use a valid action."));
               } else this.message(var1, "players-only");
               break;
            case "skin":
               if (var2.length >= 3 && var2[2].matches("[A-Za-z0-9_]{3,16}")) {
                  this.getConfig().set("npcs.skin.username", var2[2]);
                  this.saveConfig();
                  this.npcs.spawnSaved();
                  var1.sendMessage(colors("&aNPCs now use the Java skin of &e" + var2[2] + "&a."));
               } else {
                  var1.sendMessage("Usage: /riftpvp npc skin <Java-account-name>");
               }
               break;
            case "skinmode":
               if (var2.length >= 3 && Set.of("account", "uploaded").contains(var2[2].toLowerCase(Locale.ROOT))) {
                  boolean var6 = var2[2].equalsIgnoreCase("uploaded");
                  this.getConfig().set("npcs.skin.use-resource-pack", var6);
                  this.saveConfig();
                  this.npcs.spawnSaved();
                  var1.sendMessage(
                     var6
                        ? colors("&aNPCs now use your uploaded PNG. Make sure its texture is merged into the forced resource pack.")
                        : colors("&aNPCs now use the current skin of the configured Minecraft account.")
                  );
               } else {
                  var1.sendMessage("Usage: /riftpvp npc skinmode <account|uploaded>");
               }
               break;
            default:
               var1.sendMessage("Usage: /riftpvp npc <create|list|remove|move|rename|bind|skin|skinmode>");
         }
      }
   }

   private void rankAdmin(CommandSender var1, String[] var2) throws IOException {
      if (var2.length >= 2 && !"list".equalsIgnoreCase(var2[1])) {
         String var3 = var2[1].toLowerCase(Locale.ROOT);
         if (var2.length < 3) {
            var1.sendMessage("Missing rank ID or player name.");
         } else {
            String var4 = var2[2].toLowerCase(Locale.ROOT);
            boolean var5 = var3.equals("create") || var3.equals("delete") || var3.equals("admin") || var3.equals("color") || var3.equals("prefix");
            if ((var5 || var3.equals("set") && var2.length >= 4 && "owner".equalsIgnoreCase(var2[3])) && !this.ranks.isOwner(var1)) {
               var1.sendMessage(ChatColor.RED + "Only the Owner or an OP may manage this rank setting.");
            } else {
               boolean var6;
               switch (var3) {
                  case "create":
                     var6 = this.ranks.create(var4);
                     break;
                  case "delete":
                     var6 = this.ranks.delete(var4);
                     break;
                  case "set":
                     if (var2.length < 4) {
                        var1.sendMessage("/riftpvp rank set <online-player> <rank>");
                        return;
                     }

                     Player var9 = Bukkit.getPlayerExact(var2[2]);
                     if (var9 == null) {
                        var1.sendMessage(ChatColor.RED + "Player must be online.");
                        return;
                     }

                     String var10 = var2[3].toLowerCase(Locale.ROOT);
                     if ("owner".equals(var10) && !this.ranks.isOwner(var1)) {
                        var1.sendMessage(ChatColor.RED + "Only the Owner or an OP can grant Owner.");
                        return;
                     }

                     var6 = this.ranks.assign(var9, var10);
                     break;
                  case "prefix":
                     if (var2.length < 4) {
                        var1.sendMessage("/riftpvp rank prefix <rank> <text>");
                        return;
                     }

                     var6 = this.ranks.setPrefix(var4, String.join(" ", Arrays.copyOfRange(var2, 3, var2.length)));
                     break;
                  case "color":
                     if (var2.length < 4) {
                        var1.sendMessage("/riftpvp rank color <rank> <#RRGGBB>");
                        return;
                     }

                     var6 = this.ranks.setColor(var4, var2[3]);
                     break;
                  case "admin":
                     if (var2.length < 4 || !var2[3].equalsIgnoreCase("true") && !var2[3].equalsIgnoreCase("false")) {
                        var1.sendMessage("/riftpvp rank admin <rank> <true|false>");
                        return;
                     }

                     var6 = this.ranks.setAdmin(var4, Boolean.parseBoolean(var2[3]));
                     break;
                  default:
                     var1.sendMessage("/riftpvp rank <list|create|set|prefix|color|admin|delete>");
                     return;
               }

               if (var6) this.staffAudit.log(var1, "rank-" + var3,
                  String.join(" ", Arrays.copyOfRange(var2, 2, var2.length)));
               var1.sendMessage(var6 ? ChatColor.GREEN + "Rank updated." : ChatColor.RED + "Invalid rank, input, or protected rank.");
            }
         }
      } else {
         var1.sendMessage("Ranks: " + this.ranks.list());
         var1.sendMessage("/riftpvp rank <create|set|prefix|color|admin|delete> ...");
      }
   }

   public List<String> onTabComplete(CommandSender var1, Command var2, String var3, String[] var4) {
      if (var4.length == 0) {
         return List.of();
      }

      String var6 = var2.getName().toLowerCase(Locale.ROOT);

      List<String> var5 = switch (var6) {
         case "tpa", "tpahere", "riftkick", "rtempban", "rtunban" -> var4.length == 1 ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList() : List.of();
         case "visibility" -> var4.length == 1 ? List.of("everyone", "party", "hidden") : List.of();
         case "queueprefs" -> var4.length == 1 ? List.of("ping") : var4.length == 2 ? List.of("any", "50", "100", "150", "200", "300") : List.of();
         case "customkit" -> var4.length == 1 ? List.of("save", "delete", "host", "queue", "leave", "list", "invite", "join") :
            (var4.length == 2 && Set.of("save", "delete", "host").contains(var4[0].toLowerCase(Locale.ROOT)) ? List.of("1", "2", "3", "4", "5", "6") : List.of());
         case "staff" -> var4.length == 1 ? List.of("help", "kick", "tempban", "unban") : List.of();
         case "tpaccept", "tpdeny" -> var4.length == 1 ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList() : List.of();
         case "ranked" -> var4.length == 1 ? append(append(this.kits.configuredIds(), "leave"), "top") : (var4.length == 2 && var4[0].equalsIgnoreCase("top") ? this.kits.configuredIds() : List.of());
         case "duel" -> var4.length == 1 ? append(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), "accept") : var4.length == 2 ? this.kits.configuredIds() : List.of();
         case "stats", "rank", "profile" -> var4.length == 1 ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList() : List.of();
         case "spectate" -> var4.length == 1 ? append(append(append(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), "list"), "next"), "prev") : List.of();
         case "party" -> var4.length == 1 ? List.of("create", "invite", "accept", "leave", "list") : (var4.length == 2 && var4[0].equalsIgnoreCase("invite") ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList() : List.of());
         case "kitmodes" -> var4.length == 1 ? this.kits.configuredIds() : var4.length == 2 ? List.of("ranked", "queue", "duel", "custom") : var4.length == 3 ? List.of("on", "off") : List.of();
         case "kit", "kiteditor", "kitsettings", "kitdelete" -> var4.length == 1 ? this.kits.configuredIds() : List.of();
         case "leaderboard" -> var4.length == 1 ? List.of("kills", "deaths", "playtime") : List.of();
         case "rtpq" -> var4.length == 1 ? append(this.kits.configuredIds(), "leave") : List.of();
         case "rtp" -> var4.length == 1 ? List.of("overworld", "nether", "end") : List.of();
         case "riftpvp" -> {
            if (!this.ranks.isAdmin(var1)) {
               yield List.of();
            } else if (var4.length == 1) {
               yield List.of("doctor", "audit", "integrity", "backup", "maintenance", "toggles", "cleanup", "reload", "setlobby", "kit", "rank", "npc");
            } else if (var4.length == 2) {
               yield switch (var4[0].toLowerCase(Locale.ROOT)) {
                  case "cleanup" -> List.of("status", "preview", "now");
                  case "backup" -> List.of("list", "now", "restore");
                  case "maintenance" -> List.of("on", "off", "status");
                  case "audit" -> List.of("5", "10", "20");
                  case "kit" -> List.of("list", "save", "import", "icon");
                  case "rank" -> List.of("list", "create", "set", "prefix", "color", "admin", "delete");
                  case "npc" -> List.of("create", "list", "remove", "move", "rename", "bind", "skin", "skinmode");
                  default -> List.of();
               };
            } else if (var4.length == 3 && var4[0].equalsIgnoreCase("npc")) {
               yield switch (var4[1].toLowerCase(Locale.ROOT)) {
                  case "create" -> List.of("queue", "rtp", "leaderboard", "editor", "heal", "hub", "ranked", "spectate");
                  case "bind" -> List.of("hub", "ranked", "spectate", "none");
                  case "skinmode" -> List.of("account", "uploaded");
                  case "remove", "delete", "move", "rename" -> this.npcs.ids();
                  default -> List.of();
               };
            } else if (var4.length == 3 && var4[0].equalsIgnoreCase("rank") && var4[1].equalsIgnoreCase("set")) {
               yield Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            } else if (var4.length == 4 && var4[0].equalsIgnoreCase("rank") && var4[1].equalsIgnoreCase("set")) {
               yield Arrays.asList(this.ranks.list().split(", "));
            } else if (var4.length == 3 && var4[0].equalsIgnoreCase("kit")) {
               yield this.kits.configuredIds();
            } else {
               yield List.of();
            }
         }
         default -> List.of();
      };
      String var11 = var4[var4.length - 1].toLowerCase(Locale.ROOT);
      return var5.stream().filter(var1x -> var1x.toLowerCase(Locale.ROOT).startsWith(var11)).toList();
   }

   private static List<String> append(List<String> var0, String var1) {
      ArrayList var2 = new ArrayList(var0);
      var2.add(var1);
      return var2;
   }
}
