# B7 phase package contract — draft revision 1

Prepared 2026-11-13 for the requested package drafting. Planning only; no implementation/policy approval or verification is inferred. Read the [original design brief](DESIGN.md), [scope](README.md), [shared B contract](../planning/B-SHARED.md) and [decisions](../planning/B-DECISIONS.md). This contract and the [queue](EXECUTION.md) supplement DESIGN with individually selectable package boundaries. Shared current authority/verification rules supersede historical broader checklists, while recorded user approvals remain intact.

## Scope, ownership and dependencies

Short selling and securities lending. Inputs: Accepted B6 risk/credit and B2/B3/B4 custody, current marks and execution. Reuse the existing journal, bounded worker and dispatcher. Stage A owns cash/FX/credit; B2 owns securities/return claims; B4 owns fill coordination. Each package names its narrow domain owner; the main implementation agent owns shared integration and sequential Gradle/game checks. No parallel banking database or direct balance/holding edits.

The first package is a source/document feasibility checkpoint. It pins actual signatures, record limits, codec/version allocation and permissions; it does not manufacture runtime readiness. Later packages consume immutable versioned snapshots and recheck the current authoritative owner/source revisions at commit. Large actions cannot exceed existing record/transaction bounds: use an approved staged immutable plan with an affected-product gate or atomic generation publication, preserving original inputs and outcomes.

Upstream navigation: [B2 custody queue](../B2-brokerage-custody/EXECUTION.md), [B4 execution queue](../B4-orders-settlement/EXECUTION.md), [B6 shared risk queue](../B6-margin-risk/EXECUTION.md). Only scoped prerequisite evidence applies; these links authorize no execution.

## Coverage and downstream ownership

| Origin | Prior behavior | Package owner | Acceptance | Handoff |
| --- | --- | --- | --- | --- |
| New lending/short scope | No prior covered-short implementation | B7-01/02 | Locates, filled borrowing and returns conserve units | B8 transforms |
| B6 shared risk | One risk/claim view | B7-03 | Short liability and restricted proceeds counted once | B9 risk audit |
| F08/F16 | Cash/custody/execution service authority | B7-02/04 | Covers/fees commit via owner services without duplicate charges | B9 reconciliation |
| F19/F23 | Server authority and scoped recovery evidence | B7-00/05/06 | Original recall/loan IDs retain deadlines/outstanding units | B8/B9 evidence |

All listed new financial behaviors are proposals until their decisions are approved and implementation is explicitly selected. Existing tests or old code do not prove new guarantees; accepted unchanged services/evidence should be reused. No historical F-ID is silently removed.

## Required semantics

The detailed state/transaction/time examples in DESIGN remain design targets and are assigned to the package files in the queue. The shared contract supplies request identity, owner grants, revocation, worker/server/publication boundaries, checked precision, original-outcome recovery, bounded configuration/query/diagnostic rules and immutable history. Every runtime mutation needs its entity/policy/source revisions, permissions, transition, durable outcome and recovery position specified before edits. Money/share/risk/fee/participant effects must compose through their authoritative services in one financial commit; no assumed rollback after publication failure.

Player corporations from B0 are single-owner organizations with appointed managers and account/payment/transfer scope initially. **This phase must not enable corporate borrowing, margin, short exposure, public listing or share issuance by implication.** Corporate accounts and personal credit remain separate owners; no implicit shareholder/owner guarantee or debt transfer. Any later corporate financial product needs separately selected terms, authority and liability treatment.

Effective UTC history, chronological catch-up and deterministic original identities govern offline work. Missing marks, FX, custody authority or servicing readiness makes the affected product unavailable/restricted, never zero-valued or silently profitable. Pauses prevent new exposure without cancelling committed obligations. Queries are bounded/private; operators have audited scoped inspection, not implied owner spending authority. Configuration values apply prospectively after validation.

## Unresolved policy gates

BD07/[L01–L11](POLICY-REVIEW.md) are approved for initial personal short scope/combined cap, lending consent/supply/lineage, locates, proceeds/shared risk, rates/payouts, calls/recalls/buy-ins and residual obligations. Exact conforming lineage, aggregate-risk, fee/source and servicing/B8 feasibility remains B7-00 preparation. BD06 shared risk and BD08 action/equivalent interfaces must stay consistent; BD10 excludes corporate credit/short exposure initially.

Read DESIGN's alternatives and numerical examples as proposals, not approved settings. Record the chosen policy and reason in the specific package handoff before enabling it. No arbitrary rate, amount, leverage, delay, deadline, history-retention or workload budget becomes approved merely by writing a draft. Remaining questions block their affected behavior only; source/design checkpoints can document gaps without implementing them.

## Verification and completion

