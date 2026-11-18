# B9 design brief — Complete market integration and release

Draft revision 1 — 2026-11-13. Planning only. Read [phase scope](README.md), [shared B-series contract](../planning/B-SERIES-DESIGN.md) and [qualified A10 handoff](../../.codex/A10/architecture/a10-downstream.md). This brief does not approve release, broad test runs, migrations or any B implementation.

## Outcome and prerequisites

Produce a reviewable release whose documented capabilities match implemented contracts and actual evidence across the approved market universe. B9 integrates B1–B8; it does not use release preparation to silently fill unapproved gameplay policies, add optional compatibility modules or broaden upstream guarantees.

Required inputs are each B phase's approved decisions, package evidence, accounting/custody invariants, known limits and user-visible capability state. Current Stage A records schema 55, wire 13 and BankingIntegration 3; reconcile actual release inventory when selected. Historical A6/A10 version labels remain checkpoint facts, not release targets.

B9 owns integration audit, synchronization/retention policy, workload evidence, operator recovery procedures, final UI/API documentation and artifact review. Stage A/B2/B4 remain sole financial/custody/execution authorities. Fixes discovered here return to an explicitly bounded owning package or a scoped B9 integration fix; they do not authorize unrelated redesign.

## Proposed release evidence contract

Maintain a traceability ledger with feature ID, preservation/change decision, owning phase/package, current contract, implemented capability, evidence reference, limitations and release disposition. Record design, implementation and verification separately. An approved exclusion requires its actual decision reference; an absent test or old inventory path is not an exclusion.

Retain a release manifest containing mod/platform/dependency versions, schema/wire/API inventory, catalog hash and separate company/security/listing counts, policy/algorithm versions, source/license inventory, artifact hash and evidence scope. Runtime initialization/migrations must preserve old worlds and backups. The completed one-time reset is never rerun.

### Feature reconciliation scope

| Inventory | Owning behavior/evidence to reconcile | B9 acceptance focus |
| --- | --- | --- |
| F01–F03 | A1 lifecycle/storage; later approved database-upgrade contract | Platform/runtime packaging, current migration authority and preserve-world recovery; historical importer exclusion must be reconciled with later authorized import tooling |
| F04–F06 | A2/A8 currency/FX and A3/A4/A10 denomination/history | Exact money and captured currency facts; B1/B3/B4 listings/history remain correctly labeled after reporting changes |
| F07–F09 | A3/A4 account/funds/journal plus current overdrafts | Brokerage funding uses current ownership/status/cash authority; signed BANK cash and liabilities counted once; history reconciles |
| F10–F14 | A5 cash/cards/terminals/custody/shops | Reuse unchanged evidence; market integration must not bypass physical/owner protections |
| F15 | B1 catalog/provenance/seed workflow | Reviewed release count, identities and unavailable-seed behavior, with sourced/licensed data |
| F16 | B2 custody/funding and B4 execution | Finite counterparties, holdings/return obligations, lots and cash/fee settlement reconcile |
| F17 | B3 price administration/history and B4 chart view | Original raw history, explicit gaps/carry-forward, B8 action/index methodology and retention |
| F18 | A5/A10 banking and B4 trading UI | Usable search/funding/orders/history, privacy, clipping/localization and accurate state explanations |
| F19 | A1 authority and B4 concrete trading validation | Duplicate/stale/forged/reconnect outcomes preserve authority and original results |
| F20 | A4/A5/A10 public finance API and B4 market API | Versioned supported capabilities, owner grants, completion/cancellation and unavailable states |
| F21–F22 | A5 machines/assets and B4 computer/art | Four-cell computer behavior, packaging, original/license provenance and accessible resources |
| F23 | Owning phase's scoped evidence | Trace meaningful changed behavior and qualified guarantees; no automatic rerun of every historical test |

This is an audit plan, not a claim that every row is implemented or verified. Every unresolved row needs an owner and explicit release disposition.

## Synchronization, retention and bounded work

Proposed snapshots carry world/session/view generation, source revision, data instant, readiness, cursor and capability version. Quote displays distinguish indicative from executable values, while order outcomes remain authoritative. A stale screen can refresh; a stale request must not be silently executed at a new price. Recheck publication audience after asynchronous completion.

Private owner history and public market series have separate retention needs. Preserve operation identity, original terminal outcomes, authoritative journal/claim facts and required decoders. An archive policy may compact derived candles only with approved granularity/provenance and reproducible boundaries. Pagination and subscriptions are bounded; closing a menu removes display work without cancelling accepted orders.

Define approved ceilings before benchmarking: target players and actor type, 800-listing exact universe, active orders/positions/loans/recalls, publication cadence, data retention, offline interval, queue admission and latency percentiles. Record hardware/runtime/storage, warmup/sample duration and source readiness. Historical 15-player planning target and 20-synthetic-owner A10 sample are not equivalent to live B-series clients.

Illustrative workload declaration: “800 listings, 15 synthetic owners, 30 submitted intents per second, 10-minute sample” is merely a candidate, not an approved target. A measured 95th percentile of 100 ms would describe that sample only. No throughput/latency budget is approved by this example. Choose a representative bounded workload, rather than claiming every combination of 800 listings and all failure modes.

