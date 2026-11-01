package dev.riftpvp;

import java.util.Arrays;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Lobby-only, owned menu shortcuts. Never overwrites player items or grants permissions. */
final class LobbyHotbar implements Listener {
    private record Shortcut(String id, int slot, Material material, String name, String permission) {}
    // The first five items are the primary PvP shortcuts. Everything else
    // stays in the player inventory and opens on a normal inventory click.
    private static final List<Shortcut> SHORTCUTS = List.of(
        new Shortcut("menu", 0, Material.HEART_OF_THE_SEA, "§d§lRift Menu", "riftpvp.queue"),
        new Shortcut("queue", 1, Material.DIAMOND_SWORD, "§b§lRTP Queue", "riftpvp.queue"),
        new Shortcut("ranked", 2, Material.NETHERITE_SWORD, "§c§lRanked", "riftpvp.ranked"),
        new Shortcut("editor", 3, Material.SMITHING_TABLE, "§a§lKit Editor", "riftpvp.kiteditor"),
        new Shortcut("rtp", 4, Material.AMETHYST_SHARD, "§e§lRandom TP", "riftpvp.rtp"),
        new Shortcut("leaderboard", 9, Material.BOOK, "§6§lLeaderboards", "riftpvp.leaderboard"),
        new Shortcut("profile", 10, Material.PLAYER_HEAD, "§b§lMy Profile", "riftpvp.profile"),
        new Shortcut("settings", 11, Material.REPEATER, "§f§lPlayer Settings", "riftpvp.settings"),
        new Shortcut("visibility", 12, Material.SPYGLASS, "§d§lVisibility Settings", "riftpvp.visibility"),
        new Shortcut("customkit", 13, Material.CHEST, "§d§lCustom Kits", "riftpvp.customkit"),
        new Shortcut("tutorial", 14, Material.KNOWLEDGE_BOOK, "§e§lTutorial", "riftpvp.tutorial"),
        new Shortcut("staff", 15, Material.NETHER_STAR, "§d§lStaff Tools", "riftpvp.staff"),
        new Shortcut("admin", 16, Material.COMPARATOR, "§c§lAdmin Panel", "riftpvp.admin")
    );
    private final RiftPvpPlugin plugin;
    private final NamespacedKey ownedKey;

    LobbyHotbar(RiftPvpPlugin plugin) {
        this.plugin = plugin;
        this.ownedKey = new NamespacedKey(plugin, "lobby_hotbar_shortcut");
    }

