package dev.riftpvp;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

final class StatsSidebar implements Listener {
   private final RiftPvpPlugin plugin;
   private final File file;
   private final Map<UUID, StatsSidebar.Record> stats = new HashMap<>();
   private final Map<UUID, String> names = new HashMap<>();
   private final Map<UUID, Scoreboard> boards = new HashMap<>();
   private final Map<UUID, Long> sessionStarted = new HashMap<>();
   private final Map<UUID, Map<Integer, String>> shownLines = new HashMap<>();

   StatsSidebar(RiftPvpPlugin var1) {
      this.plugin = var1;
      this.file = new File(var1.getDataFolder(), "stats.yml");
      this.load();
   }

   void load() {
      this.stats.clear();
      this.names.clear();
      YamlConfiguration var1 = YamlConfiguration.loadConfiguration(this.file);
      ConfigurationSection var2 = var1.getConfigurationSection("players");
      if (var2 != null) {
         for (String var4 : var2.getKeys(false)) {
            try {
               UUID var5 = UUID.fromString(var4);
               this.stats
                  .put(
                     var5,
                     new StatsSidebar.Record(
                        Math.max(0, var1.getInt("players." + var4 + ".kills")),
                        Math.max(0, var1.getInt("players." + var4 + ".deaths")),
                        Math.max(0L, var1.getLong("players." + var4 + ".playtime-ms"))
                     )
                  );
               String var6 = var1.getString("players." + var4 + ".name");
               if (var6 != null && !var6.isBlank()) {
                  this.names.put(var5, var6);
               }
            } catch (IllegalArgumentException var7) {
            }
         }
      }
   }

