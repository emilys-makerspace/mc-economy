# Village bank foundations — 2026-11-09

## Reproduction and correction

User-reported fresh vanilla terrain, Economy only: seed `-3557599905807449533`, damaged building near `-2095 109 2448`. The snowy village source is chunk `[-132,150]`. This is an Economy bank, not a truncated vanilla house. Before the fix, branch slot 0 completed chunk `[-131,153]` but rejected `[-132,152]`, `[-132,153]` and `[-131,152]` as `SUPPORT_UNSAFE` at predicted support blocks that were actually air. Independent chunk preflight left a visible quarter-building.

New captures reserve six foundation cells per bank/dry-path column and validate support at floor−7. Every replacement cell is checked during planning. Initial placement retains protected-block/support/leveling checks; natural gravel is replaceable, while falling support remains rejected. Path leveling uses the captured three-cell clearance ceiling to recover walking height, excluding foundation layers. Revision suffix advances to `:standard-banks-v4-foundations`; saved plans and receipts are not converted.

## Checks

- Exact-seed integrated-client regression: `./gradlew runClient -PvillageGenerationProbe -PvillageGenerationRepro -PeconomyRunRoot=/private/tmp/economy-village-slice-verified` — **passed**, including normal save/shutdown and Gradle success. Minecraft 1.21.1, NeoForge 21.1.252, Java 21.
- Both naturally generated banks retained; all **9 chunk fragment receipts COMPLETE**; all **7,382 planned non-air bank/path/foundation blocks** matched their expected block types. Exact mutable block states, financial activation and accounts were not asserted.
- Screenshot visually inspected: both banks have complete walls and roofs. Evidence: `/private/tmp/economy-village-slice-verified/client/screenshots/village-generation-bank.png`; saved plan/report in `client/saves/economy-a7-04-c8c65fc4-c8f2-481c-9974-f15e9e2b77c7/{village-start.snbt,reproduction.txt}` under that run root.
- `./gradlew build -x test` — **passed**, including formatting checks; `build/libs/economy-0.5.0-alpha.jar` produced.
- `./gradlew help --task runClient` and `./gradlew runClient --dry-run` — **passed**, once each. Main source-set registration, server run and SQLite development runtime remain configured.
- Read-only independent production/probe diff review found no blocking regression. No JUnit or full-suite invocation; the actual world-generation regression was the focused behavior check.

The first corrected run exposed natural gravel in the other bank's deeper foundation and correctly failed the regression; gravel handling was fixed before the successful run. Early screenshot fixtures stopped while their new view was still loading and stalled in Minecraft's chunk-unload loop after requesting normal shutdown; only those disposable clients were terminated. The regression now uses a smaller render distance and a longer render warmup, and the final run shut down cleanly.

## Limits

Only this reported natural seed/location was rerun; prior five-style and financial acceptance evidence was not repeated. Cross-chunk placement remains independent rather than atomic; unexpected protected terrain or support loss can still conflict. Existing partial/conflicted banks are preserved, not repaired. Loaded additive retries retain supported-air-only behavior. No player world or backup was opened or modified.
