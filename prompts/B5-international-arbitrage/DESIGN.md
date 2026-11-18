# B5 design brief — Cross-listings and international arbitrage

Draft revision 1 — 2026-11-13. Planning only; no policy or implementation approval. Read the [phase scope](README.md) and [shared B-series contract](../planning/B-SERIES-DESIGN.md). All package IDs below are proposed future work and need separate invocation.

## Outcome and dependencies

Allow eligible securities to move between venues and let funded institutions exploit executable price differences through normal orders. A listing is a venue/currency expression of an instrument, not a new supply of shares. A reporting-currency change does not alter its trading currency or rewrite old trades.

B1 supplies stable security/listing IDs, fungibility/conversion groups, ratios and restrictions. B2 supplies owned custody, reservations, cost-basis transport and return obligations. B3 supplies session-aware finite executable liquidity. B4 supplies accepted order/fill/cancel semantics and atomic settlement. Stage A alone supplies cash, FX, fees and immutable monetary history. These are prerequisite contracts, not claims that B implementations exist.

B5 owns conversion eligibility, transfer instructions, net-opportunity plans and institutional orchestration. It does not own balances, share issuance, an FX feed or a matching engine. B8 owns later corporate-action changes to conversion relationships. Third-party integrations remain separate.

## Proposed contract

Persist versioned conversion rules containing source/target security IDs, rational quantity ratio, eligibility, effective interval, fee policy and delay policy. Persist transfer ID, owner, source/target custody, reserved input, resulting output, any remainder claim, captured rule and state. States are `REQUESTED → RESERVED → PENDING → COMPLETED`; rejection/cancellation releases only unconsumed input. A completed conversion cannot be cancelled.

For fungible listings of the same security, transfer venue eligibility without creating another security holding. For distinct convertible securities, consume source units and create target units against an explicit conversion authority and reconciliation account. A reversible mapping is not permission to issue either side. Refuse unknown relationships, unapproved ratios, pledged/loaned/ordered input, unsupported fractions and missing destination eligibility.

Proposed first delivery supports exact integer-ratio conversions only. For a 2:1 source-to-target rule, six free source units produce three target units; five source units are rejected rather than silently discarding one. A later approved fractional-entitlement policy may replace this restriction. Cost basis and provenance travel with the converted claim under B2's approved method.

Reserve input and commit conversion instructions/outcome in one storage transaction. A delayed completion uses a persisted due instant and revalidates only policy conditions explicitly defined as completion-time conditions; it must not silently recalculate the agreed ratio. Completion, custody movements, fees and terminal result compose atomically. Capture actor/session authority on the server thread; calculate opportunities from detached snapshots; revalidate quote, FX, session, rule and inventory revisions on the storage worker.

An opportunity input includes both executable sides, quantity, available depth, currency/FX publication, all fees, expected delay and participant budget. Output distinguishes indicative opportunity from executable instruction. Never compare last-trade prices as if they were executable bids/asks. Missing FX, stale books, halted sessions or unknown transfer costs yield unavailable results.

Illustrative quote: buy 10 units at EUR 10.00 and sell 10 at USD 11.20 with captured EUR→USD 1.10. Gross USD spread is 2.00. If explicit FX/execution/transfer costs total USD 2.50, net is −0.50 and the proposed institutional strategy declines. These are example inputs, not approved fees or a profit guarantee.

Institutional plans reserve finite cash/share/risk budgets and submit B4 orders. Proposed default is inventory-backed independent legs, with explicit partial-fill exposure and bounded unwind policy. A cross-venue atomic all-or-none strategy is a separate choice requiring a multi-book transaction contract; do not imply real-world simultaneity. Price convergence emerges from consumed depth and inventory, never direct price edits.

Retries preserve transfer/strategy/leg identity. If one leg fills before another becomes unavailable, retain the committed fill and residual exposure; do not manufacture an offsetting trade. Disconnect does not unwind accepted work. Restart resumes due transfers and unresolved legs from persisted state, without redrawing opportunities or resetting limits. Unknown commit follows the shared recovery gate.

Bound candidates by indexed conversion group, maximum legs and per-dispatch work; approve numeric caps before execution. Expose private plan reasons, pending transfers, captured versions, remaining budget, failed-leg exposure and reconciliation status. Operator controls may pause new plans; already committed obligations remain recoverable. No personal holdings in routine logs.

