# RiftPvP

Custom Minecraft PvP practice plugin supporting RTP queues, ranked kit matchmaking, duels, custom kits, Kit Room, lobby menus and staff administration.

## Status

**v1.3.4-SNAPSHOT — development build.** The build workflow compiles against Paper **26.1.2** with **Java 25**. Compilation and source checks do not guarantee gameplay correctness. Test on a private server before public deployment, especially matchmaking, reconnects, Kit Room and inventory handling.

## Build

Requirements: JDK 25, Maven 3.9+, internet access for the Paper API dependency.

```bash
mvn clean verify
```

The output is `target/RiftPvP-1.3.4-SNAPSHOT.jar`.

GitHub Actions builds on updates to `main` and stores an installable JAR under **Actions → latest successful run → Artifacts**. A workflow artifact is a test build, not a formally tested release.

## Installation

1. Stop the Paper 26.1.2 server.
2. Back up `plugins/RiftPvP/` before upgrading.
3. Place exactly one RiftPvP JAR in `plugins/` and remove older versions.
4. Start the server and inspect `logs/latest.log` for errors.
5. Set and test the lobby with `/riftpvp setlobby` if needed.

All persistent kit, rank, leaderboard, backup and match data belongs in `plugins/RiftPvP/`, not in this repository.

## Player features

- Rift Menu, RTPQ, Ranked (Plains and Sunflower Plains), duels and rematches
- Per-kit Elo/ranks, profiles, saved fight history and match results
- Kit Editor / Kit Room / private custom kits
- Clickable invitations, spectating and lobby navigation

## Moderation

Staff and admin menus provide moderated actions. Permission checks are enforced on the server. Use `/riftpvp doctor` to inspect configuration, and test all permissions as a **non-OP** before opening a public server.

## Source layout

- `src/main/java/dev/riftpvp/` — Java plugin source
- `src/main/resources/` — plugin descriptor, default configuration, kit templates
- `tools/` — static source audits; these do not substitute for runtime tests
- `.github/workflows/build.yml` — Java 25 Maven build and downloadable test artifact

This repository deliberately excludes server backups, runtime data, archived source ZIPs, compiled JARs and transfer files. Older development snapshots remain accessible in Git history.
