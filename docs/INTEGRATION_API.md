# Server integration contracts

Economy targets Minecraft 1.21.1, NeoForge 21.1.252 and Java 21. Separate integration mods can consume the authenticated server facades below. The [API reference](API_REFERENCE.md) inventories exact signatures, legacy type exposure, completion/cancellation, failure and retained-version contracts.

## Banking API version 2

`ca.emilybelnavis.mcmods.economy.api.BankingIntegration` declares `VERSION = 2` and exposes `version()`. The active server's ledger service implements it.

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
| `prime(grant)` | Read the legacy global configured policy; no currency is inferred. |
| `prime(grant, currency)` | Read an explicit-currency prime policy, unavailable before activation or during incomplete publication. |
| `primeAt(grant, currency, effectiveMillis)` | Read an explicit-currency policy at a past/current trusted instant. |
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
| `BankingIntegration` | 2 | Server financial integration interface. |
| Banking menu transport | 10 | Offered actions bound to the current player, menu, session and revision. |
| Banker dialogue transport | 1 | Explicit questions and fresh routing to physical banking access. |
| Trader menu transport | 1 | Bounded server-offered actions; no client prices or financial authority. |
| `EconomicSimulationIntegration` | 1 | Existing cached snapshots and legacy selected-plan history. |
| `EconomicSimulationHistory` | 1 | Additive API-owned selected-plan pages with explicit financial-day cursors. |
| `ItemValuationIntegration` | 1 | Authenticated immutable item-reference reads, history and typed provider contributions. |
| `BranchPlans` serialization | 1 | Internal captured source, provider, geometry and placement provenance. |
| World storage schema | 53 | Internal projections, migration validation and journal recovery. |

Banking transport10 retains bounded dashboard/operation state and adds frozen owner-history queries, captured detail and a distinct historical FX view. Schema53 contains an additive original-fact read projection; old LedgerHistory ascending cursor semantics remain unchanged. Paired endpoints must use the same wire version; financial canonical requests and persisted intent meanings are unchanged. UI original-outcome lookup never automatically resubmits after absence. The transport and persistence versions describe their respective protocols. Integrations use the server API and current grants for financial operations. World storage upgrades follow the stopped-world complete-backup procedure in the server guide.

## Downstream consumers

A8 delivers the activity observer, immutable windows, future shared prime/FX activation and stage-0 provider. Ordinary loans capture their explicit currency policy; historical fixed terms and subprime pricing retain their existing meanings.

A9-02 installs internal base/override evaluation and immutable snapshots; A9-03-01 adds detached ordinary recipes, canonical sticks and calibrated processing under a new manifest version while retaining base-only history. A9-03-02 adds version-three vanilla brewing snapshots with exact typed potion identity and shared material costs, preserving earlier encodings. A9-04 exposes the grant-authenticated item-reference API/SPI. A9-03-03 adds version-four supported component/container evaluation with retained typed identities and bounded recursive publication. A9-06 delivers prospective reference-source activation and native checkout in its recorded scope. A reference cost is distinct from an accepted financial valuation source. Financial qualification continues to use the current authoritative valuation contracts.

A10 can extend the player experience and multiplayer coverage using the existing offered-action, authorization, quote and original-outcome rules. Securities, market trading and expanded lending retain their separate B-series prerequisites; monetary NPC commerce is the delivered A8 feature.

[Server guide](SERVER_GUIDE.md), [A7 downstream handoff](../.codex/architecture/a7-downstream.md).

## Economic simulation reads (version1)

On the authenticated server thread, obtain `Economy.serverServices(server).simulation()`, returning `EconomicSimulationIntegration`. Its `snapshot(grant)` returns an immutable cached optional snapshot (sample time, availability/reason, mode, W/B coverage, FX age, A/F/H, revisions, currency policies and source/limit manifest). `history(grant, beforeFinancialDay, limit)` asynchronously returns up to16 immutable saved selected outcomes using an exclusive financial-day cursor. Obtain a fresh ownership grant through `PartyAuthorization.player`; stale sessions, inspection grants and mismatched worlds do not gain ownership reads. These reads neither observe/advance financial time nor authorize money, items or a trade. A missing cache/sample is unavailable rather than zero coverage.

Public operators use the authenticated future-policy commands described in the server guide; clients never send policies, prices, bank authority or SQL callbacks. Trader transport accepts only the currently offered action/value with matching menu/session/revision. Backend `tradeStorage` is trusted maintenance, not public monetary admission. Live monetary work uses the separate economic-current gate and captured revocable server proofs.

`TradePriceSource.EmeraldV1` remains the supported legacy captured source, using original emerald quantities, pinned standard quantities and exact effective FX. A9-05/06 add the source-neutral prospective reference path described below; the item-reference read API alone never activates that path. Original source epochs, quotes, carry and trade history keep their captured interpretation.

### Cursor-bearing simulation history (version1)

