package dev.riftpvp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.Map.Entry;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

final class KitManager implements Listener {
   private final RiftPvpPlugin plugin;
   private final Map<String, KitManager.KitSnapshot> kits = new HashMap<>();
   private final Map<String, ItemStack> customIcons = new HashMap<>();
   private final Map<UUID, String> equippedKit = new HashMap<>();
   private final File kitDir;
   private final File iconFile;

   KitManager(RiftPvpPlugin var1) {
      this.plugin = var1;
      this.kitDir = new File(var1.getDataFolder(), "kits");
      this.iconFile = new File(var1.getDataFolder(), "icons.yml");
      if (!this.kitDir.isDirectory() && !this.kitDir.mkdirs()) {
         var1.getLogger().warning("Could not create the kits directory.");
      }

      this.loadIcons();
      this.reload();
   }

   void reload() {
      this.kits.clear();

      for (String var2 : this.configuredIds()) {
         try {
            File var3 = new File(this.kitDir, var2 + ".yml");
            KitManager.KitSnapshot var4;
            if (var3.isFile()) {
               var4 = this.readSaved(var3);
            } else {
               var4 = this.importFromSource(var2);
               if (var4 != null) {
                  this.saveSnapshot(var2, var4);
               }
            }

            if (var4 != null) {
               this.kits.put(var2, var4);
            } else {
               this.plugin.getLogger().warning("No kit contents were found for " + var2);
            }
         } catch (Exception var5) {
            this.plugin.getLogger().warning("Kit '" + var2 + "' failed to load: " + var5.getMessage());
         }
      }
   }

   List<String> configuredIds() {
      ConfigurationSection var1 = this.plugin.getConfig().getConfigurationSection("kits");
      return var1 == null
         ? List.of()
         : var1.getKeys(false)
            .stream()
            .map(String::toLowerCase)
            .filter(KitManager::safeId)
            .filter(var1x -> this.plugin.getConfig().getBoolean("kits." + var1x + ".enabled", true))
            .sorted(Comparator.comparingInt(var1x -> this.plugin.getConfig().getInt("kits." + var1x + ".slot", 10)))
            .toList();
   }

   /** Soft-delete: hides kit from menus, queues and equipping without discarding saved data.
    * The explicit disabled setting also suppresses bundled default kits after reload.
    * An operator can recover the old kit using /kitrestore. */
   boolean archiveKit(String id) {
      if (!safeId(id) || !hasKit(id)) return false;
      plugin.getConfig().set("kits." + id + ".enabled", false);
      plugin.saveConfig();
      kits.remove(id);
      equippedKit.entrySet().removeIf(entry -> id.equals(entry.getValue()));
      return true;
   }

   boolean restoreArchivedKit(String id) {
      if (!safeId(id) || hasKit(id) || !plugin.getConfig().contains("kits." + id)
          || plugin.getConfig().getBoolean("kits." + id + ".enabled", true)) return false;
      plugin.getConfig().set("kits." + id + ".enabled", true);
      plugin.saveConfig();
      reload();
      if (hasKit(id)) return true;
      plugin.getConfig().set("kits." + id + ".enabled", false);
      plugin.saveConfig();
      reload();
      return false;
   }

   boolean hasKit(String var1) {
      return this.kits.containsKey(var1);
   }

   String name(String var1) {
      return this.plugin.getConfig().getString("kits." + var1 + ".name", var1);
   }

   int slot(String var1) {
      return this.plugin.getConfig().getInt("kits." + var1 + ".slot", 10);
   }

