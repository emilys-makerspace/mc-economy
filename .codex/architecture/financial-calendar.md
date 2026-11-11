# Financial calendar and arbitrary deadlines

A6-02-01 extends [clock/recovery](recovery-clock.md) under the [shared A6 contract](../../prompts/A6-credit-debt/CONTRACT.md) and [approved servicing rules](../../prompts/A6-credit-debt/POLICIES-SERVICING.md).

`FinancialCalendar` v1: day=1,200,000ms, week=8,400,000ms, month=33,600,000ms, year=436,800,000ms (13 months). Checked anchored boundaries retain exact origin/origination milliseconds; UUIDs bind world, subject, anchor, period, index and version. Positive indices, nonnegative UTC and overflow are checked. Helpers provide +14-day minimums, +2-day reporting, +84-day default, +7-year reporting expiry and +2-year collection expiry. Game time never enters this API.

Production journal-activates the exact UTC origin once, after migration. No debt products, scoring or missing reporting dependencies are installed here. Restart, downtime, pause and time commands cannot reset obligations.

## Storage and journal

V18 adds `financial_calendar` (origin/version, observed high-water, global tuple/fact/count), `financial_deadline_providers` (historical implementation identities and completed head), `financial_deadline_versions` (effective policy schedule), and `financial_deadline_events` (event/provider/instant/stage/version, exclusive cursor, counts and APPLYING/COMPLETE). Indexed heads/facts avoid full-history scans. Validated triggers protect calendar identity/high-water and monotonic event progress. V17 upgrades retain the existing stopped-world backup gate and all prior IDs/balances/history.

Journal service `economy:a6`; canonical v1; operation types `economy:a6/calendar/activate`, `economy:a6/calendar/advance`, `economy:a6/calendar/policy`. Exact v1 record encodings:

- `economy:a6/calendar-activation`: `1|originMillis`.
- `economy:a6/deadline-progress` / `economy:a6/deadline-completion`: `1|eventUUID|provider|effectiveMillis|stage|policyVersion|cursor|eventChildCount|completeFlag|globalChildCount`.
- `economy:a6/policy-effective`: `1|provider|policyVersion|effectiveMillis`.

Decoders reject malformed/noncanonical facts and mismatched completion flags. Startup, uncertainty recovery and offline inspection check historical fact/checkpoint linkage. Schedules start at exact origin/v1; later rows require their policy facts. Historical implementations are retained; additions may append, never reinterpret stored identities. New policies must begin strictly beyond wall time, observed target and processed frontier. Limits: 64 providers, 16 retained implementations/schedule boundaries per provider.

## Adapter and transaction contract

`DeadlineDispatcher.Provider` supplies the earliest next event, ordered exclusive target keys, version identities and read-only child preparation. These trusted worker APIs accept no client callbacks/SQL. Adapters must use bounded indexed queries and freeze membership at an event-time input frontier, including creation/closure semantics. No next event means dormant. Discovered events cannot backdate before the global (instant, stage, provider, UUID) frontier. Later products own actual financial calculations, authorization/revision checks and domain facts.

Select at most 16 targets/page; commit one child or event completion per worker turn, yielding to FIFO work. Page boundaries never complete an event. Child facts, effects, cursor and outcome commit atomically with a deterministic world/event/target operation ID through JournalEngine. Completion requires no remaining targets. Deferred funded payouts must be terminal processing outcomes with separate payables. Uncertain commits use original-ID lookup; replay does not repeat committed effects. No item/entity effects are implemented here.

## Runtime, readiness and configuration

FoundationRuntime merges events with fixed EconomicClock intervals by effective UTC. Existing fixed publications win equal-time ties without changing cadence, signatures, IDs or dependency order. Domain stages: policy/FX, accrual/yield, statement, payment, transition/expiry, reporting, score, tier, entity eligibility. Detached fixed commits recheck domain priority.

Domain child counts remain separate from fixed-only `economic_updates`/economic sequence. Readiness combines fixed debt and a bounded domain pending-event witness (0/1), not a total future-obligation count. Forward observations retain backlog; backward UTC waits and cannot repeat completed effects. Both schedulers gate new financial mutations; original outcome lookup remains possible. Startup final projection validation uses journal sequence so domain-only commits are included.

Optional `config/economy-calendar.properties`: `pageSize=16`, integer 1–16. Missing file uses defaults; unknown/unreadable/invalid/out-of-range configuration rejects without clamping. Calendar lengths and one-child-per-turn behavior are fixed. Settings load once per server lifecycle. A6-02-02 delivers the diagnostics below; A6-02-03 accepts the shared clock foundation within the evidence and limits below.

## Diagnostics

A6-02-02 adds `CalendarDiagnostics`, an immutable snapshot from one SELECT-only transaction on the existing worker. It reads at most64 fixed systems,64 domain providers and16 effective-version rows per registry entry, plus indexed single active heads. It calls only the trusted dispatcher's read-only next-event source in that transaction. It never observes UTC, activates, commits, advances cursors or calls readiness admission. No new schema, persisted facts, journal types/versions, packets or physical effects.

`FoundationRuntime.readCalendarDiagnostics()` is available after clock/storage initialization, including during startup catch-up. ServerServices samples at most once/second during ongoing/startup work, plus final readiness; a failed sample preserves the last timestamped snapshot with a controlled error code. Existing bounded worker queues and rate-limited failure logs remain. Command formatting only reads cached detached data, so diagnostics neither wait on JDBC nor bypass financial readiness during backlog, clock waits or pressure.

