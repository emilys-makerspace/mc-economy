# Shared debt lifecycle foundation

## State and policy

A6-10-01 implements shared card and loan obligations, delinquency, default, collection ownership, repayment and expiry. DebtRecords separates payment status, creditor ownership, collectability and report visibility. Original facility identity, due date, default date and seven-year report expiry remain captured throughout the lifecycle.

Financial days are 1,200,000 UTC milliseconds. An unmet obligation becomes delinquent just after its due instant. Default occurs at the oldest unmet due plus84 financial days. A positive short payment reduces the original unmet amount. Full satisfaction cures that obligation and preserves its original due identity. Reporting occurs at due plus two financial days. Later cure appends a report revision while retaining the original met/missed snapshot.

At default, the core facility becomes DEFAULTED and credit access closes. Earned interest through that instant is recognized with HALF_EVEN minor settlement and an explicit signed residual disposition. Card undrawn reservations return to the issuer's own cash. Defaulted cards and loans leave the active accrual providers. Original captured rates and contracts remain available for history.

Each case initially belongs to the originating lender. Collection eligibility starts at default even while a funded sale is pending. A case freezes principal and interest in its contractual currency. A committed positive partial payment before expiry moves the acknowledgement anchor and collection expiry by728 financial days from that payment. Full payment settles the case. Original default and report expiry stay fixed. Owner reads, dialogue, previews, failed payments and zero amounts cannot acknowledge debt.

At collection expiry, demands become ineligible and the scheduled transition marks the case UNCOLLECTABLE. Remaining payment requirements clear. Borrower liability and gross historical receivable remain. Creditor allowance/write-off accounting reduces the remaining carrying asset to zero. A stale payment at or after expiry rejects. Default report visibility ends at the original default plus2,548 financial days. Paid cases show PAID_DEFAULT while visible.

## Monetary boundaries

DebtFinance freezes core balances, transfers funded claims, allocates frozen repayments and records write-offs through InstitutionSettlement. The lifecycle manager combines those projections with case revisions, immutable facts and the original journal outcome in the same transaction. Existing A1 lookup resolves retries and uncertain commits before handler preparation. The financial operations have journal evidence for every currency and counterparty.

Waystone purchases a claim for10% of remaining principal, rounded down, with at least one minor unit for positive principal. Pure interest can transfer for zero consideration. Its own funded treasury conversion and operating floor govern admission. Insufficient funding completes a COLLECTIONS_PENDING_SALE receipt and schedules a daily retry. The original lender remains creditor until an actual funded transfer commits.

A sale transfers the gross receivable once. The seller receives the purchase cash and records the difference as loss. The collector records gross face value and a separate negative ALLOWANCE. Its acquisition discount produces recovery income proportionally as payment arrives, using FLOOR until final settlement takes the remaining residual. At expiry, only the remaining net carrying value becomes additional creditor loss. Borrower debt is independent of the discount.

DebtClaims maintains typed receivable and borrower-liability totals with core-table insert/update and case-ownership-transfer triggers. Native loan repayment and lifecycle monetary transitions compare these constant-size witnesses with the ledger before committing. The previous128 shared-lender-claim repayment scan is replaced. Existing per-owner/source bounds continue to apply to valuation and underwriting.

## Clock and observer integration

Five v1 providers join the shared DeadlineDispatcher. They have durable APPLYING cursors, indexed deadline heads and bounded keyset targets.

| Provider suffix | Stage | Work |
| --- | --- | --- |
| debt-due | 4 | Persist delinquency after the due instant |
| debt-default | 4 | Freeze the oldest eligible unmet debt and open its case |
| debt-expiry | 4 | End collectability and write off the net carrying asset |
| debt-sale | 4 | Fund a pending sale or complete a deferred retry receipt |
| debt-report | 5 | Snapshot the original obligation at due+2days |

Existing interest and statements use stages1 and2. Scheduled payment work belongs at stage3. The later score stage6 precedes Tier stage7. Provider name ordering places default and expiry before sale when their instants coincide. Each dispatcher turn commits at most one target or completion. Empty domains have no target work.

