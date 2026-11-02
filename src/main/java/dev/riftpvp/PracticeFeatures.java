package dev.riftpvp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.projectiles.ProjectileSource;

/** Player duels, ranked matchmaking, profiles, spectating and post-fight comparisons. */
final class PracticeFeatures implements Listener {
   private final RiftPvpPlugin plugin;
   private final File recordsFile;
   private final File penaltyFile;
   private final Map<UUID, Penalty> rankedPenalties = new HashMap<>();
   private final Map<UUID, Invite> requests = new HashMap<>();
   private final Map<UUID, Fight> fights = new HashMap<>();
   private final Map<UUID, Boolean> pendingRanked = new HashMap<>();
   private final Map<UUID, Recent> recent = new HashMap<>();
   private final Map<UUID, Result> results = new HashMap<>();
   private final Map<UUID, Map<String, Rating>> ratings = new HashMap<>();
   private final Map<String, Deque<UUID>> ranked = new HashMap<>();
   private final Map<UUID, String> rankedWaiting = new HashMap<>();
   private final Map<UUID, Long> rankedJoinedAt = new HashMap<>();
   private final Map<UUID, Spectator> spectators = new HashMap<>();
   private final Map<UUID, Party> membership = new HashMap<>();
   private final Map<UUID, UUID> partyInvites = new HashMap<>();

   PracticeFeatures(RiftPvpPlugin plugin) {
      this.plugin = plugin;
      recordsFile = new File(plugin.getDataFolder(), "competitive.yml");
      penaltyFile = new File(plugin.getDataFolder(), "ranked-penalties.yml");
      loadPenalties();
      YamlConfiguration data = YamlConfiguration.loadConfiguration(recordsFile);
      ConfigurationSection players = data.getConfigurationSection("players");
      if (players != null) {
         for (String id : players.getKeys(false)) {
            try {
               UUID uuid = UUID.fromString(id);
               Map<String, Rating> perKit = new HashMap<>();
               ConfigurationSection kits = data.getConfigurationSection("players." + id);
               if (kits != null) for (String kit : kits.getKeys(false)) {
                  String path = "players." + id + "." + kit + ".";
                  perKit.put(kit, new Rating(data.getInt(path + "wins"), data.getInt(path + "losses"),
                     Math.max(100, data.getInt(path + "elo", 1000))));
               }
               ratings.put(uuid, perKit);
            } catch (IllegalArgumentException ignored) {
            }
         }
      }
   }

   boolean hasActiveFights() { return !fights.isEmpty(); }
   void forfeitDisconnected(UUID id) { finish(id, true); }
   void clearRankedWaiting() {
      for (UUID id : new HashSet<>(rankedWaiting.keySet())) {
         Player p = Bukkit.getPlayer(id);
         if (p != null) p.sendMessage("§eRanked queues were closed for server maintenance.");
         clearWaiting(id);
      }
      ranked.clear();
   }
   void removeRankedKitQueue(String kit) {
      Deque<UUID> removed = ranked.remove(kit);
      if (removed != null) for (UUID id : removed) {
         clearWaiting(id);
         Player player = Bukkit.getPlayer(id);
         if (player != null) player.sendMessage("§cRanked queue for this kit was disabled.");
      }
   }
   void removeKitInvites(String kit) { requests.entrySet().removeIf(e -> e.getValue().kit.equals(kit)); }


   void removeKitQueues(String kit) {
      Deque<UUID> removed = ranked.remove(kit);
      if (removed != null) for (UUID id : removed) {
         rankedWaiting.remove(id);
         rankedJoinedAt.remove(id);
         Player player = Bukkit.getPlayer(id);
         if (player != null) player.sendMessage("§cThe ranked queue for this kit was removed by staff.");
      }
      requests.entrySet().removeIf(e -> e.getValue().kit.equals(kit));
   }

   boolean command(Player player, String name, String[] args) {
      return switch (name) {
         case "duel" -> { duel(player, args); yield true; }
         case "rematch" -> { rematch(player); yield true; }
         case "ranked" -> { ranked(player, args); yield true; }
         case "stats" -> { stats(player, args); yield true; }
         case "fightstats" -> { plugin.fightAnalytics().playerStats(player); yield true; }
         case "spectate" -> { spectate(player, args); yield true; }
         case "unspectate" -> { unspectate(player); yield true; }
         case "fightresult" -> { resultMenu(player); yield true; }
         case "party" -> { party(player, args); yield true; }
         case "rank" -> { showRank(player, args); yield true; }
         case "profile" -> { stats(player, args); yield true; }
         default -> false;
      };
   }

   private void duel(Player player, String[] args) {
      if (args.length < 1) {
         player.sendMessage("§d/duel <player> [kit] §7| §d/duel accept §7| §d/duel deny");
         return;
      }
      if (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny")) {
         Invite invite = requests.get(player.getUniqueId());
         if (invite == null || System.currentTimeMillis() > invite.expires
               || args.length > 1 && !args[1].equalsIgnoreCase(invite.token.toString())) {
            player.sendMessage("§cNo current duel request.");
            return;
         }
         requests.remove(player.getUniqueId());
         Player challenger = Bukkit.getPlayer(invite.from);
         if (challenger == null) { player.sendMessage("§cThe challenger left."); return; }
         if (args[0].equalsIgnoreCase("deny")) {
            challenger.sendMessage("§c" + player.getName() + " declined your duel.");
            player.sendMessage("§7Duel declined.");
            return;
         }
         if (!CustomKits.isCustom(invite.kit) && !plugin.kitModes().check(player, invite.kit, "duel")) return;
         if (plugin.matchQueue().challenge(challenger, player, invite.kit)) {
            challenger.sendMessage("§aDuel accepted! §7Kit: §d" + invite.kit);
            player.sendMessage("§aPreparing duel against §f" + challenger.getName());
         } else player.sendMessage("§cCannot start: one player is busy, combat-tagged, or the kit was removed.");
         return;
      }
      Player target = Bukkit.getPlayer(args[0]);
      String kit = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : plugin.kits().configuredIds().stream().filter(plugin.kits()::hasKit).findFirst().orElse("");
      if (target == null || target.equals(player)) { player.sendMessage("§cChoose another online player."); return; }
      // /duel Player custom:1 through custom:6 selects the challenger's own saved kit.
      // Full custom:uuid:slot tokens are accepted for rematches only if one opponent owns it.
      if (kit.matches("custom:[1-6]")) kit = CustomKits.id(player.getUniqueId(), Integer.parseInt(kit.substring(7)));
      if (CustomKits.isCustom(kit)
            ? !plugin.customKits().exists(kit) ||
              !(kit.startsWith("custom:" + player.getUniqueId() + ":") || kit.startsWith("custom:" + target.getUniqueId() + ":"))
            : !plugin.kits().hasKit(kit)) {
         player.sendMessage("§cUnknown kit. Use /kit or save a personal kit with /customkit save 1-6."); return;
      }
      if (!CustomKits.isCustom(kit) && !plugin.kitModes().check(player, kit, "duel")) return;
      if (plugin.combat().tagged(player) || plugin.combat().tagged(target) || fights.containsKey(player.getUniqueId())) {
         player.sendMessage("§cYou cannot challenge during combat or an active fight."); return;
      }
      UUID token = UUID.randomUUID();
      requests.put(target.getUniqueId(), new Invite(player.getUniqueId(), kit, System.currentTimeMillis() + 60000, token));
      if (!plugin.playerPreferences().allows(target, "duels")) {
         player.sendMessage("§cThat player has disabled duel invitations.");
         requests.remove(target.getUniqueId());
         return;
      }
      target.sendMessage(Component.text(player.getName() + " challenged you to a " + kit + " duel!  ", NamedTextColor.LIGHT_PURPLE)
         .append(Component.text("[ACCEPT] ", NamedTextColor.GREEN)
            .clickEvent(ClickEvent.runCommand("/duel accept " + token)))
         .append(Component.text("[DECLINE]", NamedTextColor.RED)
            .clickEvent(ClickEvent.runCommand("/duel deny " + token))));
      player.sendMessage("§aDuel request sent to §f" + target.getName() + "§a.");
   }

