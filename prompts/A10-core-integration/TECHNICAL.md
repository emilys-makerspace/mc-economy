# A10 technical contract — revision 1

Status: technical revision1 approved 2026-11-08 with all A10 prompts, subject to prerequisites. No implementation package is selected/executed by this document. [Source map](SOURCE_MAP.md) distinguishes existing behavior from additions below; [runtime plan](RUNTIME_PLAN.md) defines engineering workloads/budgets and resource caveats. Exact source feasibility is revalidated when A10-01 is selected.

## Scope, ownership and compatibility

Keep Minecraft 1.21.1, NeoForge 21.1.252 and Java 21. Core banking, history, synchronization, public API, admin guidance, recovery and 20-player operations are in scope. All third-party compatibility, mod-item catalogs, external inventory/network access and the wallet are outside A10 in separately installable integration modules. No adapter classes, target-mod runtimes or module acceptance requirements enter this phase.

Main owns shared interfaces, integration and sequential Gradle/game checks. Each package gives workers disjoint file ownership; no source/API change occurs during this drafting. A10-01 records exact consumer signatures and any necessary additive migration/protocol design before code. A10-02 is an overview, not selectable: its dashboard/status, history and localization children are separately selectable in [EXECUTION.md](EXECUTION.md).

## Server/worker/presentation boundary

Capture fresh player instance, world, owner grant, physical access, menu/session/revision and offered actions on the server thread. Dispatch immutable detached observations through existing bounded storage/lifecycle services. Recheck relevant admission on mutation and before publishing results to a still-current audience. UI reads must not advance financial time, recalculate scores, originate accounts/products or change journal state.

Compose each dashboard projection in one bounded worker read transaction so its section values share a consistent read view. New projection DTOs are immutable and contain bounded primitives/IDs, exact money values, typed availability and source references. Screens render authoritative data and choose only offered actions. No SQL callback, live entity/container, storage worker or mutable grant crosses into a public read DTO. An asynchronous completion does not prove the original menu/player session still exists; drop stale audience publication without undoing committed effects.

## Planned dashboard DTO and navigation

Introduce a bounded owner-scoped dashboard projection alongside existing readers; do not reinterpret an old snapshot as the new layout. Logical fields: world/menu/session/revision; capture time; institution/native currency/access context; account rows; credit/loan summary; upcoming obligations; recent activity; offered destinations; per-section availability and continuation flags.

- Account balances stay separate by account/currency; do not add an implicit FX-converted total. Retain existing account/product caps. Page if an existing lower service bound cannot be represented in a single snapshot.
- Initial dashboard budget: up to four bank-account rows, four upcoming-obligation rows and four recent-activity rows. Summary sections disclose truncation and link to the owning service/history page. Do not scan an unbounded portfolio to fill a summary.
- Destinations: Accounts, Payments, FX, Credit, Loans and History, only as offered by the current surface/policy. World denomination setup and no-account opening retain their separate permissions/readiness; no auto-opening/setup on viewing Home.
- Current institution/currency identifies physical service context, not ownership of every displayed account. Do not silently restrict legitimate owner history or broaden a kiosk's permitted mutations.
- Amount entry → server review → explicit confirmation → outcome/receipt remains the financial flow. Dashboard navigation alone cannot submit a mutation. Back preserves harmless draft input where appropriate, but invalidates stale reviews and never revives an old capability.
- Per-section unavailability is visible and distinct from zero balance or no debt. Capture time identifies the projection; it is not a promise of settlement readiness. Refresh is explicit or driven by a completed current request; no per-frame storage polling.

## Structured operation state

Add a transport-safe enum with READY, REVIEW, PROCESSING, COMPLETED, DECLINED, EXPIRED_STALE and OUTCOME_UNCERTAIN. READY is an editing/navigation state; the other names implement the user's status policy. State is server-owned. Add structured reason/message key plus typed display arguments and optional original operation/request/receipt references. Keep financial completion separate from physical delivery/claim/quarantine status.