SqliteStorage's commit decorator observes existing v1 card/loan snapshots. It synchronizes at most16 remaining requirements, preserves immutable originals and appends cure revisions in the original financial transaction. Collection payment timestamps come from their accounting witness. Final facility state follows the same-commit case snapshot, including settlement and uncollectability. Current lender diagnostic totals exclude sold claims from the original issuer's portfolio.

Production score dispatch and its complete reporting/utilization acceptance remain with A6-10-03. Its cadence remains the approved world-aligned28-day cycle. New production borrowing continues through CreditSafety until that acceptance. The stage3 payment/stage5 report/stage6 score-order test uses explicit fixture providers to verify the ordering contract.

## Storage and historical compatibility

V31 adds nine tables. debt_obligations holds original due amounts and current unmet/report state. debt_cases holds independent case dimensions and frozen debt. debt_reports stores append-only report revisions. debt_claim_totals holds constant-size claim witnesses. debt_facility_states holds saved status. debt_due_queue, debt_default_heads and debt_sale_attempts index pending domain work. debt_bootstrap tracks bounded installation progress. Twelve explicit indexes and five triggers support the lookups and witnesses.

The existing institution account table is copied with the additional ALLOWANCE role, retaining account IDs and balances. Negative allowances are separate from spendable cash. New allowance accounts use institution-account v2 with an ALLOWANCE-only decoder. Historical v1 account decoding retains its earlier role set.

New v1 families are debt-obligation, debt-case, debt-report, debt-state, debt-sale-attempt, debt-freeze, debt-treasury, debt-accounting and debt-repayment-fx under economy:a6. The case/report/obligation codecs are canonical base64 field records. State and financial receipts use bounded typed fields. Bootstrap administrative receipts use debt-sale-attempt with BOOTSTRAP_CARD/BOOTSTRAP_LOAN reasons and the bootstrap operation UUID. Sale diagnostics select actual case IDs and distinguish the journal operation kind. Existing card, loan, FX and posting families supply their original evidence. Main/offline schema validation and complete-world backup support V31.

Startup processes one saved facility per bootstrap turn. Compatible existing snapshots retain original obligation IDs and due dates without repeating finance. Already tracked facilities retain their lifecycle progress. An untracked original deadline behind the completed financial frontier fails with DEBT_LEGACY_TIMELINE_REQUIRES_RECONCILIATION. Those worlds require a separate journal-based reconciliation migration before readiness. This package verifies the rejection and recovery preserve financial state. Later acceptance must address that historical-timeline boundary before unrestricted activation.

## Owner contracts and handoff

LedgerService exposes owner-bound collectionCases, collectionCase and debtReports reads. Report pages use an exclusive effective-time/report-ID/revision cursor with up to16 entries and one lookahead. Owner/world and case projection bindings are checked before publication.

quoteCollectionPayment and repayCollection provide native protected repayment from an owned open BANK account. quoteCollectionPaymentFx and repayCollectionFx capture an explicit exact-credit conversion with separate source fee and durable single-use quote consumption. Quotes bind case, funding, ledger, provider, denomination and FX revisions. Expiry is at most30 seconds and is clipped before relevant loan/card/collection boundaries. Protected FX pricing preserves repayment access below specialist qualification while retaining ordinary fees.

A6-10-02 owns owner/operator lifecycle diagnostics. A6-10-03 owns broad lifecycle acceptance, production score activation and remaining restart/crash/abuse/performance probes. A6-11 can use demands(case,now) for debtor-only collection presentation and reportVisible/bureauStatus for independent bureau state. The current package supplies server/worker financial APIs for those integrations.

[Credit foundation](credit-acceptance.md), [loan foundation](loan-acceptance.md) and [verification](../A6/verification/A6-10-01.md) record the related contracts and measured coverage.

A6-10-02 adds [bounded owner history and audited operator explanations](debt-diagnostics.md) through schema32 diagnostic indexes. The lifecycle facts and scheduling providers described above retain their contracts.

A6-10-03 accepts [the reporting/scoring dependency and A6-11 payment handoff](debt-acceptance.md). Safe bounded legacy replay now reconstructs original reports and verifies tracked history. Historical monetary defaults or incomplete tracked provenance retain precise readiness rejections. Production includes the stage6 score provider and current reporting admission.