Use `services.simulationHistory().page(grant, Long.MAX_VALUE, limit)` for API-owned immutable historical entries and an explicit next cursor. Limits are1–16. An empty Optional means denied ownership/session/world; a present page with no entries means an authorized empty history. Follow `nextBeforeDay()` only when present. Day indices are the original zero-based financial windows, not calendar dates. Entries preserve exact selected amounts, numerator/denominator, original hashes, record version and policy/world/window identities. A selected plan can appear before its basket finishes; history is not publication readiness or monetary authority. Existing `simulation()` signatures remain supported.

The [complete authenticated consumer example](../src/test/java/ca/emilybelnavis/mcmods/economy/examples/SimulationHistoryExample.java) is compiled with the focused checks and excluded from the release jar. Copy it into the consuming server mod and call `page` from an already authenticated action. Pass a server-thread `stillViewing` predicate for the caller's current menu/session/revision, keep the returned display-abandon action, and call it when that view ends. For another page, reacquire a grant through the example with the returned day cursor. The example routes completion through the server executor and refuses stale player instances, service sessions or worlds. It installs no packet, command, event or compatibility module.

Never block the server thread. Handle synchronous admission rejection as well as failed stages and typed unavailable values. Abandoning display or canceling a consumer future is not a command rollback or provider-publication cancellation. Read the [completion and failure contract](API_REFERENCE.md#authentication-completion-and-cancellation) before writing a consumer.

## Legacy A8 price-source compatibility

The historical A8 source is the captured immutable `TradePriceSource.EmeraldV1`. Its generic `Basis` exposes source UUID/revision, FX UUID, currency and exact minor principal per standard quantity; it grants no settlement authority. Its legacy durable account/cash Terms retain EmeraldV1 types. A8 alone supplied no live source switch; A9-05/06 deliver the separately versioned transition and activation described below. Implementing another `TradePriceSource` alone cannot safely enable it.

A separately selected A9 adapter must add source-neutral, versioned durable quote/fact terms and an authoritative prospective active source/epoch. BOOK captures that identity and SETTLE checks it again for uncommitted quotes; a source UUID/revision/epoch switch invalidates old uncommitted quotes without consuming money, items or stock. Resolve a committed original operation before those freshness checks: it must still return its captured outcome after a source change, without another trade or delivery.

A source transition must journal its boundary and fractional-carry/residual treatment. Existing carry epochs cannot be silently reset or reused with different content. Preserve recorded trades, balances, stock/restock progress, exact components/ingredients and historical activity principals; a new reference price does not revalue old observations or rate windows. Demand/recovery anchors change only under their explicit transition contract. Missing A9 inputs must report unavailable rather than silently falling back to the old price. A9 reference prices do not automatically become accepted bank qualification/wealth sources.

[Detailed downstream contract](../.codex/architecture/a8-downstream.md) lists the required migration and acceptance boundaries. A9 reference reads and the A9-05 durable source-transition foundation are implemented. A9-06 delivers explicit production activation and controller/native admission as described below. The preceding A8 contract describes the historical migration boundary.

## Item reference reads (version1)

On the authenticated server thread, obtain `Economy.serverServices(server).itemValuation(server)`. Every read requires a fresh current-session ownership grant from `PartyAuthorization.player`. The returned `ItemValuationIntegration` exposes immutable data; it cannot mint money, qualify assets, select a trade-price source or authorize settlement. Callers retain the separate offer, custody, funding, financial-qualification and settlement checks.

A request supplies `Identity(itemId, canonicalComponents, adapterVersion)`, quantity 1–1,000,000, target currency and either `Current` or `Retained(valuationSnapshot, fxPublication)`. Canonical component JSON is bounded to 64 KiB and preserves array order and supported state. `Current` captures a manifest and one effective FX publication. A retained request requires both saved identities and never substitutes today's FX. `currentCached(grant, request)` accepts only Current and returns a completed value only while its storage epoch and next effective-FX time boundary still prove the captured FX is current; otherwise it starts bounded asynchronous work and returns NOT_READY/PENDING. Other storage work conservatively invalidates this witness. Use `evaluate(grant, request)` for a bounded asynchronous admission-time FX capture and evaluation. Reading does not advance economic time.

READY returns reduced exact unit minor cost for provenance plus published unit/quantity `Money`, captured time, manifest effective time, bounded structured explanation and snapshot/configuration/recipe/provider/algorithm/FX identities. Exact values are not fractional spendable balances. Other statuses retain typed reasons and expose no partial money. Explanation truncation does not change numeric completeness. Invalid or unauthenticated requests reject at admission. Lifecycle readiness, session/thread checks and bounded queues can refuse work.

`history(grant, Optional<UUID> beforeSnapshot, limit)` returns at most 16 immutable publication metadata rows. Its cursor is exclusive and resolved from the retained snapshot's durable publication sequence. Keep returned snapshot and FX IDs with any downstream read-only record that needs reproducibility. A value alone gives A10 no quote, listing, financial or settlement authority.

Provider contributions are explicit immutable lifecycle data with authenticated provider identity/revision and registry claims. They may supply bases, exact supported component selectors and opt into supported detached derivation/variant data. Conflicting claims reject the entire candidate even if prices match; a server override remains highest value precedence without concealing conflicts. Registration/removal publishes a new immutable snapshot. No arbitrary callback runs on journal/storage workers. Mod items without a registered contribution remain unavailable even when crafting recipes exist.

### Generic provider registration

Call the provider factory from the contributing loaded mod's own code/module on the server thread. The handle authenticates that module's namespace and is scoped to the current session; passing another provider ID does not grant ownership. All claimed item IDs must already exist in the registry. This example assumes the generic `demo` mod registers its own ore, plate and inlaid-plate items, with a supported ordinary plate recipe. It adds no built-in integration.

```java
import ca.emilybelnavis.mcmods.economy.api.ItemValuationIntegration.*;

var services = Economy.serverServices(server);
var provider = services.itemValuationProvider(server);
var data = new Contribution(
    "demo:prices", "1",
    Set.of("demo:ore", "demo:plate", "demo:inlaid_plate"),
    List.of(new Base("demo:ore", EconomyCurrency.CAD, "2", 1)),
    List.of(), // optional ExactSelector(Identity, Base) entries
    Set.of("demo:plate"), // opt into the captured supported recipe graph
    Map.of("demo:inlaid_plate", new MaterialRule(
        List.of(new Material("demo:plate", 1, 1),
                new Material("minecraft:gold_nugget", 1, 1)), null)),
    Set.of("economy:ordinary-recipes/v1", "economy:component-values/v1")
);
provider.register(data).thenAccept(saved -> {
    // Retain saved.id()/revision(); schedule any game access on the server executor.
});
```

`MaterialRule` is supported detached material-sum data, with an optional positive CAD content amount (`null` means none); it is not executable code. Exact selectors retain full canonical component identity and supply an explicit price for that selected supported state. Derivation opt-ins do not enable arbitrary serializers or callbacks. Publish a changed revision by registering a complete replacement contribution. `provider.remove("demo:prices")` publishes removal; original snapshots and values remain retained. Treat asynchronous failures as a rejected candidate, preserve the prior contribution, and do not infer successful registration from merely constructing the data. Explicit server overrides have highest value precedence.

The ordinary adapter opt-in declares plain consumed material semantics for the provider's claimed items. It enables those IDs as inputs to reviewed vanilla serializer classes without invoking item remainder methods. Do not opt in a returned-container or custom-consumption item. Mod equipment/container behavior is not inferred from its Java class: use a supported exact selector for component-bearing mod states. A plain override cannot strip unknown component state; such a state remains unavailable when its exact selector is suppressed by a server override. Re-register providers each server session after foundation readiness; accepted registrations survive resource reloads in that session, while original durable manifests remain readable after restart/removal. A revision cannot be reused with different data while that provider is registered.

## Prospective trader-source foundation

A9-05 adds internal source-neutral account/cash services alongside unchanged EmeraldV1 facts and pricing. `SourceRef` binds the exact world/market/item scope, source revision/epoch, valuation/input versions and activation identity. New quotes retain exact component identity, the complete selected valuation result/proof, catalog/comparison/reputation witnesses, FX and rounded terms. New settlement preparation compares authoritative source identity before any effect; the journal returns committed originals before current source, expiry or admission checks.

Reference pricing uses HALF_UP per goods unit with a one-minor-unit floor, then multiplies that published unit by goods count. It has no accumulating carry. Old residuals stay in their original epochs with immutable retirement records and no payout. A conservative rounded-cohort proof can refuse even a priced item; it never changes rounding or silently widens spread. Existing financial/foreign-FX rounding remains separate.

Valid valuation publication advances only previously enabled source scopes in the same transaction. The current implementation limits one atomic refresh to63 scopes, a retirement boundary to64 old carry keys, and a complete cohort scan to64 bindings/32 equivalent offers. Oversized16KiB quote/fact envelopes refuse. The public read API grants no activation authority. A9-06 operator controls authenticate initial activation and validate live runtime recipe/provider generation while reloads are pending, in addition to durable source checks. Reference values still confer no asset qualification or settlement authority. [Transition contract](../.codex/architecture/trade-source-transition.md).

## Reference trader activation

A9-06 wires explicit operator review/publication and the source-neutral account/cash controller under schema52, with accepted native checkout/presentation and read-only history validation in its documented scope. New activations use `economy:reference-unit/v2`, proving the rounded cohort gap at the captured and authoritatively revalidated demand/reputation state for all larger quantities. Retained V1 quotes keep their original proof/rounding interpretation. Current source/manifest and live session/resource/provider generation are checked before new effects; worker-service original outcomes still resolve first. The UI's physical/native preconditions and Claims recovery remain distinct from backend replay guarantees.

Native custody components (`values`/`patch`) and valuation projection are stored separately and linked by versioned source data. Exact predicates only: do not reinterpret required-component matching as exact or strip extra component state. Activated policies omit unavailable/unreviewed offers, retaining reasons in operator/API reads. Native required-component buybacks are not enabled by this adapter. Registration of a mod reference value alone does not authorize a native component adapter or trade. [Activation architecture and acceptance](../.codex/architecture/reference-trader-activation.md).
