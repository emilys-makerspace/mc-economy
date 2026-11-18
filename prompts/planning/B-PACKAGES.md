# Stage B package drafts — master index

Prepared 2026-11-13. **All 72 package drafts across B0–B9 were approved on 2026-11-17.** Each package remains queued for explicit selection by ID after its prerequisites are satisfied. [Shared contract](B-SHARED.md) · [Decision register](B-DECISIONS.md) · [Original design briefs](B-SERIES-DESIGN.md) · [Current project state](../../.codex/PROJECT.md).

| Phase | Selectable packages | Navigation |
| --- | ---: | --- |
| [B0](../B0-corporations/README.md) | 6 | [Contract](../B0-corporations/CONTRACT.md) · [Ordered packages](../B0-corporations/EXECUTION.md) |
| [B1](../B1-market-registries/README.md) | 6 | [Contract](../B1-market-registries/CONTRACT.md) · [Ordered packages](../B1-market-registries/EXECUTION.md) |
| [B2](../B2-brokerage-custody/README.md) | 6 | [Contract](../B2-brokerage-custody/CONTRACT.md) · [Ordered packages](../B2-brokerage-custody/EXECUTION.md) |
| [B3](../B3-prices-liquidity/README.md) | 7 | [Contract](../B3-prices-liquidity/CONTRACT.md) · [Ordered packages](../B3-prices-liquidity/EXECUTION.md) |
| [B4](../B4-orders-settlement/README.md) | 8 | [Contract](../B4-orders-settlement/CONTRACT.md) · [Ordered packages](../B4-orders-settlement/EXECUTION.md) |
| [B5](../B5-international-arbitrage/README.md) | 6 | [Contract](../B5-international-arbitrage/CONTRACT.md) · [Ordered packages](../B5-international-arbitrage/EXECUTION.md) |
| [B6](../B6-margin-risk/README.md) | 7 | [Contract](../B6-margin-risk/CONTRACT.md) · [Ordered packages](../B6-margin-risk/EXECUTION.md) |
| [B7](../B7-shorts-lending/README.md) | 7 | [Contract](../B7-shorts-lending/CONTRACT.md) · [Ordered packages](../B7-shorts-lending/EXECUTION.md) |
| [B8](../B8-advanced-markets/README.md) | 13 | [Contract](../B8-advanced-markets/CONTRACT.md) · [Ordered packages](../B8-advanced-markets/EXECUTION.md) |
| [B9](../B9-release-hardening/README.md) | 6 | [Contract](../B9-release-hardening/CONTRACT.md) · [Ordered packages](../B9-release-hardening/EXECUTION.md) |

B8-08 is a nonselectable four-child corporation-expansion overview, with its children counted above. B8-04 is a nonselectable overview; its two children are included in the count. Added00 source checkpoints in B4–B8 are explicit packages. Existing brief IDs retain their meanings where possible; each queue identifies splits. A package plus its linked phase/shared contracts is the future implementation prompt. Every package has scoped ownership/outcome, prerequisites, authority/transaction/failure semantics, acceptance examples, unresolved policies, exclusions and a handoff boundary.

## Added corporation scope and settled follow-up

B0 is an independently deliverable corporation foundation after accepted Stage A banking. The user requests player-created companies, corporate accounts and a supported extension API for other mods. Explicit follow-up answers settle:

- One owner may appoint managers; multiple equity ownership is later scope.
- Accounts, payments and transfers first; corporate borrowing/credit later.
- Incorporation fee CAD 500 or equivalent; unlimited corporations per player.
- Four bank accounts per corporation, separate from personal accounts, under existing bank currency rules.
- Corporate banking through a company selector at existing bank kiosks.
- Real exchange hours evaluated against the mod's authoritative UTC clock.

Registration fee payment and company creation must be atomic, replay-safe and explicitly reviewed in the funding currency with captured FX where applicable. Company count remains unlimited while indexed pages, request sizes and processing remain bounded. Company registration creates no shares, listing, credit or personal guarantee. B0 extension API supplies organization authority and corporate account/payment access; specific mod adapters remain separate.

The user also approved [corporation policies C01–C08](../B0-corporations/POLICY-REVIEW.md) on 2026-11-13. The first corporation package is [B0-01, the corporate source and authority checkpoint](../B0-corporations/B0-01.md), for explicit selection.

## Dependency order

B0 may ship without securities and B1 registry work may proceed independently. Corporate brokerage/trading needs B0 acceptance. B1 supplies normalized reference identities; B2-01–04 custody/funding/composition foundation precedes B3 simulation; B3-04 marks then allow B2-05 financial valuation and B2-06 final acceptance. B4 consumes accepted B2/B3 to activate both player and autonomous settlement. No temporary independent cash/share writer resolves that order.

B5 arbitrage and B6 margin branch after B4; B7 lending/shorts follows B6. B8 integrates B5–B7 with coordinated listed-issuer actions and disclosed indices. B9 audits selected B0–B8 scope, operations and release claims. Finish each selected package's scoped checks/handoff before starting its dependents; queues are not batch execution instructions.

