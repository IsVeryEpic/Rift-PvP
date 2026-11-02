"""Source regression checks for ranked-only damage immunity and practical queue matching."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
match = (root / "src/main/java/dev/riftpvp/MatchQueue.java").read_text()
combat = (root / "src/main/java/dev/riftpvp/CombatTag.java").read_text()
ranked = (root / "src/main/java/dev/riftpvp/PracticeFeatures.java").read_text()
config = (root / "src/main/resources/config.yml").read_text()

assert "private boolean isRankedCombatant(UUID id)" in match
assert "if (!isRankedCombatant(victimId)" in match
assert "if (rankedMatch) {\n                                 this.protectFightStart(var1, var2);" in match
assert "this.plugin.combat().clearArrival(var1);" in match
assert "this.plugin.combat().clearArrival(var2);" in match
assert "void clearArrival(Player player)" in combat
assert "void protectArrival(Player var1)" in combat
assert 'args[0].equalsIgnoreCase("status")' in ranked
assert "rankedEloCompatible(" in ranked
assert "rankedRespectPing()" in ranked
assert "allow-any-elo-after-seconds: 60" in config
assert "respect-ping-preferences: false" in config
assert "rankedFightStarts.contains(var2)" in match
assert "usableRadius" in (root / "src/main/java/dev/riftpvp/SafeTeleport.java").read_text()
print("14 ranked queue / combat-mode regression assertions passed")
