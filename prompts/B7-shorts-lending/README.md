# B7 — Short selling and securities lending

Status: draft DESIGN prompt. Refine specifications and future prompts only. Do not implement code, scaffold, run Gradle/tests/game processes, generate assets, change dependencies, or migrate worlds. Edit planning files only when explicitly requested. Reading a roadmap or this file is not implementation authorization.

## Design brief

[First-pass design brief](DESIGN.md) · [B-series dependencies and shared contracts](../planning/B-SERIES-DESIGN.md). Drafts distinguish fixed requirements from proposed decisions; no implementation or policy approval is implied.

## Goal and dependencies

Prepare a complete, reviewable specification for B7. Required upstream work: B6. Do not treat existing code as completed redesign work. Supply approved upstream contracts explicitly; mark missing contracts unresolved.

Fixed baseline: Minecraft 1.21.1, NeoForge 21.1.252, Java 21; server-authoritative behavior on both integrated and dedicated servers. Stage A owns money/FX/credit; custody owns shares/return obligations; no market-owned parallel banking database.

## Scope to flesh out

Borrow pools, locates/reservations; lending contracts/liabilities; availability/utilization/dynamic borrow rates; short sell/cover; restricted proceeds; short margin; borrow/dividend-equivalent fees; recalls/buy-ins.

## Mandatory current-feature reimplementation

Inventory coverage: No current short/lending implementation.

Maintain custody return obligations and one shared margin view. Prohibit naked shorts and double-lent inventory; distinguish economic beneficial ownership, custody location and obligation to return.

All assigned existing behavior must appear in the coverage table even when replacement architecture differs. Record any proposed behavior change and its approval status. Existing implementation and existing tests do not satisfy the new phase gate.

## Proposed bounded subprompts

Lending inventory/contracts; short execution/cover; restricted proceeds and risk; fee accrual; recall/buy-in and recovery.

For each subprompt define outcome, owner, prerequisites, persistence/entities, service/network contracts, permissions, state transitions, transaction boundary, server/worker handoff, time/version semantics, idempotency, configuration and diagnostics. Specify failures, overflow/rounding, stale requests, disconnects, retries and recovery where relevant. Avoid production code and premature class/file prescriptions.

## Acceptance scenarios to specify, not execute

Locate/order/cancel retries preserve inventory; no over-lending; cover returns correct quantities; recalls under closed/illiquid market have explicit outcomes; margin and fees reconcile after restart.

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

## Securities-lending/short policy review — 2026-11-16

[L01–L11](POLICY-REVIEW.md) record approved personal short scope/combined exposure, opt-in supply/no re-lending, 60s IOC locates, 150/130% collateral, restricted proceeds, 2–20% financial-year borrow APR/80–20 collected-fee split, calls/recalls/buy-ins, residual obligations and B8 dividend/action handoffs, accepted 2026-11-16 with “this works for me”; no implementation selected.