## Approved launch policies

[BD01–BD12](B-DECISIONS.md) record approved launch policies and implementation checkpoints. Already approved venue quotas total 800 including 95 Euronext, and two OTC originals are approved replacements; replacement selections and catalog rules are now [recorded under K01–K07](../B1-market-registries/POLICY-REVIEW.md); exact 800 row/close/source/license/relationship evidence remains. B5 cross-listing arbitrage [X01–X10](../B5-international-arbitrage/POLICY-REVIEW.md) policies are approved, with exact source/custody/fee/strategy/manifest feasibility remaining. Corporate C01–C08 policies are now [approved](../B0-corporations/POLICY-REVIEW.md); actual interface/ordinary-fee feasibility remains for B0-01. Calendar [T01–T07](../B1-market-registries/CALENDAR-REVIEW.md) policies are also approved; actual sourced schedules/covered years remain preparation work. Funding/liquidity [M01–M08](../B3-prices-liquidity/LIQUIDITY-REVIEW.md) are approved at CAD 2.8T cash (100×), with the separate share target and other parameters unchanged. Moderately active [P01–P07](../B3-prices-liquidity/PRICE-DYNAMICS-REVIEW.md) dynamics/financial-mark policies are approved, with exact formulas/source feasibility remaining for B3-01. Brokerage [R01–R09](../B2-brokerage-custody/POLICY-REVIEW.md) policies are approved, with source/representation/proof feasibility remaining. Order [O01–O10](../B4-orders-settlement/POLICY-REVIEW.md) policies are approved, with conforming source/lot/controller/sequence feasibility remaining. Margin [G01–G11](../B6-margin-risk/POLICY-REVIEW.md) policies are approved, with conforming adapter/risk/pledge/rate/servicing feasibility remaining. Securities-lending/short [L01–L11](../B7-shorts-lending/POLICY-REVIEW.md) policies are approved, with lineage/shared-risk/fee/return feasibility remaining. The [approved operations and history policy](../B9-release-hardening/OPERATIONS-REVIEW.md) governs workload targets and chart retention.

## Drafting handoff and verification

Planning only: reviewed phase contracts/queues, representative domain prompts, relative links, numbering/counts and prose diffs. No runtime code/dependency/assets/world/backup changes; no Gradle/tests/game processes. Required runnable checks belong to a later separately selected implementation delivery; these prose drafts do not establish current runtime verification.

[Project verification policy](../../.codex/VERIFICATION.md) applies to every package/sub-task. No automatic tests/full-suite gate. Future ordinary runnable delivery keeps Minecraft 1.21.1 / NeoForge 21.1.252 / Java 21, working runClient/runServer, registered main source set and required development runtime dependencies, and runs build-without-tests plus runClient help/dry-run once. At most one focused named-test invocation where useful; one owner runs Gradle/game tasks sequentially. Preserve saves/backups and qualified upstream evidence. External-machine evidence stays manual/outside CI.

Two bounded economy_worker assignments drafted B1–B3 and B5–B8; main established the shared contract/decisions and drafted B0/B4/B9 plus final integration review. No researcher profile/model substitution or delegated runtime checks. A new chat can start with this index, the selected exact package ID and its decision/prerequisite records; nothing is automatically selected next.

## Player-corporation B8 expansion — 2026-11-16

[Requested scope/answers](../B8-advanced-markets/CORPORATIONS-EXPANSION.md) add four B8-08 packages for private equity/classes/issuance, founder-control-to-voting governance and optional listing, alongside shared funded dividends/splits/same/cross-player merger/delist actions. The selected source model is fictional in-game news on master UTC; compressed financial periods do not shift the real venue-session clock. B8 now has 13 selectable drafts and the master total is 72. Existing IDs are preserved; the queue places new prerequisites explicitly. Initial equity, governance and player-corporation action rules are approved. Launch listing and valuation are approved. Stop and halt rules are approved. Fictional news calibration is approved. Index methodology is approved. See the [approved index methodology](../B8-advanced-markets/INDEX-METHODOLOGY-REVIEW.md) and [approved curated issuer action rules](../B8-advanced-markets/CURATED-ISSUER-ACTIONS-REVIEW.md). See the [approved news calibration](../B8-advanced-markets/NEWS-CALIBRATION-REVIEW.md). See the [approved stop and halt rules](../B8-advanced-markets/STOPS-AND-HALTS-REVIEW.md). See the [approved listing and valuation rules](../B8-advanced-markets/LISTING-AND-VALUATION-REVIEW.md). See the [approved equity and action rules](../B8-advanced-markets/EQUITY-AND-ACTIONS-REVIEW.md).

B8 corporation/news refinements are approved: [one-way founder→class voting and synchronized fictional news semantics](../B8-advanced-markets/CORPORATIONS-EXPANSION.md#approval-record--2026-11-16). See BD08 and BD12 in the [decision register](B-DECISIONS.md) for the current status.
