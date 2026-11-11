# Collection Agent gameplay

A6-11 registers the original `economy:collection_agent` civilian entity, its humanoid renderer and private trading-style payment dialogue. The existing64×64 skin and folio/satchel geometry from A6-05 are reused. Minecraft1.21.1, NeoForge21.1.252 and Java21 retain their pins.

## Presentation and work bounds

One automatically managed, unsaved presentation follows each online debtor and aggregates eligible cases. Owner/world cases arrive through the existing storage worker. Ticks operate on immutable snapshots. One player read is dispatched per server tick, with round-robin cohort polling and a freshness window of1000ms plus50ms per online player. Financial catch-up immediately disables cached navigation, chat and interaction. Local expiry is checked against current UTC before every presentation action.

Spawn searches examine at most16 deterministic candidates in an8–12-block ring, within±3 vertical blocks. A global four-candidate budget and200-tick retry/relocation timer bound searches. Candidates require already loaded chunks, safe solid support, two free blocks, no hazardous support or fluid, border containment and unobstructed collision space. Navigation checks loaded surroundings, refreshes at most every20 ticks and stops within three blocks. Distance beyond32 blocks or200 ticks of blocked progress triggers bounded safe relocation. The manager creates no chunk tickets.

The agent has20 health and movement speed0.25. It has no attack goals, target assignment or attack implementation. It cannot push in either direction, open doors, trample farmland, trigger pressure plates, carelessly step on eggs, place a wither rose, ride, be ridden, be leashed or change dimensions. It drops no items or experience and has no recipe or natural spawn registration. Peaceful mode retains the civilian. Only the debtor receives the eight-second initial action-bar greeting and reminders at least one financial day apart within eight blocks.

`CollectionProtection.setPredicate` supplies a default-allow spawn/interaction predicate for separate integration mods. Denial suppresses presentation independently of debt and cooldown. No specific external protection integration is bundled.

## Creative placement

`/summon economy:collection_agent` and `economy:collection_agent_spawn_egg` create unbound representatives. The egg uses NeoForge's deferred spawn-egg registration, vanilla egg model/tinting and the Economy/Spawn Eggs creative tabs. Unbound entities have no navigation goals and persist through ordinary entity saves; managed entities still override `shouldBeSaved` to false. Only `bind` creates a managed presentation, and debtor/lease data are never loaded from item or entity NBT.

Unbound representatives use their entity UUID as menu presentation identity. They permit private owner-authorized dialogue within six blocks, with live server/economy, player, entity, dimension and interaction-protection checks. Their case reads do not require a LIVE presence lease; managed menus retain the matching LIVE lease check. Both paths use the existing caller's owner grant and financial APIs. Placing, removing or killing an unbound representative neither reserves a lease nor changes the automatic cooldown. Automatic encounter placement alone uses the bounded search and spawn-protection predicate above.

## Durable leases and death

V34 adds `collection_agent_state`, keyed by world/owner, and the partial `collection_agent_eligible` index for unsettled collectible cases. The state stores lease UUID, phase, observed UTC, cooldown expiry, revision and canonical body. Phases are LIVE, RELEASED, KILLED and UNCERTAIN. New v1 `economy:a6/collection-presence` facts and stable `economy:collection-agent` / `economy:presentation` operations use the existing journal and worker.

A committed RESERVE precedes physical spawning. RELEASE removes only the matching live presentation and preserves debt. Accepted death records KILLED with at least seven financial days from the observed death instant. An orphaned LIVE lease after an interrupted process is reconciled to UNCERTAIN with seven days from recovery. This deliberately protects against death occurring before its queued save reached storage. The managed entity itself is excluded from world saves.

Logout, unload, travel and normal stop enqueue lease-bound release. Stop queues removal before service drainage. Death is separate from removal and cannot be cleared by a later release. Failed or uncertain transitions block replacement until fresh authoritative state resolves them. A failed spawn releases its reservation. Physical appearance/disappearance is a non-atomic presentation effect outside the journal, reconciled by the committed lease. It never supplies payment or debt evidence.

Owner-bound snapshot reads check financial readiness on the worker before opening their read transaction. RESERVE also requires current finance. RELEASE, DEATH and RECONCILE are narrowly permitted during catch-up so presentation removal and cooldowns can drain at shutdown. They produce presentation facts only. Inspection grants cannot invoke owner capabilities.

## Menu and payment protocol

For managed encounters, right-click requires the debtor, the current owned entity/lease, the same dimension, distance at most six blocks and the protection predicate. `economy:collection` is a dedicated server-issued menu with entity UUID, presentation UUID, random menu session and increasing revision. `economy:collection_action` and `economy:collection_snapshot` are registered v1 transport payloads. Actions use allowlisted controls and server-offered identifiers. Snapshots contain at most16 lines/choices and12KB. Case and BANK funding selectors use four-row pages. Slot/drag/quick-move handling transfers no items.

Partial payments accept any positive exactly representable ledger amount. CHF ledger cents remain valid independently of physical coin denominations. Plain bounded decimals reject exponent abuse, overflow and fractional minor units. Full payment uses the selected remaining balance. Native and explicit-FX previews capture case/funding revisions, current creditor, exact debt credit, source debit, separate FX fee and expiry before confirmation. Updated revisions invalidate the displayed selection. Existing collection settlement APIs recheck every financial precondition inside the original transaction.

At most one unresolved original payment per debtor and256 per server is retained. Duplicate confirmations fail menu/revision/busy checks. Closing the menu cancels waiting presentation only. Unknown outcomes retry the original intent, operation and quote. Before submission, `economy_collection_payment` stores world/operation UUIDs in verified player data. Saving this pointer is separate from financial commitment. Owner/world/actor/kind-restricted receipt lookup resolves it after restart. A bounded online recovery poll also resolves receipts without an agent, including after final settlement. A fresh payment requires explicit new preview after the old result resolves.

`LedgerService` adds `collectionAgentState`, `collectionAgentTransition` and `lookupCollectionPaymentReceipt`. Existing native/FX quote and repayment contracts retain their authority and original-operation recovery. Server callbacks recheck player instance, world/session, menu identity and entity lease before publication. Exit, previews, rejected payments, removal and death never acknowledge debt.

## Verification and next package

[Acceptance evidence](../A6/verification/A6-11.md) records unit, production-runtime and disposable Minecraft menu/entity checks. The opted-in fixture uses explicit test funding and frozen claims, follows normal server menu/quote/settlement paths and is excluded from release jars. It verifies partial/full payment once, unload replacement, death/reconnect, no loot/XP, receipt recovery without an agent and a second-process cooldown check. It uses fixture chunk tickets to prepare its arena, independently of the agent algorithm.

A6-12 owns broader bank/credit menus and public integration. Manual visual GUI inspection, in-game FX confirmation and actual dimension travel remain outside this package's recorded fixture coverage. Their financial/protocol gates and dimension checks are implemented, with underlying FX settlement covered upstream.
