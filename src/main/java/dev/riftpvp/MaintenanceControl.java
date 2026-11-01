package dev.riftpvp;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/** A persistent matchmaking drain mode; existing fights complete normally. */
final class MaintenanceControl implements Listener {
    private final RiftPvpPlugin plugin;
    private volatile boolean active;
    private volatile boolean restoring;
    MaintenanceControl(RiftPvpPlugin plugin) {
        this.plugin = plugin;
        active = plugin.getConfig().getBoolean("maintenance.enabled", false);
    }
    boolean isActive() { return active || restoring; }
    boolean allowNewMatch(Player player) {
        if (!isActive()) return true;
        if (player != null) player.sendMessage("§eRiftPvP is in maintenance. New matches are paused; current fights may finish.");
        return false;
    }
    void beginRestore() { restoring = true; }
    void endRestore() { restoring = false; }
    void reload() { active = plugin.getConfig().getBoolean("maintenance.enabled", false); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (restoring || plugin.dataBackups().restartRequired()) {
            // Do not access the Bukkit online player map or OP state on this async thread.
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                "RiftPvP is undergoing maintenance. Please rejoin after the restart.");
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(org.bukkit.event.player.PlayerLoginEvent event) {
        if (active && plugin.getConfig().getBoolean("maintenance.block-new-joins", false)
            && !event.getPlayer().isOp() && !plugin.ranks().canManageKits(event.getPlayer()))
            event.disallow(org.bukkit.event.player.PlayerLoginEvent.Result.KICK_OTHER,
                "RiftPvP is in maintenance. Staff may still join.");
    }
    void command(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "status";
        switch (action) {
            case "status" -> sender.sendMessage("§dMaintenance: " + (isActive() ? "§eON" : "§aOFF")
                + " §7| Active matches: " + plugin.practice().hasActiveFights()
                + " §7| Online: " + Bukkit.getOnlinePlayers().size());
            case "on", "off" -> {
                if (restoring || plugin.dataBackups().restartRequired()) {
                    sender.sendMessage("§cServer restart required before leaving maintenance."); return;
                }
                boolean value = action.equals("on");
                boolean before = active;
                plugin.getConfig().set("maintenance.enabled", value);
                try { plugin.saveConfig(); }
                catch (Exception exception) {
                    plugin.getConfig().set("maintenance.enabled", before);
                    sender.sendMessage("§cUnable to save maintenance mode: " + exception.getMessage()); return;
                }
                active = value;
                if (value) {
                    plugin.matchQueue().clearWaitingQueues();
                    plugin.practice().clearRankedWaiting();
                    plugin.customKits().clearWaitingOnly();
                }
                plugin.staffAudit().log(sender,"maintenance", value ? "enabled" : "disabled");
                Bukkit.broadcastMessage(value
                    ? "§eRiftPvP maintenance: new matches are paused; active fights may finish."
                    : "§aRiftPvP maintenance is over. Matchmaking is open again.");
            }
            default -> sender.sendMessage("§7/riftpvp maintenance [on|off|status]");
        }
    }
}
