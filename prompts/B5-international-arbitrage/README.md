# B5 — Cross-listings and international arbitrage

Status: draft DESIGN prompt. Refine specifications and future prompts only. Do not implement code, scaffold, run Gradle/tests/game processes, generate assets, change dependencies, or migrate worlds. Edit planning files only when explicitly requested. Reading a roadmap or this file is not implementation authorization.

## Design brief

[First-pass design brief](DESIGN.md) · [B-series dependencies and shared contracts](../planning/B-SERIES-DESIGN.md). Drafts distinguish fixed requirements from proposed decisions; no implementation or policy approval is implied.

## Goal and dependencies

Prepare a complete, reviewable specification for B5. Required upstream work: B4. Do not treat existing code as completed redesign work. Supply approved upstream contracts explicitly; mark missing contracts unresolved.

Fixed baseline: Minecraft 1.21.1, NeoForge 21.1.252, Java 21; server-authoritative behavior on both integrated and dedicated servers. Stage A owns money/FX/credit; custody owns shares/return obligations; no market-owned parallel banking database.

## Scope to flesh out

Fungible transfers; conversion ratios and restrictions; executable bid/ask FX comparison; costs/latency/session friction; finite arbitrage; institutional arbitrage orders and convergence; activity reporting.

## Mandatory current-feature reimplementation

Inventory coverage: No current cross-market implementation.

All money/FX uses Stage A and custody uses B2. Listing currency is independent of world reporting currency. No infinite conversion loops, free identity conversion or direct price nudges masquerading as liquidity-consuming trades.

All assigned existing behavior must appear in the coverage table even when replacement architecture differs. Record any proposed behavior change and its approval status. Existing implementation and existing tests do not satisfy the new phase gate.

## Proposed bounded subprompts

Eligibility/conversion; cross-market transfer; opportunity calculation net of costs; institutional execution; exploit limits and reporting.

For each subprompt define outcome, owner, prerequisites, persistence/entities, service/network contracts, permissions, state transitions, transaction boundary, server/worker handoff, time/version semantics, idempotency, configuration and diagnostics. Specify failures, overflow/rounding, stale requests, disconnects, retries and recovery where relevant. Avoid production code and premature class/file prescriptions.

## Acceptance scenarios to specify, not execute

Fees/spreads/rounding prevent artificial cycle profits; allowed cross-listing quantities reconcile; closed venues and delayed conversion handled; arbitrage consumes finite liquidity and budgets.

Include concrete numerical or state-transition examples where useful. Select proportionate future checks under the project verification policy; unit, integration, restart/crash and performance categories are alternatives chosen for changed behavior, not cumulative gates. Distinguish proposed guarantees from verified behavior. Future runnable deliveries require configured runClient/runServer, mod registration, required development runtime dependencies, and runClient help/dry-run verification; run no checks during this planning task.

## Required planning response

- Phase specification and dependency/ownership map.
- F-ID coverage table: old behavior, new contract, owning subprompt, acceptance cases and explicit later handoff.
- Ordered self-contained future prompts with scope exclusions and completion gates.
- Decisions needed now, with recommended alternatives and consequences; do not silently approve defaults.
- Open questions and approved-versus-proposed rules.

Stop at planning. Do not execute the prompts you draft or expand into later-phase implementation.


## Individually selectable package drafts — 2026-11-13

[Phase contract](CONTRACT.md) · [Ordered package queue](EXECUTION.md) · [Stage B shared contract](../planning/B-SHARED.md) · [Decision register](../planning/B-DECISIONS.md). These flesh out the original DESIGN brief without implementing it. All packages remain drafts awaiting their policy gates and separate explicit selection. The current shared verification policy controls every package/subtask; no automatic full-suite or per-package test gate is introduced. Corporation registration/accounts/API are separately proposed under B0; new corporate borrowing, short exposure and public listing are outside its initial scope.

Approved requirement 2026-11-13: B5 handles eligible catalog cross-listings through FX/fee/transfer-aware finite arbitrage, with conserved underlying custody. [Catalog K07](../B1-market-registries/POLICY-REVIEW.md) and [phase contract](CONTRACT.md) define the handoff. Numeric friction/budget policies are now approved under X01–X10; implementation is unselected.

## Cross-listing/arbitrage policy review — 2026-11-16

[X01–X10](POLICY-REVIEW.md) record eligible ratios, free 30-second same-security transfers, 0.10%/five-minute distinct-security conversions, net-profit/session/budget limits, sequential funded legs, failed-leg treatment, explicit activation and economic attribution, approved 2026-11-16 with “this works”; no implementation selected.