## Feature coverage and handoff

| Origin | Existing behavior | B5 ownership and acceptance | Later handoff |
| --- | --- | --- | --- |
| New B5 scope | No current cross-market implementation | B5-01/02 conserve eligible quantities; exact ratio, stale rule and restricted-input refusal | B8 action-adjusted relationships |
| F06 follow-through | Historical/raw currencies survive denomination changes | B5-03 retains listing/FX versions; reporting rebase cannot create a price discrepancy | B9 history audit |
| F08/F16 reused | Stage A money and B2/B4 holdings/execution | B5-04 consumes ordinary cash/share holds and finite depth; no separate postings writer | B9 composite reconciliation |
| F19/F23 follow-through | Server authority and meaningful recovery evidence | B5-05 rejects forged ownership and resumes original legs without duplicate fills | B9 integration evidence |

No historical feature is removed or declared verified by this draft. New conversion fees, delays and strategy rules remain proposed.

## Ordered future packages

### B5-01 — Eligibility and conversion rules

Owner: B5 rules. Requires accepted B1 identity and B2 quantity semantics. Define versioned ratios, fungibility, permissions and effective-time query results. Gate: distinguish same-security listings from distinct convertible securities and reject inconsistent cycles. Exclude custody mutation and autonomous orders.

### B5-02 — Durable cross-market transfer

Owner: B5 transfer orchestration. Requires B5-01 and accepted B2 atomic custody composition. Define reservation, completion/cancellation, due time, fee quote and cost-basis transport. Gate: six-to-three example, restricted-input rejection and same-ID recovery conserve claims. Exclude profitable-opportunity selection.

### B5-03 — Net executable opportunity view

Owner: B5 analytics. Requires B5-02, B3 depth/session snapshots and Stage A FX/fee contracts. Return versioned net-cost explanations with finite quantities and unavailable reasons. Gate: negative-net example and stale/closed venue refusal. Exclude guaranteed profits and price mutation.

### B5-04 — Funded institutional strategy

Owner: B5 orchestration through B4. Requires B5-03 and accepted B4 orders. Persist strategy/leg state, participant budgets, partial-fill exposure and selected unwind policy. Gate: one-leg fill followed by rejection retains exact exposure and original outcomes. Exclude new matching or money engines.

### B5-05 — Controls and bounded acceptance

Owner: B5 operations. Requires B5-04. Define prospective pause/configuration, indexed bounds, private diagnostics and B9 handoff. Select a focused real-service/SQLite scenario for changed recovery behavior where useful; reuse unchanged settlement evidence. Gate: bounded candidates and explainable pending exposure. No automatic broad-suite gate.

## Decisions needed before execution

| Decision | Recommendation — proposed | Alternative and consequence |
| --- | --- | --- |
| Conversion fractions | Exact integer conversions first | Fractional claims require explicit custody precision, rounding destination and later redemption |
| Delay/fees | Versioned explicit per-rule policy, disclosed before reservation | Zero delay/fee is simpler but changes arbitrage economics and needs approval |
| Multi-leg risk | Inventory-backed independent legs with capped exposure | Atomic all-or-none reduces leg risk but requires shared multi-book reservation/commit design |
| Strategy activation | Default off until participant capitalization and limits are approved | Automatic launch can consume scarce liquidity before operators understand exposure |
| Activity attribution | Emit one accepted economic observation per actual eligible fill, through A8 contract | Treating a plan or transfer as commerce risks double-counting; exact attribution requires review |

Open inputs: approved conversion catalog, security quantity precision, FX executable-quote support, fee schedule, session/calendar policy, participant capitalization and risk budgets. Each blocks only its affected package; none is silently defaulted.

## Future verification and exit

Use the [shared scoped verification policy](../planning/B-SERIES-DESIGN.md#approval-and-delivery-boundaries). Planning review only now. Future evidence should address the changed quantity/leg/recovery contract with proportionate focused checks; ordinary runnable deliveries retain the three required build/client commands and at most one useful focused test invocation. Preserve runServer, mod registration/runtime dependencies, worlds and backups.

Exit records approved policies, implemented package evidence, remaining exposure/capability limits and explicit B8/B9 handoffs. A draft or successful old test does not establish execution, economic balancing or performance guarantees.
