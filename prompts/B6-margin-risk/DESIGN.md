# B6 design brief — Margin lending, leverage and liquidation

Draft revision 1 — 2026-11-13. Planning only; no risk values or implementation approved. Read [phase scope](README.md), [shared contract](../planning/B-SERIES-DESIGN.md) and the [A6 collateral handoff](../../.codex/A6/architecture/a6-downstream.md#b6-collateral-and-variable-rate-products). Proposed packages require separate invocation.

## Outcome and authority

Offer an explicitly enrolled margin product whose borrowing, collateral restrictions, calls and forced sales remain explainable and recoverable through existing finance/custody services. Ordinary cash brokerage remains usable without enrolling. Margin is a new product, not a reinterpretation of existing fixed unsecured loans.

A6/Stage A owns lender funding, borrowing, interest, repayments, debt reporting and residual debt. B2 owns collateral quantity and claim priority. B3 supplies approved current financial marks and executable liquidity; B4 executes liquidation orders with ordinary fills and fees. B6 owns a shared risk view, enrollment, collateral policy, call state and liquidation orchestration. B7 later adds return obligations to that same view.

Current `Loans.capabilities` fixed/variable/secured gates remain authoritative. Do not advertise variable or secured lending until a separately implemented adapter supplies ownership, valuation, reservation, release/default history and, if chosen, rate-reset semantics. Existing fixed debt keeps its terms. Item reference costs are not eligible securities marks.

## Proposed state and storage contract

Persist margin agreement ID, owner/brokerage/lender, immutable accepted terms, policy version, effective time and status. Persist collateral claims with unique priority, security quantity, custody revision and release conditions. Persist debt references to Stage A, versioned risk snapshots, call ID/deadline/reason, liquidation plan/leg IDs and terminal outcomes. Do not duplicate authoritative debt balances.

Proposed states: `DISABLED → ACTIVE → RESTRICTED → CALL_OPEN → LIQUIDATING → RESOLVED`; termination requires no debt, open orders, collateral claims or unresolved outcomes. A cured call returns to the explicitly approved state, preserving its history. Missing marks or backlog readiness restrict risk-increasing actions; they do not erase debt or assume a zero liability.

Risk input carries asset marks, FX versions, mark instants, free/reserved/pledged quantities, cash, every covered liability, fees, pending orders and policy revision. The output explains eligible collateral, haircut, equity, initial/maintenance requirements, maximum additional commitment and unavailable reasons. Include signed BANK cash and closed-primary lifecycle from the [current overdraft contract](../../.codex/architecture/overdrafts.md); count negative cash/debt once, and do not automatically draw a bank overdraft to fund brokerage. Prevent duplicate valuation of the same holding or liability across installed wealth sources.

Illustrative policy only: assets 1,000, debt 400, equity 600 and maintenance requirement 30% of gross assets gives a 300 requirement. A fall to 500 leaves equity 100 against 150 required, a 50 deficiency. A cash deposit of 50 cures only if fees, marks and other obligations remain unchanged. No 30% rate or cure deadline is approved by this example.

Commit an order/withdrawal/risk-increasing borrow only after recomputing the affected risk under the serialized transaction, including existing holds. Reserve collateral and cash against unique obligations; no quantity can be freely withdrawn, pledged twice or simultaneously sold except through an approved release-and-settlement boundary. The same rule applies to internal integrations, not only menus.

Borrowing must atomically compose lender-funded Stage A postings, debt creation, collateral restriction and original outcome. Reject missing adapter/cash/mark authority without partial effects. Interest uses Stage A eight-place accrual and retained effective policies; B6 never posts its own competing interest. Variable-rate resets, if approved, require captured boundary/rate history before capability activation.

Calls use persisted UTC effective times/deadlines and original deficiency snapshots; game sleep and reconnect do not reset them. Process overdue work chronologically through bounded dispatcher stages. A later favorable mark can cure only under the chosen revaluation policy. A stale worker calculation is discarded or replanned against current revisions before committing.

Liquidation plans select deterministic eligible lots/orders and submit B4 orders in bounded batches. Recompute remaining deficiency after each committed fill. Closed/halted/illiquid markets may leave `LIQUIDATING` with remaining debt and a retry condition; never force a fictitious fill or forgive residual debt. Cash proceeds pay debt/fees under approved priority, with any remainder released only after obligations clear.

If 10 shares offered for liquidation receive only four fills, record four sold and six still restricted, plus actual proceeds and debt remaining. A cancel race releases only the unfilled B4 reservation. Restart resumes the same call/plan/leg IDs; a published failure or disconnect cannot duplicate a sale. Unknown commit gates replacement execution.

Server-thread entry captures enrollment consent and authority; storage worker owns financial/risk decisions; CPU workers may prepare immutable candidate plans. Bound positions per snapshot, call batches, liquidation legs and retry work. Private diagnostics expose stale sources, restriction reasons, deadline, mark versions and remaining debt. Operators may pause new exposure but cannot silently waive existing obligations.

## Coverage and handoffs

| Origin | Existing behavior | B6 proposal and acceptance | Handoff |
| --- | --- | --- | --- |
| New B6 scope | No current margin | B6-01/03 risk/enrollment and exclusive collateral; competing withdrawal/order cannot overcommit | B7 shared long/short risk view |
| A6 loan seam | Fixed unsecured debt; unsupported secured/variable capabilities | B6-02 implements only approved adapter; absent adapter remains unavailable | Stage A debt lifecycle |
| F08/F16 reused | Existing money and custody/execution authority | B6-05 actual finite fills repay debt without synthetic cash/shares | B9 accounting audit |
| F19/F23 follow-through | Server validation and scoped recovery evidence | B6-04/06 retain deadlines and original plan after restart | B9 stress/recovery evidence |

These are new products or reused contracts, not evidence of historical margin support. Any new interest, default or claim-priority policy requires approval.

## Ordered future packages

### B6-01 — Agreement and unified buying power

Owner: B6 risk. Requires accepted A6/B2/B3/B4 interfaces. Define enrollment, liabilities, risk equation and unavailable/restriction reasons with no enabled borrowing. Gate: explicit consent, cross-owner refusal and illustrative shock calculation. Exclude interest posting and forced sales.

### B6-02 — Funded lending adapter

Owner: Stage A product adapter coordinated by B6. Requires B6-01 and approved lender/rate/repayment terms. Compose funding, debt and collateral in one transaction; add variable resets only if selected. Deliver this adapter gated until B6-03 exclusive claims and commitment checks are accepted. Gate: insufficient lender funds or absent adapter produces no exposure; interest remains Stage A-owned. Exclude liquidation strategy.

### B6-03 — Collateral and commitment boundary

Owner: B6/B2 coordinated claims. Requires B6-02. Define priority, eligibility/haircuts, release and risk checks shared by withdrawal, order, transfer and borrowing. Gate: two competing commitments cannot pledge one holding twice. Exclude collateral reuse/rehypothecation unless separately approved.

### B6-04 — Calls, deadlines and servicing

Owner: B6 lifecycle. Requires B6-03 and dispatcher readiness. Define call/cure/default states, chronological scheduling, policy revisions and private diagnostics. Gate: restart preserves deadline and stale marks restrict new risk without losing claims. Exclude simulated forced proceeds.

### B6-05 — Finite liquidation and residual debt

Owner: B6 orchestration through B4/Stage A. Requires B6-04. Define deterministic lot selection, fee/debt priority, partial fills, market closure and retries. Gate: four-of-ten example reconciles actual proceeds, restricted remainder and residual debt. Exclude direct book/price edits.

### B6-06 — Product controls and handoff

Owner: B6 integration. Requires B6-05. Publish capability/readiness, bounded owner views and B7 liability seam. Select focused changed-behavior evidence where useful; reuse accepted settlement/interest evidence. Gate: no enabled capability without implemented coverage and all unresolved claims visible. No automatic full-suite run.

## Decisions before implementation

| Decision | Recommendation — proposed | Alternative and consequence |
| --- | --- | --- |
| Product scope | Secured brokerage margin first; keep variable rate optional | Combined variable launch needs rate-reset history and expands debt/recovery scope |
| Thresholds/leverage | Review initial/maintenance, concentration and FX haircut table together | One global percentage is simpler but conceals concentration/currency risk |
| Claim priority | Exclusive first-priority pledge; no collateral reuse initially | Shared claims need ordered recovery and allocation under shortfall |
| Call timing | Persisted real-time deadline, explicit restrictions and cure policy | Immediate liquidation removes cure window and changes gameplay materially |
| Illiquid liquidation | Bounded repeated ordinary orders with visible residual debt | Guaranteed fills require an explicitly funded backstop, not an assumed buyer |
| Debt collection | Reuse only approved Stage A lifecycle consequences | New deficiency/collection rules require separate policy and provenance |

Open inputs: approved lender capitalization, rates/reset choice, risk formula and values, eligible marks/freshness, call deadline, liquidation ordering, fee priority and treatment of market-wide outages. No numerical default is approved here.

## Future evidence and exit

Apply [shared verification scope](../planning/B-SERIES-DESIGN.md#approval-and-delivery-boundaries): diff/link review now; future ordinary runnable delivery uses required build/client commands once and at most one useful focused test invocation. Retain runServer/main registration/runtime dependencies. Choose meaningful real-service/SQLite race or recovery evidence rather than testing every formula permutation.

Exit requires documented adapter capability, approved policy versions, exact remaining debt/claims, implementation evidence and B7/B9 limits. Preserved old fixed-loan evidence does not prove new secured lending.
