# Account identity and catalog

The world database scopes the catalog. `AccountIdentity` carries the stable
account UUID, typed owner, purpose, status, revision and optional currency.
BANK is single-currency. The permanent primary is OPEN and counts toward the
three-open-BANK limit. OPEN BANK currencies are unique per owner. Closed BANK
rows retain their IDs and metadata and cannot reopen. A later opening uses a
new ID. TRADING is a separate permanent identity with no eligible currency
until B2 supplies its policy.

The account table, initialization journal fact and terminal outcome are
written together. An authorized equivalent retry returns the durable outcome.
A different logical initialization after creation commits a resolution fact
without creating another account. A1 handles uncertain commits and actor-bound
lookup. The opening FX reference also denotes the current denomination until
A4 implements durable rebase. BANK initialization reads it in the same
transaction that decides account creation.

`ServerServices.findOwnedAccount` admits a fresh owner grant and makes missing
and cross-owner IDs indistinguishable. Trusted worker calls to `AccountCatalog`
must use an owner in the transaction's actual world. History reads use an
owner/ID keyset index with a maximum of 64 rows. A3-04 owns audience and cursor
policy for externally exposed queries.

## Lifecycle

`AccountLifecycle.Open` carries a currency and expected primary revision.
Opening requires initialized banking. It checks the denomination and reads at
most three indexed open BANK rows to decide uniqueness and cap under the
journal transaction. The primary revision anchors the opening request across
denomination changes.

`AccountLifecycle.Close` carries the source revision and optional destination
and revision. Eligibility protects the primary and every BANK account in the
current denomination. It reads source, claims, and any destination in one
snapshot. A missing authoritative closure state blocks closure. A funded
source requires a distinct, open, same-owner BANK destination at the specified
revision. Any supplied destination is validated even for an empty source.

The close registration commits only for an eligible empty source. A4 must
rerun eligibility with authoritative balances and claims inside the funded
settlement transaction. A5 owns player-facing teller admission. The account
transition, versioned journal record, and original outcome share one commit.

## Queries and funds

Owner lookups and pages use a fresh detached grant and check owner and world
scope in the worker snapshot. An absent and foreign account ID produce the
same empty result. History pages include closed identities and use 1–63
results plus a single lookahead. A versioned cursor binds world, owner, and
the last returned account ID. Each page sees its own committed snapshot.
Closing an account preserves its position. New IDs that sort before the
cursor appear when paging starts again.

Operator lookup and paging use the live player-backed command source. Each
query allocates a new audit operation ID and commits an inspection receipt
with the exact query scope in canonical fields. Read and publication follow
only after current audience checks. Ordinary owner requests require no
operator receipt. Trusted owner callers must recheck the live audience when
publishing to a player.

`AccountFunds` v1 represents the three disjoint, nonnegative categories in
one currency. The posted sum uses checked minor-unit arithmetic.
`FundingEligibility` is advisory and leaves reservation and settlement to
A4. Account funds remain explicitly unavailable until the ledger exists.

## Rebase planning

A2 issues a detached `DenominationChange.Confirmed` proof only after the
second confirmation. The internal worker planner checks world, session,
actor, and current denomination revision. Each result carries the original
intent and the expected versions of the primary and optional collision.
The target currency is the confirmed target. When present, the collision
retires and its successor is the surviving primary ID.

The planner performs one indexed roster and collision query with a 257-row
lookahead and checks claims on at most 512 affected accounts. More than 256
primaries blocks planning. The limit bounds internal reads and results. A4
must separately size its atomic write against A1's 64-record and 128 KiB
transaction bounds, 16 KiB per record, and measured ledger costs.

A4 must recheck live authority and rerun the planner in the settlement
transaction, comparing the full result with the expected plan. Include the
entire roster so that new primaries, new collisions and changed revisions
invalidate stale plans. Retire a collision before changing its primary's
currency within the same transaction so uniqueness remains enforced.
Authorize and validate postings and denomination effects in that same
transaction. This planner has no apply operation.

## CHF availability

The registry now declares eleven currencies. Published-rate membership filters opening choices, and authoritative AccountLifecycle opening rejects CHF before its effective publication. A fresh CHF world initializes a protected CHF primary; legacy activation never changes world denomination/primary. Explicit rebase/secondary collision logic remains, including controlled missing-rate rejection. The account cap remains3 here; A6-03 owns the later four-account/provider lifecycle. See [the current CHF contract](chf.md) and [verification](../A6/verification/A6-03-01.md).


## A6-03 current contract (2026-11-05)

[Institution/provider contracts](institutions.md) supersede earlier lifecycle/cap references: schema V20, four total open BANK accounts including primary, version3 institution postings, explicit legacy provider migration, and sealed branch-only lifecycle admission. Live opening/closing/provider changes are unavailable until A6-05-01. Existing account services and original outcome lookup remain.


## A6-04 current contract (2026-11-05)

[Wealth, pricing and funded yield](wealth-pricing.md) defines schema V21, persisted required-source contracts, fresh qualification/fee witnesses, assigned-bank FX pricing and time-weighted bank-funded deposit yield. Production installs bounded accrual/payout providers at stage 1 and daily Tier reevaluation at stage 7; empty domains remain dormant. Source snapshots and unpaid receivables remain distinct from spendable funds. Live branch adapters, issued cards, lending and trading are still separate packages. Earlier no-product/default-Tier2-denial descriptions apply to their historical delivery checkpoints.

## A6-05-01 live kiosks

[Live branch sessions](branch-hardware.md) now admit the existing four-account/native-currency provider lifecycle, including permanent primary onboarding, legacy assignment, two-branch switching and protected closure. Owner/operator machine rights never substitute for customer/Tier authority. Old generic lifecycle handlers stay fail-closed.

## A6-06 debit linkage

[Issued cards](issued-cards.md) are separate authoritative owner/account/provider-epoch records, with one active debit token per BANK account. Provider changes/closure and rebase collision retirement revoke retired tokens; permanent primary rebase retains the account/token exception. No extra account slots, score or debt are created.
