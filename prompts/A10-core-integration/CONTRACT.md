# A10 shared package contract — revision 1

Status: approved 2026-11-08 with all eleven selectable A10 prompts, subject to prerequisites; A10-01, [A10-02-01](../../.codex/A10-02-01.md) and [A10-02-02](../../.codex/A10-02-02.md) are complete in their recorded scopes; later packages remain unselected. See [frozen contracts](../../.codex/architecture/a10-source-contracts.md). [Planning](../../.codex/architecture/a10-planning.md) records inherited scope, evidence and open acceptance decisions. [Queue](EXECUTION.md) defines package boundaries.

## Authority and compatibility

Consume current server-session services and authenticated owner grants. Retain live player/menu/session/revision/reach/loaded hardware checks and finite server-offered actions. A screen, banker, branch location, client amount or valuation read never grants financial authority. Review and confirm actual server terms, source accounts, explicit currency/FX and fees. Do not silently substitute an account, rail or product. Resolve original operations before resubmitting uncertain work; disconnects do not reverse committed outcomes.

Bankers remain optional question-led guides; kiosks work independently. Branch discovery is distinct from loaded service availability. Losing staff or construction does not alter accounts or debts. Use existing discovery and authorized service paths; a new map, locator item or remote banking capability needs separate selection.

Reference values are informational and do not authorize trade, collateral, wealth qualification or redemption. Preserve captured valuation/config/recipe/provider/FX identities and complete retained proofs for durable consumers. Unsupported listings stay disabled without player-facing diagnostic explanations under the accepted A9 policy. Keep detailed derivation/unavailability in authenticated API/operator diagnostics; do not add a player provenance browser by implication.

Retain A8 activity, currency-prime and FX history and A9 source epochs, rounded-unit pricing, spread/cohort protections, custody and original progression. Keep automatic-payment default-OFF/named-source consent and existing retry/reporting order. No changed rates, deadlines, credit policies, catalog prices, retrospective valuation or monetary history rewrite is selected.

B2 securities, B4 financial trading, B6 collateral/variable lending, specific integration mods, superflat issue #3 and arbitrary terrain/datapack support remain separate. No reset or automatic building repair.

## Full core scope and package ownership

Preserve F06 chooser/admin, F18 banking/FX/history, F19 synchronization, F20 public API/admin and F23 core coverage from the original design. A10-02-01 owns dashboard/navigation/structured state, A10-02-02 filtered history/details, A10-02-03 core localization/explanations, A10-03 layouts, A10-04 transport/recovery, A10-05 debt composite, A10-06 API/events, A10-07 permissions/admin/audit, A10-08 core restore/operations/budgets and A10-09 integrated documentation. Main owns shared interfaces and integration; only substantial independent work is delegated with explicit disjoint ownership.

Use [technical revision1](TECHNICAL.md), [source map](SOURCE_MAP.md) and [runtime/workload plan](RUNTIME_PLAN.md) for the contract dimensions and concrete engineering defaults; A10-01 revalidates affected feasibility before edits. No new persistence/entity, scheduler, database or config is proposed by default. Preserve current rounding/overflow refusal, exact request identity and exclusive cursors. New behavior/configuration/events require explicit specification rather than assumption. Scope is review/fix of existing core capabilities, playable without stocks.

A10 administration improves existing settings and commands. A dedicated admin GUI is retained as an optional future feature outside A10, requiring separate selection.

## Source and interface checkpoint

A10-01 must confirm current implementation signatures, protocol revisions, service entry points, fixture boundaries and evidence before changing them. Historical A6/A7 handoffs contain older versions; current source and later acceptance control. The preparation baseline is schema V52, BankingIntegration v2, EconomicSimulationIntegration v1 and ItemValuationIntegration v1. Preparation does not freeze a new transport/schema version.

Prefer existing menus, services and fixtures. Amend the applicable current subsystem contract if behavior changes. Retain old decoders, operations and captured terms. Any necessary protocol/schema addition needs an explicit compatibility/recovery design in the selected package before implementation.

## Evidence and verification

Every package and delegated task inherits [VERIFICATION.md](../../.codex/VERIFICATION.md). Docs-only work uses diff/link review; no Gradle/tests/game. Ordinary changes use at most one focused invocation, normally one to three named classes, only when useful for changed behavior or a concrete regression. No automatic test/full-suite phase gate. Reuse real-service/SQLite fixtures and unchanged evidence; avoid duplicate mock-heavy coverage.

For each ordinary runnable delivery run Java 21 `./gradlew build -x test`, `./gradlew help --task runClient`, and `./gradlew runClient --dry-run` once. Fix failures and repeat only affected checks. Preserve Minecraft 1.21.1, NeoForge 21.1.252, registered main source set, required development runtime dependencies and working runClient/runServer. One owner runs Gradle/game tasks sequentially.

Use necessary disposable runtime witnesses only for affected behavior or uncovered acceptance. Never use player worlds, accept a new EULA, kill a user's game or label injected/accelerated clocks as ordinary real-time gameplay. Record clients, dimensions, GUI scales, transport conditions, durations, actual actions/results and limitations. A loopback test is not external multiplayer; storage reopen is not process-crash evidence. Missing external-client evidence stays explicitly pending. The approved debt composite uses a controlled accelerated fixture; an unaccelerated playthrough is optional later evidence and is not an A10 completion requirement. Production clocks/deadlines remain unchanged.

Each selected package writes a short `.codex/A10-XX.md` handoff and `.codex/verification/A10-XX.md` evidence, updates statuses and stops before its dependents. Preserve historical notes. README stays user-facing; root changelog changes only for a usable release/user-facing change.

Approved phase acceptance policy: when actual external-player testing is unavailable, clearly labeled partial acceptance is permitted if selected core implementation and available required checks pass. Record missing evidence and reserve full verification until it is obtained. This does not waive missing implementation or failing checks. An unaccelerated debt playthrough remains optional later evidence.

## Scope boundary — standalone integrations

All third-party mod compatibility is outside A10 and is planned in [separate optional integration modules](../integrations/README.md). A10 stabilizes core public APIs and existing functionality only; it does not add mod adapters, compatibility item catalogs, external inventory/network access, the wallet upgrade or target-mod development dependencies. A10 completion/performance acceptance does not depend on those modules.

## Approved planning artifacts

[Technical revision1](TECHNICAL.md) owns DTO/state/wire/query/authority/time semantics and bounded engineering defaults. [Source baseline](SOURCE_MAP.md) records current banking8/other-menu1 transports, legacy ascending history and current API exposure. [Runtime plan](RUNTIME_PLAN.md) owns concrete320×240 minimum GUI, delay, accelerated debt and reference20-actor workload/budgets. [Eleven-package queue](EXECUTION.md) splits the former oversized A10-02. Numeric targets are not measurements; no implementation/verification status follows from finalizing these drafts.
