# A8 execution queue

Status: technical revision 1 and all 12 packages approved by the user on 2026-11-07; A8-01 through A8-08 complete and accepted in their recorded scopes. Main gameplay decisions are recorded in [the phase plan](../../.codex/architecture/a8-planning.md), with [technical revision 1](../../.codex/architecture/a8-shared-contracts.md) source-confirmed by [A8-01](../../.codex/A8-01.md). A8 execution is complete; see the [acceptance matrix](../../.codex/architecture/a8-acceptance.md) and [A9 downstream boundary](../../.codex/architecture/a8-downstream.md). A9 remains unselected.

Follow [CONTRACT.md](CONTRACT.md), [project workflow](../../.codex/WORKFLOW.md) and [verification policy](../../.codex/VERIFICATION.md). Execution approval is recorded for all listed packages, subject to prerequisites. The user must still explicitly select the package to begin. A selection begins only that package; no automatic dependent execution, broad tests or game launches.

| Package | Outcome | Prerequisite | Status |
| --- | --- | --- | --- |
| [A8-01](A8-01.md) | Shared contracts and pinned-source feasibility | Accepted A7/upstream evidence and approved A8 gameplay | Complete: source/docs only |
| [A8-02-01](A8-02-01.md) | Activity attribution | [A8-01](A8-01.md) | Complete: attribution foundation |
| [A8-02-02](A8-02-02.md) | Immutable windows and deterministic model | [A8-02-01](A8-02-01.md) | Complete: immutable windows/model foundation |
| [A8-03-01](A8-03-01.md) | Markets, identity and pricing | [A8-02-02](A8-02-02.md) | Complete: detached market/catalog/pricing foundation |
| [A8-03-02](A8-03-02.md) | Entity hooks, stock and progression | [A8-03-01](A8-03-01.md) | Complete: owned entity hooks/stock/progression adapters |
| [A8-04-01](A8-04-01.md) | Account, NPC and FX settlement | [A8-03-02](A8-03-02.md) | Complete: account/NPC/FX API foundation |
| [A8-04-02](A8-04-02.md) | Cash and physical custody | [A8-04-01](A8-04-01.md) | Complete: cash/physical-custody APIs |
| [A8-05](A8-05.md) | Currency-specific prime | [A8-04-02](A8-04-02.md); A8-02-02 model contract | Complete: prime/loan-capture foundation |
| [A8-06-01](A8-06-01.md) | Fixed retirement and effective-time FX foundation | [A8-05](A8-05.md) | Complete: retirement/exact-time foundation |
| [A8-06-02](A8-06-02.md) | Financial-day rate provider and FX handover | [A8-06-01](A8-06-01.md); all prior A8 package handoffs | Complete in [recorded scope](../../.codex/A8-06-02.md) |
| [A8-07](A8-07.md) | Configuration, diagnostics, menus and production integration | [A8-06-02](A8-06-02.md) | Complete in [recorded scope](../../.codex/A8-07.md) |
| [A8-08](A8-08.md) | Scoped acceptance and A9 handoff | [A8-07](A8-07.md) | Accepted in [recorded scope](../../.codex/A8-08.md) |

The split replaces provisional broad A8-02/03/04/06 planning rows; only the exact IDs above are executable-on-selection prompts. Implementation packages follow the order shown even where a technical interface could be developed independently. A8-04 may deliver API-only settlement; the complete monetary menus and production activation belong to A8-07. A8-01 is document/source review only and cannot establish runtime acceptance.

Before selection, consult current prerequisite handoffs and actual changed interfaces, not a historical checklist. At handoff record outcome/evidence/limits, then stop. The main agent runs Gradle/game tasks sequentially and owns integration; delegated ownership/check limits inherit the contract. A8-08 reuses unchanged evidence and closes only concrete remaining integrated concerns; it is not a full-suite trigger.
