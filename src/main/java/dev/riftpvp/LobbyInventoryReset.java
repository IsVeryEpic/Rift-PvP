package dev.riftpvp;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

/** Clear carried fight gear only after entering the configured RiftPvP lobby.
 * Preserve persistent stats/presets/XP and reissue the temporary lobby menus and VIP perks. */
final class LobbyInventoryReset implements Listener {
    private final RiftPvpPlugin plugin;
    private final Set<UUID> scheduled = new HashSet<>();

    LobbyInventoryReset(RiftPvpPlugin plugin) { this.plugin = plugin; }

    /** Explicit /lobby and post-match recovery always clear, even if automatic clearing is disabled. */
    void clearNow(Player player) {
        scheduled.remove(player.getUniqueId());
        if (plugin.matchReconnect().pending(player.getUniqueId())) return;
        if (!player.isOnline() || !plugin.inLobby(player.getLocation())) return;
        // A /spawn or other plugin can teleport a fighter directly into the
        // lobby; end the managed fight before wiping its temporary equipment.
        plugin.practice().lobby(player);
        plugin.matchQueue().cancelForLobby(player);
        plugin.customKits().removeWaiting(player.getUniqueId());
        player.closeInventory(); // Restore any Kit Room escrow before clearing.
        plugin.kits().clearEquippedKit(player);
        plugin.lobbyPerks().remove(player);
        plugin.lobbyHotbar().remove(player);
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.setItemOnCursor(null);
        if (plugin.getConfig().getBoolean("lobby.clear-potion-effects-on-entry", true)) {
            for (PotionEffect effect : player.getActivePotionEffects())
                player.removePotionEffect(effect.getType());
            player.setFireTicks(0);
            player.setFallDistance(0);
        }
        plugin.lobbyHotbar().refresh(player);
        plugin.lobbyVisibility().tick();
        plugin.lobbyPerks().tick();
        player.updateInventory();
    }

    private void queue(Player player, long delay) {
        UUID id = player.getUniqueId();
        if (!scheduled.add(id)) return; // /lobby and async teleports can trigger several entry hooks.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!scheduled.remove(id) || !player.isOnline()) return;
            if (plugin.matchReconnect().pending(id)) return; // resume fight first
            if (!plugin.getConfig().getBoolean("lobby.clear-inventory-on-entry", true)) return;
            if (plugin.inLobby(player.getLocation())) clearNow(player);
        }, delay);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // Later than the Kit Room escrow restoration and interrupted-match recovery.
        queue(event.getPlayer(), 5L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        // Resume logic must restore the fight gear before any lobby cleanup.
        if (plugin.matchReconnect().pending(event.getPlayer().getUniqueId())) return;
        // Do not wipe players for an ordinary teleport *within* the lobby.
        if (!plugin.inLobby(event.getFrom()) && plugin.inLobby(event.getTo()))
            queue(event.getPlayer(), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        if (plugin.inLobby(event.getRespawnLocation())) queue(event.getPlayer(), 2L);
    }
}
