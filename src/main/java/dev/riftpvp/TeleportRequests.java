package dev.riftpvp;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/** Non-OP player teleport invitations. Requests are bound to UUIDs, expire, and are revalidated. */
final class TeleportRequests implements Listener {
    private static final long EXPIRE_MS = 60_000L;
    private final RiftPvpPlugin plugin;
    private final Map<UUID, Request> pending = new HashMap<>(); // Recipient => request
    private final Map<UUID, Warmup> warmups = new HashMap<>();  // Traveller => warmup

    private record Request(UUID sender, UUID recipient, boolean here, long expires, UUID token) { }
    private record Warmup(UUID traveller, UUID destination, Location start, UUID sender, long expires) { }

    TeleportRequests(RiftPvpPlugin plugin) { this.plugin = plugin; }

    void command(Player player, String name, String[] args) {
        switch (name) {
            case "tpa", "tpahere" -> send(player, name.equals("tpahere"), args);
            case "tpaccept" -> answer(player, args, true);
            case "tpdeny" -> answer(player, args, false);
            case "tpcancel" -> cancel(player);
            default -> player.sendMessage("Unknown teleport command.");
        }
    }

    private boolean available(Player p) {
        if (!p.isOnline() || plugin.combat().tagged(p) || plugin.matchQueue().busy(p)) return false;
        if (plugin.inLobby(p.getLocation()) && !plugin.getConfig().getBoolean("tpa.allow-in-lobby", false)) return false;
        return true;
    }

