# Contributing

Thanks for helping improve RiftPvP.

## Before you start
- Check existing issues and discussions to avoid duplicating work.
- For larger changes, open an issue first to discuss the approach.
- Keep pull requests focused and avoid unrelated formatting or refactors.

## Development setup
- Java 25 (JDK)
- Maven 3.9+
- Paper 26.1.2 API

Build and run the checks:

```bash
mvn clean verify
```

The CI workflow also runs the project's source audits and test scripts. Passing these checks does not replace testing on a private Paper server.

## Pull requests
Include:
- A concise explanation of the change and its motivation.
- Steps used to test it, including any checks that were not run.
- Screenshots or short clips for visible UI/menu changes, where useful.
- Notes about configuration changes or migration impact.

Do not commit server files, player data, backups, credentials, API keys, compiled JARs, or local configuration. Keep changes compatible with the project's supported Paper version unless the PR explicitly updates it.
