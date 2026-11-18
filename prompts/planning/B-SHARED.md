# Stage B shared draft contract — revision 1

The user approved all B0–B9 package drafts on 2026-11-17. The [master index](B-PACKAGES.md) and phase queues supersede earlier draft approval labels. Execute one explicitly selected package at a time after confirming its prerequisites, and finish its scoped verification and handoff before starting dependents. Individual package files, their phase contract and this contract form each implementation prompt. Preserve existing code, worlds, backups and historical outcomes. Do not read ROADMAP.md as an execution contract.

## Requirements and authority

Minecraft 1.21.1 / NeoForge 21.1.252 / Java 21 remain fixed. Reuse Stage A's existing world-local SQLite journal, bounded worker, scheduler, money/FX/credit and authentication. Custody owns securities and return obligations; execution composes through one existing journal transaction. No alternate money engine, public SQL callbacks or mandatory compatibility bundle.

Use [.codex/PROJECT.md](../../.codex/PROJECT.md) and [current downstream boundaries](../../.codex/A10/architecture/a10-downstream.md); historical version inventories are not current. Planning baseline is schema55, banking wire13, BankingIntegration3 and AccountFunds2 after overdrafts; revalidate actual signatures and allocate future versions at each source checkpoint. Other current facades and decoders stay compatible. Signed AccountFunds does not authorize a new product to borrow. Corporate and trading funding must explicitly disallow personal overdraft/credit until separately accepted.

Each corporation has its own four-account BANK limit, independent of personal accounts, following existing bank currency rules. Corporate bank access uses the approved company selector at existing bank kiosks; no separate terminal is selected. Corporate registration is approved at CAD 500 or equivalent, with unlimited corporations per player. Exact fee debit and organization creation must commit together; bound requests/pages rather than silently impose a company cap.

The user's additional requirements are player-created corporations, corporate accounts and an extension API usable by other mods. Follow-up answers approve **one owner with appointed managers initially**, and **accounts/payments/transfers first; corporate loans and credit later**. B0 is an independently deliverable foundation before corporate use in B2/B4. A player-created corporation is an organization party, distinct from a security issuer/company catalog record. Creating it does not mint shares, list it, grant credit, or give integrations arbitrary authority. Specific integration mods remain separate.

The user selected real exchange hours against the mod’s authoritative UTC clock. Venue-local hours must map to UTC through reviewed rules; neither Minecraft time nor host-local time governs sessions. Calendar [T01–T07](../B1-market-registries/CALENDAR-REVIEW.md) were approved 2026-11-13: official holiday/early-close/break rules, regular continuous sessions only, retained venue-zone/DST-to-UTC intervals with inclusive open/exclusive close, chronological catch-up, locally reviewed coverage and affected-market refusal on gaps, audited prospective exceptional changes and session-aware arbitrage. Actual sourced coverage/interface feasibility remains preparation work.

The user approved [B0 policies C01–C08](../B0-corporations/POLICY-REVIEW.md) on 2026-11-13, settling initial manager authority, banking eligibility, incorporation funding/names, fixed founding ownership, contributions, wind-down and extension consent. These approvals supersede earlier open-BD10 descriptions. Source/API/ordinary-fee feasibility remains for B0-01; no implementation package is selected.

## Cross-phase interfaces

| Owner | Authority and immutable handoff |
| --- | --- |
| B0 | World-scoped organization ID, single owner, scoped manager grants/revocation, corporate bank owner and actor audit, typed extension facade. Corporate credit unavailable. |
| B1 | Independent company/security/share-class/exchange/listing IDs, effective registry revisions, source provenance, sessions and tick rules. Optional organization-to-issuer link requires an explicit future issuance policy. |
| B2 | Brokerage cash links, balanced security postings, free/reserved/pledged/loaned quantities, lots, exact mark/FX manifests and atomic settlement participation. |
| B3 | Retained deterministic simulation state, funded participant inventories, finite executable quotes and liquidity revision, bounded chronological price/history publication. |
| B4 | Durable orders/fills, fund/share reservations, deterministic matching and atomic cash/custody/liquidity/fee/outcome coordination; owner-facing terminal and public facade. |
| B5 | Custody-preserving cross-listing transfers and finite arbitrage through B4, Stage A FX and B2; never direct price nudges. |
| B6 | Credit-owned margin liabilities/rate resets, custody-owned pledges and a unified risk view, calls and ordinary finite-liquidity liquidation orders. |
| B7 | Lending inventory, return obligations, locates, restricted short proceeds, borrow fees and recalls sharing B6 risk. |
| B8 | Effective corporate actions, advanced order triggers, halt rules and disclosed index methodology across all affected ledgers. |
| B9 | Traceable coverage/reconciliation, bounded operations, measured workloads, preserved migrations/recovery and release claims. |

