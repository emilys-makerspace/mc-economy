# Recovery, projections, offline maintenance and clock

## Three separate recovery guarantees

1. SQLite engine recovery: opening the sole owned connection recovers SQLite transactions/WAL.
   It does not validate application meanings, rebuild projections or reconcile inventories.
2. Application recovery: explicit record decoders validate journal facts, then deterministic
   reducers rebuild derived generations from stored outcomes. No historical commands,
   handlers, random decisions, cache publishers or external deliveries are replayed.
3. Backup restore: a validated stopped complete-world image is copied into a separate destination.
   It restores Minecraft and economy from one snapshot, not post-backup player actions.

Neither these tests nor SQLite's contract are hardware power-loss/failed-filesystem repair proof.
A privileged administrator can replace DB/software; protections are not cryptographic tamper proofing.

## Incremental startup and readiness

FoundationRuntime replaces the production bare SqliteStorage lifecycle. Order:

- Acquire exclusive world storage ownership; SQLite engine recovery; identity/schema/history and
  integrity checks. Invalidate the old clean-world witness for this Minecraft session.
- JournalCompatibility validates up to 16 operation outcomes per startup step BEFORE any upgrade.
  Validate canonical bytes, IDs/actor/service/type, envelope version, ordered complete records,
  correction/economic linkage, known type/version payloads, and economic completion correspondence.
- Validate the existing complete-world pre-upgrade backup, then migrate the entire pending chain
  atomically. Publish world identity as before, verify immutable protection triggers and pragmas.
- Validate projection definitions/checkpoints/checksums, resume or rebuild required generations.
- Prepare persisted economic registrations/versions and process overdue UTC intervals.
- Revalidate projections after new economic records; check the wall clock again before publishing
  READY. If another interval became due during rebuild, process it and repeat final validation.

StorageLifecycle.Progress is immutable and exposed through ServerServices.progress(). Phase changes
and failures are logged. ServerServices starts at most one startup worker job at a time and never
waits on tick-thread storage; later steps are scheduled after completed futures are observed.
Requests remain gated until all required work completes. Ordinary Minecraft play can continue.
A waiting/backward wall clock retains debt/gating rather than declaring skipped work complete.

Standalone SqliteStorage.initialize drains the same bounded validation steps synchronously for
non-Minecraft fixtures/maintenance. Production uses beginInitialization/advanceInitialization.
Uncertain-commit reconnection retains A1-03's ownership/fail-closed rules and revalidates journal
compatibility in interruptible pages before original-ID lookup. No empty fallback is introduced.

RecordCatalog maps exact type/version keys to historical validators; core knows the version-1
foundation:economic-update record and the [FX journal facts](fx.md). Trusted integrations must explicitly register their historical
decoders. Unknown committed types/versions or invalid payloads fail closed, including before an
upgrade. JournalEngine also checks the catalog before committing newly constructed records.
Tests register fixture decoders only in test sources. No legacy implicit record acceptance.

## Derived generations and consistent snapshots

ProjectionManager definitions have a stable name, version, consumed record keys, pure reducer and
validator. Registry max 32. Generic cells are deliberately small: 1024 keys / 1MiB query values;
keys <=128 UTF-8 bytes, values <=16KiB. They are a foundation fixture primitive, not a market schema.

projection_heads points only at the published generation. projection_generations stores version,
build state, target sequence, last fully applied sequence/transaction and checksum. projection_cells
are scoped to name/generation. A batch reads at most 16 whole immutable journal envelopes, then
commits derived cell changes, checkpoint and checksum together. Replay advances over irrelevant
known records without rerunning commands. The checksum binds version, checkpoint transaction/
sequence and sorted cell contents. Missing/mismatched checkpoints or damaged derived data cause
rebuild; authoritative journal damage instead makes the economy unavailable.

Rebuilds use a separate generation; the old published generation is retained until completed state
passes validation and its head is atomically activated. Restart resumes only committed progress;
an interrupted batch rolls back its cell changes and checkpoint together. Unknown persisted
projection definitions fail closed. A version change rebuilds with the new reducer, preserving old
published state until activation. Retired/failed generations are retained; no cleanup automation.

Snapshot is an immutable, consistently read version/generation/checkpoint/value view from one DB
transaction. It is derived state within the same database, not a second authoritative snapshot
store. There is no financial/account projection or full snapshot framework in this package.

## UTC and reproducible update identity

