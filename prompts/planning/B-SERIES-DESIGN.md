# B-series design brief index

Draft revision 1 — 2026-11-13. The user requested design briefs for all B phases. This authorizes planning documents only. No B implementation, row-level catalog approval or verification is claimed. Explicit policy decisions are recorded separately below.

## Recorded decisions

2026-11-13 — The user approved [B1 venue quotas](../B1-market-registries/DESIGN.md#approved-venue-allocation--2026-11-13), totaling **800 listings**, including **95 Euronext listings** across six venues. Individual listing selection and the remaining B-series policies are unresolved.

2026-11-13 — The user approved [replacement of Fannie Mine/FNMA and Freddie Map/FMCC](../B1-market-registries/DESIGN.md#approved-otc-replacements--2026-11-13) with eligible exchange-listed companies within the existing quotas. Delegated BLK/SCHW NYSE replacements are now [recorded](../B1-market-registries/POLICY-REVIEW.md); the total stays 800.

## Briefs and dependency order

| Phase | Draft | Required handoff |
| --- | --- | --- |
| B1 registries | [Design brief](../B1-market-registries/DESIGN.md) | A1 storage/identity and A2 currency contracts |
| B2 custody | [Design brief](../B2-brokerage-custody/DESIGN.md) | A4 accounting, B1 instruments; B3 marks before live financial valuation |
| B3 simulation | [Design brief](../B3-prices-liquidity/DESIGN.md) | A8 economic/time sources, B1 calendars/instruments, B2 participant custody |
| B4 execution | [Design brief](../B4-orders-settlement/DESIGN.md) | B2/B3 settlement and liquidity, current Stage A authority |
| B5 international arbitrage | [Design brief](../B5-international-arbitrage/DESIGN.md) | B4 execution and B1/B2 instrument conversion |
| B6 margin | [Design brief](../B6-margin-risk/DESIGN.md) | B4 execution and A6 debt/collateral adapter contracts |
| B7 shorts/lending | [Design brief](../B7-shorts-lending/DESIGN.md) | B6 shared margin plus B2/B4 custody/execution |
| B8 advanced markets | [Design brief](../B8-advanced-markets/DESIGN.md) | B5–B7 conversion, margin and return obligations |
| B9 release | [Design brief](../B9-release-hardening/DESIGN.md) | B1–B8 scoped handoffs and unresolved evidence ledger |

B2 defines the custody/settlement interface before B3 participants consume it. B2 valuation may be specified early but cannot activate without accepted B3 marks. B3 supplies liquidity to B4; autonomous executions must use the eventual shared settlement boundary, never a temporary independent cash/share writer. B5 and B6 can be designed independently after B4; neither is silently selected by this index. Package IDs inside briefs are proposed, not executable approvals.

## Shared authority and recovery contract

Use [current project state](../../.codex/PROJECT.md), [A10 handoff](../../.codex/A10/architecture/a10-downstream.md), [public API contracts](../../.codex/architecture/public-api-contracts.md) and [current precision](../../.codex/architecture/monetary-precision.md). Historical inventories describe preservation obligations, not the current runtime. Current project inventory records schema 55, banking wire 13, BankingIntegration 3 and AccountFunds 2; older A6/A10 checkpoint versions must not be treated as current. Reconcile actual signatures when an implementation package is selected.

Minecraft 1.21.1, NeoForge 21.1.252 and Java 21 remain pinned. Integrated and dedicated servers share server authority. Stage A owns cash, FX, fees, credit and debt; B2 owns custody and return obligations; B4 coordinates execution. Use the existing world database, journal and bounded worker. No parallel market banking database, public SQL callbacks or client-authored financial authority.

Capture live sender/session/world/menu/reach authority on the server thread, pass bounded immutable data to the storage worker, revalidate revisions and ownership at commit, and recheck the audience before publishing private results. CPU workers may calculate detached plans; commit must compare their input versions. Operators receive explicitly scoped audited commands, not implied spending authority.

Every mutation carries world, authenticated actor, operation kind, operation ID and canonical versioned request. Persist journal facts, projections, reservations and terminal outcome together. An authorized equivalent replay returns the original outcome before fresh-quote checks; a changed request conflicts. Admission/overload failures do not consume identity. Disconnect, cancel or publication failure cannot undo a committed fill. Uncertain commit requires recovery and original-outcome lookup before replacement work. Preserve historical decoders, currencies, FX/fee versions and raw facts; corrections append facts.

Use checked quantities and minor-unit money. Tick prices are distinct from settlement precision. FX and interest follow current eight-place contracts; market notional/fee rounding remains a consequential design decision where not already supplied by Stage A. Reject overflow, missing marks, stale inputs and unsupported capabilities explicitly. Item reference values are not collateral or wealth authority. Existing unsecured debt retains its original terms. The [current overdraft contract](../../.codex/architecture/overdrafts.md) permits signed BANK balances and closed primary accounts: brokerage eligibility must query current product state, include debt once, and cannot assume a permanent primary is always open or that all bank cash is nonnegative. Trusted over-limit Java authority does not automatically authorize brokerage borrowing.

Persist UTC effective times, independent commit sequences, scheduler checkpoints and policy revisions. Follow the existing real-time chronological dispatcher and bounded catch-up/readiness gates. A menu, player count or restart must not reroll simulation. Numeric cadence, retention, load and risk budgets in later briefs are proposals until reviewed. The historical 15-player planning target and A10 20-synthetic-owner sample are different evidence; neither proves a B-series live-client capacity guarantee.

## Review order and consequential decisions

| Review checkpoint | Decisions to settle | What remains blocked |
| --- | --- | --- |
| Registry identity and source policy | Account for all 100 originals, preserving 98 and replacing the two OTC entries; approved venue quotas (resolved); individual listing selection; source licenses; venue/calendar rules; instrument units and ratios | Catalog activation and downstream instrument eligibility |
| Custody and market foundation | Issuer inventory/capitalization; lot/fee basis; funding currencies; finite participant budgets; simulation cadence and source freshness | Live liquidity and portfolio qualification |
| Cash-market milestone | Settlement rounding; order time-in-force and priority; partial fills; player matching; quoted FX/fees; computer interaction | Player trading release |
| Cross-market and leverage | Transfer fees/delays and leg exposure; lender/rate product; haircuts/priority; call and liquidation rules | Arbitrage and margin activation |
| Lending and advanced products | Lender consent; borrow rates/recalls; dividend equivalents; corporate-action fractions; trigger source; index coverage | Shorts, actions and advanced-order activation |
| Release evidence | Supported workload/hardware and measured budgets; retention/archives; recoverable upgrades; remaining manual external-client evidence | Specific release guarantees unsupported by evidence |

Review the corresponding phase brief before approving a value. Recommendations are starting points, and examples illustrate arithmetic rather than approved game rules. Resolve only decisions needed by the next selected package; a full B-series policy approval is not required to continue bounded design work.

## Approval and delivery boundaries

Fixed requirements come from each phase README and accepted upstream contracts. New policy recommendations are **proposed**, including sessions, individual catalog rows within the approved B1 venue quotas, liquidity capitalization, lot accounting, order defaults, leverage, lending and index methodology. Record approval against the specific decision before enabling its behavior. Missing historical catalog/import artifacts are unresolved inputs; do not fabricate replacements or treat the completed reset as authorization to reset again.

These are first-pass briefs. They define proposed packages and acceptance outcomes; individually selectable execution prompts still need approved policy values, resolved interfaces and prerequisite evidence. Preserve worlds/backups and the root changelog. Optional third-party adapters remain separate modules. No online feed, asset generation or external research has been performed for these drafts; actual ticker/MIC/catalog/license claims require sourced review during B1 preparation.

Follow [verification scope](../../.codex/VERIFICATION.md): prose changes use diff/link review only. Future routine work uses at most one focused check when useful; ordinary runnable delivery runs `./gradlew build -x test`, `./gradlew help --task runClient` and `./gradlew runClient --dry-run` once, plus at most one focused test invocation if useful. Keep runClient/runServer, main source-set registration and required development runtime dependencies. No automatic full-suite or test-per-package gate; one owner runs Gradle/game tasks sequentially. External-machine evidence remains pending/manual and excluded from CircleCI. Preserve qualified upstream acceptance limits.

## Individual packages and player corporations — 2026-11-13

[Master package index](B-PACKAGES.md), [shared package contract](B-SHARED.md) and [decisions](B-DECISIONS.md) refine these first-pass briefs into individual future prompts. [B0](../B0-corporations/EXECUTION.md) is proposed for the added user requirement: player-created companies, corporate bank accounts and an extension API. Follow-up answers settle one owner plus appointed managers initially and accounts/payments before corporate credit. Existing B1 venue/OTC approvals remain settled. No implementation is selected.

Per-phase CONTRACT/EXECUTION files own the refined selectable boundaries. Earlier brief IDs are preserved where possible; added source checkpoints and split children are identified in each queue. B2 custody foundation precedes B3; B2 live financial mark acceptance follows B3 publication, avoiding a prerequisite cycle. Player-company public listing/equity remains separate from B8 listed-issuer corporate actions.

## Catalog rules approved — 2026-11-13

[Catalog K01–K07](../B1-market-registries/POLICY-REVIEW.md) supersede earlier open selection/naming/seed/replacement proposals. Starting prices use 2026-11-13 regular closes; selected replacements are BLK/BlackBlock and SCHW/Charles Shulker within NYSE under the documented former-venue interpretation. Actual 800 rows and historical data/source/license/relationship verification remain preparation work. B5 explicitly handles eligible cross-listings through net executable finite arbitrage and conserved custody; numeric friction/risk policies remain open. No implementation selected.