   ItemStack icon(String var1) {
      ItemStack var2 = this.customIcons.get(var1);
      if (var2 != null) {
         return var2.clone();
      }

      String var3 = this.plugin.getConfig().getString("kits." + var1 + ".icon", "CHEST");
      Material var4 = Material.matchMaterial(var3 == null ? "CHEST" : var3);
      if (var4 == null || !var4.isItem()) {
         var4 = Material.CHEST;
      }

      ItemStack var5 = new ItemStack(var4);
      ItemMeta var6 = var5.getItemMeta();
      if (var6 != null) {
         var6.setEnchantmentGlintOverride(this.plugin.getConfig().getBoolean("kits." + var1 + ".glow", false));
         if (var6 instanceof PotionMeta var7) {
            String var8 = this.plugin.getConfig().getString("kits." + var1 + ".potion", "");
            if (var8 != null && !var8.isBlank()) {
               try {
                  String var9 = var8.toUpperCase(Locale.ROOT);
                  if (var9.equals("INSTANT_HEAL") || var9.equals("INSTANT_HEALTH")) {
                     var9 = "HEALING";
                  }

                  var7.setBasePotionType(PotionType.valueOf(var9));
               } catch (IllegalArgumentException var10) {
                  this.plugin.getLogger().warning("Unknown potion type in kit icon for " + var1 + ": " + var8);
               }
            }
         }

         var5.setItemMeta(var6);
      }

      return var5;
   }

   boolean equip(Player var1, String var2) {
      KitManager.KitSnapshot var3 = this.kits.get(var2);
      if (var3 == null) {
         return false;
      }

      ItemStack[] var4 = this.plugin.kitRoom().apply(var1.getUniqueId(), var2, this.plugin.layouts().apply(var1.getUniqueId(), var2, slots(var3)));
      if (this.isXpBottleCapEnabled(var2)) limitXpBottles(var4, 64);
      if (this.isBuiltInKit(var2)) stripArmorTrims(var4);
      PlayerInventory var5 = var1.getInventory();
      var5.clear();
      var5.setStorageContents(Arrays.copyOf(var4, 36));
      var5.setHelmet(cloneOrNull(var4[36]));
      var5.setChestplate(cloneOrNull(var4[37]));
      var5.setLeggings(cloneOrNull(var4[38]));
      var5.setBoots(cloneOrNull(var4[39]));
      var5.setItemInOffHand(cloneOrNull(var4[40]));
      this.plugin.trims().apply(var1, var2);
      this.equippedKit.put(var1.getUniqueId(), var2);
      var1.closeInventory();
      this.plugin.heal(var1);
      var1.updateInventory();
      return true;
   }

   private boolean isBuiltInKit(String kit) {
      try (InputStream resource = this.plugin.getResource("kit-imports/" + kit + ".yml")) {
         return resource != null;
      } catch (IOException error) {
         this.plugin.getLogger().warning("Could not inspect built-in kit '" + kit + "': " + error.getMessage());
         return false;
      }
   }

   private static void stripArmorTrims(ItemStack[] slots) {
      for (int i = 36; i < 40; i++) {
         ItemStack item = slots[i];
         if (item != null && item.getItemMeta() instanceof ArmorMeta meta && meta.hasTrim()) {
            meta.setTrim(null);
            item.setItemMeta(meta);
         }
      }
   }

   boolean isKitRoomEnabled(String kit) {
      return plugin.getConfig().getBoolean("kits." + kit + ".kit-room-enabled",
         "htk".equalsIgnoreCase(kit) || "endgame".equalsIgnoreCase(kit));
   }

   void setKitRoomEnabled(String kit, boolean enabled) {
      if (!hasKit(kit)) throw new IllegalArgumentException("Unknown kit");
      plugin.getConfig().set("kits." + kit + ".kit-room-enabled", enabled);
      plugin.saveConfig();
   }

   String equippedKitId(Player player) { return equippedKit.get(player.getUniqueId()); }

   boolean isXpBottleCapEnabled(String kit) {
      // Endgame is intentionally always exempt, including existing saved kits.
      return !"endgame".equalsIgnoreCase(kit)
         && this.plugin.getConfig().getBoolean("kits." + kit + ".xp-bottle-cap-enabled", true);
   }

   void setXpBottleCap(String kit, boolean enabled) {
      this.plugin.getConfig().set("kits." + kit + ".xp-bottle-cap-enabled", !"endgame".equalsIgnoreCase(kit) && enabled);
      this.plugin.saveConfig();
   }

   static int countXpBottles(ItemStack[] slots) {
      int count = 0;
      for (int i = 0; i < slots.length; i++) {
         if (slots[i] != null && slots[i].getType() == Material.EXPERIENCE_BOTTLE) count += slots[i].getAmount();
      }
      return count;
   }

