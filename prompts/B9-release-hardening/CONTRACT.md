# B9 package contract — Market integration, operations and release handoff

Draft revision1, 2026-11-13. Planning only; none of these packages is selected, implemented or verified. Every package includes this contract, [Stage B shared contract](../planning/B-SHARED.md) and the [decision register](../planning/B-DECISIONS.md). Explicit user requirements/answers remain approved scope; recommendations are unapproved policies. Implement only after separate exact-package selection and prerequisite/decision resolution.

## Scope and dependencies

Refine [existing design](DESIGN.md) after B0–B8 in their selected accepted scopes. B9 audits preservation and actual behavior, resolves specifically assigned integration issues and produces a reviewable release artifact/handoff. It cannot approve missing product policies, reset worlds, silently omit features or infer external-client evidence from local/synthetic samples. B0 corporations are new user scope and must be in the final matrix even though they have no historical F-ID.

## Ownership and feature coverage

| Coverage | Owner evidence | B9 package |
| --- | --- | --- |
| F01–F03 runtime/store/upgrades | A1 and current database upgrade contracts | B9-01/04/06 |
| F04–F06 currency/FX/rebase | A2/A8/A10 and B1/B3/B4 raw history | B9-01/02/04 |
| F07–F09 banking/funds/audit | A3/A4/current overdrafts, B0/B2/B4 | B9-01/04 |
| F10–F14 cash/cards/terminals/shops | A5/A6, unchanged evidence | B9-01/05/06 |
| F15 registry and provenance | B1 exact 800 reviewed rows, approved98+two replacements | B9-01/06 |
| F16 custody/funding/trades | B2/B4/B5 with B6/B7 obligations | B9-01/03/04 |
| F17 prices/charts/admin | B3/B4/B8 | B9-02/05 |
| F18–F20 UI/authority/API | A10/B0/B4 and product extensions | B9-02/05 |
| F21/F22 machines/art | A5/B4 geometry/resources/licenses | B9-06 |
| F23 meaningful coverage | Every owning phase, no blanket suite rerun | B9-01/06 |
| New corporations and B5–B8 products | B0 ownership/roles/API; conversion/risk/lending/actions | B9-01/04/05 |


Main owns shared interfaces and sequential Gradle/game tasks; delegate only substantial independent work with disjoint files and explicit allowed checks. No automatic delegation/check gate. Use [package queue](EXECUTION.md) in prerequisite order, stopping after each handoff.

## Domain contract and examples

Maintain a traceable row per feature/required behavior: approval/approved change, owner package, current authority contract, implemented capability, actual evidence, unresolved decision/limitation and release disposition. Historical importer exclusion is superseded where later approved stopped-world import/upgrade tooling exists; retain history rather than claiming no migrations/imports. Corporate funds/manager revocation and unavailable corporate credit join ordinary accounting checks.

Release manifest records platform/dependencies, actual schema/wire/API versions, artifact hash, source/license inventory, exact catalog release hash and separate company/security/listing counts, policies/algorithms, supported workloads and known limits. No unresolved row disappears because an old test passed. Source-review evidence, synthetic owners, native client, socket clients and external machines retain distinct labels.

Bound quote snapshots/pages/subscriptions by owner/session/view/revision; stop abandoned display work while preserving accepted financial work. Archive only reviewed derived market series with reproducible boundaries; preserve journal facts, canonical operation identities/outcomes and decoders required for lots/fees/obligations/actions. A history page never reprices a historical fill at current FX. B9 changes product balancing prospectively only under specifically approved versioned parameters.

Use the [approved BD09 workload, recovery targets and history policy](OPERATIONS-REVIEW.md). Measure queues, latencies, refusals and integrity alongside throughput. Published capacity claims use the measured B9 workload and actor type. Overload refusals occur before admission and preserve operation IDs for retries.

Stopped-world upgrades/restores use existing manifest/readiness tools on disposable copies. Check original-operation recovery, residual margin debt/recall obligations and corporate authority at the exact selected interruption. No online arbitrary SQL repairs or deleting balances to reconcile. Actual process termination and clean reopen prove different things; do not manufacture a crash claim. External clients remain manual/outside CI and missing required local work cannot be excused by that qualification.

## Decisions, bounds and verification

The [decision register](../planning/B-DECISIONS.md) controls unresolved policies; finalize only affected choices before implementation. [Stage B shared contract](../planning/B-SHARED.md) supplies current platform, thread/worker/transaction/authority, exact arithmetic, UTC/version, original-operation, overload and recovery requirements. A missing exact source/bound/version is resolved at the source checkpoint; never substitute an invented default. Configuration and new schema/wire/API versions are prospective, validated and recorded with historical compatibility.

Apply [project verification scope](../../.codex/VERIFICATION.md) to every sub-task. Planning uses diff/link review only. Future routine work uses at most one useful focused named-test invocation; no automatic test/full-suite completion gate. Ordinary runnable delivery runs Java 21 `./gradlew build -x test`, `./gradlew help --task runClient`, `./gradlew runClient --dry-run` once, keeping registered main mod, required development runtime dependencies and runClient/runServer. Main owns sequential tasks; preserve player worlds/backups and use disposable fixtures only when necessary. Each package writes its scoped handoff/evidence and stops. No tests/game/build run during drafting.
