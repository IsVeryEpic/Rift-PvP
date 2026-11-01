package dev.riftpvp;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/** Bounded on-disk data snapshots. Never archives the backup directory or follows symlinks. */
final class DataBackups {
    private final RiftPvpPlugin plugin;
    private final Path root, folder;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean restartRequired;
    DataBackups(RiftPvpPlugin plugin) {
        this.plugin = plugin;
        root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        folder = root.resolve("backups");
    }
    boolean restartRequired() { return restartRequired; }
    private int limitMB() { return Math.max(4, Math.min(512, plugin.getConfig().getInt("backups.max-size-mb", 128))); }
    void start() {
        if (!plugin.getConfig().getBoolean("backups.enabled", true)) return;
        long interval = Math.max(30L, plugin.getConfig().getLong("backups.interval-minutes", 120L));
        Bukkit.getScheduler().runTaskTimer(plugin, () -> snapshotAsync(null,"scheduled"), interval * 1200L, interval * 1200L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> snapshotAsync(null,"startup"), 100L);
    }
    private boolean include(Path file) {
        Path relative = root.relativize(file);
        if (relative.startsWith("backups") || relative.startsWith("kit-room-escrow")) return false;
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.equals("match-sequence.txt");
    }
    void snapshotAsync(CommandSender requester, String reason) {
        if (!running.compareAndSet(false, true)) {
            if (requester != null) requester.sendMessage("§eA backup or restore is already running.");
            return;
        }
        if (requester != null) requester.sendMessage("§7Creating RiftPvP data backup...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String message;
            try { message = "§aBackup created: §f" + snapshot(reason).getFileName(); }
            catch (Exception error) { message = "§cBackup failed: " + error.getMessage(); plugin.getLogger().warning(message); }
            finally { running.set(false); }
            String feedback = message;
            if (requester != null && plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> requester.sendMessage(feedback));
        });
    }
    private Path snapshot(String reason) throws IOException {
        Files.createDirectories(folder);
        String when = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now());
        Path file = folder.resolve("backup-" + when + "-" + reason + ".zip");
        Path temporary = folder.resolve(file.getFileName() + ".tmp");
        long max = (long)limitMB() * 1024 * 1024;
        try {
            List<Path> files;
            try (var stream = Files.walk(root)) {
                files = stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> !Files.isSymbolicLink(p) && include(p)).sorted().toList();
            }
            long estimated = 0;
            for (Path p : files) { estimated += Files.size(p); if (estimated > max) throw new IOException("data exceeds configured size cap"); }
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                for (Path p : files) {
                    out.putNextEntry(new ZipEntry(root.relativize(p).toString().replace(File.separatorChar, '/')));
                    Files.copy(p, out);
                    out.closeEntry();
                }
            }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file); }
            prune();
            return file;
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }
    private List<Path> backups() throws IOException {
        Files.createDirectories(folder);
        try (var stream = Files.list(folder)) {
            return stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .filter(p -> p.getFileName().toString().matches("backup-[0-9]{8}-[0-9]{6}-[0-9]{3}-(startup|scheduled|manual|pre-restore)\\.zip"))
                .sorted(Comparator.reverseOrder()).toList();
        }
    }
    private void prune() throws IOException {
        int keep = Math.max(2, Math.min(50, plugin.getConfig().getInt("backups.keep", 10)));
        List<Path> list = backups();
        for (int i = keep; i < list.size(); i++) Files.deleteIfExists(list.get(i));
    }
    void command(CommandSender sender, String[] args) {
        String op = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        try {
            if (op.equals("list")) {
                sender.sendMessage("§dRiftPvP backups (newest first):");
                for (Path p : backups().stream().limit(10).toList()) sender.sendMessage("§7" + p.getFileName());
            } else if (op.equals("now")) snapshotAsync(sender,"manual");
            else if (op.equals("restore")) {
                if (args.length != 4 || !args[3].equalsIgnoreCase("confirm")) {
                    sender.sendMessage("§cUsage: /riftpvp backup restore <backup-file.zip> confirm"); return;
                }
                // Restrict disk-wide restoration to console when no players are online.
                if (sender instanceof org.bukkit.entity.Player || !Bukkit.getOnlinePlayers().isEmpty()
                    || !plugin.maintenance().isActive()) {
                    sender.sendMessage("§cRestore is console-only, requires maintenance ON and zero online players."); return;
                }
                Path chosen = folder.resolve(args[2]).normalize();
                if (!chosen.getParent().equals(folder) || !backups().contains(chosen)) {
                    sender.sendMessage("§cUnknown backup. Use /riftpvp backup list."); return;
                }
                if (!running.compareAndSet(false,true)) { sender.sendMessage("§eAnother backup operation is active."); return; }
                plugin.maintenance().beginRestore();
                sender.sendMessage("§ePreparing backup for the next full server restart. Current data remains unchanged.");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        Path pending = folder.resolve("pending-restore.zip");
                        Path temporary = folder.resolve("pending-restore.zip.tmp");
                        // Copy first: pruning a new pre-restore snapshot must not delete the selected archive.
                        Files.copy(chosen, temporary, StandardCopyOption.REPLACE_EXISTING);
                        snapshot("pre-restore");
                        try { Files.move(temporary, pending, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                        catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, pending, StandardCopyOption.REPLACE_EXISTING); }
                        restartRequired = true;
                        plugin.getLogger().warning("Restore staged: " + chosen.getFileName() + ". Stop and START the whole server to apply it.");
                    } catch (Exception ex) {
                        plugin.getLogger().severe("Backup staging failed, current data unchanged: " + ex.getMessage());
                    } finally {
                        running.set(false);
                        plugin.maintenance().endRestore();
                    }
                });
            } else sender.sendMessage("§7/riftpvp backup [now|list|restore <filename> confirm]");
        } catch (IOException error) { sender.sendMessage("§cBackup error: " + error.getMessage()); }
    }
    /** Must execute before ANY plugin data has been read into manager caches. */
    void applyPendingRestoreAtStartup() throws IOException {
        Path pending = folder.resolve("pending-restore.zip");
        if (!Files.exists(pending)) return;
        if (!Files.isRegularFile(pending, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(pending))
            throw new IOException("Pending restore must be a regular archive");
        plugin.getLogger().warning("Applying staged RiftPvP backup before loading plugin data...");
        restore(pending);
        plugin.reloadConfig(); // Reload the restored configuration before any managers initialise.
        Files.delete(pending);
        plugin.getLogger().warning("RiftPvP backup restore completed. Check the server data before accepting players.");
    }
    private void restore(Path archive) throws IOException {
        Path staging = Files.createTempDirectory(folder, "restore-");
        long total = 0, maximum = (long)limitMB() * 1024 * 1024;
        try {
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                ZipEntry entry;
                int entries = 0;
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > 15000) throw new IOException("too many archive entries");
                    if (entry.isDirectory()) continue;
                    Path relative = Path.of(entry.getName()).normalize();
                    Path target = root.resolve(relative).normalize();
                    if (relative.isAbsolute() || !target.startsWith(root) || !include(target)) throw new IOException("unsafe archive entry");
                    Path extracted = staging.resolve(relative).normalize();
                    if (!extracted.startsWith(staging)) throw new IOException("unsafe staging path");
                    Files.createDirectories(extracted.getParent());
                    try (OutputStream out = Files.newOutputStream(extracted, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        byte[] bytes = new byte[8192]; int n;
                        while ((n = zip.read(bytes)) != -1) {
                            total += n;
                            if (total > maximum) throw new IOException("expanded backup exceeds size limit");
                            out.write(bytes,0,n);
                        }
                    }
                }
            }
            try (var stream = Files.walk(staging)) {
                for (Path p : stream.filter(Files::isRegularFile).toList()) {
                    Path target = root.resolve(staging.relativize(p)).normalize();
                    // Symlink protection also applies to pre-existing data folders.
                    Path parent = target.getParent();
                    while (parent != null && parent.startsWith(root)) {
                        if (Files.isSymbolicLink(parent)) throw new IOException("symbolic-link target blocked");
                        parent = parent.getParent();
                    }
                    if (Files.isSymbolicLink(target)) throw new IOException("symbolic-link file blocked");
                    Files.createDirectories(target.getParent());
                    Files.move(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            try (var walk = Files.walk(staging)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }
}
