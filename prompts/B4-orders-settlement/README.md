# B4 — Orders, execution, settlement and trading UI

Status: draft DESIGN prompt. Refine specifications and future prompts only. Do not implement code, scaffold, run Gradle/tests/game processes, generate assets, change dependencies, or migrate worlds. Edit planning files only when explicitly requested. Reading a roadmap or this file is not implementation authorization.

## Design brief

[First-pass design brief](DESIGN.md) · [B-series dependencies and shared contracts](../planning/B-SERIES-DESIGN.md). Drafts distinguish fixed requirements from proposed decisions; no implementation or policy approval is implied.

## Goal and dependencies

Prepare a complete, reviewable specification for B4. Required upstream work: B2, B3; approved Stage A core. Do not treat existing code as completed redesign work. Supply approved upstream contracts explicitly; mark missing contracts unresolved.

Fixed baseline: Minecraft 1.21.1, NeoForge 21.1.252, Java 21; server-authoritative behavior on both integrated and dedicated servers. Stage A owns money/FX/credit; custody owns shares/return obligations; no market-owned parallel banking database.

## Scope to flesh out

Market/limit orders, cancellation, states/expiration/time-in-force, partial fills/rejection, quote/tick/permissions checks; simulated and eligible player matching; commissions/FX; fund/share holds; atomic settlement; reports/history/receipts.

## Mandatory current-feature reimplementation

Inventory coverage: F16 trading, F17 charts, F18 trading UI, F19 market validation, F20 market API, F21 computer, F22 computer art.

Reimplement whole-share buy/sell, funding/unfunding, stock search/selection, holdings/chart panel and functional four-cell trading computer. Replace immediate unbounded fixed-price execution with approved liquidity and custody contracts. Existing price/order/trade currencies remain accurately labeled across rebase/history.

All assigned existing behavior must appear in the coverage table even when replacement architecture differs. Record any proposed behavior change and its approval status. Existing implementation and existing tests do not satisfy the new phase gate.

## Proposed bounded subprompts

Order state machine/idempotency; reservations; matching/fills/cancellation races; fees and cash/custody settlement; report/query APIs; trading menus/networking and computer assets.

For each subprompt define outcome, owner, prerequisites, persistence/entities, service/network contracts, permissions, state transitions, transaction boundary, server/worker handoff, time/version semantics, idempotency, configuration and diagnostics. Specify failures, overflow/rounding, stale requests, disconnects, retries and recovery where relevant. Avoid production code and premature class/file prescriptions.

## Acceptance scenarios to specify, not execute

Duplicate packets cannot duplicate orders/fills; partial fill plus cancel releases only remainder; stale quotes/insufficient liquidity/closed sessions explicit; rejected settlement changes neither asset; histories and holdings reconcile after restart.

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

## Order policy review

[O01–O10](POLICY-REVIEW.md) record approved market/limit types, 1% protection, IOC/DAY/30-day GTC, closed-session limit queuing, price-time/anonymous player matching, partial fills, cancellation/revocation and exact settlement-lot rules, accepted 2026-11-14 with “yeppers”; no implementation selected.
