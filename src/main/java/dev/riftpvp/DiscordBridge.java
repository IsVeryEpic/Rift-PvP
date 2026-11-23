package dev.riftpvp;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;

/** Opt-in, non-blocking webhook delivery. Never sends player IPs, inventories or config secrets. */
final class DiscordBridge {
   private final RiftPvpPlugin plugin;
   private final AtomicInteger inFlight = new AtomicInteger();

   DiscordBridge(RiftPvpPlugin plugin) {
      this.plugin = plugin;
   }

   void send(String type, String information) {
      if (!plugin.getConfig().getBoolean("discord.enabled", false)) return;
      String raw = plugin.getConfig().getString("discord.webhook-url", "").trim();
      if (raw.isBlank() || !raw.startsWith("https://")) return;
      if (!plugin.getConfig().getBoolean("discord.events." + type, true)) return;

      URI uri;
      try {
         uri = URI.create(raw);
         if (!"discord.com".equalsIgnoreCase(uri.getHost())
               && !"discordapp.com".equalsIgnoreCase(uri.getHost())) return;
         if (uri.getRawUserInfo() != null || uri.getPort() != -1) return;
      } catch (IllegalArgumentException error) {
         return;
      }

      if (inFlight.incrementAndGet() > 8) {
         inFlight.decrementAndGet();
         return;
      }

      String sanitized = ("RiftPvP | " + type + " | " + information)
         .replace('\n', ' ')
         .replace('\r', ' ')
         .replace("@", "＠");
      if (sanitized.length() > 1600) sanitized = sanitized.substring(0, 1600);
      final String message = sanitized;

      Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
         HttpURLConnection conn = null;
         try {
            conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(3500);
            conn.setInstanceFollowRedirects(false);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            String json = "{\"content\":\"" + escapeJson(message)
               + "\",\"allowed_mentions\":{\"parse\":[]}}";
            try (var stream = conn.getOutputStream()) {
               stream.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300)
               plugin.getLogger().warning("Discord webhook HTTP " + code + " (" + type + ")");
         } catch (Exception error) {
            plugin.getLogger().warning("Discord webhook unavailable: " + error.getClass().getSimpleName());
         } finally {
            if (conn != null) conn.disconnect();
            inFlight.decrementAndGet();
         }
      });
   }

   /** Escapes every JSON control character, not just common chat characters. */
   static String escapeJson(String value) {
      StringBuilder escaped = new StringBuilder(value.length() + 16);
      for (int i = 0; i < value.length(); i++) {
         char c = value.charAt(i);
         switch (c) {
            case '"' -> escaped.append("\\\"");
            case '\\' -> escaped.append("\\\\");
            case '\b' -> escaped.append("\\b");
            case '\f' -> escaped.append("\\f");
            case '\n' -> escaped.append("\\n");
            case '\r' -> escaped.append("\\r");
            case '\t' -> escaped.append("\\t");
            default -> {
               if (c < 0x20) {
                  escaped.append("\\u00");
                  escaped.append(Character.forDigit((c >> 4) & 0xF, 16));
                  escaped.append(Character.forDigit(c & 0xF, 16));
               } else {
                  escaped.append(c);
               }
            }
         }
      }
      return escaped.toString();
   }
}
