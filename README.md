# SENTINELAC

SentinelAC is a Paper anticheat plugin targeting **Paper 26.2** and **Java 25**. The repository is intentionally flat: `pom.xml`, `.github/`, and `src/` are at the repository root.

## Features

- Movement checks for speed, flight, no-fall, timer, and anti-knockback.
- Combat checks for kill aura, reach, hitbox abuse, suspiciously precise aim, criticals, mace damage, and spear damage.
- World checks for scaffold placement and vehicle movement.
- Operator-only alerts, management commands, and client-brand reports.
- Violation/audit storage in SQLite; reported client brands are saved separately in `client-brands.yml`.

The aim-assist check is a suspicion signal only. It does not cancel attacks or punish players. Client brands are self-reported by the connecting client and are not proof of cheating.

## Commands

- `/anticheat status|alerts|inspect|violations|checks|reset|debug|reload|punish`
- `/brandcheck <player>` — view that player's most recently saved client brand.

These commands and alert messages are restricted to operators. The console can use `/anticheat` administration commands; `/brandcheck` is an in-game operator command.

## Build and install

Requires Java 25 and Maven. From the repository root, run:

```text
mvn --batch-mode --no-transfer-progress clean verify
```

Copy `target/SentinelAC-1.0.1.jar` to the server's `plugins/` directory. GitHub Actions runs the same Maven build on pushes, pull requests, and manual dispatch, then uploads a version-named artifact.

See [PROJECT_INFO.md](PROJECT_INFO.md) for architecture and limitations, and [DETECTIONS.md](DETECTIONS.md) for each check's behavior.
