package dev.riftpvp;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.scoreboard.Team.Option;
import org.bukkit.scoreboard.Team.OptionStatus;

final class RankManager implements Listener {
   private static final String FOUNDER_USERNAME = "bgps";
   private final RiftPvpPlugin plugin;
   private final File file;
   private final Map<UUID, String> assignments = new ConcurrentHashMap<>();
   private final Map<String, RankManager.Rank> ranks = new ConcurrentHashMap<>();
   private UUID founderUuid;

   RankManager(RiftPvpPlugin var1) {
      this.plugin = var1;
      this.file = new File(var1.getDataFolder(), "ranks.yml");
      this.reload();
   }

   void reload() {
      YamlConfiguration var1 = this.file.isFile() ? YamlConfiguration.loadConfiguration(this.file) : new YamlConfiguration();
      if (!var1.contains("ranks.member")) {
         var1.set("ranks.member.name", "Member");
         var1.set("ranks.member.prefix", "[Member] ");
         var1.set("ranks.member.color", "#AAAAAA");
         var1.set("ranks.member.plugin-admin", false);
      }

      if (!var1.contains("ranks.moderator")) {
         var1.set("ranks.moderator.name", "Moderator");
         var1.set("ranks.moderator.prefix", "[Mod] ");
         var1.set("ranks.moderator.color", "#55AAFF");
         var1.set("ranks.moderator.plugin-admin", false);
      }

      if (!var1.contains("ranks.admin")) {
         var1.set("ranks.admin.name", "Admin");
         var1.set("ranks.admin.prefix", "[Admin] ");
         var1.set("ranks.admin.color", "#FF5555");
         var1.set("ranks.admin.plugin-admin", true);
      }

      if (!var1.contains("ranks.vip")) {
         var1.set("ranks.vip.name", "VIP");
         var1.set("ranks.vip.prefix", "[VIP] ");
         var1.set("ranks.vip.color", "#55DDFF");
         var1.set("ranks.vip.plugin-admin", false);
      }

      if (!var1.contains("ranks.owner")) {
         var1.set("ranks.owner.name", "Owner");
         var1.set("ranks.owner.prefix", "[Owner] ");
         var1.set("ranks.owner.color", "#AA55FF");
         var1.set("ranks.owner.plugin-admin", true);
      }

      if (!var1.contains("ranks.founder")) {
         var1.set("ranks.founder.name", "Founder");
         var1.set("ranks.founder.prefix", "[Founder] ");
         var1.set("ranks.founder.color", "#BB66FF");
         var1.set("ranks.founder.plugin-admin", true);
      var1.set("ranks.moderator.plugin-admin", false);
      }

      var1.set("ranks.founder.plugin-admin", true);
      var1.set("ranks.moderator.plugin-admin", false);

      try {
         this.save(var1);
      } catch (IOException var10) {
         this.plugin.getLogger().warning(var10.getMessage());
      }

      this.ranks.clear();
      ConfigurationSection var2 = var1.getConfigurationSection("ranks");
      if (var2 != null) {
         for (String var4 : var2.getKeys(false)) {
            String var5 = "ranks." + var4 + ".";
            this.ranks
               .put(
                  var4.toLowerCase(Locale.ROOT),
                  new RankManager.Rank(
                     var1.getString(var5 + "name", var4),
                     var1.getString(var5 + "prefix", "[" + var4 + "] "),
                     var1.getString(var5 + "color", "#AAAAAA"),
                     var1.getBoolean(var5 + "plugin-admin", false)
                  )
               );
         }
      }

      this.assignments.clear();
      this.founderUuid = null;
      String var11 = var1.getString("founder.uuid", "");
      if (!var11.isBlank()) {
         try {
            this.founderUuid = UUID.fromString(var11);
         } catch (IllegalArgumentException var9) {
            this.plugin.getLogger().warning("Invalid founder UUID in ranks.yml; awaiting bgps login.");
         }
      }

      ConfigurationSection var12 = var1.getConfigurationSection("players");
      if (var12 != null) {
         for (String var6 : var12.getKeys(false)) {
            try {
               this.assignments.put(UUID.fromString(var6), var12.getString(var6, "member"));
            } catch (IllegalArgumentException var8) {
            }
         }
      }

      Bukkit.getOnlinePlayers().forEach(var1x -> {
         this.ensureFounder(var1x);
         this.updateDisplay(var1x);
      });
      if (this.plugin.sidebar() != null) {
         this.plugin.sidebar().refreshNameTags();
      }
      if (this.plugin.lobbyHotbar() != null) Bukkit.getOnlinePlayers().forEach(this.plugin.lobbyHotbar()::refresh);
   }

   boolean isAdmin(CommandSender var1) {
      // Rank names, inherited nodes and configured plugin-admin flags are cosmetic.
      // Administrative actions must require an actual operator or the console.
      return !(var1 instanceof Player player) || player.isOp();
   }

