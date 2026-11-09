# Current project state

2026-11-09 — Direct ATM/kiosk changes deliver bank-branded cream screens, bank-scoped accounts/history and bills, kiosk cash services and physical-cash closure. [Current contract](architecture/terminal-ui.md) and [checks/limits](verification/terminal-ui.md) supersede earlier dense Home/footer descriptions; no phase status changes.

**A1 foundation is complete and verified.** SQLite schema V53 (minimum V1); the initial reset is complete. A2-01 through A2-03-03 are complete and verified. A3-01 through A3-05 are verified. A4-01 through A4-06 are verified. A5-01-01 through A5-02-03 and A5-03 through A5-09 are verified. A6’s 23 prompts and policies are approved. [A6-01](A6-01.md) is complete as a document/source-review checkpoint. [A6-02-01](A6-02-01.md) scheduling/configuration, [A6-02-02](A6-02-02.md) diagnostics and [A6-02-03](A6-02-03.md) calendar foundation acceptance are verified. [A6-03-01](A6-03-01.md) CHF currency/cash and [A6-03](A6-03.md) institution/accounts are verified in their recorded scopes. [A6-04](A6-04.md) wealth qualification, pricing and funded yield are verified; live branch adapters are delivered by A6-05-01; fixed loan APIs are delivered by A6-09-01 with new exposure controlled by accepted reporting readiness; securities trading remains unimplemented. [Shared contract](../prompts/A6-credit-debt/CONTRACT.md); [approval and next-package queue](../prompts/A6-credit-debt/EXECUTION.md). Other later phases await explicit invocation.

