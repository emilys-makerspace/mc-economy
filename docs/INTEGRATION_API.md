# Server integration contracts

Economy targets Minecraft 1.21.1, NeoForge 21.1.252 and Java 21. Separate integration mods can consume the server banking interface below.

## Banking API version 1

`ca.emilybelnavis.mcmods.economy.api.BankingIntegration` declares `VERSION = 1` and exposes `version()`. The active server's ledger service implements it.

Resolve services on the server thread after Economy reaches READY.

```java
var services = Economy.serverServices(server);
if (services == null || services.state() != ServerServices.State.READY) {
    return;
}
BankingIntegration banking = services.ledger();
if (banking.version() != BankingIntegration.VERSION) {
    return;
}
```

Obtain owner grants from authenticated server context through `PartyAuthorization`. Retain the world and player identity supplied by that grant. Completion stages carry asynchronous results, including explained unavailability or failure. Keep server and generation threads responsive while work completes.

| Entry point | Contract |
| --- | --- |
| `prime(grant)` | Read the effective versioned pricing policy. |
| `valuation(grant)` | Read the owner's valuation snapshot with denomination, FX, source revisions and availability. |
| `feeQuote(grant, institution, service, principal)` | Obtain a quote with captured policy, amounts, revisions and expiry. |
| `quoteCreditDraw(grant, token, anchor, generation, funding, amount, purchase)` | Capture a quote from current owner, issued-token and physical-service context. |
| `drawCredit(grant, operation, attempt, request, proof)` | Submit a draw using its sealed admission proof and original operation identity. |
| `creditCheckout(grant, request, proof)` | Supply the existing register COMMIT settlement composer for its captured credit-funded purchase. |
| `collateralCapabilities()` | Report current fixed, variable and secured product availability. |

A quote is a captured observation. Settlement rechecks current ownership, expiry, revisions, eligibility and the required physical proof. Preserve original operation identity across uncertain outcomes and use the existing journal's outcome lookup before another submission. Existing custody handling governs physical cash, cards and goods.

Fixed unsecured lending is available under current policies. Variable and secured lending report `UNSUPPORTED_B6_ADAPTER_REQUIRED`. Trading remains unavailable through the banking facade. Future adapters require their separately selected product and settlement contracts.

[BankingIntegration source](../src/main/java/ca/emilybelnavis/mcmods/economy/api/BankingIntegration.java), [server authorization](../src/main/java/ca/emilybelnavis/mcmods/economy/foundation/PartyAuthorization.java).

## Village banking boundaries

Generated branches expose ordinary authenticated kiosk services. Branches and staff provide physical access and guidance. Customer accounts belong to the existing owner/account services. Losing a building, kiosk, desk or employee preserves account identity, balances and obligations.

`BranchService`, generation publication, hardware bootstrap and staffing coordination are internal server adapters. Their physical leases are issued from current server observations. A branch ID, institution key, entity UUID or saved placement identity identifies a record and supplies no financial authority by itself.

| Versioned surface | Current version | Scope |
| --- | --- | --- |
| `BankingIntegration` | 1 | Server financial integration interface. |
| Banking menu transport | 8 | Offered actions bound to the current player, menu, session and revision. |
| Banker dialogue transport | 1 | Explicit questions and fresh routing to physical banking access. |
| `BranchPlans` serialization | 1 | Internal captured source, provider, geometry and placement provenance. |
| World storage schema | 39 | Internal projections, migration validation and journal recovery. |

The transport and persistence versions describe their respective protocols. Integrations use the server API and current grants for financial operations. World storage upgrades follow the stopped-world complete-backup procedure in the server guide.

## Downstream consumers

Economic simulation can consume effective prime policy and retained historical versions through the existing banking contract. Its scheduling and publication adapter belongs to A8.

Reference item valuation belongs to A9. A future reference cost is distinct from an accepted financial valuation source. Financial qualification continues to use the current authoritative valuation contracts.

A10 can extend the player experience and multiplayer coverage using the existing offered-action, authorization, quote and original-outcome rules. Securities, trading and expanded lending retain their separate B-series prerequisites.

[Server guide](SERVER_GUIDE.md), [A7 downstream handoff](../.codex/architecture/a7-downstream.md).


## Economic simulation reads (version1)

On the authenticated server thread, obtain `Economy.serverServices(server).simulation()`, returning `EconomicSimulationIntegration`. Its `snapshot(grant)` returns an immutable cached optional snapshot (sample time, availability/reason, mode, W/B coverage, FX age, A/F/H, revisions, currency policies and source/limit manifest). `history(grant, beforeFinancialDay, limit)` asynchronously returns up to16 immutable saved selected outcomes using an exclusive financial-day cursor. Obtain a fresh ownership grant through `PartyAuthorization.player`; stale sessions, inspection grants and mismatched worlds do not gain ownership reads. These reads neither observe/advance financial time nor authorize money, items or a trade. A missing cache/sample is unavailable rather than zero coverage.

Public operators use the authenticated future-policy commands described in the server guide; clients never send policies, prices, bank authority or SQL callbacks. Trader transport accepts only the currently offered action/value with matching menu/session/revision. Backend `tradeStorage` is trusted maintenance, not public monetary admission. Live monetary work uses the separate economic-current gate and captured revocable server proofs.

`TradePriceSource` remains the detached A9 replacement seam. The installed source is immutable `EmeraldV1`, using captured original emerald quantities, pinned standard quantities and exact effective FX. Future adapters must introduce prospective source epochs/provenance without rewriting saved quotes, carry state or trade history. No A9 valuation adapter is executed here.
