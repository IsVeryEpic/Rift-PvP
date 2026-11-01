package dev.riftpvp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Simple persistent evidence trail: only logs facts the plugin actually observes. */
final class MatchLedger {
   private final RiftPvpPlugin plugin;
   private final File folder;
   private final File indexFile;
   private final File reportsFile;
   private final File sequenceFile;
   private final Map<UUID, String> live = new HashMap<>();
   private final Map<UUID, Long> reportCooldown = new HashMap<>();
   private YamlConfiguration index;
   private YamlConfiguration reports;
   private long sequence;

   MatchLedger(RiftPvpPlugin plugin) {
      this.plugin = plugin;
      folder = new File(plugin.getDataFolder(), "match-records");
      if (!folder.isDirectory() && !folder.mkdirs()) plugin.getLogger().warning("Cannot create match records folder");
      indexFile = new File(plugin.getDataFolder(), "match-index.yml");
      reportsFile = new File(plugin.getDataFolder(), "match-reports.yml");
      sequenceFile = new File(plugin.getDataFolder(), "match-sequence.txt");
      index = YamlConfiguration.loadConfiguration(indexFile);
      reports = YamlConfiguration.loadConfiguration(reportsFile);
      try { sequence = sequenceFile.isFile() ? Long.parseLong(Files.readString(sequenceFile.toPath()).trim()) : 0; }
      catch (Exception error) { plugin.getLogger().warning("Match sequence needs inspection: " + error.getMessage()); sequence = System.currentTimeMillis(); }
   }

   String started(Player first, Player second, String kit, boolean ranked) {
      String id = "R-" + String.format(java.util.Locale.ROOT, "%08d", ++sequence);
      try { atomic(sequenceFile, Long.toString(sequence)); }
      catch (IOException error) { plugin.getLogger().severe("Could not persist match ID sequence: " + error.getMessage()); return null; }
      YamlConfiguration data = new YamlConfiguration();
      data.set("id", id);
      data.set("timestamp", Instant.now().toString());
      data.set("players.a.uuid", first.getUniqueId().toString());
      data.set("players.a.name", first.getName());
      data.set("players.b.uuid", second.getUniqueId().toString());
      data.set("players.b.name", second.getName());
      data.set("kit", kit);
      data.set("mode", ranked ? "ranked" : "unranked");
      data.set("world", first.getWorld().getName());
      data.set("x", first.getLocation().getBlockX());
      data.set("y", first.getLocation().getBlockY());
      data.set("z", first.getLocation().getBlockZ());
      data.set("status", "active");
      data.set("events", List.of(Instant.now() + ": Both players teleported; match began"));
      try {
         saveRecord(id, data);
         indexHistory(first.getUniqueId(), id);
         indexHistory(second.getUniqueId(), id);
         persistIndex();
         live.put(first.getUniqueId(), id);
         live.put(second.getUniqueId(), id);
         cleanupOldRecords();
      } catch (IOException error) {
         plugin.getLogger().severe("Match logging failed for " + id + ": " + error.getMessage());
      }
      first.sendMessage("§7Match ID: §d" + id + " §7(use this ID when reporting problems)");
      second.sendMessage("§7Match ID: §d" + id + " §7(use this ID when reporting problems)");
      return id;
   }

   private void indexHistory(UUID player, String id) {
      String path = "players." + player;
      List<String> history = new ArrayList<>(index.getStringList(path));
      history.add(0, id);
      if (history.size() > 10) history.subList(10, history.size()).clear();
      index.set(path, history);
   }

   private void persistIndex() throws IOException { atomic(indexFile, index.saveToString()); }

   void finished(String id, UUID winner, UUID loser, boolean abandoned, long durationSeconds) {
      if (id == null) return;
      YamlConfiguration record = readRecord(id);
      if (record == null) return;
      record.set("status", "finished");
      record.set("winner.uuid", winner.toString());
      record.set("loser.uuid", loser.toString());
      record.set("duration-seconds", durationSeconds);
      record.set("end-reason", abandoned ? "player-left" : "player-death");
      addEvent(record, "Match ended: " + (abandoned ? "player left" : "death"));
      try { saveRecord(id, record); }
      catch (IOException error) { plugin.getLogger().severe("Match result could not be saved for " + id); }
      live.remove(winner, id);
      live.remove(loser, id);
   }

