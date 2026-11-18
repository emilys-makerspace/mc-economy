# B1 — Companies, securities, exchanges and listings

Status: draft DESIGN prompt. Refine specifications and future prompts only. Do not implement code, scaffold, run Gradle/tests/game processes, generate assets, change dependencies, or migrate worlds. Edit planning files only when explicitly requested. Reading a roadmap or this file is not implementation authorization.

## Design brief

[First-pass design brief](DESIGN.md) · [B-series dependencies and shared contracts](../planning/B-SERIES-DESIGN.md). Drafts distinguish fixed requirements from proposed decisions; no implementation or policy approval is implied.

Approved decision, 2026-11-13: [venue allocation](DESIGN.md#approved-venue-allocation--2026-11-13) totals **800 listings**, including **95 Euronext listings**. Individual catalog rows and other proposed policies remain open.

## Goal and dependencies

Prepare a complete, reviewable specification for B1. Required upstream work: A1, A2. Do not treat existing code as completed redesign work. Supply approved upstream contracts explicitly; mark missing contracts unresolved.

Fixed baseline: Minecraft 1.21.1, NeoForge 21.1.252, Java 21; server-authoritative behavior on both integrated and dedicated servers. Stage A owns money/FX/credit; custody owns shares/return obligations; no market-owned parallel banking database.

## Scope to flesh out

Independent company/security/share-class/exchange/listing IDs; ISO MICs; industries; outstanding shares; primary/secondary listing relationships; fungible/convertible/nonfungible instruments; tick sizes/currencies; calendars; approximately 800 target listings with reviewed exact release count.

## Mandatory current-feature reimplementation

Inventory coverage: F15, F06 market-denomination schema consequences.

Account for all 100 original catalog identities/display names and source-price provenance in normalized concepts; preserve 98 and apply the [two user-approved OTC replacements](DESIGN.md#approved-otc-replacements--2026-11-13); retain importer failure/unavailable handling and approved historical seed policy. Prepare an explicit 800-row allocation, replacing five NOK Oslo proposals with EUR Euronext rows. No trading or price simulation in registry phase.

All assigned existing behavior must appear in the coverage table even when replacement architecture differs. Record any proposed behavior change and its approval status. Existing implementation and existing tests do not satisfy the new phase gate.

## Proposed bounded subprompts

Entity schema/identifiers; exchange calendars/rules; instrument relationships; catalog provenance/import workflow; 800-listing allocation and validation.

For each subprompt define outcome, owner, prerequisites, persistence/entities, service/network contracts, permissions, state transitions, transaction boundary, server/worker handoff, time/version semantics, idempotency, configuration and diagnostics. Specify failures, overflow/rounding, stale requests, disconnects, retries and recovery where relevant. Avoid production code and premature class/file prescriptions.

## Acceptance scenarios to specify, not execute

Duplicate symbol permitted only under explicit venue identity; security relationships and currencies validate; unavailable seeds disable appropriate behavior; count companies/securities/listings separately; no invented tickers/prices or unreviewed naming changes.

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

## Approved catalog policies — 2026-11-13

[Policy record K01–K07](POLICY-REVIEW.md) settles ordinary-share selection, Minecraft-themed names, missing-seed handling and the **2026-11-13 closing-price cutoff**. Delegated selections replace FNMA/FMCC with BlackRock BLK (BlackBlock) and Charles Schwab SCHW (Charles Shulker), within existing NYSE quotas under the documented former-venue interpretation. Exact 800 rows and seed/source/license verification remain. B5 must handle eligible cross-listings through finite executable arbitrage and conserved custody; registry availability alone does not enable cross-venue trading. No implementation selected.

[Calendar policy review](CALENDAR-REVIEW.md) records T01–T07 approved 2026-11-13 for holidays, session scope, UTC/DST, catch-up, calendar coverage, exceptional changes and cross-market behavior. Actual sourced venue/date coverage remains preparation work; implementation is unselected.
