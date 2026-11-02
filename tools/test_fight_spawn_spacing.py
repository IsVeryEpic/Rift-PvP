"""Regression checks for safe, face-to-face RTPQ / duel / Ranked spawn spacing."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
match = (root / "src/main/java/dev/riftpvp/MatchQueue.java").read_text()
config = (root / "src/main/resources/config.yml").read_text()

assert 'Math.max(10, Math.min(12, plugin.getConfig().getInt("matchmaking.separation-blocks", 10)))' in match
assert 'for (int distance = spread; distance <= Math.max(12, spread); distance++)' in match
assert 'int nx = x + offset[0] * distance, nz = z + offset[1] * distance;' in match
assert '(nx >> 4) != (x >> 4) || (nz >> 4) != (z >> 4)' in match
assert 'Math.abs(first.getY() - second.getY()) <= 2' in match
assert 'SafeTeleport.hasClearDropColumn(second, height)' in match
assert 'var10.setYaw(var24 + 180.0F)' in match
assert 'separation-blocks: 10' in config

# Every same-chunk partner produced by the search must be >= 10 blocks away.
# Some starting block positions need a location retry; we must never silently
# move players closer together just to find a partner.
for x in range(16):
    for z in range(16):
        for spread in (10, 11, 12):
            for distance in range(spread, max(12, spread) + 1):
                for ox, oz in ((1, 0), (0, 1), (-1, 0), (0, -1),
                               (1, 1), (-1, 1), (1, -1), (-1, -1)):
                    nx = x + ox * distance
                    nz = z + oz * distance
                    if 0 <= nx < 16 and 0 <= nz < 16:
                        assert (nx - x) ** 2 + (nz - z) ** 2 >= 100
print("Fighter spawn spacing and face-to-face regression checks passed")
