# Persistent village branches

Branch persistence was introduced in V36; current world schema is V39 after [A7-05 staffing](banker-staffing.md). Production source reconciliation, physical placement, explicit terrain retry and captured-desk bankers are installed. [A7 revision-1 contracts](../A7/architecture/a7-shared-contracts.md) retain policy/geometry; this document records implemented interfaces and conservative limits. [Verification](../A7/verification/A7-02.md) records the selected real SQLite checks.

## Captured plans and identities

`village.BranchPlans` provides immutable bounded version-one records: source/start tuple, original boxes/junctions/count/style/abandoned status, planning snapshot, stable required/optional slots, institution/native currency, template/transform/envelopes, service/desk anchors and per-chunk manifests. Its canonical sorted JSON rejects unknown fields, noncanonical encodings, invalid sizing/eligibility/foreign preference/omission combinations and oversized/deep input. IDs use SHA-256-derived v8 UUIDs with world identity/source/slot separation. Service generations use physical indexes0/1, desk generations2/3. Branch system actors remain typed `SystemActorId`; player UUID ownership encodings are unchanged.

`BranchSelection.capture` uses world seed and original source to select purpose/slot-separated deterministic streams with unsigned rejection sampling, sorted eligible providers and captured optional rolls. It excludes world UUID from selection. Primary requires an eligible native-denomination Tier1/hybrid; extra slots prefer unused foreign providers then unused local providers. Required exhaustion persists PROVIDER_PENDING; optional exhaustion persists OMITTED/NO_DISTINCT_PROVIDER; unselected optional rolls persist NOT_SELECTED. Detached selection initially reports SITE_PENDING. The installed generation adapter searches safe sites and captures physical RESERVED choices or a concrete pending-terrain reason. No later read/restart rerolls choices.

`BranchService` is constructed only by `ServerServices.branches()` on the server thread. `planningSnapshot` asynchronously reads the authoritative denomination and current institution capabilities on the existing worker, returning detached values for future server publication. No generation thread may wait on it. `observePlan` returns a saved canonical `Pending` intent; `reconcilePlan` performs first-ready capture for an uncaptured source. The worker accepts CAPTURE_PENDING→CAPTURED exactly once by compare-and-set of original source/layout/digest/revision; a different subsequent captured plan is rejected. The A7-04 adapter retains raw generation resource revision and reobserves loaded sources; see [generation publication and receipt boundaries](village-generation.md).

The generation-local codec cap remains256KiB. Durable journal admission has an explicit64KiB UTF-8 cap, because journal requests/records/envelopes have independent limits. Larger source metadata returns BRANCH_PLAN_JOURNAL_LIMIT and must remain in its generation outbox for reported operator/developer resolution; it is never truncated or claimed captured. Observe requests carry the source+SHA-256 digest, binding the immutable server-held plan; full plan facts are split into at most8 chunks of8192 bytes, base64url encoded within the existing per-record/envelope bounds. Original canonical intent/operation identity must be retained on retry.

## V36 persistence and compatibility

Five appended tables, four indexes; V1–V35 remain unchanged:

- `village_plans`: world/source/village identity, canonical captured plan+digest and capture revision.
- `village_branches`: stable village/slot identity, independent lifecycle/reason/revision and saved staffing pause flag.
- `village_branch_anchors`: exact captured service coordinates/generation, persisted terminal revision and PENDING/ACTIVE/REMOVED/CONFLICT state.
- `village_branch_events`: contiguous per-branch revision history, reason and original operation ID.
- `village_provider_fills`: one explicit later required-provider capture, including institution/native currency, new eligibility snapshot and original operation ID. The original captured snapshot, optional omissions and other branch choices are retained.

Exact DDL, integer/state/bounds checks, canonical world/source/digest/ID checks, complete branch/anchor projections, provider-fill eligibility/distinctness and latest/contiguous event checks run on storage open/recovery and offline inspection. New fact decoders `economy:village_branch` v1 and `economy:village_plan_part` v1 are installed in `RecordCatalog.core` before journal compatibility/replay. Clean backup/restore recognizes V36; supported older worlds require the existing stopped-world complete backup gate. No parallel financial store, financial entities or customer/account migration is introduced.

## Progress and atomic activation

`journal.Branches` registers scoped OBSERVE, PROGRESS, ACTIVATE, REMOVE, PAUSE, RETRY and FILL operations through the ordinary journal. It captures source plans, CAS transitions and history atomically. Initial ACTIVE/REMOVED plans are rejected. RESERVED→PLACING→PHYSICAL_COMPLETE requires exact manifest-bound A7-04 chunk receipts; plans alone never establish physical success. Terminal/removed/conflicted states cannot be silently promoted. Per-branch state is independent of sibling branches; immutable chunk manifests retain original operation/before/after identities. A7-04 implements actual serialized chunk receipts and fresh loaded observations.

