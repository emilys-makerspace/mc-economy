# B8 design brief — Advanced orders, corporate actions and indices

Draft revision 1 — 2026-11-13. Planning only. Read [phase scope](README.md) and [shared B-series contract](../planning/B-SERIES-DESIGN.md). These proposed packages need approved policies and separate implementation invocation.

## Outcome and ownership

Add advanced order conditions, coherent corporate actions, halts/news and disclosed index analytics without breaking cash, custody, lending or historical meaning. B5–B7 are prerequisite handoffs because a split or dividend affects cross-listing conversion, collateral and return obligations as well as free holdings.

B8 owns action definitions/effective-time orchestration, trigger conditions, halt policy, news/factor inputs and index methodology. B1 owns instrument/listing identities and tick rules; B2 owns quantities/lots/entitlements; B3 owns quotes/history/simulation; B4 owns orders/fills; B5 owns conversion rules; B6/B7 own collateral/return claims; Stage A owns monetary postings/FX/debt. Compose through these owners in the existing journal transaction boundary.

No new real-world constituent, company-news or exchange-calendar claims are made here. Source/license review is a future input. Fictional simulated news must be labeled and deterministically persisted; historical source data is not a live feed.

## Proposed entities and semantics

Persist action ID/type, issuer/security scope, announcement/record/ex/effective/payment instants as applicable, captured policy, source, state, entitlement snapshot/generation and application outcome. Persist advanced-order trigger ID, reference-price kind, threshold/tick revision and derived B4 order ID. Persist halt event/reason/resume conditions, news event/factor versions and index methodology/constituents/weights/base/history revisions.

Action lifecycle: `DRAFT → ANNOUNCED → FROZEN → APPLYING → APPLIED`, with explicit pre-effective cancellation rules. Publish no partially transformed financial state. For large affected sets, prepare a bounded immutable plan/checkpoint and use an approved atomic generation-switch or action gate; do not imply that independent per-account commits are one atomic action. An interrupted plan resumes from its original input manifest.

Proposed trigger basis is an accepted venue last trade, with explicit behavior for sparse/no trades and stale/halted markets. Stop activation commits once and submits one child B4 market/limit order. Triggering is not a guaranteed execution price. A stop-limit can trigger and remain unfilled. Owner cancellation racing activation is serialized against trigger/order state, with no second child on retry.

Dividend entitlements use an approved record/ex-date ordering and immutable ownership/loan claims. Payment is funded from an explicit issuer account; insufficient funds yields a visible unpaid obligation under approved rules, not minted cash. B7 dividend equivalents must distinguish issuer payment from borrower/lender transfers and avoid double payment. FX and original currencies remain captured history.

Illustrative split: a 2-for-1 action turns 10 units with total lot basis 100.00 into 20 units with the same total basis, a 3-unit return obligation into 6 and a sell order for 4 into 8 under the approved adjustment policy. A tick/price transform is separate from money settlement. Do not halve total cost basis or historical raw prices.

Illustrative reverse split: 7 units at 1-for-3 produce two whole units plus a one-third entitlement. Recommended first policy is a separately tracked fractional claim settled by explicit funded cash-in-lieu at a captured valuation; this remains unapproved. Rounding to two without an entitlement destroys value, while issuing three creates value. B2 quantity representation must be settled before implementation.

A merger/delisting plan enumerates successor identities, exchange ratio, cash consideration, pending orders, conversion relationships, pledged assets, open loans/shorts and retained audit references. Recommend cancellation/review of affected open orders rather than automatic repricing until every order type has an approved transform. A delisted instrument may remain a nontradable claim; removal from search cannot delete ownership or debt.

Halts restrict new matching according to venue/security scope, retaining or cancelling orders only under an approved policy. Due interest, fees, action entitlements and recall deadlines remain explicit; a halt is not a global clock pause. Resume uses a captured event and bounded reopening policy, not a random new seed.

News/earnings events influence B3 through versioned factor inputs and effective instants. Persist the chosen event/result before its effects; restart cannot reroll it. Distinguish displayed narrative, fundamentals and executable liquidity. News does not directly mint participant capital or bypass B4 fills.

Index queries disclose exact constituent coverage, weighting, rebalancing, divisor, FX publication, missing-price treatment, base instant and return convention. Recommend an explicitly named in-game subset unless full licensed constituent coverage is available. A price index and total-return index are different series. Historical membership/weights are immutable; do not recalculate old values from today's constituents.

For a simple illustrative price-weighted two-member index with prices 20 and 30 and divisor 0.5, level is 100. A 2-for-1 split in the first member changes prices to 10 and 30; divisor 0.4 preserves level 100 absent market movement. Real methodology remains to approve; this only demonstrates why action-aware divisor history matters.

