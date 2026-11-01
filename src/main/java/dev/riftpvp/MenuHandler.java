package dev.riftpvp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

final class MenuHandler implements Listener {
   private final RiftPvpPlugin plugin;
   private final Map<UUID, String> leaderboardModes = new HashMap<>();

   MenuHandler(RiftPvpPlugin var1) {
      this.plugin = var1;
   }

   void openKits(Player var1) {
      this.openKitMenu(var1, "kit");
   }

   void openQueue(Player var1) {
      this.openKitMenu(var1, "queue");
   }

   void openRanked(Player player) {
      this.openKitMenu(player, "ranked");
   }

   void openSpectate(Player player) {
      List<Player> fighters = this.plugin.practice().activeFighters();
      if (fighters.isEmpty()) {
         player.sendMessage("§7There are no active fights to spectate right now.");
         return;
      }
      MenuHandler.RiftHolder holder = new MenuHandler.RiftHolder("spectate");
      Inventory inventory = Bukkit.createInventory(holder, sanitizeSize(Math.max(9, fighters.size())),
          "§5§lRiftPvP §8| §dSpectate Fights");
      holder.inventory = inventory;
      for (int i = 0; i < fighters.size() && i < inventory.getSize(); i++) {
         Player fighter = fighters.get(i);
         ItemStack head = new ItemStack(Material.PLAYER_HEAD);
         ItemMeta meta = head.getItemMeta();
         if (meta != null) {
            meta.setDisplayName("§d" + fighter.getName());
            meta.setLore(List.of("§7Click to spectate this player"));
            head.setItemMeta(meta);
         }
         inventory.setItem(i, head);
         holder.slots.put(i, fighter.getUniqueId().toString());
      }
      player.openInventory(inventory);
   }

   void openEditorSelection(Player var1) {
      this.openKitMenu(var1, "editor");
   }

   private void openKitMenu(Player var1, String var2) {
      if (this.plugin.combat().mayUse(var1, var2.equals("queue") ? "queue" : "kit")) {
         FileConfiguration var3 = this.plugin.getConfig();
         int var4 = this.plugin.kits().configuredIds().stream().mapToInt(this.plugin.kits()::slot).max().orElse(0);
         int var5 = sanitizeSize(Math.max(var4 + 1, var3.getInt("menus." + var2 + "-size", var2.equals("ranked") ? 45 : 36)));
         MenuHandler.RiftHolder var6 = new MenuHandler.RiftHolder(var2);
         Inventory var7 = Bukkit.createInventory(var6, var5, RiftPvpPlugin.colors(var3.getString("menus." + var2 + "-title", var2.equals("ranked") ? "&5&lRiftPvP &8| &dRanked Kits" : "&5Rift PvP")));
         var6.inventory = var7;
         List<String> var8 = var3.getStringList("menus." + var2 + "-lore");
         if (var2.equals("ranked") && var8.isEmpty())
            var8 = List.of("&7Click to join Ranked with this kit", "&7Waiting: &e%waiting%");

         for (String var10 : this.plugin.kits().configuredIds()) {
            int var11 = this.plugin.kits().slot(var10);
            if (var11 >= 0 && var11 < var5 && !var6.slots.containsKey(var11)) {
               ItemStack var12 = this.plugin.kits().icon(var10);
               ItemMeta var13 = var12.getItemMeta();
               if (var13 != null) {
                  var13.setDisplayName(RiftPvpPlugin.colors("&d" + this.plugin.kits().name(var10)));
                  ArrayList var14 = new ArrayList();
                  boolean var15 = var2.equals("queue") && var3.getBoolean("matchmaking.auto-equip-kit", true);

                  for (String var17 : var8) {
                     if (!var15 || !var17.contains("First choose /kit")) {
                        int waiting = var2.equals("ranked") ? this.plugin.practice().rankedWaitingCount(var10)
                           : this.plugin.matchQueue().waiting(var10);
                        var14.add(RiftPvpPlugin.colors(var17.replace("%kit%", var10)
                           .replace("%waiting%", Integer.toString(waiting))));
                     }
                  }
                  if (var2.equals("ranked")) {
                     var14.add(this.plugin.practice().rankedKitStatus(var1, var10));
                     var14.add("§7Fight biomes: §aPlains only");
                     if (!this.plugin.kitModes().allowed(var10, "ranked"))
                        var14.add("§cRanked disabled for this kit");
                  }

                  if (var15) {
                     String var18 = var3.getString("menus.queue-auto-equip-lore", "&aBoth players receive this kit on match");
                     if (var18 != null && !var18.isBlank()) {
                        var14.add(RiftPvpPlugin.colors(var18));
                     }
                  }

                  if (!this.plugin.kits().hasKit(var10)) {
                     var14.add(ChatColor.RED + "Kit data is missing");
                  }

                  var13.setLore(var14);
                  var12.setItemMeta(var13);
               }

               var7.setItem(var11, var12);
               var6.slots.put(var11, var10);
            }
         }

         if (var2.equals("ranked")) {
            int leaveSlot = var5 - 5;
            if (!var6.slots.containsKey(leaveSlot)) {
               ItemStack leave = new ItemStack(Material.BARRIER);
               ItemMeta meta = leave.getItemMeta();
               if (meta != null) {
                  meta.setDisplayName("§c§lLeave Ranked Queue");
                  meta.setLore(List.of("§7Leave your current ranked queue"));
                  leave.setItemMeta(meta);
               }
               var7.setItem(leaveSlot, leave);
               var6.slots.put(leaveSlot, "__leave_ranked");
            }
         }

         if (var2.equals("queue") && var5 >= 45 && !var6.slots.containsKey(49)) {
            ItemStack custom = new ItemStack(Material.NETHER_STAR);
            ItemMeta meta = custom.getItemMeta();
            if (meta != null) {
               meta.setDisplayName(RiftPvpPlugin.colors("&d&lCustom Kit Matchmaking"));
               meta.setLore(List.of(RiftPvpPlugin.colors("&aClick to host or join a random custom kit.")));
               custom.setItemMeta(meta);
            }
            var7.setItem(49, custom);
            var6.slots.put(49, "__custom_queue");
         }
         var1.openInventory(var7);
      }
   }

