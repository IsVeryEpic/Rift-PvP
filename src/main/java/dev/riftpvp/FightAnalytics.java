package dev.riftpvp;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Server-side counters for completed managed fights. Does not claim to detect client-side combos. */
final class FightAnalytics {
   private final RiftPvpPlugin plugin;
   private final File file;
   private final YamlConfiguration totals;
   private final Map<UUID, Fighter> active = new HashMap<>();
   private static final class Fighter {
      double damage, healing;
      int hits, criticalCandidates, combo, bestCombo;
      long lastHit;
      UUID lastTarget;
   }

   FightAnalytics(RiftPvpPlugin plugin) {
      this.plugin = plugin;
      file = new File(plugin.getDataFolder(), "fight-analytics.yml");
      totals = YamlConfiguration.loadConfiguration(file);
   }

   void start(UUID a, UUID b) {
      active.put(a, new Fighter()); active.put(b, new Fighter());
   }

   void hit(Player attacker, Player victim, double damage, boolean melee) {
      Fighter state = active.get(attacker.getUniqueId());
      if (state == null || !active.containsKey(victim.getUniqueId()) || damage <= 0) return;
      long now = System.currentTimeMillis();
      state.hits++;
      state.damage += damage;
      if (victim.getUniqueId().equals(state.lastTarget) && now - state.lastHit <= 2400L) state.combo++;
      else state.combo = 1;
      state.lastHit = now;
      state.lastTarget = victim.getUniqueId();
      state.bestCombo = Math.max(state.bestCombo, state.combo);
      // Approximation: legitimate falling melee hit; vanilla enchantments/conditions may differ.
      if (melee && attacker.getFallDistance() > 0.0F && !attacker.isOnGround()
            && !attacker.isGliding() && !attacker.isSwimming()
            && !attacker.hasPotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS)) state.criticalCandidates++;
   }

   void healed(UUID player, double amount) {
      Fighter state = active.get(player);
      if (state != null && amount > 0) state.healing += amount;
   }

   /** Snapshot active fight numbers before they are committed and cleared. */
   record FightStats(int hits, double damage, double healing, int bestCombo) {}

   FightStats snapshot(UUID playerId) {
      Fighter fighter = active.get(playerId);
      return fighter == null ? new FightStats(0, 0, 0, 0)
         : new FightStats(fighter.hits, fighter.damage, fighter.healing, fighter.bestCombo);
   }

   void finished(String matchId, UUID a, UUID b, UUID winner, String kit, boolean ranked) {
      Fighter fa = active.remove(a), fb = active.remove(b);
      if (fa == null || fb == null) return;
      commit(matchId, a, fa, a.equals(winner), kit, ranked);
      commit(matchId, b, fb, b.equals(winner), kit, ranked);
      try { totals.save(file); }
      catch (IOException error) { plugin.getLogger().warning("Could not save fight analytics: " + error.getMessage()); }
   }

   private void commit(String matchId, UUID id, Fighter f, boolean won, String kit, boolean ranked) {
      String path = "players." + id + ".kits." + kit.replace('.', '_') + ".";
      totals.set(path + "matches", totals.getInt(path + "matches") + 1);
      totals.set(path + (won ? "wins" : "losses"), totals.getInt(path + (won ? "wins" : "losses")) + 1);
      totals.set(path + "hits", totals.getLong(path + "hits") + f.hits);
      totals.set(path + "damage", totals.getDouble(path + "damage") + f.damage);
      totals.set(path + "healing", totals.getDouble(path + "healing") + f.healing);
      totals.set(path + "critical-candidates", totals.getLong(path + "critical-candidates") + f.criticalCandidates);
      totals.set(path + "longest-combo", Math.max(totals.getInt(path + "longest-combo"), f.bestCombo));
      int current = won ? totals.getInt(path + "streak") + 1 : 0;
      totals.set(path + "streak", current);
      totals.set(path + "best-streak", Math.max(current, totals.getInt(path + "best-streak")));
      if (ranked) {
         String rp = path + "ranked.";
         int rankedStreak = won ? totals.getInt(rp + "streak") + 1 : 0;
         totals.set(rp + "streak", rankedStreak);
         totals.set(rp + "best-streak", Math.max(rankedStreak, totals.getInt(rp + "best-streak")));
      }
      Player player = Bukkit.getPlayer(id);
      if (player != null && player.isOnline()) {
         player.sendMessage(String.format(Locale.ROOT,
            "§dFight stats §7| §f%d hits, %.1f damage, %.1f healed, best combo %d. §bStreak: %d (best %d)",
            f.hits, f.damage, f.healing, f.bestCombo, current, totals.getInt(path + "best-streak")));
      }
      plugin.matchLedger().recordStatistics(matchId, id,
         f.hits, f.damage, f.healing, f.criticalCandidates, f.bestCombo,
         current, totals.getInt(path + "best-streak"));
   }

   void playerStats(Player player) {
      String prefix = "players." + player.getUniqueId() + ".kits.";
      var section = totals.getConfigurationSection(prefix.substring(0, prefix.length()-1));
      if (section == null || section.getKeys(false).isEmpty()) {
         player.sendMessage("§7No completed RiftPvP fights recorded yet."); return;
      }
      player.sendMessage("§5§lRiftPvP §d» Saved fight statistics");
      for (String kit : section.getKeys(false)) {
         String p = prefix + kit + ".";
         player.sendMessage(String.format(Locale.ROOT,
            "§d%s §7- §f%dW/%dL §7| §b%d streak (best %d) §7| §f%d hits, %.1f damage",
            kit, totals.getInt(p + "wins"), totals.getInt(p + "losses"),
            totals.getInt(p + "streak"), totals.getInt(p + "best-streak"),
            totals.getLong(p + "hits"), totals.getDouble(p + "damage")));
      }
   }

   void discard(UUID a, UUID b) { active.remove(a); if (b != null) active.remove(b); }
   int activeCount() { return active.size() / 2; }
}
