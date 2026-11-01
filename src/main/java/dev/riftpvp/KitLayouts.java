package dev.riftpvp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

final class KitLayouts implements Listener {
   private static final int SIZE = 41;
   private final RiftPvpPlugin plugin;
   private final File directory;
   private final Map<UUID, Map<String, int[]>> cached = new HashMap<>();
   private final Map<UUID, KitLayouts.EditorSession> sessions = new HashMap<>();

   KitLayouts(RiftPvpPlugin var1) {
      this.plugin = var1;
      this.directory = new File(var1.getDataFolder(), "layouts");
      if (!this.directory.isDirectory() && !this.directory.mkdirs()) {
         var1.getLogger().warning("Could not create RiftPvP kit layouts folder.");
      }
   }

   ItemStack[] apply(UUID var1, String var2, ItemStack[] var3) {
      int[] var4 = this.playerLayout(var1, var2);
      ItemStack[] var5 = new ItemStack[41];

      for (int var6 = 0; var6 < 41; var6++) {
         ItemStack var7 = var3[var4[var6]];
         var5[var6] = var7 == null ? null : var7.clone();
      }

      return var5;
   }

   private int[] playerLayout(UUID var1, String var2) {
      int[] var3 = this.playerLayouts(var1).get(var2);
      return var3 != null && valid(var3) ? (int[])var3.clone() : identity();
   }

   private Map<String, int[]> playerLayouts(UUID var1) {
      return this.cached.computeIfAbsent(var1, this::load);
   }

   private Map<String, int[]> load(UUID var1) {
      HashMap var2 = new HashMap();
      YamlConfiguration var3 = YamlConfiguration.loadConfiguration(new File(this.directory, var1 + ".yml"));
      ConfigurationSection var4 = var3.getConfigurationSection("kits");
      if (var4 != null) {
         for (String var6 : var4.getKeys(false)) {
            List<Integer> var7 = var3.getIntegerList("kits." + var6);
            if (var7.size() == 41) {
               int[] var8 = var7.stream().mapToInt(Integer::intValue).toArray();
               if (valid(var8)) {
                  var2.put(var6, var8);
               }
            }
         }
      }

      return var2;
   }

   private static int[] identity() {
      int[] var0 = new int[41];
      int var1 = 0;

      while (var1 < 41) {
         var0[var1] = var1++;
      }

      return var0;
   }

   private static boolean valid(int[] var0) {
      if (var0.length != 41) {
         return false;
      }

      boolean[] var1 = new boolean[41];

      for (int var5 : var0) {
         if (var5 < 0 || var5 >= 41 || var1[var5]) {
            return false;
         }

         var1[var5] = true;
      }

      return true;
   }

