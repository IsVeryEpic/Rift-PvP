package dev.riftpvp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;

/** VIP-only, per-player cosmetic choices. No attributes or combat stats are altered. */
final class VipTrims implements Listener {
   private static final List<String> PATTERNS = List.of(
      "sentry", "dune", "coast", "wild", "ward", "eye", "vex", "tide", "snout",
      "rib", "spire", "wayfinder", "shaper", "silence", "raiser", "host", "flow", "bolt");
   private static final List<String> MATERIALS = List.of(
      "amethyst", "copper", "diamond", "emerald", "gold", "iron", "lapis",
      "netherite", "quartz", "redstone", "resin");
   private final RiftPvpPlugin plugin;
   private final File directory;
   private final Map<UUID, Session> open = new HashMap<>();

   VipTrims(RiftPvpPlugin plugin) {
      this.plugin = plugin;
      this.directory = new File(plugin.getDataFolder(), "vip-trims");
      if (!directory.isDirectory() && !directory.mkdirs()) {
         plugin.getLogger().warning("Could not create VIP trims data folder.");
      }
   }

   void open(Player player, String kit) {
      if (!plugin.ranks().canEditTrims(player)) {
         player.sendMessage(RiftPvpPlugin.colors("&cVIP rank required to edit armour trims."));
         return;
      }
      if (!plugin.kits().hasKit(kit)) {
         plugin.message(player, "kit-not-loaded");
         return;
      }
      YamlConfiguration data = load(player.getUniqueId());
      String path = "kits." + kit + ".";
      Holder holder = new Holder();
      holder.inventory = Bukkit.createInventory(holder, 54, RiftPvpPlugin.colors("&5VIP Armour Trims &8| &f" + kit));
      Session session = new Session(kit, holder,
         data.getString(path + "pattern", "sentry"),
         data.getString(path + "material", "amethyst"));
      open.put(player.getUniqueId(), session);
      draw(session);
      player.openInventory(holder.inventory);
      player.sendMessage(RiftPvpPlugin.colors("&bVIP Trims &7» &fChoose a pattern and material, then click &aSave&f."));
   }

   void apply(Player player, String kit) {
      if (!plugin.ranks().canEditTrims(player)) return;
      YamlConfiguration data = load(player.getUniqueId());
      String path = "kits." + kit + ".";
      if (!data.contains(path + "pattern") || !data.contains(path + "material")) return;
      TrimPattern pattern = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft(data.getString(path + "pattern", "sentry")));
      TrimMaterial material = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft(data.getString(path + "material", "amethyst")));
      if (pattern == null || material == null) return;
      ItemStack[] armour = player.getInventory().getArmorContents();
      for (ItemStack item : armour) {
         if (item == null || !(item.getItemMeta() instanceof ArmorMeta meta)) continue;
         meta.setTrim(new ArmorTrim(material, pattern));
         item.setItemMeta(meta);
      }
      player.getInventory().setArmorContents(armour);
   }

   private void draw(Session session) {
      Inventory inv = session.holder.inventory;
      inv.clear();
      for (int i = 0; i < PATTERNS.size(); i++) {
         String id = PATTERNS.get(i);
         Material icon = Material.matchMaterial(id.toUpperCase() + "_ARMOR_TRIM_SMITHING_TEMPLATE");
         if (icon == null) icon = Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE;
         inv.setItem(i, item(icon, (id.equals(session.pattern) ? "&a✔ " : "&7") + title(id) + " Pattern"));
      }
      for (int i = 0; i < MATERIALS.size(); i++) {
         String id = MATERIALS.get(i);
         Material icon = switch (id) {
            case "amethyst" -> Material.AMETHYST_SHARD;
            case "copper" -> Material.COPPER_INGOT;
            case "diamond" -> Material.DIAMOND;
            case "emerald" -> Material.EMERALD;
            case "gold" -> Material.GOLD_INGOT;
            case "iron" -> Material.IRON_INGOT;
            case "lapis" -> Material.LAPIS_LAZULI;
            case "netherite" -> Material.NETHERITE_INGOT;
            case "quartz" -> Material.QUARTZ;
            case "redstone" -> Material.REDSTONE;
            default -> Material.RESIN_BRICK;
         };
         inv.setItem(27 + i, item(icon, (id.equals(session.material) ? "&a✔ " : "&7") + title(id) + " Material"));
      }
      inv.setItem(45, item(Material.EMERALD_BLOCK, "&a&lSave Cosmetic Trim"));
      inv.setItem(49, item(Material.BARRIER, "&cReset to Server Default"));
      inv.setItem(53, item(Material.ARROW, "&fBack to Kit Editor"));
   }

   private static String title(String s) {
      return Character.toUpperCase(s.charAt(0)) + s.substring(1);
   }

   private static ItemStack item(Material material, String name) {
      ItemStack item = new ItemStack(material);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(RiftPvpPlugin.colors(name));
         item.setItemMeta(meta);
      }
      return item;
   }

   @EventHandler
   public void click(InventoryClickEvent event) {
      if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
      event.setCancelled(true);
      if (!(event.getWhoClicked() instanceof Player player)) return;
      Session session = open.get(player.getUniqueId());
      if (session == null || session.holder != holder || event.getClickedInventory() != holder.inventory) return;
      if (!plugin.ranks().canEditTrims(player)) {
         player.closeInventory();
         return;
      }
      int slot = event.getRawSlot();
      if (slot >= 0 && slot < PATTERNS.size()) session.pattern = PATTERNS.get(slot);
      else if (slot >= 27 && slot < 27 + MATERIALS.size()) session.material = MATERIALS.get(slot - 27);
      else if (slot == 45 || slot == 49) {
         YamlConfiguration data = load(player.getUniqueId());
         String path = "kits." + session.kit;
         if (slot == 49) data.set(path, null);
         else {
            data.set(path + ".pattern", session.pattern);
            data.set(path + ".material", session.material);
         }
         try {
            data.save(file(player.getUniqueId()));
            player.sendMessage(RiftPvpPlugin.colors(slot == 49 ? "&aArmour trims reset!" : "&aVIP armour trims saved!"));
            player.closeInventory();
         } catch (IOException error) {
            player.sendMessage(RiftPvpPlugin.colors("&cUnable to save trims: " + error.getMessage()));
         }
         return;
      } else if (slot == 53) {
         open.remove(player.getUniqueId());
         Bukkit.getScheduler().runTask(plugin, () -> plugin.layouts().open(player, session.kit));
         return;
      }
      draw(session);
   }

   @EventHandler
   public void drag(InventoryDragEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
   }

   @EventHandler
   public void quit(PlayerQuitEvent event) {
      open.remove(event.getPlayer().getUniqueId());
   }

   private File file(UUID uuid) { return new File(directory, uuid + ".yml"); }
   private YamlConfiguration load(UUID uuid) { return YamlConfiguration.loadConfiguration(file(uuid)); }

   private static final class Holder implements InventoryHolder {
      private Inventory inventory;
      public Inventory getInventory() { return inventory; }
   }

   private static final class Session {
      private final String kit;
      private final Holder holder;
      private String pattern;
      private String material;
      private Session(String kit, Holder holder, String pattern, String material) {
         this.kit = kit;
         this.holder = holder;
         this.pattern = pattern;
         this.material = material;
      }
   }
}
