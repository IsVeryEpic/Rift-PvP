package dev.riftpvp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

/** Per-kit repeated-pair evidence, non-punitive by default. */
final class RankedIntegrity {
   private final RiftPvpPlugin plugin;
   private final File file;
   private final YamlConfiguration yaml;
   RankedIntegrity(RiftPvpPlugin plugin) {
      this.plugin = plugin;
      file = new File(plugin.getDataFolder(), "ranked-integrity.yml");
      yaml = YamlConfiguration.loadConfiguration(file);
   }
   private static String pair(UUID a, UUID b, String kit) {
      return (a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a) + ":" + kit.replace('.', '_');
   }
   /** Always records the outcome. Returns true only when configured to suppress suspicious Elo changes. */
   boolean finished(String matchId, UUID a, UUID b, String kit, boolean abandoned) {
      if (abandoned || !plugin.getConfig().getBoolean("ranked.integrity.enabled", true)) return false;
      String key = pair(a, b, kit);
      String path = "pairs." + key;
      long now = System.currentTimeMillis();
      long window = Math.max(5, plugin.getConfig().getLong("ranked.integrity.window-minutes", 60)) * 60_000L;
      List<Long> times = new ArrayList<>(yaml.getLongList(path));
      times.removeIf(ts -> ts > now || now - ts > window);
      times.add(now);
      int threshold = Math.max(3, plugin.getConfig().getInt("ranked.integrity.flag-after-matches", 4));
      if (times.size() >= threshold) {
         long lastNotify = yaml.getLong("last-notified." + key, 0L);
         if (now - lastNotify >= window) {
            yaml.set("last-notified." + key, now);
            plugin.getLogger().warning("RANKED INTEGRITY: " + a + " vs " + b + " kit=" + kit + " repeats=" + times.size() + " match=" + matchId);
            plugin.matchLedger().flagIntegrity(matchId, "Pairings=" + times.size() + " in " + window/60000 + "m");
            plugin.discord().send("integrity", "Repeated ranked pair, " + kit + ", " + times.size() + " matches. Record " + matchId);
         }
      }
      yaml.set(path, times.size() > 24 ? times.subList(times.size() - 24, times.size()) : times);
      try { yaml.save(file); } catch (IOException e) { plugin.getLogger().warning("Ranked integrity could not save: " + e.getMessage()); }
      // Default never changes Elo, wins, placements, or punishes players without evidence review.
      return times.size() >= threshold && plugin.getConfig().getBoolean("ranked.integrity.suppress-repeat-elo", false);
   }
   void report(CommandSender sender) {
      var section = yaml.getConfigurationSection("pairs");
      if (section == null) { sender.sendMessage("§7No ranked integrity pairs yet."); return; }
      sender.sendMessage("§dRanked integrity: high-frequency pairing observations");
      int threshold = Math.max(3, plugin.getConfig().getInt("ranked.integrity.flag-after-matches", 4));
      long now = System.currentTimeMillis();
      long window = Math.max(5, plugin.getConfig().getLong("ranked.integrity.window-minutes", 60)) * 60_000L;
      section.getKeys(false).stream().filter(k -> section.getLongList(k).stream().filter(v -> v <= now && now - v <= window).count() >= threshold)
         .sorted(Comparator.naturalOrder()).limit(10).forEach(k -> sender.sendMessage("§e" + k));
   }
}
