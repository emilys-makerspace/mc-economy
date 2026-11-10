# A10 — Core integration, UI and hardening specification

Prepared 2026-11-08 at the user's “prep PhaseA10” request. **All eleven selectable A10 prompts and shared technical/runtime contracts approved 2026-11-08, subject to prerequisites. No package is selected, executed or verified.** Preserve existing code, worlds, backups and historical outcomes. The original [A10 design](../../../prompts/A10-core-integration/README.md) remains in scope; this specification fleshes it out without executing it.

## Baseline and dependencies

A1–A9 are prerequisites, accepted only in their recorded scopes. [A9-06](../../A9/A9-06.md) completes all eight A9 packages under schema V52. [A6 downstream](../../A6/architecture/a6-downstream.md), [A7 downstream](../../A7/architecture/a7-downstream.md), [A8 downstream](../../A8/architecture/a8-downstream.md) and [A9 read-only handoff](../../A9/architecture/a9-read-only-handoff.md) define authority/compatibility boundaries. Later implementation/source supersedes historical version numbers in earlier handoffs. Preparation baseline: BankingIntegration v2, EconomicSimulationIntegration v1, ItemValuationIntegration v1. A10-01 must pin current transport/API signatures.

Minecraft 1.21.1, NeoForge 21.1.252 and Java 21 remain fixed. Stage A owns money/FX/credit; custody owns shares/return obligations. Reuse the existing journal, storage worker, scheduler and owner grants; no parallel banking database. Core remains playable without stocks. No new financial product is selected. All third-party mod compatibility is outside A10 and belongs to separately selectable, separately distributed integration modules. No implementation package is selected.

## Coverage and package ownership

“Existing” below summarizes accepted mechanisms/design requirements; A10-01 must inventory exact current coverage rather than assume a missing capability.

| Feature | Existing behavior | Proposed A10 contract / owning package | Acceptance example | Later handoff |
| --- | --- | --- | --- | --- |
| F06 chooser/admin UI | Offered choices, terminal settings and permission-gated services | A10-02-01 dashboard/chooser/setup/account/world-currency journeys; A10-07 admin/action/audit map | Stale settings refuse without effects; consequential choice shows exact scope | Specialized admin/integration adapters separately selected |
| F18 banking/FX/history | Reviewed financial operations, retained versions and bounded history | A10-02-02 filtered frozen history/FX details, A10-02-03 localization; A10-03 readable controls | New current rate does not change a captured historical display; next page neither repeats nor skips under its contract | B-series products retain unavailable status |
| F19 core synchronization | Session/revision checks, original-operation recovery and protected custody | A10-04 dedicated two-player, delayed-response and reconnect acceptance | Old review after revision change refuses; committed original resolves without another debit/delivery | Arbitrary networks/platforms remain unclaimed |
| F20 stabilized API/admin | Future-based authenticated facades and audited operator paths | A10-06 immutable public types, lifecycle/thread/failure/version contracts; A10-07 permission/audit UX | Foreign owner denied; unavailable service stays typed; no DB/client type leaks | Separate integration mods and concrete event consumers |
| F23 core coverage | Scoped upstream client/backend/recovery evidence | A10-05 debt composite; A10-08 restore/operations; A10-09 qualified acceptance | Linked facility/obligation/case/payment IDs reconcile; restore uses a new disposable destination | Pending external or real-time witnesses remain explicit |

A10-01 owns shared source/interface checkpoint and acceptance plan. Main owns integration and sequential Gradle/game checks. Package authors own only explicitly assigned files; read-only review cannot mutate source. [Eleven-package queue](../../../prompts/A10-core-integration/EXECUTION.md) is deliberately sequential: checkpoint → dashboard/structured state → filtered history → localization → layouts → multiplayer → debt composite → API/events → administration → core restore/operations → acceptance/docs. A10-02 is a nonselectable overview of three children. Finish and hand off each before dependents. All eleven selectable prompts are approved subject to prerequisites; no package is selected.

## Shared proposed behavior and contract dimensions

