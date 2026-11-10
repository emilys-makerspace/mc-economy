# A10 — Core integration, UI and hardening

Status: all eleven selectable A10 prompts and their shared contracts approved 2026-11-08, subject to prerequisites. No implementation package is selected, executed or verified. Select packages individually through [the approved queue](EXECUTION.md). This phase entry and its original design brief do not auto-execute implementation, Gradle/tests/game, assets or migrations.

Formerly A8.

## Goal and dependencies

Prepare a complete, reviewable specification for A10. Required upstream work: A1–A9. Reuse accepted upstream contracts and unchanged evidence; mark missing contracts unresolved.

Fixed baseline: Minecraft 1.21.1, NeoForge 21.1.252, Java 21; server-authoritative behavior on both integrated and dedicated servers. Stage A owns money/FX/credit; custody owns shares/return obligations; no market-owned parallel banking database.

Integrate A7 branch discovery, banker guidance and unattended terminal behavior; A8 simulation/prime explanations; A9 reference value availability/provenance. Preserve A6 broader-playtesting limits, window/GUI-scale review and combined borrowing-to-default-to-collection coverage.

## Scope to flesh out

Public API/events; banking/FX/statement UI; permissions/admin/audit; core API extension seams; migrations/restores/recovery; throughput and persistence budgets; stock-facing documentation.

## Mandatory current-feature reimplementation

Inventory coverage: F06 chooser/admin UI, F18 banking/FX/history, F19 core synchronization, F20 stabilized API/admin, F23 core coverage.

Finish all existing banking, history, terminal setup, account lifecycle and world-currency UX, including frozen display history, cursor pagination, historical FX pair views, clipped native controls/localization. Stabilize future-based API without leaking database/client implementation types. No specific integration mod is authorized by generic adapter scope.

Record affected feature coverage and proposed behavior changes. Preserve accepted behavior and reuse unchanged evidence; do not rebuild earlier phases merely to satisfy this planning document.

## Proposed bounded subprompts

Core UI and history; API/events; administration/auditing; core platform and migration procedures; operational/performance acceptance.

For each subprompt define outcome, owner, prerequisites, persistence/entities, service/network contracts, permissions, state transitions, transaction boundary, server/worker handoff, time/version semantics, idempotency, configuration and diagnostics. Specify failures, overflow/rounding, stale requests, disconnects, retries and recovery where relevant. Avoid production code and premature class/file prescriptions.

## Acceptance scenarios to specify, not execute

Integrated and dedicated servers satisfy the same authority/recovery contract; two-player/reconnect/shutdown flows covered; account/ledger/issuance reconcile; development runClient wiring and actual runtime checks scheduled for implementation. Core is playable without stocks.

Include concrete numerical or state-transition examples where useful. Select proportionate future checks for changed behavior under the project verification policy; categories are options, not cumulative gates. Distinguish proposed guarantees from verified behavior. Future runnable deliveries require configured runClient/runServer, mod registration, required development runtime dependencies, and runClient help/dry-run verification; run no checks during this planning task.

## Required planning response

- Phase specification and dependency/ownership map.
- F-ID coverage table: old behavior, new contract, owning subprompt, acceptance cases and explicit later handoff.
- Ordered self-contained future prompts with scope exclusions and completion gates.
- Decisions needed now, with recommended alternatives and consequences; do not silently approve defaults.
- Open questions and approved-versus-proposed rules.

Stop at planning. Do not execute the prompts you draft or expand into later-phase implementation.

## Verification scope

Follow [the project verification policy](../../.codex/VERIFICATION.md) for every package and sub-task. Planning uses diff/link review only; run no Gradle, tests or game processes now. Routine implementation uses at most one focused test invocation when useful, selecting named classes for meaningful changed behavior and reusing existing real-service/SQLite fixtures and unchanged evidence. Package/phase completion does not automatically require tests or a full suite. A full-suite run requires an explicit request or a stated concrete concern narrower checks cannot address.

For an ordinary runnable delivery, run `./gradlew build -x test`, `./gradlew help --task runClient` and `./gradlew runClient --dry-run` once. Keep working `runClient`/`runServer`, registered main source set and required development runtime dependencies. One owner runs Gradle/game checks sequentially. Use disposable worlds for necessary runtime/generation checks; preserve player worlds and backups.

## Preparation — 2026-11-08

The user requested “prep PhaseA10”. [Phase specification and decisions](../../.codex/A10/architecture/a10-planning.md), [approved shared contract](CONTRACT.md), [eleven-package queue](EXECUTION.md) and [handoff](../../.codex/A10/A10-planning.md) are approved 2026-11-08, subject to prerequisites. No implementation package is selected or executed. A10-01 is the proposed source/interface checkpoint. Original design requirements above remain in scope; technical revision1 and package/runtime contracts are approved design targets, not verified behavior.

## Scope boundary — standalone integrations

All third-party mod compatibility is outside A10 and is planned in [separate optional integration modules](../integrations/README.md). A10 stabilizes core public APIs and existing functionality only; it does not add mod adapters, compatibility item catalogs, external inventory/network access, the wallet upgrade or target-mod development dependencies. A10 completion/performance acceptance does not depend on those modules.

## Approved technical planning

The settled decisions are now reflected in [technical revision1](TECHNICAL.md), [current source map](SOURCE_MAP.md), [runtime/performance plan](RUNTIME_PLAN.md) and [eleven selectable packages](EXECUTION.md). A10-02 is an overview of three children, not selectable. Remaining items are source-feasibility revalidation and setup-time resource availability, not unanswered routine gameplay choices. All eleven selectable prompts are approved; no implementation package has started.
