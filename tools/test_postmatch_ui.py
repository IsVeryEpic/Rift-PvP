#!/usr/bin/env python3
"""Static regression assertions for the results GUI (not live Paper testing)."""
from pathlib import Path
src=Path(__file__).resolve().parents[1]/'src/main/java/dev/riftpvp'
p=(src/'PracticeFeatures.java').read_text()
a=(src/'FightAnalytics.java').read_text()
checks={
 'six-row GUI': 'Bukkit.createInventory(holder, 54' in p,
 'winner and loser outcomes': '§a§lVICTORY' in p and '§c§lDEFEAT' in p,
 'per-player real statistics snapshot': 'snapshot(fight.a)' in p and 'snapshot(fight.b)' in p and 'record FightStats(' in a,
 'health and healing shown': 'stats.healing()' in p and 'winnerHealth' in p,
 'hits damage and combos': all(s in p for s in ('stats.hits()', 'stats.damage()', 'stats.bestCombo()')),
 'ranked Elo change': 'result.aDelta' in p and 'result.bDelta' in p and 'result.eloHeld' in p,
 'five safe result actions': all(x in p for x in ('case 36 -> "rematch"','case 38 -> requeueCommand','case 40 -> "lobby"','case 42 ->','event.getRawSlot() == 53')) == False and all(x in p for x in ('case 45 -> "rematch"','case 47 -> requeueCommand','case 49 -> "lobby"','case 51 ->','event.getRawSlot() == 53')),
 'no inventory item removal': 'event.setCancelled(true);' in p and 'holder.result != result' in p,
 'no fabricated accuracy metric': 'Accuracy:' not in p,
}
for k,v in checks.items(): print(('PASS' if v else 'FAIL')+' '+k)
if not all(checks.values()): raise SystemExit(1)
