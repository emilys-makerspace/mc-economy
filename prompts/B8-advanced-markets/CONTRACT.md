# B8 phase package contract — draft revision 1

Prepared 2026-11-13 for the requested package drafting. Planning only; no implementation/policy approval or verification is inferred. Read the [original design brief](DESIGN.md), [scope](README.md), [shared B contract](../planning/B-SHARED.md) and [decisions](../planning/B-DECISIONS.md). This contract and the [queue](EXECUTION.md) supplement DESIGN with individually selectable package boundaries. Shared current authority/verification rules supersede historical broader checklists, while recorded user approvals remain intact.

## Scope, ownership and dependencies

Advanced orders, corporate actions and indices. Inputs: Accepted B5/B6/B7 scoped handoffs and B1/B2/B3/B4 identity/custody/session/order contracts. Reuse the existing journal, bounded worker and dispatcher. Stage A owns cash/FX/credit; B2 owns securities/return claims; B4 owns fill coordination. Each package names its narrow domain owner; the main implementation agent owns shared integration and sequential Gradle/game checks. No parallel banking database or direct balance/holding edits.

The first package is a source/document feasibility checkpoint. It pins actual signatures, record limits, codec/version allocation and permissions; it does not manufacture runtime readiness. Later packages consume immutable versioned snapshots and recheck the current authoritative owner/source revisions at commit. Large actions cannot exceed existing record/transaction bounds: use an approved staged immutable plan with an affected-product gate or atomic generation publication, preserving original inputs and outcomes.

Upstream navigation: [B1 registry queue](../B1-market-registries/EXECUTION.md), [B2 custody queue](../B2-brokerage-custody/EXECUTION.md), [B3 simulation queue](../B3-prices-liquidity/EXECUTION.md), [B4 execution queue](../B4-orders-settlement/EXECUTION.md), [B5 conversion queue](../B5-international-arbitrage/EXECUTION.md), [B6 risk queue](../B6-margin-risk/EXECUTION.md), [B7 obligations queue](../B7-shorts-lending/EXECUTION.md). Only scoped prerequisite evidence applies; these links authorize no execution.

## Coverage and downstream ownership

| Origin | Prior behavior | Package owner | Acceptance | Handoff |
| --- | --- | --- | --- | --- |
| New advanced-order/halt scope | No substantive prior implementation | B8-01/02 | At most one child; prohibited halt cannot fill | B9 order audit |
| New actions/dividends | No prior coordinated action implementation | B8-03/04-01/04-02 | Funded rights preserve basis/claims and atomic visibility | B9 reconciliation |
| F17 | Stored/daily raw history preserved | B8-05/06 | Retained factors and disclosed indices do not rewrite raw prices | B9 retention |
| F19/F23 | Server authority and meaningful recovery coverage | B8-00/07 | Interrupted action cannot publish mixed financial generations | B9 evidence ledger |

All listed new financial behaviors are proposals until their decisions are approved and implementation is explicitly selected. Existing tests or old code do not prove new guarantees; accepted unchanged services/evidence should be reused. No historical F-ID is silently removed.

## Required semantics

The detailed state/transaction/time examples in DESIGN remain design targets and are assigned to the package files in the queue. The shared contract supplies request identity, owner grants, revocation, worker/server/publication boundaries, checked precision, original-outcome recovery, bounded configuration/query/diagnostic rules and immutable history. Every runtime mutation needs its entity/policy/source revisions, permissions, transition, durable outcome and recovery position specified before edits. Money/share/risk/fee/participant effects must compose through their authoritative services in one financial commit; no assumed rollback after publication failure.

Player corporations from B0 are single-owner organizations with appointed managers and account/payment/transfer scope initially. **This phase must not enable corporate borrowing, margin, short exposure, public listing or share issuance by implication.** Corporate accounts and personal credit remain separate owners; no implicit shareholder/owner guarantee or debt transfer. Any later corporate financial product needs separately selected terms, authority and liability treatment.

Effective UTC history, chronological catch-up and deterministic original identities govern offline work. Missing marks, FX, custody authority or servicing readiness makes the affected product unavailable/restricted, never zero-valued or silently profitable. Pauses prevent new exposure without cancelling committed obligations. Queries are bounded/private; operators have audited scoped inspection, not implied owner spending authority. Configuration values apply prospectively after validation.

## Unresolved policy gates

BD08 governs trigger basis, halt/reopening, action chronology, fractions, funded entitlements, successor claims, news and index methodology. BD09 bounds/retention must be approved. The explicitly requested B8 corporation expansion adds player issuer/equity and optional listing; B0 registration alone still does not issue/list shares.

Read DESIGN's alternatives and numerical examples as proposals, not approved settings. Record the chosen policy and reason in the specific package handoff before enabling it. No arbitrary rate, amount, leverage, delay, deadline, history-retention or workload budget becomes approved merely by writing a draft. Remaining questions block their affected behavior only; source/design checkpoints can document gaps without implementing them.

## Verification and completion

Apply [project verification policy](../../.codex/VERIFICATION.md) to all packages/subtasks. Drafting is diff/link review only; no code/assets/Gradle/tests/game/migrations. Future routine changes use at most one focused named-test invocation if it adds confidence in meaningful changed behavior, reusing existing real-service/SQLite fixtures and unchanged evidence. No automatic full-suite, phase test, crash matrix or performance rerun. Categories in the original README are candidate evidence selected by impact, not cumulative completion gates.

