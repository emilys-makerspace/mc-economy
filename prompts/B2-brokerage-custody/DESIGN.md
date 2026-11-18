# B2 design brief — Brokerage and custody

Draft revision 1, 2026-11-13. Planning only. Read the [phase scope](README.md) and [shared B-series contract](../planning/B-SERIES-DESIGN.md). Proposed policies below require review before implementation.

## Intended outcome and dependency boundary

B2 gives each authorized owner a brokerage identity linked to Stage A funding, a balanced securities custody ledger, encumbrances, lot accounting and bounded portfolio/history reads. No player trade executes in B2. Holdings originate from explicit issuer/treasury/counterparty custody movements; no unbounded implicit market counterparty survives the redesign.

A4/Stage A owns money, funding transfers, FX and cash holds. The current [overdraft contract](../../.codex/architecture/overdrafts.md) permits signed BANK balances and closed primary accounts; resolve actual eligible funding, include liabilities once and never imply automatic brokerage overdraft borrowing. B1 owns instrument IDs, units and fungibility. B2 owns share custody, reservation and return obligations. B3 owns accepted market marks and participant liquidity. B4 is the shared cash/security execution coordinator. B6/B7 later own margin/lending policy but must consume B2 claims instead of maintaining independent position quantities.

Resolve the dependency cycle explicitly: B2 custody and settlement interfaces precede B3 participant inventory; B2 can specify and deliver unavailable-mark portfolio reads before B3. Activation of wealth qualification requires accepted fresh B3 marks and complete liability coverage. B3 must not create a temporary independent settlement writer to bypass B4.

## Proposed records and service contracts

Persist brokerage ID, owner/world, linked Stage A trading funding identity, lifecycle/revision and policy references. Preserve money separation from BANK products. Cash is read through the Stage A account contract; a displayed brokerage balance is not a second authoritative cash balance.

Persist security custody accounts, balanced quantity movements, lots, lot allocations and identified claims. Each movement names source, destination, security, exact quantity, reason, operation and transaction. Issuance/retirement uses explicit authorized issuer accounts and supply facts; registry outstanding-share metadata alone creates nothing.

Use disjoint custody buckets for free, order-reserved, pledged and delivered-on-loan inventory. Beneficial ownership and return obligations require separate views so loaned holdings do not appear as spendable custody. Before B7, loaned claims remain unsupported. Multiple claim kinds cannot count the same share twice.

Proposed brokerage states are OPEN, CLOSING and CLOSED. Closure requires zero cash, no custody or active obligations and no unresolved outcomes. A closed ID retains history and does not reactivate. Policy on keeping an empty permanent trading identity is unresolved; Stage A account lifecycle rules prevail until approved replacement.

Expose trusted atomic participants for validate/reserve/consume/release and settlement application, not public database callbacks. Inputs name actor, brokerage, instrument/quantity, operation, expected revisions and policy/quote references. Results distinguish unavailable, unauthorized/absent, stale, insufficient free assets, unsupported claim, overflow and committed original outcome. Public reads authorize each bounded cursor page against a consistent snapshot.

Funding/unfunding moves Stage A money atomically between eligible owned accounts and the trading identity. Same-currency movement is the proposed baseline; any FX conversion requires explicit reviewed quote and captured Stage A settlement rules. Reject foreign ownership, closed/protected-ineligible endpoints, encumbered cash and unknown outcome replacement.

## Lot and valuation proposals

Recommend FIFO disposal with acquisition price, currency, allocated fee, FX references and immutable acquisition operation per lot. Cost basis is reporting information, never spending authority. Cross-listing aggregation follows B1 security/fungibility identity, not company name. Nonfungible classes remain separate.

For a simple illustrative lot sequence, buy 10 shares for EUR 100 plus EUR 1 fee, then 5 for EUR 60 plus EUR 0.60 fee. FIFO sale of 12 consumes EUR 101 plus EUR 24.24 basis = EUR 125.24. At EUR 150 net sale proceeds, realized P&L is EUR 24.76. This example proposes fee allocation and FIFO; it does not approve fee rates or cross-currency tax accounting.

A portfolio reports quantity, free/encumbered categories, original basis, market mark identity/time, captured FX and reporting projection separately. Missing marks produce UNAVAILABLE/PARTIAL coverage, never zero value. Historical projections retain their original references across world rebase.

The existing wealth seam requires a stable trusted source ID/revision, bounded immutable manifest, retained mark history and fresh assets/liabilities. Respect the recorded one-financial-day freshness rule and existing source/component caps, resolving current signatures before implementation. Prevent duplicate holdings and margin liabilities across sources; raw item valuation cannot qualify securities wealth.

## Atomicity and recovery examples

An owner with 10 free shares requests two reservations of 7. Serialized validation permits at most one; free becomes 3 and reserved 7. Replaying the successful operation does not reserve 7 again. Releasing it twice returns the original outcome and leaves free 10.