EconomicClock accepts an explicit Clock (production UTC), definitions with explicit origin/cadence,
a dependency DAG and versioned pure simulations. The production configuration registers [FX](fx.md) with an origin supplied by
its committed opening publication. The fixture core has no active systems.
Max 64 systems, 16 retained effective-version boundaries per system and 16 updates per worker step.

Persisted seed is generated once in schema initialization. Each update gets a stable UUID from
world UUID + system + interval end + scheduled version; its operation/system-actor identities are
stable derivatives. SHA-256 of persisted seed + update UUID + version supplies deterministic random
inputs. Simulators must be pure over those inputs and preserve their versioned meaning. Stored
outputs are authoritative for replay, so committed updates never recompute random decisions.

Process the earliest due interval globally, with deterministic topological dependency order for
same-time intervals and stable lexical ties. Cadences may differ. Effects, journal record/result,
economic completion, system frontier and increasing global economic sequence commit in ONE A1-03
transaction. Stable original operation lookup prevents duplicate completion. No skipped intervals,
truncation or implicit aggregation. An unresolved update fails closed rather than advancing time.

System origins/cadences and historical simulation schedules persist. A stored implementation
fingerprint binds the declared algorithm identity, cadence, origin and sorted dependency meaning.
Changing these under the same version fails; a tag is a trusted version contract, not automatic
bytecode verification. Register historical implementations, then explicitly schedule a new version
at an aligned boundary strictly later than wall time, observed target and completed frontier.
Overdue intervals retain their OLD scheduled versions. There is no "use current code for backlog"
default. Missing historical implementations/signatures or system registrations are unavailable.