   void openRtp(Player var1) {
      if (this.plugin.combat().mayUse(var1, "rtp")) {
         MenuHandler.RiftHolder var2 = new MenuHandler.RiftHolder("rtp");
         Inventory var3 = Bukkit.createInventory(var2, 27, RiftPvpPlugin.colors(this.plugin.getConfig().getString("menus.rtp-title", "&5Rift PvP | RTP")));
         var2.inventory = var3;
         this.putDimension(var3, var2, 11, "overworld", Material.GRASS_BLOCK, "Overworld");
         this.putDimension(var3, var2, 13, "nether", Material.NETHERRACK, "Nether");
         this.putDimension(var3, var2, 15, "end", Material.END_STONE, "The End");
         var1.openInventory(var3);
      }
   }

   void openLeaderboard(Player var1) {
      this.openLeaderboard(var1, "kills");
   }

   void openLeaderboardMode(Player var1, String var2) {
      String var3 = var2.toLowerCase(Locale.ROOT);
      if (!List.of("kills", "deaths", "playtime").contains(var3)) {
         var3 = "kills";
      }

      this.openLeaderboard(var1, var3);
   }

   void cycleLeaderboard(Player var1) {
      String var2 = this.leaderboardModes.getOrDefault(var1.getUniqueId(), "playtime");

      this.openLeaderboard(var1, switch (var2) {
         case "kills" -> "deaths";
         case "deaths" -> "playtime";
         default -> "kills";
      });
   }

   private void openLeaderboard(Player var1, String var2) {
      boolean var3 = var2.equals("deaths");
      boolean var4 = var2.equals("playtime");
      this.leaderboardModes.put(var1.getUniqueId(), var2);
      MenuHandler.RiftHolder var5 = new MenuHandler.RiftHolder("leaderboard");
      Inventory var6 = Bukkit.createInventory(
         var5,
         27,
         RiftPvpPlugin.colors(
            this.plugin
               .getConfig()
               .getString(
                  var4 ? "menus.playtime-leaderboard-title" : (var3 ? "menus.deaths-leaderboard-title" : "menus.leaderboard-title"),
                  var4 ? "&5Rift PvP &8| &fTop Playtime" : (var3 ? "&5Rift PvP &8| &fTop Deaths" : "&5Rift PvP &8| &fTop Kills")
               )
         )
      );
      var5.inventory = var6;
      List var7 = var4 ? this.plugin.sidebar().topPlaytime(9) : (var3 ? this.plugin.sidebar().topDeaths(9) : this.plugin.sidebar().topKills(9));

      for (int var8 = 0; var8 < var7.size(); var8++) {
         StatsSidebar.LeaderboardEntry var9 = (StatsSidebar.LeaderboardEntry)var7.get(var8);

         Material var10 = switch (var8) {
            case 0 -> var4 ? Material.CLOCK : (var3 ? Material.REDSTONE_BLOCK : Material.GOLD_BLOCK);
            case 1 -> var4 ? Material.CLOCK : (var3 ? Material.IRON_BLOCK : Material.DIAMOND_BLOCK);
            case 2 -> var4 ? Material.CLOCK : Material.IRON_BLOCK;
            default -> var4 ? Material.CLOCK : Material.DIAMOND_SWORD;
         };
         ItemStack var11 = new ItemStack(var10);
         ItemMeta var12 = var11.getItemMeta();
         if (var12 != null) {
            var12.setDisplayName(RiftPvpPlugin.colors("&d#" + (var8 + 1) + " &f" + var9.name()));
            var12.setLore(
               List.of(
                  RiftPvpPlugin.colors("&aKills: &f" + var9.kills()),
                  RiftPvpPlugin.colors("&cDeaths: &f" + var9.deaths()),
                  RiftPvpPlugin.colors("&bPlaytime: &f" + StatsSidebar.formatPlaytime(var9.playtimeMillis()))
               )
            );
            var11.setItemMeta(var12);
         }

         var6.setItem(9 + var8, var11);
      }

      if (var7.isEmpty()) {
         ItemStack var13 = new ItemStack(Material.PAPER);
         ItemMeta var15 = var13.getItemMeta();
         if (var15 != null) {
            var15.setDisplayName(RiftPvpPlugin.colors("&7No player stats recorded yet"));
            var13.setItemMeta(var15);
         }

         var6.setItem(13, var13);
      }

      ItemStack var14 = new ItemStack(Material.COMPASS);
      ItemMeta var16 = var14.getItemMeta();
      if (var16 != null) {
         var16.setDisplayName(RiftPvpPlugin.colors(var4 ? "&aClick to view Top Kills" : (var3 ? "&bClick to view Top Playtime" : "&cClick to view Top Deaths")));
         var16.setLore(List.of(RiftPvpPlugin.colors("&7Also switches every time you click the NPC.")));
         var14.setItemMeta(var16);
      }

      var6.setItem(22, var14);
      var5.slots.put(22, "toggle");
      var1.openInventory(var6);
   }

