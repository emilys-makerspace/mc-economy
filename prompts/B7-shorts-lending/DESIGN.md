# B7 design brief — Short selling and securities lending

Draft revision 1 — 2026-11-13. Planning only. Read [phase scope](README.md), [shared contract](../planning/B-SERIES-DESIGN.md) and [B6 risk brief](../B6-margin-risk/DESIGN.md). All rates, lending consent and deadlines below remain proposed; implementation requires separate selection.

## Outcome and ownership

Support covered short selling against explicitly available lending inventory, with durable obligations to return shares, restricted proceeds and the same B6 margin assessment used for long positions. No naked shorts, double lending or independent short-margin money engine.

B2 owns custody location, lending claims and return obligations. B4 owns sell/cover execution and cash/share settlement. B6 owns aggregate risk, collateral restrictions, calls and liquidation. Stage A owns fees, borrowing, interest and residual monetary debt. B7 owns pool enrollment, locates, lending agreement lifecycle, borrow-fee scheduling and recall orchestration. B8 owns corporate-action transforms and dividend entitlement coordination.

A lender's economic claim, current physical custody and a borrower's return obligation are distinct records. A transferred share cannot be counted as free lender inventory while on loan. The lender claim and short liability must enter financial valuation exactly once. Signed bank cash/overdraft debt follow the [current overdraft contract](../../.codex/architecture/overdrafts.md); do not count the same bank liability again as a separate debt component.

## Proposed storage and state contract

Persist lending-pool enrollment/consent and eligible quantities; locate ID with owner, security, quantity, expiry and pool revision; lending agreement ID, lender/borrower, quantity outstanding, rate-policy version, start/effective times, collateral link and fee residual; recall ID/deadline; and execution/return/outcome references. Keep securities obligations separate from money liabilities.

Proposed locate states: `AVAILABLE → RESERVED → CONSUMED` or `RELEASED/EXPIRED`. A locate is a bounded reservation, not a completed loan or permission for unlimited sells. Loan states: `OPEN → RECALL_PENDING → RETURNING → CLOSED`, with an explicit unresolved/buy-in condition rather than pretending failed returns succeeded.

The sell transaction consumes only the filled locate quantity, establishes the return obligation, transfers the borrowed units through B2, settles B4 cash/fees and restricts agreed proceeds atomically. A partial fill leaves a distinct remaining locate/order reservation. Cancellation releases only unfilled quantities; it cannot cancel the return obligation for committed sales.

Example: a pool has 100 free units. A locate for 60 leaves 40 available. A competing request for 50 is rejected or explicitly reduced only with consent. A sell filling 20 creates a 20-unit return obligation; cancelling the remaining order releases 40, leaving 80 units unloaned and 20 on loan. Replaying any operation does not release or sell again.

Cover orders buy ordinary finite liquidity. Bought units are reserved to the named return obligation and returned atomically with its reduction; do not first publish them as freely sellable collateral. Covering 8 of 20 leaves 12 outstanding, with allocated cost/fees and unchanged remaining claim identity. Over-cover is rejected or split into a separately consented ordinary buy; no hidden long position.

Restricted short proceeds are Stage A cash restrictions linked to an obligation, not another account-money implementation. B6 evaluates long assets, short replacement liability, borrow fees, pending orders and collateral in one consistent snapshot. Concurrent withdrawal, cover and new short must pass the same serialized commitment check. A favourable mark never makes a return obligation vanish.

Borrow rates may depend on captured utilization under an approved versioned function; no formula or APR is approved here. Persist effective rate intervals and accrual residuals; Stage A monetary precision/posting rules apply. Charge a due interval once through stable schedule identity. Missing rate/history/readiness restricts new borrowing and exposes servicing status, rather than silently using zero fees.

Recall fixes a persisted due instant and quantity. Notify privately and attempt ordinary B4 buy-in only under approved authority after the deadline. Closed/halted/illiquid markets leave visible outstanding units and retry state. A forced buy-in needs funded cash/credit authority and finite sellers; no synthetic shares or assumption of unlimited borrower solvency. Any monetary deficiency remains Stage A debt under approved terms.

Capture live owner consent on the server thread, calculate detached fee/risk plans from immutable snapshots, and revalidate pool/custody/risk versions at storage commit. Equivalent retries return original results; changed payloads conflict. Disconnect does not release consumed inventory. Restart resumes locate expiry, fee windows and recalls in chronological bounded work without duplicate charges or deadline reset.

Bound pool scans by indexed security/eligibility, locates per actor, agreements per batch and recall legs. Private queries expose lender claim versus available inventory, borrower outstanding units, restricted proceeds, captured rates, recall deadline and failed buy-in reasons. Operators may pause new lending, with no implied cancellation of existing obligations.