   /** Mutates only XP bottle stacks, keeping the hotbar first and never rearranging other items. */
   static void limitXpBottles(ItemStack[] slots, int maximum) {
      int remaining = maximum;
      for (int i = 0; i < slots.length; i++) {
         ItemStack item = slots[i];
         if (item == null || item.getType() != Material.EXPERIENCE_BOTTLE) continue;
         if (remaining <= 0) {
            slots[i] = null;
            continue;
         }
         int kept = Math.min(item.getAmount(), remaining);
         if (kept != item.getAmount()) {
            item = item.clone();
            item.setAmount(kept);
            slots[i] = item;
         }
         remaining -= kept;
      }
   }

   void enforceEquippedXpLimits() {
      for (Map.Entry<UUID, String> entry : new HashMap<>(equippedKit).entrySet()) {
         Player player = Bukkit.getPlayer(entry.getKey());
         if (player == null || !player.isOnline()) {
            equippedKit.remove(entry.getKey());
            continue;
         }
         if (!isXpBottleCapEnabled(entry.getValue())) continue;
         ItemStack[] slots = new ItemStack[41];
         ItemStack[] storage = player.getInventory().getStorageContents();
         for (int i = 0; i < 36; i++) slots[i] = storage[i] == null ? null : storage[i].clone();
         slots[40] = cloneOrNull(player.getInventory().getItemInOffHand());
         if (countXpBottles(slots) <= 64) continue;
         limitXpBottles(slots, 64);
         player.getInventory().setStorageContents(Arrays.copyOf(slots, 36));
         player.getInventory().setItemInOffHand(slots[40]);
      }
   }

   void clearEquippedKit(Player player) {
      equippedKit.remove(player.getUniqueId());
   }

   @EventHandler
   public void onDeath(PlayerDeathEvent event) {
      clearEquippedKit(event.getEntity());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      clearEquippedKit(event.getPlayer());
   }

   ItemStack[] originalSlots(String var1) {
      KitManager.KitSnapshot var2 = this.kits.get(var1);
      if (var2 == null) return null;
      ItemStack[] items = slots(var2);
      if (this.isBuiltInKit(var1)) stripArmorTrims(items);
      return items;
   }

   private static ItemStack[] slots(KitManager.KitSnapshot var0) {
      ItemStack[] var1 = new ItemStack[41];

      for (int var2 = 0; var2 < 36; var2++) {
         var1[var2] = cloneOrNull(var0.storage[var2]);
      }

      var1[36] = cloneOrNull(var0.helmet);
      var1[37] = cloneOrNull(var0.chest);
      var1[38] = cloneOrNull(var0.legs);
      var1[39] = cloneOrNull(var0.boots);
      var1[40] = cloneOrNull(var0.offhand);
      return var1;
   }

   boolean savePlayerKit(Player var1, String var2) throws IOException {
      if (!safeId(var2)) {
         return false;
      }

      PlayerInventory var3 = var1.getInventory();
      KitManager.KitSnapshot var4 = new KitManager.KitSnapshot(
         copy(var3.getStorageContents()),
         cloneOrNull(var3.getHelmet()),
         cloneOrNull(var3.getChestplate()),
         cloneOrNull(var3.getLeggings()),
         cloneOrNull(var3.getBoots()),
         cloneOrNull(var3.getItemInOffHand())
      );
      this.saveSnapshot(var2, var4);
      this.kits.put(var2, var4);
      this.registerNewKit(var2);
      return true;
   }

   boolean saveCreatedKit(String kit, ItemStack[] original, ItemStack icon, boolean limitXp) throws IOException {
      if (!safeId(kit) || original == null || original.length != 41 || this.kits.containsKey(kit)) return false;
      ItemStack[] items = copy(original);
      if (limitXp && !"endgame".equalsIgnoreCase(kit)) limitXpBottles(items, 64);
      KitManager.KitSnapshot snapshot = new KitManager.KitSnapshot(
         Arrays.copyOf(items, 36), cloneOrNull(items[36]), cloneOrNull(items[37]),
         cloneOrNull(items[38]), cloneOrNull(items[39]), cloneOrNull(items[40]));
      this.saveSnapshot(kit, snapshot);
      this.kits.put(kit, snapshot);
      this.registerNewKit(kit);
      this.setXpBottleCap(kit, limitXp);
      if (icon != null && !icon.getType().isAir()) {
         ItemStack display = icon.clone();
         display.setAmount(1);
         this.setIcon(kit, display);
      }
      return true;
   }