Settlement of 4 of the reserved 7 consumes exactly 4 from the identified claim and source lots, credits the buyer's custody and leaves 3 reserved. B4 must commit cash, custody, fees, liquidity and outcome together. A failed cash leg cannot leave a delivered share or consumed lot.

Persist claims and outcomes with ledger facts. Disconnect does not release a committed reservation arbitrarily; its owning order/lifecycle decides release. Recovery reconciles total quantity and claim ownership before making affected assets available. Unknown authoritative movements fail closed rather than rebuilding balances from client snapshots.

## Feature coverage

| ID | Historical behavior | Proposed owner/replacement | Acceptance and later handoff |
| --- | --- | --- | --- |
| F07 | Separate TRADING account | B2-01 linked brokerage/funding identity | No BANK-cap change implied; Stage A lifecycle retained pending approval. B4 exposes UI. |
| F08 | Trading funding/unfunding | B2-01 Stage A atomic transfers | Owned endpoints, replay and held-funds rejection; B4 uses the same service. |
| F16 | Persistent holdings and owned-account reads | B2-02 custody; B2-03 lots/queries | No negative free quantity, duplicate claim or invented counterparty; B4 settles executions. |
| F16 | Immediate holdings changes from unbounded market | B2-04 atomic composition seam | Supply/counterparty reconciliation; B3 finite participants and B4 execution replace old behavior pending review. |

## Proposed ordered packages

### B2-01 — Brokerage lifecycle and funding

Requires A4/current account APIs and B1 identity contract. Brokerage owner defines lifecycle, Stage A account link, authenticated queries and funding intent/results. Deliver no second cash ledger. Exclude shares/trades/valuation. Gate: duplicate opening, competing funding, stale/foreign destination and original-outcome examples have explicit outcomes.

### B2-02 — Custody postings and claims

Requires B2-01 and B1 quantity/relationship rules. Custody owner defines issuer/counterparty inventory, balanced movements, free/reserved/pledged vocabulary and bounded claim lookup. Pledge/lending activation remains unavailable pending B6/B7. Exclude matching and uncontrolled issuance. Gate: conservation, competing reservation, replay and claim release cases reconcile by security.

### B2-03 — Lots, portfolios and valuation contract

Requires B2-02 and approved lot/fee/FX policy. Portfolio owner defines immutable lot allocation, cost basis, cross-listing grouping, history cursors and mark-availability results. Deliver the trusted wealth-source manifest contract; activation additionally requires accepted B3 marks/liability coverage. Exclude trading and unsupported collateral qualification. Gate: worked FIFO or approved alternative, unavailable mark and historical rebase labels are unambiguous.

### B2-04 — Atomic settlement interface and downstream handoff

Requires B2-02/B2-03 and Stage A transaction composition review. Custody owner agrees with B4 the reservation consume/release, lot allocation and money participant boundary. Deliver a versioned invariant/reconciliation contract usable by B3/B4/B6/B7 without SQL exposure. Exclude player execution. Gate: failed money leg changes no share, one operation cannot consume twice, and source/destination/claim totals survive recovery.

All packages inherit shared authority, worker, version, overflow and original-outcome rules. Exact API names and migration layouts remain implementation-package decisions.

## Decisions and unresolved inputs

| Decision | Recommendation (proposed) | Alternative and consequence |
| --- | --- | --- |
| Funding currencies | Explicit currency balances supported by the Stage A trading identity, with no automatic FX | Single currency simplifies UI but requires reviewed conversion/rebase policy. |
| Lot selection | FIFO and immutable per-fill allocation | Specific-lot selection adds request/race complexity; average cost loses lot lineage. |
| Fee allocation | Allocate acquisition fees into lot basis with conserved residual destination | Expense separately changes P&L presentation and must be disclosed. |
| Cash/share composition | One existing database transaction through trusted participants | Saga delivery would need a new approved custody risk/recovery contract. |
| Mark absence | Partial/unavailable reporting; block affected financial qualification | Last-value eligibility can admit stale collateral and needs separate policy. |

Open: exact issuer/treasury opening inventory and capitalization (B3); fractional quantities after B8 actions; closure of empty brokerage; lot precision and residual destinations; current wealth-source integration signatures; historical holding migration evidence. No old catalog/holdings migration is presumed available or authorized.

## Future evidence and exit

Use focused real-service/SQLite behavior cases for custody conservation, competing holds, lot allocation or the changed settlement seam. Reuse unchanged Stage A evidence. Recovery tests are useful only where the new durable contract needs them; no mandatory test per package or full-suite gate. This draft uses diff/link review only under [verification scope](../../.codex/VERIFICATION.md).

B2 exits with custody/funding/valuation contracts and explicit capability readiness. It does not advertise player execution, financial qualification without marks, variable lending or securities lending.
