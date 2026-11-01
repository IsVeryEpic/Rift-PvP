package dev.riftpvp;

import java.util.*;
import org.bukkit.entity.Player;

/** Four independent modes per server kit. Defaults preserve existing availability. */
final class KitModes {
    private final RiftPvpPlugin plugin;
    private static final Set<String> MODES = Set.of("ranked", "queue", "duel", "custom");
    KitModes(RiftPvpPlugin plugin) { this.plugin = plugin; }
    boolean allowed(String kit, String mode) {
        if (!MODES.contains(mode)) return false;
        if (CustomKits.isCustom(kit)) return true; // custom source is checked when loaded/hosted
        return plugin.kits().hasKit(kit) && plugin.getConfig().getBoolean("kits." + kit + ".modes." + mode, true);
    }
    boolean check(Player player, String kit, String mode) {
        if (allowed(kit, mode)) return true;
        player.sendMessage("§c" + kit + " is currently disabled for " + mode + " matches.");
        return false;
    }
    void command(Player player, String[] args) {
        if (!plugin.ranks().canManageKits(player)) { player.sendMessage("§cOnly OP/Admin/Founder may change kit modes."); return; }
        if (args.length < 1 || !plugin.kits().hasKit(args[0].toLowerCase(Locale.ROOT))) {
            player.sendMessage("§7/kitmodes <kit> [ranked|queue|duel|custom] [on|off]"); return;
        }
        String kit = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            for (String m : List.of("ranked", "queue", "duel", "custom"))
                player.sendMessage("§d" + kit + " §7" + m + ": " + (allowed(kit,m) ? "§aON" : "§cOFF"));
            return;
        }
        String mode = args[1].toLowerCase(Locale.ROOT);
        if (!MODES.contains(mode) || args.length != 3 || !List.of("on", "off").contains(args[2].toLowerCase(Locale.ROOT))) {
            player.sendMessage("§7/kitmodes <kit> <ranked|queue|duel|custom> <on|off>"); return;
        }
        boolean desired = args[2].equalsIgnoreCase("on");
        String path = "kits." + kit + ".modes." + mode;
        Object previous = plugin.getConfig().get(path);
        plugin.getConfig().set(path, desired);
        try { plugin.saveConfig(); }
        catch (Exception error) { plugin.getConfig().set(path, previous); player.sendMessage("§cCannot save kit mode."); return; }
        if (!desired) {
            if (mode.equals("queue")) plugin.matchQueue().removeKitQueue(kit);
            if (mode.equals("ranked")) plugin.practice().removeRankedKitQueue(kit);
            if (mode.equals("duel")) plugin.practice().removeKitInvites(kit);
            if (mode.equals("custom")) plugin.customKits().removeHostsForSource(kit);
        }
        plugin.staffAudit().log(player,"kit-mode",kit + " " + mode + " " + desired);
        player.sendMessage("§a" + kit + " " + mode + " is now " + (desired ? "enabled" : "disabled") + ". Existing fights are unaffected.");
    }
}
