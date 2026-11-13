# Database upgrade verification — 2026-11-12

## Scope

Implemented recognized original legacy import and supported-schema migration behind a connected-operator review/confirmation receipt, followed by stopped-world backup, staged conversion and recoverable installation. No existing player world or live database was upgraded during development. Legacy schema discovery inspected a disposable byte copy; original world files were preserved.

## Focused checks

One invocation selected `LegacyDatabaseUpgradeTest`, `DatabaseUpgradeCommandsTest` and `JournalMigrationTest`: 36 cases, with 31 existing/permission cases passing and five new importer cases initially failing because the macOS @TempDir path traversed the /var symlink. The fixture was corrected to use its real path, preserving production symlink refusal. Only `LegacyDatabaseUpgradeTest` was rerun: all five passed.

The new real-SQLite fixture checks CAD minor units, JPY and CHF openings plus trading balances; exact IDs/owners/primary flags; archived ledger/market/stock rows and read-only triggers; balanced journal postings and projection rebuild; full-world backup inventory and source DB bytes; missing/revoked/stale approvals; unknown/future format refusal; the production older-schema gate even with an ordinary backup; supported current-1 identity/history preservation; and an injected stop after atomic DB replacement with blocked startup and successful nonduplicate resume. The command test denies nonplayer, console-style and RCON approval before storage admission. It does not establish a native connected-client positive-session witness.

A separate bounded worker read-only review found no concrete data-loss/approval-binding/install-order issue; its stale legacy-error wording finding was corrected. No full suite or repeated game acceptance was run for this change.

## Delivery

Passed `./gradlew build -x test`, `./gradlew help --task runClient` and `./gradlew runClient --dry-run` once. Packaged JAR inspection confirms the importer, upgrade controller and admin commands are included. `git diff --check` is clean. Minecraft 1.21.1, NeoForge 21.1.252 and Java 21 pins and main-source/development-runtime registration remain in place; runServer is retained. No game was launched for the final delivery checks. Artifact: `build/libs/economy-0.6.0-alpha.jar`.

## Limits

Current target is schema55 through the dynamic registry; supported foundation sources 1–54 and the recognized legacy minor-unit layout are covered. Unknown/future schemas and incompatible legacy identity/account limits remain refused. Historical market data is archived rather than activated. Migration does not manufacture authority for legacy physical cash/cards. The offline apply step is required even though confirmation occurs in-game. Existing worlds require their own directly connected administrator approval; development did not supply one.