All client commands carry live server authority; detached calculations return versioned plans for storage revalidation. Original action/trigger/payment IDs and outcomes survive retries/disconnects. Overflow, missing entitlement sources, stale marks and unsupported transforms fail closed for affected actions. Bound affected-row manifests, trigger scans, scheduler batches and index pages; expose private/admin action progress without leaking other owners' portfolios.

## Coverage and handoffs

| Origin | Existing behavior | B8 ownership and acceptance | Handoff |
| --- | --- | --- | --- |
| New B8 scope | No substantive corporate-action/advanced-order implementation | B8-01/02 trigger once; halt/resume consistent with B4 states | B9 order/history audit |
| New B8 scope | No current dividend/action processing | B8-03/04 conserve basis, custody, return claims and funded entitlements | B9 reconciliation/recovery |
| F17 follow-through | Stored/daily historical price facts | B8-05/06 append factors/index series without rewriting raw old prices | B9 retention/history |
| F19/F23 follow-through | Server authority and meaningful evidence | B8-07 interrupted action publishes no mixed generation; restart retains event identity | B9 composite acceptance |

New features are proposed, not silently approved replacements. Exact named-index completeness is unresolved.

## Ordered proposed packages

### B8-01 — Advanced trigger contracts

Owner: B8 conditions through B4. Requires B5–B7 handoffs and accepted B4 state machine. Define stop/stop-limit basis, sparse-market behavior, expiry and cancellation race. Gate: one trigger produces one child; unfilled stop-limit remains explicit. Exclude additional order types until selected.

### B8-02 — Halts and reopening

Owner: B8 venue policy through B3/B4. Requires B8-01 and B1 sessions. Define halt scope, accepted-order handling, resume authority and due-obligation treatment. Gate: no fill during prohibited state and no deadline reset. Exclude unsupported auctions.

### B8-03 — Dividend entitlements and payment

Owner: B8 coordinated Stage A/B2/B7. Requires approved ownership cutoff and B7 claims. Define entitlement freeze, issuer funding, equivalents, FX and unpaid outcomes. Gate: lender/borrower/issuer obligations reconcile and payment replay cannot duplicate cash. Exclude splits.

### B8-04 — Splits, mergers and delistings

Owner: B8 coordinated action plan. Requires B8-03 and every affected owner interface. Define fractions, basis, orders, collateral, conversion/return transforms and atomic publication. Gate: split/reverse-split examples conserve rights; interrupted plan has no mixed visible state. Exclude unapproved action varieties.

### B8-05 — Earnings/news factor inputs

Owner: B8 event definitions through B3. Requires accepted action chronology. Persist deterministic simulated events, source labels and bounded factor application. Gate: restart yields the same chosen event and price inputs. Exclude live feeds and unsourced real news.

### B8-06 — Index methodology and analytics

Owner: B8 analytics. Requires reviewed catalog coverage, B3 history and B8-04 action history. Follow the [approved index methodology](INDEX-METHODOLOGY-REVIEW.md) for membership, weighting, return, reporting and missing data. Verify continuity across actions and rebalances. Exclude claims of full named-index replication without data.

### B8-07 — Action controls and integrated handoff

Owner: B8 integration. Requires B8-01–06. Publish bounded controls/progress/history and B9 invariants. Select only meaningful changed-behavior evidence, reusing upstream checks; no automatic full-suite run. Gate: every action lists its affected claim owners and retained historical semantics.

## Decisions and open inputs

| Decision | Recommendation — proposed | Alternative and consequence |
| --- | --- | --- |
| Trigger basis | Accepted venue last trade, with stale/no-trade refusal | Bid/ask/reference triggers need different manipulation, spread and disclosure rules |
| Fractional actions | Durable fractional entitlement plus funded cash-in-lieu | Fractional custody requires broader precision/settlement support |
| Open orders on actions | Cancel/review until type-specific transforms approved | Automatic transforms preserve intent only with exact price/quantity/tick rules |
| Unfunded dividends | Visible obligation and restricted activation under approved policy | Cancelling or deferring payment changes issuer/lender expectations |
| Indices | Explicit in-game subset methodology first | Full named index requires complete sourced constituents and licensing review |
| Halt timers | Preserve due obligations with explicit servicing outcomes | Pausing deadlines changes debt/lending contracts and must be approved |

Use the [decision register](../planning/B-DECISIONS.md) and the policy records linked from the [B8 contract](CONTRACT.md). Worked examples illustrate the recorded rules. BD09 governs history retention.

## Future evidence and exit

Use [shared scoped verification](../planning/B-SERIES-DESIGN.md#approval-and-delivery-boundaries). Prose review only now. Future runnable deliveries retain required build/client checks once and at most one useful focused test invocation; keep runServer, mod registration/runtime dependencies and saved worlds/backups. Choose action/race/recovery evidence only where changed behavior needs it.

Exit records approved methodologies and action policies, actual package evidence, missing varieties and B9 review obligations. Catalog count alone cannot prove index completeness.
