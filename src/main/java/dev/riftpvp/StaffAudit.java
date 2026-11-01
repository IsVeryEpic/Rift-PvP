package dev.riftpvp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import org.bukkit.command.CommandSender;

/** Append-only, bounded staff action trail. The audit deliberately excludes webhook secrets. */
final class StaffAudit {
   private final RiftPvpPlugin plugin;
   private final File file;
   StaffAudit(RiftPvpPlugin plugin) {
      this.plugin = plugin;
      file = new File(plugin.getDataFolder(), "staff-audit.log");
   }
   void log(CommandSender sender, String action, String detail) {
      String clean = (Instant.now() + " | " + sender.getName() + " | " + action + " | " + detail)
         .replace('\n', ' ').replace('\r', ' ').replace('\u0000', ' ');
      try {
         if (file.exists() && file.length() > 5_000_000L) {
            Files.move(file.toPath(), new File(file.getParentFile(), "staff-audit.previous.log").toPath(), StandardCopyOption.REPLACE_EXISTING);
         }
         Files.writeString(file.toPath(), clean + System.lineSeparator(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      } catch (IOException error) { plugin.getLogger().severe("STAFF AUDIT WRITE FAILED: " + clean); }
      plugin.getLogger().info("AUDIT " + clean);
   }
   void recent(CommandSender viewer, int n) {
      if (!file.isFile()) { viewer.sendMessage("§7No staff actions logged yet."); return; }
      try {
         List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
         viewer.sendMessage("§dRecent RiftPvP staff actions:");
         for (int i = Math.max(0, lines.size() - Math.max(1, Math.min(20,n))); i < lines.size(); i++)
            viewer.sendMessage("§7" + lines.get(i));
      } catch (IOException error) { viewer.sendMessage("§cAudit log cannot be read."); }
   }
}
