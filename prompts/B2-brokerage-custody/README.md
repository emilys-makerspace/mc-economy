# B2 — Brokerage, custody and portfolios

Status: draft DESIGN prompt. Refine specifications and future prompts only. Do not implement code, scaffold, run Gradle/tests/game processes, generate assets, change dependencies, or migrate worlds. Edit planning files only when explicitly requested. Reading a roadmap or this file is not implementation authorization.

## Design brief

[First-pass design brief](DESIGN.md) · [B-series dependencies and shared contracts](../planning/B-SERIES-DESIGN.md). Drafts distinguish fixed requirements from proposed decisions; no implementation or policy approval is implied.

## Goal and dependencies

Prepare a complete, reviewable specification for B2. Required upstream work: A4, B1. Do not treat existing code as completed redesign work. Supply approved upstream contracts explicitly; mark missing contracts unresolved.

Fixed baseline: Minecraft 1.21.1, NeoForge 21.1.252, Java 21; server-authoritative behavior on both integrated and dedicated servers. Stage A owns money/FX/credit; custody owns shares/return obligations; no market-owned parallel banking database.

## Scope to flesh out

Brokerage linked to existing identity/banking; balanced securities custody movements; free/reserved/pledged/loaned quantities; lots/cost basis/realized P&L; cross-listing aggregation; FX valuation/history; cash-security reconciliation.

## Mandatory current-feature reimplementation

Inventory coverage: F16 holdings, F07 trading account, F08 funding contract.

Reimplement persistent holdings, trading funds separation, funding/unfunding and owned-account queries. Define exact settlement coordination before B4; holdings may not appear from nowhere as they do under the implicit current market counterparty. UI query contracts can be designed now; player execution waits B4.

All assigned existing behavior must appear in the coverage table even when replacement architecture differs. Record any proposed behavior change and its approval status. Existing implementation and existing tests do not satisfy the new phase gate.

## Proposed bounded subprompts

Brokerage lifecycle/funding; custody postings/reservations; positions/lots; valuation/history; atomic settlement interface.

For each subprompt define outcome, owner, prerequisites, persistence/entities, service/network contracts, permissions, state transitions, transaction boundary, server/worker handoff, time/version semantics, idempotency, configuration and diagnostics. Specify failures, overflow/rounding, stale requests, disconnects, retries and recovery where relevant. Avoid production code and premature class/file prescriptions.

## Acceptance scenarios to specify, not execute

No oversell/duplicate reservation; custodied quantities reconcile to issuer/counterparty/treasury inventories; multi-currency value labels source and FX versions; replay preserves ownership and cost basis.

Include concrete numerical or state-transition examples where useful. Select proportionate future checks for meaningful changed behavior under the project verification policy; categories are options, not cumulative gates. Distinguish proposed guarantees from verified behavior. Future runnable deliveries require configured runClient/runServer, mod registration, required development runtime dependencies, and runClient help/dry-run verification; run no checks during this planning task.

## Required planning response

- Phase specification and dependency/ownership map.
- F-ID coverage table: old behavior, new contract, owning subprompt, acceptance cases and explicit later handoff.
- Ordered self-contained future prompts with scope exclusions and completion gates.
- Decisions needed now, with recommended alternatives and consequences; do not silently approve defaults.
- Open questions and approved-versus-proposed rules.

Stop at planning. Do not execute the prompts you draft or expand into later-phase implementation.

## Package drafts — 2026-11-13

[Shared phase contract](CONTRACT.md) and [individually selectable package queue](EXECUTION.md) now refine this design brief. All packages remain draft/unselected; unresolved consequential policies are [recorded centrally](../planning/B-DECISIONS.md). Preserve the specific approvals already recorded in DESIGN.md. The shared [B-stage contract](../planning/B-SHARED.md) governs authority, corporate-owner boundaries, verification and handoffs. This drafting runs no Gradle, tests or game processes.

## Brokerage policy review

[R01–R09](POLICY-REVIEW.md) record approved account/currency structure, bank relationship, explicit cash/FX funding, FIFO/basis/fees/reporting, corporate brokerage scopes and closure, accepted 2026-11-14 with “yeppers”; no implementation selected.
