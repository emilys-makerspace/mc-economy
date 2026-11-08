# Owner history and captured transaction detail

A10-02-02,2026-11-08. [Handoff](../A10-02-02.md), [evidence](../verification/A10-02-02.md). Schema53 and banking wire10; public BankingIntegration2, simulation1 and valuation1 unchanged. The [A10-01 history design](a10-source-contracts.md) is installed with the atomic-maintenance refinement below. Preserve legacy `LedgerHistory.page` ascending exclusive world/account v1 cursors.

## Authority, query and publication

`BankingHistory` captures the live player/menu/session/revision and authenticated owner grant. `LedgerService.ownerHistory` captures committed journal high-water in an authorized worker read and yields each `OwnerHistory.scan` turn through the existing bounded `readAccounts` queue. Each turn rechecks owner/session authorization; immutable original facts below the high-water provide stable results across those transactions. No SQL/JDBC/future join runs on the server thread. History viewing does not advance financial time or mutate financial state.

Query is an owned account or all owned accounts, optional currency, normalized UTC `[fromInclusive,toExclusive)`, finite type and page size8 default/16 maximum. Closed-account identities remain eligible. Date entry is explicitly UTC and rejects invalid/reversed/overflow inputs; default has no date restriction. Filters use original recorded audit time, not synthetic financial-calendar days. Type is derived from original service/operation identity; categories include transfer, FX, cash, payment, credit, loan, collection and other. Basic ledger PAY is explicitly PAYMENT.

One movement is one owned original posting keyed descending `(sequence,position)`. Original transaction/operation/account, signed Money, recorded UTC, posting version and denomination revision remain attached. Same-owner opposing account legs are labeled internal movements and shown separately, never counted as new income or guessed counterparties. A page can split a transaction; detail follows the original transaction.

One weak menu state owns a stable random query generation, normalized query, committed high-water and at most16 visited page boundaries. Random server-retained action handles bind page/detail destinations to that generation and the current offered controls. Next/Previous/detail/Back retain the query; refresh/filter changes reset it. Short-lived request generation plus existing audience revision suppress obsolete callbacks independently. Close removes menu state. No raw client SQL/keyset offset or client-filter/reverse operation is accepted.

Account filter choices page16 retained owner identities plus lookahead, including closed accounts. Currency/type/page-size controls are finite server choices. UTC inputs are admitted only from their offered input page. Every action preserves current surface/menu admission. History is read-only on ATM/teller/kiosk/active-card surfaces, and absent from REGISTER/ADMIN/WORLD_SETUP.

## Additive storage and bounded work

Schema53 creates `owner_history` and `owner_history_state`. Projection rows store original world/transaction/operation/account/owner, sequence/position, audit epoch millis, currency/minor amount, denomination revision, posting decoder and original service/type/category. Eight indexes cover the owner/account/currency/type equality combinations followed by descending keyset. Date remains a bounded candidate predicate; LIMIT alone is not the work bound.

Each worker scan inspects at most256 indexed candidates and retains only page+lookahead. At most16 FIFO turns/4,096 candidates are allowed. A sparse query that cannot establish a complete page/end returns WORK_LIMIT with no published cursor advancement or partial successful page. Exact owner/closed-account admission remains authoritative even when a projection row exists. Readiness/coverage failure is distinct from an empty available result.

Startup validates/builds at most16 original transactions per initialization step, each at most64 original records. Construction frontier/target/readiness persist atomically with rows; reopening validates prior constructed sources again in bounded steps before continuing. Original posting versions1–4 retain their existing accepted representation; projection normalization does not rewrite them. Mismatched source/projection proof refuses readiness; no automatic original-history repair.

Refinement from A10-01: one general `JournalEngine` post-effect/post-outcome hook appends this transaction's projection rows in the same SQLite transaction, rather than introducing runtime catch-up or changing every financial handler. This keeps ready coverage synchronized with live committed high-water and avoids index work on menu open. Declined/unknown operations produce no fabricated posting rows. Projection failure rolls back the same transaction, preserving journal atomicity.

Schema/current-store validation, bounded startup source checks, poisoned-connection recovery and `OfflineInspector` validate the new schema/provenance. Complete-world backup/restore accepts schema53 and checks full source coverage. Old-world migration retains the existing stopped-world upgrade-backup requirement; worlds/identity/original records remain intact. Offline validation iterates bounded source pages while holding maintenance ownership, not during a menu read.

## Original detail and historical FX

`HistoryDetails.read` first proves an owned original posting in the requested transaction/world/high-water. It reads at most64 transaction records and exposes only up to16 owned movements, four FX captures and eight source references, with explicit continuation/unavailable flags. Raw canonical requests/outcome payloads and other owners' postings are not exposed. Transaction status is COMMITTED; pending/refused results belong to original-operation resolution.

Installed decoder families include FxSettlement facts1–3, original ServicePricing facts1–2, NPC/reference-NPC receipts and original reference quote/manifest/epoch, merchant invoice receipts with exact operation/invoice association, original credit/loan/debt institution snapshots, loan disbursement FX and original deposit/withdrawal facts. Cash COMMIT detail requires exact owner/bound-account/action association. Stored cash pricing supplies explicit fees; no-price legacy facts do not imply a zero fee. Multiple fee values/currencies without a single unambiguous fee remain UNAVAILABLE instead of being summed or converted.

Fee distinguishes KNOWN (including captured zero), NOT_RECORDED and UNAVAILABLE. Counterparty distinguishes explicitly captured owner-visible identity, private conflict and not recorded. Never infer an arbitrary balancing posting as counterparty or use current policy/prime/pricing to fill missing detail. Unsupported/missing specialized facts leave proven generic envelope/movement data and an explicit additional-detail limitation.

Captured FX resolves the original publication ID at the original exclusive source sequence, exact direction, ratio, fact version and effective instant. Missing source remains unavailable; current rates are never substituted. Shared FX settlement facts are associated by exact containing transaction; production FX admission owns both accounts. New cash parsing uses the captured publication component of the retained FX revision, never the current revision.

Historical FX is a separate informational view with explicit base/quote and EFFECTIVE/DAILY family. Existing daily and intraday cursor meanings stay separate. Additive `pageAt`/`intradayPageAt` readers accept an initial selected UTC day/instant while retaining the original frozen upper/cursor on continuation. Effective pages use retained effective snapshot IDs/record versions; daily pages retain original update IDs/versions, missing-day and currency-unavailable states. No retained publication is explicit. Rate history cannot authorize an exchange.

## Wire, screen and downstream limits

Paired banking registrar10 adds `BankingHistoryView` to Snapshot. All arrays/strings validate before decode allocation/publication; the existing total24KiB and12,000UTF-8-text limits remain, with exact history encoded size included. Old constructors remain source-compatible but wire9 peers are rejected. Canonical financial requests and saved pending intent versions are unchanged.

The screen uses server-offered row/detail controls, a persistent History/FX controls chooser, UTC input, scrollable original detail and Back. Authorized detail bypasses generic diagnostic UUID hiding so original transaction/operation references remain visible. Dashboard recent activity now reads the new owner index without changing balance semantics. Added English fallback keys cover this selected interaction; complete core localization and minimum-viewport/focus work remain later packages.

Verification is a focused real-SQLite/wire selection, one actual fresh local integrated History widget witness and stopped disposable backup/restore. No full suite, performance, dedicated/external multiplayer or process-crash atomicity claim. Keep these limits with later handoffs; no dependent package or compatibility integration is authorized by this delivery.