Preserve current authenticated server-offered actions, fresh physical admission and quote consent. Client text, branch discovery and banker appearance do not authorize money. Bankers remain optional question-led guides; kiosks function without staff. Preserve accounts/debts through construction loss. Explain institution/native currency, activity-driven currency-prime/FX, eligibility, explicit source account/fees and automatic-payment consent using existing server data.

Item reference values remain informational, with retained source/config/recipe/provider/FX identity and full proof for durable readers. They do not authorize trade, collateral, wealth qualification or redemption. Keep unsupported trader listings disabled without player-facing diagnostic reasons as approved in A9. Detailed provenance remains in existing API/operator tools for A10. The user deferred a player-facing reference-value/provenance browser as an optional future feature requiring separate selection.

Each package must finalize these dimensions at A10-01 or before its implementation; a current linked contract can establish “unchanged” rather than duplicate it:

| Dimension | Proposed default / invariant | Owning packages |
| --- | --- | --- |
| Persistence/entities/config | No new entity/schema/config by default; preserve existing facts and original codecs | All; A10-06/08 compatibility review |
| Service/network/permissions | Existing owner grants, offered choices, session/menu/revision/reach and explicit operator audit | A10-02-01/02-02/04/06/07 |
| State/transaction/worker | Financial state/outcome in one existing journal transaction; separate physical witnesses; detached work on existing worker | A10-04/05/06 |
| Time/version/idempotency | Captured fixed terms and history; original outcome first; no elapsed-time fabrication or duplicate confirmation effects | A10-02-01/02-02/04/05 |
| Failure/rounding/overflow | Preserve exact money, bounded positive amounts, refusal on unrepresentable/unsupported work; never fall back to a different funding rail | All changed operations |
| Diagnostics/recovery | Typed bounded results, exclusive history cursors, owner isolation, conservative ambiguous custody; clean complete-world restore | A10-04/06/07/08 |

Native review/confirm must present actual server principal/currency/fees/rates/dates before settlement. Example: a historical fixed-loan prime capture remains unchanged after a current prime publication. A repeated original committed payment must return the original outcome, without another debit. A stale uncommitted quote must refuse before effects; an unavailable reference is never zero or a one-cent fallback. More exact numerical examples must use the current policies pinned by A10-01 rather than invented rates.

No new event bus, admin GUI, permission model, locator item, tutorial gate, remote service, catalog repricing or financial policy is approved here. Stabilize current API first; propose a new event only for a concrete core consumer and specify ordering/thread/failure/backpressure semantics. Specific compatibility adapters need explicit selection. B2 securities, B4 trading, B6 expanded lending and superflat issue #3 remain separate.

## Source review and known evidence

Paths below are under `src/main/java/ca/emilybelnavis/mcmods/economy/`.

- `client/BankingScreen.java`: fixed 316×236, contextual navigation, six-row choices and scrolling. `reviewed()`/`containerTick()` depend on English status substrings. Wording changes must preserve or deliberately replace that coupling without weakening confirmation.
- `client/BankerScreen.java`: fixed 376×224; answers wrap then stop at a fixed boundary without scrolling.
- `client/CollectionScreen.java`: fixed 340×230, retained partial-payment draft and fixed body/status limits without scrolling.
- `client/TraderScreen.java`: viewport-clamped dimensions and tooltips; description/status clipping and shrinking choices require a supported minimum viewport.
- Current service entry points include `foundation/CreditMenus.java`, `LoanMenus.java`, `AutopayMenus.java` and the banking/banker/collection/trader server menus. A10-01 traces exact network admission/signatures before changes.

These are source mechanisms/candidate concerns, not witnessed visual or financial defects. Review input/focus/scroll retention across resize and revision-driven rebuilds, especially delayed responses and stale reviews.

A6-13 already has GUI-scale-2 finance and native FX collection payment evidence. Native FX/subprime loan confirmation and continuous real-time origination-to-default-to-agent play retain explicit limits. A7 adds direct generated-kiosk account opening/same-save restart with bounded terrain/style evidence. A8 adds monetary-commerce/activity-to-rates evidence; A9-06 adds native reference-priced checkout/read-only history. Reuse unchanged results, not every upstream scenario.

