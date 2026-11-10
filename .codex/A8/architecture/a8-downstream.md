# A8 downstream contracts — A9 entry boundary

A8 is accepted in its [recorded scope](a8-acceptance.md). A9 remains unselected and unimplemented. This handoff defines replacement boundaries; it does not execute recipe valuation, activate an adapter or change any saved economic history.

## Delivered surfaces

- `BankingIntegration` v2: explicit-currency prime/current-at-time reads and existing owner-scoped financial services. The argument-free-currency prime read remains the legacy global configured policy. New ordinary loans capture their explicit currency policy; historical fixed/subprime terms retain their meanings.
- `EconomicSimulationIntegration` v1: authenticated immutable cached availability/coverage/mode/source snapshots and asynchronous exclusive financial-day history, at most16 outcomes. Neither read advances time nor grants money authority.
- `TradePriceSource` / `Basis`: detached original price principal, source UUID/revision, captured effective FX UUID, currency and exact rational minor units per pinned standard quantity. Pricing still owns demand/recovery, comparison/cohort spread limits, reputation, cumulative carry and conservative rounding.
- Immutable merchant receipts/observations/window/model/rate history, original journal operation lookup, stock/progression and independently witnessed physical custody. These remain authoritative when a future reference price changes.

## What A9 must add before enabling a replacement

Current durable `NpcFacts.Terms`, `NpcCashFacts.Terms` and production controller View use `TradePriceSource.EmeraldV1` directly. A8's immutable production manifest prevents live initial-source drift. `CatalogPricing.compare` accepts the general interface, but that alone is not a complete runtime replacement API. There is no mutable active price-source/epoch registry in A8, and current settlement does not independently compare a captured source UUID/revision to such a registry.

1. Add an additive source-neutral durable quote/fact version with strict registered codecs/validators. Retain all old EmeraldV1 terms, decoders, effective FX identities and original outcomes; do not reinterpret a v1 payload as A9 reference valuation.
2. Journal an authoritative prospective active source UUID/revision and epoch with explicit world/market/item applicability and an immutable implementation/input/provenance manifest. Keep ready/missing/unsupported statuses distinct. A missing A9 input is unavailable, not an implicit fallback to1 CAD/emerald.
3. BOOK captures the active source/epoch and complete equivalent-offer/reputation witness. For a new SETTLE, resolve the original operation first, then require the active source UUID/revision/epoch still matches the quote in the same authoritative preparation. Any switch invalidates old uncommitted quotes before financial, stock or physical consumption. A committed original still returns its old receipt after a switch, expiry or rejected current admission; never redo money, goods or progression.
4. Define and journal the prospective price/carry transition. Carry keys include market/item/player/side/epoch. Current epoch changes with unhandled residuals deliberately reject. Choose an approved explicit residual treatment; never silently erase/reuse fractional carry, reset balances, merge actors or change historical rounding. Preserve stock/restock clocks and demand/recovery anchors except through their explicitly recorded transition contract.
5. Feed the existing original principal/pricing composition rather than bypassing spread, positive-payment, representability, cohort, component/secondary-item, capacity, stock or explicit foreign-FX checks. Keep quantity definitions versioned. A9 does not add random listing discovery or silently widen unsupported predicates.
6. Preserve prior financial receipts, source/config/FX UUIDs, balances, exact custody lots, activity observations, immutable windows and prime/FX outputs byte-for-byte under their original versions. New reference prices do not revalue prior activity or change captured fixed-loan terms. Item reference price eligibility is separate from accepted wealth/credit/fee valuation sources.

The selected A9 implementation must provide its own narrow evidence for a pre-switch quote refusing without effects, a fresh post-switch quote using the new source, original committed replay remaining unchanged, and the chosen carry/residual transition. Choose focused named checks under the project verification policy; this handoff creates no automatic full-suite or repeated upstream acceptance gate.

## Other consumers and exclusions

A10 may extend player/multiplayer experience using existing offered actions, current grants, original quote/operation identities and protected custody boundaries. Broader performance, platform and physical interruption evidence remains explicit. B-series securities, exchange markets, expanded lending and new external product integrations require their own selection/contracts. No commodity backing, recipe valuation execution, banking source qualification, reset or hypothetical deployed-trader migration is authorized by A8 completion.

[Public API](../../../docs/INTEGRATION_API.md) · [server behavior/limits](../../../docs/SERVER_GUIDE.md#economic-simulation-and-trading) · [A8-08 evidence](../verification/A8-08.md).