   boolean importKit(String var1) throws IOException {
      if (!safeId(var1)) {
         return false;
      }

      KitManager.KitSnapshot var2 = this.importFromSource(var1);
      if (var2 == null) {
         return false;
      }

      this.saveSnapshot(var1, var2);
      this.kits.put(var1, var2);
      this.registerNewKit(var1);
      return true;
   }

   void setIcon(String var1, ItemStack var2) throws IOException {
      if (safeId(var1) && this.kits.containsKey(var1) && var2 != null && !var2.getType().isAir()) {
         this.customIcons.put(var1, var2.clone());
         YamlConfiguration var3 = new YamlConfiguration();

         for (Entry var5 : this.customIcons.entrySet()) {
            var3.set("icons." + (String)var5.getKey(), encode((ItemStack)var5.getValue()));
         }

         atomicSave(var3, this.iconFile);
      } else {
         throw new IllegalArgumentException("Unknown kit or empty hand");
      }
   }

   private void registerNewKit(String var1) {
      if (!this.plugin.getConfig().contains("kits." + var1)) {
         this.plugin.getConfig().set("kits." + var1 + ".enabled", true);
         this.plugin.getConfig().set("kits." + var1 + ".name", var1);
         this.plugin.getConfig().set("kits." + var1 + ".icon", "CHEST");
         this.plugin.getConfig().set("kits." + var1 + ".glow", false);
         Set<String> var2 = this.plugin.getConfig().getConfigurationSection("kits").getKeys(false);
         HashSet var3 = new HashSet();

         for (String var5 : var2) {
            if (!var5.equals(var1)) {
               var3.add(this.plugin.getConfig().getInt("kits." + var5 + ".slot", -1));
            }
         }

         int var6 = -1;

         for (int var7 = 10; var7 < 54; var7++) {
            if (!var3.contains(var7)) {
               var6 = var7;
               break;
            }
         }

         if (var6 < 0) {
            var6 = 53;
         }

         this.plugin.getConfig().set("kits." + var1 + ".slot", var6);
         this.plugin.saveConfig();
      }
   }

   private void loadIcons() {
      if (this.iconFile.isFile()) {
         YamlConfiguration var1 = YamlConfiguration.loadConfiguration(this.iconFile);
         ConfigurationSection var2 = var1.getConfigurationSection("icons");
         if (var2 != null) {
            for (String var4 : var2.getKeys(false)) {
               try {
                  ItemStack var5 = decode(var2.getString(var4));
                  if (var5 != null) {
                     this.customIcons.put(var4, var5);
                  }
               } catch (Exception var6) {
                  this.plugin.getLogger().warning("Could not load custom icon for " + var4);
               }
            }
         }
      }
   }