   boolean isOwner(CommandSender var1) {
      return !(var1 instanceof Player player) || player.isOp();
   }

   /** Gives Founder and the explicit Admin rank kit-management only, not OP powers. */
   boolean canManageKits(Player player) {
      return player.isOp() || isFounder(player)
         || "admin".equalsIgnoreCase(rankId(player.getUniqueId()));
   }

   boolean isFounder(Player player) {
      return "founder".equals(this.rankId(player.getUniqueId()));
   }

   /** Founder bypasses all RiftPvP feature permission nodes without requiring vanilla OP. */
   boolean hasPluginPermission(Player player, String permission) {
      return this.isFounder(player) || player.hasPermission(permission);
   }

   /** VIP lobby flight is separate from admin OP and trim-edit permissions. */
   boolean hasVipLobbyFlight(Player player) {
      return Set.of("vip", "founder", "owner").contains(rankId(player.getUniqueId()))
         || player.hasPermission("riftpvp.vipflight");
   }

   boolean canEditTrims(Player player) {
      return player.isOp() || player.hasPermission("riftpvp.viptrims")
         || Set.of("vip", "founder", "owner").contains(this.rankId(player.getUniqueId()));
   }

   String rankId(UUID var1) {
      Player var2 = Bukkit.getPlayer(var1);
      return var2 != null && var2.getName().equalsIgnoreCase("bgps") && var1.equals(this.founderUuid)
         ? "founder"
         : this.assignments.getOrDefault(var1, "member");
   }

   private void ensureFounder(Player var1) {
      if (var1.getName().equalsIgnoreCase("bgps")) {
         UUID var2 = var1.getUniqueId();
         if (this.founderUuid != null && !var2.equals(this.founderUuid)) {
            this.plugin.getLogger().warning("Rejected mismatched UUID for protected founder username bgps.");
         } else if (!var2.equals(this.founderUuid) || !"founder".equals(this.assignments.get(var2))) {
            try {
               YamlConfiguration var3 = this.load();
               var3.set("founder.uuid", var2.toString());
               var3.set("players." + var2, "founder");
               this.save(var3);
               this.founderUuid = var2;
               this.assignments.put(var2, "founder");
            } catch (IOException var4) {
               this.plugin.getLogger().warning("Could not save Founder rank for bgps: " + var4.getMessage());
            }
         }
      }
   }

   String list() {
      return String.join(", ", this.ranks.keySet().stream().sorted().toList());
   }

   boolean exists(String var1) {
      return this.ranks.containsKey(var1);
   }

   boolean create(String var1) throws IOException {
      if (KitManager.safeId(var1) && !this.exists(var1)) {
         YamlConfiguration var2 = this.load();
         var2.set("ranks." + var1 + ".name", var1);
         var2.set("ranks." + var1 + ".prefix", "[" + var1 + "] ");
         var2.set("ranks." + var1 + ".color", "#AAAAAA");
         var2.set("ranks." + var1 + ".plugin-admin", false);
         this.save(var2);
         this.reload();
         return true;
      } else {
         return false;
      }
   }

   boolean assign(Player var1, String var2) throws IOException {
      if (!this.exists(var2)) {
         return false;
      }

      boolean var3 = var1.getName().equalsIgnoreCase("bgps");
      if (var3 != "founder".equals(var2)) {
         return false;
      }

      if (var3 && this.founderUuid != null && !var1.getUniqueId().equals(this.founderUuid)) {
         return false;
      }

      YamlConfiguration var4 = this.load();
      var4.set("players." + var1.getUniqueId(), var2);
      this.save(var4);
      this.assignments.put(var1.getUniqueId(), var2);
      this.updateDisplay(var1);
      if (this.plugin.sidebar() != null) {
         this.plugin.sidebar().refreshNameTags();
      }
      if (this.plugin.lobbyHotbar() != null) this.plugin.lobbyHotbar().refresh(var1);

      return true;
   }

   boolean setPrefix(String var1, String var2) throws IOException {
      if (this.exists(var1) && var2.length() <= 48) {
         YamlConfiguration var3 = this.load();
         var3.set("ranks." + var1 + ".prefix", var2);
         this.save(var3);
         this.reload();
         return true;
      } else {
         return false;
      }
   }

   boolean setColor(String var1, String var2) throws IOException {
      if (this.exists(var1) && var2.matches("#[0-9a-fA-F]{6}")) {
         YamlConfiguration var3 = this.load();
         var3.set("ranks." + var1 + ".color", var2);
         this.save(var3);
         this.reload();
         return true;
      } else {
         return false;
      }
   }

   boolean setAdmin(String var1, boolean var2) throws IOException {
      if (this.exists(var1) && !Set.of("owner", "founder", "moderator").contains(var1)) {
         YamlConfiguration var3 = this.load();
         var3.set("ranks." + var1 + ".plugin-admin", var2);
         this.save(var3);
         this.reload();
         return true;
      } else {
         return false;
      }
   }