   void flagIntegrity(String id, String reason) {
      if (id == null) return;
      YamlConfiguration record = readRecord(id);
      if (record == null) return;
      addEvent(record, "Ranked integrity review: " + reason);
      try { saveRecord(id, record); } catch (IOException error) {
         plugin.getLogger().warning("Could not record integrity review for " + id);
      }
   }

   void recordStatistics(String id, UUID fighter, int hits, double damage, double healing,
                         int criticalCandidates, int longestCombo, int streak, int bestStreak) {
      if (id == null) return;
      YamlConfiguration record = readRecord(id);
      if (record == null) return;
      String prefix = "statistics." + fighter + ".";
      record.set(prefix + "hits", hits);
      record.set(prefix + "damage", damage);
      record.set(prefix + "healing", healing);
      record.set(prefix + "critical-candidates", criticalCandidates);
      record.set(prefix + "longest-combo", longestCombo);
      record.set(prefix + "win-streak", streak);
      record.set(prefix + "best-win-streak", bestStreak);
      try { saveRecord(id, record); }
      catch (IOException error) { plugin.getLogger().warning("Could not save match statistics for " + id); }
   }

   void kitEvent(Player player, String kit, boolean accepted) {
      String id = live.get(player.getUniqueId());
      if (id != null) {
         YamlConfiguration data = readRecord(id);
         if (data != null) {
            addEvent(data, player.getName() + (accepted ? " changed kit to " : " attempted blocked kit change to ") + kit);
            try { saveRecord(id, data); }
            catch (IOException error) { plugin.getLogger().warning("Could not write match kit event for " + id); }
         }
      }
      if (!accepted || !plugin.getConfig().getBoolean("features.rekit-alerts", true)) return;
      int radius = Math.max(8, plugin.getConfig().getInt("rekit-alerts.radius", 48));
      for (Player other : player.getWorld().getPlayers()) {
         if (other.equals(player) || other.getLocation().distanceSquared(player.getLocation()) > radius * radius) continue;
         other.sendMessage(Component.text("[RiftPvP] " + player.getName() + " changed kit to " + kit + " nearby.  ", NamedTextColor.LIGHT_PURPLE)
            .append(Component.text("[REPORT]", NamedTextColor.RED)
               .clickEvent(ClickEvent.suggestCommand("/report " + player.getName() + " "))));
      }
   }