| Package | Prerequisite | Status |
| --- | --- | --- |
| [A1-01](A1-01.md) | Design checkpoint | Verified |
| [A1-02](A1-02.md) | A1-01 | Verified |
| [A1-03](A1-03.md) | A1-02 | Verified |
| [A1-04](A1-04.md) | A1-02, A1-03 | Verified |
| [A1-05-01](A1-05-01.md) scheduling/configuration | A1-04 | Verified |
| [A1-05-02](A1-05-02.md) diagnostics | A1-05-01 | Verified |
| [A1-05-03](A1-05-03.md) foundation acceptance | A1-05-02 | Verified |
| [A2-01](A2-01.md) currency registry and monetary types | A1-05-03 | Verified |
| [A2-02](A2-02.md) conversion and settlement rounding | A2-01 | Verified |
| [A2-03-01](A2-03-01.md) daily FX scheduling and configuration | A2-02 | Verified |
| [A2-03-02](A2-03-02.md) FX diagnostics | A2-03-01 | Verified |
| [A2-03-03](A2-03-03.md) FX foundation acceptance | A2-03-02 | Verified |
| [A2-04](A2-04.md) versioned quotes and pair history | A2-03-03 | Verified |
| [A2-05](A2-05.md) world denomination and change protocol | A2-04 | Accepted |
| [A3-01](A3-01.md) parties and authorization | A1 | Verified |
| [A3-02](A3-02.md) account catalog and products | A3-01, A2 registry and denomination read | Verified |
| [A3-03](A3-03.md) account lifecycle and limits | A3-02 | Verified |
| [A3-04](A3-04.md) account queries and funds | A3-03, A2 monetary types | Verified |
| [A3-05](A3-05.md) account rebase planning | A3-04, A2-05 confirmation contract | Verified |
| [A4-06](A4-06.md) history and public contracts | A4-05, A3 queries | Verified, including live commands |
| [A5-01-01](A5-01-01.md) merchant payment foundation | A4-06 | Verified |
| [A5-01-02](A5-01-02.md) payment diagnostics | A5-01-01 | Verified |
| [A5-01-03](A5-01-03.md) payment foundation acceptance | A5-01-02 | Verified |
| [A5-02-01](A5-02-01.md) custody foundation | A5-01-03 | Verified |
| [A5-02-02](A5-02-02.md) custody diagnostics | A5-02-01 | Verified |
| [A5-02-03](A5-02-03.md) custody foundation acceptance | A5-02-02 | Verified |
| [A5-03](A5-03.md) cash and cards | A5-02-03 | Verified |
| [A5-04](A5-04.md) terminal policy | A5-03 | Verified |
| [A5-05](A5-05.md) deposits | A5-04 | Verified |
| [A5-06](A5-06.md) withdrawals | A5-05 | Verified |
| [A5-07](A5-07.md) register sales | A5-06 | Verified |
| [A5-08](A5-08.md) physical terminals and assets | A5-07 | Verified |
| [A5-09](A5-09.md) menus and phase acceptance | A5-08 | Verified |
| [A6-01](A6-01.md) shared contract/policy checkpoint | Verified A1–A5 | Complete: documents/source review only |
| [A6-02-01](A6-02-01.md) financial calendar scheduling/configuration | A6-01 | Verified |
| [A6-02-02](A6-02-02.md) financial calendar diagnostics | A6-02-01 | Verified |
| [A6-02-03](A6-02-03.md) financial calendar foundation acceptance | A6-02-02 | Accepted |
| [A6-03-01](A6-03-01.md) CHF currency, notes and coins | A6-01 | Verified |
| [A6-03](A6-03.md) institution catalog/accounts | A6-01, A6-02-03, A6-03-01 | Verified; live kiosks deferred |
| [A6-04](A6-04.md) wealth qualification, pricing and funded yield | A6-03 | Verified |
| [A6-05](A6-05.md) original banking artwork | A6-01, A6-03 | Verified; resources only |
| [A6-05-01](A6-05-01.md) branded hardware and branch admission | A6-03, A6-05 | Verified |
| [A6-06](A6-06.md) issued debit cards and lifecycle | A6-03, A6-05, A6-05-01 | Verified; no credit facility |
| [A6-07](A6-07.md) credit profiles and score policy | A6-01, A6-02-03, A6-03 | Verified; recurring scoring dormant |
| [A6-08-01](A6-08-01.md) credit facility foundation | A6-04, A6-06, A6-07 | Verified in recorded API scope; lifecycle activation pending |
| [A6-08-02](A6-08-02.md) credit diagnostics | A6-08-01 | Verified; owner views and audited bounded inspection |
| [A6-08-03](A6-08-03.md) credit foundation acceptance | A6-08-02 | Accepted foundation; new production exposure gated pending lifecycle |
| [A6-09-01](A6-09-01.md) fixed loan foundation | A6-04, A6-07 | Verified API foundation; production origination gated pending lifecycle |
| [A6-09-02](A6-09-02.md) loan diagnostics | A6-09-01 | Verified; bounded owner/operator reads and audited inspection |
| [A6-09-03](A6-09-03.md) loan foundation acceptance | A6-09-02 | Accepted gated foundation with bounded accounting and lifecycle handoff |
| [A6-10-01](A6-10-01.md) shared debt lifecycle | A6-08-03, A6-09-03 | Verified foundation; legacy timelines require recorded reconciliation |
| [A6-10-02](A6-10-02.md) debt diagnostics | A6-10-01 | Verified bounded owner history and audited operator explanations |
| [A6-10-03](A6-10-03.md) lifecycle/reporting acceptance | A6-10-02 | Accepted current/proven histories with production scoring and bounded reconciliation |
| [A6-11](A6-11.md) Collection Agent | A6-05, A6-10-03 | Verified entity, durable cooldowns and private payment dialogue |
| [A6-12](A6-12.md) bank/credit menus and integration | A6-05-01, A6-06, A6-08-03, A6-09-03, A6-10-03, A6-11 | Verified in recorded scope with atomic credit use and version-one API |
| [A6-13](A6-13.md) integrated acceptance | A6-12 | Accepted with D15 automatic payments, scoped recovery and actual client/server evidence |

