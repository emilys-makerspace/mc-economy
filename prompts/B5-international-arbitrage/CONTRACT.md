# B5 phase package contract — draft revision 1

Prepared 2026-11-13 for the requested package drafting. Planning only; no implementation/policy approval or verification is inferred. Read the [original design brief](DESIGN.md), [scope](README.md), [shared B contract](../planning/B-SHARED.md) and [decisions](../planning/B-DECISIONS.md). This contract and the [queue](EXECUTION.md) supplement DESIGN with individually selectable package boundaries. Shared current authority/verification rules supersede historical broader checklists, while recorded user approvals remain intact.

## Scope, ownership and dependencies

Cross-listings and international arbitrage. Inputs: Accepted B4 execution/settlement, B1 instrument relationships, B2 custody, B3 finite books and Stage A FX. Reuse the existing journal, bounded worker and dispatcher. Stage A owns cash/FX/credit; B2 owns securities/return claims; B4 owns fill coordination. Each package names its narrow domain owner; the main implementation agent owns shared integration and sequential Gradle/game checks. No parallel banking database or direct balance/holding edits.

The first package is a source/document feasibility checkpoint. It pins actual signatures, record limits, codec/version allocation and permissions; it does not manufacture runtime readiness. Later packages consume immutable versioned snapshots and recheck the current authoritative owner/source revisions at commit. Large actions cannot exceed existing record/transaction bounds: use an approved staged immutable plan with an affected-product gate or atomic generation publication, preserving original inputs and outcomes.

Upstream navigation: [B1 registry queue](../B1-market-registries/EXECUTION.md), [B2 custody queue](../B2-brokerage-custody/EXECUTION.md), [B3 liquidity queue](../B3-prices-liquidity/EXECUTION.md), [B4 execution queue](../B4-orders-settlement/EXECUTION.md). Only scoped prerequisite evidence applies; these links authorize no execution.

## Coverage and downstream ownership

| Origin | Prior behavior | Package owner | Acceptance | Handoff |
| --- | --- | --- | --- | --- |
| New cross-market scope | No prior cross-market implementation | B5-01/02 | Same-security supply and exact conversions conserve quantities | B8 relationship transforms |
| F06 | Raw/reporting currency history preserved | B5-03 | Reporting rebase changes no executable listing/FX history | B9 history audit |
| F08/F16 | Money/custody/execution services reused | B5-04 | Finite legs consume actual cash/shares/depth; no independent writer | B9 reconciliation |
| F19/F23 | Server authority and scoped recovery coverage | B5-00/05 | Original legs survive retries with explicit exposure | B9 evidence ledger |

All listed new financial behaviors are proposals until their decisions are approved and implementation is explicitly selected. Existing tests or old code do not prove new guarantees; accepted unchanged services/evidence should be reused. No historical F-ID is silently removed.

## Required semantics

The detailed state/transaction/time examples in DESIGN remain design targets and are assigned to the package files in the queue. The shared contract supplies request identity, owner grants, revocation, worker/server/publication boundaries, checked precision, original-outcome recovery, bounded configuration/query/diagnostic rules and immutable history. Every runtime mutation needs its entity/policy/source revisions, permissions, transition, durable outcome and recovery position specified before edits. Money/share/risk/fee/participant effects must compose through their authoritative services in one financial commit; no assumed rollback after publication failure.

Player corporations from B0 are single-owner organizations with appointed managers and account/payment/transfer scope initially. **This phase must not enable corporate borrowing, margin, short exposure, public listing or share issuance by implication.** Corporate accounts and personal credit remain separate owners; no implicit shareholder/owner guarantee or debt transfer. Any later corporate financial product needs separately selected terms, authority and liability treatment.

Effective UTC history, chronological catch-up and deterministic original identities govern offline work. Missing marks, FX, custody authority or servicing readiness makes the affected product unavailable/restricted, never zero-valued or silently profitable. Pauses prevent new exposure without cancelling committed obligations. Queries are bounded/private; operators have audited scoped inspection, not implied owner spending authority. Configuration values apply prospectively after validation.

## Approved cross-listing requirement — 2026-11-13

The user explicitly requires arbitrage to handle catalog cross-listings. Consume the complete B1 relationship manifest under [approved catalog K07](../B1-market-registries/POLICY-REVIEW.md), and give every pair an eligible route or explicit restriction/unsupported status. Same-security custody is shared underlying supply, not two issuer inventories; separately convertible classes need sourced exact ratios and current rights. Unrelated/nonfungible classes are not a conversion-arbitrage pair.

B5-03 compares executable ask/bid depth after actual Stage A FX, fees/spreads, quantities, conversion ratios, session and transfer restrictions; historical seed closes from different local times are not simultaneous tradeable quotes. B5-04 exploits actual net-positive routes only through funded finite ordinary B4 orders and B2 transfer claims; no direct convergence/price rewrite or guaranteed second leg. Preserve remaining inventory and cash/FX exposure after partial fills, closure or interruption. BD11/[X01–X10](POLICY-REVIEW.md) fees/delays/budgets/leg-risk policies are approved; actual route/source/fee/manifest feasibility and readiness still gate activation. B4 must enforce required B5 eligibility before cross-venue delivery/use, retaining venue-local operations where separately ready.

B5-05 acceptance must trace one eligible pair end to end: two listings/one conserved underlying, exact FX/fees, bounded buy/transfer/sell, a cost-negative non-opportunity, an unavailable/nonfungible route, a closed/partial second leg and original-outcome recovery. Select proportionate evidence under project policy; this is a behavior contract, not an instruction to run every test category.

