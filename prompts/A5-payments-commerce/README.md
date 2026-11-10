# A5 — Payments, cash, shops and physical terminals

Status: draft DESIGN prompt. Refine specifications and future prompts only. Do not implement code, scaffold, run Gradle/tests/game processes, generate assets, change dependencies, or migrate worlds. Edit planning files only when explicitly requested. Reading a roadmap or this file is not implementation authorization.

## Goal and dependencies

Prepare a complete, reviewable specification for A5. Required upstream work: A4. Do not treat existing code as completed redesign work. Supply approved upstream contracts explicitly; mark missing contracts unresolved.

Fixed baseline: Minecraft 1.21.1, NeoForge 21.1.252, Java 21; server-authoritative behavior on both integrated and dedicated servers. Stage A owns money/FX/credit; custody owns shares/return obligations; no market-owned parallel banking database.

## Scope to flesh out

Payment requests/invoices; authorize/capture/cancel/refund states; merchants/fees/FX; receipts; third-party commerce API; durable item fulfillment and recovery; cash/card/ATM/teller/register gameplay.

## Mandatory current-feature reimplementation

Inventory coverage: F10–F14, F18 banking workflows, F19 banking validation, F20 shop command, F21 banking machines, F22 banking art.

Reimplement all 118 cash and eight card identities, twelve creative-tab organization, alternate CAD art, generic card access/no credit, 24-slot deposits and exact withdrawals. Preserve ATM bills-only, teller automatic coins, denomination whitelist/modes/owner configuration, feasible bill edits, stale plan validation and item return behavior. Rebuild register batch/component matching, source-container stock, pricing/ownership and refund/delivery fallback rules. Include 17 ATM/register colors, teller footprint/placement/recipes/loot/assets. Trading computer activation is B4.

All assigned existing behavior must appear in the coverage table even when replacement architecture differs. Record any proposed behavior change and its approval status. Existing implementation and existing tests do not satisfy the new phase gate.

## Proposed bounded subprompts

Payment lifecycle and merchant service; cross-save fulfillment/reconciliation; cash issuance/access/catalog; deposit workflow; withdrawal planner; terminal settings; shop stock/payment workflow; physical blocks/assets and menus.

For each subprompt define outcome, owner, prerequisites, persistence/entities, service/network contracts, permissions, state transitions, transaction boundary, server/worker handoff, time/version semantics, idempotency, configuration and diagnostics. Specify failures, overflow/rounding, stale requests, disconnects, retries and recovery where relevant. Avoid production code and premature class/file prescriptions.

## Acceptance scenarios to specify, not execute

No duplicate capture/refund/delivery; reject insufficient stock/funds/space before inappropriate effects; reconnect/restart/crash matrix reconciles cash and goods; altered terminal settings invalidate plans; unauthorized packets/configuration reject. Cover 20/50 bill combinations, impossible exact amounts and forged coin edits.

Include concrete numerical or state-transition examples where useful. Describe future unit, integration, restart/crash and performance checks appropriate to each subprompt. Distinguish proposed guarantees from verified behavior. Future runnable deliveries require configured runClient/runServer, mod registration, required development runtime dependencies, and runClient help/dry-run verification; run no checks during this planning task.

## Required planning response

- Phase specification and dependency/ownership map.
- F-ID coverage table: old behavior, new contract, owning subprompt, acceptance cases and explicit later handoff.
- Ordered self-contained future prompts with scope exclusions and completion gates.
- Decisions needed now, with recommended alternatives and consequences; do not silently approve defaults.
- Open questions and approved-versus-proposed rules.

Stop at planning. Do not execute the prompts you draft or expand into later-phase implementation.

[A5 shared contract](contract.md) · [A5 specification and package sequence](PLAN.md)

## Approved execution prompts

The project owner reviewed and approved all 13 prompts. A5-01-01 through A5-02-03 and A5-03 through A5-09 are verified.
Invoke one package at a time in the order below after its prerequisite
handoff is verified.

- [A5-01-01 — Payments scheduling and configuration](A5-01-01.md) · [Verified handoff](../../.codex/A5/A5-01-01.md)
- [A5-01-02 — Payment diagnostics](A5-01-02.md) · [Verified handoff](../../.codex/A5/A5-01-02.md)
- [A5-01-03 — Payment foundation acceptance](A5-01-03.md) · [Verified handoff](../../.codex/A5/A5-01-03.md)
- [A5-02-01 — Custody scheduling and configuration](A5-02-01.md) · [Verified handoff](../../.codex/A5/A5-02-01.md)
- [A5-02-02 — Custody diagnostics](A5-02-02.md) · [Verified handoff](../../.codex/A5/A5-02-02.md)
- [A5-02-03 — Custody foundation acceptance](A5-02-03.md) · [Verified handoff](../../.codex/A5/A5-02-03.md)
- [A5-03 — Cash and cards](A5-03.md) · [Verified handoff](../../.codex/A5/A5-03.md)
- [A5-04 — Terminal policy](A5-04.md) · [Verified handoff](../../.codex/A5/A5-04.md)
- [A5-05 — Deposits](A5-05.md) · [Verified handoff](../../.codex/A5/A5-05.md)
- [A5-06 — Withdrawals](A5-06.md) · [Verified handoff](../../.codex/A5/A5-06.md)
- [A5-07 — Register sales](A5-07.md) · [Verified handoff](../../.codex/A5/A5-07.md)
- [A5-08 — Physical terminals and assets](A5-08.md) · [Verified handoff](../../.codex/A5/A5-08.md)
- [A5-09 — Menus and phase acceptance](A5-09.md) · [Verified handoff](../../.codex/A5/A5-09.md)
