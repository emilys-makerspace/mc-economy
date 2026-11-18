# B3 design brief — Prices and finite liquidity

Draft revision 1, 2026-11-13. Planning only. Read the [phase scope](README.md) and [shared B-series contract](../planning/B-SERIES-DESIGN.md). No cadence, capitalization, formula or performance guarantee is approved by this draft.

## Intended outcome and boundaries

B3 supplies autonomous, deterministic evolution across the reviewed listing universe, executable finite liquidity snapshots, session state and honest price/history data. It preserves price administration and daily chart semantics while replacing static unlimited prices with an explicit participant model. Historical source prices are seeds, not a runtime market feed.

A8 supplies accepted economic observations/publications and chronological scheduling. B1 supplies instruments, ticks, currencies and calendars. B2 supplies participant custody/funding and reservation contracts. B4 supplies shared execution/settlement; B3 may publish liquidity and evolve factors before B4 but must keep actual autonomous trades gated until the shared transaction composition is available. It cannot write independent cash or share balances.

Fixed preservation requirements include F17 administration/daily history, no invented prehistory, an initial chart view of at most 14 days, explicit carry-forward and seed provenance. All target listings evolve without requiring an open menu or player action. Tick precision is separate from money settlement precision.

## Proposed model and records

Persist a model-policy revision, deterministic seed/stream state, simulation checkpoint and per-window selected inputs. Inputs include captured A8 effective economic versions, company/sector factors, B1 release and prior factor state. A detached CPU calculation returns a bounded plan and input hashes; the storage worker verifies versions and publishes one complete committed generation.

Recommend a common economic factor plus sector factor and bounded idiosyncratic company factor. Map company/security fundamentals to listing-local reference marks using retained FX and approved venue basis/spread rules. Correlation weights, shock bounds and mean reversion remain policy decisions; no formula here establishes financial realism.

Persist participant identity, strategy-policy revision, eligible listings, Stage A funded accounts and B2 custodied inventory references. Track committed exposure and quotes/claims, never a cached independent balance sheet. Funding comes from explicit reviewed issuance/capitalization or transfers, not per-tick replenishment or bank-credit bypass.

A quote contains listing, side, tick price, available quantity, participant, session, generation, expiry, input revisions and consumption identity. Quote availability is bounded by actual cash/custody, outstanding claims and risk limits. Cancel/replace/consume transitions are journaled with the eventual execution. A displayed reference mark is not necessarily an executable bid/ask.

Persist venue session intervals, current status, next transition and calendar revision. Proposed statuses are CLOSED, PREOPEN, OPEN, HALTED and CATCHING_UP. B8 owns advanced halt policy; until then unsupported transitions remain unavailable rather than invented. A closed venue can display last mark/time with CLOSED status, never a fresh executable quote.

Persist trade-derived OHLCV separately from model/reference marks. A no-trade interval has zero volume; carry-forward is visibly flagged and cannot manufacture trades or high/low observations. Record original listing currency, mark kind, effective time, source and FX projection. Daily chart aggregation uses explicit venue-day boundaries, not a player's time zone.

## Scheduling, administration and bounded work

Use persisted chronological effective windows. A window is selected once; retries/restart use the same source versions and random stream position. Menu refreshes read a published snapshot and never advance simulation. Offline catch-up follows existing gradual work limits and gates affected execution until required state is current.

Recommend splitting independently managed scheduling/configuration, diagnostics and foundation acceptance where the selected package introduces a new persistent schedule. Exact package size can be revisited after contract review; completing a subpackage does not itself trigger tests.

Operators may propose prospective model/seed corrections with explicit reason, authority and reviewed effective boundary. Retain original prices and append correction facts. A correction must say whether it changes only reference marks or prospective quotes; it cannot retroactively reprice settled trades. Retrying the same correction returns its original result.

Use bounded listing batches, participant counts, quote levels, history pages and detached-plan sizes. Set numerical budgets before implementation and measure the actual full universe before claiming capacity. Overload reports lag/readiness and throttles new work; it cannot skip time, reroll a window or silently truncate offline history.

## Concrete scenarios

A participant has 100 free shares and quotes 60 at EUR 10.00. A fill of 25 consumes that exact quote claim, leaving 35 quoted and 40 otherwise free. The next buyer cannot consume the original 60 again. Replenishment requires a new revision and sufficient remaining custody.

If a venue closes at an effective boundary while an order arrives, serialized session/quote validation determines whether the fill commits before closure. A delayed client timestamp does not reopen the session. Original committed outcomes remain discoverable after quote expiry.

