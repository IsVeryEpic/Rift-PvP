package dev.riftpvp;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** OP-only administration GUI; all changes still flow through the existing commands. */
final class AdminPanel implements Listener {
    private final RiftPvpPlugin plugin;
    private final Map<UUID, ChatRequest> prompts = new ConcurrentHashMap<>();

    private record ChatRequest(String type, String target, String duration, long expiresAt) {}

    AdminPanel(RiftPvpPlugin plugin) { this.plugin = plugin; }

    void open(Player player) {
        if (!player.isOp()) { player.sendMessage("§cAdmin Panel requires OP."); return; }
        View view = create("§5§lRiftPvP §8| §fAdmin Panel", "home");
        add(view, 10, Material.IRON_SWORD, "§cPlayer Moderation", "Kick, ban and unban players", "moderation");
        add(view, 11, Material.NAME_TAG, "§bManage Ranks", "Assign ranks to online players", "ranks");
        add(view, 12, Material.NETHERITE_CHESTPLATE, "§dManage Kits", "Edit kits or create new ones", "kits");
        add(view, 13, Material.COMPARATOR, "§eFeature Toggles", "Enable or disable server features", "features");
        add(view, 14, Material.BEACON, "§aLobby & NPCs", "Lobby location and NPC tools", "lobby");
        add(view, 15, Material.CLOCK, "§6Maintenance", "Pause new matches safely", "maintenance");
        add(view, 19, Material.CHEST, "§bBackups", "Save and list backups", "backups");
        add(view, 20, Material.HOPPER, "§aEntity Cleanup", "Preview or run cleanup", "cleanup");
        add(view, 21, Material.COMPASS, "§dDiagnostics", "Doctor, integrity and audit logs", "diagnostics");
        add(view, 22, Material.REDSTONE, "§eServer Operations", "Reload and other actions", "operations");
        add(view, 40, Material.BARRIER, "§cClose", "Close Admin Panel", "close");
        player.openInventory(view.inventory);
    }

