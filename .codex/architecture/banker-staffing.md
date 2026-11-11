# A7-05 banker staffing contracts

This package adds staffed banker desks and private banking dialogue to captured village branches. It does not change village placement rules or fix superflat village generation.

## Physical desks

`economy:banker_desk` is a two-wide, two-high, one-deep four-cell block. Cell zero is the sole block-entity anchor. Local right is the facing direction’s clockwise direction (north-facing desks extend east), unlike the older kiosk geometry. Both lower cells require support. Oak, jungle, acacia and spruce palettes follow village styles; the monitor, ledger and hardware assets are original.

Manual placement checks all four loaded cells, support, world bounds, permission and entity collision, then installs under a geometry guard with rollback. It ignores item block-state/entity identity components. Every new player placement has a fresh random unbound generation. Breaking any cell tears down matching loaded cells, with one Survival item drop or zero Creative drops. Unloaded geometry observations defer validation; the component does not fill missing cells or repair construction.

A generated anchor stores branch, desk generation and placement operation identities. These are evidence inputs, not banking authority. Only a captured latest branch desk slot with matching coordinates and generation can acquire staffing. Legacy vanilla slab/lectern/glass furniture is UNBOUND for staffing and is not automatically upgraded. Placing a visually identical desk never restores a removed assignment.

## Detached durable projection

Schema 39 adds `banker_slots`, `banker_employee_history` and `banker_slot_events`. Capacity is at most two captured desk slots per branch. Each current row records its desk identity/location/state, assignment UUID, employee UUID, phase, elapsed eligible ticks, revision and last operation. Employee and assignment identities are never reused; history retains older dead employees after replacement. No customer account, institutional capital or other financial entity is created by staffing.

All mutations pass through `BankStaffing` journal handlers with canonical bounded commands, fresh branch revision, row revision CAS and a refreshed internal live-authority lease. Durable operation lookup/replay resolves the original operation before any new attempt. Server adapters provide physical and permission evidence; no client-supplied NBT or packet can mint it. Offline/startup/recovery validation checks exact schema, captured desk capacity, canonical rows, event continuity and transitions, latest projection/history consistency, and matching immutable journal facts.

## Lifecycle

- ATTACH requires an ACTIVE unpaused branch and a verified captured desk. It durably reserves fresh employee/assignment identities before entity creation.
- BEGIN changes RESERVED to SPAWNING before the physical attempt. LIVE confirms the exact employee/assignment after successful verified creation. Pausing does not prevent confirmation of an existing employee.
- UNKNOWN freezes an uncertain SPAWNING attempt; an exact positively loaded employee/assignment can confirm LIVE without another spawn. DEFER can return it to RESERVED only with positive proof that no physical spawn was attempted; identities remain unchanged.
- A positively observed DEATH for the exact current identity changes RESERVED, SPAWNING, LIVE or UNKNOWN to VACANT and marks history DEAD. An unloaded chunk or absent entity observation never counts as death.
- DESK_REMOVED persists removal before block identity invalidation and freezes further hiring/progress. If removal precedes first ATTACH, a REMOVED/UNKNOWN tombstone with nonspawned identities still occupies the captured slot. This prevents later loading from recreating capacity.
- CHECKPOINT advances only VACANT slots with an ACTIVE desk and ACTIVE unpaused branch. Each earned delta is 1–200 ticks and the total clamps at 24,000. No offline or unloaded time is added.
- REPLACE requires the full 24,000 eligible loaded ticks and current safe physical evidence. It durably reserves a fresh employee/assignment pair while retaining the old DEAD history.
- PAUSE atomically flushes earned per-slot progress with the branch pause revision/event. Resume retains earned progress. Operator permission proof is distinct from desk/entity proof.

Normal progress checkpoints begin at 100 ticks, with at most 200 earned ticks buffered while storage acknowledgements are pending; unavailable observation at that bound adds zero. Progress batches contain at most 200 eligible ticks. A crash can lose at most 200 recently earned ticks; recovery must never credit unearned time or replace an employee merely because an observation is uncertain. Shutdown/outbox handling retains original checkpoint operations and positive death/removal events for durable replay. Physical removal and event records do not automatically rebuild desks or buildings.

## Runtime and dialogue

The server adapter watches loaded captured desks and exact employee identities without forcing chunks. It rechecks session, branch, desk, identity, occupancy and permission at asynchronous acknowledgements. Unloaded, paused, blocked or uncertain slots cannot authorize another physical employee. Existing live entities remain persistent; their presence is confirmed separately from financial-service eligibility.

Banker dialogue is private to the player. Requests revalidate the banker/desk/branch association and route through a currently available trusted branch kiosk and existing banking authorization. A skin, profession, nearby furniture or stale menu does not grant account access. No new financial functionality or bypass of existing account permissions is introduced.

Operator branch status exposes staffing observations; `/economy branch staffing <branch-id> pause|resume` controls staffing without deleting existing employees. There is no automatic legacy-furniture conversion, uncertain-spawn replacement, desk repair, chunk forcing or superflat placement change in this package.

## Presentation and integrated acceptance

The banker uses a 64×64 original skin with separate eyebrows, inward pupils and a compact 1.5×1.5×0.75 model-unit nose. Suit details are painted onto the standard humanoid body. Desks use original veneer and hardware textures. [A7-06 evidence](../A7/verification/A7-06.md) records direct banking before dialogue, same-save process recovery and account independence from staffing or hardware loss.