After a restart midway through a selected window, recovery either finds its committed generation or resumes that exact plan. A favorable alternate random path is never selected. If a mark exists on day 1 and no trade occurs on day 2, a carry-forward display is labeled; day-2 trading volume remains zero.

## Feature coverage

| ID | Historical behavior | Proposed owner/replacement | Acceptance and handoff |
| --- | --- | --- | --- |
| F15 | Catalog historical seed consumption | B3-01 captures B1 seed availability/provenance | Missing seed remains unavailable; no live-feed claim. B4 displays mark kind/time. |
| F17 | Explicit stored-price update API | B3-04 audited prospective administration | Replayed correction applies once and settled history is unchanged. B9 operator documentation. |
| F17 | Daily records, 14-day chart, carry-forward | B3-04 history/aggregation | No invented prehistory/volume; B4 chart uses the same typed data. |
| F17 | No autonomous prices | B3-01/02/03 bounded factors, sessions and finite quotes | Restart determinism and finite consumption; B4 executes, B9 benchmarks/retains history. |

## Proposed ordered packages

### B3-01 — Factor and participant contracts

Requires B1 release/rules, accepted A8 effective-time readers and B2 custody seam. Simulation owner defines deterministic factors, input selection, participant balance sheets, seed availability and quote types. Exclude actual trades and fresh funding policies. Gate: every mark/quote explains source/version, every participant has an explicit funding/inventory origin, and no cash/share writer duplicates Stage A/B2.

### B3-02-01 — Scheduling and configuration foundation

Requires B3-01 and approved cadence/model/calendar policy. Simulation owner defines persisted windows/checkpoints, bounded catch-up and prospective configuration activation through the existing dispatcher. Exclude player execution. Gate: same selected window cannot reroll, unsupported calendars gate affected work and limits are reviewable.

### B3-02-02 — Diagnostics and readiness

Requires B3-02-01. Operations owner defines lag, unavailable seed/FX, failed plan, participant exhaustion, backlog and publication status with bounded audited inspection. Exclude raw private financial dumps. Gate: selected plan, committed generation and executable readiness are distinguishable; an empty book is not reported as infrastructure failure.

### B3-02-03 — Foundation acceptance and downstream readiness

Requires B3-02-02. Simulation owner records scoped deterministic/offline evidence and remaining qualifications, using existing applicable witnesses. Exclude automatic full-suite/load gates. Gate: explicit readiness contract for B4 and known budget gaps; no activation based only on a selected-but-uncommitted plan.

### B3-03 — Session and finite liquidity publication

Requires B3-02-03, approved participant funding and accepted B2 claims. Simulation owner publishes bounded bid/ask generations under calendar, custody/cash and risk constraints. Agree consume/cancel revision semantics with B4. Autonomous matching stays gated until B4 settlement exists. Gate: competing consumption, close/expiry races and exhausted inventory produce conserved outcomes.

### B3-04 — History, administration and integration

Requires B3-03 and approved retention/mark rules. History owner supplies immutable daily/OHLCV pages, labeled carry-forward, prospective operator corrections and B4 mark/quote APIs. Exclude B8 news/corporate actions and unapproved historical imports. Gate: restart preserves history, currencies and sources; no-trade volume is zero and the initial chart respects its 14-day scope.

## Decisions and open inputs

| Decision | Recommendation (proposed) | Alternative and consequence |
| --- | --- | --- |
| Sessions | Use B1 versioned venue-local calendars and explicit closed status | Game-scaled access improves play availability but changes international overlap. |
| Liquidity funding | Finite reviewed institutional capitalization and issuer inventory | Replenishing balances per tick makes conservation meaningless. |
| Factor policy | Shared/sector/company components with retained versions | Independent random walks are simpler but weaken correlation and arbitrage coherence. |
| Mark authority | Distinguish reference, last trade and executable bid/ask | One ambiguous price misleads valuation, charts and order consent. |
| Retention | Retain authoritative facts; bounded hot history and explicit archival contract | Destructive retention needs approved replay/audit consequences before use. |

Open: numeric cadence and factor coefficients, exact participant capitalization/distribution, budget/hardware target, spread/impact/volume calibration, policy for unavailable FX, B4 execution activation sequence, retention horizon and accepted marks for B2/B6. B9 balancing cannot silently approve these inputs.

## Future evidence and exit

Select focused deterministic replay, finite consumption or session-boundary cases for changed behavior; add a full-universe benchmark only when its contract and budget are approved. Reuse accepted A8 time/FX evidence. Follow [verification scope](../../.codex/VERIFICATION.md); this draft uses prose review only and introduces no automatic full-suite gate.

Exit publishes explicit mark/liquidity/readiness contracts and limitations. It does not claim every target listing, a seven-day catch-up target or any live-client load is verified by the design.
