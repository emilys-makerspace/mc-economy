# Posting facts

Trusted journal handlers resolve account UUIDs, roles and currencies and derive
authority from authenticated server context. The journal access policy controls
admission. Authority must never come from a client claim. Canonical requests
must bind the settlement inputs in stable order for journal retry matching.

Positive entries credit and negative entries debit. Transfers use ordinary
accounts. Issuance credits ordinary accounts and debits an issuance
counteraccount. Redemption reverses those signs. Each leg uses the account's
currency and a nonzero whole-coin amount represented in minor units. Checked
gross credits and debits must match independently in every currency.

Version 1 economy:posting records encode account UUID, role, currency, signed
minor units, and authority as pipe-separated fields. The journal envelope
supplies one transaction identity, actor and record order. Rejections use
stable codes and empty payloads.

## Durable settlement

Bind Settlement.registration to a trusted JournalService kind. The V1 canonical
request preserves ordered legs. The registration resolves live authorization
for all legs and checks ordinary accounts against the open bank catalog. The
issuance account is a deterministic UUID per currency. Worker-side settlement
checks each projected balance before adding the immutable facts, projections,
and sealed outcome to one SQLite transaction. Missing projection rows represent
zero; ordinary balances cannot go negative. Issuance has a signed balance.

Journal admission and lookup scope outcomes to the world, actor and operation
kind. Same-request replay preserves the original result, including terminal
rejection. A changed payload conflicts. Uncertain commits resolve against the
original operation ID. Disconnect can skip publication while admitted work
continues. Diagnostic events distinguish replay, conflict, business rejection,
lookup failure and unknown outcome without including posting data.

The V7 ledger_balances table is derived. LedgerProjection.rebuild is an explicit
worker-only maintenance operation. It pages immutable facts in sequence order
and replaces the projection atomically, with the same per-transaction account
deltas as live settlement.

## Holds

V8 stores hold identity, account, currency, amount, actor, and state. The
reservation operation ID is the hold ID. The journal records each transition
with the state update. Reservation reduces availability by the held amount.
Ordinary settlement protects all active reservations. Capture transfers the
full amount to a distinct open bank account in the same currency, with
posting and transition in the same commit. Release restores availability.
Both transitions require an active hold.

Trusted workflows bind the hold registrations to journal kinds and supply live
authority checks. Transition and recovery lookups scope by authenticated actor.
A caller with no access receives a generic rejection. Workflow recovery pages
active holds in hold ID order. Holds persist until explicit capture or release.
LedgerProjection.rebuild reconstructs posted balances and leaves the persisted
hold state in place.

## Same-currency operations

SameCurrency supplies V1 registrations for primary payment, owned transfer,
credit and debit. Requests bind account IDs and expected revisions to the
amount and owners. The trusted server registration checks live actor authority
before resolving current primaries or owned accounts. Missing primaries, stale
revisions, revoked authority and insufficient available funds have separate
rejection codes. Authorized credit and debit use the per-currency issuance
counteraccount. All successful operations share the settlement and hold checks.

## FX, closure and rebase

FX adds deterministic clearing and game fee accounts per currency. Each
currency balances independently. A fresh quote binds the authenticated actor,
world, publication and pricing version. Quote use, postings and balances
commit together. V9 stores consumed IDs for indexed lookup. Quoted fees are
additive in the source currency. The payout floors to whole target coins,
including zero. Exact-credit source debit rounds up to a whole coin.

Funded closure uses A3 eligibility with current balances and active claims.
An exact-debit quote must consume the entire source balance including fees.
The closure fact and catalog update follow settlement in the same transaction.

Rebase reruns the A3 roster and claim plan with live authority and a confirmed
A2 change. It binds the FX publication, floors converted balances to whole
coins, and folds target-currency collision funds into the surviving primary.
Collisions retire before catalog currency updates. Historical posting facts
keep their original currency. The indexed denomination fact controls future
account initialization and eligibility; the FX publication reference remains
part of FX pricing history. The atomic limit is 10 primaries. One denomination
fact plus up to five postings and one mapping fact per primary gives at most
61 records, each under 256 bytes.

## Service and history

Trusted mods obtain the current server session with Economy.serverServices and
use ServerServices.ledger on the server thread. Obtain fresh owner grants with
PartyAuthorization.player. Owner payments, quotes, exchanges, closure, funds
and history use those grants. Credit/debit registrations require separate
trusted admission and worker authority policies. Holds and rebase can bind
their typed registrations through trustedJournal. Keep the original operation
ID and request for retries; assign a fresh request ID to each attempt.

Bank funds read posted and held units from the same snapshot. Trading
identities await their own funding policy. History allows owners to read their
closed accounts. Operator inspection uses the audited A3 grant and rechecks
live permission before disclosure. A row reports signed original minor units,
currency, denomination revision, journal sequence, position and transaction ID.
The revision is zero before the first rebase; all postings in a rebase
transaction use its resulting revision.

History seeks on the V10 account index in ascending sequence and record
position order. An opaque cursor is world- and account-bound and resumes
exclusively after its last row. Subsequent pages can include appends. The
page limit is 63 plus one lookahead. Reconciliation is an audited, bounded
current-currency comparison. Histories exceeding the page limit yield an
incomplete result. Transaction IDs correlate related entries without exposing
the other account's history.

## Version-two minor postings

Legacy v1 posting amounts remain whole-major and rejectCHF. V2 has the same five payload fields with exact nonzero minor units. CHF legs or explicit trusted precision=minor-v2 composition select v2 without relaxing role, funds/hold, sign, overflow or per-currency balance checks. CHF holds/FX use strict v2 facts; legacy nonCHF transfer/hold/exchange rules remain. Newly fractional legacy-currency balances may fully drain at account closure or world rebase via v2; rebase target-payout rounding remains the existing whole-major policy. Projection rebuild/history fold both versions through the V19 history index. See [the current CHF contract](chf.md) and [verification](../A6/verification/A6-03-01.md).


## A6-03 current contract (2026-11-05)

[Institution/provider contracts](institutions.md) supersede earlier lifecycle/cap references: schema V20, four total open BANK accounts including primary, version3 institution postings, explicit legacy provider migration, and sealed branch-only lifecycle admission. Live opening/closing/provider changes are unavailable until A6-05-01. Existing account services and original outcome lookup remain.


## A6-04 current contract (2026-11-05)

[Wealth, pricing and funded yield](wealth-pricing.md) defines schema V21, persisted required-source contracts, fresh qualification/fee witnesses, assigned-bank FX pricing and time-weighted bank-funded deposit yield. Production installs bounded accrual/payout providers at stage 1 and daily Tier reevaluation at stage 7; empty domains remain dormant. Source snapshots and unpaid receivables remain distinct from spendable funds. Live branch adapters, issued cards, lending and trading are still separate packages. Earlier no-product/default-Tier2-denial descriptions apply to their historical delivery checkpoints.
