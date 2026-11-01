package dev.riftpvp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Restored central player menu. All actual actions pass through existing command permissions. */
final class RiftMenu implements Listener, CommandExecutor {
    private final RiftPvpPlugin plugin;
    RiftMenu(RiftPvpPlugin plugin) { this.plugin = plugin; }

    /** Retains queue access: sneak + right-click the hub Compass. */
    static void openFromOld(MenuHandler menu, Player player) {
        if (player.isSneaking()) menu.openQueue(player);
        else player.performCommand("riftmenu");
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("§cThis menu can only open in-game."); return true; }
        open(player);
        return true;
    }

    void open(Player player) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 45, "§5§lRiftPvP §8| §dRift Menu");
        holder.inventory = inv;
        ItemStack background = item(Material.PURPLE_STAINED_GLASS_PANE, "§5 ", List.of());
        for (int slot = 0; slot < 45; slot++) inv.setItem(slot, background);
        put(holder, 10, Material.HEART_OF_THE_SEA, "§d§lRTP Queues", "§7Choose a PvP kit and find a match", "rtpq");
        put(holder, 11, Material.SMITHING_TABLE, "§b§lKit Editor", "§7Configure your saved PvP layouts", "kiteditor");
        put(holder, 12, Material.NETHER_STAR, "§d§lCustom Kits", "§7Manage and host your six personal kits", "customkit");
        put(holder, 14, Material.NETHERITE_SWORD, "§c§lRanked PvP", "§7View your ranked matchmaking options", "ranked");
        put(holder, 15, Material.DIAMOND_SWORD, "§6§lDuels", "§7Challenge another player with /duel", "duel");
        put(holder, 16, Material.AMETHYST_SHARD, "§a§lRandom Teleport", "§7Overworld, Nether or End", "rtp");
        put(holder, 19, Material.GOLD_BLOCK, "§e§lLeaderboards", "§7Kills, deaths and playtime", "leaderboard");
        put(holder, 20, Material.PLAYER_HEAD, "§b§lMy Profile", "§7View your stats and kit ranks", "profile");
        put(holder, 21, Material.REPEATER, "§f§lSettings", "§7Requests, sound and queue preferences", "settings");
        put(holder, 23, Material.SPYGLASS, "§d§lVisibility", "§7Everyone / Party / Hidden", "visibility");
        put(holder, 24, Material.BOOK, "§a§lTutorial", "§7How RiftPvP works", "tutorial");
        put(holder, 25, Material.GRASS_BLOCK, "§a§lLobby", "§7Return to lobby safely", "lobby");
        // Never grant commands by displaying an icon. Staff and admin actions validate again on execution.
        if (plugin.staffTools().hasStaffAccess(player))
            put(holder, 30, Material.NETHER_STAR, "§d§lStaff Panel", "§7Moderator tools", "staff");
        if (player.isOp())
            put(holder, 32, Material.COMPARATOR, "§c§lAdmin Controls", "§7Player moderation and server management", "admin-panel");
        put(holder, 40, Material.BARRIER, "§cClose", "§7Close this menu", "__close");
        player.openInventory(inv);
    }

    private static ItemStack item(Material mat, String title, List<String> lore) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) { meta.setDisplayName(title); meta.setLore(lore); stack.setItemMeta(meta); }
        return stack;
    }

    private static void put(Holder holder, int slot, Material mat, String title, String description, String command) {
        holder.inventory.setItem(slot, item(mat, title, List.of(description, "§8Click to open")));
        holder.commands.put(slot, command);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        String selected = holder.commands.get(event.getRawSlot());
        if (selected == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (player.getOpenInventory().getTopInventory().getHolder() != holder) return;
            player.closeInventory();
            if ("__close".equals(selected)) return;
            // Core submenu dispatch uses the existing command executor and permission checks,
            // but opens the menus directly to prevent WorldEdit or vanilla item interactions.
            switch (selected) {
                case "rtpq" -> {
                    if (plugin.ranks().hasPluginPermission(player, "riftpvp.queue")) plugin.menus().openQueue(player);
                }
                case "ranked" -> {
                    if (plugin.ranks().hasPluginPermission(player, "riftpvp.ranked")) plugin.menus().openRanked(player);
                }
                case "rtp" -> {
                    if (plugin.ranks().hasPluginPermission(player, "riftpvp.rtp")) plugin.menus().openRtp(player);
                }
                case "kiteditor" -> {
                    if (plugin.ranks().hasPluginPermission(player, "riftpvp.kiteditor")) plugin.menus().openEditorSelection(player);
                }
                case "leaderboard" -> {
                    if (plugin.ranks().hasPluginPermission(player, "riftpvp.leaderboard")) plugin.menus().openLeaderboard(player);
                }
                case "settings" -> {
                    if (plugin.ranks().hasPluginPermission(player, "riftpvp.settings")) plugin.playerPreferences().open(player);
                }
                case "staff" -> {
                    if (plugin.staffTools().hasStaffAccess(player)) plugin.staffTools().open(player);
                }
                case "admin-panel" -> {
                    if (player.isOp()) plugin.adminPanel().open(player);
                }
                default -> player.performCommand(selected);
            }
        });
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    private static final class Holder implements InventoryHolder {
        Inventory inventory;
        final Map<Integer, String> commands = new HashMap<>();
        @Override public Inventory getInventory() { return inventory; }
    }
}
