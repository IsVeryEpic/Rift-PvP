package dev.riftpvp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

/**
 * Opt-in, one-way public leaderboard export. Snapshots Bukkit state on the
 * server thread; sends only immutable JSON using the JDK asynchronous client.
 * The receiver authenticates and upserts UUID+server ID; it never changes ranks.
 */
final class WebsiteSync {
   record PlayerStats(String name, int kills, int deaths, long playtimeMs) {}
   record KitRanking(int elo, int wins, int losses, String tier) {}

   private final RiftPvpPlugin plugin;
   private final HttpClient client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();
   private final AtomicBoolean inFlight = new AtomicBoolean(false);
   private BukkitTask timer;
   private volatile long lastSuccessMillis;
   private volatile int lastHttpStatus;
   private volatile String lastError = "Never synchronized";

   WebsiteSync(RiftPvpPlugin plugin) {
      this.plugin = plugin;
   }

   void start() {
      stop();
      if (!plugin.getConfig().getBoolean("website-sync.enabled", false)) return;
      String problem = configurationError();
      if (problem != null) {
         plugin.getLogger().warning("Website sync disabled: " + problem);
         return;
      }
      long seconds = Math.max(30L, plugin.getConfig().getLong("website-sync.interval-seconds", 60L));
      timer = Bukkit.getScheduler().runTaskTimer(plugin, this::push, 20L, seconds * 20L);
      plugin.getLogger().info("Website sync started (every " + seconds + " seconds).");
   }

   void stop() {
      if (timer != null) {
         timer.cancel();
         timer = null;
      }
   }

   void command(CommandSender sender, String[] args) {
      // Caller checks OP status. Do not expose endpoint or authorization token.
      if (args.length >= 2 && args[1].equalsIgnoreCase("now")) {
         if (push()) sender.sendMessage("§aWebsite sync request started. Use /riftpvp websync status to check the result.");
         else sender.sendMessage("§cWebsite sync could not start: " +
            (inFlight.get() ? "previous request is still pending" : String.valueOf(configurationError())));
         return;
      }
      sender.sendMessage("§dWebsite sync §7| " + (plugin.getConfig().getBoolean("website-sync.enabled", false) ? "enabled" : "disabled"));
      sender.sendMessage("§7In flight: §f" + inFlight.get() + " §7| Last HTTP status: §f" + (lastHttpStatus == 0 ? "none" : lastHttpStatus));
      sender.sendMessage("§7Last success: §f" + (lastSuccessMillis == 0 ? "never" : Instant.ofEpochMilli(lastSuccessMillis)));
      sender.sendMessage("§7Status: §f" + (configurationError() == null ? lastError : configurationError()));
   }

   private String configurationError() {
      if (!plugin.getConfig().getBoolean("website-sync.enabled", false)) return "disabled in config.yml";
      String raw = plugin.getConfig().getString("website-sync.endpoint", "").trim();
      try {
         URI uri = URI.create(raw);
         if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
               || uri.getUserInfo() != null || uri.getFragment() != null)
            return "endpoint must be a valid HTTPS URL";
      } catch (IllegalArgumentException error) {
         return "endpoint must be a valid HTTPS URL";
      }
      String key = plugin.getConfig().getString("website-sync.api-key", "");
      if (key.isBlank() || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0)
         return "set a valid website-sync.api-key";
      String id = plugin.getConfig().getString("website-sync.server-id", "");
      if (!id.matches("[A-Za-z0-9_-]{1,64}")) return "server-id must be 1-64 letters, digits, _ or -";
      return null;
   }

   private boolean push() {
      // This method is always called on the primary Paper thread.
      if (configurationError() != null || !inFlight.compareAndSet(false, true)) return false;
      try {
         String payload = snapshotJson();
         URI endpoint = URI.create(plugin.getConfig().getString("website-sync.endpoint").trim());
         String key = plugin.getConfig().getString("website-sync.api-key");
         HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
         client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, failure) -> {
            inFlight.set(false);
            if (failure != null) {
               Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                  ? failure.getCause() : failure;
               lastError = "Network failure: " + cause.getClass().getSimpleName();
               plugin.getLogger().warning("Website sync failed: " + lastError);
               return;
            }
            lastHttpStatus = response.statusCode();
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
               lastSuccessMillis = System.currentTimeMillis();
               lastError = "OK";
            } else {
               lastError = "HTTP " + response.statusCode();
               plugin.getLogger().warning("Website sync rejected by API: HTTP " + response.statusCode());
            }
         });
         return true;
      } catch (RuntimeException error) {
         inFlight.set(false);
         lastError = "Request preparation failed: " + error.getClass().getSimpleName();
         plugin.getLogger().warning("Website sync failed: " + lastError);
         return false;
      }
   }

   private String snapshotJson() {
      Map<UUID, PlayerStats> stats = plugin.sidebar().websiteSnapshot();
      Map<UUID, Map<String, KitRanking>> rankings = plugin.practice().websiteRankingsSnapshot();
      Set<UUID> ids = new HashSet<>(stats.keySet());
      ids.addAll(rankings.keySet());
      List<UUID> ordered = new ArrayList<>(ids);
      ordered.sort(Comparator.comparing(UUID::toString));

      StringBuilder json = new StringBuilder(256 + ordered.size() * 160);
      json.append("{\"schema_version\":1,\"server_id\":");
      quote(json, plugin.getConfig().getString("website-sync.server-id"));
      json.append(",\"generated_at\":");
      quote(json, Instant.now().toString());
      json.append(",\"players\":[");
      boolean firstPlayer = true;
      for (UUID uuid : ordered) {
         if (!firstPlayer) json.append(',');
         firstPlayer = false;
         PlayerStats stat = stats.getOrDefault(uuid, new PlayerStats(null, 0, 0, 0L));
         String name = stat.name();
         var online = Bukkit.getPlayer(uuid);
         if (online != null) name = online.getName();
         json.append("{\"uuid\":");
         quote(json, uuid.toString());
         json.append(",\"name\":");
         quote(json, name);
         json.append(",\"rank\":");
         quote(json, plugin.ranks().rankId(uuid));
         json.append(",\"kills\":").append(stat.kills())
            .append(",\"deaths\":").append(stat.deaths())
            .append(",\"playtime_ms\":").append(stat.playtimeMs())
            .append(",\"kits\":{");
         Map<String, KitRanking> kits = rankings.getOrDefault(uuid, Map.of());
         boolean firstKit = true;
         for (String kit : kits.keySet().stream().sorted().toList()) {
            if (!firstKit) json.append(',');
            firstKit = false;
            KitRanking rating = kits.get(kit);
            quote(json, kit);
            json.append(":{\"elo\":").append(rating.elo())
               .append(",\"wins\":").append(rating.wins())
               .append(",\"losses\":").append(rating.losses())
               .append(",\"tier\":");
            quote(json, rating.tier());
            json.append('}');
         }
         json.append("}}");
      }
      return json.append("]}").toString();
   }

   /** Encode JSON strings without adding an external library to the Paper plugin. */
   private static void quote(StringBuilder out, String value) {
      if (value == null) {
         out.append("null");
         return;
      }
      out.append('"');
      for (int i = 0; i < value.length(); i++) {
         char c = value.charAt(i);
         switch (c) {
            case '"' -> out.append("\\\"");
            case '\\' -> out.append("\\\\");
            case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");
            case '\t' -> out.append("\\t");
            default -> {
               if (c < 0x20) out.append(String.format("\\u%04x", (int)c));
               else out.append(c);
            }
         }
      }
      out.append('"');
   }
}
