# B3 — Prices, liquidity and autonomous market simulation

Status: draft DESIGN prompt. Refine specifications and future prompts only. Do not implement code, scaffold, run Gradle/tests/game processes, generate assets, change dependencies, or migrate worlds. Edit planning files only when explicitly requested. Reading a roadmap or this file is not implementation authorization.

## Design brief

[First-pass design brief](DESIGN.md) · [B-series dependencies and shared contracts](../planning/B-SERIES-DESIGN.md). Drafts distinguish fixed requirements from proposed decisions; no implementation or policy approval is implied.

## Goal and dependencies

Prepare a complete, reviewable specification for B3. Required upstream work: A8, B1; B2 participant custody contracts as needed. Do not treat existing code as completed redesign work. Supply approved upstream contracts explicitly; mark missing contracts unresolved.

Fixed baseline: Minecraft 1.21.1, NeoForge 21.1.252, Java 21; server-authoritative behavior on both integrated and dedicated servers. Stage A owns money/FX/credit; custody owns shares/return obligations; no market-owned parallel banking database.

## Scope to flesh out

Fundamentals/economic/sector/company pricing factors; listing-specific quotes; finite market makers/institutions; spreads/volume/impact; sessions; candles; deterministic seeds/state/history and bounded update cost.

## Mandatory current-feature reimplementation

Inventory coverage: F17, F15 seed consumption.

Reimplement stored-price administration and daily history semantics with at most 14-day initial chart view, no invented prehistory and documented carry-forward. Historical sources are seed data, not a live feed. Separate price ticks from currency settlement precision. Design funded/custodied participant budgets and closed-market quotes.

All assigned existing behavior must appear in the coverage table even when replacement architecture differs. Record any proposed behavior change and its approval status. Existing implementation and existing tests do not satisfy the new phase gate.

## Proposed bounded subprompts

Fundamentals and correlated factors; participant inventory/liquidity; sessions; pricing cadence/impact; OHLCV/history and snapshot publication.

For each subprompt define outcome, owner, prerequisites, persistence/entities, service/network contracts, permissions, state transitions, transaction boundary, server/worker handoff, time/version semantics, idempotency, configuration and diagnostics. Specify failures, overflow/rounding, stale requests, disconnects, retries and recovery where relevant. Avoid production code and premature class/file prescriptions.

## Acceptance scenarios to specify, not execute

All target listings evolve without players; deterministic recovery cannot reroll favorable prices; finite volume is consumed; session transitions/catch-up bounded; market simulation cost stays within approved budget independent of menu/player count where possible.

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

## Funding and liquidity review — 2026-11-13

[Approved M01–M08](LIQUIDITY-REVIEW.md) record accepted cash/share capitalization, participant allocation, 60s/5s cadence, spread/depth, risk and autonomous-activity limits, amended to CAD 2.8T initial cash (100×) with the separate up-to-CAD 28B share-value target unchanged; numeric price dynamics and financial-mark policy are now approved under P01–P07; exact formula/source feasibility remains for B3-01. No implementation selected.

## Price-dynamics direction

The user selected moderately active markets. [P01–P07](PRICE-DYNAMICS-REVIEW.md) record approved numeric volatility/correlation, macro response, temporary effects and financial-mark policies, accepted with “yeppers”. Exact deterministic formulas and source/interface feasibility remain for B3-01. No implementation selected.
