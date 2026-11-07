# A8 shared prompt contract

Status: gameplay, technical revision 1 and all 12 A8 packages approved by the user on 2026-11-07, subject to their prerequisites. A8-01 is complete as a document/source checkpoint; A8-02-01 is implemented/verified in its recorded source-counter scope. A8-02-02 is complete in its recorded immutable-window/model scope. A8-03-01 is complete in its recorded detached market/catalog/pricing scope. A8-03-02 is complete in its recorded owned entity/stock/progression scope. A8-04-01 is complete in its account/NPC/FX API scope. A8-04-02 is complete in its cash/physical-custody API scope. A8-05 is complete in its currency-prime/loan-capture foundation scope. A8-06-01 is complete in its retirement/exact-time FX foundation scope. A8-06-02 is complete in its financial-day provider/handover scope. A8-07 is complete in its recorded production configuration/diagnostics/menu scope. The next package is A8-08, unselected. Reading these prompts never authorizes execution.

## Sources and boundaries

Use [approved gameplay](../../.codex/architecture/a8-planning.md), [shared technical contract](../../.codex/architecture/a8-shared-contracts.md), [current project](../../.codex/PROJECT.md), [workflow](../../.codex/WORKFLOW.md) and [verification policy](../../.codex/VERIFICATION.md). A8-01 confirms pinned-source feasibility before implementation. Its recorded contracts become the implementation baseline; report a material conflict rather than weakening gameplay or inventing a reset.

The selected scope is activity-driven currency-specific prime and FX, monetary villager/wandering-trader commerce, original catalog goods/secondary ingredients, cash and authorized account rails, explicit foreign FX, stock/market timing, provenance and controls. The technical contract carries the exact formulas, bounds, source manifest, accounting/custody rules and public interface meanings; do not duplicate a divergent formula here.

Minecraft 1.21.1, NeoForge 21.1.252 and Java 21 stay pinned. Keep working runClient/runServer tasks, register the main source set and required development runtime dependencies. Preserve existing code, worlds, backups and historical financial outcomes. The one-time reset is complete. No hypothetical deployed-trader migration, A9 valuation implementation, commodity backing or B-series financial-market work. A7 banker guides remain non-trading.

## Execution and ownership

Execute only an explicitly selected package, after its prerequisites are evidenced. Stop after its scoped verification and handoff; approval or completion never selects its dependent. [Execution queue](EXECUTION.md) is the exact order, including split packages.

The main agent owns shared interfaces, integration and all Gradle/game runs. Delegate only substantial independent work under project profiles: at most two economy_worker agents and one read-only economy_researcher, with explicit disjoint file ownership or read-only boundaries. Workers are not alone, must preserve others' changes, must not delegate, and run no Gradle/game tasks. A prompt's responsibility boundary is not a claim that existing files can be overwritten wholesale. Freeze shared record/fact/API ownership before assigning implementation; one owner handles shared runtime, registry and storage integration.

Use bounded worker transactions and detached trusted server observations. Minecraft mutations stay on the server thread; no tick-thread JDBC waits. Original operation lookup precedes new effects. Monetary settlement, stock/market state, observation facts and delivery intent share the journal transaction; physical items/entities retain independently witnessed custody/recovery. No second banking store, silent FX, implicit credit, free drops, historical replay of random decisions or fabricated missing observations.

## Verification and handoff

Apply the project verification policy to every package and assignment. Planning/document changes use diff/link/source review only; no Gradle, tests or game launch. Routine implementation uses at most one useful focused invocation with named behavior test classes, normally one to three, reusing real-service/SQLite fixtures and unchanged evidence. No automatic tests at sub-task completion and no automatic full-suite/phase gates. Add only meaningful behavior/regression coverage.

An ordinary runnable delivery runs these once, sequentially under the main owner:

- `./gradlew build -x test`
- `./gradlew help --task runClient`
- `./gradlew runClient --dry-run`

Fix a failed check and rerun only the affected check. A full suite needs an explicit request or a stated concrete concern narrower checks cannot address, before running it. Use disposable fixtures/worlds for necessary runtime evidence; never modify player worlds, accept the EULA or kill a user's game. A dry run establishes task wiring, not actual loading.

At completion write a concise `.codex/<package-id>.md` handoff and proportional `.codex/verification/<package-id>.md` evidence, update the project and queue statuses without claiming unperformed checks, and link the current subsystem contract. Preserve historical notes. Keep README user-facing and the root changelog for usable releases/user-facing changes. Prompts describe acceptance outcomes, not a requirement to create or run a test for every listed case.
