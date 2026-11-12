# Funded credit facilities — A6-08-01

## Schema and immutable records

V25 adds `credit_facilities`, `credit_facility_tokens`, `credit_statements`, plus owner, daily-deadline, statement-deadline, unique active-token and statement-history indexes. No rows are seeded. Facility body records fixed world/borrower/issuer/network/currency/limit/Tier, origination anchor, completed day/statement, revision/status/access, purchase and advance buckets, outstanding minimum requirements and disjoint statement debt attribution. Normalized indexed columns are checked against the canonical body when read. No second monetary store exists: debt changes compose typed financial postings and the domain snapshot atomically.

New v1 facts: `economy:a6/credit-facility` (18 base64url fields), `credit-token` (12), `credit-statement` (11), `credit-event` (four), `credit-borrower` (four); `credit-treasury` records world/issuer/currency/reservation/floor/effective time and any captured FX conversion, reference residual and typed rounding identity. `credit-repayment-fx` retains repayment, funding, FX, state and timing witnesses. `credit-card-replacement-fee` retains the captured fee with a facility target UUID, distinct from old debit fee facts. Old debit v1 decoders remain unchanged.

`CreditFacilities` canonical kinds use `economy:credit` with issue, draw, repay, increase, close, replace and revoke verbs. Every request binds authenticated owner/world, expected revisions, contract amounts, selected identities and applicable quotes/lease. Original A1 operation lookup precedes preparation; uncertain commits use that original identity, never a new facility or delivery. Registered validators reject malformed historical facts. No replay inserts an item.

## Origination and capital

`issuance` requires a sealed live `BranchAdmission` CREDIT_ISSUE proof for the actual issuer kiosk, fresh denomination/calendar/Tier/profile and a completed capital manifest. It enforces approved issuer networks, three open facilities, combined revolving/exposure ceilings, arrears/default blockers, current score and finite funding. An absent profile uses exactly 2,500 current world-major units and score 650 in the same transaction through `CreditProfiles.firstIssuance`. Later facilities use issuer-native currency; a different issuer cannot repeat the starter. Accepted underwriting denials retain inquiries without creating a profile; malformed/unauthorized attempts do not.

`CreditTreasury.reserve` transfers own CASH to RESERVATION in actual facility currency. If native cash is insufficient, it exchanges only needed base cash through A2/A4 clearing at a fresh immutable publication, rounding source debit upward. The base opening operating floor is protected; due yield distress denies new commitments. Customer deposits, receivables and income never fund a line. The shared reservation account is paired with facility limit/principal allocation so draws cannot borrow another line's commitment. Increases use new live issuer admission, current underwriting and fresh Tier, reserve only the positive delta, and leave anchor/debt/profile/starter unchanged. No automatic limit reduction exists.

## Tokens and draw authority

Credit tokens are separate from debit tokens and reference a facility. One ACTIVE token per facility, five-financial-year expiry, shared revocation for copies and A5 protected delivery remain authoritative. `CreditCardDesigns` and `CardCatalog` register only approved issuer/network/Tier combinations using existing original art. Items store only bounded world/token references. Owner read access accepts either validated debit or credit tokens; repayment does not require possession.

`CreditDrawAdmission.capture` is server-thread only and returns a sealed proof after current player/menu/terminal/held-card capture. Tick refresh revokes stale/dead/disconnected/replaced audiences; the worker reads detached flags and rechecks token, exact design, current terminal generation/revision and canonical request. PURCHASE requires matching current register price, merchant destination and currency; ADVANCE requires ATM/teller admission and an owned BANK destination. Integrated goods/cash delivery flows remain A5 responsibilities: an advance credits the BANK account once, and any physical withdrawal is an independent custody operation. No virtual draw packet or UI action was added.

Draw preparation requires current token/network/facility/profile revisions, fresh current service Tier, aggregate exposure and contractual headroom. Interest can exhaust headroom but does not create a new missed payment. Outstanding advance principal is at most floor(limit/5); interest consumes overall headroom only. A draw transfers RESERVATION to merchant/owned BANK and recognizes issuer RECEIVABLE plus borrower LIABILITY, balanced in each currency. It never issues money.

## Interest, statements and payments

`CardServicing` uses 34 significant digits/HALF_EVEN, stored 18-place major-unit accrual and signed bucket-local residual. Completed facility-anchor days accrue on principal plus unpaid bucket interest at APR/364; no fraction of an incomplete day is charged. Keystone rates are 21.99% / 23.99%, the other networks 19.99% / 21.99%. Prior-day carry compounds; current-day interest does not enter its own basis. Full payoff before the next cutoff stops the next charge. There is no additional interest-free purchase grace in the approved daily policy.

Two production `CreditRuntime` providers process daily interest at stage 1 and statements at stage 2. They use indexed earliest anchor deadlines, deterministic world/provider/instant event IDs, at most 16 exclusive-cursor targets and one child per shared dispatcher turn. APPLYING events survive their final target. Manual mutations decline when a day/statement is due; ServerServices' shared calendar gate prevents overtaking scheduled stages. No reporting/score provider is installed.

