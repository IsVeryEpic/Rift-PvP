package dev.riftpvp;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.projectiles.ProjectileSource;

/** Short in-process match pause while a player reconnects. Server restarts use MatchRecovery. */
final class MatchReconnect implements Listener {
    private record Waiting(UUID opponent, Location location, long deadline) {}
    private final RiftPvpPlugin plugin;
    private final Map<UUID, Waiting> offline = new HashMap<>();
    private final Set<UUID> frozen = new HashSet<>();
    private final Set<UUID> resuming = new HashSet<>();
    MatchReconnect(RiftPvpPlugin plugin) { this.plugin = plugin; }
    boolean pending(UUID id) { return offline.containsKey(id); }
    int pendingCount() { return offline.size(); }
    boolean frozen(UUID id) { return frozen.contains(id); }
    boolean onDisconnect(Player player) {
        if (!plugin.getConfig().getBoolean("reconnect.enabled", true)) return false;
        UUID id = player.getUniqueId();
        if (offline.containsKey(id)) return true;
        if (!plugin.practice().hasActiveFight(id) || !plugin.matchQueue().isInActiveFight(id)) return false;
        UUID other = plugin.matchQueue().opponentOf(id);
        if (other == null) return false;
        int seconds = Math.max(5, Math.min(90, plugin.getConfig().getInt("reconnect.grace-seconds", 25)));
        offline.put(id, new Waiting(other, player.getLocation().clone(), System.currentTimeMillis() + seconds * 1000L));
        frozen.add(id); frozen.add(other);
        Player opponent = Bukkit.getPlayer(other);
        if (opponent != null && opponent.isOnline()) opponent.sendMessage("§eOpponent disconnected. Fight paused for up to " + seconds + " seconds. Don't leave the match.");
        Bukkit.getScheduler().runTaskLater(plugin, () -> expire(id), seconds * 20L + 1L);
        return true;
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void joined(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (pending(player.getUniqueId())) Bukkit.getScheduler().runTaskLater(plugin, () -> resume(), 3L);
    }
    void clearPair(UUID one, UUID two) {
        offline.remove(one); frozen.remove(one); resuming.remove(one);
        if (two != null) { offline.remove(two); frozen.remove(two); resuming.remove(two); }
    }
    private void expire(UUID player) {
        Waiting waiting = offline.get(player);
        if (waiting == null) return;
        if (System.currentTimeMillis() < waiting.deadline()) return;
        UUID opponent = waiting.opponent();
        offline.remove(player);
        Player timedOutPlayer = Bukkit.getPlayer(player);
        Player otherPlayer = Bukkit.getPlayer(opponent);
        boolean timedOutOnline = timedOutPlayer != null && timedOutPlayer.isOnline();
        boolean opponentOnline = otherPlayer != null && otherPlayer.isOnline();
        if (bothOffline(timedOutOnline, opponentOnline)) {
            // Neither contestant returned. No arbitrary winner or Elo loss.
            plugin.practice().expireMatch(player);
            plugin.matchQueue().expireDisconnected(player);
            plugin.matchRecovery().started(player, opponent);
            return;
        }
        // Only a genuinely offline player forfeits after the grace period.
        if (timedOutOnline) {
            // Reconnected but teleport did not complete before deadline: cancel, never punish as a dodge.
            plugin.practice().expireMatch(player);
            plugin.matchQueue().expireDisconnected(player);
            plugin.matchRecovery().recoverOnline();
            return;
        }
        plugin.practice().forfeitDisconnected(player);
        plugin.matchQueue().expireDisconnected(player);
        // The fight is over. Recover both fighter inventories/locations; the
        // disconnected fighter's marker is retained until their next login.
        plugin.matchRecovery().started(player, opponent);
        Player remaining = Bukkit.getPlayer(opponent);
        if (remaining != null && remaining.isOnline()) {
            remaining.sendMessage("§aOpponent failed to reconnect in time. Match result recorded.");
            plugin.matchRecovery().recoverSoon(remaining);
        }
    }
    static boolean bothOffline(boolean firstOnline, boolean secondOnline) {\n        return !firstOnline && !secondOnline;\n    }\n\n    private void resume() {
        if (offline.isEmpty() || !plugin.isEnabled()) return;
        // Resume only once the specific opponent is still in the same managed fight.
        for (UUID id : new HashSet<>(offline.keySet())) {
            Waiting waiting = offline.get(id);
            if (waiting == null || System.currentTimeMillis() >= waiting.deadline()) continue;
            Player player = Bukkit.getPlayer(id), opponent = Bukkit.getPlayer(waiting.opponent());
            if (player == null || opponent == null || !player.isOnline() || !opponent.isOnline()) continue;
            if (resuming.contains(id) || !plugin.matchQueue().isInActiveFight(id)) continue;
            // Wait until all participants have rejoined if both disconnected.
            // When both participants were absent, both must be online before either resumes.
            resuming.add(id);
            player.teleportAsync(waiting.location().clone()).whenComplete((ok, error) -> {
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    resuming.remove(id);
                    if (error != null || !Boolean.TRUE.equals(ok) || !plugin.matchQueue().isInActiveFight(id)) {
                        // Do not award a win on plugin/server teleport failure.
                        plugin.practice().expireMatch(id);
                        plugin.matchQueue().expireDisconnected(id);
                        plugin.matchRecovery().recoverOnline();
                        return;
                    }
                    offline.remove(id);
                    if (!offline.containsKey(waiting.opponent())) {
                        frozen.remove(id); frozen.remove(waiting.opponent());
                        player.sendMessage("§aMatch resumed. Good luck!");
                        if (opponent.isOnline()) opponent.sendMessage("§aMatch resumed. Good luck!");
                    }
                });
            });
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drop(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void swap(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void command(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        if (!frozen.contains(event.getPlayer().getUniqueId())) return;
        if (!event.getMessage().trim().equalsIgnoreCase("/lobby")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§eThis fight is paused awaiting reconnect. Use /lobby to forfeit.");
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && frozen.contains(player.getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        if (!frozen.contains(event.getPlayer().getUniqueId()) || event.getTo() == null) return;
        Location a = event.getFrom(), b = event.getTo();
        if (a.getX() != b.getX() || a.getY() != b.getY() || a.getZ() != b.getZ()) {
            Location locked = a.clone(); locked.setYaw(b.getYaw()); locked.setPitch(b.getPitch()); event.setTo(locked);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void projectile(ProjectileLaunchEvent event) {
        ProjectileSource source = event.getEntity().getShooter();
        if (source instanceof Player player && frozen.contains(player.getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void inventory(InventoryClickEvent event) {
        if (frozen.contains(event.getWhoClicked().getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        if (frozen.contains(event.getWhoClicked().getUniqueId())) event.setCancelled(true);
    }
}
