# B6 phase package contract — draft revision 1

Prepared 2026-11-13 for the requested package drafting. Planning only; no implementation/policy approval or verification is inferred. Read the [original design brief](DESIGN.md), [scope](README.md), [shared B contract](../planning/B-SHARED.md) and [decisions](../planning/B-DECISIONS.md). This contract and the [queue](EXECUTION.md) supplement DESIGN with individually selectable package boundaries. Shared current authority/verification rules supersede historical broader checklists, while recorded user approvals remain intact.

## Scope, ownership and dependencies

Margin lending, leverage and liquidation. Inputs: Accepted A6/Stage A lending lifecycle and B2/B3/B4 custody/mark/execution handoffs. Reuse the existing journal, bounded worker and dispatcher. Stage A owns cash/FX/credit; B2 owns securities/return claims; B4 owns fill coordination. Each package names its narrow domain owner; the main implementation agent owns shared integration and sequential Gradle/game checks. No parallel banking database or direct balance/holding edits.

The first package is a source/document feasibility checkpoint. It pins actual signatures, record limits, codec/version allocation and permissions; it does not manufacture runtime readiness. Later packages consume immutable versioned snapshots and recheck the current authoritative owner/source revisions at commit. Large actions cannot exceed existing record/transaction bounds: use an approved staged immutable plan with an affected-product gate or atomic generation publication, preserving original inputs and outcomes.

