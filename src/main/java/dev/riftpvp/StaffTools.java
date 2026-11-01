package dev.riftpvp;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Rank-scoped moderation. Admin/Founder capabilities require actual vanilla OP status. */
final class StaffTools implements Listener {
    private static final String TITLE = "§5§lRiftPvP §8| §dStaff Tools";
    private static final String LEGACY_ITEM_NAME = "§d§lStaff Panel §7(Right-click)";
    private final RiftPvpPlugin plugin;
    private final File banFile;
    private YamlConfiguration bans;

    StaffTools(RiftPvpPlugin plugin) {
        this.plugin = plugin;
        this.banFile = new File(plugin.getDataFolder(), "temporary-bans.yml");
        this.bans = YamlConfiguration.loadConfiguration(banFile);
    }

    /** 0=member, 1=moderator, 2=OP admin, 3=OP Founder. */
    private int level(Player player) {
        String rank = plugin.ranks().rankId(player.getUniqueId());
        if (player.isOp()) return rank.equals("founder") ? 3 : 2;
        return rank.equals("moderator") ? 1 : 0;
    }

    private boolean staff(Player player) { return level(player) > 0; }

    boolean hasStaffAccess(Player player) { return staff(player); }

    void command(CommandSender sender, String command, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§eUse an in-game staff account to open the panel; moderation commands require a player.");
            return;
        }
        int access = level(player);
        if (access == 0) { player.sendMessage("§cYou are not staff."); return; }
        if (command.equals("staff") && args.length == 0) { open(player); return; }
        String operation = command.equals("staff") ? args[0].toLowerCase(Locale.ROOT) : command;
        String[] params = command.equals("staff") ? Arrays.copyOfRange(args, 1, args.length) : args;
        switch (operation) {
            case "kick", "riftkick" -> kick(player, params);
            case "tempban", "rtempban" -> tempban(player, params);
            case "unban", "rtunban" -> {
                if (access < 2) { player.sendMessage("§cOnly OP admins can unban."); return; }
                unban(player, params);
            }
            case "help" -> {
                player.sendMessage("§d/staff §7- Staff menu, §d/riftkick <player> <reason>");
                player.sendMessage("§d/rtempban <player> <30m|12h|7d|30d> <reason>");
                if (access >= 2) player.sendMessage("§d/rtunban <player> §7- Remove temporary ban");
            }
            default -> player.sendMessage("§cUnknown staff action. Use /staff help.");
        }
    }

    private boolean mayModerate(Player actor, Player victim) {
        if (actor.equals(victim) || victim.isOp()) return false;
        String targetRank = plugin.ranks().rankId(victim.getUniqueId());
        // Protect assigned admins even when they are temporarily de-opped.
        if (SetOfStaff.isAdministrator(targetRank)) return false;
        // OP admins may moderate moderators; moderators may only moderate
        // ordinary members and VIPs. No action may target an equal/higher rank.
        if ("moderator".equals(targetRank)) return level(actor) >= 2;
        return level(actor) >= 1;
    }

    private void kick(Player actor, String[] args) {
        if (args.length < 2) { actor.sendMessage("§cUsage: /riftkick <online-player> <reason>"); return; }
        Player victim = Bukkit.getPlayerExact(args[0]);
        if (victim == null) { actor.sendMessage("§cPlayer not online."); return; }
        if (!mayModerate(actor, victim)) { actor.sendMessage("§cYou cannot moderate yourself or staff members."); return; }
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (reason.length() > 160) reason = reason.substring(0, 160);
        plugin.staffAudit().log(actor, "kick", victim.getName() + " reason=" + reason);
        plugin.discord().send("staff-actions", actor.getName() + " kicked " + victim.getName() + " reason=" + reason);
        victim.kick(Component.text("Kicked from RiftPvP\nReason: " + reason, NamedTextColor.RED));
        actor.sendMessage("§aPlayer kicked.");
    }

    private void tempban(Player actor, String[] args) {
        if (args.length < 3) { actor.sendMessage("§cUsage: /rtempban <player> <30m|12h|7d|30d> <reason>"); return; }
        long millis = duration(args[1]);
        long max = level(actor) == 1 ? Duration.ofDays(30).toMillis() : Duration.ofDays(3650).toMillis();
        if (millis < Duration.ofMinutes(1).toMillis() || millis > max) {
            actor.sendMessage("§cInvalid duration. Moderators: maximum 30 days."); return;
        }
        OfflinePlayer victim = Bukkit.getOfflinePlayer(args[0]);
        if (!victim.hasPlayedBefore() && !victim.isOnline()) { actor.sendMessage("§cPlayer not found in server history."); return; }
        Player online = victim.getPlayer();
        String victimRank = plugin.ranks().rankId(victim.getUniqueId());
        if (actor.getUniqueId().equals(victim.getUniqueId())
            || (online != null && !mayModerate(actor, online))
            || (online == null && (SetOfStaff.isAdministrator(victimRank)
                || level(actor) < 2 && "moderator".equals(victimRank)))) {
            actor.sendMessage("§cYou cannot ban yourself or staff with equal/higher access."); return;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
        if (reason.length() > 160) reason = reason.substring(0, 160);
        if (reason.isBlank()) { actor.sendMessage("§cYou must enter a reason."); return; }
        String base = "bans." + victim.getUniqueId();
        bans.set(base + ".name", victim.getName() == null ? args[0] : victim.getName());
        bans.set(base + ".moderator", actor.getUniqueId().toString());
        bans.set(base + ".moderator-name", actor.getName());
        bans.set(base + ".reason", reason);
        bans.set(base + ".until", System.currentTimeMillis() + millis);
        if (!save()) { actor.sendMessage("§cFailed to save ban. No kick performed."); return; }
        plugin.staffAudit().log(actor, "tempban", args[0] + " for " + args[1] + " reason=" + reason);
        plugin.discord().send("staff-actions", actor.getName() + " tempbanned " + args[0] + " for " + args[1]);
        if (online != null) online.kick(Component.text(banMessage(victim.getUniqueId()), NamedTextColor.RED));
        actor.sendMessage("§aTemporary ban saved for " + args[0] + ".");
    }

    private void unban(Player actor, String[] args) {
        if (args.length != 1) { actor.sendMessage("§cUsage: /rtunban <player>"); return; }
        OfflinePlayer victim = Bukkit.getOfflinePlayer(args[0]);
        String base = "bans." + victim.getUniqueId();
        if (!bans.contains(base)) { actor.sendMessage("§cNo temporary ban found."); return; }
        bans.set(base, null);
        if (save()) {
            plugin.staffAudit().log(actor, "unban", args[0]);
            plugin.discord().send("staff-actions", actor.getName() + " unbanned " + args[0]);
            actor.sendMessage("§aTemporary ban removed.");
        }
        else {
            // Preserve the live ban on disk write failures, rather than
            // silently allowing a player to reconnect until next restart.
            bans = YamlConfiguration.loadConfiguration(banFile);
            actor.sendMessage("§cFailed to write ban file; the temporary ban is unchanged.");
        }
    }

    private long duration(String text) {
        if (!text.matches("[1-9][0-9]{0,4}[mhd]")) return -1;
        try {
            long n = Long.parseLong(text.substring(0, text.length() - 1));
            return Math.multiplyExact(n, switch (text.charAt(text.length() - 1)) {
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L;
                default -> 0L;
            });
        } catch (ArithmeticException e) { return -1; }
    }

    private String banMessage(UUID id) {
        String base = "bans." + id;
        long until = bans.getLong(base + ".until");
        long remainingMinutes = Math.max(1, (until - System.currentTimeMillis() + 59_999L) / 60_000L);
        return "Temporarily banned from RiftPvP\nReason: " + bans.getString(base + ".reason", "No reason")
            + "\nRemaining: ~" + remainingMinutes + " minutes";
    }

    private boolean save() {
        try { bans.save(banFile); return true; }
        catch (IOException e) { plugin.getLogger().severe("Failed to save temporary bans: " + e.getMessage()); return false; }
    }

    @EventHandler public void onLogin(PlayerLoginEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        String base = "bans." + id;
        if (!bans.contains(base)) return;
        if (bans.getLong(base + ".until") <= System.currentTimeMillis()) { bans.set(base, null); save(); return; }
        event.disallow(PlayerLoginEvent.Result.KICK_BANNED, banMessage(id));
    }

    /** Display only currently active bans, including offline players. */
    java.util.List<String> activeBanNames() {
        java.util.List<String> result = new java.util.ArrayList<>();
        org.bukkit.configuration.ConfigurationSection section = bans.getConfigurationSection("bans");
        if (section != null) for (String id : section.getKeys(false)) {
            String base = "bans." + id;
            if (bans.getLong(base + ".until") > System.currentTimeMillis()) {
                String name = bans.getString(base + ".name", "");
                if (name.matches("[A-Za-z0-9_]{3,16}")) result.add(name);
            }
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    void open(Player player) {
        if (!staff(player)) return;
        StaffHolder holder = new StaffHolder();
        Inventory gui = Bukkit.createInventory(holder, 27, TITLE);
        holder.inventory = gui;
        gui.setItem(10, item(Material.IRON_SWORD, "§cKick Player", "§7Select a player via command"));
        gui.setItem(12, item(Material.CLOCK, "§dTemporary Ban", "§7Duration and reason required"));
        gui.setItem(14, level(player) >= 2
            ? item(Material.BOOK, "§bInspect Match Evidence", "§7OP-only full evidence access")
            : item(Material.BOOK, "§bView Match Info", "§7Public match summary (no staff evidence)"));
        if (level(player) >= 2) {
            gui.setItem(16, item(Material.BARRIER, "§6Remove Temp Ban", "§7OP only"));
            gui.setItem(18, item(Material.COMPARATOR, "§dServer Feature Toggles", "§7OP only"));
        }
        player.openInventory(gui);
    }

    private ItemStack item(Material type, String name, String lore) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(java.util.List.of(lore));
        item.setItemMeta(meta);
        return item;
    }

    void giveHotbarItem(Player player) {
        // One-time migration from the old unsigned staff star in hotbar slot 9.
        // The new owned star occupies slot 7 and cannot conflict with VIP rockets.
        ItemStack old = player.getInventory().getItem(8);
        if (old != null && old.getType() == Material.NETHER_STAR && old.hasItemMeta()
                && LEGACY_ITEM_NAME.equals(old.getItemMeta().getDisplayName())
                && old.getItemMeta().hasLore()
                && old.getItemMeta().getLore().contains("§7Open with right-click or /staff")) {
            player.getInventory().setItem(8, null);
        }
        plugin.lobbyHotbar().refresh(player);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (event.getPlayer().isOnline()) giveHotbarItem(event.getPlayer()); }, 20L);
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof StaffHolder) event.setCancelled(true);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().getTopInventory().getHolder() instanceof StaffHolder) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (!staff(player) || slot < 0 || slot >= 27) return;
            player.closeInventory();
            if (slot == 18 && level(player) >= 2) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && player.isOp()) plugin.adminFeatures().open(player);
                });
                return;
            }
            String command = switch (slot) {
                case 10 -> "/riftkick <player> <reason>";
                case 12 -> "/rtempban <player> <7d> <reason>";
                case 14 -> level(player) >= 2 ? "/match inspect <id>" : "/matchinfo <id>";
                case 16 -> level(player) >= 2 ? "/rtunban <player>" : null;
                case 18 -> null;
                default -> null;
            };
            if (command != null) player.sendMessage(Component.text("Click to prepare: " + command, NamedTextColor.LIGHT_PURPLE)
                .clickEvent(ClickEvent.suggestCommand(command)));
        }
    }

    void close() { save(); }

    private static final class StaffHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class SetOfStaff {
        static boolean isAdministrator(String rank) {
            return rank.equals("admin") || rank.equals("owner") || rank.equals("founder");
        }
    }
}