Activation requires exact branch/revision/slot/generation/expected terminal revision, typed branch owner and revocable detached live authority. The worker resolves the captured choice/anchor from its own records. A new terminal composes the real `TerminalPolicy.PLACE` commit and factored `TerminalBanking.prepareBinding` commit, then updates anchor/branch history in the **same SQLite transaction**. The shared binding helper preserves native currency, kind/revision, confirmation and unresolved-custody policy. No branch activation grants general operator authority, opens accounts, reseeds capital or enables cash modes. Generated kiosks cannot be rebranded to a different captured provider through ordinary operator branding.

The existing journal handles original-ID lookup, canonical conflict and uncertain-commit recovery. Bootstrap hardware operation IDs include branch revision, slot, generation and expected terminal revision, so a known durable stale rejection can be followed by a distinct corrected attempt without reusing different canonical bytes. UNKNOWN retains the original pending intent and first performs original lookup; it never invents a retry ID. Historical COMMITTED replay is not an enabling instruction.

`TerminalPolicy.matches` and `TerminalBanking.matches` additionally check the generated branch/anchor servicing state on the worker. CONFLICT/REMOVED branches cannot admit fresh finance even if a stale block cache is still ready. The existing actor/session/reach/revision/custody/customer eligibility checks remain authoritative. Ordinary terminals without a generated anchor record retain prior behavior.

## Loaded hardware and removals

`TerminalBlockEntity` saves an explicit `village_branch` identity separately from player owner UUIDs. `generatedPlacement` installs deterministic presentation identity but leaves policy unready; it creates no financial authority. Player placement clears branch identity. Branch-tagged reload/removal routes to `BranchBootstrap`, never through a synthetic `PlayerId`.

`BranchBootstrap` maintains a server-owned256-entry queue, at most4 admitted hardware reconciliations per tick, bounded branch-page scanning, revocable detached flags and original prepared intents. It observes only loaded cells and uses existing `TerminalGeometry`; it never loads chunks, places blocks, rebuilds geometry or replaces hardware. A7-03 physical admission accepts captured `narrow` or `wide` KIOSK service anchors with exact matching state-aware geometry. Explicitly upgraded ACTIVE generations retain their original anchor/binding. Registered plans alone cannot cause physical effects.

After activation outcome/replay, a separate fresh persisted terminal/binding/branch read and exact loaded identity/geometry match are required before policyReady is set. Pre-callback validation revokes missing/replaced hardware. Absence while unloaded is unknown; it never establishes removal. A bounded periodic loaded-anchor observation catches missing ACTIVE hardware even if a prior removal outbox write was lost in a crash.

Generated removals have a separate branch/generation outbox in the existing Minecraft-owned `TerminalRemovals` SavedData. It survives ordinary save/restart independently of a destroyed block. Journal removal deactivates only the matching generated terminal and retains branch/anchor tombstones; a later player replacement at the coordinates is untouched. Removing one kiosk retains another ACTIVE kiosk. Removing a CONFLICT/PLACING anchor never promotes incomplete placement to activation-ready. No removal or restart rebuilds presentation blocks or changes accounts/balances/debts. Cross-store atomicity is not claimed; physical correctness always fails closed and banking requires loaded geometry.

## Commands and downstream interfaces

Authenticated permission-level-2 operators, server console and the existing trusted RCON console path may use:

- `/economy branch plans [after <village-id>]`, `/economy branch list [after <branch-id>]`: indexed pages≤16.
- `/economy branch status <id>`: captured count/style/slot/provider/currency/revisions, loaded/unknown service and desk generations, staffing assignment/phase/elapsed state, pause state and recent16 event/operation IDs.
- `/economy branch staffing <id> pause|resume`: atomically flush earned replacement progress and persist revision-checked pause state; resume preserves elapsed time.
- `/economy branch retry <id> <expectedRevision>`: explicit operator only. SITE_PENDING invokes the installed loaded-only terrain adapter with fresh supported empty-space and preservation checks. PROVIDER_PENDING selects an unused eligible institution from a newly recorded snapshot using the original source/slot stream, keeping the original denomination and prior choices; if none exists, remains pending. REMOVED/CONFLICT/OMITTED cannot retry into construction.

Operator work uses detached atomic permission leases refreshed before storage callback publication. Commands acknowledge durable outcomes and show original operation IDs; unknown results must be inspected, not assumed failed. They expose no customer balances/private financial history. Direct trusted server callers can retain `BranchService.Pending` and call lookup before resubmission. These interfaces are not network DTOs or client/NPC-minted proofs.

A7-03 now consumes typed physical branch identity, legacy/wide service anchors and stable terminal generations/bindings; its [upgrade contract](kiosk-upgrades.md) is current. A7-04 supplies [metadata reconciliation, receipts and safe explicit terrain retries](village-generation.md). Pending plans have no invented anchors; V38 accepted later-site choices install anchors separately from immutable capture, preserving all removal tombstones. A7-05 adds desk admission, assignment/vacancy/timer/spawn records while consuming the durable pause flag and reserved desk geometry. [A7-05 staffing](banker-staffing.md) is installed with conservative UUID reconciliation and explicit-only dialogue.

[A7-06](../A7/A7-06.md) records integrated account access and recovery acceptance. [Downstream contracts](../A7/architecture/a7-downstream.md) identify stable financial API boundaries and internal presentation formats.
