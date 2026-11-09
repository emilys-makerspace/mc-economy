# Withdrawals

Schema V16 stores frozen withdrawal reservations before any account debit.
LedgerService exposes previewWithdrawal, withdrawals and owner-scoped
withdrawal inspection. Admission verifies the authenticated actor and live
terminal session. Account owner, open state, currency/revision, active terminal
generation/revision, withdrawal mode and denomination allowlists are checked
again at financial commit.

WithdrawalPlanner computes exact plans from the current server catalog.
ATM plans use bills. Teller defaults maximize feasible bill value, minimize
bill count for that value and automatically fill the remainder with coins.
Bill edits can change only enabled bill quantities. Coin edits, foreign or
unknown identities, negative counts and inexact plans reject.
A null bill-edit map selects automatic planning. An explicit empty map asks
the teller to use coins for the whole amount.

The enabled denominations' GCD normalizes the amount. Limits are 100,000
normalized units, 2,000,000 DP work cells, 118 candidate denominations and
2304 individual items. Committed outputs additionally permit at most
24 denomination lines and 36 stacks. Results are deterministic by value and
item ID, including equal-value alternate artwork. Limit failures reject
before mutation.

Preview freezes cash amount/currency, bill edits, server-derived coins,
account/terminal bindings and UTC FX publication identity. A2 ceiling
conversion determines the account debit, rounded upward to the existing
whole-major-unit bank quantum. The full canonical preview must fit the
journal request bound.

Capacity is mandatory at confirmation, RESERVE and final COMMIT admission.
Capture capacity and session observations on the server thread, and pass
detached admission evidence to worker callbacks. Never inspect Minecraft
objects on the worker. If the complete batch cannot fit, no debit or issuance
occurs. Capacity can change after admission, which uses the custody fallback.

COMMIT revalidates the frozen plan and atomically writes balanced bank
debit/issuance postings, deterministic cash lots, committed custody delivery
intent, quantity protection and withdrawal state. Lot identities derive from
reservation plus item ID. The original COMMIT operation ID and canonical
request remain the lookup/retry boundary. Underfunded commits retain intent
for retry. Cancellation before commit creates no cash and debits nothing.

WithdrawalInventory materializes only a current admitted DELIVERING custody
case for its authenticated owner. It checks the entire fresh-lot batch against
main-inventory stack space before insertion, then stores a delivery-attempt
marker with the inventory. A repeated saved attempt returns its prior result.
At most 16 uncollected delivery markers are allowed per player. Clear a
completed marker only after an independently verified player save and a fresh
DELIVERED custody snapshot.

If capacity disappears after settlement, the adapter inserts nothing.
Confirmed non-delivery may become an owner-bound custody claim without
additional debit or issuance. Disconnect or unknown partial effects require
the existing evidence/quarantine protocol. Saved delivery releases quantity
locks; confirmed-loss compensation retires the original quantities.
Acknowledgment alone is insufficient save evidence.

Startup quarantines interrupted pre-debit reservations in bounded passes.
Existing custody and cash startup gates quarantine unresolved committed
obligations and outstanding quantities. An old committed outcome can be
replayed while the current delivery remains quarantined. Such replay cannot
authorize fresh physical effects.

Physical terminal geometry, final menus and register sales are separately
invoked packages. Their event controllers must recheck current custody state,
preserve cleanup obligations after disconnect and resolve uncertain financial
outcomes before attempting delivery.


Current player-facing integration is delivered in [A5-09 banking menus](banking.md); that contract supersedes earlier statements here that menus are pending. Existing subsystem safety and save-evidence requirements still apply.

## CHF exact cash settlement

CHF cash/account previews bind CHF_MINOR_V2 and emit withdrawal factv2, preserving account cents while requiring exact physical representability. Teller uses notes/coins; ATM notes only;1.02CHF rejects. Old nonCHF quantum/serialization remain. Deterministic lots, full-batch capacity, saved delivery, custody and original-ID recovery are unchanged. Catalog bound becomes131; all solver/item/work budgets remain. See [the current CHF contract](chf.md) and [verification](../verification/A6-03-01.md).

## Cash selection and artwork

The withdrawal review offers Cash select with bounded +/- bill counts per enabled denomination, a selected/remaining total, Review mix and Default mix. ATM edits must equal the requested amount; tellers derive the remainder in coins. Each successful review refreshes the frozen preview before confirmation. No quantity change debits an account.

When both vertical and horizontal note appearances are terminal-enabled, preview draws one ticket per bill: ticket zero of twenty selects vertical; the other nineteen select horizontal. Sampled changes are frozen as exact bill edits in the existing preview format. Reserve, commit and recovery revalidate those counts without drawing again. Artwork selection preserves amount, denomination counts, coins and item count; final capacity includes any extra stacks from distinct artwork.

## ATM surcharge (2026-11-09)

Bank-bound ATMs charge a CAD2.50 base fee converted to funding account currency when its provider differs from the ATM bank or is unassigned/missing. Same-bank accounts and tellers are exempt; payment-network compatibility does not decide this fee. The converted fee rounds once to the nearest 0.25 target major unit, or 25 JPY; HALF_UP resolves ties. Review includes the surcharge and total debit. Withdrawal fact V5 freezes the ATM bank, provider identity/revision, exact converted fee and original CAD/target rate values; reserve and commit recalculate and reject stale changes. Fees transfer from the funding account to the ATM bank's cash/income atomically with cash issuance and custody intent, with insufficient-funds retry and original-ID replay. V1–V4 retained facts remain readable, including original fixed local-currency V4 fees; old uncommitted reviews require a fresh CAD-based review. Owner history reports the surcharge together with any existing FX fee; FX activity excludes it from exchanged principal.