Relevant existing opt-in probes under `src/inventoryProbe/java/ca/emilybelnavis/mcmods/economy/probe/` include FinanceAcceptanceProbe, BankerProbe, CollectionAcceptanceProbe, VillageBankAcceptanceProbe, SimulationProductionProbe and ReferenceTraderProbe. Their existence is not A10 acceptance or launch authorization.

## Approved design decisions

The user selected **redesign** for banking navigation: a unified banking home dashboard within the existing kiosk/menu, organizing accounts, payments, FX, credit, loans and history according to current authorized capabilities. Include institution/currency context and contextual help. Preserve direct kiosk access, optional banker questions and existing server authority/confirmation. Exact layout and information hierarchy remain design work. This approves the navigation direction only; no implementation package is selected.

The user approved **one History section** with account/currency filters, transaction details and historical FX views. Preserve authenticated owner scope, captured historical values/versions and bounded exclusive pagination. Exact layout remains design work; this selects no implementation package.

The user **deferred the player-facing reference-value/provenance browser** as an optional future feature. It is outside A10; existing trade reviews and authenticated API/operator diagnostics remain in scope. No future implementation is authorized by retaining the option.

The user approved **British English (`en_gb`), Canadian French (`fr_ca`) and French for France (`fr_fr`)** for A10 localization. Cover redesigned text and the existing core banking journeys in A10 scope, with translation keys and region-appropriate wording. Preserve exact amounts/currency identity and financial meaning; translated labels/statuses must not govern authorization or confirmation state. Include French text expansion and accents in bounded layout review. This is a design decision, not implementation selection.

The user has **no specific resolution or GUI-scale requirements**. Establish the supported minimum usable logical viewport and representative scales during layout work; provide resizable screens, scrolling where needed and keyboard navigation. Exact dimensions are an engineering choice to document and verify, not a pending user preference.

The user approved **two-player testing on a disposable dedicated server**, including delayed responses and disconnect/reconnect recovery. Actual external-player testing may remain explicitly pending if participants are unavailable. Record the actual topology; local sessions do not establish external multiplayer coverage.

The user approved **a controlled accelerated fixture for the complete borrowing-to-default-to-collection-to-payment flow**. Exercise the linked facility through production services with fixture-only time control; leave production clocks/deadlines unchanged and label the evidence accelerated. An unaccelerated playthrough is optional later evidence, not an A10 completion requirement. Pre-seeded cases alone do not establish the complete linked flow.

The user approved **preserving existing API integrations through additive changes and versioned interfaces**. Identify any unavoidable breaking change, affected consumers and migration path before implementation. Event scope is settled separately below.

The user approved **documenting existing integration hooks and adding events only for a concrete integration need**. No general event bus or third-party adapter implementation is selected in A10; future integration modules consume stable core APIs and motivate only concrete extension hooks. Any added event must specify thread, ordering, failure/backpressure and idempotency semantics.

The user approved **improving existing administration settings and commands** for A10. A dedicated admin GUI is flagged as an optional future feature outside A10 requiring separate selection. Preserve current permission checks, reviewed mutation scope and audit paths.

The user selected **up to 20 concurrent players** as A10's performance target. Define representative mixed core banking/trading and background economic activity, hardware/storage baseline and measurable tick/latency/persistence budgets before claiming verified support. Record actual connected-client counts separately from simulated service workload. Player count is a target, not existing performance evidence; concrete workload/budget defaults are specified below and still require source-feasibility revalidation and measurement.

The user approved **clearly labeled partial acceptance when actual external-player testing is unavailable**, provided the selected implementation and available required checks pass. Name the missing evidence and reserve full verification until it is obtained. This exception does not waive required implementation or failing available checks; the unaccelerated debt playthrough is separately optional later evidence.

## Standalone integration modules — revised scope

The user removed **all mod compatibility from A10** and requested separately installable modules to reduce core component loading. [Module plan](../../../prompts/integrations/README.md) preserves Sophisticated, AE2 and extension requirements, complete item values and the backpack wallet outside A10. Core Economy contains only its public integration API; optional integration JARs own adapter code, third-party types, registrations, item data/assets and target-mod development runtimes. Uninstalled module code must not be scanned/registered/loaded by the core. No bundled integration monolith or mandatory dependency on all extensions.

