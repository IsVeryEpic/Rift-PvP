# RiftPvP

[![Build](https://github.com/IsVeryEpic/Rift-PvP/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/IsVeryEpic/Rift-PvP/actions/workflows/build.yml)
![Java](https://img.shields.io/badge/Java-25-orange)
![Paper](https://img.shields.io/badge/Paper-26.1.2-blue)

A custom Minecraft PvP practice plugin featuring ranked kit matchmaking, RTP queues, duels, custom kits, Kit Room, lobby menus, player profiles and staff tools.

> **Development status:** `1.3.4-SNAPSHOT`. This is a development build, not a stable release. The project currently targets Paper 26.1.2 and Java 25. Gameplay features should be tested on a private server before public deployment.

## Features

- **Queues and matchmaking:** RTPQ, ranked queues (Plains and Sunflower Plains), duels and rematches.
- **Ranked progression:** per-kit Elo, ranks, profiles, match history and results.
- **Kits:** kit editor, Kit Room and private custom kits.
- **Social and lobby:** clickable invitations, spectating and lobby navigation.
- **Staff tools:** administration menus and moderated actions with server-side permission checks.

## Requirements

- Paper 26.1.2
- Java 25
- JDK 25 and Maven 3.9+ to build from source

## Build from source

```bash
mvn clean verify
```

The compiled JAR is created at:

```
target/RiftPvP-1.3.4-SNAPSHOT.jar
```

GitHub Actions runs the build and source audits on pushes to `main`, pull requests and version tags. Successful workflow runs upload a test-build artifact under **Actions → workflow run → Artifacts**. CI passing is not a substitute for in-game testing.

## Installation / upgrade

1. Stop your Paper server.
2. Back up `plugins/RiftPvP/`.
3. Remove old RiftPvP JARs from `plugins/` and install only the new one.
4. Start the server and check `logs/latest.log` for errors.
5. Configure or verify your lobby with `/riftpvp setlobby`, if needed.

Persistent kit, rank, leaderboard, backup and match data is stored under `plugins/RiftPvP/` on the server. **Do not upload runtime data or backups to this repository.**

## Optional website sync

RiftPvP has an outbound, one-way website API adapter. It is disabled by default and does not accept website-issued game commands, change staff permissions, or write ranks/Elo back into the server.

Configure this on your server in `plugins/RiftPvP/config.yml` (never commit the API key):

```yaml
website-sync:
  enabled: true
  endpoint: 'https://your-domain.example/api/riftpvp/sync'
  api-key: 'a-long-random-shared-secret'
  server-id: 'main'
  interval-seconds: 60
```

Then use `/riftpvp reload`, `/riftpvp websync now` and `/riftpvp websync status` (OP/console only). The plugin sends a full JSON snapshot over asynchronous HTTPS POST. Requests have timeouts, do not follow redirects, and do not overlap. Sync errors do not affect gameplay or match data.

The receiving API should:
- Require `Authorization: Bearer <api-key>`.
- Validate the schema version, token, request size and submitted values.
- Respond with HTTP 2xx only after data is committed.
- Use idempotent UPSERTs on `(server_id, uuid)`.

**This repository does not include a deployed website/backend endpoint.** The integration only works after a compatible receiving endpoint is deployed and configured. Do not expose the API key in browser-side code.

## Development and community

- [Contributing guide](CONTRIBUTING.md)
- [Security policy](SECURITY.md)
- [Code of conduct](CODE_OF_CONDUCT.md)

Please report reproducible bugs with the server version, plugin build, steps to reproduce and relevant logs. Remove IP addresses, tokens, passwords and other private information before posting.

## Repository structure

- `src/main/java/dev/riftpvp/` — plugin source
- `src/main/resources/` — plugin descriptor and defaults
- `tools/` — source audits and test scripts
- `.github/workflows/build.yml` — CI build and test-artifact workflow

This repository excludes server backups, runtime data, compiled JARs, archived source ZIPs and transfer files. Older development snapshots remain available in Git history.