A6 execution preparation: [approval and package queue](../prompts/A6-credit-debt/EXECUTION.md). A6-01 through A6-02-03 are complete in their recorded scopes. CHF A6-03-01 and institution/accounts A6-03 are also verified. [A6-05](A6-05.md) original assets are verified. [A6-05-01](A6-05-01.md) physical hardware and live branch admission are verified. [A6-06](A6-06.md) issued debit cards are verified. [A6-07](A6-07.md) credit profiles and score policy are verified with recurring scoring dormant. [A6-09-01](A6-09-01.md) fixed loan foundation is verified with production origination gated. [A6-09-02](A6-09-02.md) bounded loan diagnostics is verified. [A6-09-03](A6-09-03.md) accepts the gated loan foundation. [A6-10-01](A6-10-01.md) shared debt lifecycle foundation is verified in its recorded scope, with historical timeline migration explicitly constrained. [A6-10-02](A6-10-02.md) debt diagnostics is verified. [A6-10-03](A6-10-03.md) accepts the installed reporting/scoring dependency for current and provenance-complete histories. Unsupported historical defaults or missing tracked obligations retain precise readiness rejections. [A6-11](A6-11.md) delivers Collection Agent gameplay and protected payment dialogue. [A6-12](A6-12.md) delivers bank/credit/loan/collection menus, atomic credit purchases, advances and version-one server integration. [A6-13](A6-13.md) records completed integrated acceptance with approved D15 automatic payments. Later packages await explicit selection and their prerequisite evidence. Approval is not implementation or verification.

Load only the affected contracts: [lifecycle](architecture/lifecycle.md), [storage/migrations](architecture/storage.md), [journal](architecture/journal.md), [recovery/clock/backup](architecture/recovery-clock.md), [FX](architecture/fx.md), [identity](architecture/identity.md), [accounts](architecture/accounts.md). [custody](architecture/custody.md), [cash](architecture/cash.md), [terminals](architecture/terminals.md), [deposits](architecture/deposits.md), [withdrawals](architecture/withdrawals.md), [registers](architecture/registers.md), [banking menus](architecture/banking.md). [financial calendar](architecture/financial-calendar.md), [CHF integration](architecture/chf.md). [Verification guide](VERIFICATION.md) selects checks; historical notes describe the delivered state at the time and do not override current contracts.

Use [the execution workflow](WORKFLOW.md) for package boundaries, handoffs and delegation. Only the user-selected prompt authorizes package execution.

Current institution contract: [catalog, funding and provider admission](architecture/institutions.md). A6-13 acceptance is complete with approved D15 automatic payments. See [automatic-payment contracts](architecture/autopay.md) and the recorded broader-playtesting limits (now planned under A10).

Current wealth/pricing/yield contract: [authoritative qualification and funded servicing](architecture/wealth-pricing.md).

Current CAD-based banking fees: every credit cash advance finances a CAD 3.00 base issuer fee; other-bank/unassigned ATM use adds a CAD 2.50 base surcharge, with teller/same-bank exemptions. Convert at the captured current FX publication and round to nearest 0.25 target major unit or 25 JPY (HALF_UP ties). Reviewed fees settle atomically and replay safely. See [fee evidence](verification/banking-fees.md), [credit integration](architecture/banking-integration.md) and [withdrawal contract](architecture/withdrawals.md).

Original banking resource contract: [art inventory and bindings](architecture/banking-art.md).

Current hardware and live branch contract: [bank binding and kiosk sessions](architecture/branch-hardware.md).

Current issued-token contract: [debit lifecycle, fees and protected delivery](architecture/issued-cards.md).

Current credit policy/profile contract: [deterministic score, starter and underwriting inputs](architecture/credit-profiles.md).

Current funded credit contract: [facilities, reservations, statements and repayment](architecture/credit-facilities.md).

Current credit diagnostic contract: [owner statements and audited servicing inspection](architecture/credit-diagnostics.md).

Current credit acceptance and downstream source: [safety gate, immutable events and recovery](architecture/credit-acceptance.md).

Current fixed loan contract: [underwriting, amortization, funded FX and protected repayment](architecture/loans.md).

Current loan diagnostic contract: [owner commands and audited bounded inspection](architecture/loan-diagnostics.md).

Current loan acceptance and downstream source [fixed terms, original obligations and recovery](architecture/loan-acceptance.md).

Current shared debt contract [original obligations, collection ownership and independent reporting clocks](architecture/debt-lifecycle.md).

Current debt diagnostic contract [owner history, clocks and audited inspection](architecture/debt-diagnostics.md).