A10 does not implement compatibility adapters, mod-item catalogs, backpack/ME inventory access or the wallet. Its 20-player workload and completion gates cover core behavior without those modules. Module implementation has its own independently selected packages and scoped checks; retained feature requirements are not discarded by this scope change. No runtime modularization or loading improvement is claimed yet because this work updates planning only.

The user approved **dashboard contents**: current institution/currency, account balances separated by currency, credit/loan summary and upcoming payments, recent transactions, and navigation to Accounts, Payments, FX, Credit, Loans and History. Use authenticated bounded server reads; financial actions open separate review/confirmation screens. Exact layout remains design work. No implicit combined-currency total or new restricted-service access is selected.

The user approved **History details**: newest-first entries; account, currency, date-range and transaction-type filters; a transaction detail view showing amount, fees, counterparty, status and captured FX where applicable. Historical FX rates have a separate view within History. Retain owner scope, frozen historical facts and bounded exclusive pagination; reset query pagination when filters change and specify exact filter/cursor semantics before implementation.

The user approved **operation status and recovery messages**: Processing waits; Completed shows a receipt; Declined explains why and what can change; Expired/stale requests fresh review; Outcome uncertain checks the original operation before offering another attempt. Do not automatically resubmit financial actions. Preserve original identity, use structured server state for action availability and keep uncertainty distinct from refusal. This UI policy leaves separately consented automatic-payment backend semantics unchanged.

The user has **no intended server hardware**. Establish and document an available reference machine during technical planning, including CPU, memory/JVM allocation, OS, Java version and storage, together with workload and measured results. The 20-player target is unchanged; this does not establish universal hardware requirements or verified capacity. Concrete workload/budget defaults are specified below; actual setup and measurements remain pending.

## Decision record and engineering defaults

User choices below remain authoritative. [Technical revision1](../../../prompts/A10-core-integration/TECHNICAL.md) and [runtime plan](../../../prompts/A10-core-integration/RUNTIME_PLAN.md) now supply concrete engineering defaults; these are unmeasured design targets, not completed runtime evidence. The user approved these design contracts; approval does not select implementation.

| Decision | Recommended proposal | Alternative / consequence |
| --- | --- | --- |
| Navigation scope — approved | Redesign the banking home screen as a unified dashboard within the existing kiosk/menu | Exact layout/information hierarchy remains design work; no mandatory tutorial or new remote capability |
| History organization — approved | One History section with account/currency filters, transaction details and historical FX views | Preserve captured historical values, owner scope and bounded pagination; exact layout remains design work |
| Provenance audience — decided | Keep detailed provenance in current API/operator tools for A10 | Player-facing reference-value/provenance browser deferred as an optional future feature requiring separate selection; unsupported listings remain quiet |
| API compatibility — approved | Preserve existing integrations through additive changes and versioned interfaces | Identify unavoidable breaking changes, affected consumers and migration path before implementation |
| Events — approved | Document existing hooks; add events only for a concrete integration need | No general event bus or specific integration mod selected; specify semantics for any added event |
| Admin surface — approved | Improve existing settings and commands | Dedicated admin GUI flagged as an optional future feature outside A10 requiring separate selection |
| Localization — approved | British English (`en_gb`), Canadian French (`fr_ca`), French for France (`fr_fr`) | Translate A10 core UI/help/review text and verify text expansion; preserve financial semantics |
| GUI matrix — direction settled | No user-specific resolution/scale requirement; establish minimum logical viewport and representative scales during layout work, with resizing, scrolling and keyboard navigation | Exact dimensions remain an engineering choice; document and verify the supported range |
| Network acceptance — approved | Two distinct authenticated players on a disposable dedicated server, including delayed responses and disconnect/reconnect recovery | Actual external-player testing may remain explicitly pending if participants are unavailable; record actual topology |
| Delay method — engineering default | Fixture-local150ms each direction with deterministic±25ms jitter for60seconds, bounded buffers and measured timings | No host-wide network settings; distinguish actual network from application delay |
| Debt chronology — approved | Controlled accelerated fixture for the complete linked borrowing/default/collection/payment flow; production clocks/deadlines unchanged | Explicitly label accelerated evidence; an unaccelerated playthrough is optional later evidence, not an A10 completion requirement |
| Phase acceptance — approved | Partial acceptance allowed when actual external-player testing is unavailable and selected implementation/available required checks pass | Record missing evidence; full verification waits for it; no waiver for missing implementation or failing available checks |
| Operational target — approved | Up to 20 concurrent players; document existing bounds and define/measure representative mixed activity | Specify workload, hardware/storage baseline and budgets; distinguish actual clients from simulated workload before claiming support |

