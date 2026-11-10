# Terminal policy

Schema V14 stores terminal_settings by world, dimension and integer anchor.
Snapshots include owner, kind, active flag, placement generation, revision,
cash mode, currency allowlist and exact denomination allowlist.
terminal_generations permanently records used generation IDs within the
world. Tombstones and history survive reload and replacement.

TerminalPolicy supports PLACE, CONFIGURE and REMOVE journal registrations.
LedgerService.terminalPolicy binds authenticated server admission, an immutable
copy of the cash catalog, trusted live placement/session authority and current
operator authority. Placement adapters must verify the actual anchor event.
Menu adapters must validate session, reach, dimension and anchor before
submission. Worker callbacks consume detached data and persisted state.

Configuration and removal require owner or operator authority as well as the
live authority check. A placement must name its owner and, when replacing an
existing anchor, its exact prior generation and revision. Replacing an active
terminal requires its owner or operator. A new placer can replace a tombstone
with the exact prior token and verified placement authority.

New placements and replacements start disabled with empty allowlists.
Settings permit BOTH, DEPOSIT, WITHDRAW and DISABLED. ATM whitelists permit
bills. Teller whitelists permit bills and coins. Register cash modes remain
disabled. Every denomination must exist in the supplied catalog and belong to
an allowed currency. Enabled settings require both allowlists to be nonempty.

Payloads permit at most ten currencies and 118 denominations. Namespaced keys
are bounded to 96 ASCII characters. Anchors permit x/z within ±30,000,000 and
y from -2048 through 2047. These are protocol bounds, with physical dimension
bounds additionally enforced by placement/session adapters.

Revisions increase monotonically per anchor, including removal and
replacement. Updates must match both generation and revision. An old
generation can never be assigned again, including at another anchor.
Journal replay returns the original outcome while preserving the current
projection. Callers must recheck the current generation/revision before
acting on an old operation result.

LedgerService.terminalSettings provides authorized owner/operator inspection.
TerminalPolicy.matches is the trusted worker guard for exact active
generation/revision. Physical terminal geometry and block events are implemented in A5-08 below. Cash settlement contracts are in deposits/withdrawals/registers; player-facing settings and banking/purchase menus remain A5-09.


## Physical terminals — A5-08

TerminalCatalog registers `atm` and `register`, each with 16 `<dye>_atm` / `<dye>_register` counterparts, plus `teller` and the Economy Terminals creative tab. `stock_trading_computer` is a decorative facing block with no menu, block entity, or economic behavior. Data includes three base terminal recipes, 32 dye recolors, tags, empty wide-machine loot and ordinary single-block loot.

WideTerminalBlock occupies 2×2 cells. CELL 0 is the lower anchor, 1 its right neighbor from the front, 2 above anchor, 3 above neighbor. Local right is FACING.getCounterClockWise(). Placement faces the player. Only the anchor has a TerminalBlockEntity. TerminalGeometry resolves every cell and verifies full loaded structure plus generation. BlockState rotation/mirror supports structure rotation; no wrench UI is introduced.

TerminalBlockItem preflights four replaceable, in-bounds, border-valid, loaded and collision-free cells, permission at every cell and sturdy upward support below both lower cells. It installs with a mutation guard and rollback. Block-state and block-entity item components cannot inject a satellite state, owner or generation. Server placement requires a real placer and READY storage admission. RegisterBlock is one cell with sturdy support or a Container directly below, allowing a chest stock source.

Any wide cell break, replacement or support loss removes matching loaded peers, invalidates the unique anchor and produces one same-variant survival item. Creative player teardown produces none. Empty wide loot tables prevent a second vanilla drop. Replacement blocks and unrelated structures are preserved. Unloaded cells are never forced in; integrity checks defer until all relevant chunks are loaded. Each cell has an initial scheduled check then checks every 200 ticks, so loaded orphans eventually clean up without creating an extra item. The guarded teardown anchor lookup also avoids forced loading. Registers use normal self loot and preserve the container below.

The anchor persists fresh placement generation, owner and placement-operation identity in Minecraft NBT. Re-placing/recoloring items creates fresh placement settings; configured ownership does not travel with drops. policyReady is transient and defaults false. policyRevision in NBT is an informational cache; always read authoritative settings for financial actions.

TerminalLifecycle copies physical observations on the server thread before journal jobs. It admits at most four jobs per tick to a 256-entry live queue, retrying the same canonical intent/operation ID after unknown or rejected admission. New/replacement policy starts disabled. Callbacks may mark readiness only for the same complete current physical generation. A stale revision re-reads before a new canonical attempt. Loaded placements that exceed admission capacity remain closed and can retry by interaction.

TerminalRemovals is an overworld SavedData removal outbox covering all dimensions. Removal is recorded before anchor invalidation. Sixteen-entry pages refill the bounded live queue, including after reload; a full live queue cannot discard removals. Completion clears an entry only after successful REMOVE or confirmation that its matching active generation is absent. Minecraft world saves persist the outbox; it is not a synchronous disk witness or an atomic transaction with SQLite. Startup/unknown-save reconciliation and integrated crash tests remain A5-09. No pending removal assumes that an absent physical machine is usable.

The shop price command now requires a ready physical register and rechecks its current generation against the journal snapshot. Every final menu/controller must also require current actor/dimension/reach/session, complete geometry, physical generation and authoritative policy revision. TerminalLifecycle.matches is the physical guard; TerminalPolicy.matches remains the storage guard. No external protection mod is included.

[Acceptance evidence](../A5/verification/A5-08.md).


Current player-facing integration is delivered in [A5-09 banking menus](banking.md); that contract supersedes earlier statements here that menus are pending. Existing subsystem safety and save-evidence requirements still apply.

## A6-05-01 current hardware

[Branded hardware and branch sessions](branch-hardware.md) supersedes generic defaults above: V22 explicit bank bindings, native-only cash, disabled/unbound placement and legacy migration, two-cell kiosk, owner branding, immutable-after-enable binding, protocol3 and read-only inspection. Legacy IDs and existing A5 custody remain intact.
