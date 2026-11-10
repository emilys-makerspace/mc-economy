# Deposits

Schema V15 adds deposits, deposit_cash_reservations, deposit_returns and
custody_cash_reservations. LedgerService exposes trusted preview, reservation,
settlement and return APIs. Worker callbacks receive immutable inputs and
persisted state. Minecraft effects run through DepositInventory on the server
thread. Live menu/reach/dimension and actor admission remain required when
these APIs are connected to terminal menus.

A preview accepts up to 24 nonempty indexed stacks. Marked cash includes its
exact cash lot; unmarked Creative/command cash carries an explicit mint flag
and deterministic request identity. Every slot retains the full serialized
ItemStack components/count. Inputs are bounded to
2048 encoded bytes per stack, 32 KiB aggregate components and the journal's
16 KiB complete canonical request budget. Preview rejects an unreservable
request before any originals move. The physical adapter permits at most
16 pending saved reservation batches per player.

Preview freezes bank account revision/currency, terminal anchor/generation/
revision and the current UTC FX publication day/update/policy identity.
It verifies account ownership/open state, active issued quantities for marked cash,
terminal deposit mode, currencies and denominations. ATMs accept bills;
tellers accept bills and coins. Unmarked catalog cash can be reserved for the
mint path; marked cash from another world or an unknown/exhausted lot rejects.

Counts and face-value subtotals are grouped by source currency. A2 conversion
is applied to each source subtotal, floored to the existing whole-major-unit
bank ledger quantum. A group with zero bank credit is skipped in full.
All-skipped deposits remain valid return-only operations with zero financial
postings and no cash consumption.

The normal sequence is preview, journal RESERVE, exact physical reservation,
verified player save, journal SAVED and journal COMMIT. DepositInventory
revalidates every slot before moving originals into player persistent data.
Only an independently observed saved reservation may admit settlement.

COMMIT rechecks account, terminal and FX bindings. Marked-cash redemption,
unmarked-cash minting, balanced target-bank issuance/credit, deposit state and durable skipped-original
return intents share one transaction. Original source subtotals and terms
remain in the journal snapshot. Keep the COMMIT operation ID and request for
lookup/retry when its outcome is unknown. Retire consumed physical originals
only after resolving a successful commit.

Cancellation on close or page change requires proof that originals remain
in server custody. Disconnect cleanup can use the retained cleanup obligation
authority rather than new banking admission. If possession/save is unknown,
quarantine the reservation. Cancellation and skipped groups persist exact
original return rows with their quantity locks.

Direct return BEGIN requires a proven undelivered original and capacity
admission. DepositInventory.returnOnce adds what fits and retains the exact
remainder plus an attempt marker in the same player save. Repeated attempts
return their original observation. COMPLETE requires independently verified
saved delivered quantity and a known undelivered remainder. A zero-capacity
known return can become a claim directly.

Return completion atomically transfers the remainder into the existing
owner-bound Custody claim, retaining components and issuance identity.
Deposit quantity locks move to custody quantity locks. Generic redemption
cannot consume reserved quantities. Successful saved claim delivery releases
its lock. Confirmed-loss compensation retires the protected cash quantity
before releasing the lock, preventing both compensation and later redemption.

Before READY, bounded startup passes quarantine interrupted deposits and
returns, creating operator-review custody cases with original quantity
protection. No return or credit is inferred from missing evidence. Existing
custody operator resolution requires conclusive evidence. Ambiguous partial
deliveries remain open until their complete state can be proved.

Final UI and terminal geometry are separately invoked packages. Their event
handlers must call these APIs in order, preserve cleanup obligations after
menu closure, and apply save evidence before publishing completion.


Current player-facing integration is delivered in [A5-09 banking menus](banking.md); that contract supersedes earlier statements here that menus are pending. Existing subsystem safety and save-evidence requirements still apply.

## CHF minor settlement

CHF-aware previews bind settlement=CHF_MINOR_V2 in canonical envelope1 and the persisted preview, emitting deposit factv2. A source group settles at account minor precision iff source or account isCHF; other groups keep historical whole-major rounding. Marked/unmarked paths compose explicit minor-v2 postings with the existing quantity, saved-original and return transaction. Legacy preview bytes/pending operations/factv1 remain unchanged; group bound is11. Both fact decoders remain strict. See [the current CHF contract](chf.md) and [verification](../A6/verification/A6-03-01.md).
