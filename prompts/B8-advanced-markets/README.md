# B8 — Advanced orders, corporate actions and indices

Status: draft DESIGN prompt. Refine specifications and future prompts only. Do not implement code, scaffold, run Gradle/tests/game processes, generate assets, change dependencies, or migrate worlds. Edit planning files only when explicitly requested. Reading a roadmap or this file is not implementation authorization.

## Design brief

[First-pass design brief](DESIGN.md) · [B-series dependencies and shared contracts](../planning/B-SERIES-DESIGN.md). Drafts distinguish fixed requirements from proposed decisions; no implementation or policy approval is implied.

## Goal and dependencies

Prepare a complete, reviewable specification for B8. Required upstream work: B5–B7. Do not treat existing code as completed redesign work. Supply approved upstream contracts explicitly; mark missing contracts unresolved.

Fixed baseline: Minecraft 1.21.1, NeoForge 21.1.252, Java 21; server-authoritative behavior on both integrated and dedicated servers. Stage A owns money/FX/credit; custody owns shares/return obligations; no market-owned parallel banking database.

## Scope to flesh out

Stop/stop-limit/additional order types; dividends; splits/reverse splits/consolidation; mergers/delistings; earnings/news; exchange halts/volatility controls; indices and historical analytics; economic correlation.

## Mandatory current-feature reimplementation

Inventory coverage: No substantive current implementation.

Corporate actions must transform lots, custody, lending liabilities, dividends-equivalent payments, collateral, orders/ticks and histories coherently. Decide named-index completeness/approximation against the actual 800 listings; do not invent full coverage.

All assigned existing behavior must appear in the coverage table even when replacement architecture differs. Record any proposed behavior change and its approval status. Existing implementation and existing tests do not satisfy the new phase gate.

## Proposed bounded subprompts

Advanced order triggers; dividend scheduling; split/merger/delist lifecycle; halts; earnings/news; index methodology and analytics.

For each subprompt define outcome, owner, prerequisites, persistence/entities, service/network contracts, permissions, state transitions, transaction boundary, server/worker handoff, time/version semantics, idempotency, configuration and diagnostics. Specify failures, overflow/rounding, stale requests, disconnects, retries and recovery where relevant. Avoid production code and premature class/file prescriptions.

## Acceptance scenarios to specify, not execute

Actions apply once at effective time; fractional entitlements/rounding have explicit destinations; shorts/lenders receive correct obligations; halted/triggered orders transition consistently; index constituent/FX method disclosed.

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

## Player corporations and news — 2026-11-16

[Expanded scope and answers](CORPORATIONS-EXPANSION.md) add private equity/classes/issues/splits, same/cross-player mergers, founder control with optional class voting, and optional exchange listing. Fictional in-game news uses master UTC. [B8-08 overview](B8-08.md) links four new children; B8 now has 13 selectable drafts. Exact governance/action/listing/news/other B8 policies remain for review; no implementation selected.

The user approved the [expansion refinements](CORPORATIONS-EXPANSION.md#approval-record--2026-11-16): one-way transition to class voting and synchronized fictional-news announcement/first effect on master UTC, with session-aware execution and fact-backed player-action announcements. The user subsequently approved the initial share-class, issuance, voting, dividend, split and merger rules. The user also approved the launch listing and valuation rules. Stop and halt rules are approved. Fictional news calibration is approved. Use the [approved index methodology](INDEX-METHODOLOGY-REVIEW.md) for B8-06. Use the [approved curated issuer action rules](CURATED-ISSUER-ACTIONS-REVIEW.md) for B8-03 and B8-04. See the [approved news calibration](NEWS-CALIBRATION-REVIEW.md). See the [approved equity and action rules](EQUITY-AND-ACTIONS-REVIEW.md).

B8-08-04 uses the [approved listing and valuation rules](LISTING-AND-VALUATION-REVIEW.md) for admission, first trade and launch financial eligibility.

B8-01 and B8-02 use the [approved stop and halt rules](STOPS-AND-HALTS-REVIEW.md) for triggers, price protection, expiry and reopening.