   private void putDimension(Inventory var1, MenuHandler.RiftHolder var2, int var3, String var4, Material var5, String var6) {
      ItemStack var7 = new ItemStack(var5);
      ItemMeta var8 = var7.getItemMeta();
      if (var8 != null) {
         var8.setDisplayName(RiftPvpPlugin.colors("&e" + var6));
         var8.setLore(this.plugin.getConfig().getStringList("menus.rtp-lore").stream().map(RiftPvpPlugin::colors).toList());
         var7.setItemMeta(var8);
      }

      var1.setItem(var3, var7);
      var2.slots.put(var3, var4);
   }

   @EventHandler
   public void onClick(InventoryClickEvent var1) {
      if (var1.getView().getTopInventory().getHolder() instanceof MenuHandler.RiftHolder var2) {
         var1.setCancelled(true);
         if (var1.getWhoClicked() instanceof Player var5 && var1.getClickedInventory() == var1.getView().getTopInventory()) {
            String var6 = var2.slots.get(var1.getRawSlot());
            if (var6 != null) {
               Bukkit.getScheduler().runTask(this.plugin, () -> {
                  if (var5.isOnline()) {
                     var5.closeInventory();
                     switch (var2.kind) {
                        case "kit":
                           this.plugin.equipKit(var5, var6);
                           break;
                        case "queue":
                           if ("__custom_queue".equals(var6)) this.plugin.customKits().open(var5);
                           else this.plugin.matchQueue().join(var5, var6);
                           break;
                        case "ranked":
                           if ("__leave_ranked".equals(var6)) var5.performCommand("ranked leave");
                           else var5.performCommand("ranked " + var6);
                           break;
                        case "spectate":
                           try {
                              Player target = Bukkit.getPlayer(UUID.fromString(var6));
                              if (target != null && this.plugin.practice().hasActiveFight(target.getUniqueId()))
                                 var5.performCommand("spectate " + target.getName());
                              else var5.sendMessage("§cThat fight has ended.");
                           } catch (IllegalArgumentException ignored) {
                              var5.sendMessage("§cInvalid spectator target.");
                           }
                           break;
                        case "rtp":
                           this.plugin.randomTeleport(var5, var6);
                           break;
                        case "editor":
                           this.plugin.layouts().open(var5, var6);
                           break;
                        case "leaderboard":
                           this.cycleLeaderboard(var5);
                     }
                  }
               });
            }
         }
      }
   }

   @EventHandler
   public void onDrag(InventoryDragEvent var1) {
      if (var1.getView().getTopInventory().getHolder() instanceof MenuHandler.RiftHolder) {
         var1.setCancelled(true);
      }
   }

   private static int sanitizeSize(int var0) {
      return Math.max(9, Math.min(54, (var0 + 8) / 9 * 9));
   }

   private static final class RiftHolder implements InventoryHolder {
      final String kind;
      final Map<Integer, String> slots = new HashMap<>();
      private Inventory inventory;

      RiftHolder(String var1) {
         this.kind = var1;
      }

      public Inventory getInventory() {
         return this.inventory;
      }
   }
}