Every mutation carries authenticated world/session/actor/owner, canonical versioned intent and operation ID, expected entity/policy/source revisions and explicit amounts/currencies. Live server-thread admission captures immutable input; the existing worker revalidates authoritative ownership/revisions in its transaction. Publication rechecks the current audience. Resolve an original operation before freshness checks/new spend: equivalent replay returns its original result; changed payload conflicts. Cancellation/disconnect does not roll back commit. Revocation must invalidate not-yet-committed delegated work, including queued requests; a committed operation remains recoverable without leaking data to revoked readers.

All affected money postings balance by currency; all securities movements balance by security. No implicit infinite seller, unsupported negative availability, duplicate claims, double pledge or unjournaled issuance. Cash/share/fee/order/fill/participant liquidity and original outcome commit together, or none do. Large domain actions must use staged plans with bounded atomic visibility rather than exceed existing transaction/record limits. Preserve immutable facts and captured history; corrections append. A simulation fair value is not an executable quote, an item reference is not a financial mark, and a quote is not a collateral right.

Checked integer quantities and exact monetary arithmetic; price ticks separate from currency minor units. Reject overflow, unsupported precision, missing marks/FX and stale versions rather than clamp or invent prices. R05 approves whole-share ordinary trades and FIFO; B8 fractional-entitlement policy is still separate. Stage A eight-place intermediate FX and currency-specific final rounding stay authoritative; product-specific rounding/fee rules need their owning decision. UTC elapsed time and retained effective versions govern; Minecraft sleep/time changes cannot advance finance. Offline work is chronological, deterministic, bounded and never silently discarded. Affected products stay unavailable until their dependencies catch up.

Persist product states, canonical operations, policy/registry/rate/mark versions and scheduler completion IDs inside the existing store. Read APIs return immutable bounded pages with owner/world/filter-bound cursors and captured versions; diagnostics distinguish absent, stale, catching-up and unsupported without disclosing foreign owners. Configuration changes validate and take effect prospectively. State transitions and terminal outcomes are auditable. Unknown/ambiguous authority or recovery fails closed; no new operation key to bypass uncertainty.

## Preserved catalog approvals

The [existing B1 design](../B1-market-registries/DESIGN.md) records approved venue quotas totaling800 (95 Euronext) and replacement of the FNMA/FMCC entries. Those approvals remain settled. Replacement identities and initial catalog policy are now settled in [K01–K07](../B1-market-registries/POLICY-REVIEW.md): BLK/BlackBlock and SCHW/Charles Shulker occupy NYSE slots under the documented former-venue interpretation; seeds use 2026-11-13 local regular closes. Exact 800 row/source/license/price and relationship evidence remains preparation work. The original catalog/provenance was recovered at Git `61256d4^`; a checkpoint reviews those artifacts without restoring runtime financial state. Existing DESIGN.md briefs remain context; new package CONTRACT/EXECUTION files refine them and explicitly identify any split.

## Draft decisions and execution boundaries

[Decision register](B-DECISIONS.md) distinguishes required scope, approved answers and recommendations. An affected package cannot implement an unresolved consequential policy. Each phase starts with source/contract feasibility and ends with a qualified handoff; neither checkpoint mandates rebuilding unchanged behavior. B0 can be delivered independently; B1 registry research can proceed without public-player-company listing rules. B2/B3 contracts precede B4; B5 and B6 branch after B4; B7 follows B6; B8 integrates B5–B7; B9 reconciles all selected scope. Do not start dependents automatically.

## Verification and handoff — all packages and delegated work

Apply [.codex/VERIFICATION.md](../../.codex/VERIFICATION.md). Planning is diff/link review only: no Gradle/tests/game/assets/dependency/migration work. Routine implementation uses at most one focused named-test invocation where useful for meaningful changed behavior, normally one to three classes; reuse real-service/SQLite fixtures and unchanged evidence. No automatic full-suite or phase-completion test gate. A full suite requires an explicit user request or a stated concrete concern narrower checks cannot address.

