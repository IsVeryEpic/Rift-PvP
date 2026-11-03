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

## Website sync (optional)

RiftPvP includes an **outbound, one-way** website API adapter. It is **disabled by default**. It never accepts website-issued game commands, changes staff permissions, or writes ranks/Elo back into the server.

Configure `plugins/RiftPvP/config.yml` on the **server**, not in GitHub:

```yaml
website-sync:
  enabled: true
  endpoint: 'https://your-domain.example/api/riftpvp/sync'
  api-key: 'a-long-random-shared-secret'
  server-id: 'main'
  interval-seconds: 60
```

Run `/riftpvp reload`, then `/riftpvp websync now` and `/riftpvp websync status` (OP/console only). The plugin sends a full snapshot in JSON via an asynchronous HTTPS POST. One request at a time prevents overlap; there are request timeouts and no HTTP redirects. A failed request does **not** affect gameplay, stored stats or match results. The API must accept `Authorization: Bearer <api-key>`, respond with HTTP 2xx only after committing data, and use UPSERTs on `(server_id, uuid)` to make repeats idempotent.

**JSON schema v1:**

```json
{
  "schema_version": 1,
  "server_id": "main",
  "generated_at": "2026-11-03T12:00:00Z",
  "players": [{
    "uuid": "f84c6a79-5d2b-466d-a337-7199e7a99465",
    "name": "ExamplePlayer",
    "rank": "member",
    "kills": 7,
    "deaths": 3,
    "playtime_ms": 234000,
    "kits": {"sword": {"elo": 1048, "wins": 3, "losses": 1, "tier": "Unranked 4/5"}}
  }]
}
```

The receiver should validate `schema_version`, token, body size and each value. Reject unauthorized requests; do not expose the API key in frontend JavaScript. Public leaderboards can read the synchronized server-side database. Never use this write endpoint from a browser.

**Important:** This repository has no connected website/backend or deployed sync API. The bridge becomes operational only after you deploy a matching receiving endpoint and configure its URL/secret. `config.yml` and scheduled RiftPvP backup archives may contain secrets and must remain private.

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