Current accepted lifecycle/reporting contract [V33 sources, stage6 scoring, replay and A6-11 payment API](architecture/debt-acceptance.md).

Current Collection Agent contract [presentation, cooldown, private dialogue and original receipt recovery](architecture/collection-agent.md).

Current bank/credit menu and API contract [protocol8 and version-two integration](architecture/banking-integration.md), with [A6-12 acceptance evidence](verification/A6-12.md).

[A6-13 acceptance](A6-13.md) records the delivered banking foundation and approved D15 automatic payments. [Downstream contracts](architecture/a6-downstream.md) publish A8/B2/B4/B6/A10 handoffs (renumbered from A7/A8) with capabilities and prerequisites.

## A7 acceptance and later planned phases

A7 gameplay decisions are recorded in [the phase contract](architecture/planned-a7-a10.md). [A7-01](A7-01.md) is complete as a bounded source/resource/API feasibility checkpoint; [revision-1 contracts](architecture/a7-shared-contracts.md) freeze geometry, generation, persistence handoffs, activation and staffing interfaces. A7-02 persistent plans, provider recovery, trusted activation and bounded diagnostics are implemented; [current branch architecture](architecture/village-branches.md) and [A7-02 evidence](verification/A7-02.md) record their scope. [A7-03 wider kiosks](architecture/kiosk-upgrades.md) now preserve legacy narrow forms and provide explicit free recoverable upgrades, verified in a disposable integrated client. [A7-04 village generation](architecture/village-generation.md) installs the pinned composition wrapper, original GenMC templates, chunk receipts and explicit loaded terrain retry; [evidence](verification/A7-04.md) records bounded five-style and natural-generation acceptance.

Native bank structure discovery is now registered as `/locate structure #economy:banks` (`economy:bank`), with [direct vanilla-command evidence](verification/bank-structure-locate.md). Local kiosk POI discovery remains separate.

A7-01 through A7-06 are user-approved for implementation as of 2026-11-06: [shared contract](../prompts/A7-village-banking/CONTRACT.md) and [selection queue](../prompts/A7-village-banking/EXECUTION.md). A7-01 through A7-06 have been executed and A7 is accepted in its recorded scope. Approval does not begin implementation or establish verification; A7-01 records source/API feasibility, exact building envelopes/floor plans and shared interfaces; downstream runtime acceptance remains explicit. Approval covers A7 only.

[A7 pinned-source research](architecture/a7-generation-research.md) selects the composition wrapper at the pre-freeze layout window. [Template inventory](architecture/a7-template-inventory.md) is content-audited classification revision 1. Abandoned villages receive no A7 content. Approved placement tries interior, nearby edge with foundations/modest leveling, then farther connected sites; exhausted search retains a reported pending branch plan. Exact geometry, search/terrain measurements and conservative recovery are frozen in the revision-1 contracts; production placement is implemented by [A7-04](A7-04.md), with pending and no-auto-rebuild limits.

A6 is complete in its accepted scope. The revised sequence is **A7 Village Banking Package → A8 Economic simulation (former A7) → A9 Basic item valuation → A10 Core integration (former A8)**. A7-01 through [A7-06](A7-06.md) are complete in their recorded scopes. A8 is accepted in the scope recorded below; A9-01 is complete in source/document scope and A9-02 delivers base-only snapshots and A9-03-01 ordinary recipes/processing plus A9-03-02 brewing; remaining A9 packages and A10 require explicit selection.

[Planning boundaries and suggested package splits](architecture/planned-a7-a10.md) cover village bank plans and placement/pending behavior, persistent branches, banker guides/desks, wider kiosks, reference item costs and provenance. Use this contract for implementation planning; the public roadmap does not authorize execution. Complete remaining technical limits, recovery, kiosk-upgrade details and recipe policies in the selected package before implementation. B-series handoffs remain separate.