| State | Offered behavior |
| --- | --- |
| READY | Edit inputs or navigate permitted services; no implicit financial submit |
| REVIEW | Render captured principal/source/currency/fees/rates/expiry and explicitly offered confirm/cancel |
| PROCESSING | Wait for the submitted original; no second financial submit |
| COMPLETED | Show original receipt and separately available delivery/claim actions |
| DECLINED | Explain the typed refusal and permitted correction; a corrected financial action requires new review |
| EXPIRED_STALE | Refresh/review current terms; never revive the old confirmation |
| OUTCOME_UNCERTAIN | Lookup original operation first; offer no replacement submit until authoritative resolution |

No button eligibility or confirmation/retry behavior may depend on English/French status text. Client timeout/disconnect is not evidence of refusal or absence. Original committed outcomes replay without another debit/delivery; confirmed absence requires a fresh reviewed request, not auto-resubmission. Keep canonical request bytes and existing persisted pending-intent identity unchanged unless a separately specified version is genuinely needed. Existing backend autopay consent/retry semantics are unchanged.

## Banking wire changes and bounds

The current banking registrar is version 8. Plan the next banking transport revision (9 if still unused at A10-01) for structured state and typed dashboard/history data. Preserve IDs/canonical financial service requests and persisted decoders; update paired client/server codecs together and reject incompatible wire versions explicitly. Do not silently append fields under an unchanged codec version.

Reuse existing action text/rate bounds and menu admission. Current banking text has at most 32 lines and 12,000 text bytes; new page rendering must remain bounded rather than emit an entire history/portfolio. Budget encoded banking snapshots to at most 24 KiB total, including DTO arrays and choices; bound every collection/string before encoding/decoding and refuse/mark unavailable rather than truncate financial review terms. Keep the lower existing page/choice/service bound where applicable. Typed screen pages should page before the payload budget is exceeded.

Separate short-lived request/publication generation from stable history-query generation. Each new page/detail request invalidates obsolete in-flight publication, while Next/Previous/detail/Back retain the active query generation, filters, high-water and bounded page boundaries. Filter/owner/menu-session changes reset the query generation and its cursor audience. At most one in-flight financial mutation per menu/owner pending pipeline as already enforced. Preserve existing storage queue/backpressure rather than add an independent executor or a second queue that bypasses admission.

## History query and frozen pagination

Do not alter legacy LedgerHistory.page's ascending/world-account cursor meaning. Add a newest-first owner history query and immutable page/detail projection. Planned query fields: owned account selection or all permitted owner accounts, optional currency, UTC date range, finite server-offered transaction-type filter and page size. Default page size 8; maximum 16 rows, subject to existing lower service limits. UI date labels explicitly identify UTC recorded timestamps; these are not the synthetic 20-minute financial calendar's dates.

Normalize date filters to [fromInclusiveMillis,toExclusiveMillis); reject reversed/out-of-range/overflow values. Default is no date restriction, not an unbounded row scan. Filtering/paging uses indexed bounded worker queries; filtering a truncated old page on the client or reading all old pages to reverse them is not acceptable.

Capture a committed journal high-water sequence on the initial query. Order descending by immutable journal sequence and stable record/entry position. Retain the high-water across pages so new commits appear only after explicit refresh. Bind an opaque server-retained cursor to world/owner, normalized filter query, high-water, last exclusive key and current menu/session plus stable history-query generation. Changing filters/owner/session resets it. Bound query state to one active query and 16 visited page boundaries per menu; on exhaustion offer a new query/refined filter, not unbounded retained history. Previous navigation may use those retained page boundaries; do not offset-scan arbitrary history.

A transaction can have several postings. Use stable transaction identity and explicit owner-account movement rows; an all-account page must disclose source/destination legs, without portraying an internal transfer as net new income. Specify row grouping/provenance in A10-01, reusing original transaction/account keys; never guess a counterparty from an arbitrary opposing ledger posting.

Detail fields: original transaction/operation identity, recorded timestamp, relevant account/currency amounts, status, fee and counterparty where captured/owner-visible, captured FX where applicable, and original provenance/version references. Distinguish zero fee from unknown/not recorded and unavailable/private counterparty from an invented label. Do not use current FX/prime/config to reconstruct missing historical terms. In-progress/refused operations belong to original-operation status unless a retained authoritative history source actually supplies such outcomes; no fabricated declined transaction rows.

