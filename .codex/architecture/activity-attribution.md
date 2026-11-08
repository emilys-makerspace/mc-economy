# Activity attribution — A8-02-01

Delivered source/counter foundation for [A8 revision 1](a8-shared-contracts.md). World schema is **40**. This package installs no window closer, macro model, rate provider, trader checkout, NPC emission or player diagnostics. Existing prime, daily FX, financial amounts and captured product terms retain their authority.

## Runtime and prospective coverage

`simulation.activity.ActivityRuntime` is installed for financial-calendar runtimes. The source observer runs after A1 original-operation lookup, handler preparation, `PricingAdmission` and `DepositYield.observe`. Their rejection/result semantics are retained. Standalone trusted `SqliteStorage` fixtures/maintenance are not silently promoted into a fully configured finance runtime.

Production registers an immutable `economy:a8/activity-manifest` v1 after existing initialization/catch-up and pricing activation, then seeds registered institution CASH in keyset pages <=16 before READY. New source effects during later catch-up are observed against the retained manifest. The partial day containing activation is explicitly distinguishable through `activated_ms` and calendar `origin_ms`; no prior day is declared covered or zero. Capacity updates on capital allocation use the same-commit account/floor identities, so first-world capital allocation after currency choice remains observable as capacity, never as recurring monetary creation.

Manifest v1 names merchant/register/loan/credit/debt/fx/operator/cash adapters and declares NPC sources without emitting them. Record/classifier version 1 is immutable. Relevant financial fact versions are explicitly checked; a future adapter cannot silently reinterpret them.

## Semantic sources

| Source | Captured activity / exclusions |
| --- | --- |
| Register COMMIT | Goods principal once; embedded merchant CAPTURE is suppressed. Credit-funded checkout still counts its goods sale. Reservation, stock save and delivery are neutral. |
| Merchant CAPTURE/REFUND | Indexed original invoice principal/currency. Refund links the original capture operation and contributes a separate refund counter. Authorization/expiry/cancellation do not count. |
| Loan ORIGINATED/PAYMENT | Explicit disbursed principal and principal-paid allocation; exclude interest and fees. |
| Credit draw/payment | Actual DRAW_PURCHASE/DRAW_ADVANCE; prefer explicit audit principal allocation over duplicate repayment event. Undrawn limits/issuance/interest are neutral. |
| Collection payment | Resolve the debt case and compare pre/post underlying principal from the committed state; debt sale/write-off is not borrower repayment. Autopay carries ordinary semantic receipts. |
| Customer FX | Source principal sold and actual target principal bought from generic FX, loan disbursement and deposit/withdrawal cash previews. Exclude fees/rounding remainder, treasury/rebase/internal clearing. Retain publication UUID. |
| Operator money | Actual SameCurrency CREDIT/DEBIT semantic kinds, not arbitrary ISSUE/REDEEM postings. Transfers are neutral. |
| Cash | Marked deposit/withdrawal/change/serial representation is neutral. First accepted unmarked operator cash is counted by its original denomination, once. |
| NPC | Enum/source semantics are declared only; settlement-owned adapters must install actual emission in their later packages. |

`ActivityFacts.Draft` carries source/version, semantic kind, currency, positive gross minor amount, subject, original effective time, optional reversal and publication. `Observation` adds stable world/operation/classifier/ordinal UUID, accounting time and day. Refunds/removal remain separate positive gross kinds; future consumers derive signed/net values without destroying provenance. No network action supplies a draft or SQL callback.

## Atomic facts and projections

`economy:a8/activity-batch` v1 canonically encodes observations and changed capacity snapshots. It appends to the **same** source journal transaction and preserves its result. Source projections and activity projections roll back together. A1 replay returns the original outcome before classification; it does not append another observation or mutate a counter.

V40 stores `activity_activation`, `activity_batches`, `activity_observations`, `activity_day_counters`, `activity_capacity`, `activity_capacity_history`, `activity_capacity_totals` and `activity_capacity_totals_history`. Observation identity is unique by world/source operation/classifier/ordinal. Counters key financial day/currency/kind with checked count/minor totals. SQLite integer checks and Java checked arithmetic prevent wrapping or REAL promotion. A bounded or overflowing observation rejects before financial effects commit.

Accounting time is max of supplied trusted worker UTC, activation and retained observation high-water. If a future consumer has sealed the candidate day, move to the first open boundary; retain original financial effective time. No package here seals buckets. A clock rewind cannot move later observations into an older day. Missing required manifest/projection evidence fails startup/recovery rather than uninstalling the source or rebuilding it by rerunning financial classifiers.

The v1 bound is 32 observations and 275 capacity identities (25 institutions ×11 currencies) per encoded batch, with at most 25 bank keys. Actual fact payload stays within the existing 16KiB record limit and the source transaction retains its 64-record/128KiB envelope bound. A source combination that genuinely exceeds these limits is rejected, never partially captured. Capacity seed pages remain <=16 raw accounts. History reads by models/diagnostics must be indexed/keyset paged; no public unbounded history endpoint is added.

## Lending-capacity proxy

Only actual registered CASH of customer banks/eligible Ironbridge is included; collection-only Waystone and treasury/depositor synthetic accounts are excluded. Subtract the immutable operating floor only in its base currency; exclude segregated reserves, claims and customer deposits. A bank with recognized unpaid dated yield has zero available proxy. This snapshot is normalization input, not additional loan authority or a promise of spendable funds.

Observe affected cash postings, same-commit planned financial accounts/capital floors and final yield claim states. The overlay includes all planned cash deltas and removes replaced claim rows when checking distress. Recalculate only affected banks and their at most 11 registered cash currencies, each indexed query <=16 rows. Do not scan all customers. Unchanged snapshots create no new capacity history.

Maintain checked per-currency totals incrementally. Both account and aggregate histories record the source **journal sequence**, in addition to accounting milliseconds; random operation UUIDs are not chronology. An aggregate as-of read uses `currency=? AND accounting_ms<exclusiveEnd AND journal_sequence<=frontier ORDER BY accounting_ms DESC,journal_sequence DESC LIMIT 1`. This remains deterministic when multiple source commits share one millisecond. No value before initial coverage is silently invented.

## Validation and handoff

Strict historical codecs are registered in `RecordCatalog.core`. Activity schema validation runs on ordinary/recovery storage open and supported offline backup/restore. It streams immutable batches without rerunning the classifier, verifies manifest/world/calendar linkage, every observation/history/source-operation correspondence, checked counter sums and latest capacity/aggregate projections. Required partial indexes cover CASH-only seed work and source/historical reads. Missing or orphaned projections fail closed. Clean restore supports V40 through the existing complete-world workflow; older worlds retain the pre-upgrade backup gate.

[A8-02-01 evidence](../verification/A8-02-01.md) distinguishes executed checks from source review. Next A8-02-02 consumes this manifest, coverage/clock, day counters and journal-ordered capacity history to implement immutable windows and the pure model. It must preserve source kinds, late-time attribution and original outcomes. It does not receive permission to rewrite posted observations or enable rates/traders.
