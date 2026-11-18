# Approved fictional market news calibration

The user approved these B8-05 launch policies on 2026-11-17. Apply each event once per affected underlying on master UTC and publish the headline with its first pricing effect. Venue sessions govern execution. See [the fictional news chronology](B8-05.md#approved-fictional-news-chronology--2026-11-16).

| Event | Approved frequency | Initial effect on the reference price | Duration |
| --- | --- | --- | --- |
| Simulated earnings | Once per curated issuer per seven real days, staggered across issuers | Routine surprises up to ±2%. At most one of any ten consecutive releases per issuer may use the larger band, with a hard ±4% maximum. | Half as a lasting change in the fundamental level. Half as sentiment with a six-hour half-life. |
| Other issuer headlines | At most one per curated issuer per 14 real days | Up to ±1% for that issuer | Temporary sentiment with a six-hour half-life |
| Sector headlines | At most one per world per real day, targeting one sector | Up to ±1.5% for that sector's affected issuers | Temporary sentiment with a six-hour half-life |
| Broad economy headlines | At most one per world per seven real days | Up to ±0.75% for affected curated issuers | Temporary sentiment with a six-hour half-life |

Use deterministic UTC scheduling and event selection. Stagger earnings releases on the existing 60-second reference windows. Generators can pick muted or neutral releases and have no built-in preference for bullish outcomes. Persist selected times, outcomes and targets so online execution and offline catch-up agree. Count earnings by issuer and apply the event once within each affected underlying factor group. A cross-listed share inherits the same event input across its venues.

The first event effect can exceed the ordinary ±0.25% one-minute move limit. Cap combined fresh news shocks for each underlying at 5% of its pre-event reference per model window. For simultaneous events that exceed that budget, scale their gross absolute contributions proportionally and record the effective contributions. This is a cap on fresh event input. Ordinary price movement, existing sentiment decay and actual execution prices follow their own rules. Calibrate the permanent and decaying portions so they compose to the approved initial effect and earn no repeated jump on later ticks. Preserve ordinary trade-flow effects separately.

For example, a +2% earnings surprise sets a CAD 100 reference to CAD 102 before other market effects. After six hours, half the temporary portion remains, leaving the reference around CAD 101.50 if everything else is flat. It eventually settles near CAD 101 from the lasting portion of that surprise. These are illustrative projections.

Fictional price effects target the curated simulation. A player-company announcement comes from a recorded corporate action and can reflect the action's approved mechanics. A free-text post carries no engine-assigned price effect. For fictional broad news, apply the reference-price effect through B3 and exclude the scheduled headline itself from A8 economic activity counters. Actual settled trades, FX and authorised corporate actions retain their existing attribution through their source services.

Publish the event with the new reference and invalidate the prior affected quotes at the same effective boundary. Each venue then publishes its own book and executable quotes using current depth. While a venue is closed, the event can update its model reference without a fill. Stops and volatility halts respond to eligible real fills under their existing rules. A model move alone cannot trigger either.

Before implementation, pin the deterministic distribution, exact effect composition and cap arithmetic, event scheduling and persistence bounds, and same-window publication order. See [the B8-05 package](B8-05.md), [approved price dynamics](../B3-prices-liquidity/PRICE-DYNAMICS-REVIEW.md) and [approved stop and halt rules](STOPS-AND-HALTS-REVIEW.md).