Upstream navigation: [Stage A collateral boundary](../../.codex/A6/architecture/a6-downstream.md#b6-collateral-and-variable-rate-products), [B2 custody queue](../B2-brokerage-custody/EXECUTION.md), [B3 marks/liquidity queue](../B3-prices-liquidity/EXECUTION.md), [B4 execution queue](../B4-orders-settlement/EXECUTION.md). Only scoped prerequisite evidence applies; these links authorize no execution.

## Coverage and downstream ownership

| Origin | Prior behavior | Package owner | Acceptance | Handoff |
| --- | --- | --- | --- | --- |
| New margin scope | No prior margin implementation | B6-01/03 | Single risk view and exclusive pledges resist competing commitments | B7 shared short risk |
| A6 lending seam | Fixed unsecured; secured/variable unavailable without adapter | B6-00/02 | Only approved funded adapter may add capabilities | Stage A debt servicing |
| F08/F16 | Cash/custody/execution remain service-owned | B6-05 | Actual finite fills pay only actual debt/fees | B9 reconciliation |
| F19/F23 | Authenticated authority and scoped evidence | B6-04/06 | Restart preserves deadlines/plan and residual debt | B9 evidence ledger |

All listed new financial behaviors are proposals until their decisions are approved and implementation is explicitly selected. Existing tests or old code do not prove new guarantees; accepted unchanged services/evidence should be reused. No historical F-ID is silently removed.

## Required semantics

The detailed state/transaction/time examples in DESIGN remain design targets and are assigned to the package files in the queue. The shared contract supplies request identity, owner grants, revocation, worker/server/publication boundaries, checked precision, original-outcome recovery, bounded configuration/query/diagnostic rules and immutable history. Every runtime mutation needs its entity/policy/source revisions, permissions, transition, durable outcome and recovery position specified before edits. Money/share/risk/fee/participant effects must compose through their authoritative services in one financial commit; no assumed rollback after publication failure.

Player corporations from B0 are single-owner organizations with appointed managers and account/payment/transfer scope initially. **This phase must not enable corporate borrowing, margin, short exposure, public listing or share issuance by implication.** Corporate accounts and personal credit remain separate owners; no implicit shareholder/owner guarantee or debt transfer. Any later corporate financial product needs separately selected terms, authority and liability treatment.

Effective UTC history, chronological catch-up and deterministic original identities govern offline work. Missing marks, FX, custody authority or servicing readiness makes the affected product unavailable/restricted, never zero-valued or silently profitable. Pauses prevent new exposure without cancelling committed obligations. Queries are bounded/private; operators have audited scoped inspection, not implied owner spending authority. Configuration values apply prospectively after validation.

## Unresolved policy gates

BD06/[G01–G11](POLICY-REVIEW.md) are approved, settling initial eligibility/cap, leverage/equity, collateral/haircuts, existing-lender funding, financial-calendar variable rates, calls/liquidation/residual debt and outage policy. Exact conforming adapter/source/risk/claim/reporting feasibility remains B6-00 preparation. BD09 governs work bounds. Corporate credit remains expressly deferred under BD10.

Read DESIGN's alternatives and numerical examples as proposals, not approved settings. Record the chosen policy and reason in the specific package handoff before enabling it. No arbitrary rate, amount, leverage, delay, deadline, history-retention or workload budget becomes approved merely by writing a draft. Remaining questions block their affected behavior only; source/design checkpoints can document gaps without implementing them.

## Verification and completion

Apply [project verification policy](../../.codex/VERIFICATION.md) to all packages/subtasks. Drafting is diff/link review only; no code/assets/Gradle/tests/game/migrations. Future routine changes use at most one focused named-test invocation if it adds confidence in meaningful changed behavior, reusing existing real-service/SQLite fixtures and unchanged evidence. No automatic full-suite, phase test, crash matrix or performance rerun. Categories in the original README are candidate evidence selected by impact, not cumulative completion gates.

Ordinary runnable deliveries run Java 21 `./gradlew build -x test`, `./gradlew help --task runClient` and `./gradlew runClient --dry-run` once. Preserve Minecraft 1.21.1/NeoForge 21.1.252/Java 21, working runClient/runServer, registered main source set and development runtime dependencies. One owner runs Gradle/game work sequentially; necessary runtime witnesses use disposable worlds. Never replace player worlds/backups, accept a new EULA or terminate a user's game.

Each future package writes `.codex/B6/<package>.md`, scoped evidence when needed, changed contracts, actual checks, unresolved capabilities and downstream needs; update queue status and stop. External-machine evidence stays manual/pending and excluded from CircleCI. Report actual process-crash versus clean reopen, synthetic versus real clients and accelerated versus production time honestly. Phase acceptance is traceable coverage with explicit limits, not automatic proof of every scenario.

## Approved personal margin policies — G01–G11, 2026-11-16

[Policy record](POLICY-REVIEW.md) is normative. Personal opt-in only, established score ≥650 with no current delinquency/default/collection blockers, CAD 2,000 own equity at enrollment/new risk, one facility per brokerage. New debt including accrued interest/fees is capped at CAD 100,000 equivalent subject to collateral and actual lender free capital; later accrual can exceed the cap without erasing debt. No new seed/issuance, personal BANK overdraft fallback, corporate/institution margin or automatic Tier 2 multiplier.

New risk requires 50% adjusted equity and a 2× gross leverage ceiling; maintenance is 35%. Define G gross marked assets, A haircut-adjusted eligible assets, D complete facility debt, E=A−D. Apply exact G02 equations to post-action and pending commitments. Verified owned cash/ordinary securities use P06 fresh independent marks/FX, with 10% FX haircut and additional 20% on issuer exposure above 25% of G. Aggregate classes/cross-listings without duplicate holdings; first-priority exclusive collateral only, no rehypothecation. Missing source data restricts risk rather than fabricating zero-value collateral.

Draw only in lender native currency as part of an actual B4 purchase settlement, funded by existing protected lender cash. Variable APR is currency prime + 4pp bounded 0–50%, reset prospectively at the next financial-day boundary; Stage A accrues eight-place interest and posts monthly. Retain previous valid rate until a valid prospective reset; gate new borrowing when required prime is unavailable. Financial day=20 real minutes, month=9h20m, year=5d1h20m; this is not a 365-real-day APR. No origination/call/prepayment fees. Explicit eligible brokerage cash/net proceeds reduce due debt under captured allocation; no automatic personal BANK sweep or borrowed cash withdrawal.

Below 35% maintenance open a 30-real-minute cure call, restrict new risk and require restoration to 50%. With debt and reliable complete risk, equity ≤10% of A or nonpositive gross equity triggers immediate liquidation. No deadline reset on reconnect/restart; unknown marks cannot invent a breach or cure. At deadline/critical breach, cancel residual risk-increasing orders and liquidate most-liquid eligible positions first, FIFO within security, via finite B4 IOC. Recompute after actual fills; no more than one new liquidation intent/facility/60s and resolve original outcomes first. O02 protection applies, but X08's 2% optional arbitrage-loss budget does not limit mandatory margin liquidation.

Actual net sale cash services debt; disclosed forced-sale mandate permits explicitly quoted FX of actual eligible foreign sale proceeds into loan currency. Unavailable markets/FX retain real inventory/cash and pending debt, not fictitious fills. Collateral exhaustion creates an actual due deficiency carried through the existing lifecycle; preserve due/report/default and creditor/secured-claim provenance through collections. Calls alone are not fabricated missed-payment reports. Closing/disabling requires debt/orders/claims/uncertainty resolved. Secured/variable capabilities stay unavailable until the complete accepted adapter/pledge/rate/servicing/recovery boundary exists. Source feasibility remains preparation; B7 shorts, B8 actions and BD09 budgets remain separate. No implementation selected.

## Approved securities-lending handoff — 2026-11-16

B7 [L01–L11](../B7-shorts-lending/POLICY-REVIEW.md) approve the personal short extension with a shared CAD 100k cash-debt+replacement-liability+fees admission ceiling and disjoint collateral allocations. Long sleeve retains G02/G03; short sleeve requires 150/130% coverage and 150% cure. Keep one authoritative aggregate-risk/claim view and total 2×position-risk/own-equity ceiling; no shared collateral double count or a tiny short disabling G02. Earliest shared call deadline/long critical rules remain; 110% short coverage is its critical trigger. One forced B6/B7 intent per facility/minute, with real funded liquidity and separate share-return/monetary claims. Freeze exact equations/source compatibility at B7-00/03; no automatic corporate/system borrowing.
