# Core handoff after A10

Use the [qualified acceptance matrix](a10-acceptance.md), [public API contract](public-api-contracts.md), [signature/lifecycle reference](../../docs/API_REFERENCE.md) and [operator runbook](../../docs/SERVER_GUIDE.md). Historical A6–A9 handoffs retain their checkpoint versions; they do not override this inventory.

Pins remain Minecraft1.21.1, NeoForge21.1.252 and Java21. Storage is54 and banking wire12; paired clients/server must update together. BankingIntegration2, EconomicSimulationIntegration1, ItemValuationIntegration1 and additive EconomicSimulationHistory1 remain supported. No new general event bus or public SQL/worker callback exists. Owner-thread/session/world grants, live physical proofs, current audience publication and bounded queues remain separate requirements.

Keep original canonical requests, operation identity, historical decoders, effective policies, quoted fees/FX and custody facts. Resolve an original operation before freshness checks or a replacement spend; a disconnected view cannot undo a commit. Cancellation/display abandonment is not rollback. Immutable history retains its captured version and meaning; selected simulation plans are distinct from complete published baskets. Item-reference cost and an accepted financial mark are distinct authorities.

## Separate B-series work

| Consumer | Existing seam | Required before enabling the product |
| --- | --- | --- |
| B2 securities valuation | Trusted WealthValuation sources and retained marks, [A6 financial contract](a6-downstream.md#b2-securities-valuation) | Stable source/revision/mark identity, fresh asset/liability coverage, bounded immutable manifest and no double-counted holdings or liabilities. ItemValuationIntegration supplies reference reads, not qualification or collateral authority. |
| B4 execution/trading | Fee primitives; trading remains unavailable through BankingIntegration | Separately approved execution, ownership, cash, holdings, fees and outcome must compose atomically, with cumulative fee rounding and original recovery. A fee quote or trading account does not implement execution. |
| B6 variable/collateral lending | Fixed unsecured lending and Loans.capabilities | Capabilities remain fixed=true, variable=false, secured=false with UNSUPPORTED_B6_ADAPTER_REQUIRED. Rate-reset/history and collateral ownership/valuation/reservation/release/default contracts require separate selection. Existing debt retains its terms. |

No B-series package is selected by this handoff. No stock, execution, collateral, new remote authority or monetary policy is implied by core acceptance.

## Separate optional integrations

Third-party adapters, target-mod dependencies, item catalogs, external inventories/networks and wallet changes belong to [separately installed modules](../../prompts/integrations/README.md). They are neither core dependencies nor prerequisites for the A10 workload. A module needs its own pinned dependency/API/data/authority/recovery contract and scoped verification; a valuation provider alone does not enable trade, collateral or bank qualification.

## Evidence carried forward

Two authenticated local socket clients are verified separately from20 synthetic service owners. External-machine testing remains pending/manual and excluded from CircleCI. Synthetic results do not establish20 live clients, physical-delivery throughput, natural-publication load or all-world/hardware support. The accelerated debt composite preserves production deadlines; an unaccelerated playthrough is optional later evidence. Finite native layouts and current terminal cases are bounded witnesses, not every screen×locale×scale permutation. These limits remain visible downstream; none waives a future failed implementation or check.