   private void rematch(Player player) {
      Recent old = recent.get(player.getUniqueId());
      if (old == null) { player.sendMessage("§cNo recent opponent to rematch."); return; }
      Player target = Bukkit.getPlayer(old.other);
      if (target == null) { player.sendMessage("§cYour last opponent is offline."); return; }
      if (!plugin.playerPreferences().allows(target, "rematches")) {
         player.sendMessage("§cYour last opponent disabled rematch invitations."); return;
      }
      duel(player, new String[]{target.getName(), old.kit});
   }

   private record Penalty(int strikes, long lastAt, long endsAt) {}

   private void loadPenalties() {
      YamlConfiguration data = YamlConfiguration.loadConfiguration(penaltyFile);
      ConfigurationSection section = data.getConfigurationSection("players");
      if (section == null) return;
      for (String id : section.getKeys(false)) {
         try {
            UUID player = UUID.fromString(id);
            String path = "players." + id + ".";
            rankedPenalties.put(player, new Penalty(data.getInt(path + "strikes", 0), data.getLong(path + "lastAt", 0), data.getLong(path + "endsAt", 0)));
         } catch (IllegalArgumentException ignored) { }
      }
   }

   private void savePenalties() {
      YamlConfiguration data = new YamlConfiguration();
      rankedPenalties.forEach((id, penalty) -> {
         String path = "players." + id + ".";
         data.set(path + "strikes", penalty.strikes);
         data.set(path + "lastAt", penalty.lastAt);
         data.set(path + "endsAt", penalty.endsAt);
      });
      try { data.save(penaltyFile); }
      catch (IOException error) { plugin.getLogger().warning("Could not save ranked queue penalties: " + error.getMessage()); }
   }

   /** Applied only for a player explicitly choosing /lobby in an active ranked fight. */
   private void explicitRankedAbandon(Player player) {
      if (!plugin.getConfig().getBoolean("ranked.penalties.enabled", true)) return;
      Fight fight = fights.get(player.getUniqueId());
      if (fight == null || !fight.competitive || fight.finished) return;
      long now = System.currentTimeMillis();
      Penalty previous = rankedPenalties.get(player.getUniqueId());
      long decayMs = Math.max(1, plugin.getConfig().getLong("ranked.penalties.decay-hours", 24)) * 3600000L;
      int strikes = previous == null || now - previous.lastAt > decayMs ? 1 : Math.min(3, previous.strikes + 1);
      int[] times = {30, 60, 120};
      int seconds = times[strikes - 1];
      rankedPenalties.put(player.getUniqueId(), new Penalty(strikes, now, now + seconds * 1000L));
      savePenalties();
      player.sendMessage("§cYou left an active ranked match. §7Next ranked queue available in §e" + seconds + "s§7.");
   }

   private long activeRankedPenalty(UUID id) {
      Penalty entry = rankedPenalties.get(id);
      return entry == null ? 0L : Math.max(0L, entry.endsAt - System.currentTimeMillis());
   }

   boolean isRankedQueued(Player player) { return rankedWaiting.containsKey(player.getUniqueId()); }
   int rankedWaitingCount(String kit) { return ranked.getOrDefault(kit, new ArrayDeque<>()).size(); }
   String rankedKitStatus(Player player, String kit) {
      Rating current = rating(player.getUniqueId(), kit);
      return "§b" + tier(current) + " §7| §f" + current.elo + " Elo";
   }