Statements close every 28 days after that day's interest; due is 14 days later. IDs derive from world/facility/close instant. Frozen snapshots retain debt/principal, newly earned period interest, new and total minimums. New minimum is min(debt, CEILING(2% unpaid principal + this period's interest)), further capped by debt minus old unmet requirements. Old requirements retain original due dates; minima are metadata, never a second liability. Previously billed interest is not billed again as newly earned interest. Keyset statement reads return at most 16 rows with lookahead.

Repayment independently satisfies oldest unmet requirements and allocates money to oldest overdue statement debt (interest then highest-APR principal), other interest, then highest-APR principal; equal APR advances first and older attributed debt precedes new spending. Payment cannot exceed posted payoff. Signed subminor payoff residual disposition is explicit in the immutable payment event; historical earned interest survives payment. Principal receipts restore open-line RESERVATION; interest receipts become own CASH. Closed-line principal goes to CASH. Closing releases only unused reservation, revokes the token and preserves debt/interest/anchor; final payment on a closed line marks SETTLED.

Same-currency payoff quotes capture facility/funding/ledger revisions and expire within 30 seconds or the next daily/statement boundary. `CreditRepaymentFx` adds exact-credit A2 conversion, denomination/provider/publication/current pricing witness, source debit and separate fee, reference residual and one-time durable FX quote consumption. It composes the entire source debit/clearing/debt receipt atomically without a fake BANK account. Only the contract-currency amount satisfies debt/minimum. Fee failure rolls everything back. Existing account uniqueness and the four-account cap are unchanged.

Replacement requires CREDIT_REPLACE issuer admission and the current latest token/revisions. Free within 28 days before expiry or afterward; earlier replacement uses the existing exact 25 world-major fee calculation and fresh funding/FX witness under a separate credit fee fact. No fee charges the facility. Failure leaves the old token unchanged. Remote owner revocation is free.

## Services, valuation and boundary

`LedgerService` exposes owner-granted facility/statement pages, issue/increase/draw, same-currency/FX repayment quote/submit, close, replacement quote/submit and remote token revocation. Every mutation uses `submitOwned`; no operator grant can substitute for owner authority. `CreditDrawAdmission.Proof` supplies physical admission rather than a client boolean. Existing `KioskAdmission` issues branch proofs. No new banking network mutation was registered; integrated product screens remain later work.

`CreditLiabilities` is a required production valuation source. It aggregates bounded currency totals and reconciles them with the typed borrower LIABILITY ledger before supplying debt to net worth; a mismatch yields unavailable. Fresh origination/draw input uses that reconciliation and current Tier. Later loans/collection adapters must extend owner exposure/blockers rather than assume absent installed debt is zero.

Live servicing bodies retain at most 16 outstanding requirements and 34 debt slices; fully paid entries compact while frozen history remains. Exceeding a bound fails closed. This package does not activate the A6-10 default/report/collection lifecycle; its 84-day default transition must be installed before accepting long-lived unpaid production portfolios that could reach these limits. Autopay is not installed, no collections entity exists, and recurring score recalculation remains dormant until A6-10-03. Do not label those dependencies verified by these fixtures.

## A6-08-02 diagnostics

A6-08-02 adds prospective immutable daily-input/payment-allocation receipts to the same servicing transactions, plus bound owner/statement display validation and audited diagnostic pages. Financial amounts and historical v1 statements are unchanged. See [diagnostics](credit-diagnostics.md).

## A6-08-03 acceptance

A6-08-03 accepts the detached foundation and gates LedgerService new issuance/increase/draw inside journal preparation until the required lifecycle is installed/accepted. No operator/config bypass exists; stored outcomes replay first. Protected reads/debt actions remain under their ordinary guards. Credit API owner binding is normalized without changing final canonical bytes. See [acceptance and lifecycle source](credit-acceptance.md).

## A6-09-01 shared exposure

Credit underwriting includes outstanding loan exposure and blockers; loan underwriting includes credit exposure and blockers, counting each source once. Shared lender receivables now include cards and loans while borrower liabilities remain separate. New production loan origination uses the existing CreditSafety gate; protected repayment remains available. See [loan contracts](loans.md).

## Fixed advance fees (2026-11-09)

Current physical advances freeze `AdvanceFees` (terminal anchor/generation/revision, bank and amounts) in their canonical request. All banks charge a CAD3.00 base fee converted to facility currency. A different issuing bank at an ATM adds a converted CAD2.50 base fee; tellers and same-bank ATMs are exempt. Both fees use captured current CAD/target rates and round once to the nearest 0.25 target major unit or 25 JPY, with HALF_UP ties. The canonical request includes the FX publication; changed publications reject a stale review. The requested proceeds, issuer fee and ATM surcharge consume reservation and advance principal together; fees count toward all underwriting/headroom checks and the outstanding 20% sublimit, accrue at the advance APR and follow existing repayment allocation. Fees are earned atomically by their supplying institutions. Purchase draws are unchanged. Old committed outcomes and historical snapshots retain their original amounts; replay cannot charge again.

## Trusted over-limit integration (2026-11-12)

[Current override contract](overdrafts.md) adds trusted Java-only flagged draws/checkout. Ordinary canonical bytes remain unchanged. Flagged transactions may exceed contractual headroom using issuer-funded extra principal; other risk/authority and advance sublimit checks remain. Repayment/increase reservations account for over-limit principal.