Track server tick/callback work, storage/CPU queues, accepted/rejected intent counts, settle latency, catch-up progress, retained history size and cash/custody invariants. Load shedding returns explicit retry/admission status without consuming operation IDs. Restrict affected new work during recovery/backlog; avoid whole-world stalls where current authority permits independent work.

## Recovery and combined scenarios

The operator runbook must identify readiness, safe stop, complete-world backup manifest, stopped-world upgrade procedure, restore validation, unsupported schema and uncertain commit handling. No live arbitrary SQL repair or backup overwrite. Use disposable copies for authorized future recovery evidence, retaining original backups and facts.

Select a composite changed-behavior scenario that combines actual finite fills, margin deficiency, recall and FX revision while checking cash, fees, shares, collateral and return obligations. For example, a recall of 20 units with only 7 bought before halt leaves 13 outstanding; reopening must resume the same obligation, not report a completed recall. A simultaneous reporting-currency change must not rewrite those original fills.

A crash/restore witness must identify the exact checkpoint and authority being demonstrated. Byte-identical backup restoration does not alone prove network recovery; a synthetic service actor does not prove a real client; a local socket client does not prove external-machine connectivity. Preserve qualified A10 limits rather than quietly upgrading them to full acceptance.

External-machine client evidence remains pending/manual and excluded from CircleCI jobs/pass-fail/coverage gates. Existing self-contained integrated-client CI remains valid within its scope. If external evidence is later obtained, record it separately. A missing local implementation or failed local check cannot be waived by that qualification.

## Ordered proposed packages

### B9-01 — Feature and accounting audit

Owner: B9 traceability. Requires B1–B8 handoffs. Reconcile F01–F23, new B features and deliberate changes against current authority/evidence. Gate: each gap has owner, blocker and release disposition; no inferred approvals. Exclude automatic test reruns or undocumented gameplay changes.

### B9-02 — Synchronization and history lifecycle

Owner: B9 integration with B3/B4 owners. Requires B9-01 and approved retention/snapshot policies. Define bounded subscriptions/pages, stale outcome handling and derived archive boundaries. Gate: reconnect shows original order state and histories keep original currencies/versions. Exclude deleting authoritative financial identities.

### B9-03 — Full-universe bounded workload

Owner: B9 performance. Requires B9-02 and approved workload/budgets. Record exact catalog, hardware, actor type and measured limits; select one justified workload sufficient for the concern. Gate: measured queues/latency and reconciliation match the approved claim or the release claim is narrowed. Exclude implied live-client or all-hardware guarantees.

### B9-04 — Upgrade, restore and composite recovery

Owner: B9 operations. Requires B9-03 and current database-upgrade contract. Define stopped-world copies, exact recovery witness and finite margin/recall/FX scenario. Gate: original outcomes and residual obligations survive selected interruption; actual failures remain visible. Exclude player-world migration without authorization and full-suite-by-default checks.

### B9-05 — Balancing and user/API documentation

Owner: B9 product integration. Requires B9-04 and reviewed measured behavior. Present fees, restrictions, insolvency/illiquidity outcomes, source/index labels and capability limitations in usable UI/docs. Balance changes require specific approved values and retained policy history. Gate: documentation matches actual capabilities and signed bank/closed-primary cases. Exclude optional mod adapters.

### B9-06 — Artifact and release handoff

Owner: B9 delivery. Requires B9-05 and resolved release dispositions. Review artifact resources/licenses, runtime dependencies, fixture exclusion, manifests and current changelog. Gate: runnable delivery checks and traceable evidence match published release claims. Publishing/deployment is separately authorized when needed; drafting a handoff does not publish.

## Decisions before implementation

| Decision | Recommendation — proposed | Alternative and consequence |
| --- | --- | --- |
| Release claim | State measured/qualified capability explicitly | Broad “fully verified” claim requires evidence absent from current handoffs |
| Workload | Measure the [approved reference scenario](OPERATIONS-REVIEW.md) | Report the actual host, scope, actor type and measured budgets |
| Retention | Apply the [approved chart and archive policy](OPERATIONS-REVIEW.md) | Preserve authoritative records for replay, fees, cost basis and action reconstruction |
| Balancing | Prospective versioned changes after measured review | Retroactive rebalance rewrites contracts and needs separate approval |
| External client | Keep manual qualification visible and outside CI | Treating synthetic/local actors as external evidence overstates coverage |
| Release gaps | Named deferral with explicit owner/approval where consequential | Silent omission loses preservation requirements and makes upgrade behavior uncertain |

Use the approved policy for performance targets and retention. The release review draws on the actual catalog, implementation inventory, source and license records, and package evidence.

## Verification and exit

Planning uses diff/link review only. Follow [project verification scope](../../.codex/VERIFICATION.md): future ordinary runnable delivery runs `./gradlew build -x test`, `./gradlew help --task runClient` and `./gradlew runClient --dry-run` once, plus at most one useful focused test invocation. Keep working runClient/runServer, registered main source set and required development runtime dependencies. One owner runs Gradle/game tasks sequentially.

Reuse unchanged evidence. Before any full-suite run, state the concrete concern and why narrower checks cannot address it, or cite the user's explicit request. Release/phase completion alone is not that reason. Exit supplies actual manifest, coverage matrix, approved deferrals, measured limits, recovery runbook and user-facing changelog; no implementation or verification is claimed by this design brief.