   boolean delete(String var1) throws IOException {
      if (this.exists(var1) && !Set.of("member", "moderator", "admin", "owner", "founder").contains(var1)) {
         YamlConfiguration var2 = this.load();
         var2.set("ranks." + var1, null);
         ConfigurationSection var3 = var2.getConfigurationSection("players");
         if (var3 != null) {
            for (String var5 : var3.getKeys(false)) {
               if (var1.equals(var2.getString("players." + var5))) {
                  var2.set("players." + var5, "member");
               }
            }
         }

         this.save(var2);
         this.reload();
         return true;
      } else {
         return false;
      }
   }

   void updateDisplay(Player var1) {
      RankManager.Rank var2 = this.ranks.getOrDefault(this.rankId(var1.getUniqueId()), this.ranks.get("member"));
      if (var2 != null) {
         Object var3 = TextColor.fromHexString(var2.color);
         if (var3 == null) {
            var3 = NamedTextColor.GRAY;
         }

         var1.playerListName(Component.text(var2.prefix, (TextColor)var3).append(Component.text(var1.getName(), NamedTextColor.WHITE)));
      }
   }

   void decorateScoreboard(Scoreboard var1) {
      if (!this.plugin.getConfig().getBoolean("nametags.enabled", true)) {
         for (Team var12 : new HashSet<>(var1.getTeams())) {
            if (var12.getName().startsWith("rp_")) {
               var12.unregister();
            }
         }
      } else {
         HashSet var2 = new HashSet();

         for (Player var4 : Bukkit.getOnlinePlayers()) {
            var2.add(var4.getName());
            String var5 = "rp_" + var4.getUniqueId().toString().replace("-", "").substring(0, 12);
            Team var6 = var1.getTeam(var5);
            if (var6 == null) {
               var6 = var1.registerNewTeam(var5);
            }

            RankManager.Rank var7 = this.ranks.getOrDefault(this.rankId(var4.getUniqueId()), this.ranks.get("member"));
            if (var7 != null) {
               Object var8 = TextColor.fromHexString(var7.color);
               if (var8 == null) {
                  var8 = NamedTextColor.GRAY;
               }

               var6.prefix(Component.text(var7.prefix, (TextColor)var8));
               var6.color(NamedTextColor.nearestTo((TextColor)var8));
               var6.setOption(Option.NAME_TAG_VISIBILITY, OptionStatus.ALWAYS);
               var6.setAllowFriendlyFire(true);
               var6.setCanSeeFriendlyInvisibles(false);
               if (!var6.hasEntry(var4.getName())) {
                  Team var9 = var1.getEntryTeam(var4.getName());
                  if (var9 != null) {
                     var9.removeEntry(var4.getName());
                  }

                  var6.addEntry(var4.getName());
               }
            }
         }

         for (Team var13 : new HashSet<>(var1.getTeams())) {
            if (var13.getName().startsWith("rp_") && var13.getEntries().stream().noneMatch(var2::contains)) {
               var13.unregister();
            }
         }
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent var1) {
      this.ensureFounder(var1.getPlayer());
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         if (var1.getPlayer().isOnline()) {
            this.updateDisplay(var1.getPlayer());
         }

         if (this.plugin.sidebar() != null) {
            this.plugin.sidebar().refreshNameTags();
         }
      }, 2L);
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent var1) {
      Bukkit.getScheduler().runTask(this.plugin, () -> {
         if (this.plugin.sidebar() != null) {
            this.plugin.sidebar().refreshNameTags();
         }
      });
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onChat(AsyncChatEvent var1) {
      RankManager.Rank var2 = this.ranks.getOrDefault(this.rankId(var1.getPlayer().getUniqueId()), this.ranks.get("member"));
      if (var2 != null) {
         TextColor var3 = TextColor.fromHexString(var2.color);
         TextColor var4 = var3 == null ? NamedTextColor.GRAY : var3;
         var1.renderer(
            (var2x, var3x, var4x, var5) -> ((TextComponent)((TextComponent)Component.text(var2.prefix, var4).append(var3x))
                  .append(Component.text(": ", NamedTextColor.GRAY)))
               .append(var4x)
         );
      }
   }

   private YamlConfiguration load() {
      return YamlConfiguration.loadConfiguration(this.file);
   }

   private void save(YamlConfiguration var1) throws IOException {
      File var2 = new File(this.file.getParentFile(), "ranks.yml.new");
      Files.writeString(var2.toPath(), var1.saveToString(), StandardCharsets.UTF_8);

      try {
         Files.move(var2.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException var4) {
         Files.move(var2.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }
   }

   private record Rank(String name, String prefix, String color, boolean pluginAdmin) {
   }
}