Ordinary runnable deliveries run Java 21 `./gradlew build -x test`, `./gradlew help --task runClient` and `./gradlew runClient --dry-run` once. Preserve Minecraft 1.21.1/NeoForge 21.1.252/Java 21, working runClient/runServer, registered main source set and development runtime dependencies. One owner runs Gradle/game work sequentially; necessary runtime witnesses use disposable worlds. Never replace player worlds/backups, accept a new EULA or terminate a user's game.

Each future package writes `.codex/B8/<package>.md`, scoped evidence when needed, changed contracts, actual checks, unresolved capabilities and downstream needs; update queue status and stop. External-machine evidence stays manual/pending and excluded from CircleCI. Report actual process-crash versus clean reopen, synthetic versus real clients and accelerated versus production time honestly. Phase acceptance is traceable coverage with explicit limits, not automatic proof of every scenario.

## Approved securities-lending handoff — 2026-11-16

B7 [L01–L11](../B7-shorts-lending/POLICY-REVIEW.md) are approved. B8 must transform loan/locate/return quantities, origin-lineage restrictions, basis, collateral/proceeds and order entitlements coherently through dividends/actions. Lender dividend equivalents are 100% borrower-funded entitlements, not subject to 80/20 borrow-fee sharing; distinguish issuer distributions to actual qualified holders and do not duplicate economic claims. No invented historical dividends, cash replacement of unreturned units or claim erasure. Exact event policy remains B8 review.

## Explicit player-corporation expansion — 2026-11-16

[Recorded scope and three answers](CORPORATIONS-EXPANSION.md) supersede earlier B8 exclusions of player equity/mergers/listing: private share issuance/classes/splits, same-owner and cross-player mergers, class-aware dividends/actions, founder control with optional transition to class voting and separate optional listing. B0 remains the founder registration/banking foundation, without automatic equity/listing or corporate borrowing. Four B8-08 children carry equity/issuance/governance/listing interfaces; existing B8-03/04 coordinate actual actions with all custody/debt/return owners. Preserve minority/creditor/counterparty rights through approved class/governance terms and dual-company merger authority.

Fictional news is the selected source model. Current UTC instants are shared; 20-minute financial days are a calendar scale, not a separate UTC standard. Retain source/provenance and master effective/commit times; real catalog seed dates do not become live news dates. Publish simulated labels, persist selected events and apply once per underlying factor group with versioned atomic quote/event visibility. Synchronized announcement/first effect, once-per-underlying application, session-aware execution/original offline recovery and fact-backed player-action news are approved. Apply the [approved news calibration](NEWS-CALIBRATION-REVIEW.md) for launch cadence, magnitude and decay. Player action announcements derive from actual approved actions; free-text news cannot mint shares/cash or override governance. See the expansion for the approved equity, action and launch listing rules. Stop and halt rules are approved. Use the [approved index methodology](INDEX-METHODOLOGY-REVIEW.md) for B8-06. Use the [approved curated issuer action rules](CURATED-ISSUER-ACTIONS-REVIEW.md) for B8-03 and B8-04. No implementation selected.

## Expansion refinements approved — 2026-11-16

[Approval record](CORPORATIONS-EXPANSION.md#approval-record--2026-11-16) confirms the one-way founder→class-voting transition, with no founder-control rollback or stale-founder mandate bypass after effectiveness. It also approves the proposed master-UTC fictional news chronology: announcement and initial price effect/quote invalidation publish together, apply once per underlying, closed venues wait for real sessions and original selected events survive catch-up. Actual player-corporation action facts authorize their announcements; narrative cannot mint/merge/revalue arbitrarily. The subsequent 2026-11-17 approval settles the initial player equity and action rules. The launch listing and valuation rules were also approved on 2026-11-17. No implementation selected.

## Player equity and action policy approved on 2026-11-17

Apply the [approved equity and action rules](EQUITY-AND-ACTIONS-REVIEW.md) to B8-03, B8-04 and B8-08. They define the share classes and their protections, 24-hour pro rata offers and dividend notice, voting thresholds and 48-hour window, uniform splits with funded fraction handling, and the merger consent and successor rules. Each cross-player survivor adopts share-class voting at closing. A same-owner survivor may retain founder control if both companies use it.

## Player listing policy approved on 2026-11-17

Use the [approved listing and valuation rules](LISTING-AND-VALUATION-REVIEW.md) for B8-08-04. Approve one class per issuer on one venue under the 40-listing world cap. Apply the 20% outside float and three independent holder groups at admission. Funded limit orders can establish a first traded price before protected market orders become available. Private and listed player equity are outside Tier 2 wealth qualification, collateral and securities lending at launch. Subsequent financial eligibility needs a separate independent valuation policy.

## Stop and halt policy approved on 2026-11-17

B8-01 and B8-02 use the [approved stop and halt rules](STOPS-AND-HALTS-REVIEW.md). Eligible settled listing trades trigger one protected stop-market or stop-limit intent. A confirmed 10% move within five minutes pauses the listing for five real minutes. A coincident stop waits for eligible reopening and retains its original deadline and price bounds. Resting orders keep their expiry and priority, owners can cancel, and financial servicing deadlines continue. Reopening uses the next eligible regular session and a reconciled book.