    private void send(Player sender, boolean here, String[] args) {
        if (args.length != 1) { sender.sendMessage("§cUsage: /" + (here ? "tpahere" : "tpa") + " <player>"); return; }
        Player recipient = Bukkit.getPlayerExact(args[0]);
        if (recipient == null || !recipient.isOnline() || recipient.equals(sender)) {
            sender.sendMessage("§cThat player is unavailable."); return;
        }
        if (!plugin.playerPreferences().allows(recipient, "tpa")) {
            sender.sendMessage("§cThat player has disabled incoming teleport requests."); return;
        }
        if (!available(sender) || !available(recipient)) {
            sender.sendMessage("§cTeleport requests aren't allowed in combat, matches, or protected lobbies."); return;
        }
        long now = System.currentTimeMillis();
        Request existing = pending.get(recipient.getUniqueId());
        if (existing != null && existing.expires > now) {
            sender.sendMessage("§cThat player already has a pending request."); return;
        }
        Request req = new Request(sender.getUniqueId(), recipient.getUniqueId(), here, now + EXPIRE_MS, UUID.randomUUID());
        pending.put(req.recipient, req);
        sender.sendMessage("§dRiftPvP §8» §aRequest sent to " + recipient.getName() + ". Expires in 60 seconds.");
        recipient.sendMessage(Component.text("✦ RiftPvP » ", NamedTextColor.LIGHT_PURPLE)
            .append(Component.text(sender.getName() + (here ? " wants you to teleport to them." : " wants to teleport to you."), NamedTextColor.WHITE)));
        recipient.sendMessage(Component.text("[ACCEPT]", NamedTextColor.GREEN)
            .clickEvent(ClickEvent.runCommand("/tpaccept " + sender.getName() + " " + req.token()))
            .hoverEvent(HoverEvent.showText(Component.text("Accept this teleport request")))
            .append(Component.text("  "))
            .append(Component.text("[DECLINE]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/tpdeny " + sender.getName() + " " + req.token()))
                .hoverEvent(HoverEvent.showText(Component.text("Decline this teleport request")))));
        sender.sendMessage(Component.text("[CANCEL REQUEST]", NamedTextColor.YELLOW)
            .clickEvent(ClickEvent.runCommand("/tpcancel")));
    }

    private void answer(Player recipient, String[] args, boolean accept) {
        Request req = pending.get(recipient.getUniqueId());
        if (req == null || req.expires <= System.currentTimeMillis()) {
            pending.remove(recipient.getUniqueId());
            recipient.sendMessage("§cThere is no active teleport request."); return;
        }
        Player sender = Bukkit.getPlayer(req.sender);
        // Each clickable button contains the unique request ID. Old buttons
        // cannot accept/decline a later request from the same player.
        if (sender == null || (args.length > 0 && !sender.getName().equalsIgnoreCase(args[0]))
              || (args.length > 1 && !req.token().toString().equals(args[1]))) {
            recipient.sendMessage("§cThat request is no longer available."); return;
        }
        pending.remove(recipient.getUniqueId());
        if (!accept) {
            recipient.sendMessage("§eTeleport request declined.");
            sender.sendMessage("§c" + recipient.getName() + " declined your request.");
            return;
        }
        Player traveller = req.here ? recipient : sender;
        Player destination = req.here ? sender : recipient;
        if (!available(traveller) || !available(destination)) {
            recipient.sendMessage("§cTeleport was cancelled: a player is busy, tagged or in a protected location.");
            return;
        }
        UUID travellerId = traveller.getUniqueId();
        if (warmups.containsKey(travellerId)) {
            recipient.sendMessage("§cA teleport is already preparing."); return;
        }
        Warmup warmup = new Warmup(travellerId, destination.getUniqueId(), traveller.getLocation().clone(), req.sender, System.currentTimeMillis() + 3000);
        warmups.put(travellerId, warmup);
        traveller.sendMessage("§dRiftPvP §8» §eTeleporting in 3 seconds. Don't move or take damage.");
        destination.sendMessage("§dRiftPvP §8» §aTeleport accepted. Arriving in 3 seconds.");
        Bukkit.getScheduler().runTaskLater(plugin, () -> complete(warmup), 60L);
    }

    private void complete(Warmup warmup) {
        if (warmups.get(warmup.traveller) != warmup) return;
        warmups.remove(warmup.traveller);
        Player traveller = Bukkit.getPlayer(warmup.traveller);
        Player destination = Bukkit.getPlayer(warmup.destination);
        if (traveller == null || destination == null || !available(traveller) || !available(destination)
            || moved(warmup.start, traveller.getLocation())) {
            if (traveller != null) traveller.sendMessage("§cTeleport cancelled."); return;
        }
        Location arrival = destination.getLocation().clone();
        if (!safe(arrival)) { traveller.sendMessage("§cDestination is unsafe."); return; }
        traveller.teleportAsync(arrival, TeleportCause.PLUGIN).whenComplete((ok, ex) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (traveller.isOnline()) traveller.sendMessage(ex == null && Boolean.TRUE.equals(ok) ? "§aTeleported successfully." : "§cTeleport failed.");
            });
        });
    }

    private boolean safe(Location loc) {
        if (loc.getWorld() == null) return false;
        Block feet = loc.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block below = feet.getRelative(0, -1, 0);
        return below.getType().isSolid() && feet.isPassable() && head.isPassable()
            && feet.getType() != Material.WATER && feet.getType() != Material.LAVA
            && below.getType() != Material.MAGMA_BLOCK && below.getType() != Material.CACTUS;
    }

    private boolean moved(Location from, Location to) {
        return to == null || from.getWorld() != to.getWorld() || from.distanceSquared(to) > 0.04;
    }

    private void cancel(Player sender) {
        boolean removed = pending.entrySet().removeIf(e -> e.getValue().sender.equals(sender.getUniqueId()));
        sender.sendMessage(removed ? "§eTeleport request cancelled." : "§cYou don't have an outgoing request.");
    }

    @EventHandler public void onMove(PlayerMoveEvent event) {
        Warmup warmup = warmups.get(event.getPlayer().getUniqueId());
        if (warmup != null && moved(warmup.start, event.getTo())) {
            warmups.remove(warmup.traveller);
            event.getPlayer().sendMessage("§cTeleport cancelled because you moved.");
        }
    }

    @EventHandler public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && warmups.remove(player.getUniqueId()) != null)
            player.sendMessage("§cTeleport cancelled because you took damage.");
    }

    @EventHandler public void onWorld(PlayerChangedWorldEvent event) { invalidate(event.getPlayer().getUniqueId()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { invalidate(event.getPlayer().getUniqueId()); }

    private void invalidate(UUID id) {
        pending.entrySet().removeIf(e -> e.getValue().recipient.equals(id) || e.getValue().sender.equals(id));
        warmups.entrySet().removeIf(e -> e.getValue().traveller.equals(id) || e.getValue().destination.equals(id));
    }

    void close() { pending.clear(); warmups.clear(); }
}