    private View create(String title, String page) {
        View view = new View(page);
        view.inventory = Bukkit.createInventory(view, 54, title);
        ItemStack pane = item(Material.PURPLE_STAINED_GLASS_PANE, "§5 ", "");
        for (int i=0;i<54;i++) view.inventory.setItem(i,pane);
        return view;
    }
    private static ItemStack item(Material material, String name, String description) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(List.of(description, "§8Click to select"));
            stack.setItemMeta(meta);
        }
        return stack;
    }
    private static void add(View view, int slot, Material material, String name, String description, String action) {
        view.inventory.setItem(slot, item(material,name, "§7" + description));
        view.actions.put(slot,action);
    }
    private static void back(View view) { add(view, 49, Material.ARROW, "§eBack", "Return to Admin Panel", "home"); }
    private void show(Player player, View view) { player.openInventory(view.inventory); }

    private void players(Player player) {
        View v = create("§5§lRiftPvP §8| §fModeration", "players");
        int slot=0;
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (slot >= 45) break;
            add(v, slot++, Material.PLAYER_HEAD, "§f"+target.getName(), "Choose an action for this player", "player:"+target.getName());
        }
        add(v, 46, Material.BARRIER, "§eActive Bans", "View and revoke temporary bans", "bans");
        back(v);show(player,v);
    }
    private void banned(Player player) {
        View v=create("§5§lRiftPvP §8| §fActive Bans","bans");
        int slot=0;
        for (String name:plugin.staffTools().activeBanNames()) {
            if (slot >= 45) break;
            add(v,slot++,Material.BARRIER,"§c"+name,"Select a temporary ban to revoke","unban:"+name);
        }
        add(v,48,Material.ARROW,"§eBack to Players","Select online players","moderation");
        back(v);show(player,v);
    }
    private void playerActions(Player player,String target) {
        View v=create("§5§lRiftPvP §8| §f"+target,"target");
        add(v,11,Material.IRON_SWORD,"§cKick " + target,"Enter a reason in chat, then confirm","reason:kick:"+target);
        add(v,13,Material.CLOCK,"§6Temporary Ban","Select the ban duration","duration:"+target);
        add(v,15,Material.PAPER,"§bMatch Evidence","Inspect by match ID using chat","reason:match:"+target);
        add(v,48,Material.ARROW,"§eBack to Players","Online player list","moderation");
        back(v);show(player,v);
    }
    private void durations(Player player,String target) {
        View v=create("§5§lRiftPvP §8| §fBan Duration","duration");
        add(v,10,Material.CLOCK,"§e30 Minutes","Short temporary ban","reason:ban:"+target+":30m");
        add(v,12,Material.CLOCK,"§e12 Hours","Half-day temporary ban","reason:ban:"+target+":12h");
        add(v,14,Material.CLOCK,"§c7 Days","One-week temporary ban","reason:ban:"+target+":7d");
        add(v,16,Material.CLOCK,"§c30 Days","One-month temporary ban","reason:ban:"+target+":30d");
        back(v);show(player,v);
    }
    private void confirm(Player player,String operation,String target,String duration,String reason) {
        View v=create("§5§lRiftPvP §8| §cConfirm Action","confirm");
        v.target=target;v.operation=operation;v.duration=duration;v.reason=reason;
        String details=reason.isBlank()?"No reason needed":reason;
        add(v,21,Material.LIME_WOOL,"§aConfirm " + operation + " on " + target,
            "Reason: " + (details.length()>50?details.substring(0,50):details),"confirm");
        add(v,23,Material.RED_WOOL,"§cCancel","Return without applying changes","home");
        show(player,v);
    }
    private void kits(Player player) {
        View v=create("§5§lRiftPvP §8| §fKit Management","kits");
        int slot=0;
        for (String kit : plugin.kits().configuredIds()) {
            if (slot>=45) break;
            add(v,slot++,Material.NETHERITE_CHESTPLATE,"§b"+kit,"Left-click: settings | Right-click: delete","kit:"+kit);
        }
        add(v,47,Material.ANVIL,"§aCreate New Kit","Enter a kit ID in chat","reason:create-kit:unused");
        back(v);show(player,v);
    }
    private void ranks(Player player) {
        View v=create("§5§lRiftPvP §8| §fRank Management","ranks");
        int i=0;
        for (Player target:Bukkit.getOnlinePlayers()) {
            if (i>=45) break;
            add(v,i++,Material.NAME_TAG,"§b"+target.getName(),"Current: "+plugin.ranks().rankId(target.getUniqueId()),"rank-target:"+target.getName());
        }
        add(v,47,Material.ANVIL,"§aCreate New Rank","Type a rank ID in chat","reason:create-rank:unused");
        back(v);show(player,v);
    }
    private void chooseRank(Player player,String target) {
        View v=create("§5§RiftPvP §8| §fSet Rank: "+target,"rank-choice");
        int slot=0;
        for(String rank:plugin.ranks().list().split(", ")) {
            if(slot>=45)break;
            add(v,slot++,Material.NAME_TAG,"§b"+rank,"Assign this rank to "+target,"rank-set:"+target+":"+rank);
        }
        back(v);show(player,v);
    }
    private void controls(Player player,String page) {
        View v=create("§5§lRiftPvP §8| §f"+page,"controls");
        switch(page) {
            case "lobby" -> {
                add(v,10,Material.BEACON,"§aSet Lobby Here","Save your current position as lobby","confirm-command:riftpvp setlobby");
                add(v,12,Material.ARMOR_STAND,"§eCreate Hub NPC","Create an NPC where you stand","command:riftpvp npc create hub");
                add(v,13,Material.ARMOR_STAND,"§eCreate Ranked NPC","Create a Ranked NPC","command:riftpvp npc create ranked");
                add(v,14,Material.ARMOR_STAND,"§eCreate Spectate NPC","Create a Spectate NPC","command:riftpvp npc create spectate");
                add(v,16,Material.PAPER,"§fList NPCs","Show the saved NPC list","command:riftpvp npc list");
                add(v,22,Material.NAME_TAG,"§bBind Looking NPC","Bind a mannequin to a menu","npcbind");
                add(v,24,Material.BOOK,"§fManage Existing NPCs","Move, rename or remove saved NPCs","npc-list");
            }
            case "npc-list" -> {
                int slot=0;
                for(String id:plugin.npcs().ids()) {
                    if(slot>=45)break;
                    add(v,slot++,Material.ARMOR_STAND,"§b"+id,"Select to manage this NPC","npc:"+id);
                }
            }
            case "npcbind" -> {
                add(v,11,Material.NETHER_STAR,"§dBind Hub","Look at the mannequin first","command:riftpvp npc bind hub");
                add(v,13,Material.NETHERITE_SWORD,"§cBind Ranked","Look at the mannequin first","command:riftpvp npc bind ranked");
                add(v,15,Material.SPYGLASS,"§bBind Spectate","Look at the mannequin first","command:riftpvp npc bind spectate");
            }
            case "maintenance" -> {
                add(v,11,Material.LIME_WOOL,"§aDisable Maintenance","Resume new matchmaking","command:riftpvp maintenance off");
                add(v,15,Material.RED_WOOL,"§cEnable Maintenance","Stop new matches","confirm-command:riftpvp maintenance on");
                add(v,22,Material.CLOCK,"§eMaintenance Status","Check current state","command:riftpvp maintenance status");
            }
            case "backups" -> {
                add(v,11,Material.CHEST,"§aCreate Backup Now","Save plugin data snapshot","command:riftpvp backup now");
                add(v,15,Material.BOOK,"§eList Backups","Review existing backup files","command:riftpvp backup list");
                add(v,22,Material.BARRIER,"§cRestore Backup","Console-only safeguard: see instructions","command:riftpvp backup list");
            }
            case "cleanup" -> {
                add(v,11,Material.HOPPER,"§aPreview Cleanup","See entities eligible for clearing","command:riftpvp cleanup preview");
                add(v,13,Material.REDSTONE,"§eCleanup Status","Show cleanup status","command:riftpvp cleanup status");
                add(v,15,Material.LAVA_BUCKET,"§cCleanup Now","Remove eligible entities","confirm-command:riftpvp cleanup now");
            }
            case "diagnostics" -> {
                add(v,11,Material.COMPASS,"§bPlugin Doctor","Check configured features","command:riftpvp doctor");
                add(v,13,Material.PAPER,"§eRanked Integrity","Check competitive systems","command:riftpvp integrity");
                add(v,15,Material.WRITABLE_BOOK,"§dStaff Audit","Show recent staff actions","command:riftpvp audit");
            }
            case "operations" -> {
                add(v,13,Material.REDSTONE_BLOCK,"§cReload RiftPvP","Reload plugin configuration","confirm-command:riftpvp reload");
                add(v,15,Material.COMPARATOR,"§dFeature Toggles","Manage features and build mode","features");
            }
            default -> { }
        }
        back(v);show(player,v);
    }
    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof View view)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.isOp()) return;
        if (event.getClickedInventory()!=event.getView().getTopInventory()) return;
        String action=view.actions.get(event.getRawSlot());
        if(action==null)return;
        if(action.startsWith("kit:") && event.isRightClick())action="kit-delete:"+action.substring(4);
        final String chosen=action;
        Bukkit.getScheduler().runTask(plugin,()->{
            if(!player.isOnline()||!player.isOp())return;
            if(player.getOpenInventory().getTopInventory().getHolder()!=view)return;
            player.closeInventory();
            route(player,chosen,view);
        });
    }
    private void route(Player player,String action,View source) {
        if(!player.isOp())return;
        if(action.equals("close"))return;
        if(action.equals("home")){open(player);return;}
        if(action.equals("moderation")){players(player);return;}
        if(action.equals("bans")){banned(player);return;}
        if(action.equals("ranks")){ranks(player);return;}
        if(action.equals("kits")){kits(player);return;}
        if(action.equals("features")){plugin.adminFeatures().open(player);return;}
        if(action.equals("npcbind")||List.of("lobby","maintenance","backups","cleanup","diagnostics","operations").contains(action)){
            controls(player,action);return;
        }
        if(action.equals("npc-list")){controls(player,"npc-list");return;}
        if(action.startsWith("npc:")){
            String id=action.substring(4);
            View v=create("§5§lRiftPvP §8| §fNPC "+id,"npc-action");
            add(v,11,Material.ENDER_PEARL,"§eMove NPC Here","Reposition the NPC to where you stand","command:riftpvp npc move "+id);
            add(v,13,Material.NAME_TAG,"§bRename NPC","Type a new name in chat","reason:npc-rename:"+id);
            add(v,15,Material.BARRIER,"§cRemove NPC","Requires confirmation","confirm-command:riftpvp npc remove "+id);
            back(v);show(player,v);return;
        }
        if(action.startsWith("player:")){playerActions(player,action.substring(7));return;}
        if(action.startsWith("unban:")){confirm(player,"unban",action.substring(6),"","");return;}
        if(action.startsWith("duration:")){durations(player,action.substring(9));return;}
        if(action.startsWith("rank-target:")){chooseRank(player,action.substring(12));return;}
        if(action.startsWith("rank-set:")){
            String[] parts=action.substring(9).split(":",2);
            if(parts.length==2)player.performCommand("riftpvp rank set "+parts[0]+" "+parts[1]);
            ranks(player);return;
        }
        if(action.startsWith("kit-delete:")){confirm(player,"kit-delete",action.substring(11),"","");return;}
        if(action.startsWith("kit:")){player.performCommand("kitsettings "+action.substring(4));return;}
        if(action.startsWith("reason:")){
            String[] parts=action.split(":",4);
            String type=parts.length>1?parts[1]:"";
            String target=parts.length>2?parts[2]:"";
            String duration=parts.length>3?parts[3]:"";
            prompts.put(player.getUniqueId(),new ChatRequest(type,target,duration,System.currentTimeMillis()+60000));
            player.sendMessage("§eType "+(type.equals("create-kit")?"a kit ID":type.equals("create-rank")?"a rank ID":type.equals("npc-rename")?"an NPC name":type.equals("match")?"a match ID":"a reason")+" in chat within 60 seconds. Type cancel to abort.");
            return;
        }
        if(action.equals("confirm")){
            if(source.operation==null)return;
            switch(source.operation){
                case "ban" -> plugin.staffTools().command(player,"rtempban",new String[]{source.target,source.duration,source.reason});
                case "kick" -> plugin.staffTools().command(player,"riftkick",new String[]{source.target,source.reason});
                case "unban" -> plugin.staffTools().command(player,"rtunban",new String[]{source.target});
                case "kit-delete" -> {
                    // The server's existing deletion system already implements its own
                    // 30-second confirmation. Complete it only after this GUI confirmation.
                    player.performCommand("kitdelete "+source.target);
                    player.performCommand("kitdelete "+source.target+" confirm");
                }
                case "command" -> player.performCommand(source.target);
                default -> { }
            }
            return;
        }
        if(action.startsWith("confirm-command:")){confirm(player,"command",action.substring(16),"","");return;}
        if(action.startsWith("command:")){player.performCommand(action.substring(8));return;}
    }
    @EventHandler public void onChat(AsyncChatEvent event) {
        UUID id=event.getPlayer().getUniqueId();
        ChatRequest pending=prompts.remove(id);
        if(pending==null)return;
        event.setCancelled(true);
        String entered=PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin,()->{
            Player player=Bukkit.getPlayer(id);
            if(player==null||!player.isOp())return;
            if(System.currentTimeMillis()>pending.expiresAt()){
                player.sendMessage("§cAdmin input expired. Open the menu again.");return;
            }
            if(entered.equalsIgnoreCase("cancel")){player.sendMessage("§7Cancelled.");return;}
            if(entered.isBlank()||entered.length()>160||entered.chars().anyMatch(c->Character.isISOControl((char)c))){
                player.sendMessage("§cInvalid input; use 1-160 characters.");return;
            }
            switch(pending.type()){
                case "create-kit" -> {
                    if(!KitManager.safeId(entered)){player.sendMessage("§cInvalid kit ID.");return;}
                    player.performCommand("kitcreate "+entered);
                }
                case "create-rank" -> {
                    if(!KitManager.safeId(entered)){player.sendMessage("§cInvalid rank ID.");return;}
                    player.performCommand("riftpvp rank create "+entered);
                }
                case "npc-rename" -> player.performCommand("riftpvp npc rename "+pending.target()+" "+entered);
                case "match" -> player.performCommand("match inspect "+entered.replaceAll("[^A-Za-z0-9_-]",""));
                case "kick","ban" -> confirm(player,pending.type(),pending.target(),pending.duration(),entered);
                default -> { }
            }
        });
    }
    @EventHandler public void onDrag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof View)event.setCancelled(true);
    }
    private static final class View implements InventoryHolder {
        final String page;
        final Map<Integer,String> actions=new HashMap<>();
        Inventory inventory;
        String target,operation,duration,reason;
        View(String page){this.page=page;}
        @Override public Inventory getInventory(){return inventory;}
    }
}
