# B3 — Prices, liquidity and market simulation — package contract

Draft revision1, 2026-11-13. Planning only; no package is selected, implemented or verified. Read the [original scope](README.md), [design brief](DESIGN.md), [shared B contract](../planning/B-SHARED.md) and [decision register](../planning/B-DECISIONS.md). The queue below refines the brief's proposed package boundaries; approvals explicitly recorded in the brief remain intact.

## Ownership and domain contract

B3 owns deterministic factors, session readiness, finite participant quote generations and retained mark/history publication. A8 supplies effective economic/FX sources, B1 supplies reviewed seeds/rules, B2-04 supplies funded cash/custody participants and B4 owns fills. Actual autonomous matching remains unavailable until B4 accepts atomic execution; B3 may calculate marks and publish finite quotes without a temporary independent money/share writer.

Persist model/policy/registry versions, UTC windows, selected inputs/hashes, deterministic seed/stream position and completed generation. Detached CPU plans are immutable and bounded; the worker compares input revisions and atomically publishes a complete generation. Restart resumes the selected window, never rerolls it. A menu refresh is a read. Offline chronological work remains bounded without dropping elapsed time; affected execution is gated while catching up.

Proposed model uses common economic, sector and bounded company factors; numeric factor/volatility/impact and financial-mark policies are approved under [P01–P07](PRICE-DYNAMICS-REVIEW.md); B3-01 must freeze the exact conforming formulas/source interfaces. [M01–M08](LIQUIDITY-REVIEW.md) funding/liquidity/cadence/risk policies are approved, including the user’s100× cash-only amendment. Quote identity includes listing, side, exact tick price, quantity, participant, session/generation/expiry, cash/custody claim and revision. Fair/reference mark, last trade and executable bid/ask are distinct. Cash/share-backed quote capacity is finite and conserved through B4 consumption. No per-tick money/inventory replenishment.

History retains raw listing currency, source/kind/effective time, captured FX and correction provenance. Model marks are not trades. No-trade intervals have zero volume; visible carry-forward cannot invent prehistory or OHLC observations. Initial chart view remains at most 14 days. Venue-day aggregation boundaries and mark freshness must be explicit. Closed/unsupported venues never publish apparently current executable quotes.

## Coverage ledger

All rows are draft contracts, implementation not started, verification not run. Historical behavior comes from the [feature inventory](../planning/01-current-feature-inventory.md); current accepted Stage A contracts supersede old implementation assumptions.

| Feature | Historical behavior | Package owner / replacement | Acceptance and downstream handoff |
| --- | --- | --- | --- |
| F15 seed consumption | Historical catalog source prices | B3-01 captures B1 admissible source identities | No live-feed or invented seed; B4 shows kind/time/currency. |
| F17 stored-price administration | Explicit update API | B3-04 prospective audited correction | Replay applies once; original settled trade/history remains. |
| F17 daily history/chart | Daily record, up to 14-day view, carry-forward | B3-04 immutable OHLCV/daily pages | No prehistory/false volume; B4 chart consumes typed source. |
| F17 autonomous evolution | No substantive old simulation | B3-01/02 foundation and B3-03 finite quotes | Deterministic recovery and conservation; B4 executes and B9 measures. |
| F06 raw/report currency | Historical labels across rebase | B3-01/04 captured currencies/FX | Rebase preserves raw marks/trades; B4 displays accurately. |

## Decision gates

BD02/[T01–T07](../B1-market-registries/CALENDAR-REVIEW.md) calendar policy is approved. Implement sourced regular continuous intervals including holidays/early closes/breaks; resolve venue zones/DST to retained UTC intervals. Gaps gate affected execution; chronological catch-up preserves versions and creates no trades during closed intervals. Actual coverage/source feasibility remains preparation work. BD03 funding/liquidity M01–M08 are approved; P01–P07 price dynamics/financial-mark policies are also approved; remaining gates are exact conforming formulas/source-interface feasibility and prerequisite evidence. Source feasibility and actual funding/issuance manifests remain implementation preparation, not open routine preferences. BD09 gates concrete batch/payload/history/workload budgets and retention. A suggested formula or numeric example does not approve economic behavior. B2-05 mark qualification follows B3-04, avoiding a cycle with B2-04 foundation.

## Package boundaries and delivery

[EXECUTION.md](EXECUTION.md) is the authoritative selectable draft queue. Earlier proposed brief headings are refined by this queue: existing IDs retain their domain; additional acceptance/query/activation packages split later boundaries. No approval, implementation or verification follows from this refinement.

All packages inherit shared authentication, worker/journal, exact arithmetic, original-outcome, effective-time, bounded-read/configuration and recovery semantics. Reject stale, overflow, absent authority and unsupported sources explicitly; do not fabricate fallback quantities/prices. Each selected package must finalize its affected schema/state/DTO/configuration bounds before code and preserve historical decoders. No public SQL or alternate money ledger.

Planning uses diff/link review only; future packages follow [.codex/VERIFICATION.md](../../.codex/VERIFICATION.md), including at most one useful focused test invocation and the three ordinary runnable delivery commands once. No automatic full-suite/test-per-package gate. Keep runClient/runServer, registered main source set and required development runtime dependencies; one owner runs Gradle/game checks sequentially. Each package records its handoff/evidence and stops before dependents.

## Catalog and cross-listing handoff — 2026-11-13

