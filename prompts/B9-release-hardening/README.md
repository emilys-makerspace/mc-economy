# B9 — Complete market integration and release

Status: draft DESIGN prompt. Refine specifications and future prompts only. Do not implement code, scaffold, run Gradle/tests/game processes, generate assets, change dependencies, or migrate worlds. Edit planning files only when explicitly requested. Reading a roadmap or this file is not implementation authorization.

## Design brief

[First-pass design brief](DESIGN.md) · [B-series dependencies and shared contracts](../planning/B-SERIES-DESIGN.md). Drafts distinguish fixed requirements from proposed decisions; no implementation or policy approval is implied.

## Goal and dependencies

Prepare a complete, reviewable specification for B9. Required upstream work: B1–B8. Do not treat existing code as completed redesign work. Supply approved upstream contracts explicitly; mark missing contracts unresolved.

Fixed baseline: Minecraft 1.21.1, NeoForge 21.1.252, Java 21; server-authoritative behavior on both integrated and dedicated servers. Stage A owns money/FX/credit; custody owns shares/return obligations; no market-owned parallel banking database.

## Scope to flesh out

Final UI/admin/API; quote propagation; CPU/memory/queue limits; throughput/settlement latency; history retention/archival; migration/replay/recovery; 800-listing concurrency; liquidation/recall/FX stress scenarios; economic balancing and documentation.

## Mandatory current-feature reimplementation

Inventory coverage: F18/F19 market completion, F23 full coverage; all F-IDs reconciled.

Audit every inventory F-ID against its reimplementation owner and explicit approved behavior changes. Preserve original asset/licensing provenance and runtime packaging. Do not use passing old tests as proof of new recovery guarantees.

All assigned existing behavior must appear in the coverage table even when replacement architecture differs. Record any proposed behavior change and its approval status. Existing implementation and existing tests do not satisfy the new phase gate.

## Proposed bounded subprompts

Feature/accounting audit; full-universe performance; synchronization/retention; migration/recovery operations; balancing and release documentation.

For each subprompt define outcome, owner, prerequisites, persistence/entities, service/network contracts, permissions, state transitions, transaction boundary, server/worker handoff, time/version semantics, idempotency, configuration and diagnostics. Specify failures, overflow/rounding, stale requests, disconnects, retries and recovery where relevant. Avoid production code and premature class/file prescriptions.

## Acceptance scenarios to specify, not execute

Approved player load across all listings meets budgets; no unbounded tick work/history/queues; combined margin cascades, recalls and FX shocks reconcile; release artifact excludes fixtures; registered runClient/runServer and runtime dependencies verified during authorized implementation; unresolved guarantees documented.

Include concrete numerical or state-transition examples where useful. Describe future unit, integration, restart/crash and performance checks appropriate to each subprompt. Distinguish proposed guarantees from verified behavior. Future runnable deliveries require configured runClient/runServer, mod registration, required development runtime dependencies, and runClient help/dry-run verification; run no checks during this planning task.

## Required planning response

- Phase specification and dependency/ownership map.
- F-ID coverage table: old behavior, new contract, owning subprompt, acceptance cases and explicit later handoff.
- Ordered self-contained future prompts with scope exclusions and completion gates.
- Decisions needed now, with recommended alternatives and consequences; do not silently approve defaults.
- Open questions and approved-versus-proposed rules.

Stop at planning. Do not execute the prompts you draft or expand into later-phase implementation.

## Individual package drafts — 2026-11-13

[Package contract](CONTRACT.md) · [Ordered selectable drafts](EXECUTION.md) · [Stage B shared contract](../planning/B-SHARED.md) · [decision register](../planning/B-DECISIONS.md). All packages remain unselected drafts. These refine the design brief and split oversized topics; existing explicit approvals stay in force. Product policies remain open where recorded. The shared project verification scope supersedes any broad earlier checklist: planning is diff/link review only; future checks are selected by changed behavior, with no automatic full-suite or test-per-package gate. Corporate ownership/API comes from B0; corporate borrowing remains excluded initially.
