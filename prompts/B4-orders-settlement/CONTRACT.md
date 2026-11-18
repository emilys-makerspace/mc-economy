# B4 package contract — Orders, atomic settlement and trading computer

Draft revision1, 2026-11-13. Planning only; none of these packages is selected, implemented or verified. Every package includes this contract, [Stage B shared contract](../planning/B-SHARED.md) and the [decision register](../planning/B-DECISIONS.md). Explicit user requirements/answers remain approved scope; recommendations are unapproved policies. Implement only after separate exact-package selection and prerequisite/decision resolution.

## Scope and dependencies

Refine the [existing brief](DESIGN.md) into bounded packages after B2 custody/funding and B3 finite quote/session contracts. Stage A owns money/FX/fees; B2 owns securities; B3 owns finite market liquidity; B4 coordinates one durable settlement. Approved initial O01–O10 products are cash-funded whole-share protected-market/limit trades, under [order policy](POLICY-REVIEW.md). Corporate trading requires accepted B0 grants and separately accepted B2 corporate brokerage; initial corporate credit remains unavailable. The split below preserves the brief's B4-01 through05 intent and separates its source checkpoint, hardware/assets and final native acceptance.

## Ownership and feature coverage

| Feature | Package owner | Concrete acceptance / later handoff |
| --- | --- | --- |
| F16 whole-share buy/sell | B4-01/02/03 | Finite fills and cash/custody conservation; B5–B8 reuse same path |
| F08 funding; F06 raw currencies | B4-03/05 via B2 | No implicit credit/FX; rebase preserves original fill currency |
| F17 charts | B4-04/05 | B3 at-most14-day initial view, no fabricated prehistory |
| F18 trading search/holdings/funding/UI | B4-05, acceptance B4-07 | Exact review/partial fills/cancel and outcome recovery |
| F19 validation | B4-01/04/05 | Owner/company/menu/reach/version isolation; bounded requests |
| F20 market API | B4-04 | Immutable authenticated reads/intents, explicit capability readiness |
| F21 four-cell computer; F22 art | B4-06 | Preserved rotation/support/anchor cleanup/single drop; licensed resources |
| F23 coverage | B4-07 | Link selected changed-behavior evidence and known limits, no blanket rerun |


Main owns shared interfaces and sequential Gradle/game tasks; delegate only substantial independent work with disjoint files and explicit allowed checks. No automatic delegation/check gate. Use [package queue](EXECUTION.md) in prerequisite order, stopping after each handoff.

## Domain contract and examples

Order state is proposed ACCEPTED/OPEN → PARTIALLY_FILLED → FILLED, or remaining quantity → CANCELLED/EXPIRED; rejected admission is explicit. Canonical order ID is distinct from submission operation, fill, transaction and quote IDs. Persist original quantity, remaining quantity, executed notional, fee accumulator, reservation IDs, source owner, listing/rules/session/policy versions and creation/expiry time. A durable business rejection has an original outcome; transient overload does not consume identity.

Buys reserve bounded cash including approved fee/FX protection; sells reserve eligible free custody. No sell uses pledged/loaned inventory without an accepted product path. Protected market orders cannot consent to an unlimited price. Plans are detached and nonauthoritative: commit compares order, current session/liquidity, reservation and participant revisions. Approved O01–O10 priority is price/time with durable server sequence tie-break; block same-controller self-trades, including player/corporation control. Market IOC has a 1% reviewed price band; limits support IOC/DAY/default and up-to-30-real-day GTC. Eligible player/system matching is enabled with anonymous aggregated public depth. Quotes/candles alone do not execute autonomous trades; B4 owns that activation too.

Every fill transaction consumes finite liquidity and order remainder, changes both counterparties' securities and cash, allocates fees/FX/rounding residuals explicitly, updates holds/lots/fee accumulator and records the original result atomically. A failed leg changes nothing. CumulativeTradingFee policy is Tier 1 0.25% of executed cash notional and Tier 2 zero under current A6 primitives; capture policy/tier per accepted order per reviewed rule and charge only incremental cumulatively rounded fee. Unfilled/cancelled quantity earns no fee. O09 approves exact minor-unit gross notional using compatible whole-unit settlement lots; one identical buyer/seller gross before separately posted fees. Refuse incompatible quantities/routes rather than introduce fill-splitting rounding profits, altered ticks or fractional-cash authority. B4-00/03 must freeze actual compatible lot/unit and existing fee/FX signatures.

Cancellation and expiry serialize with fills through the same transaction boundary: completed fills remain; release only unfilled remainder. An original committed submission/receipt resolves even after quote expiry or disconnect. Cancel/display abandonment is not rollback. Frozen owner histories retain raw listing currency, captured FX/source/fee facts, fill sequence and cursors; reporting rebase changes display projection only. API discovery keeps trading unavailable until atomic settlement and production readiness are accepted.

Example: buy 10, fill4, cancel6. Cash/share/fee changes reflect only 4; only remaining hold releases. If liquidity supplies6 of10, IOC fills6 and cancels4 under approved O02/O06; no synthetic remaining seller. Test chosen partial-fill and cancellation behavior meaningfully during implementation rather than adding a test for each helper.

## Decisions, bounds and verification

The [decision register](../planning/B-DECISIONS.md) controls unresolved policies; finalize only affected choices before implementation. [Stage B shared contract](../planning/B-SHARED.md) supplies current platform, thread/worker/transaction/authority, exact arithmetic, UTC/version, original-operation, overload and recovery requirements. A missing exact source/bound/version is resolved at the source checkpoint; never substitute an invented default. Configuration and new schema/wire/API versions are prospective, validated and recorded with historical compatibility.