Cross-listing handoff: retain common underlying/issuer factor identity with distinct listing quote/liquidity/session/FX provenance for [B5 arbitrage](../B5-international-arbitrage/CONTRACT.md). Seed baseline is the approved 2026-11-13 local regular close, with explicit actual-date carry-forward where needed; differently timed closes are not simultaneous executable opportunities. B5 convergence comes from finite B4 fills, never unjournaled price-equality nudges.

## Approved funding and liquidity — M01–M08

[Policy record](LIQUIDITY-REVIEW.md) supplies the accepted parameters. Initial cash is **CAD 2.8 trillion-equivalent**, one journaled issuance independent of the current 2.8T server-major bank/institution seed, allocated 60%/30%/10%: CAD 1.68T makers, CAD 840B long-only funds and CAD 280B arbitrage. Two makers per 18 approved venues plus four investment funds and two arbitrage funds gives 42 system participants. Replay must not seed twice.

The separate initial game-share reference-value target remains up to **CAD 28B**, allocated by distinct eligible security and issued once per underlying, with 60% of units to makers/40% to long-only funds. Cross-listings do not multiply supply. Factor/reference cadence is 60 real seconds; executable quote refresh is 5 seconds during approved intervals. Base total spread 0.50%, stress widening to 1.50%, midpoint skew at most 0.25%, three per-maker/side CAD 5k/10k/20k levels at 0.25%/0.50%/1.00% offsets. Actual cash/custody/claims limit quoted depth; no automatic replenishment.

Initial risk allows no leverage/naked shorts, preserves 10% of original funded cash as a floor and caps new single-security exposure at 5% of original funded capital across all listings/claims. Investment/arbitrage funds submit at most four ordinary B4 intents per 60s window, subject to product/session/risk readiness; no guaranteed volume or profit. Only cash amounts and their derived percentage limits scale100×; share target, quote caps, participant count, cadence and other policies remain as approved. Numeric price dynamics and financial-mark policies are approved under P01–P07. Exact formulas/source feasibility remain for B3-01; BD09 measured budgets and BD11 leg-risk/friction settings remain separately open. No implementation selected.

## Current institution funding baseline correction

The user clarified that new bank/institution seed funding is already **2.8 trillion server-currency major units**, confirmed by `InstitutionCapital.TOTAL_MAJOR = 2_800_000_000_000L` and the [current institution contract](../../.codex/architecture/institutions.md). Allocation is 18×40B Tier 1/hybrid, 5×400B specialists, 40B Ironbridge and 40B Waystone. Legacy 28B manifests retain their captured total without automatic top-up/reseeding.

Earlier references to a current bank 28B seed were stale baseline descriptions. The separately approved B3 market cash pool remains **CAD 2.8 trillion-equivalent**, allocated CAD 1.68T/840B/280B. This correction applies no additional 100× multiplier, combines no funding manifests and changes no source code, player worlds or existing capital.

## Approved moderately active price dynamics — P01–P07

[Policy record](PRICE-DYNAMICS-REVIEW.md) is normative. Baseline volatility targets 2% standard deviation per 24 real UTC hours at 60-second windows, with ordinary one-minute model moves bounded ±0.25%. Normal innovation variance shares are 20% common economic/30% sector/50% company, not raw linear weights. No guaranteed upward drift or reversion to the original seed. Directional macro contribution is bounded ±0.25% per 24h; temporary sentiment decays with a six-hour half-life.

Committed qualifying net trade pressure may temporarily adjust quote midpoint by at most ±0.25%, decaying with a 30-minute half-life, separately from approved M07 inventory skew; actual orders first consume finite depth. No wash/submitted/cancelled-order pressure or permanent wealth revaluation from that quote adjustment. Initial wealth/portfolio marks use independent fundamental/model references, excluding quote pressure/inventory skew and isolated last-trade manipulation, published at 60-second cadence with the existing 1,200-second securities-mark age ceiling. Closed-session model marks remain clearly non-executable; last trade/session close retain their original timestamps. B6/B7 collateral/short-risk policy remains separate.

B8 owns larger scripted news/action events and explicit exemptions from normal bounds. B3-01 must freeze deterministic shock, economic loading, sentiment decomposition, committed-flow imbalance and combined tick/quote formulas that obey these accepted policies. Source feasibility, actual measured calibration and exact algorithms are not claimed by approval; return only materially different rules or demonstrated policy gaps for decision. No implementation selected.

## Order policy handoff — 2026-11-14

B4 [O01–O10](../B4-orders-settlement/POLICY-REVIEW.md) are approved. Unchanged economic quotes retain priority across technical refresh; changed price/increased size obtains replacement priority. Finite quote consumption follows exact compatible settlement lots and same-controller self-trade prevention, so wash/blocked/submitted work supplies no volume or P05 pressure. Quote/session source feasibility remains explicit.

## Approved cross-market handoff — 2026-11-16

B5 [X01–X10](../B5-international-arbitrage/POLICY-REVIEW.md) are approved, constrained by existing M06/M08 funding/exposure/intent limits. No arbitrage depth or pool top-up is implied. X10 requires prospective versioned activity separation: securities notional/custody transfers are not goods/borrowing, automated institutional market FX is not rate-driving customer flow, and actual customer FX retains existing once-only semantics. Pin actual adapter/manifest/source feasibility before code; retain historical facts.

## Approved margin handoff — 2026-11-16

B6 [G01–G11](../B6-margin-risk/POLICY-REVIEW.md) use fresh P06 independent financial marks/FX with explicit FX/concentration haircuts and unavailable-data restrictions. Executable liquidation proceeds still come only from actual B4 depth, not a model mark. M06 institutional no-leverage policy and all funding manifests remain unchanged; B6 approval is personal-only.
