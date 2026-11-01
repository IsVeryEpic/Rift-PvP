package dev.riftpvp;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Persistent, privacy-conscious client preferences. No OP needed. */
final class PlayerPreferences implements Listener {
   private final RiftPvpPlugin plugin;
   private final File file;
   private final YamlConfiguration settings;
   private static final List<String> OPTIONS = List.of("tpa", "duels", "parties", "sounds", "rematches", "queue-notifications");
   private static final int[] SLOTS = {10, 12, 14, 16, 19, 21};
   private static final Material[] ICONS = {Material.ENDER_PEARL, Material.DIAMOND_SWORD, Material.TOTEM_OF_UNDYING,
      Material.NOTE_BLOCK, Material.NETHER_STAR, Material.COMPASS};
   private static final String[] NAMES = {"Teleport Requests", "Duel Requests", "Party Invitations", "RiftPvP Sounds",
      "Rematch Invitations", "Queue Notifications"};

   PlayerPreferences(RiftPvpPlugin plugin) {
      this.plugin = plugin;
      this.file = new File(plugin.getDataFolder(), "player-preferences.yml");
      this.settings = YamlConfiguration.loadConfiguration(file);
   }

   boolean allows(Player player, String option) {
      if (!OPTIONS.contains(option)) return true;
      return settings.getBoolean("players." + player.getUniqueId() + "." + option, true);
   }

   int maxOpponentPing(Player player) {
      return Math.max(0, Math.min(999, settings.getInt("players." + player.getUniqueId() + ".max-opponent-ping", 0)));
   }

   boolean pingCompatible(Player a, Player b) {
      int aMax = maxOpponentPing(a), bMax = maxOpponentPing(b);
      return (aMax == 0 || b.getPing() <= aMax) && (bMax == 0 || a.getPing() <= bMax);
   }

   void queueCommand(Player player, String[] args) {
      if (args.length == 0) {
         player.sendMessage("§dQueue preferences §7| Max opponent ping: §f" + (maxOpponentPing(player) == 0 ? "Any" : maxOpponentPing(player) + "ms"));
         player.sendMessage("§7/queueprefs ping <any|50|100|150|200|300> §7| /settings for notifications/rematches");
         return;
      }
      if (args.length != 2 || !args[0].equalsIgnoreCase("ping")
            || !(args[1].equalsIgnoreCase("any") || List.of("50", "100", "150", "200", "300").contains(args[1]))) {
         player.sendMessage("§cUsage: /queueprefs ping <any|50|100|150|200|300>"); return;
      }
      int limit = args[1].equalsIgnoreCase("any") ? 0 : Integer.parseInt(args[1]);
      String path = "players." + player.getUniqueId() + ".max-opponent-ping";
      int old = maxOpponentPing(player);
      settings.set(path, limit);
      try { settings.save(file); player.sendMessage("§aOpponent ping limit: §f" + (limit == 0 ? "Any" : limit + "ms")); }
      catch (IOException error) { settings.set(path, old); player.sendMessage("§cUnable to save preference."); }
   }

   void open(Player player) {
      Holder holder = new Holder();
      holder.inventory = Bukkit.createInventory(holder, 27, "§5§lRiftPvP §8| §fSettings");
      for (int i = 0; i < OPTIONS.size(); i++) {
         boolean on = allows(player, OPTIONS.get(i));
         ItemStack icon = new ItemStack(ICONS[i]);
         ItemMeta meta = icon.getItemMeta();
         if (meta != null) {
            meta.setDisplayName("§d" + NAMES[i] + " §7- " + (on ? "§aON" : "§cOFF"));
            meta.setLore(Arrays.asList("§7Click to toggle", "§8Saved for your account"));
            icon.setItemMeta(meta);
         }
         holder.inventory.setItem(SLOTS[i], icon);
      }
      ItemStack pingIcon = new ItemStack(Material.CLOCK);
      ItemMeta pingMeta = pingIcon.getItemMeta();
      if (pingMeta != null) {
         pingMeta.setDisplayName("§bOpponent Ping: §f" + (maxOpponentPing(player) == 0 ? "Any" : maxOpponentPing(player) + "ms"));
         pingMeta.setLore(List.of("§7Click to cycle the matchmaking limit.", "§8Higher ping opponents may be excluded."));
         pingIcon.setItemMeta(pingMeta);
      }
      holder.inventory.setItem(23, pingIcon);
      player.openInventory(holder.inventory);
   }

   @EventHandler public void onClick(InventoryClickEvent event) {
      if (!(event.getView().getTopInventory().getHolder() instanceof Holder)) return;
      event.setCancelled(true);
      if (!(event.getWhoClicked() instanceof Player player)) return;
      if (event.getClickedInventory() != event.getView().getTopInventory()) return;
      if (event.getRawSlot() == 23) {
         int[] values = {0, 50, 100, 150, 200, 300};
         int previous = maxOpponentPing(player), next = 0;
         for (int i = 0; i < values.length; i++) if (values[i] == previous) { next = values[(i + 1) % values.length]; break; }
         queueCommand(player, new String[]{"ping", next == 0 ? "any" : Integer.toString(next)});
         Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline()) open(player); });
         return;
      }
      for (int i = 0; i < SLOTS.length; i++) if (event.getRawSlot() == SLOTS[i]) {
         String path = "players." + player.getUniqueId() + "." + OPTIONS.get(i);
         boolean value = !allows(player, OPTIONS.get(i));
         settings.set(path, value);
         try {
            settings.save(file);
         } catch (IOException ex) {
            plugin.getLogger().warning("Cannot save player preferences: " + ex.getMessage());
            settings.set(path, !value);
            player.sendMessage("§cYour setting could not be saved.");
            return;
         }
         Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline()) open(player); });
         return;
      }
   }

   @EventHandler public void onDrag(InventoryDragEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
   }

   private static final class Holder implements InventoryHolder {
      private Inventory inventory;
      @Override public Inventory getInventory() { return inventory; }
   }
}