Apply [project verification scope](../../.codex/VERIFICATION.md) to every sub-task. Planning uses diff/link review only. Future routine work uses at most one useful focused named-test invocation; no automatic test/full-suite completion gate. Ordinary runnable delivery runs Java 21 `./gradlew build -x test`, `./gradlew help --task runClient`, `./gradlew runClient --dry-run` once, keeping registered main mod, required development runtime dependencies and runClient/runServer. Main owns sequential tasks; preserve player worlds/backups and use disposable fixtures only when necessary. Each package writes its scoped handoff/evidence and stops. No tests/game/build run during drafting.

## Catalog and cross-listing handoff — 2026-11-13

Cross-listing delivery gate: [catalog K07](../B1-market-registries/POLICY-REVIEW.md) requires B5 arbitrage support. Revalidate B1 relationships and B2 venue-available/transfer claims against accepted B5 restrictions before cross-venue use. Until that route authority exists, refuse affected cross-venue delivery rather than silently offer free conversion; independently accepted venue-local trading may remain available. B5 legs consume ordinary finite B4 liquidity and retain exact original fills/exposure.

## Approved calendar handoff — 2026-11-13

[T01–T07](../B1-market-registries/CALENDAR-REVIEW.md) are approved. Fill commit revalidates sourced regular continuous session intervals; opening is inclusive, closing exclusive, and holiday/break/early-close/coverage restrictions invalidate executable quotes. Authorized cancellation/read access may remain available outside sessions; O03/O04 now approve funded DAY/GTC queuing when the next sourced session is known; market/IOC remains open-session-only. Imported official-close seeds do not implement auction matching.

## Approved brokerage handoff — 2026-11-14

[B2 R01–R09](../B2-brokerage-custody/POLICY-REVIEW.md) are approved. Order admission uses explicitly prefunded listing-currency cash, current sponsor/owner authority and separate corporate brokerage scopes; never silently convert another balance or draw credit. Settlement preserves whole-share FIFO, cumulative commissions, captured basis/FX/reporting and conserved lot residuals. Provider-change/closure blockers include live orders/holds/uncertainty. O01–O10 now settle BD05 order and exact-notional policies; source/lot/quote/authority feasibility remains for B4-00/01/02/03. No implementation selected.

## Approved orders — O01–O10, 2026-11-14

[Order policy record](POLICY-REVIEW.md) is normative. Market orders are 1%-protected IOC; limits support DAY default, GTC up to 30 real UTC days and IOC. DAY uses its assigned venue trading date's final regular close, with a break retaining the same date and after-close/weekend/holiday placement targeting the next sourced eligible date. Funded DAY/GTC may queue while closed, reserving the full request; IOC cannot. Unknown calendar coverage is unavailability, not a guessed open session.

Matching is best price then durable acceptance sequence at the resting price, without preferential actor class or client timestamps. Anonymous aggregated player/system books are enabled; block same-controller self-trades, including player/owned-corporation and companies with the same sole owner. Cancel the incoming remainder that would self-match rather than skip priority; preserve earlier legitimate fills. No wash/blocked/submitted/cancelled volume or P05 pressure.

Partial fills are allowed after full request/fee reservation; release only exact remaining claims on IOC/cancel/expiry. Edits are cancel-plus-new-review with new priority, not a silent mutation. Disconnect/menu close does not cancel a standing order. Corporate delegation revocation cancels the unfilled remainder before another fill; previously committed executions remain. Sponsor/fee eligibility invalidation cancels remainder for renewed consent instead of silently repricing.

O09 requires exact shared gross cash notional and compatible whole-unit venue/settlement steps, preserving existing cumulative fees and R07 basis residuals. B4-00 must surface actual instrument conflicts rather than silently alter catalog ticks/units or exclude rows. Session open is inclusive/close exclusive; chronological expiry and revision checks precede affected fills. Cash/custody/liquidity/fees/fill/outcome commit together; original operations resolve before new work. BD09 capacity and BD11 cross-market friction/leg risk remain separate. No implementation selected.

## Approved cross-market handoff — 2026-11-16

B5 [X01–X10](../B5-international-arbitrage/POLICY-REVIEW.md) now settle cross-market policy. Strategy legs use ordinary O01–O10 IOC, claim/quote/session/precision and self-control rules; no multi-book atomic profit promise. Corporate transfer/conversion scopes and prior custody obligations remain distinct from delegated order authority. Preserve actual residual inventory/cash exposure and the separately capped unwind consent. Actual source/strategy feasibility still gates activation.

## Approved margin handoff — 2026-11-16

B6 [G01–G11](../B6-margin-risk/POLICY-REVIEW.md) are approved for separately gated personal margin draws and mandatory liquidation. Accepted B6 must compose actual native-currency lender-funded purchase/debt/pledge/risk within the ordinary fill transaction; cash-only product stays unchanged until capability readiness. Forced-sale servicing mandate permits disclosed quoted FX of actual proceeds, not hidden order-time FX. O02 exact price/lot/liquidity and original-outcome rules remain. Mandatory liquidation has no X08 arbitrage 2% loss-consent ceiling.

## Approved securities-lending handoff — 2026-11-16

B7 [L01–L11](../B7-shorts-lending/POLICY-REVIEW.md) approve separately gated personal short IOC initiation after a backed 60s locate. Fill atomically consumes actual locate units, establishes return/proceeds/risk claims and settles B2 cash/custody/fees; unused IOC quantity releases only unfilled claims. Cover/return must preserve approved origin-lineage and named obligation reduction without creating a free intermediate share. O01–O10 authority/control/price/session/exact-lot rules remain, with capability unavailable before full B7 readiness.