`/economy diagnostics` includes the calendar summary. `/economy diagnostics calendar` pages registry detail; `/economy diagnostics calendar after <cursor>` continues after the printed exclusive key (`domain/<id>` or `fixed/<id>`). Eight entries/page, at most35 lines on a detail page; each page may use a newer timestamped sample. Both routes inherit the existing live server-console/RCON/permission-level2 operator check. Nonoperator status contains no calendar/private detail. Invalid cursors and failure reasons are sanitized; output goes only to the requester.

Displays include exact origin/version; sampled current, observed and retained UTC targets; completed zero-based day/month/year counts; checked next world boundaries (explicit overflow); next domain instant/stage/event/version; fixed earliest instant (without inventing a dependency tie order); effective/retained/pending provider versions; active exclusive cursor and child count; worker checkpoint/in-flight state and clock-wait/failure reasons. Fixed interval backlog is exact, while domain progress is explicitly a0/1 pending-next-event witness, never total obligations or an ETA. A sample does not grant readiness. The generic pending-work metric is labeled work units rather than incorrectly calling the domain witness an interval. No reset/skip command exists.

## Foundation acceptance

[A6-02-03](../A6/A6-02-03.md) accepts the shared dispatch/readiness boundary for later score, interest, payment, reporting and collection adapters. [Evidence](../A6/verification/A6-02-03.md) adds exact month/restart identity and cursor preservation, three-month stopped-target/rewind recovery, all nine same-time stages after fixed publication, historical policies, +777ms anchors,65-target paging, and actual worker FIFO/admission witnesses.

Standalone disposable JVMs are terminated before a child commit, after a child commit and after event completion; recovery preserves only committed effects and original completion IDs. All bank 1–5-year and subprime 1–8-year terms map to 13 payments/year and13–65/13–104 monthly periods. No production code, schema, fact encoding, API, packet, lifecycle authority or physical effect changes in acceptance.

Fixture timings and 225-effect/65-target counts are bounded local witnesses, not production portfolio throughput. Controlled UTC represents downtime without waiting real years. Future adapters must preserve indexed event-time membership, paged historical input folds, captured versions and their own financial/security/performance acceptance; clock acceptance does not implement or verify those products.


## A6-04 current contract (2026-11-05)

[Wealth, pricing and funded yield](wealth-pricing.md) defines schema V21, persisted required-source contracts, fresh qualification/fee witnesses, assigned-bank FX pricing and time-weighted bank-funded deposit yield. Production installs bounded accrual/payout providers at stage 1 and daily Tier reevaluation at stage 7; empty domains remain dormant. Source snapshots and unpaid receivables remain distinct from spendable funds. Live branch adapters, issued cards, lending and trading are still separate packages. Earlier no-product/default-Tier2-denial descriptions apply to their historical delivery checkpoints.

## A6-07 dormant score contract

[Credit scoring](credit-profiles.md) computes the next sequential world-aligned28-day period only from a completed reporting-stage witness and captured input/journal frontier. No score provider or missing reporting dependency is installed in production. A6-10-03 owns combined due+2 reporting/SCORE activation and backlog acceptance.

## A6-08-01 extension

A6-08-01 adds economy:a6/credit-daily (stage 1) and economy:a6/credit-statements (stage 2), with facility-anchor deadlines and bounded target cursors. Empty credit domains have no events. Reporting/score providers remain unregistered; see [credit servicing](credit-facilities.md).

## A6-08-02 diagnostics

Credit diagnostics report saved per-facility anchor backlog and sanitized provider heads without advancing time. A narrow durably audited inspection path remains available during catch-up; ordinary financial writes remain gated. No new provider or reporting/score activation occurs. See [credit diagnostics](credit-diagnostics.md).

## A6-08-03 acceptance

A6-08-03 verifies multi-period credit catch-up and crash/restart replay. CreditEvents is a bounded reader, not another provider. Production new exposure is gated while the required default/reporting lifecycle is absent; no missing dependency is registered and empty-domain readiness remains intact. See [acceptance](credit-acceptance.md).

## A6-09-01 monthly loans

The production dispatcher registers `economy:a6/loan-monthly` v1, fingerprint `loan-anchor-monthly-v1-fixed-segmented`, at interest/requirement stage 1. Indexed origination-anchored boundaries use bounded 16-row keyset pages and one child turn per loan; immutable monthly requirements and capitalized interest commit atomically. Empty loan domains add no daily job. No reporting, default, autopay or scoring provider is activated. See [loan servicing](loans.md).

## A6-09-03 accepted loan catch-up

Two loans with page size one resume the persisted APPLYING cursor after restart and catch up three offline months within eight resumed turns. Each turn commits at most one child/completion. Original installment UUIDs and due dates remain stable. A6-10 owns chronological delinquency transitions within the foundation requirement bound. See [loan recovery and obligation handoff](loan-acceptance.md).

## A6-10-01 lifecycle deadlines

Five v1 providers process due/default/expiry/sale work at stage4 and original due+2 reports at stage5. Scheduled payment stage3 precedes reporting, with score stage6 reserved for later acceptance and Tier stage7 retained. Indexed durable heads and tuple cursors bound work per turn. See [lifecycle provider configuration](debt-lifecycle.md).