   private void save(UUID var1, String var2, int[] var3) throws IOException {
      if (!valid(var3)) {
         throw new IllegalArgumentException("Invalid kit layout permutation");
      }

      Map<String, int[]> var4 = this.playerLayouts(var1);
      HashMap<String, int[]> var5 = new HashMap<>(var4);
      var5.put(var2, (int[])var3.clone());
      YamlConfiguration var6 = new YamlConfiguration();

      for (Entry<String, int[]> var8 : var5.entrySet()) {
         var6.set("kits." + (String)var8.getKey(), Arrays.stream((int[])var8.getValue()).boxed().toList());
      }

      File var11 = new File(this.directory, var1 + ".yml");
      File var12 = new File(this.directory, var1 + ".yml.new");
      Files.writeString(var12.toPath(), var6.saveToString(), StandardCharsets.UTF_8);

      try {
         Files.move(var12.toPath(), var11.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException var10) {
         Files.move(var12.toPath(), var11.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }

      this.cached.put(var1, var5);
   }

   void open(Player var1, String var2) {
      if (!this.plugin.kits().hasKit(var2)) {
         this.plugin.message(var1, "kit-not-loaded");
      } else if (this.plugin.combat().mayUse(var1, "kit")) {
         KitLayouts.EditorHolder var3 = new KitLayouts.EditorHolder();
         Inventory var4 = Bukkit.createInventory(var3, 54, RiftPvpPlugin.colors("&5Kit Editor &8| &f" + this.plugin.kits().name(var2)));
         var3.inventory = var4;
         KitLayouts.EditorSession var5 = new KitLayouts.EditorSession(var2, this.playerLayout(var1.getUniqueId(), var2), var3);
         this.sessions.put(var1.getUniqueId(), var5);
         this.draw(var1, var5);
         var1.openInventory(var4);
         var1.sendMessage(RiftPvpPlugin.colors("&dKit Editor &7» &fClick two slots to swap, then click &aSave&f."));
      }
   }

   private void draw(Player var1, KitLayouts.EditorSession var2) {
      ItemStack[] var3 = this.plugin.kits().originalSlots(var2.kit);
      if (var3 != null) {
         Inventory var4 = var2.holder.inventory;

         for (int var5 = 0; var5 < 41; var5++) {
            int var6 = viewToSlot(var5);
            if (var6 >= 0) {
               ItemStack var7 = var3[var2.mapping[var6]];
               ItemStack var8 = var7 != null && !var7.getType().isAir() ? var7.clone() : new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
               ItemMeta var9 = var8.getItemMeta();
               if (var9 != null) {
                  ArrayList var10 = var9.hasLore() && var9.getLore() != null ? new ArrayList(var9.getLore()) : new ArrayList();
                  if (var7 == null || var7.getType().isAir()) {
                     var9.setDisplayName(RiftPvpPlugin.colors("&8Empty Slot"));
                  }

                  var10.add(RiftPvpPlugin.colors(var2.selected == var6 ? "&eSELECTED - choose another slot" : "&7Click to select / swap"));
                  var9.setLore(var10);
                  var8.setItemMeta(var9);
               }

               var4.setItem(var5, var8);
            }
         }

         for (int var11 = 41; var11 < 54; var11++) {
            var4.setItem(var11, new ItemStack(Material.BLACK_STAINED_GLASS_PANE));
         }

         var4.setItem(45, button(Material.EMERALD_BLOCK, "&a&lSave Layout"));
         var4.setItem(46, this.plugin.kits().isKitRoomEnabled(var2.kit)
            ? button(Material.CHEST, "&d&lKit Room &7| &fRestocking Items")
            : button(Material.BARRIER, "&8Kit Room &7| &cDisabled for this kit"));
         var4.setItem(47, button(this.plugin.ranks().canEditTrims(var1) ? Material.SMITHING_TABLE : Material.BARRIER,
            this.plugin.ranks().canEditTrims(var1) ? "&bVIP Armour Trims" : "&8Armour Trims &7(VIP only)"));
         var4.setItem(49, button(Material.BARRIER, "&cReset to Default"));
         var4.setItem(53, button(Material.ARROW, "&fBack to Kits"));
      }
   }

   private static ItemStack button(Material var0, String var1) {
      ItemStack var2 = new ItemStack(var0);
      ItemMeta var3 = var2.getItemMeta();
      if (var3 != null) {
         var3.setDisplayName(RiftPvpPlugin.colors(var1));
         var2.setItemMeta(var3);
      }

      return var2;
   }

   private static int viewToSlot(int var0) {
      if (var0 >= 0 && var0 < 27) {
         return var0 + 9;
      } else if (var0 >= 27 && var0 < 36) {
         return var0 - 27;
      } else {
         return var0 >= 36 && var0 <= 40 ? var0 : -1;
      }
   }

   @EventHandler
   public void onClick(InventoryClickEvent var1) {
      if (var1.getView().getTopInventory().getHolder() instanceof KitLayouts.EditorHolder var2) {
         var1.setCancelled(true);
         if (var1.getWhoClicked() instanceof Player var9 && var2.equals(this.holderOf(var9))) {
            if (var1.getClickedInventory() == var1.getView().getTopInventory()) {
               int var10 = var1.getRawSlot();
               KitLayouts.EditorSession var5 = this.sessions.get(var9.getUniqueId());
               if (var10 == 45) {
                  try {
                     this.save(var9.getUniqueId(), var5.kit, var5.mapping);
                     var9.sendMessage(RiftPvpPlugin.colors("&aLayout saved for &e" + this.plugin.kits().name(var5.kit) + "&a!"));
                     var9.closeInventory();
                  } catch (IOException var8) {
                     var9.sendMessage(RiftPvpPlugin.colors("&cCould not save layout: " + var8.getMessage()));
                  }
               } else if (var10 == 46) {
                  if (!this.plugin.kits().isKitRoomEnabled(var5.kit)) {
                     var9.sendMessage(RiftPvpPlugin.colors("&cKit Room is disabled for this kit."));
                     return;
                  }
                  String kit = var5.kit;
                  this.sessions.remove(var9.getUniqueId());
                  Bukkit.getScheduler().runTask(this.plugin, () -> this.plugin.kitRoom().open(var9, kit));
               } else if (var10 == 47) {
                  if (this.plugin.ranks().canEditTrims(var9)) {
                     String kit = var5.kit;
                     this.sessions.remove(var9.getUniqueId());
                     Bukkit.getScheduler().runTask(this.plugin, () -> this.plugin.trims().open(var9, kit));
                  } else {
                     var9.sendMessage(RiftPvpPlugin.colors("&cArmour trim customization requires the VIP rank."));
                  }
               } else if (var10 == 49) {
                  var5.mapping = identity();
                  var5.selected = -1;
                  this.draw(var9, var5);
               } else if (var10 == 53) {
                  this.sessions.remove(var9.getUniqueId());
                  Bukkit.getScheduler().runTask(this.plugin, () -> this.plugin.menus().openEditorSelection(var9));
               } else {
                  int var6 = viewToSlot(var10);
                  if (var6 >= 0 && (var1.isLeftClick() || var1.isRightClick())) {
                     if (var5.selected >= 0 && var5.selected != var6) {
                        int var7 = var5.mapping[var6];
                        var5.mapping[var6] = var5.mapping[var5.selected];
                        var5.mapping[var5.selected] = var7;
                        var5.selected = -1;
                     } else {
                        var5.selected = var5.selected == var6 ? -1 : var6;
                     }

                     this.draw(var9, var5);
                  }
               }
            }
         }
      }
   }

   private KitLayouts.EditorHolder holderOf(Player var1) {
      KitLayouts.EditorSession var2 = this.sessions.get(var1.getUniqueId());
      return var2 == null ? null : var2.holder;
   }

   @EventHandler
   public void onDrag(InventoryDragEvent var1) {
      if (var1.getView().getTopInventory().getHolder() instanceof KitLayouts.EditorHolder) {
         var1.setCancelled(true);
      }
   }

   @EventHandler
   public void onClose(InventoryCloseEvent var1) {
      if (var1.getView().getTopInventory().getHolder() instanceof KitLayouts.EditorHolder var2) {
         UUID var4 = var1.getPlayer().getUniqueId();
         if (var2 == this.holderOf((Player)var1.getPlayer())) {
            this.sessions.remove(var4);
         }
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent var1) {
      this.sessions.remove(var1.getPlayer().getUniqueId());
      this.cached.remove(var1.getPlayer().getUniqueId());
   }

   private static final class EditorHolder implements InventoryHolder {
      private Inventory inventory;

      public Inventory getInventory() {
         return this.inventory;
      }
   }

   private static final class EditorSession {
      private final String kit;
      private int[] mapping;
      private final KitLayouts.EditorHolder holder;
      private int selected = -1;

      private EditorSession(String var1, int[] var2, KitLayouts.EditorHolder var3) {
         this.kit = var1;
         this.mapping = var2;
         this.holder = var3;
      }
   }
}