[A7-05 banker desks and staffing](architecture/banker-staffing.md) installs actual job desks in new templates, persistent employee assignments, proven-vacancy replacement clocks and explicit private dialogue through ordinary kiosk admission. [Handoff](A7-05.md) and [evidence](verification/A7-05.md) record acceptance and limits. Existing saved furniture is not converted; superflat generation issue [#3](https://github.com/emilybelnavis/economy/issues/3) remains deferred. [A7-06 acceptance](A7-06.md) closes A7 with [composite evidence](verification/A7-06.md) and [downstream contracts](architecture/a7-downstream.md).

A7 acceptance reuses prior counts/provider, five-style, terrain and upgrade evidence and adds direct generated-kiosk account opening, a normal same-save process restart, saved staffing progress and populated account survival after staff/hardware loss. Read the [A7-06 evidence](verification/A7-06.md) for its limits.

## A8 accepted; A9 source checkpoint

A9's [gameplay decisions are settled](architecture/a9-planning.md#final-decisions-before-drafting), and the user requested drafting on 2026-11-07. [Approved technical revision 1](architecture/a9-shared-contracts.md), [vanilla catalog](architecture/a9-item-catalog.md), [per-item inventory](architecture/a9-item-catalog.csv) and [eight approved selectable packages](../prompts/A9-item-valuation/EXECUTION.md) cover exact snapshots, ordinary recipes, brewing, variants/containers, public API/SPI and durable trader-source replacement. A9-03 is a nonselectable overview of its three children. Catalog prices are approved with the latest log/meat/disc revisions; all technical/package drafts are approved 2026-11-07, subject to prerequisites. Accepted premiums and gameplay rules remain approved. [A9-01](A9-01.md) is complete and accepted as a source/document checkpoint, with [frozen interfaces and source map](architecture/a9-source-contracts.md) and [scoped evidence](verification/A9-01.md). [A9-02](A9-02.md) is complete in its [base-only snapshot scope](architecture/item-valuation-snapshots.md), with [evidence](verification/A9-02.md). [A9-03-01](A9-03-01.md) is complete in its [ordinary graph/processing scope](architecture/item-valuation-recipes.md), with [evidence](verification/A9-03-01.md). [A9-03-02](A9-03-02.md) is complete in its [brewing scope](architecture/item-valuation-brewing.md), with [evidence](verification/A9-03-02.md). [A9-03-03](A9-03-03.md) is complete in its [component/container scope](architecture/item-valuation-variants.md), with [evidence](verification/A9-03-03.md). [A9-04](A9-04.md) delivers the [public API/SPI](architecture/item-valuation-api.md), with [evidence](verification/A9-04.md) and [A10 read-only handoff](architecture/a9-read-only-handoff.md). [A9-05](A9-05.md) delivers the [source-transition foundation](architecture/trade-source-transition.md), with [evidence](verification/A9-05.md). [A9-06](A9-06.md) is complete in its [activation scope](architecture/reference-trader-activation.md), with [focused/build, native checkout and read-only history evidence](verification/A9-06.md). All eight A9 packages are complete in their recorded scopes as of 2026-11-08; documented component/search/admission limits remain. A10-01 is complete in source/document scope; [A10-02-01](A10-02-01.md) delivers the dashboard/state and [A10-02-02](A10-02-02.md) delivers indexed filtered history. Later packages remain unselected.

The user approved activity-driven prime and FX, both every 20 real minutes, currency-specific bank-loan prime, and monetary villager/wandering-trader commerce. [Gameplay decisions](architecture/a8-planning.md) preserve the selected rates, stock, market, currency, payment and recovery policies. Subprime and captured fixed loans retain their existing rules. No legacy deployed-trader migration is required; preserve local worlds/backups and existing foundation history.

[Revision-1 technical contracts](architecture/a8-shared-contracts.md) specify observation attribution, normalized equal-weight formulas, exact pairwise FX limits, prospective daily-FX retirement, effective-time readers, trader identity/pricing, NPC settlement/custody and A9 price-source replacement. Numeric normalization scales and low-level algorithms are approved engineering defaults requiring A8-01 source-feasibility review; approval does not establish implemented or gameplay-tested behavior.

[Phase entry point](../prompts/A8-economic-simulation/README.md), [shared package contract](../prompts/A8-economic-simulation/CONTRACT.md), and [selection queue](../prompts/A8-economic-simulation/EXECUTION.md) define 12 independently selectable packages (A8-01 through split foundations and A8-08). **The user approved technical revision 1 and all 12 A8 packages on 2026-11-07, subject to prerequisites. A8-01 is complete in source/document scope and [A8-02-01](A8-02-01.md) is implemented in its [verified attribution scope](verification/A8-02-01.md). A8-02-02, A8-03-01, A8-03-02, A8-04-01, A8-04-02, A8-05, A8-06-01, A8-06-02, A8-07 and A8-08 are also complete in their recorded scopes; subsequent packages remain unselected.** [Handoff](A8-01.md) and [source evidence](verification/A8-01.md) record pinned hooks, balanced settlement examples, ownership and scheduler/readers. Current [activity architecture](architecture/activity-attribution.md) records the V40 source/counter foundation. [A8-02-02](A8-02-02.md) delivers [immutable windows and deterministic model plans](architecture/activity-windows.md) in its recorded scope. [A8-03-01](A8-03-01.md) delivers [detached market/catalog/pricing](architecture/trade-markets.md) in its recorded scope. [A8-03-02](A8-03-02.md) delivers [owned entity hooks, stock and progression](architecture/trader-entities.md) with actual hook evidence. [A8-04-01](A8-04-01.md) delivers [account/NPC/FX settlement](architecture/npc-account-settlement.md) under V44 with atomic custody/stock/activity intent and zero terminal bridge. [A8-04-02](A8-04-02.md) delivers [cash and physical custody](architecture/npc-cash-custody.md) under V45, with exact lots/change, saved physical evidence, one-use delivery and explicit original-input return. [A8-05](A8-05.md) delivers [currency-prime history, scoped plans and loan capture](architecture/currency-prime.md) under V46 and explicit-currency BankingIntegration v2 reads. [A8-06-01](A8-06-01.md) delivers [prospective fixed retirement and exact-time FX](architecture/fx-effective-time.md) under V47, with enumerated live-consumer migration and preserved legacy history. [A8-06-02](A8-06-02.md) delivers the [financial-day provider and atomic handover](architecture/macro-rates.md) under V48, with frozen selected baskets, actual dispatcher completion readiness and trusted prospective policies. [A8-07](A8-07.md) enables the [production lifecycle, controls and trader menus](architecture/simulation-production.md) under V49 with actual foreign cash/network/save evidence. [A8-08](A8-08.md) accepts A8 in its [recorded scope](architecture/a8-acceptance.md), adding the merchant-to-rate composite and [A9 downstream contract](architecture/a8-downstream.md). All 12 A8 packages are complete; A9-01 is complete in source/document scope and A9-02 in base-only snapshot scope and A9-03-01 in ordinary graph/processing scope and A9-03-02 brewing and A9-03-03 variants/containers and A9-04 public API/SPI and A9-05 source-transition foundation. Remaining A9 packages and later phases remain unselected.

## A10 preparation

2026-11-09 — [A10-05](A10-05.md) is complete in its local native scope: original borrowing→due→report→default/sale→managed agent→positive partial→full settlement passed in305.212s/4,250 observed dispatcher turns. [Evidence](verification/A10-05.md) and [clock architecture](architecture/fixture-financial-time.md) distinguish actual protocol/entity witnesses from read-only checks. Production UTC/deadlines remain unchanged; debt-sale/activity allowance compatibility and creditor display defects are fixed. External-client testing remains pending/manual and excluded from CircleCI. Next eligible explicit selection: A10-06; no dependent work is selected.

2026-11-08 — The user requested “prep PhaseA10”. [Phase specification](architecture/a10-planning.md), [handoff](A10-planning.md), [shared draft contract](../prompts/A10-core-integration/CONTRACT.md) and [eleven-package queue](../prompts/A10-core-integration/EXECUTION.md) are approved 2026-11-08, subject to prerequisites, preserving the original A10 core UI/history/API/admin/operations scope. All third-party compatibility is now assigned to standalone integration modules outside A10. All eleven selectable prompts are approved subject to prerequisites. [A10-01](A10-01.md) is complete in source/document scope with [frozen contracts](architecture/a10-source-contracts.md) and [evidence](verification/A10-01.md). [A10-02-01](A10-02-01.md) is complete with [architecture](architecture/banking-dashboard.md) and [focused/native evidence](verification/A10-02-01.md). [A10-02-02](A10-02-02.md) is complete with [owner-history architecture](architecture/owner-history.md) and [SQLite/native/restore evidence](verification/A10-02-02.md). Banking wire10/schema53 add bounded frozen filtered history and original details; APIs2/1/1 remain. [A10-02-03](A10-02-03.md) completes covered core localization and contextual explanations with [architecture](architecture/core-localization.md) and [source/build evidence](verification/A10-02-03.md). [A10-03](A10-03.md) completes the [core layouts](architecture/core-layout.md) with [native minimum/French/resize evidence](verification/A10-03.md). [A10-04](A10-04.md) passes authenticated local multiplayer/recovery with [network architecture](architecture/network-recovery.md) and [actual socket/delay evidence](verification/A10-04.md); external-client evidence remains pending. Next eligible explicit selection: A10-06. The actual320×240 logical boundary and finite normal/large combinations are recorded; external-client connectivity remains pending; the local dedicated/two-session setup is verified; engineering workload/budget defaults are drafted for the approved target of up to 20 concurrent players, with measurements pending. The user approved a controlled accelerated debt-composite fixture, with unchanged production deadlines; an unaccelerated playthrough is optional later evidence. Preparation is prose/source review only.

A10 technical revision1 and all eleven selectable prompts are approved 2026-11-08, subject to prerequisites: [revision1](../prompts/A10-core-integration/TECHNICAL.md), [source map](../prompts/A10-core-integration/SOURCE_MAP.md) and [finite runtime/workload plan](../prompts/A10-core-integration/RUNTIME_PLAN.md). Eleven independently selectable packages include three A10-02 children; A10-02 is overview-only. A10-01, A10-02-01 and A10-02-02 are complete in their recorded scopes. A10-02-03 is complete in its recorded localization/presentation scope. A10-03 is complete in its recorded native layout scope. [A10-04](A10-04.md) passes authenticated local multiplayer/recovery with [network architecture](architecture/network-recovery.md) and [actual socket/delay evidence](verification/A10-04.md); external-client evidence remains pending. Next eligible explicit selection: A10-06. Finite GUI dimensions are verified; the bounded local network-delay case is measured; performance targets and the dedicated reference workload setup remain pending. All compatibility is outside A10.

## Standalone integration modules

All third-party compatibility is outside A10 and belongs to [separately installed integration modules](../prompts/integrations/README.md). Core Economy must not bundle or load module adapters, registrations, assets or third-party dependencies. Keep modules independently selectable; extension-specific code/data activates only with its applicable module/requirements, without a mandatory all-extension bundle. A small shared module is allowed only when installed adapters need it and must not become a core dependency.

Use [explicit dependency targets](../prompts/integrations/DEPENDENCIES.md), without modpack naming, moving versions or silent upstream-latest substitution. Separate [extension](../prompts/integrations/EXTENSIONS.md), [item-value](../prompts/integrations/ITEM_VALUES.md) and [wallet](../prompts/integrations/WALLET.md) requirements are retained. Pinned API, storage authority/recovery, module packaging and development-runtime feasibility remain unimplemented/unverified. Module packages inherit the same scoped verification policy; core A10 acceptance and 20-player workload do not require them.

A10 approval: the user approved all eleven selectable prompts/shared contracts; A10-02 stays overview-only. A10-01, A10-02-01 and A10-02-02 are complete in their recorded scopes. A10-02-03 is complete in its recorded localization/presentation scope. A10-03 is complete in its recorded native layout scope. A10-04 passes in authenticated local scope with external-client evidence pending. Next eligible selection is A10-06. Approval does not execute dependents; standalone compatibility modules are not included. Banking wire10/schema53 install the additive filtered-history projection, original detail and historical FX views. One local GUI-scale2 widget/withdrawal/lookup witness passed; the finite A10-03 display/locale matrix also passed. Authenticated local multiplayer/recovery also passed; external connectivity and measured operations remain downstream needs; A10-05 now supplies the guarded accelerated native debt composite.