   private void ranked(Player player, String[] args) {
      UUID uuid = player.getUniqueId();
      if (!plugin.getConfig().getBoolean("features.ranked", true)
         && (args.length == 0 || !args[0].equalsIgnoreCase("leave") && !args[0].equalsIgnoreCase("top"))) {
         player.sendMessage("§cRanked matchmaking is temporarily disabled."); return;
      }
      if (args.length > 0 && args[0].equalsIgnoreCase("top")) {
         String kit = args.length > 1 ? args[1].toLowerCase(Locale.ROOT)
            : plugin.kits().configuredIds().stream().filter(plugin.kits()::hasKit).findFirst().orElse("");
         player.sendMessage("§5§lRanked Leaderboard §8| §d" + kit);
         ratings.entrySet().stream()
            .map(e -> Map.entry(e.getKey(), e.getValue().get(kit)))
            .filter(e -> e.getValue() != null)
            .sorted((a, b) -> Integer.compare(b.getValue().elo, a.getValue().elo))
            .limit(10)
            .forEachOrdered(e -> {
               String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
               player.sendMessage("§7" + (name == null ? e.getKey() : name) + " §f" + e.getValue().elo + " Elo");
            });
         return;
      }
      if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
         String kit = rankedWaiting.get(uuid);
         if (kit == null) {
            player.sendMessage("§7You are not in Ranked. Use §d/ranked§7 to open the kit selector.");
            return;
         }
         long waited = Math.max(0L, (System.currentTimeMillis() - rankedJoinedAt.getOrDefault(uuid, System.currentTimeMillis())) / 1000L);
         int count = rankedWaitingCount(kit);
         player.sendMessage("§5§lRanked Queue §8| §f" + kit + " §7(" + count + " waiting, " + waited + "s)");
         player.sendMessage(count < 2
            ? "§7Waiting for another player to select the same kit."
            : "§7Checking Elo and a safe Plains arena. §fMatching retries every 5s.");
         player.sendMessage("§7Use §d/ranked leave§7 to exit. Ranked may take longer while preparing a new arena.");
         return;
      }
      if (args.length > 0 && args[0].equalsIgnoreCase("leave")) {
         String old = rankedWaiting.remove(uuid);
         rankedJoinedAt.remove(uuid);
         if (old != null) ranked.getOrDefault(old, new ArrayDeque<>()).remove(uuid);
         player.sendMessage(old == null ? "§cNot in ranked queue." : "§7Left ranked queue.");
         return;
      }
      if (args.length == 0) {
         if (!plugin.maintenance().allowNewMatch(player)) return;
         plugin.menus().openRanked(player);
         return;
      }
      long penalty = activeRankedPenalty(uuid);
      if (penalty > 0) {
         player.sendMessage("§cRanked is temporarily unavailable after leaving an active match: §e" + ((penalty + 999) / 1000) + "s§c.");
         return;
      }
      String kit = args.length > 0 ? args[0].toLowerCase(Locale.ROOT)
         : plugin.kits().configuredIds().stream().filter(plugin.kits()::hasKit).findFirst().orElse("");
      if (!plugin.kits().hasKit(kit)) { player.sendMessage("§cUnknown kit. /ranked <kit>"); return; }
      if (!plugin.kitModes().check(player, kit, "ranked") || !plugin.maintenance().allowNewMatch(player)) return;
      if (fights.containsKey(uuid) || plugin.matchQueue().busy(player) || plugin.combat().tagged(player) || rankedWaiting.containsKey(uuid)) {
         player.sendMessage("§cAlready busy or queued. Use /ranked leave if waiting."); return;
      }
      Deque<UUID> queue = ranked.computeIfAbsent(kit, ignored -> new ArrayDeque<>());
      long now = System.currentTimeMillis();
      int entrantElo = rating(uuid, kit).elo;
      int baseGap = Math.max(0, plugin.getConfig().getInt("ranked.matchmaking.initial-elo-gap", 150));
      int extraPer15s = Math.max(0, plugin.getConfig().getInt("ranked.matchmaking.elo-gap-per-15s", 50));
      int maxGap = Math.max(baseGap, plugin.getConfig().getInt("ranked.matchmaking.maximum-elo-gap", 600));
      // Iterate a snapshot: challenge() clears ranked queue entries as part of
      // the shared match pipeline, so a live Deque iterator would be invalidated.
      for (UUID otherId : List.copyOf(queue)) {
         Player other = Bukkit.getPlayer(otherId);
         if (other == null || !other.isOnline() || plugin.combat().tagged(other)
               || plugin.matchQueue().busy(other) || !kit.equals(rankedWaiting.get(otherId))) {
            queue.remove(otherId);
            rankedWaiting.remove(otherId);
            rankedJoinedAt.remove(otherId);
            continue;
         }
         long waitMillis = Math.max(0L, now - rankedJoinedAt.getOrDefault(otherId, now));
         int gap = (int)Math.min(maxGap, baseGap + (waitMillis / 15000L) * extraPer15s);
         if (!rankedEloCompatible(entrantElo, rating(otherId, kit).elo, gap, waitMillis)) continue;
         if (rankedRespectPing() && !plugin.playerPreferences().pingCompatible(player, other)) continue;
         // Only remove the candidate after a successful match. Failed challenges
         // must not silently discard an eligible player from the ranked queue.
         if (plugin.matchQueue().challenge(other, player, kit, true)) {
            queue.remove(otherId);
            rankedWaiting.remove(otherId);
            rankedJoinedAt.remove(otherId);
            pendingRanked.put(otherId, true);
            pendingRanked.put(uuid, true);
            other.sendMessage("§aRanked match found! §7Kit: §d" + kit + "§7. Preparing a Plains arena...");
            player.sendMessage("§aRanked match found! §7Kit: §d" + kit + "§7. Preparing a Plains arena...");
            return;
         }
      }
      queue.addLast(uuid);
      rankedWaiting.put(uuid, kit);
      rankedJoinedAt.put(uuid, now);
      if (plugin.playerPreferences().allows(player, "queue-notifications")) player.sendMessage(Component.text("Ranked queue joined: " + kit + "  ", NamedTextColor.LIGHT_PURPLE)
         .append(Component.text("[LEAVE QUEUE]", NamedTextColor.RED)
            .clickEvent(ClickEvent.runCommand("/ranked leave"))));
   }

   /** Ranked defaults to not inheriting casual RTPQ ping limits, which can silently block small queues. */
   private boolean rankedRespectPing() {
      return plugin.getConfig().getBoolean("ranked.matchmaking.respect-ping-preferences", false);
   }

   /** Avoid two players waiting forever at different Elo in a low-population server. */
   private boolean rankedEloCompatible(int aElo, int bElo, int gap, long waitMillis) {
      if (Math.abs((long)aElo - bElo) <= gap) return true;
      int relaxAfter = plugin.getConfig().getInt("ranked.matchmaking.allow-any-elo-after-seconds", 60);
      return relaxAfter > 0 && waitMillis >= (long)relaxAfter * 1000L;
   }

   /** Re-check waiting pairs as Elo windows widen, even when nobody new joins. */
   void retryRankedMatches() {
      if (plugin.maintenance().isActive()) return;
      if (!plugin.getConfig().getBoolean("features.ranked", true)) return;
      int base = Math.max(0, plugin.getConfig().getInt("ranked.matchmaking.initial-elo-gap", 150));
      int increase = Math.max(0, plugin.getConfig().getInt("ranked.matchmaking.elo-gap-per-15s", 50));
      int ceiling = Math.max(base, plugin.getConfig().getInt("ranked.matchmaking.maximum-elo-gap", 600));
      long now = System.currentTimeMillis();
      for (Map.Entry<String, Deque<UUID>> entry : ranked.entrySet()) {
         String kit = entry.getKey();
         if (!plugin.kitModes().allowed(kit, "ranked")) continue;
         Deque<UUID> queue = entry.getValue();
         boolean matched;
         do {
            matched = false;
            List<UUID> waiting = List.copyOf(queue);
            for (int i = 0; i < waiting.size() && !matched; i++) {
               UUID oneId = waiting.get(i);
               Player one = Bukkit.getPlayer(oneId);
               if (!eligibleRankedCandidate(one, oneId, kit)) {
                  clearWaiting(oneId);
                  continue;
               }
               for (int j = i + 1; j < waiting.size(); j++) {
                  UUID twoId = waiting.get(j);
                  Player two = Bukkit.getPlayer(twoId);
                  if (!eligibleRankedCandidate(two, twoId, kit)) {
                     clearWaiting(twoId);
                     continue;
                  }
                  long oldestWait = Math.max(now - rankedJoinedAt.getOrDefault(oneId, now),
                     now - rankedJoinedAt.getOrDefault(twoId, now));
                  int gap = (int)Math.min(ceiling, base + Math.max(0L, oldestWait / 15000L) * increase);
                  if (!rankedEloCompatible(rating(oneId, kit).elo, rating(twoId, kit).elo, gap, oldestWait)) continue;
                  if (rankedRespectPing() && !plugin.playerPreferences().pingCompatible(one, two)) continue;
                  if (plugin.matchQueue().challenge(one, two, kit, true)) {
                     pendingRanked.put(oneId, true);
                     pendingRanked.put(twoId, true);
                     one.sendMessage("§aRanked match found! §7Kit: §d" + kit + "§7. Preparing a Plains arena...");
                     two.sendMessage("§aRanked match found! §7Kit: §d" + kit + "§7. Preparing a Plains arena...");
                     matched = true;
                     break;
                  }
               }
            }
         } while (matched);
      }
   }

   private boolean eligibleRankedCandidate(Player player, UUID id, String kit) {
      return player != null && player.isOnline() && kit.equals(rankedWaiting.get(id))
         && !plugin.combat().tagged(player) && !plugin.matchQueue().busy(player)
         && !fights.containsKey(id) && activeRankedPenalty(id) == 0L;
   }

   private void stats(Player player, String[] args) {
      Player target = args.length == 0 ? player : Bukkit.getPlayer(args[0]);
      if (target == null) { player.sendMessage("§cPlayer not online. Use /stats <online player>."); return; }
      UUID id = target.getUniqueId();
      player.sendMessage("§5§lRiftPvP §d» §f" + target.getName());
      int kills = plugin.sidebar().kills(id), deaths = plugin.sidebar().deaths(id);
      player.sendMessage("§7Kills: §a" + kills + " §7Deaths: §c" + deaths + " §7K/D: §f" + String.format(Locale.ROOT, "%.2f", kills / (double)Math.max(1, deaths)));
      player.sendMessage("§7Playtime: §f" + StatsSidebar.formatPlaytime(plugin.sidebar().playtimeMillis(id)));
      int wins = 0, losses = 0;
      for (Rating rating : ratings.getOrDefault(id, Map.of()).values()) {
         wins += rating.wins;
         losses += rating.losses;
      }
      player.sendMessage("§7Duels won: §a" + wins + " §7Duels lost: §c" + losses);
      for (Map.Entry<String, Rating> entry : ratings.getOrDefault(id, Map.of()).entrySet()) {
         Rating rating = entry.getValue();
         player.sendMessage("§d" + entry.getKey() + " §7- Tier: §b" + tier(rating)
            + " §7Elo: §f" + rating.elo + " §7W/L: §a" + rating.wins + "§7/§c" + rating.losses);
      }
   }

   private static final String[] TIERS = {"LT5", "HT5", "LT4", "HT4", "LT3", "HT3", "LT2", "HT2", "LT1", "HT1"};

   /** Server-local tiers, not certification from third-party MCTiers organisations. */
   private String tier(Rating rating) {
      int placements = Math.max(1, plugin.getConfig().getInt("ranked.placements", 5));
      int games = rating.wins + rating.losses;
      if (games < placements) return "Unranked " + games + "/" + placements;
      String tier = TIERS[0];
      int base = plugin.getConfig().getInt("ranked.tier-base-elo", 1000);
      int step = Math.max(25, plugin.getConfig().getInt("ranked.tier-step", 100));
      for (int i = 1; i < TIERS.length; i++) if (rating.elo >= base + i * step) tier = TIERS[i];
      return tier;
   }

   private void showRank(Player sender, String[] args) {
      Player target = args.length == 0 ? sender : Bukkit.getPlayer(args[0]);
      if (target == null) { sender.sendMessage("§cPlayer not online. Usage: /rank [player]"); return; }
      sender.sendMessage("§5§lRiftPvP §dRanked tiers for §f" + target.getName());
      Map<String, Rating> perKit = ratings.getOrDefault(target.getUniqueId(), Map.of());
      if (perKit.isEmpty()) sender.sendMessage("§7No ranked matches yet. Start with §d/ranked <kit>§7.");
      for (String kit : plugin.kits().configuredIds()) {
         Rating rating = perKit.get(kit);
         if (rating != null) sender.sendMessage("§d" + plugin.kits().name(kit) + " §7» §b" + tier(rating)
            + " §7(" + rating.elo + " Elo, " + rating.wins + "W/" + rating.losses + "L)");
      }
   }

   /** Called only after both players have teleported and received their kits. */
   void started(Player first, Player second, String kit) {
      boolean aRanked = Boolean.TRUE.equals(pendingRanked.remove(first.getUniqueId()));
      boolean bRanked = Boolean.TRUE.equals(pendingRanked.remove(second.getUniqueId()));
      boolean competitive = aRanked && bRanked;
      Fight fight = new Fight(first, second, kit, competitive,
         plugin.matchLedger().started(first, second, kit, competitive));
      plugin.matchRecovery().started(first.getUniqueId(), second.getUniqueId());
      plugin.fightAnalytics().start(first.getUniqueId(), second.getUniqueId());
      fights.put(first.getUniqueId(), fight);
      fights.put(second.getUniqueId(), fight);
      recent.put(first.getUniqueId(), new Recent(second.getUniqueId(), kit));
      recent.put(second.getUniqueId(), new Recent(first.getUniqueId(), kit));
   }

   void clearPending(UUID a, UUID b) {
      pendingRanked.remove(a);
      if (b != null) pendingRanked.remove(b);
   }

   void expireMatch(UUID id) {
      Fight fight = fights.remove(id);
      if (fight == null || fight.finished) return;
      fight.finished = true;
      plugin.fightAnalytics().discard(fight.a, fight.b);
      fights.remove(fight.a);
      fights.remove(fight.b);
      for (UUID member : List.of(fight.a, fight.b)) {
         Player p = Bukkit.getPlayer(member);
         if (p != null) p.sendMessage("§7Your match expired without a result.");
      }
   }

   void clearWaiting(UUID id) {
      String kit = rankedWaiting.remove(id);
      rankedJoinedAt.remove(id);
      if (kit != null) ranked.getOrDefault(kit, new ArrayDeque<>()).remove(id);
   }

   void lobby(Player player) {
      UUID id = player.getUniqueId();
      explicitRankedAbandon(player);
      clearWaiting(id);
      if (fights.containsKey(id)) finish(id, true);
      Spectator old = spectators.remove(id);
      if (old != null) player.setGameMode(old.mode);
   }

   private void finish(UUID loser, boolean abandoned) {
      Fight fight = fights.get(loser);
      if (fight == null || fight.finished) return;
      fight.finished = true;
      UUID winner = fight.a.equals(loser) ? fight.b : fight.a;
      Player winnerPlayer = Bukkit.getPlayer(winner);
      Player loserPlayer = Bukkit.getPlayer(loser);
      ItemStack[] loserEnd = loserPlayer != null ? copy(loserPlayer.getInventory().getArmorContents()) : new ItemStack[4];
      ItemStack[] winnerEnd = winnerPlayer != null ? copy(winnerPlayer.getInventory().getArmorContents()) : new ItemStack[4];
      int winnerHealth = winnerPlayer != null ? (int)Math.ceil(winnerPlayer.getHealth()) : 0;
      long duration = Math.max(0, (System.nanoTime() - fight.startedAt) / 1000000000L);
      FightAnalytics.FightStats aStats = plugin.fightAnalytics().snapshot(fight.a);
      FightAnalytics.FightStats bStats = plugin.fightAnalytics().snapshot(fight.b);
      fights.remove(fight.a);
      fights.remove(fight.b);
      plugin.fightAnalytics().finished(fight.matchId, fight.a, fight.b, winner, fight.kit, fight.competitive);
      plugin.matchLedger().finished(fight.matchId, winner, loser, abandoned, duration);
      boolean suspiciousRepeat = fight.competitive && plugin.rankedIntegrity().finished(
         fight.matchId, fight.a, fight.b, fight.kit, abandoned);
      plugin.matchRecovery().finished(fight.a, fight.b);
      for (Map.Entry<UUID, Spectator> entry : new HashMap<>(spectators).entrySet()) {
         if (!entry.getValue().target.equals(fight.a) && !entry.getValue().target.equals(fight.b)) continue;
         Player watcher = Bukkit.getPlayer(entry.getKey());
         if (watcher == null || !watcher.isOnline()) { spectators.remove(entry.getKey()); continue; }
         unspectate(watcher);
         watcher.sendMessage("§7That match has finished. Returned to your previous location.");
      }
      int aDelta = 0, bDelta = 0;
      String oldWinnerTier = tier(rating(winner, fight.kit));
      String oldLoserTier = tier(rating(loser, fight.kit));
      if (fight.competitive && !suspiciousRepeat) {
         Rating w = rating(winner, fight.kit), l = rating(loser, fight.kit);
         double expected = 1.0 / (1.0 + Math.pow(10.0, (l.elo - w.elo) / 400.0));
         int gain = Math.max(1, (int)Math.round(32 * (1 - expected)));
         w.elo += gain;
         l.elo = Math.max(100, l.elo - gain);
         aDelta = winner.equals(fight.a) ? gain : -gain;
         bDelta = winner.equals(fight.b) ? gain : -gain;
      }
      // Only competitive matches affect ranked W/L and placement progress.
      // Casual duels and RTP queues must never award ranked placements.
      if (fight.competitive) {
         rating(winner, fight.kit).wins++;
         rating(loser, fight.kit).losses++;
         save();
      }
      if (suspiciousRepeat) {
         if (winnerPlayer != null) winnerPlayer.sendMessage("§eUnusually frequent rematches: Elo change held for staff review.");
         if (loserPlayer != null) loserPlayer.sendMessage("§eUnusually frequent rematches: Elo change held for staff review.");
      }
      if (fight.competitive) {
         String promoted = tier(rating(winner, fight.kit));
         String demoted = tier(rating(loser, fight.kit));
         if (winnerPlayer != null && !promoted.equals(oldWinnerTier)) winnerPlayer.sendMessage("§dRank update: §f" + oldWinnerTier + " §7→ §a" + promoted);
         if (loserPlayer != null && !demoted.equals(oldLoserTier)) loserPlayer.sendMessage("§dRank update: §f" + oldLoserTier + " §7→ §c" + demoted);
      }
      if (fight.competitive) plugin.discord().send("ranked-results", fight.aName + " vs " + fight.bName
         + " | " + fight.kit + " | winner " + (winner.equals(fight.a) ? fight.aName : fight.bName)
         + " | match " + fight.matchId + (suspiciousRepeat ? " | Elo review" : ""));
      // Keep a single immutable result snapshot for both players, including actual fight
      // statistics and the finalized Elo deltas. The GUI never calculates fake metrics.
      Result result = new Result(fight, winner, abandoned, winnerHealth, loser, loserEnd,
         winnerEnd, duration, aStats, bStats, aDelta, bDelta, suspiciousRepeat);
      results.put(fight.a, result);
      results.put(fight.b, result);
      if (winnerPlayer != null && winnerPlayer.isOnline()) {
         winnerPlayer.sendMessage("§aVictory! §7Fight lasted §f" + result.duration + "s" + (fight.competitive ? " §d(Elo " + ((winner.equals(fight.a) ? aDelta : bDelta) > 0 ? "+" : "") + (winner.equals(fight.a) ? aDelta : bDelta) + ")" : "") + ". §f/fightresult");
         Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!winnerPlayer.isOnline()) return;
            sendPostFightActions(winnerPlayer, fight);
            resultMenu(winnerPlayer);
         }, 25L);
      }
      if (loserPlayer != null && loserPlayer.isOnline()) {
         loserPlayer.sendMessage("§cDefeat! §7Use §f/fightresult §7after respawning to inspect both armour sets.");
      }
   }

   private Rating rating(UUID player, String kit) {
      return ratings.computeIfAbsent(player, ignored -> new HashMap<>())
         .computeIfAbsent(kit, ignored -> new Rating(0, 0, 1000));
   }

   private void save() {
      YamlConfiguration data = new YamlConfiguration();
      for (Map.Entry<UUID, Map<String, Rating>> entry : ratings.entrySet()) {
         for (Map.Entry<String, Rating> kit : entry.getValue().entrySet()) {
            String path = "players." + entry.getKey() + "." + kit.getKey() + ".";
            data.set(path + "wins", kit.getValue().wins);
            data.set(path + "losses", kit.getValue().losses);
            data.set(path + "elo", kit.getValue().elo);
         }
      }
      try { data.save(recordsFile); }
      catch (IOException e) { plugin.getLogger().warning("Could not save competitive.yml: " + e.getMessage()); }
   }

   @EventHandler(priority = EventPriority.LOWEST)
   public void death(PlayerDeathEvent event) {
      finish(event.getEntity().getUniqueId(), false);
   }

   @EventHandler(priority = EventPriority.LOWEST)
   public void quit(PlayerQuitEvent event) {
      UUID id = event.getPlayer().getUniqueId();
      // MatchQueue.onQuit already tries to pause this fight. If pause is active,
      // do not award a premature win or delete the saved match state.
      Fight departingFight = fights.get(id);
      if (departingFight != null && !plugin.matchReconnect().pending(id)) {
         // Reconnect feature may be explicitly disabled, or preparation failed.
         UUID opponent = departingFight.a.equals(id) ? departingFight.b : departingFight.a;
         finish(id, true);
         plugin.matchRecovery().started(id, opponent);
         Player onlineOpponent = Bukkit.getPlayer(opponent);
         if (onlineOpponent != null && onlineOpponent.isOnline())
            plugin.matchRecovery().recoverSoon(onlineOpponent);
      }
      String kit = rankedWaiting.remove(id);
      rankedJoinedAt.remove(id);
      if (kit != null) ranked.getOrDefault(kit, new ArrayDeque<>()).remove(id);
      Spectator old = spectators.remove(id);
      if (old != null) {
         event.getPlayer().setGameMode(old.mode);
         event.getPlayer().teleport(old.location);
      }
      leaveParty(id);
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void damage(EntityDamageByEntityEvent event) {
      if (!(event.getEntity() instanceof Player victim)) return;
      Player attacker = null;
      Entity direct = event.getDamager();
      if (direct instanceof Player p) attacker = p;
      else if (direct instanceof Projectile projectile) {
         ProjectileSource source = projectile.getShooter();
         if (source instanceof Player p) attacker = p;
      }
      if (attacker == null) return;
      Fight fight = fights.get(victim.getUniqueId());
      if (fight != null && fight == fights.get(attacker.getUniqueId())) {
         fight.damage.merge(attacker.getUniqueId(), event.getFinalDamage(), Double::sum);
         plugin.fightAnalytics().hit(attacker, victim, event.getFinalDamage(), direct instanceof Player);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void healEvent(EntityRegainHealthEvent event) {
      if (event.getEntity() instanceof Player player && fights.containsKey(player.getUniqueId()))
         plugin.fightAnalytics().healed(player.getUniqueId(), event.getAmount());
   }

   @EventHandler
   public void respawn(PlayerRespawnEvent event) {
      Player p = event.getPlayer();
      if (results.containsKey(p.getUniqueId()))
         Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            Result latest = results.get(p.getUniqueId());
            if (latest == null) return;
            sendPostFightActions(p, latest.fight);
            resultMenu(p);
         }, 10L);
   }

   private static ItemStack[] copy(ItemStack[] items) {
      return Arrays.stream(items).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new);
   }

   private static int durability(ItemStack item) {
      if (item == null || item.getType().isAir()) return 0;
      int max = item.getType().getMaxDurability();
      if (max <= 0) return 100;
      if (item.getItemMeta() instanceof Damageable meta)
         return Math.max(0, (int)Math.round(100.0 * (max - meta.getDamage()) / max));
      return 100;
   }

   /** 6-row results screen: clear outcome, real comparison stats and safe actions. */
   private void resultMenu(Player player) {
      Result result = results.get(player.getUniqueId());
      if (result == null) { player.sendMessage("§cNo finished fight to inspect yet."); return; }
      boolean victory = result.winner.equals(player.getUniqueId());
      Fight fight = result.fight;
      ResultHolder holder = new ResultHolder(player.getUniqueId(), result);
      holder.inventory = Bukkit.createInventory(holder, 54,
         "§5§lRiftPvP §8| " + (victory ? "§aVictory" : "§cDefeat"));
      Inventory inv = holder.inventory;
      for (int i = 0; i < 54; i++)
         inv.setItem(i, item(Material.BLACK_STAINED_GLASS_PANE, "§8 "));
      for (int i = 0; i < 9; i++) inv.setItem(i, item(Material.PURPLE_STAINED_GLASS_PANE, "§8 "));
      for (int i = 45; i < 54; i++) inv.setItem(i, item(Material.GRAY_STAINED_GLASS_PANE, "§8 "));

      String winnerName = result.winner.equals(fight.a) ? fight.aName : fight.bName;
      String loserName = result.loser.equals(fight.a) ? fight.aName : fight.bName;
      inv.setItem(4, item(victory ? Material.EMERALD_BLOCK : Material.REDSTONE_BLOCK,
         victory ? "§a§lVICTORY" : "§c§lDEFEAT",
         "§7Winner: §f" + winnerName, "§7Loser: §f" + loserName,
         "§7Kit: §d" + plugin.kits().name(fight.kit),
         "§7Mode: §f" + (fight.competitive ? "Ranked" : "Unranked / Duel")));
      inv.setItem(11, playerHead(fight.a, fight.aName, player.getUniqueId(), result.winner));
      inv.setItem(15, playerHead(fight.b, fight.bName, player.getUniqueId(), result.winner));
      inv.setItem(13, item(Material.DIAMOND_SWORD, "§d§lMatch Summary",
         "§7Match: §f" + (fight.matchId == null ? "Unavailable" : fight.matchId),
         "§7Kit: §f" + fight.kit,
         "§7Mode: §f" + (fight.competitive ? "Ranked" : "Casual"),
         "§7Finish: §f" + (result.abandoned ? "Disconnect / Forfeit" : "Death")));
      inv.setItem(22, item(Material.CLOCK, "§d§lMatch Duration",
         "§f" + formatDuration(result.duration),
         "§7Winner remaining HP: §c" + result.winnerHealth));

      showFightStats(inv, 20, 21, 29, 30, fight.aName, result.aStats);
      showFightStats(inv, 24, 23, 33, 32, fight.bName, result.bStats);
      fillArmorSummary(inv, 38, fight.aName, fight.startA,
         fight.a.equals(result.loser) ? result.loserEnd : result.winnerEnd);
      fillArmorSummary(inv, 42, fight.bName, fight.startB,
         fight.b.equals(result.loser) ? result.loserEnd : result.winnerEnd);
      if (fight.competitive) {
         int change = fight.a.equals(player.getUniqueId()) ? result.aDelta : result.bDelta;
         inv.setItem(40, item(Material.GOLD_INGOT, "§e§lRanked Result",
            result.eloHeld ? "§eElo change held for review" :
               "§7Your Elo change: " + (change >= 0 ? "§a+" : "§c") + change,
            "§7Your current Elo: §f" + rating(player.getUniqueId(), fight.kit).elo,
            "§7Your tier: §b" + tier(rating(player.getUniqueId(), fight.kit))));
      } else inv.setItem(40, item(Material.PAPER, "§b§lCasual Match", "§7No Elo changes in casual fights"));
      inv.setItem(45, item(Material.LIME_DYE, "§a§lRematch", "§7Invite this opponent to a casual duel"));
      inv.setItem(47, item(Material.ENDER_EYE, "§b§lRequeue",
         "§7" + (fight.competitive ? "Join Ranked with the same kit" : "Queue with the same kit")));
      inv.setItem(49, item(Material.HEART_OF_THE_SEA, "§d§lReturn to Lobby", "§7Leave the results screen"));
      inv.setItem(51, item(Material.SPYGLASS, "§e§lSpectate Opponent", "§7If they are in a new match"));
      inv.setItem(53, item(Material.BARRIER, "§cClose", "§7Close this menu"));
      player.openInventory(inv);
   }

   private static ItemStack playerHead(UUID player, String name, UUID viewer, UUID winner) {
      ItemStack head = item(Material.PLAYER_HEAD, "§f§l" + name,
         player.equals(viewer) ? "§bYou" : "§7Opponent",
         player.equals(winner) ? "§aWinner" : "§cDefeated");
      if (head.getItemMeta() instanceof SkullMeta meta) {
         meta.setOwningPlayer(Bukkit.getOfflinePlayer(player));
         head.setItemMeta(meta);
      }
      return head;
   }

   private static String formatDuration(long seconds) {
      return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
   }

   private static String decimal(double value) { return String.format(Locale.ROOT, "%.1f", value); }

   private void showFightStats(Inventory inv, int hitsSlot, int damageSlot, int healSlot, int comboSlot,
                               String name, FightAnalytics.FightStats stats) {
      inv.setItem(hitsSlot, item(Material.IRON_SWORD, "§f" + name + " §7- Hits", "§f" + stats.hits()));
      inv.setItem(damageSlot, item(Material.NETHERITE_SWORD, "§f" + name + " §7- Damage", "§c" + decimal(stats.damage())));
      inv.setItem(healSlot, item(Material.GOLDEN_APPLE, "§f" + name + " §7- Healing", "§a" + decimal(stats.healing())));
      inv.setItem(comboSlot, item(Material.BLAZE_POWDER, "§f" + name + " §7- Best Combo", "§e" + stats.bestCombo()));
   }

   private void fillArmorSummary(Inventory inv, int slot, String name, ItemStack[] before, ItemStack[] after) {
      String[] parts = {"Boots", "Leggings", "Chestplate", "Helmet"};
      java.util.List<String> lore = new java.util.ArrayList<>();
      for (int i = 0; i < parts.length; i++) {
         ItemStack initial = before[i];
         if (initial == null || initial.getType().isAir()) continue;
         lore.add("§7" + parts[i] + ": §a" + durability(initial) + "% §7→ §e" + durability(after[i]) + "%");
      }
      if (lore.isEmpty()) lore.add("§7No armour equipped");
      inv.setItem(slot, item(Material.ARMOR_STAND, "§d" + name + " §f- Armour", lore.toArray(new String[0])));
   }

   private static ItemStack item(Material material, String name, String... lore) {
      ItemStack item = new ItemStack(material);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(name);
         meta.setLore(Arrays.asList(lore));
         item.setItemMeta(meta);
      }
      return item;
   }

   private String requeueCommand(Player player, Fight fight) {
      String kit = fight.kit;
      if (fight.competitive) return "ranked " + kit;
      if (!CustomKits.isCustom(kit)) return "rtpq " + kit;
      String ownedPrefix = "custom:" + player.getUniqueId() + ":";
      if (kit.startsWith(ownedPrefix)) return "customkit host " + kit.substring(ownedPrefix.length());
      return "customkit queue";
   }

   private void sendPostFightActions(Player player, Fight fight) {
      player.sendMessage(Component.text("✦ RiftPvP  ", NamedTextColor.LIGHT_PURPLE)
         .append(Component.text("[REMATCH] ", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/rematch")))
         .append(Component.text("[REQUEUE] ", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/" + requeueCommand(player, fight))))
         .append(Component.text("[RESULTS] ", NamedTextColor.YELLOW).clickEvent(ClickEvent.runCommand("/fightresult")))
         .append(Component.text("[LOBBY]", NamedTextColor.LIGHT_PURPLE).clickEvent(ClickEvent.runCommand("/lobby"))));
   }

   @EventHandler
   public void resultClick(InventoryClickEvent event) {
      if (!(event.getView().getTopInventory().getHolder() instanceof ResultHolder holder)) return;
      event.setCancelled(true);
      if (!(event.getWhoClicked() instanceof Player player)) return;
      if (event.getClickedInventory() != event.getView().getTopInventory()) return;
      Result result = results.get(player.getUniqueId());
      if (result == null || holder.result != result || !holder.viewer.equals(player.getUniqueId())) return;
      if (event.getRawSlot() == 53) { player.closeInventory(); return; }
      String cmd = switch (event.getRawSlot()) {
         case 45 -> "rematch";
         case 47 -> requeueCommand(player, result.fight);
         case 49 -> "lobby";
         case 51 -> {
            UUID other = result.fight.a.equals(player.getUniqueId()) ? result.fight.b : result.fight.a;
            Player opponent = Bukkit.getPlayer(other);
            yield opponent != null && fights.containsKey(other) ? "spectate " + opponent.getName() : null;
         }
         default -> null;
      };
      if (cmd != null) Bukkit.getScheduler().runTask(plugin, () -> {
         if (!player.isOnline()) return;
         player.closeInventory();
         player.performCommand(cmd);
      });
   }
   @EventHandler
   public void resultDrag(InventoryDragEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof ResultHolder) event.setCancelled(true);
   }

   private void spectate(Player player, String[] args) {
      if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
         player.sendMessage("§dActive fighters: §f" + fights.keySet().stream().distinct().map(Bukkit::getPlayer)
            .filter(p -> p != null && p.isOnline()).map(Player::getName).limit(20).toList());
         player.sendMessage("§7/spectate <player> | next | prev | list"); return;
      }
      if (args[0].equalsIgnoreCase("next") || args[0].equalsIgnoreCase("prev")) {
         Spectator old = spectators.get(player.getUniqueId());
         if (old == null) { player.sendMessage("§cYou are not spectating. /spectate list"); return; }
         List<UUID> candidates = fights.keySet().stream().filter(id -> Bukkit.getPlayer(id) != null).sorted().toList();
         if (candidates.isEmpty()) { player.sendMessage("§cNo active fights."); return; }
         int index = candidates.indexOf(old.target);
         int direction = args[0].equalsIgnoreCase("next") ? 1 : -1;
         Player next = Bukkit.getPlayer(candidates.get(Math.floorMod(index + direction, candidates.size())));
         if (next != null) {
            spectators.put(player.getUniqueId(), new Spectator(old.location, old.mode, next.getUniqueId()));
            player.teleportAsync(next.getLocation());
            player.sendMessage("§dNow spectating §f" + next.getName() + " §7(/spectate next|prev, /unspectate)");
         }
         return;
      }
      Player target = Bukkit.getPlayer(args[0]);
      if (target == null || !fights.containsKey(target.getUniqueId())) { player.sendMessage("§cThat player isn't in an active fight."); return; }
      if (fights.containsKey(player.getUniqueId()) || plugin.combat().tagged(player)) { player.sendMessage("§cCannot spectate during combat."); return; }
      if (plugin.kitRoom().editing(player)) {
         player.sendMessage("§cClose the Kit Editor before spectating."); return;
      }
      if (spectators.containsKey(player.getUniqueId())) {
         player.sendMessage("§cLeave your current spectator session first with /unspectate."); return;
      }
      spectators.put(player.getUniqueId(), new Spectator(player.getLocation().clone(), player.getGameMode(), target.getUniqueId()));
      player.setGameMode(GameMode.SPECTATOR);
      player.teleportAsync(target.getLocation());
      player.sendMessage(Component.text("Spectating " + target.getName() + "  ", NamedTextColor.LIGHT_PURPLE)
         .append(Component.text("[LEAVE SPECTATE]", NamedTextColor.GREEN)
            .clickEvent(ClickEvent.runCommand("/unspectate"))));
   }

   private void unspectate(Player player) {
      Spectator old = spectators.remove(player.getUniqueId());
      if (old == null) { player.sendMessage("§cYou're not spectating."); return; }
      player.teleportAsync(old.location).thenAccept(success -> {
         if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline()) player.setGameMode(old.mode); });
      });
      player.sendMessage("§aStopped spectating.");
   }

   List<Player> activeFighters() {
      return fights.keySet().stream().map(Bukkit::getPlayer)
          .filter(player -> player != null && player.isOnline() && hasActiveFight(player.getUniqueId()))
          .sorted(java.util.Comparator.comparing(Player::getName))
          .limit(45).toList();
   }

   boolean hasActiveFight(UUID id) {
      Fight fight = fights.get(id);
      return fight != null && !fight.finished;
   }

   boolean inSameParty(Player first, Player second) {
      Party party = membership.get(first.getUniqueId());
      return party != null && party.members.contains(second.getUniqueId());
   }

   private void party(Player player, String[] args) {
      UUID id = player.getUniqueId();
      if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
         Party party = membership.get(id);
         if (party == null) player.sendMessage("§7No party. §d/party create §7| §d/party invite <player> §7| §d/party accept §7| §d/party leave");
         else player.sendMessage("§dParty members: §f" + party.members.stream().map(x -> { Player p = Bukkit.getPlayer(x); return p == null ? x.toString() : p.getName(); }).toList());
         return;
      }
      switch (args[0].toLowerCase(Locale.ROOT)) {
         case "create" -> {
            if (membership.containsKey(id)) { player.sendMessage("§cLeave your current party first."); return; }
            Party party = new Party(id);
            membership.put(id, party);
            player.sendMessage("§aParty created! §7Invite with /party invite <player>.");
         }
         case "invite" -> {
            Party party = membership.get(id);
            if (party == null || !party.leader.equals(id)) { player.sendMessage("§cOnly party leaders can invite. /party create"); return; }
            Player target = args.length > 1 ? Bukkit.getPlayer(args[1]) : null;
            if (target == null || membership.containsKey(target.getUniqueId()) || !plugin.playerPreferences().allows(target, "parties")) { player.sendMessage("§cChoose an online player who isn't in a party."); return; }
            partyInvites.put(target.getUniqueId(), id);
            target.sendMessage(Component.text(player.getName() + " invited you to a party!  ", NamedTextColor.LIGHT_PURPLE)
               .append(Component.text("[ACCEPT]", NamedTextColor.GREEN)
                  .clickEvent(ClickEvent.runCommand("/party accept"))));
            player.sendMessage("§aParty invitation sent.");
         }
         case "accept" -> {
            if (membership.containsKey(id)) { player.sendMessage("§cLeave your party first."); return; }
            UUID leader = partyInvites.remove(id);
            Party party = leader == null ? null : membership.get(leader);
            if (party == null) { player.sendMessage("§cNo pending party invitation."); return; }
            party.members.add(id);
            membership.put(id, party);
            player.sendMessage("§aJoined the party.");
         }
         case "leave" -> { leaveParty(id); player.sendMessage("§7Left the party."); }
         default -> player.sendMessage("§cUsage: /party [create|invite <player>|accept|leave|list]");
      }
   }

   private void leaveParty(UUID id) {
      Party party = membership.remove(id);
      if (party == null) return;
      party.members.remove(id);
      if (party.leader.equals(id) || party.members.isEmpty()) {
         for (UUID member : party.members) {
            membership.remove(member);
            Player p = Bukkit.getPlayer(member);
            if (p != null) p.sendMessage("§7Your party disbanded.");
         }
         party.members.clear();
      }
   }

   void close() {
      for (Map.Entry<UUID, Spectator> entry : new HashMap<>(spectators).entrySet()) {
         Player player = Bukkit.getPlayer(entry.getKey());
         if (player != null && player.isOnline()) {
            player.setGameMode(entry.getValue().mode);
            player.teleport(entry.getValue().location);
         }
      }
      spectators.clear();
      save();
      savePenalties();
   }

   private record Invite(UUID from, String kit, long expires, UUID token) {}
   private record Recent(UUID other, String kit) {}
   private record Spectator(Location location, GameMode mode, UUID target) {}
   private static final class Rating {
      private int wins, losses, elo;
      private Rating(int wins, int losses, int elo) { this.wins = wins; this.losses = losses; this.elo = elo; }
   }
   private static final class Party {
      private final UUID leader;
      private final Set<UUID> members = new HashSet<>();
      private Party(UUID leader) { this.leader = leader; members.add(leader); }
   }
   private static final class Fight {
      private final UUID a, b;
      private final String aName, bName, kit;
      private final boolean competitive;
      private final String matchId;
      private final ItemStack[] startA, startB;
      private final long startedAt = System.nanoTime();
      private final Map<UUID, Double> damage = new HashMap<>();
      private boolean finished;
      private Fight(Player first, Player second, String kit, boolean competitive, String matchId) {
         a = first.getUniqueId(); b = second.getUniqueId();
         aName = first.getName(); bName = second.getName();
         this.kit = kit; this.competitive = competitive; this.matchId = matchId;
         startA = copy(first.getInventory().getArmorContents());
         startB = copy(second.getInventory().getArmorContents());
      }
   }
   private record Result(Fight fight, UUID winner, boolean abandoned, int winnerHealth, UUID loser,
                         ItemStack[] loserEnd, ItemStack[] winnerEnd, long duration,
                         FightAnalytics.FightStats aStats, FightAnalytics.FightStats bStats,
                         int aDelta, int bDelta, boolean eloHeld) {}
   private static final class ResultHolder implements InventoryHolder {
      private final UUID viewer;
      private final Result result;
      private Inventory inventory;
      private ResultHolder(UUID viewer, Result result) { this.viewer = viewer; this.result = result; }
      public Inventory getInventory() { return inventory; }
   }
}