    private String shortcutId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(ownedKey, PersistentDataType.STRING);
    }

    private boolean ours(ItemStack item) { return shortcutId(item) != null; }

    private boolean entitled(Player player, Shortcut shortcut) {
        if (shortcut.id().equals("admin")) return player.isOp();
        if (shortcut.id().equals("staff")) return plugin.staffTools().hasStaffAccess(player);
        if (shortcut.id().equals("tutorial") || shortcut.id().equals("profile") || shortcut.id().equals("visibility")
                || shortcut.id().equals("customkit")) return true;
        return plugin.ranks().hasPluginPermission(player, shortcut.permission());
    }

    private ItemStack make(Shortcut shortcut) {
        ItemStack item = new ItemStack(shortcut.material());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(shortcut.name());
        meta.setLore(Arrays.asList(shortcut.slot() < 9 ? "§7Right-click to open" : "§7Open inventory and click to open", "§8Lobby use only"));
        meta.getPersistentDataContainer().set(ownedKey, PersistentDataType.STRING, shortcut.id());
        item.setItemMeta(meta);
        return item;
    }

    void refresh(Player player) {
        if (!player.isOnline()) return;
        if (plugin.matchReconnect().pending(player.getUniqueId())) return;
        if (!plugin.getConfig().getBoolean("lobby.hotbar-enabled", true)
                || plugin.adminFeatures().hidesLobbyItems(player)
                || !plugin.inLobbyItems(player.getLocation())) { remove(player); return; }
        // The Kit Room uses a temporary inventory; do not change it mid-edit.
        if (plugin.kitRoom().editing(player)) return;
        PlayerInventory inventory = player.getInventory();
        // Do not keep duplicate shortcuts, misplaced shortcuts, or staff tools
        // after a rank/OP change. This is the plugin's OWN marked inventory only.
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!ours(stack)) continue;
            Shortcut matching = SHORTCUTS.stream().filter(s -> s.id().equals(shortcutId(stack))).findFirst().orElse(null);
            if (matching == null || matching.slot() != i || stack.getType() != matching.material() || !entitled(player, matching)) inventory.setItem(i, null);
        }
        for (Shortcut shortcut : SHORTCUTS) {
            if (!entitled(player, shortcut)) continue;
            ItemStack existing = inventory.getItem(shortcut.slot());
            if (existing == null || existing.getType().isAir()) inventory.setItem(shortcut.slot(), make(shortcut));
        }
    }

    void tick() { for (Player player : Bukkit.getOnlinePlayers()) refresh(player); }

    void remove(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < 36; i++) if (ours(inventory.getItem(i))) inventory.setItem(i, null);
        if (ours(inventory.getItemInOffHand())) inventory.setItemInOffHand(null);
        if (ours(player.getItemOnCursor())) player.setItemOnCursor(null);
    }

    private void activate(Player player, String id) {
        if (!player.isOnline() || !plugin.inLobbyItems(player.getLocation()) || plugin.adminFeatures().hidesLobbyItems(player)) return;
        Shortcut shortcut = SHORTCUTS.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
        if (shortcut == null || !entitled(player, shortcut)) return;
        switch (id) {
            case "menu" -> plugin.riftMenu().open(player);
            case "queue" -> plugin.menus().openQueue(player);
            case "ranked" -> plugin.menus().openRanked(player);
            case "editor" -> plugin.menus().openEditorSelection(player);
            case "rtp" -> plugin.menus().openRtp(player);
            case "leaderboard" -> plugin.menus().openLeaderboard(player);
            case "profile" -> player.performCommand("profile");
            case "settings", "visibility" -> plugin.playerPreferences().open(player);
            case "customkit" -> player.performCommand("customkit");
            case "tutorial" -> player.performCommand("tutorial");
            case "staff" -> plugin.staffTools().open(player);
            case "admin" -> plugin.adminPanel().open(player);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        String id = shortcutId(event.getItem());
        if (id == null) return;
        event.setCancelled(true);
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Bukkit.getScheduler().runTask(plugin, () -> activate(event.getPlayer(), id));
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String id = shortcutId(event.getCurrentItem());
        boolean hotbarSwap = event.getHotbarButton() >= 0 && event.getHotbarButton() < 9
            && ours(player.getInventory().getItem(event.getHotbarButton()));
        if (id != null || ours(event.getCursor()) || hotbarSwap
            || (event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
                && ours(player.getInventory().getItemInOffHand()))) event.setCancelled(true);
        // Only the player's own inventory screen activates the quick-access items;
        // never launch other menus while a Kit Room or chest GUI is editing items.
        if (id != null && event.getClickedInventory() == player.getInventory()
                && event.getView().getTopInventory().getType() == InventoryType.CRAFTING) {
            Bukkit.getScheduler().runTask(plugin, () -> activate(player, id));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void onDrag(InventoryDragEvent event) {
        if (ours(event.getOldCursor())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void onSwap(PlayerSwapHandItemsEvent event) {
        if (ours(event.getMainHandItem()) || ours(event.getOffHandItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void onDrop(PlayerDropItemEvent event) {
        if (ours(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::ours);
    }

    private void soon(Player player) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (player.isOnline()) refresh(player); }, 2L);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { soon(event.getPlayer()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) { soon(event.getPlayer()); }
    @EventHandler public void onChangedWorld(PlayerChangedWorldEvent event) { soon(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!plugin.inLobbyItems(event.getTo())) remove(event.getPlayer());
        else soon(event.getPlayer());
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { remove(event.getPlayer()); }
}