   private KitManager.KitSnapshot importFromSource(String var1) throws IOException {
      File var2 = new File(this.plugin.getDataFolder(), "kit-imports/" + var1 + ".yml");
      File var3 = new File(this.plugin.getDataFolder().getParentFile(), "KitsCratesPlus/kits/" + var1 + ".yml");
      YamlConfiguration var4;
      if (var2.isFile()) {
         var4 = YamlConfiguration.loadConfiguration(var2);
      } else if (var3.isFile()) {
         var4 = YamlConfiguration.loadConfiguration(var3);
      } else {
         try (InputStream var5 = this.plugin.getResource("kit-imports/" + var1 + ".yml")) {
            if (var5 == null) {
               return null;
            }

            var4 = YamlConfiguration.loadConfiguration(new InputStreamReader(var5, StandardCharsets.UTF_8));
         }
      }

      ConfigurationSection var15 = var4.getConfigurationSection("inventoryLayout");
      if (var15 == null) {
         return null;
      }

      ItemStack[] var16 = new ItemStack[36];
      ConfigurationSection var7 = var15.getConfigurationSection("slotMap");
      if (var7 != null) {
         for (String var9 : var7.getKeys(false)) {
            int var10;
            try {
               var10 = Integer.parseInt(var9);
            } catch (NumberFormatException var13) {
               continue;
            }

            if (var10 >= 0 && var10 < var16.length) {
               var16[var10] = decodeGzip(var7.getString(var9 + ".item.b64"));
            }
         }
      }

      return new KitManager.KitSnapshot(
         var16,
         decodeGzip(var15.getString("armor.helmet.item.b64")),
         decodeGzip(var15.getString("armor.chest.item.b64")),
         decodeGzip(var15.getString("armor.legs.item.b64")),
         decodeGzip(var15.getString("armor.boots.item.b64")),
         decodeGzip(var15.getString("offhand.item.b64"))
      );
   }

   private KitManager.KitSnapshot readSaved(File var1) {
      YamlConfiguration var2 = YamlConfiguration.loadConfiguration(var1);
      ItemStack[] var3 = new ItemStack[36];

      for (int var4 = 0; var4 < var3.length; var4++) {
         var3[var4] = decode(var2.getString("storage." + var4));
      }

      return new KitManager.KitSnapshot(
         var3,
         decode(var2.getString("armor.helmet")),
         decode(var2.getString("armor.chest")),
         decode(var2.getString("armor.legs")),
         decode(var2.getString("armor.boots")),
         decode(var2.getString("offhand"))
      );
   }

   private void saveSnapshot(String var1, KitManager.KitSnapshot var2) throws IOException {
      YamlConfiguration var3 = new YamlConfiguration();
      var3.set("format", 1);

      for (int var4 = 0; var4 < var2.storage.length; var4++) {
         if (var2.storage[var4] != null && !var2.storage[var4].getType().isAir()) {
            var3.set("storage." + var4, encode(var2.storage[var4]));
         }
      }

      var3.set("armor.helmet", encode(var2.helmet));
      var3.set("armor.chest", encode(var2.chest));
      var3.set("armor.legs", encode(var2.legs));
      var3.set("armor.boots", encode(var2.boots));
      var3.set("offhand", encode(var2.offhand));
      atomicSave(var3, new File(this.kitDir, var1 + ".yml"));
   }

   private static void atomicSave(YamlConfiguration var0, File var1) throws IOException {
      File var2 = new File(var1.getParentFile(), var1.getName() + ".new");
      Files.writeString(var2.toPath(), var0.saveToString(), StandardCharsets.UTF_8);

      try {
         Files.move(var2.toPath(), var1.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException var4) {
         Files.move(var2.toPath(), var1.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }
   }

   private static ItemStack decodeGzip(String var0) {
      return var0 != null && !var0.isBlank() ? ItemStack.deserializeBytes(Base64.getDecoder().decode(var0)) : null;
   }

   private static ItemStack decode(String var0) {
      return var0 != null && !var0.isBlank() ? ItemStack.deserializeBytes(Base64.getDecoder().decode(var0)) : null;
   }

   private static String encode(ItemStack var0) {
      return var0 != null && !var0.getType().isAir() ? Base64.getEncoder().encodeToString(var0.serializeAsBytes()) : null;
   }

   private static ItemStack[] copy(ItemStack[] var0) {
      ItemStack[] var1 = new ItemStack[36];

      for (int var2 = 0; var2 < Math.min(36, var0.length); var2++) {
         var1[var2] = cloneOrNull(var0[var2]);
      }

      return var1;
   }

   private static ItemStack cloneOrNull(ItemStack var0) {
      return var0 != null && !var0.getType().isAir() ? var0.clone() : null;
   }

   static boolean safeId(String var0) {
      return var0 != null && var0.matches("[a-z0-9_-]{1,40}");
   }

   private record KitSnapshot(ItemStack[] storage, ItemStack helmet, ItemStack chest, ItemStack legs, ItemStack boots, ItemStack offhand) {
   }
}