Open environment questions for A10-01: which actual window/logical sizes/scales are available, whether two authenticated and external clients are available, and how fixture-only time control will exercise the original debt boundaries. An unaccelerated playthrough is optional later evidence. No invitations/messages or external purchases are authorized. A10 can be partially accepted only with missing criteria named.

## Approved technical planning

The user requested completion of the technical contracts/drafts and then approved all eleven selectable prompts and their shared contracts on 2026-11-08, subject to prerequisites. [Technical revision1](../../../prompts/A10-core-integration/TECHNICAL.md) defines bounded dashboard DTOs, server-owned operation states, planned banking transport revision9 (revalidate next unused version), frozen newest-first query/cursor/detail contracts, historical FX-family separation and API/localization/authority boundaries. [Source map](../../../prompts/A10-core-integration/SOURCE_MAP.md) records actual banking8/other-menu1, ascending legacy history and missing structured/provenance fields; it does not establish implementation of new types.

[Runtime plan](../../../prompts/A10-core-integration/RUNTIME_PLAN.md) supplies 320×240 minimum logical viewport and representative scale/locale cases, two actual dedicated-server sessions and bounded fixture-local delay, one accelerated linked debt chain, and one20-actor2-actions/s core sample with120s warmup/300s measurement/60s recovery plus a20-request burst. Reference environment nominated by read-only inventory: Apple M5 Pro,18 reported CPU cores,24GB,macOS27.0.1. Confirm actual Java21/JVM/storage/display/client resources at setup; this is not a universal hardware minimum or a measured capacity result.

Initial reference budgets: server tick p95≤50ms/p99≤100ms, owner reads p95≤500ms/p99≤1s, terminal financial outcomes p95≤1s/p99≤2s, burst recovery≤5s and idle queue drain≤10s; preserve existing30s graceful drain. Sample counts/queue age/refusals/integrity and actual background event presence must be reported. Twenty synthetic service actors do not prove20real socket clients. Missing required local evidence/implementation or failed checks cannot use the external-player-only partial-acceptance exception.

[Eleven approved selectable packages](../../../prompts/A10-core-integration/EXECUTION.md) replace the oversized A10-02 with A10-02-01 dashboard/status, A10-02-02 history and A10-02-03 localization; A10-02 is overview-only. Remaining work is source-feasibility revalidation/implementation and setup-time resources, not an outstanding routine gameplay preference round. Next selection remains A10-01; this drafting does not execute it.

## Verification and completion

Every package/delegate inherits [VERIFICATION.md](../../VERIFICATION.md). Planning uses diff/source/link review only; no Gradle/tests/game. Ordinary changes use at most one focused named-test invocation when useful for changed behavior. No automatic full-suite, stress suite or completion gate. Prefer current real-service/SQLite fixtures and unchanged evidence.

Runnable delivery uses Java 21 `./gradlew build -x test`, `./gradlew help --task runClient` and `./gradlew runClient --dry-run` once. Keep client/server tasks, registered main mod and required runtime dependencies. Main owns sequential Gradle/game checks; necessary witnesses use disposable worlds only. Never accept a new EULA or kill a user's game. Clean shutdown/reopen is not crash evidence, accelerated clocks are not real-time, and loopback is not external multiplayer.

Each package writes its short handoff/evidence, changed contracts, actual checks and limitations. A10-09 reconciles account/ledger/issuance and gameplay evidence, updates user-facing guidance, and changes the root changelog only for delivered user-facing changes. It must label phase acceptance partial if selected criteria remain pending. Do not automatically start B-series work.
