# RiftPvP v1.3.4 — Development Build History

RiftPvP currently uses the internal version `1.3.4-SNAPSHOT` across several incremental builds. To prevent confusion, historical distributions are identified here as `v1.3.4-dev.01` through `v1.3.4-dev.10`.

**All builds below are pre-releases, not verified stable releases.** Java compilation and static checks are not substitutes for two-player server testing. Older JARs may include bugs addressed later, and should not replace a newer installation without making backups of `plugins/RiftPvP/`.

## Dev 10 — Post-Match UI (2026-11-01)

[Build #41345178389](https://github.com/IsVeryEpic/Rift-PvP/actions/runs/41345178389)

- New six-row results screen showing outcome, winner, match duration, hits, damage, healing, best combo and armour durability.
- Ranked Elo changes displayed for Ranked fights; buttons for Rematch, Requeue, Lobby, Spectate and Close.
- Added a way to reopen the latest fight result with `/fightresult`.
- Restructured the repository into normal Maven source files, resources, regression checks and a documented build.
- **Most recent development build**, still awaiting complete live two-player testing.

## Dev 09 — Admin GUI and Lobby Protection (2026-11-01)

[Build #41343630788](https://github.com/IsVeryEpic/Rift-PvP/actions/runs/41343630788)

- Expanded Admin Panel with GUI flows for player selection, kicks, ban duration, reason and confirmation.
- Added management menus for ranks, kits, NPCs, server features, maintenance, cleanup, backups and diagnostics.
- Reorganised main hotbar and secondary inventory menu shortcuts.
- Removed lobby Wind Charge grants and added lobby player-damage protection.

## Dev 08 — Kit Room Drag Fix (2026-11-01)

[Build #41342182947](https://github.com/IsVeryEpic/Rift-PvP/actions/runs/41342182947)

- Fixed Kit Room inventory editing: catalogue clicks now give the player an item on their cursor to place.
- Added right-click single-item pickup; kept Shift-click for quick adding.
- Preserved automatic catalogue restocking and XP/item restrictions.
- In-game drag behaviour was not fully verified.

## Dev 07 — Duel, Ranked and Reconnect Fix (2026-11-01)

[Build #41313575080](https://github.com/IsVeryEpic/Rift-PvP/actions/runs/41313575080)

- Removed forced 10-minute duel expiration.
- Reinstated 25-second match reconnect grace after disconnect.
- Prevented lobby-inventory reset from interrupting active reconnect recovery.
- Improved Ranked Plains location retries and error handling.

## Dev 06 — Ranked GUI and Plains Locations (2026-10-31)

[Build #41248030197](https://github.com/IsVeryEpic/Rift-PvP/actions/runs/41248030197)

- Added Ranked kit-selection GUI showing waiting counts, rank, Elo and Leave Queue.
- Routed Ranked command and NPC to the same GUI.
- Restricted Ranked fight spawns to configurable Plains / Sunflower Plains biomes.
- **Known issue:** Matchmaking could fail when the spawn-location search exhausted its attempts.

## Dev 05 — Admin Build Mode (2026-10-31)

[Build #41247459685](https://github.com/IsVeryEpic/Rift-PvP/actions/runs/41247459685)

- Personal admin toggle to hide lobby items for building.
- Saved the personal toggle across sessions.
- Introduced immediate mid-fight disconnect forfeit.
- **Known issue:** The immediate-forfeit change prevented reconnect grace from functioning. Reversed in Dev 07.

## Dev 04 — NPC Interactions (2026-10-31)

[Build #41245558746](https://github.com/IsVeryEpic/Rift-PvP/actions/runs/41245558746)

- Added click handlers for the Spectate, Hub and Ranked NPCs.
- Added menus for active matches and Ranked kit selection.
- Added binding commands for existing lobby mannequins.

## Dev 03 — Rift Menu Fix (2026-10-31)

[Build #41233754812](https://github.com/IsVeryEpic/Rift-PvP/actions/runs/41233754812)

- Restored reliable menu interactions.
- Replaced the WorldEdit-conflicting Compass menu shortcut.
- Replaced the throwable Ender Pearl shortcut with a non-throwable item.
- Corrected the inventory-open API call used by the Rift Menu.

## Dev 02 — Stage 8 Baseline (2026-10-31)

[Build #41226669165](https://github.com/IsVeryEpic/Rift-PvP/actions/runs/41226669165)

- First successful compilation of the expanded Stage 8 feature set.
- Plugin-data backups and staged rollback; maintenance mode.
- Custom-kit validation and per-kit mode controls.
- Reconnect handling, first-join tutorial and earlier stats / staff diagnostics work.
- Superseded by the later menu, reconnect and match fixes.

## Dev 01 — Stage 4 Baseline (2026-10-30)

[Build #41146025028](https://github.com/IsVeryEpic/Rift-PvP/actions/runs/41146025028)

- Early compiled baseline covering core kits, queues, duels and lobby features.
- Does not contain the later Stage 8 additions and fixes.
- Kept for archival / debugging purposes only.

## Publication and download notes

- Each listed GitHub Actions run was verified to have a non-expired compiled-artifact ZIP as of 2026-11-01.
- The [historical release publication workflow](.github/workflows/publish-archive-releases.yml) prepares separate JAR-bearing GitHub prereleases, but publishing may require an Actions secret called `RELEASE_TOKEN` with repository contents write access.
- GitHub Releases should not be considered published until entries appear in the repository's [Releases](https://github.com/IsVeryEpic/Rift-PvP/releases) page.
- All builds target **Paper 26.1.2 / Java 25**. Test on a copy of server data before changing builds.