## Unresolved policy gates

BD11/[X01–X10](POLICY-REVIEW.md) are approved, settling initial fees/delays, exact ratios, leg risk, budgets, activation and economic attribution. BD02–BD05 approvals remain in force. Freeze actual conforming source, custody, FX/fee, strategy and prospective activity-manifest contracts before enabling the affected package; return only actual policy conflicts for a specific decision.

Read DESIGN's alternatives and numerical examples as proposals, not approved settings. Record the chosen policy and reason in the specific package handoff before enabling it. No arbitrary rate, amount, leverage, delay, deadline, history-retention or workload budget becomes approved merely by writing a draft. Remaining questions block their affected behavior only; source/design checkpoints can document gaps without implementing them.

## Verification and completion

Apply [project verification policy](../../.codex/VERIFICATION.md) to all packages/subtasks. Drafting is diff/link review only; no code/assets/Gradle/tests/game/migrations. Future routine changes use at most one focused named-test invocation if it adds confidence in meaningful changed behavior, reusing existing real-service/SQLite fixtures and unchanged evidence. No automatic full-suite, phase test, crash matrix or performance rerun. Categories in the original README are candidate evidence selected by impact, not cumulative completion gates.

Ordinary runnable deliveries run Java 21 `./gradlew build -x test`, `./gradlew help --task runClient` and `./gradlew runClient --dry-run` once. Preserve Minecraft 1.21.1/NeoForge 21.1.252/Java 21, working runClient/runServer, registered main source set and development runtime dependencies. One owner runs Gradle/game work sequentially; necessary runtime witnesses use disposable worlds. Never replace player worlds/backups, accept a new EULA or terminate a user's game.

Each future package writes `.codex/B5/<package>.md`, scoped evidence when needed, changed contracts, actual checks, unresolved capabilities and downstream needs; update queue status and stop. External-machine evidence stays manual/pending and excluded from CircleCI. Report actual process-crash versus clean reopen, synthetic versus real clients and accelerated versus production time honestly. Phase acceptance is traceable coverage with explicit limits, not automatic proof of every scenario.

## Approved calendar handoff — 2026-11-13

[T01–T07](../B1-market-registries/CALENDAR-REVIEW.md) are approved. Revalidate source/destination real regular sessions, holidays, breaks, early closes and supported UTC calendar coverage for each leg. Closure after a committed first leg leaves real inventory/exposure; resume/unwind only through finite B4 orders under approved BD11 policy. Calendar approval does not set numeric arbitrage costs/delays/budgets or guarantee simultaneous execution.

## Order policy handoff — 2026-11-14

B4 [O01–O10](../B4-orders-settlement/POLICY-REVIEW.md) are approved. Arbitrage uses ordinary protected/limit IOC rules and exact compatible-lot notional, respects per-listing price-time/self-control boundaries and never assumes a guaranteed offset. BD11 X01–X10 numeric transfer/friction/leg-risk policies are now approved; exact source/readiness remains preparation work and no approval enables B5 by itself.

## Approved B5 policy — X01–X10, 2026-11-16

[Policy record](POLICY-REVIEW.md) is normative. Reviewed fungible listings share one supply, and distinct-security conversions require sourced backed exact ratios and compatible whole-unit output. Same-security transfer has no additional fee and takes 30 real UTC seconds; distinct-security conversion takes five real minutes and charges 0.10% of captured input reference value, rounded CEILING once in the selected fee currency with no flat minimum. Quote/capture fee recipient, ratio, mark/FX and due time; reserve input/fee and charge only on completion. Explicit transfers may complete while closed; cancellation is permitted only before due under chronological authority. Keep original instructions/basis and restrict rather than lose property if completion predicates fail.

Automated entry requires projected net return ≥0.25% after all costs, current supported depth/FX and both venues open with delay+60s remaining. Use cash-funded sequential buy→transfer/convert→sell via ordinary B4 IOC, not a guaranteed atomic cross-venue offset. Each cycle is capped at CAD 100,000 initial buy-side cost plus committed fees, four unresolved cycles per fund, one per underlying group and one new cycle/fund/60s; M06 risk and M08 four-intents/minute still constrain actual work. Existing CAD 280B arbitrage funding, other pools and quote depth do not grow. Initial system funds use ordinary declared servicing-bank Tier 1 trading 0.25%/FX 0.50%; player actual eligible fee quotes remain independent and no hidden execution FX is allowed.

After ten real minutes unresolved, prioritize reduction with at most three total risk-reducing IOC attempts/cycle, observing normal protection and the remaining slice's 2% all-in loss budget. Retain actual units/cash/FX exposure and pause new cycles for that fund if no permitted exit is available or attempts exhaust. Actual market loss can exceed 2%; no indemnity, credit/top-up or fake offset. Previously committed obligations remain recoverable. Automation stays OFF until B5 acceptance/readiness plus explicit operator enable. Separate transfer/conversion scope and quote consent are required; corporate revocation blocks new discretionary orders, not ownership/recovery of committed custody obligations.

X10 prospectively separates securities activity from goods/borrowing and excludes automated institutional market-FX rebalancing from rate-driving customer FX while retaining distinct audit activity; actual player/customer FX follows current rules once. B5-00 must freeze an explicit versioned source-manifest/adapter contract, preserving historical A8 meanings, not silently relabeling old facts. Source/fee/ratio/custody/strategy/manifest feasibility remains preparation. BD09 operational budgets and B6–B8 product policies remain separate. No implementation selected.