An additive read index/projection may be needed for date/type/provenance filtering. First reuse existing indexed facts and decoders. If a new index/projection is required, A10-01 specifies its additive schema/bootstrap, original-fact provenance, query bounds and startup/backup validator integration. Never rewrite original ledger facts, alter legacy cursors or silently scan all history on menu open. Unsupported historical detail is explicit; missing proof never becomes a made-up value.

## Historical FX view

Keep legacy daily pair publications and later exact effective-time publications distinguishable. Separate FX-rate view within History: explicit base/quote currencies, publication family, selected time/day, exact captured ratio/version/effective instant and exclusive bounded pagination. Use the applicable existing reader and retained FX identities. A transaction detail follows its captured FX ID; a rate-history view is not a tradable quote. Default to the current effective publication family, with explicit legacy-history selection where available. Never reinterpret an old daily cursor as an intraday cursor.

## Localization and layout

A10-02-03 owns complete covered core strings for en_gb, fr_ca and fr_fr, including dashboard/history/review/status/help and terminal/account/world-denomination journeys. Use keys with typed arguments, separate grammatical translations and region-appropriate text; no English status parsing or translated request/action IDs. Keep existing fallback behavior for untouched legacy locales. Currency codes, integer money, FX ratios, original identities and canonical request bytes remain unchanged.

Format money using the authoritative currency minor units and a consistent locale-aware display while retaining explicit currency code in reviews. Parsing uses a defined displayed input convention and server validation; translation cannot change accepted numeric meaning. Reject ambiguous amounts rather than silently alter magnitude. Amounts/dates/fees in server-derived terms retain exact provenance.

A10-03 applies the bounded viewport/focus/scroll matrix in RUNTIME_PLAN.md to BankingScreen, BankerScreen, CollectionScreen and TraderScreen. Long terms and offered actions stay reachable; no clipped required term concealed behind an enabled confirm. Keep a stable content region and persistent navigation/action region, with scrolling for long content. Resize preserves harmless drafts/selection while any revision change invalidates financial review. No automatic remote bank access, tutorial gate, unsupported product or player item-value browser is added.

## API, admin and recovery

A10-06 inventories existing public facade signatures and exposed types. Preserve legacy consumers through additive/versioned API-only projections; document remaining legacy domain-type exposure instead of deleting signatures. New public read types must not require client/database implementation classes. Document server-thread grant capture, asynchronous completion, audience/session revalidation, cancellation and lifecycle availability. Add no general event bus; an event needs an identified concrete consumer and specified delivery semantics.

A10-07 improves existing settings/commands and audited operator paths, not a new admin GUI or permission model. Use current owner/operator distinction, bounded redacted diagnostics and exact mutation scope/review. Inspection never advances financial time or compensates uncertain physical effects. No new monetary policy or hidden operator bypass.

A10-08 documents current stopped-world upgrade/backup/restore, validator/readiness and clean shutdown requirements. Retain schema 52 meaning and prior validators; a newer schema only follows an affected package's explicit additive design. No player-world fixture, EULA acceptance, online backup feature, automatic cash restoration or unsolicited migration is introduced.

## Runtime evidence and completion

Use RUNTIME_PLAN.md for finite workloads/metrics/viewport cases and dedicated/accelerated evidence. Existing A6–A9 evidence remains valid for unchanged behavior. Synthetic service actors do not prove 20 live network clients; local clients do not prove an external connection; accelerated chronology does not prove ordinary elapsed gameplay. Record actual limits.

Partial acceptance is allowed when actual external-player evidence is unavailable, if selected implementation and available required checks pass. A missing local second-player resource is a local witness blocker, not automatically an allowed external-only waiver. Unimplemented features, failed required checks or unresolved financial invariants stay incomplete. No full-phase verified label while required evidence is missing. An unaccelerated debt playthrough remains optional later work.

All packages/subtasks/delegates inherit [VERIFICATION.md](../../.codex/VERIFICATION.md). This finalized draft uses source/diff/link review only. No Gradle/tests/game during drafting. Later code checks are at most one focused named-test invocation when useful, with existing real-service/SQLite fixtures and no automatic full-suite/combination/completion gate. Runnable delivery runs build excluding tests and client task help/dry-run once, preserving mod/source registration, development runtime and working client/server tasks. Main alone runs Gradle/game sequentially; necessary runtime fixtures are disposable.
