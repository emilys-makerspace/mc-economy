# Reference-price trader activation

A9-06 is complete in its documented scope; [handoff](../A9-06.md) and [evidence](../verification/A9-06.md) record accepted native checkout/presentation and read-only history validation. Extends [V51 transition foundation](trade-source-transition.md) and [item API](item-valuation-api.md). Minecraft1.21.1 / NeoForge21.1.252 / Java21 remain pinned.

## Explicit review and prospective publication

Connected permission-level2 players use `/economy valuation review <trader UUID>` for a loaded/captured trader, then `/economy valuation activate <trader UUID> <review hash>`. The review shows pinned vanilla numeric/classification/component/resource coverage and every actual captured offer's neutral availability/reason. It binds current valuation, FX, catalog/binding revisions and report data in a hash. Activation recomputes and compares that review. Missing-source gaps or no supported listing refuse publication. The ordinary registry cannot be considered covered merely because an item is noncraftable.

`PartyAuthorization.inspection` authenticates the current player; ServerServices checks world/session ownership and requires its own session's valuation Lease. Detached live proof is revoked on disconnect/permission loss. Pending operator work is bounded4/server and saved reviews16/server, owner-bound. Activation/reload gating locks preserve worker transaction admission against synchronous reload invalidation. Operator callbacks run on server-tick delivery, never arbitrary journal callbacks.

SQLiteV52 adds immutable `reference_activations` alongside unchanged V51 source facts. One activation journals its actor/review/captured manifest/pricing implementation and all reviewed exact scope keys atomically with source boundaries/carry retirement. Unsupported original scopes are included so they cannot retain an emerald fallback. The trader policy also hides later unreviewed offers until explicitly reviewed/activated; it does not discover or add listings. Already-enabled market/item scopes shared by traders retain the same authoritative applicability. No activation occurs at startup. Valid valuation reloads advance only existing scopes atomically as before.

A source refresh supports at most63 enabled scopes in one publication (one manifest fact plus at most63 source facts). An activation covers at most32 unique captured offer keys; exceeding total scope quota refuses instead of partially publishing. Old receipts, stock/restock, market/currency assignment, demand anchors and financial/activity/rate/loan histories are not rewritten.

## Exact native identity and safe components

Native offer custody uses full `values` plus `patch` component JSON; valuation uses a typed projection. `ReferenceTrading.capture` obtains `VariantIdentity.components` on the server thread from each already-generated exact native result. It never substitutes a plain registry ID for a component-bearing result. A bounded world cache (4,096 entries) links full custody price key to projected state and valuation snapshot. Eviction or a generation mismatch makes a listing unavailable until safely recaptured.

V2 `ReferenceSource` stores a versioned projection extension while retaining exact native custody components and scope. Its codec omits that extension when absent, preserving A9-05 source/fact encoding. Historical proof evaluation uses the retained typed projection/manifest/FX; live controller Admission also rechecks native offer/reputation/inventory/capacity and current session/generation/source/cohort before physical reservation or settlement.

Only exact supported predicates participate. Native SELL/buyback offers use REQUIRED_COMPONENTS, including empty predicates; this package does not relabel them as exact or value arbitrary extra state as plain. They are disabled in activated scopes. Unknown custom components, operator-only items, absent provider data and projection failures likewise remain unavailable. Mod registration is necessary but does not imply a native component adapter. Other item API component states remain as documented in the variant/API contracts.

## Production controller and history

`TraderController` selects reference sources only for activated scopes/policies and routes account/cash BOOK/SETTLE, abandon/return, protected delivery and native progression through the A9 services. Inactive, unselected scopes retain legacy behavior. Activated-but-unavailable offers and Request quote actions are omitted without a player-facing reason. Operator/API diagnostics retain reasons. A durable activation marks menus for refresh, and valuation Lease changes refresh/revoke pending reference admission. Exact quantities, saved market currency, selected account/cash/FX rail and combined output capacity remain existing contracts.

Reference principal is the published item unit in market currency times pinned standard quantity. Original emerald quantities are used only in the dimensionless original cohort ratio, never multiplied into the new reference principal. Goods review displays the published effective unit and total. Settlement uses that captured total and records actual rounded activity once.

Backend journal services resolve committed originals before current-source/expiry/admission checks; A9-05 evidence remains applicable. The controller still enforces present physical/native/session checks before a UI attempt; Claims remain its independent recovery route. Do not conflate backend original replay with arbitrary UI retry after native/capacity/reputation refusal.

## Versioned rounded spread proof

New activation uses `economy:reference-unit/v2`; retained v1 requests keep their original sufficient `.9` envelope. Both preserve demand recovery/integrals, reputation bounds, HALF_UP goods-unit publication, minimum1, checked unit×quantity totals and no carry. Both account/cash services and historical validators dispatch by the captured SourceRef implementation.

V2 uses the captured, authoritatively revalidated demand/reputation state. Buy average integral per unit is monotone increasing with quantity; sell average is monotone decreasing. Compute the full cohort's smallest ask and largest bid at theoretical minimum one goods unit (`q=1/standardQuantity`), apply HALF_UP/minimum1, and require bid<=.8*ask. Monotone rounding proves the gap for every larger admitted quantity. A demand/tick/catalog/source change invalidates the quote and requires a new proof. This admits a5/4-cent witness while still refusing2/1.6; it never widens spread or changes rounding to force availability. No new fractional carry or change to supplier-FX/financial rounding.

## Numeric coverage and bounds

`ValuationCoverage` reports1,333 pinned identities separately:349BASE/760DERIVED/109VARIANT/115operator-only; extra unregistered mod IDs are separate. One reviewed representative state per survival ID is not every component permutation. Missing acquisition/recipe paths, unsupported component states and resource/FX limits are distinct maps. Hash reuse avoids serializing the same manifest1,218times during review.

Coverage exposed previously rejected NeoForge Compound/Difference ingredient data. The pinned final Ingredient wrapper and exact Compound/Difference/Intersection records now detach recursively by getters, item/tag sets and union/subtract/intersection only. No test/getItems/isSimple/type/recipe assembly callback is executed. Unknown custom classes remain rejected; final remainder/provider filters, depth32/work10,000 and alternatives4,096 apply. An empty custom set retains an adapter selector so it is unsatisfiable, never a free shaped hole. Captured expressions/alternatives enter the graph hash; historical graphs are unchanged.

The final live capture confirmed 0 missing-source gaps, 1,141 representative values, 0 representative component failures, 77 explicit work-limit refusals and 244 unregistered mod IDs. The native fixture completed authenticated activation, six-bread cash checkout, quiet omission, unit/total/receipt agreement, progression once and clean shutdown. Screenshot and read-only activation/source/account/cash/posting/valuation validation passed. Platform, multiplayer saturation, arbitrary predicates/components and abrupt physical interruption remain outside demonstrated evidence. No A10 or financial qualification integration is authorized.
