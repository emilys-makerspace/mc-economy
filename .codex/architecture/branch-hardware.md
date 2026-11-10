# Branded hardware and branch sessions — A6-05-01

## Persistence and journal

Schema V22 adds `terminal_bank_bindings`, keyed by world/dimension/anchor/generation. It stores institution, catalog revision 1, native currency, migration state and frozen legacy mode/currency/denomination settings. Migration copies existing ATM/teller bindings as UNBOUND_BANK without changing `terminal_settings`, inventories, identities, journals or custody. Schema validation runs at open/recovery/offline inspection; V22 complete-world manifests can restore through the existing backup gate.

`TerminalBanking.BIND` is `economy:terminal_policy/economy:bind_bank`, canonical v1 fields dimension/x/y/z/generation/revision/bank/confirm_native_change. Worker admission requires live owner/operator authority, exact active placement/revision, catalog-native currency and explicit confirmation. Binding is denied while up to 256 unresolved deposit/withdrawal/return/custody records may reference the generation; overflow fails closed globally. This bound is conservative and diagnostics expose TERMINAL_BANK_PENDING rather than mutating anything.

Binding atomically writes old-format `economy:terminal_policy` v1 configuration plus `economy:terminal_bank_binding` v1 (13 base64url fields matching the binding row, then terminal revision), projection and original outcome. New fact decoder is registered before use; old fact/monetary decoders remain intact. Configuration records the binding fact too. State transitions are UNBOUND_BANK → BOUND (cash disabled) → ENABLED latch; once cash was enabled, another issuer requires a fresh physical placement. Before enable, confirmed rebranding increments revision and invalidates old previews. Removal retains tombstones/history.

New placements are disabled/unbound. CONFIGURE permits only bound native-currency cash denominations, ATM notes or teller notes/coins; kiosk/register cash stays disabled. Fresh deposit/withdrawal preview and RESERVE reject unbound machines. Historical SAVED/COMMIT/replay paths retain their pre-binding calculations and captured revisions; binding cannot overtake unresolved custody. Teller-wide historical defaults are used only to reconcile old operations, not to enable fresh unbound service.

## Geometry, visuals and acquisition

Stable legacy terminal IDs remain registered. ATM/teller stay two-wide/two-high and retain exact geometry/rotation/support/single-drop rules. BANK=0 is neutral; BANK=1..23 is a visual cache in authoritative catalog order. `TerminalBlockEntity.applyPolicy` refreshes loaded cells from worker snapshots; texture/block state/item custom-model data never establishes a financial binding. SQL persists bank/owner/settings and anchor NBT preserves generation/placement identity. Reload begins policy-unready and reconciles before use.

`branch_kiosk` is one-wide/two-high, lower anchor/entity and upper satellite, with horizontal facing, sturdy lower support, loaded-only integrity and guarded placement rollback. Any cell/support loss invalidates the generation and tears down matching cells with one survival drop, none in creative. Dropped kiosk custom-model data preserves its bank appearance only; placement ignores all item state/entity components and creates a fresh unbound identity. Unloaded neighbors defer validation; production never forces chunks.

Neutral ATM/teller recipes remain; 16 ATM dye recipes are removed while all old IDs/items remain. Kiosk recipe: iron/glass/iron; iron/redstone/iron; iron/chest/iron. No acquisition grants capital, accounts, tokens or authority.

## Server admission and menus

Owner/operator sneak-empty-hand interaction or `/economy admin config` opens settings. Bank selection offers exactly 23 server catalog entries, followed by bank/native-currency review and explicit confirmation. Cash enable is separate. `/economy admin binding` is a bounded read-only operator view of bank/currency/catalog/migration/kind/generation/revision/geometry/pending denial.

KIOSK access alone permits branch opening, assignment, source authorization, target confirmation and protected closure. Bank ownership is independent of customer eligibility. Primary requires Tier1/hybrid native world currency; secondary uses native bank currency. Same-currency wrong-bank closure/switch still rejects; specialists require fresh A6-04 eligibility for new admission. Closure composes existing holds/claims/world-primary/funded protections through `BankProviders.CLOSE` and `FundedClosure.registrationAtBranch`. No raw player lifecycle API was reopened.

`KioskAdmission` issues ≤30-second proofs bound to actor/world/menu session/action/bank and account/provider/denomination revisions; its adapter retains anchor/dimension/generation/terminal revision. Server ticks and close/removal callbacks revoke detached `BranchLeaseState` flags for disconnect/death/replaced menu/dimension/reach>6/geometry/generation/revision/permission changes. Revocation is permanent; worker callbacks read flags and SQL only. Final preparation checks exact terminal revision and bound bank alongside existing financial revisions. Physical observations are tick-granular, not worker access to Minecraft objects. Server refreshes leases before publishing worker completions.

Banking protocol is version 3 (new access enum/actions). Packets contain bounded menu/session/revision/action/value only; offered choices and server state supply bank/actor/currency. Pending provider operations use original-ID ordered lookup and never silently resubmit after lease expiry. Busy/replaced menus cannot publish stale callbacks. Unknown results retain saved canonical intent; no physical financial effect is replayed.

ATM/teller may service any owned bank account. Cash always uses the hardware bank's native currency, including withdrawal input parsing; differing account currency requires explicit captured FX/ordinary tier pricing, same currency has no extra fee. Unsupported replacement, credit-card and loan application entries report unavailable without issuing facilities, fees or readiness claims. No new network financial authority, debt entity or financial database exists.

World/block appearance updates and dropped items are non-atomic Minecraft effects; they never create account/issuance evidence. Existing A5 custody controls all money/items and uncertain delivery. Schema DDL remains the stopped-world atomic migration/backup protocol; runtime work and admission queues retain their existing bounds.


## A7-02 generated branch identity

Schema V36 adds durable presentation plans/service anchors and a typed SystemActorId bootstrap path, distinct from player owner UUIDs. New generated kiosk activation composes existing PLACE/native BIND and branch history atomically, preserves custody/revision gates and never creates financial entities. The physical anchor saves village_branch separately; reload/removal uses a branch outbox and loaded-only reconciler. Worker admission checks branch/anchor servicing state, and generated bindings cannot be rebranded away from the captured institution. Legacy terminals retain their existing behavior. [Exact current interfaces/recovery/commands](village-branches.md). No production generator or wider kiosk is installed; the physical adapter currently accepts only captured narrow kiosks.


## A7-03 current kiosk geometry

The stable branch_kiosk ID now explicitly stores wide=false for legacy two-cell forms and wide=true for four-cell consoles. New items place the wide form; old palette loading never occupies an extra block. Owner/operator settings offer a free explicit upgrade with loaded air/support/permission/collision checks, unchanged generation/bank/native currency and staged revision invalidation. Schema V37 persists upgrade preparation/outcomes; fresh banking/config/rebranding is blocked while pending, complete forms reconcile, partial forms never rebuild automatically. [Full current contract and original asset sources](kiosk-upgrades.md), [observed four-facing client evidence](../A7/verification/A7-03.md). [A7-04 generation](village-generation.md) now creates real wide kiosks in new eligible villages; [A7-05 bankers](banker-staffing.md) route through fresh current physical kiosk admission and never gate direct kiosk use.
