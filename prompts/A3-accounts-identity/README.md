# A3 — Accounts, identity and permissions

Status: draft DESIGN prompt. Refine specifications and future prompts only. Do not implement code, scaffold, run Gradle/tests/game processes, generate assets, change dependencies, or migrate worlds. Edit planning files only when explicitly requested. Reading a roadmap or this file is not implementation authorization.

## Goal and dependencies

Prepare a complete, reviewable specification for A3. Required upstream work: A1; A2 currency contracts before integration. Do not treat existing code as completed redesign work. Supply approved upstream contracts explicitly; mark missing contracts unresolved.

Fixed baseline: Minecraft 1.21.1, NeoForge 21.1.252, Java 21; server-authoritative behavior on both integrated and dedicated servers. Stage A owns money/FX/credit; custody owns shares/return obligations; no market-owned parallel banking database.

## Scope to flesh out

Player and system authority; typed owner references for future integrations; account types/lifecycle; multi-currency balances; available/reserved/restricted definitions; stable lookup; limits and administration.

## Mandatory current-feature reimplementation

Inventory coverage: F07, F06 account collision/protection.

Reimplement permanent primary, maximum-three distinct-currency bank workflow, separate trading funding identity and teller-only opening/closure unless an explicit replacement is approved. Define collision merging/protection during rebase. Design account closure now but defer balance movement to A4 and physical teller access to A5.

All assigned existing behavior must appear in the coverage table even when replacement architecture differs. Record any proposed behavior change and its approval status. Existing implementation and existing tests do not satisfy the new phase gate.

## Proposed bounded subprompts

Identity and authorization; account structure/states; lifecycle and limits; fund categories; account query/command contracts.

For each subprompt define outcome, owner, prerequisites, persistence/entities, service/network contracts, permissions, state transitions, transaction boundary, server/worker handoff, time/version semantics, idempotency, configuration and diagnostics. Specify failures, overflow/rounding, stale requests, disconnects, retries and recovery where relevant. Avoid production code and premature class/file prescriptions.

## Acceptance scenarios to specify, not execute

Unauthorized opening/lookup/change rejected; duplicate currency/limit/protection enforced; closed accounts retain audit references. Monetary movement disabled until A4.

Include concrete numerical or state-transition examples where useful. Describe future unit, integration, restart/crash and performance checks appropriate to each subprompt. Distinguish proposed guarantees from verified behavior. Future runnable deliveries require configured runClient/runServer, mod registration, required development runtime dependencies, and runClient help/dry-run verification; run no checks during this planning task.

## Required planning response

- Phase specification and dependency/ownership map.
- F-ID coverage table: old behavior, new contract, owning subprompt, acceptance cases and explicit later handoff.
- Ordered self-contained future prompts with scope exclusions and completion gates.
- Decisions needed now, with recommended alternatives and consequences; do not silently approve defaults.
- Open questions and approved-versus-proposed rules.

Stop at planning. Do not execute the prompts you draft or expand into later-phase implementation.

## Design draft

See [the proposed specification and execution packages](DESIGN.md) for review.

Organization registration, roles and delegated access are tracked as a [deferred integration task](../planning/deferred-organization-identity.md).

## Draft execution packages

Read the [shared contract](CONTRACT.md). Invoke each package separately after its prerequisites are verified.

1. [A3-01 Parties and authorization](A3-01.md) · Verified. See the [handoff](../../.codex/A3/A3-01.md).
2. [A3-02 Account catalog and product constraints](A3-02.md) · Verified. See the [handoff](../../.codex/A3/A3-02.md).
3. [A3-03 Lifecycle and limits](A3-03.md) · Verified. See the [handoff](../../.codex/A3/A3-03.md).
4. [A3-04 Funds vocabulary and account queries](A3-04.md) · Verified. See the [handoff](../../.codex/A3/A3-04.md).
5. [A3-05 Rebase mapping and integration handoff](A3-05.md) · Verified. See the [handoff](../../.codex/A3/A3-05.md).
