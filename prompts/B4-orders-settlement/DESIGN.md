# B4 design brief — Orders and atomic execution

Draft revision 1, 2026-11-13. Planning only. Read the [phase scope](README.md), [shared B-series contract](../planning/B-SERIES-DESIGN.md) and current [overdraft contract](../../.codex/architecture/overdrafts.md). No order default, new borrowing permission or execution capability is approved here.

## Intended outcome and authority

B4 delivers whole-share market/limit orders, cancellation, finite matching, atomic cash/security settlement, durable receipts and a usable trading-computer workflow. It replaces immediate whole-order unbounded execution with the separately reviewed B2 custody and B3 liquidity model. Rejected or unfilled quantity never earns execution fees.

B2 owns brokerage/custody/claims/lots; B3 owns quotes, sessions and participant budgets; Stage A owns cash, FX, fee policy and debt; B4 coordinates the single existing database transaction. B5 adds conversion/arbitrage, B6 adds margin, B7 adds shorts and B8 adds advanced orders/actions. Their capabilities remain unavailable until selected and accepted.

Capture actor, world, session, menu and physical interaction proof on the server thread. The storage worker validates current owner/account/order/quote/session revisions before committing. Public APIs accept authenticated grants; neither packet fields nor an operator flag confer spending authority. Recheck the audience when returning private order/portfolio data.

Existing BANK balances may be signed and a primary account may be closed under the current overdraft contract. Do not assume all bank cash is nonnegative or all owners have an open primary. Resolve eligible funding through current Stage A services; brokerage purchases must not implicitly draw an overdraft or enable margin. Report liabilities once in portfolio/wealth views.

## Proposed order and fill model

Persist immutable order ID and canonical intent, owner/brokerage, listing/security, side, whole quantity, order type, limit/protection price, currency, time-in-force, expiry, policy/fee/FX references, accepted sequence, revisions and terminal reason. Distinguish original requested, filled, cancelled/expired and still-open quantities.

Proposed states: ACCEPTED → OPEN → PARTIALLY_FILLED → FILLED, with CANCELLED/EXPIRED for the remainder and REJECTED for a terminal business refusal. Avoid a state named CANCELLED that hides already committed partial fills. Query results include cumulative fill and remainder facts regardless of terminal label.

Market orders need an explicit maximum debit/minimum proceeds or reviewed price protection and time-in-force; they cannot mean unlimited permission to spend through arbitrary depth. Limit orders validate tick/lot rules and keep a bounded lifetime. Exact supported TIF/defaults remain open decisions; reject unsupported values explicitly.

Buy acceptance reserves sufficient Stage A funds for the approved bound, cumulative fees and any quoted FX. Sell acceptance reserves identified free B2 shares/lots. Claims belong to the order, and release/consume never exceeds the remaining identified amount. Missing quote, mark, funding source or session readiness yields an explicit result, not a fallback fixed price.

Persist fills with stable ID, order/counterparty, quantity, tick price, notional, money currency, exact fee increment, captured FX/rounding, quote/claim revisions and transaction/outcome. Use deterministic matching order and accepted sequence; the client clock cannot win priority. Proposed price-time priority and self-trade prevention need approval before eligible player matching activates.

## Settlement, fee and recovery boundary

For each fill, one transaction revalidates order remainder, session/rules, funding/share claims, finite participant inventory and policy revisions; posts buyer/seller cash, B2 custody and lot changes, fee/FX clearing/residuals, quote consumption, order/fill facts and durable outcome. No success acknowledgment precedes commit. No cache or client update is a settlement leg.

Stage A supplies approved trading fee primitives: Tier 1 0.25% of executed cash notional and Tier 2 zero, with the existing cumulative rounding accumulator. Resolve current APIs at implementation. Persist the accumulator per order and charge only the incremental cumulatively rounded total; preserve the captured policy and supplying-bank eligibility. Do not substitute a fresh tier during historical replay.

Exact price × quantity notional rounding, FX reservation buffers and residual destinations need an approved contract distinct from tick precision. Use checked arithmetic; overflow rejects before any leg changes. Stage A rounding applies where it already owns the conversion/fee calculation.

Original-operation lookup precedes quote freshness for authorized retries. A same-ID equivalent retry returns the original order/fill outcome; a changed payload conflicts. A disconnected player can query the original ID. Failed publication cannot refund a committed trade. An uncertain commit gates new replacement work until recovery resolves it.

Cancellation is serialized against fills. It releases only the remaining claims after the last committed fill; it does not reverse completed ownership or fees. Expiry uses persisted server effective time and the same boundary. A request denied for transient admission overload does not consume its operation identity.

## Worked scenarios

An order to buy 10 shares reserves EUR 101 under an illustrative EUR 10 bound and EUR 1 total fee cap. Four fill at EUR 9.90, producing EUR 39.60 executed notional and the approved cumulative fee increment. Cancel then releases the unspent remainder only. The example demonstrates conservation; it does not approve the EUR 1 cap or fee rounding policy.

A buy of 10 meets only 6 available shares. Under a reviewed partial-fill policy it receives 6 and leaves/rejects/cancels 4 according to TIF; no invented counterparty fills the missing 4. Under an all-or-none policy it fills zero. Both policies need explicit choice, not hidden UI behavior.