   void history(Player viewer) {
      List<String> ids = index.getStringList("players." + viewer.getUniqueId());
      if (ids.isEmpty()) { viewer.sendMessage("§7No recorded matches yet."); return; }
      viewer.sendMessage("§5§lRiftPvP §d» Your recent matches");
      for (String id : ids) {
         YamlConfiguration record = readRecord(id);
         if (record == null) continue;
         String winner = record.getString("winner.uuid", "");
         String result = record.getString("status", "unknown");
         if ("finished".equals(result)) result = winner.equals(viewer.getUniqueId().toString()) ? "WIN" : "LOSS";
         viewer.sendMessage(Component.text(id + "  " + record.getString("kit", "unknown") + "  " + result + "  ", NamedTextColor.LIGHT_PURPLE)
            .append(Component.text("[DETAILS]", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/matchinfo " + id))));
      }
   }

   void inspect(CommandSender viewer, String id, boolean admin) {
      YamlConfiguration record = readRecord(id);
      if (record == null) { viewer.sendMessage("§cUnknown match ID or record no longer retained."); return; }
      viewer.sendMessage("§5§lMatch " + id + " §7(" + record.getString("status", "unknown") + ")");
      viewer.sendMessage("§7Kit: §f" + record.getString("kit", "unknown") + " §7Mode: §f" + record.getString("mode", "unknown"));
      viewer.sendMessage("§7Players: §f" + record.getString("players.a.name", "?") + " §7vs §f" + record.getString("players.b.name", "?"));
      viewer.sendMessage("§7Date: §f" + record.getString("timestamp", "unknown"));
      if (admin) {
         viewer.sendMessage("§7Coordinates: §f" + record.getString("world", "?") + " " + record.getInt("x") + "," + record.getInt("y") + "," + record.getInt("z"));
         viewer.sendMessage("§7Events actually recorded:");
         for (String line : record.getStringList("events")) viewer.sendMessage("§8• §7" + line);
      }
      for (String side : new String[]{"a", "b"}) {
         String uuid = record.getString("players." + side + ".uuid", "");
         String prefix = "statistics." + uuid + ".";
         if (record.contains(prefix + "hits"))
            viewer.sendMessage(String.format(java.util.Locale.ROOT,
               "§7%s: §f%d hits, %.1f damage, %.1f healed, combo %d, streak %d",
               record.getString("players." + side + ".name", "?"), record.getInt(prefix + "hits"),
               record.getDouble(prefix + "damage"), record.getDouble(prefix + "healing"),
               record.getInt(prefix + "longest-combo"), record.getInt(prefix + "win-streak")));
      }
      viewer.sendMessage("§7Outcome: §f" + record.getString("end-reason", "not finished")
         + " §7Duration: §f" + record.getLong("duration-seconds") + "s");
   }

   void report(Player reporter, String target, String reason) {
      if (reason.isBlank() || reason.length() > 200) {
         reporter.sendMessage("§cUsage: /report <player|match-id> <reason (max 200 chars)>"); return;
      }
      long now = System.currentTimeMillis();
      if (now - reportCooldown.getOrDefault(reporter.getUniqueId(), 0L) < 60000L) {
         reporter.sendMessage("§cPlease wait before sending another report."); return;
      }
      String match = target.toUpperCase(java.util.Locale.ROOT).startsWith("R-") ? target.toUpperCase(java.util.Locale.ROOT) : "";
      if (!match.isEmpty() && readRecord(match) == null) {
         reporter.sendMessage("§cUnknown match ID. Check /matchhistory."); return;
      }
      long nonce = System.nanoTime();
      String path = "reports." + Long.toUnsignedString(nonce) + ".";
      reports.set(path + "reporter", reporter.getUniqueId().toString());
      reports.set(path + "reporter-name", reporter.getName());
      reports.set(path + "target", target);
      reports.set(path + "match", match);
      reports.set(path + "reason", reason);
      reports.set(path + "date", Instant.now().toString());
      try {
         atomic(reportsFile, reports.saveToString());
         reportCooldown.put(reporter.getUniqueId(), now);
         reporter.sendMessage("§aReport recorded. §7Staff will review the evidence.");
         plugin.discord().send("reports", reporter.getName() + " reported " + target + " (" + match + "): " + reason);
      } catch (IOException error) { reporter.sendMessage("§cCouldn't save your report. Please contact staff."); }
   }

   private YamlConfiguration readRecord(String id) {
      if (!id.matches("R-[0-9]{8,19}")) return null;
      File file = new File(folder, id + ".yml");
      return file.isFile() ? YamlConfiguration.loadConfiguration(file) : null;
   }

   private void saveRecord(String id, YamlConfiguration record) throws IOException {
      atomic(new File(folder, id + ".yml"), record.saveToString());
   }

   private static void addEvent(YamlConfiguration record, String event) {
      List<String> values = new ArrayList<>(record.getStringList("events"));
      if (values.size() >= 100) values.remove(0);
      values.add(Instant.now() + ": " + event);
      record.set("events", values);
   }

   private static void atomic(File target, String text) throws IOException {
      File tmp = new File(target.getParentFile(), target.getName() + ".new");
      Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
      try { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException error) { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
   }

   private void cleanupOldRecords() {
      int limit = Math.max(100, plugin.getConfig().getInt("match-records.retention", 5000));
      File[] records = folder.listFiles((parent, name) -> name.matches("R-[0-9]{8,19}\\.yml"));
      if (records == null || records.length <= limit) return;
      java.util.Arrays.sort(records, Comparator.comparing(File::getName));
      for (int i = 0; i < records.length - limit; i++) {
         if (!records[i].delete()) plugin.getLogger().warning("Could not prune match record " + records[i]);
      }
   }
}