For an ordinary runnable delivery run Java 21 `./gradlew build -x test`, `./gradlew help --task runClient` and `./gradlew runClient --dry-run` once; repeat only failed affected checks after correction. Keep working runClient/runServer, main source-set mod registration and required dependencies on development runtimes. One owner runs Gradle/game tasks sequentially. Use disposable worlds for necessary affected runtime witnesses. Preserve player saves/backups, never accept a new EULA or terminate the user's game.

Each future package records actual changed contracts, decision resolutions, checks and limitations in `.codex/<phase>/<package>.md` and, where needed, `.codex/<phase>/verification/<package>.md`; updates its phase status; then stops. Mark source review, focused services, clean reopen, actual process crash, local sockets, external machines and synthetic load honestly. External-client evidence remains pending/manual and excluded from CircleCI; do not invent broader acceptance from A10's qualified scope. README remains user-facing; changelog changes only for usable releases/user-facing changes.

## Approved catalog direction and B5 obligation — 2026-11-13

[Catalog K01–K07](../B1-market-registries/POLICY-REVIEW.md) are approved selection policy, superseding earlier open naming/seed/replacement recommendations. Original 2025 price facts stay historical; all new-start seed rows use the 2026-11-13 cutoff with actual session/source dates and no fabricated values. B5 must handle eligible cross-listing arbitrage via B1 exact relationships, conserved B2 custody, B3 executable depth, Stage A FX and finite B4 fills. Cross-venue delivery/use cannot bypass required B5 restrictions; unsupported routes stay unavailable. BD11 product parameters and actual data/licenses remain unresolved as recorded. No implementation selected.

## Approved market funding/liquidity

[M01–M08](../B3-prices-liquidity/LIQUIDITY-REVIEW.md) are approved with a 100× cash-only amendment: initial CAD 2.8T-equivalent market cash, allocated CAD 1.68T makers/CAD 840B investment/CAD 280B arbitrage. Existing bank funding remains unchanged. Separate initial share reference value remains up to CAD 28B, shared by distinct underlying security; quote depth/cadences and other accepted M01–M08 policies are unchanged. Source/issuance feasibility remains preparation work. Numerical price dynamics and financial-mark policy are now approved under [P01–P07](../B3-prices-liquidity/PRICE-DYNAMICS-REVIEW.md); exact conforming formulas/source feasibility remain B3-01 work, and BD09/BD11 retain their separate decisions. No implementation selected.

## Current institution funding baseline correction

The user clarified that new bank/institution seed funding is already **2.8 trillion server-currency major units**, confirmed by `InstitutionCapital.TOTAL_MAJOR = 2_800_000_000_000L` and the [current institution contract](../../.codex/architecture/institutions.md). Allocation is 18×40B Tier 1/hybrid, 5×400B specialists, 40B Ironbridge and 40B Waystone. Legacy 28B manifests retain their captured total without automatic top-up/reseeding.

Earlier references to a current bank 28B seed were stale baseline descriptions. The separately approved B3 market cash pool remains **CAD 2.8 trillion-equivalent**, allocated CAD 1.68T/840B/280B. This correction applies no additional 100× multiplier, combines no funding manifests and changes no source code, player worlds or existing capital.

## Approved moderately active dynamics

The user accepted [P01–P07](../B3-prices-liquidity/PRICE-DYNAMICS-REVIEW.md), settling initial numeric dynamics/financial-mark policies alongside M01–M08 funding/liquidity. Exact formula/source/authority feasibility remains for B3-01; approval does not establish implementation or measured calibration. B6/B7 risk-mark policy, BD05 order rules, BD09 budgets and BD11 arbitrage friction/leg risk remain separate. No package selected.

## Approved brokerage policy — 2026-11-14

[R01–R09](../B2-brokerage-custody/POLICY-REVIEW.md) now settle initial brokerage lifecycle/currency/provider, explicit available-cash funding and FX consent, FIFO/basis/fees/reporting, corporate trading scopes and closure. Corporate payment permission alone grants no trade/cancel/view right; owner-approved separate brokerage scopes are required once B2/B4 are accepted. Existing C01–C08 credit/yield/card exclusions remain. Exact conforming source/representation/fee/proof feasibility remains B2 preparation, with BD05 and BD11 product decisions separate. No implementation selected.

