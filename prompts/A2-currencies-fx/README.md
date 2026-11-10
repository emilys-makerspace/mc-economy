# A2 — Currencies, FX and denomination

Status: planning approved on 2026-11-03. [A2-01](../../.codex/A2/A2-01.md), [A2-02](../../.codex/A2/A2-02.md), and [A2-03-01](../../.codex/A2/A2-03-01.md) and [A2-03-02](../../.codex/A2/A2-03-02.md) are verified. Subsequent packages require explicit invocation.

## Goal and dependencies

Prepare a complete, reviewable specification for A2. Required upstream work: A1. Do not treat existing code as completed redesign work. Supply approved upstream contracts explicitly; mark missing contracts unresolved.

Fixed baseline: Minecraft 1.21.1, NeoForge 21.1.252, Java 21; server-authoritative behavior on both integrated and dedicated servers. Stage A owns money/FX/credit; custody owns shares/return obligations; no market-owned parallel banking database.

## Scope to flesh out

Ten currency registry and scale/display rules; exact money/price distinctions; internal reference; deterministic daily rates, trends/volatility inputs; versioned bid/ask quotes, fees/expiry; historical rate persistence and conversions.

## Mandatory current-feature reimplementation

Inventory coverage: F04, F05, F06 denomination contract.

Reimplement configured CAD reference baseline, JPY precision, currency formatting, grouped deposit FLOOR, withdrawal debit CEILING and historical cross-pair paging. Preserve world-selection and explicitly confirmed currency-change capability as a cross-phase contract; account effects belong A3 and posting effects A4. Decide raw versus frozen display treatment. No live rate fetch implied.

All assigned existing behavior must appear in the coverage table even when replacement architecture differs. Record any proposed behavior change and its approval status. Existing implementation and existing tests do not satisfy the new phase gate.

## Proposed bounded subprompts

Currency definitions and formatting; conversion/rounding policy; versioned quote service and history; world denomination contract; deterministic FX evolution interface.

For each subprompt define outcome, owner, prerequisites, persistence/entities, service/network contracts, permissions, state transitions, transaction boundary, server/worker handoff, time/version semantics, idempotency, configuration and diagnostics. Specify failures, overflow/rounding, stale requests, disconnects, retries and recovery where relevant. Avoid production code and premature class/file prescriptions.

## Acceptance scenarios to specify, not execute

JPY/cent conversions, inverse/cross pairs, missing history dates, zero/overflow results, stale quote/version changes and restart-repeat daily generation have explicit outcomes. Currency change never silently relabels historical amounts.

Include concrete numerical or state-transition examples where useful. Describe future unit, integration, restart/crash and performance checks appropriate to each subprompt. Distinguish proposed guarantees from verified behavior. Future runnable deliveries require configured runClient/runServer, mod registration, required development runtime dependencies, and runClient help/dry-run verification; run no checks during this planning task.

## Required planning response

- Phase specification and dependency/ownership map.
- F-ID coverage table: old behavior, new contract, owning subprompt, acceptance cases and explicit later handoff.
- Ordered self-contained future prompts with scope exclusions and completion gates.
- Decisions needed now, with recommended alternatives and consequences; do not silently approve defaults.
- Open questions and approved-versus-proposed rules.

Stop at planning. Do not execute the prompts you draft or expand into later-phase implementation.

## Approved execution packages

Review the [shared contract](CONTRACT.md) before invoking a package. The package drafts were reviewed and approved on 2026-11-03. Execution
requires verified A1 completion and explicit invocation of the selected package.

1. [A2-01 Currency registry and monetary types](A2-01.md)
2. [A2-02 Conversion and settlement rounding](A2-02.md)
3. Daily FX publication
   - [A2-03-01 Scheduling and configuration](A2-03-01.md)
   - [A2-03-02 Diagnostics](A2-03-02.md)
   - [A2-03-03 Foundation acceptance](A2-03-03.md)
4. [A2-04 Quotes and pair history](A2-04.md)
5. [A2-05 World denomination](A2-05.md)

| Feature | Replacement coverage | Handoff |
| --- | --- | --- |
| F04 Fixed ten currencies, JPY, checked money and localized formatting | A2-01 registry and format fixtures | A3/A4 share money and scale contracts |
| F05 Configured historical CAD fixture, grouped FLOOR, withdrawal CEILING, daily UTC pair history, zero spread and fee | A2-02 conversion, A2-03 daily rates rebased to the selected permanent reference, A2-04 quotes and paging | A4 posts trades, A5 handles physical cash |
| F06 First-player selection and disconnect reassignment, double-confirmed admin change, preserved original amounts and frozen display | A2-05 chooser and change protocol, A2-03 opening publication | A3 defines foreign-account preservation and target collision merges, A4 commits account effects and denomination together |
