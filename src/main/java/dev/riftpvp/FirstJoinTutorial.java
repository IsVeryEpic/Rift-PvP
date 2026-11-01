package dev.riftpvp;

import java.io.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** One-time, opt-out, clickable guide. Never changes the player's inventory. */
final class FirstJoinTutorial implements Listener {
    private final RiftPvpPlugin plugin;
    private final File file;
    private final Set<UUID> greeted = new HashSet<>();
    FirstJoinTutorial(RiftPvpPlugin plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "tutorial-seen.yml");
        for (String raw : YamlConfiguration.loadConfiguration(file).getStringList("seen")) {
            try { greeted.add(UUID.fromString(raw)); } catch (IllegalArgumentException ignored) { }
        }
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getConfig().getBoolean("tutorial.enabled", true) || greeted.contains(player.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || greeted.contains(player.getUniqueId())) return;
            show(player);
            greeted.add(player.getUniqueId());
            YamlConfiguration data = new YamlConfiguration();
            data.set("seen", greeted.stream().map(UUID::toString).sorted().toList());
            try { data.save(file); }
            catch (IOException error) { plugin.getLogger().warning("Tutorial seen state not saved: " + error.getMessage()); }
        }, 40L);
    }
    private void link(Player player, String title, String command, String detail) {
        player.sendMessage(Component.text("  [" + title + "] ", NamedTextColor.AQUA)
            .clickEvent(ClickEvent.runCommand("/" + command))
            .append(Component.text(detail, NamedTextColor.GRAY)));
    }
    void show(Player player) {
        player.sendMessage("§5§l✦ RiftPvP §d| Welcome to the server!");
        player.sendMessage("§7Right-click the lobby hotbar items or use these commands:");
        link(player,"Matchmaking","rtpq","Find a fight with an official server kit");
        link(player,"Kit Editor","kiteditor","Save your preferred inventory layout");
        link(player,"Custom Kits","customkit","Save up to six personal loadouts");
        link(player,"Ranked","ranked","Earn kit-specific Elo and tiers");
        link(player,"Duels","duel","Challenge another player");
        link(player,"Settings","settings","Change your request and sound preferences");
        player.sendMessage("§7Need this again? Type §d/tutorial§7. Staff commands are permission-restricted.");
    }
}