A closed venue, stale quote, wrong owner, unsupported tick or insufficient shares returns a specific authorized reason and no asset changes. If a fill commits immediately before disconnect, reconnect shows the same fill/receipt and balances. Replaying an expired quote with its original committed operation still returns that committed result.

## User workflow and query contract

The four-cell trading computer provides bounded stock search/selection, listing venue/currency, session and mark age, holdings/free/reserved shares, chart, funding/unfunding and review/submit/cancel controls. Quote review discloses execution bounds, fees/FX, partial-fill possibility and TIF. A visible estimate is labeled separately from executable consent.

Use server-issued session/menu identities and bounded request/snapshot types. Client screens register client-side only. Keep current localization, clipping, accessibility and finite-layout contracts; do not embed worker/SQL implementation details in product flows. Historical order/trade currency stays raw and labeled after a world reporting rebase.

Preserve the inventoried four-cell footprint, rotation/support/anchor cleanup/single-drop behavior and original/licensed art provenance unless an explicit change is reviewed. Asset generation is outside this design task. Funding controls call B2/Stage A services; UI code cannot mutate cash or holdings.

## Feature coverage

| ID | Historical behavior | Proposed owner/replacement | Acceptance and handoff |
| --- | --- | --- | --- |
| F16 | Whole-share immediate buy/sell | B4-01/02/03 finite orders/fills/settlement | Partial fill/cancel, no oversell, no duplicated fill; B5–B8 extend shared execution. |
| F17 | Chart up to 14 days | B4-04 consumes B3 typed history | No invented prehistory; carry-forward/source/currency visible; B9 retention. |
| F18 | Search, selection, holdings, buy/sell/funding | B4-05 usable trading UI | Stale/busy state, reviewed bounds and original outcome recovery; B9 final polish. |
| F19 | Bounded menu-scoped validation | B4-01/04/05 authenticated requests | Forged owner/menu/reach and oversized/stale payloads rejected. |
| F20 | Market API/admin seam | B4-04 grant-bound query/intent API | No raw SQL/spending bypass; bounded owner and audited operator reads. |
| F21 | Four-cell trading computer | B4-05 registered physical access | Support, cleanup, rotation and one-drop preservation; B9 packaging. |
| F22 | Original computer artwork/localization | B4-05 provenance-preserving resources | Resource/license inventory and native layout witnesses; B9 resource audit. |
| F06/F08 | Currency labels and trading funding | B4-03/05 Stage A/B2 composition | Rebase does not rewrite raw trades; no implicit overdraft funding. |

## Proposed ordered packages

### B4-01 — Order intents and reservations

Requires accepted B2 claim/funding seam and B3 quote/rule types plus approved TIF/protection policy. Order owner defines durable intents, states, admission, rejection and buy/sell reservations. Exclude live fills. Gate: duplicate/conflicting intents, ownership, overflow and insufficient claims have conserved outcomes.

### B4-02 — Matching and cancellation state machine

Requires B4-01, B3 finite liquidity/session readiness and approved priority/self-trade rules. Execution owner defines deterministic fill plans, partial fills, cancel/expiry races and bounded work. Plans cannot directly move assets. Exclude margin/shorts/advanced orders. Gate: no planned quantity exceeds order/quote remainder; shared settlement rejects stale plans.

### B4-03 — Atomic settlement and fees

Requires B4-02, B2 transaction participants and current Stage A fee/FX interfaces. Settlement owner composes all financial/custody/liquidity legs and original outcome atomically, including cumulative fee state. Exclude independent market money stores. Gate: failed leg changes nothing; fill+cancel, retry and restart preserve cash/share/fee totals. Actual execution remains unavailable until this gate is accepted.

### B4-04 — Reports, history and public integration

Requires B4-03. Query/API owner delivers bounded owner order/fill/receipt history, original-outcome lookup, typed quote/chart reads and scoped audited administration. Exclude remote authority or public storage callbacks. Gate: paging/current audience checks prevent cross-owner disclosure and historical values retain original source/currency.

### B4-05 — Trading computer and native workflow

Requires B4-04 and approved resource/interaction details. UI/hardware owner supplies the functional four-cell computer and search/chart/holdings/funding/order/cancel flow with paired packet versions. Exclude separate integrations and B5+ product controls. Gate: usable authenticated native workflow, bounded layout/localization evidence and no fixture resources in the deliverable.

## Decisions and exit evidence

Proposed recommendations: price-time priority; self-trades rejected without fictitious volume; protected market orders with explicit partial-fill TIF; captured per-order fee policy; no automatic FX or borrowing without reviewed consent. Alternatives include batch auctions, all-or-none only, dynamic fee tiers or automatic conversion, each changing execution or consent and needing review.

Open inputs: exact TIF/expiry limits, quote protection, rounding/residuals, supported currencies, eligible player matching and privacy, cancellation ordering at session boundaries, native screen design, retained footprint/resource identifiers and current atomic integration signatures. The existing banking facade's trading rejection must remain until accepted composition explicitly enables it.

Future evidence should target meaningful order/race/settlement behavior with existing real-service/SQLite fixtures, plus native UI or recovery witnesses only where needed. Follow [verification scope](../../.codex/VERIFICATION.md): no automatic full-suite gate, ordinary runnable checks once and at most one focused test invocation useful. This draft runs no Gradle/test/game checks. B4 exits as a usable scoped Stock Market Alpha only after separate implementation and accepted prerequisites; this document claims neither execution nor release readiness.