Apply [project verification policy](../../.codex/VERIFICATION.md) to all packages/subtasks. Drafting is diff/link review only; no code/assets/Gradle/tests/game/migrations. Future routine changes use at most one focused named-test invocation if it adds confidence in meaningful changed behavior, reusing existing real-service/SQLite fixtures and unchanged evidence. No automatic full-suite, phase test, crash matrix or performance rerun. Categories in the original README are candidate evidence selected by impact, not cumulative completion gates.

Ordinary runnable deliveries run Java 21 `./gradlew build -x test`, `./gradlew help --task runClient` and `./gradlew runClient --dry-run` once. Preserve Minecraft 1.21.1/NeoForge 21.1.252/Java 21, working runClient/runServer, registered main source set and development runtime dependencies. One owner runs Gradle/game work sequentially; necessary runtime witnesses use disposable worlds. Never replace player worlds/backups, accept a new EULA or terminate a user's game.

Each future package writes `.codex/B7/<package>.md`, scoped evidence when needed, changed contracts, actual checks, unresolved capabilities and downstream needs; update queue status and stop. External-machine evidence stays manual/pending and excluded from CircleCI. Report actual process-crash versus clean reopen, synthetic versus real clients and accelerated versus production time honestly. Phase acceptance is traceable coverage with explicit limits, not automatic proof of every scenario.

## Approved margin handoff — 2026-11-16

B6 [G01–G11](../B6-margin-risk/POLICY-REVIEW.md) are approved for personal long-margin, with one shared risk/claim/debt view. B7 must separately specify how return obligations/restricted proceeds/short liabilities enter that view; it cannot assume G02 long-only equations already enable shorts, double pledge loaned units or introduce corporate/institution margin. Secured debt/creditor ownership and prior due/call histories must remain intact.

## Approved lending/short policy — L01–L11, 2026-11-16

[Policy record](POLICY-REVIEW.md) is normative. Personal shorts require accepted B6 opt-in/score 650+/CAD 2,000 equity and no current blockers; CAD 100,000 is the combined new-risk cap for cash-margin debt, short replacement liability and accrued facility/borrow fees. Corporate/system short borrowing remains unavailable. Lending defaults OFF; personal owners choose free shares/quantities, while designated M02 long-only funds may offer up to 20% of each owned security under explicit system consent. No corporate lending initially, quoted/pledged/borrowed/in-transit inventory, automatic player opt-in or new seed.

Every short needs a backed locate expiring within 60 real UTC seconds and supports protected-market/limit IOC initially. Consume only actual filled quantity, create return claims and restrict sale cash atomically; cancel/release unused remainder. No onward lending or pool re-entry of short-delivered units while their originating obligation is open; retain lineage through onward sale and compatible cover-return constraints, not an endless re-lending chain. Same-controller lender/borrower exclusion and O07 controls apply. FIFO/acquisition basis and beneficial ownership are retained without a duplicate free lender holding.

Keep 100% short proceeds restricted. Initial total adjusted short collateral is 150% of current replacement value, maintenance 130%, cure 150%. One B6 risk service allocates collateral disjointly: long sleeve retains G02/G03; short sleeve applies L05/G03 without double-counted assets/proceeds/equity. Initial total marked long-security plus short replacement risk is ≤2×own net equity. B7-00/03 must freeze exact aggregate equations, current liability/mark/FX and exclusive claim semantics; opening a small short cannot disable long-margin requirements.

Borrow APR is 2%+18%×u² (2–20%), reset each financial day, accruing on actual outstanding units/current retained independent mark and posting due fees daily under Stage A precision. Utilization counts actual loaned supply, not locates as borrowed units; preserve denominator/rate snapshots and recalled outstanding units. Only collected fees pay 80% lender/20% bank; no spendable cash from receivables. Day 20 real minutes/year 364 financial days applies. Missing history restricts new risk and defers exact posting rather than inventing a zero fee or mark.

Short maintenance breach opens the shared 30-real-minute call with earliest existing deadline; reliable 110% coverage or nonpositive own net equity triggers immediate reduction. Lender recalls require return in 30 real UTC minutes, then ordinary finite forced cover/return using actual restricted proceeds and allocated collateral. Prioritize due recalls/deficient shorts with real liquidity; at most one new B6/B7 forced intent per borrower facility per 60s, resolving original outcomes first. No automatic bank borrowing, artificial fill or guaranteed loss/return. Partial return reduces only actual quantity; monetary fees/default claims and unreturned share obligations remain distinct. Existing cash collections cannot be assumed to manufacture shares or erase return claims at a mark price.

No new opening/recall/return charges beyond actual trade/FX and borrow fees. Lender receives 100% of valid borrower-funded dividend equivalents; B8 must coherently transform loan/locate/return/collateral/order entitlements and preserve quantity/basis without invented historic dividends. Explicit scopes/consent and complete inventory/risk/fee/recall/history coverage are required before short capability activation. Actual source/lineage/risk/provenance feasibility remains preparation; B8 actions and BD09 budgets remain separate. No implementation selected.
