# SentinelAC project information

## Project identity

- Display name: SentinelAC
- Requested GitHub repository name: `SENTINELAC`
- Maven coordinates: `dev.codex:sentinel-ac:1.0.1`
- Plugin main class: `dev.codex.sentinel.SentinelPlugin`
- Target server: Paper 26.2
- Java release: 25
- Built JAR: `SentinelAC-1.0.1.jar`

## Repository layout

```text
.github/workflows/build.yml
src/main/java/dev/codex/sentinel/...
src/main/resources/config.yml
src/main/resources/plugin.yml
src/test/java/dev/codex/sentinel/...
pom.xml
README.md
DETECTIONS.md
PROJECT_INFO.md
```

Keep the Maven project at repository root. Do not wrap it inside repeated `sentinel-ac/` directories; the workflow runs Maven from the root `pom.xml`.

## Components

- `SentinelPlugin`: startup, OP-only administration commands, alerting, violation persistence, and punishment policy.
- `ClientBrandReporter`: reports the brand Paper receives after login to online operators, saves the latest name/brand/time per UUID in `plugins/SentinelAC/client-brands.yml`, and implements `/brandcheck <player>`.
- `MovementChecks`: speed, fly, no-fall, timer, anti-knockback, and grace periods for legitimate transitions.
- `CombatChecks`: reach, kill aura, hitbox-ray checks, aim-assist suspicion, criticals, mace damage, and spear damage.
- `WorldChecks`: scaffold and vehicle checks.
- `DetectionEngine` and `PlayerData`: per-player violation state, confirmation windows, and decay.
- `physics/`: movement formulas and Paper attribute/effect helpers.
- `storage/`: SQLite violation and audit logging.
- `punishment/`: threshold and duration policies.

## Operator commands and access

`/anticheat status|alerts|inspect|violations|checks|reset|debug|reload|punish` and `/brandcheck <player>` are operator-restricted. Join-brand reports and anticheat alerts are sent to operators only. Client brand data is self-reported by clients, may be absent or spoofed, and is not evidence that a player is cheating.

## Enforcement defaults

All detection families are enabled. When a movement, hit, or placement exceeds a check's buffer, the relevant action may be cancelled or corrected. Automatic kicks and bans are disabled by default, and `punishments.monitoring-only` defaults to `true`; this does not disable movement/hit/placement corrections. Operators can inspect violations and use manual commands.

## Detection scope and limitations

Checks use Paper/Bukkit events and server-observed movement; the plugin does not read raw protocol packets. Flight and speed limits are based on vanilla movement physics with tolerances, effects, surfaces, and grace periods. Ordinary sprint-jumping is handled as a legal movement case.

The aim-assist heuristic looks for repeated attacks whose view direction is unusually close to a target's center. It only notifies operators and can neither prove a cheat nor detect assistance that stays within normal-looking aim. The hitbox check compares an attack ray with the server-side entity box, adding bounded allowance for ping and target motion. All heuristics may need tuning on the target server. The project documentation states it has not yet been validated on a live server.

## CI and release versioning

GitHub Actions uses Java 25, runs `mvn clean verify` from the repository root, and uploads `SentinelAC-${version}`. Bump the Maven `<version>` in `pom.xml` and the plugin version in `plugin.yml` together for a release; the workflow reads the Maven version for its artifact name.

The spear formula test expects the iron multiplier result `0.95 * 5.612 = 5.3314`.