UTC wall time selects due intervals. System.nanoTime remains the runtime budget clock. Journal audit
instants, journal sequences and economic sequences stay distinct. Persisted observed_wall_ms is a
monotonic high-water target (SQL integer cast avoids scalar max's TEXT comparison pitfall). Forward
jumps create reported debt. Backward jumps do not reverse/repeat work; wait until wall time can reach
the retained target/frontier. Even an erroneous forward jump is not silently erased by rolling time
back. Sleep and /time never enter this API. An integrated-server pause stops new tick scheduling,
but elapsed UTC is caught up after resume/restart. A6 now also supplies an [arbitrary-deadline calendar dispatcher](financial-calendar.md); future products register adapters without changing existing fixed-system identities or FX cadence.

## Ongoing catch-up

With registered systems, post-tick advances one chronological interval through storage
preparation, pure detached calculation and storage commit. Each stage has a reserved queue
position and yields to already queued FIFO work. Storage revalidates the prepared update
before committing. A rewind between preparation and commit can defer the update. Idle
and wall-wait probes are spaced by 250 ms on the monotonic clock. The write path checks
current UTC again on the storage worker, records newly observed debt, and refuses new
effects while debt or required projection validation remains. Original-operation lookup
can continue on the storage worker. The server observes progress without joining unfinished
work. Startup and offline maintenance use the existing bounded synchronous clock batches
on their storage-owning threads.

## Clean-world witness and offline maintenance

Database close and Minecraft save are DIFFERENT boundaries. runtime_state is dirty during use and
eligible for clean DB close only after full foundation readiness. FoundationRuntime captures an
immutable UUID/schema/journal/economic close checkpoint, then closes the owned connection.

On orderly ServerStopping, a bounded boolean-only log observer captures ERROR-or-higher save/close
failures. The pinned NeoForge source calls ServerStoppedEvent after stopServer; stopServer clears
isCurrentlySaving after saving/closing levels and closes its storage access. Because the event also
runs from a finally block, the event alone is not proof: absent orderly-stop evidence, logged
errors, outstanding saving, failed drain or missing foundation close evidence prevent certification.
An abnormal stop also disposes orphaned services, without producing a clean witness.

After the JDBC worker terminates and Minecraft finishes saving, one bounded post-save I/O job
acquires BOTH session.lock and economy owner.lock, verifies the original session-file timestamp and
closed DB checkpoint, then writes world-clean.properties. The stopping thread may wait up to 30s
at this stopped boundary; no game tick waits on it. A changed/restarted/active session or failed save
cannot reuse an old witness. Startup invalidates it. This is a conservative orderly-shutdown
witness, not evidence that arbitrary storage hardware can never lose acknowledged writes.

backupEconomyWorld requires both stopped-world locks, clean DB status and this witness. It copies
the entire world, preserving DB + WAL together, omitting transient locks/SHM and old upgrade receipt.
Checksums cover the file inventory. foundation-manifest records world UUID, mod/schema/journal
versions, last journal sequence, economic checkpoint, UTC timestamp and clean-shutdown status;
receipt hashes seal both manifests. Files/directories are forced and the receipt publishes last.

Offline inspection must not mutate a sealed backup. For a DB image without nonempty WAL, use
immutable read-only access. The pinned driver ignores WAL with immutable=1 (verified by fixture),
so a nonempty DB+WAL pair is copied into a private temporary inspection image and recovered there.
No source WAL is discarded or checkpointed; scratch files alone are cleaned up. This distinction
is covered by OfflineWalTest. A live DB-only copy is NEVER a production backup procedure.

restoreEconomyWorld validates receipts, inventory/hashes, UUID/identity, exact supported mod/schema/
journal versions, committed record decoders, projection/checkpoint checksums and economic versions/
completions. Valid older projection checkpoints may rebuild after restore. Current general restore
supports clean schema-4 through schema-6 world-backup-v1 manifests; earlier legacy-stopped upgrade receipts are
preserved but are not asserted to carry this newer clean-world proof.

Restore stages in a separate sibling directory and publishes with a non-replacing move only after
validation, rebinding the clean witness to the new runtime lock. Existing destinations ALWAYS reject;
the current world and backup remain untouched. Incomplete/failed restores can leave private staging
for operator inspection; there is no automatic backup retention, deletion, replacement or online
manager. Operator approval is still required for any later manual replacement of a current world.
Normal UTC catch-up resumes from restored seed/version/checkpoint; post-backup player actions do not.

Commands: prepareEconomyUpgrade (existing supported-schema upgrade prerequisite), backupEconomyWorld,
restoreEconomyWorld. README documents arguments. The Java APIs accept explicit integration registries;
CLI defaults to the production registry, so unrecognized integration records/systems fail safely rather than being lost.
Online backup is an extension boundary only, not implemented.

## A5 cash/shop crash boundary (contracts only)

A5 must retain evidence for: authenticated original operation/canonical intent; item/stack serial or
custody identity; precommit reservation and the Minecraft save that contains it; financial commit
and durable result; durable intended delivery identity; actual inventory/world delivery; persisted
delivery acknowledgment; and Minecraft save/reconciliation witnesses. JournalService.lookup and
stable world/operation/transaction/update IDs are the A1 recovery hooks. No item handler/outbox is
implemented here.

Reconcile at least reservation without financial commit; commit without delivered item; delivery
before DB acknowledgment; acknowledgment before the corresponding Minecraft save; and disagreeing
restored Minecraft/database saves. Determine whether the item moved through players, containers,
drops or unloaded chunks before deciding on redelivery/compensation. An outbox alone cannot atomically
join inventories and SQLite. Exactly-once physical delivery is NOT claimed. A5 must choose durable
serial/reservation/save evidence, ambiguity quarantine/manual recovery policy, and achievable
redelivery/compensation guarantees before exposing cash/shops. Do not invent a second operation ID
to bypass an unknown financial outcome.

## A6 calendar dispatch

Production activates the version-one [financial calendar](financial-calendar.md) once after migration. FoundationRuntime merges arbitrary events with fixed intervals, preserves fixed publication priority at equal instants, and includes both debt sources in startup/write admission. Each worker turn commits at most one child or event completion. Startup final projection validation compares journal sequence so domain-only facts cannot be missed. Domain progress is separate from the fixed-only economic sequence.

## CHF historical first version

A6-03-01 adds an optional historical InitialVersion reader to DeferredSystem, retaining its prior two-argument constructor. FX reads the earliest immutable publication to retain v1 for legacy openings and start v2 for fresh CHF-capable openings. After legacy catch-up, production schedules the CHF implementation only at a strictly future UTC boundary. Existing origins/cadences/v1 fingerprints and past completions remain unchanged; see [CHF compatibility](chf.md).


## A8-06-01 prospective fixed retirement

[FixedRetirements](fx-effective-time.md) adds a distinct immutable V47 receipt, binding original fixed signatures/versions and an installed stage0 successor at F. Fixed scheduling/debt/commit/startup/offline use the same first-excluded aligned H, preserve last actual frontier and require the exact reserved successor event. No excluded completion is fabricated, and legacy FX completion↔publication validation remains unchanged. Production retirement is not scheduled by this package.


A8-06-02 now delivers the dormant [financial-day macro provider](macro-rates.md) under V48: atomic A/F/H handover, actual child-linked v2 window/publication facts, immutable selected baskets, chronological paused/manual/resume policies, and readiness through the separate dispatcher completion. Historical facts and fixed loan terms remain unchanged; A8-07 lifecycle activation is still unselected.