## Coverage and handoffs

| Origin | Existing behavior | B7 ownership and acceptance | Later handoff |
| --- | --- | --- | --- |
| New B7 scope | No current lending/short implementation | B7-01/02 locates and return obligations conserve 100-unit pool example | B8 action-adjusted quantities |
| B6 shared contract | Unified margin and collateral | B7-03 adds short liabilities once; withdrawal cannot free restricted proceeds | B9 aggregate risk audit |
| F08/F16 reused | Cash and holdings settle through owning services | B7-02/04 cover/fees use B2/B4/Stage A transaction composition | B9 cash/custody reconciliation |
| F19/F23 follow-through | Authenticated authority and recovery | B7-05/06 recall/retry retains original deadlines and unresolved units | B9 stress/recovery evidence |

No historical feature is removed. Dividend-equivalent handling is a required B8 interface, not permission to activate unsupported corporate actions earlier.

## Ordered proposed packages

### B7-01 — Lending consent, inventory and locates

Owner: B7/B2 lending boundary. Requires B6 readiness and approved B2 quantity/claim contract. Define opt-in inventory, eligibility, locate expiry and reservations. Gate: competing 60/50 requests cannot lend more than 100. Exclude sell execution and automatic fees.

### B7-02 — Covered sell and partial return

Owner: B7 orchestration through B2/B4. Requires B7-01 and accepted B4 settlement. Atomically consume filled locate, establish return claim and restrict proceeds; route cover to named obligations. Deliver a gated backend foundation: actual short capability remains unavailable until B7-03 unified risk, B7-04 fee servicing and B7-05 recall readiness are accepted. Gate: 20 filled/40 cancelled and 8-unit partial return reconcile in scoped fixtures. Exclude naked shorts and automatic relending.

### B7-03 — Unified risk and proceeds restrictions

Owner: B6 risk integration. Requires B7-02. Add replacement liabilities, fees and reserve rules without duplicating long assets/overdraft debt. Gate: simultaneous short/withdrawal cannot bypass limits. Exclude a second margin engine.

### B7-04 — Borrow rates and fee servicing

Owner: B7 schedule through Stage A money. Requires B7-03 and approved rates/utilization/reset rules. Retain historical intervals, residuals and stable accrual identities. Gate: restart charges a due interval once and missing policy remains unavailable. Exclude dividends until B8 entitlement contract exists.

### B7-05 — Recalls and buy-ins

Owner: B7 lifecycle. Requires B7-04 and B6/B4 forced-order authority. Persist deadlines, partial returns, retries and residual liabilities. Gate: closed-market recall remains outstanding and later finite fills reduce only actual units. Exclude guaranteed market fills.

### B7-06 — Owner views, controls and B8 handoff

Owner: B7 integration. Requires B7-05. Publish private lending/short statements, pause semantics, bounds and corporate-action obligation interface. Gate: every free/loaned/return quantity has a reconcilable owner. Select focused evidence only for changed behavior; no automatic full-suite run.

## Decisions needed

| Decision | Recommendation — proposed | Alternative and consequence |
| --- | --- | --- |
| Lending supply | Explicit opt-in, no onward lending initially | Rehypothecation requires chain-aware return/priority accounting |
| Locate lifetime | Short persisted real-time expiry with disclosed terms | Unlimited locates can immobilize scarce inventory; exact duration remains open |
| Fee policy | Versioned utilization curve and retained reset intervals | Fixed rates simplify servicing but reduce scarcity response |
| Proceeds | Restrict proceeds until B6 permits release under one risk view | Immediate free withdrawal requires greater collateral and loss policy |
| Recall/buy-in | Defined cure window then ordinary finite orders | Immediate buy-in changes lender/borrower gameplay and outage handling |
| Dividend equivalent | Preserve lender economics through explicit B8 obligation | Omitting it changes economic claims and requires deliberate approval |

Open inputs: lender eligibility, allocation/priority among lenders, borrow-rate values, collateral/proceeds formula, recall windows, fee destination, buy-in cash authority and insolvency handling. Default brokerage funding must not automatically invoke bank overdraft.

## Future evidence and exit

Apply [shared verification scope](../planning/B-SERIES-DESIGN.md#approval-and-delivery-boundaries): prose diff/link review now; future delivery runs required build/client commands once and at most one useful focused test invocation. Keep runServer/main registration/runtime dependencies and preserve worlds/backups. Reuse unchanged B2/B4/B6 evidence.

Exit lists implemented capability, approved terms, outstanding claims and explicit B8/B9 handoffs. Existing loan or trading tests do not establish securities-lending correctness.