   void save() {
      YamlConfiguration var1 = new YamlConfiguration();

      for (Entry var3 : this.stats.entrySet()) {
         String var4 = "players." + var3.getKey();
         var1.set(var4 + ".kills", ((StatsSidebar.Record)var3.getValue()).kills);
         var1.set(var4 + ".deaths", ((StatsSidebar.Record)var3.getValue()).deaths);
         var1.set(var4 + ".playtime-ms", this.playtimeMillis((UUID)var3.getKey()));
         String var5 = this.names.get(var3.getKey());
         if (var5 != null) {
            var1.set(var4 + ".name", var5);
         }
      }

      File var8 = new File(this.file.getParentFile(), "stats.yml.new");

      try {
         Files.writeString(var8.toPath(), var1.saveToString(), StandardCharsets.UTF_8);

         try {
            Files.move(var8.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException var6) {
            Files.move(var8.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (IOException var7) {
         this.plugin.getLogger().warning("Could not save stats: " + var7.getMessage());
      }
   }

   int kills(UUID var1) {
      return this.stats.getOrDefault(var1, new StatsSidebar.Record(0, 0, 0L)).kills;
   }

   int deaths(UUID var1) {
      return this.stats.getOrDefault(var1, new StatsSidebar.Record(0, 0, 0L)).deaths;
   }

   long playtimeMillis(UUID var1) {
      long var2 = this.stats.getOrDefault(var1, new StatsSidebar.Record(0, 0, 0L)).playtimeMillis;
      Long var4 = this.sessionStarted.get(var1);
      return var2 + (var4 == null ? 0L : Math.max(0L, System.currentTimeMillis() - var4));
   }

   static String formatPlaytime(long var0) {
      long var2 = Math.max(0L, var0) / 60000L;
      long var4 = var2 / 1440L;
      long var6 = var2 / 60L % 24L;
      long var8 = var2 % 60L;
      if (var4 > 0L) {
         return var4 + "d " + var6 + "h " + var8 + "m";
      } else {
         return var2 >= 60L ? var2 / 60L + "h " + var8 + "m" : var2 + "m";
      }
   }

   List<StatsSidebar.LeaderboardEntry> topKills(int var1) {
      return this.stats
         .entrySet()
         .stream()
         .sorted(
            Comparator.<Entry<UUID, StatsSidebar.Record>>comparingInt(var0 -> var0.getValue().kills)
               .reversed()
               .thenComparingInt(var0 -> var0.getValue().deaths)
               .thenComparing(var1x -> this.displayName(var1x.getKey()), String.CASE_INSENSITIVE_ORDER)
         )
         .limit(Math.max(0, Math.min(45, var1)))
         .map(
            var1x -> new StatsSidebar.LeaderboardEntry(
               this.displayName(var1x.getKey()), var1x.getValue().kills, var1x.getValue().deaths, this.playtimeMillis(var1x.getKey())
            )
         )
         .toList();
   }

   List<StatsSidebar.LeaderboardEntry> topDeaths(int var1) {
      return this.stats
         .entrySet()
         .stream()
         .sorted(
            Comparator.<Entry<UUID, StatsSidebar.Record>>comparingInt(var0 -> var0.getValue().deaths)
               .reversed()
               .thenComparing(var0 -> -var0.getValue().kills)
               .thenComparing(var1x -> this.displayName(var1x.getKey()), String.CASE_INSENSITIVE_ORDER)
         )
         .limit(Math.max(0, Math.min(45, var1)))
         .map(
            var1x -> new StatsSidebar.LeaderboardEntry(
               this.displayName(var1x.getKey()), var1x.getValue().kills, var1x.getValue().deaths, this.playtimeMillis(var1x.getKey())
            )
         )
         .toList();
   }

   List<StatsSidebar.LeaderboardEntry> topPlaytime(int var1) {
      return this.stats
         .entrySet()
         .stream()
         .sorted(
            Comparator.<Entry<UUID, StatsSidebar.Record>>comparingLong(var1x -> this.playtimeMillis(var1x.getKey()))
               .reversed()
               .thenComparing(var1x -> this.displayName(var1x.getKey()), String.CASE_INSENSITIVE_ORDER)
         )
         .limit(Math.max(0, Math.min(45, var1)))
         .map(
            var1x -> new StatsSidebar.LeaderboardEntry(
               this.displayName(var1x.getKey()), var1x.getValue().kills, var1x.getValue().deaths, this.playtimeMillis(var1x.getKey())
            )
         )
         .toList();
   }

   private String displayName(UUID var1) {
      String var2 = this.names.get(var1);
      if (var2 != null && !var2.isBlank()) {
         return var2;
      }

      String var3 = Bukkit.getOfflinePlayer(var1).getName();
      return var3 != null ? var3 : var1.toString().substring(0, 8);
   }

   void recordLogoutDeath(UUID var1) {
      StatsSidebar.Record var2 = this.stats.getOrDefault(var1, new StatsSidebar.Record(0, 0, 0L));
      this.stats.put(var1, new StatsSidebar.Record(var2.kills, var2.deaths + 1, var2.playtimeMillis));
      this.save();
   }

   void refreshAll() {
      this.boards.clear();
      this.shownLines.clear();
      if (this.plugin.getConfig().getBoolean("scoreboard.enabled", true)) {
         Bukkit.getOnlinePlayers().forEach(this::refresh);
      }
   }

   void refresh(Player var1) {
      if (this.plugin.getConfig().getBoolean("scoreboard.enabled", true)) {
         boolean var2 = !this.boards.containsKey(var1.getUniqueId());
         Scoreboard var3 = this.boards.computeIfAbsent(var1.getUniqueId(), var0 -> Bukkit.getScoreboardManager().getNewScoreboard());
         Objective var4 = var3.getObjective("riftstats");
         Object var5 = TextColor.fromHexString(this.plugin.getConfig().getString("scoreboard.title-color", "#AA55FF"));
         if (var5 == null) {
            var5 = NamedTextColor.LIGHT_PURPLE;
         }

         TextComponent var6 = Component.text(this.plugin.getConfig().getString("scoreboard.title", "Rift PvP"), (TextColor)var5);
         if (var4 == null) {
            var4 = var3.registerNewObjective("riftstats", Criteria.DUMMY, var6);
            var4.setDisplaySlot(DisplaySlot.SIDEBAR);
            var4.numberFormat(NumberFormat.blank());
         } else if (!var6.equals(var4.displayName())) {
            var4.displayName(var6);
         }

         HashMap<Integer, String> var7 = new HashMap<>();
         var7.put(4, ChatColor.GRAY + this.plugin.getConfig().getString("scoreboard.username-label", "Player: ") + ChatColor.WHITE + var1.getName());
         var7.put(
            3,
            ChatColor.GRAY + this.plugin.getConfig().getString("scoreboard.kills-label", "Kills: ") + ChatColor.LIGHT_PURPLE + this.kills(var1.getUniqueId())
         );
         var7.put(
            2, ChatColor.GRAY + this.plugin.getConfig().getString("scoreboard.deaths-label", "Deaths: ") + ChatColor.RED + this.deaths(var1.getUniqueId())
         );
         if (this.plugin.getConfig().getBoolean("scoreboard.show-ping", true)) {
            var7.put(
               1, ChatColor.GRAY + this.plugin.getConfig().getString("scoreboard.ping-label", "Ping: ") + pingColor(var1.getPing()) + var1.getPing() + "ms"
            );
         }

         Map<Integer, String> var8 = this.shownLines.computeIfAbsent(var1.getUniqueId(), var0 -> new HashMap<>());

         for (Entry<Integer, String> var10 : new HashMap<>(var8).entrySet()) {
            if (!((String)var10.getValue()).equals(var7.get(var10.getKey()))) {
               var3.resetScores((String)var10.getValue());
            }
         }

         for (Entry<Integer, String> var12 : var7.entrySet()) {
            if (!((String)var12.getValue()).equals(var8.get(var12.getKey()))) {
               var4.getScore((String)var12.getValue()).setScore((Integer)var12.getKey());
            }
         }

         var8.clear();
         var8.putAll(var7);
         if (var2 && this.plugin.ranks() != null) {
            this.plugin.ranks().decorateScoreboard(var3);
         }

         if (var1.getScoreboard() != var3) {
            var1.setScoreboard(var3);
         }
      }
   }

   static ChatColor pingColor(int var0) {
      return var0 < 80 ? ChatColor.GREEN : (var0 < 150 ? ChatColor.YELLOW : ChatColor.RED);
   }

   void refreshNameTags() {
      for (Scoreboard var2 : this.boards.values()) {
         this.plugin.ranks().decorateScoreboard(var2);
      }
   }

   void tickHud() {
      for (Player var2 : Bukkit.getOnlinePlayers()) {
         if (this.plugin.getConfig().getBoolean("scoreboard.enabled", true)) {
            this.refresh(var2);
         }

         if (this.plugin.getConfig().getBoolean("tablist.enabled", true)) {
            this.refreshTablist(var2);
         }
      }
   }

   private void refreshTablist(Player var1) {
      Object var2 = TextColor.fromHexString(this.plugin.getConfig().getString("server.title-color", "#AA55FF"));
      if (var2 == null) {
         var2 = NamedTextColor.LIGHT_PURPLE;
      }

      String var3 = this.plugin.getConfig().getString("server.name", "Rift PvP");
      Component var4 = Component.text(var3, (TextColor)var2);
      String var5 = this.plugin.getConfig().getString("tablist.subtitle", "&7Practice • Kit PvP");
      if (var5 != null && !var5.isBlank()) {
         var4 = var4.append(Component.text("\n")).append(legacy(var5));
      }

      double var6 = Bukkit.getTPS().length > 0 ? Bukkit.getTPS()[0] : 20.0;
      String var8 = this.plugin.getConfig().getString("tablist.footer", "&7Ping: &a%ping%ms &8| &7TPS: &a%tps% &8| &7Online: &f%online%");
      var8 = var8.replace("%ping%", Integer.toString(var1.getPing()))
         .replace("%tps%", String.format(Locale.ROOT, "%.1f", Math.min(20.0, var6)))
         .replace("%online%", Integer.toString(Bukkit.getOnlinePlayers().size()))
         .replace("%server%", var3);
      var1.sendPlayerListHeaderAndFooter((Component)var4, legacy(var8));
   }

   private static Component legacy(String var0) {
      String var1 = RiftPvpPlugin.colors(var0);
      Component var2 = Component.empty();
      StringBuilder var3 = new StringBuilder();
      NamedTextColor var4 = NamedTextColor.WHITE;
      boolean var5 = false;

      for (int var6 = 0; var6 < var1.length(); var6++) {
         char var7 = var1.charAt(var6);
         if (var7 == 167 && var6 + 1 < var1.length()) {
            if (!var3.isEmpty()) {
               var2 = var2.append(Component.text(var3.toString(), var4).decoration(TextDecoration.BOLD, var5));
               var3.setLength(0);
            }

            char var8 = Character.toLowerCase(var1.charAt(++var6));
            if (var8 == 'l') {
               var5 = true;
            } else if (var8 == 'r') {
               var4 = NamedTextColor.WHITE;
               var5 = false;
            } else {
               var4 = switch (var8) {
                  case '0' -> NamedTextColor.BLACK;
                  case '1' -> NamedTextColor.DARK_BLUE;
                  case '2' -> NamedTextColor.DARK_GREEN;
                  case '3' -> NamedTextColor.DARK_AQUA;
                  case '4' -> NamedTextColor.DARK_RED;
                  case '5' -> NamedTextColor.DARK_PURPLE;
                  case '6' -> NamedTextColor.GOLD;
                  case '7' -> NamedTextColor.GRAY;
                  case '8' -> NamedTextColor.DARK_GRAY;
                  case '9' -> NamedTextColor.BLUE;
                  default -> var4;
                  case 'a' -> NamedTextColor.GREEN;
                  case 'b' -> NamedTextColor.AQUA;
                  case 'c' -> NamedTextColor.RED;
                  case 'd' -> NamedTextColor.LIGHT_PURPLE;
                  case 'e' -> NamedTextColor.YELLOW;
                  case 'f' -> NamedTextColor.WHITE;
               };
               if ("0123456789abcdef".indexOf(var8) >= 0) {
                  var5 = false;
               }
            }
         } else {
            var3.append(var7);
         }
      }

      if (!var3.isEmpty()) {
         var2 = var2.append(Component.text(var3.toString(), var4).decoration(TextDecoration.BOLD, var5));
      }

      return (Component)var2;
   }

   void finishSessions() {
      for (UUID var2 : new HashSet<>(this.sessionStarted.keySet())) {
         this.endSession(var2);
      }

      this.save();
   }

   private void endSession(UUID var1) {
      Long var2 = this.sessionStarted.remove(var1);
      if (var2 != null) {
         StatsSidebar.Record var3 = this.stats.getOrDefault(var1, new StatsSidebar.Record(0, 0, 0L));
         this.stats.put(var1, new StatsSidebar.Record(var3.kills, var3.deaths, var3.playtimeMillis + Math.max(0L, System.currentTimeMillis() - var2)));
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent var1) {
      Player var2 = var1.getPlayer();
      this.names.put(var2.getUniqueId(), var2.getName());
      this.stats.putIfAbsent(var2.getUniqueId(), new StatsSidebar.Record(0, 0, 0L));
      this.sessionStarted.put(var2.getUniqueId(), System.currentTimeMillis());
      this.save();
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         if (var1.getPlayer().isOnline()) {
            this.refresh(var1.getPlayer());
         }
      }, 2L);
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent var1) {
      this.boards.remove(var1.getPlayer().getUniqueId());
      this.shownLines.remove(var1.getPlayer().getUniqueId());
      this.endSession(var1.getPlayer().getUniqueId());
      this.save();
      Bukkit.getScheduler().runTask(this.plugin, this::refreshNameTags);
   }

   @EventHandler
   public void onDeath(PlayerDeathEvent var1) {
      Player var2 = var1.getEntity();
      this.names.put(var2.getUniqueId(), var2.getName());
      StatsSidebar.Record var3 = this.stats.getOrDefault(var2.getUniqueId(), new StatsSidebar.Record(0, 0, 0L));
      this.stats.put(var2.getUniqueId(), new StatsSidebar.Record(var3.kills, var3.deaths + 1, var3.playtimeMillis));
      Player var4 = var2.getKiller();
      if (var4 != null && !var4.equals(var2)) {
         this.names.put(var4.getUniqueId(), var4.getName());
         StatsSidebar.Record var5 = this.stats.getOrDefault(var4.getUniqueId(), new StatsSidebar.Record(0, 0, 0L));
         this.stats.put(var4.getUniqueId(), new StatsSidebar.Record(var5.kills + 1, var5.deaths, var5.playtimeMillis));
         this.refresh(var4);
      }

      this.refresh(var2);
      this.save();
   }

   record LeaderboardEntry(String name, int kills, int deaths, long playtimeMillis) {
   }

   private record Record(int kills, int deaths, long playtimeMillis) {
   }
}