## Approved order policy — 2026-11-14

[O01–O10](../B4-orders-settlement/POLICY-REVIEW.md) settle initial order protection/types/TIF/closed-session queuing, price-time matching, anonymous player/system books, same-controller self-trade prevention, partial fills, cancellation/revocation and exact compatible-lot cash-notional settlement. Later references to BD05 as an open policy are superseded by this record. Exact source/controller/sequence/lot/fee/transaction feasibility remains B4 preparation; BD09 capacity, BD11 arbitrage costs/leg risk and later advanced products remain separate. No implementation selected.

## Approved B5 policy — 2026-11-16

[X01–X10](../B5-international-arbitrage/POLICY-REVIEW.md) settle BD11 transfer/conversion fees/delays, exact ratios, cycle/session/budget/net-profit and failed-leg policies, activation/scopes and prospective economic attribution. This supersedes earlier open-BD11 preference descriptions. Actual source/fee/custody/ratio/strategy/manifest feasibility remains B5 preparation; preserve historical A8 classification and normal customer FX exactly once. No implementation or automation activation is selected. BD09 capacity and later B6–B8 terms remain separate.

## Approved personal margin policy — 2026-11-16

[G01–G11](../B6-margin-risk/POLICY-REVIEW.md) settle initial personal margin eligibility/cap, leverage/adjusted risk/haircuts, actual existing-lender funding, accelerated financial-calendar variable interest, real-UTC calls, finite liquidation/residual debt and unavailable-source restrictions. This supersedes earlier open-BD06 preference descriptions. Actual conforming secured/revolving/variable adapter, exclusive pledge, mark/rate and servicing/collection provenance remains B6 preparation. Existing fixed debts, bank/market capital and corporate borrowing exclusions remain. B7 shorts, B8 actions and BD09 budgets stay separate. No implementation selected.

## Approved lending/short policy — 2026-11-16

[L01–L11](../B7-shorts-lending/POLICY-REVIEW.md) settle BD07 initial personal short scope, opted lending supply/lineage, locates, proceeds/disjoint shared collateral, utilization fee/calendar/payout, calls/recalls/finite buy-ins and distinct money/share obligations. This supersedes earlier open-BD07 preference descriptions. Actual conforming lineage, risk/claim, fee/source, serving creditor and return/B8 feasibility remains preparation. Initial corporate/institution borrowing and corporate lending remain excluded; no new pool or inventory is created. No implementation selected.

## Requested B8 corporate expansion — 2026-11-16

[Expansion and confirmed answers](../B8-advanced-markets/CORPORATIONS-EXPANSION.md) explicitly add private player equity/classes/issues, optional public listing, class-aware dividends/splits/delistings, same-owner/cross-player mergers and founder control with optional transition to class voting. Earlier no-player-equity/merger statements are initial B0/B1–B7 scope limits, superseded for this B8 extension only. Corporate borrowing/shorting/lending and guarantees remain excluded. Company creation alone issues/lists nothing, and private issue price is not a financial mark. New B8-08 children establish equity/issuance/governance/listing; B8-03/04 apply actual corporate actions.

The chosen news model is fictional in-game events on authoritative master UTC. Financial periods are compressed durations, not a second UTC standard; venue sessions still convert from local zones to UTC. Event source/announcement/effective/commit identity must remain distinct, with no reroll/duplicate cross-listing effects or fake future news. Exact action/governance/listing/event/stop/halt/index details remain unapproved. No implementation selected.

## Corporation/news refinements approved — 2026-11-16

[Expansion approval](../B8-advanced-markets/CORPORATIONS-EXPANSION.md#approval-record--2026-11-16) now settles the one-way founder→class-voting mode transition and synchronized fictional master-UTC news publication/first pricing effect/quote generation, once-per-underlying with session-aware execution and original-event catch-up. Reconcile actual founder/manager/integration/action authority at the effective governance boundary; historical founder ID is no permanent rollback/grant bypass. Player action announcements require actual authorized facts. Unspecified class/vote/action/listing/stop/halt/news magnitudes/frequency/index policies remain open; no implementation selected.

The 2026-11-17 approval supplies the player-corporation share-class, dilution, voting, dividend, split and merger rules. Cross-player merger survivors use share-class voting at closing. See the [approved equity and action rules](../B8-advanced-markets/EQUITY-AND-ACTIONS-REVIEW.md).
