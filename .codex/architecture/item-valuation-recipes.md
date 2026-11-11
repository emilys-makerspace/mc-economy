# Ordinary reference recipes and processing — A9-03-01

Delivered 2026-11-08. [Handoff](../A9/A9-03-01.md), [evidence](../A9/verification/A9-03-01.md), [shared policy](../A9/architecture/a9-shared-contracts.md), [source freeze](../A9/architecture/a9-source-contracts.md). Uses the existing [V50 snapshot store](item-valuation-snapshots.md); no schema/dependency change, new database or scheduler.

Current extension: [A9-03-02](item-valuation-brewing.md) adds v3 brewing capture/reads and a shared exact-material budget. The v2 ordinary rules/encoding below remain frozen.

## Capture and immutable identity

`OrdinaryRecipeCapture` runs on the server thread after installed resource/tag capture. It accepts exact `ShapedRecipe`, `ShapelessRecipe`, `StonecutterRecipe`, `SmeltingRecipe`, `BlastingRecipe`, `SmokingRecipe`, `CampfireCookingRecipe` classes and their matching standard serializer identities before accessing stored ingredients/result. No assembly, matching, codec, world/remainder or mod callbacks execute. Plain outputs retain registry ID/count; patched/empty outputs reject.

`RecipeGraph` retains recipe ID/serializer, ordered consumed slots (including shaped holes), sorted original item/tag selectors and supported expanded alternatives, output ID/count, partial-alternative exclusions and full expanded tags. Every list/map is detached and immutable; graph encoding and the frozen rules hash enter the manifest. Runtime recipe input hash combines selected raw resource content, installed recipe ID/class inventory and detached graph hash, so quantity/tag/output changes cannot silently reuse an identity.

Check `Ingredient.isCustom()` before reading values; allow only vanilla ItemValue/TagValue. Direct item stacks must be plain/count one. Expand tags from the rebound captured registry, never `Ingredient.getItems()` (NeoForge inserts a BARRIER placeholder for an empty tag). No selectors is an empty shaped slot with no cost; selectors with no supported members are unsatisfiable, never free. Repeated consumed slots remain repeated even when their identities coincide.

The five source-declared vanilla crafting remainder inputs are water/lava/milk buckets, dragon breath and honey bottle. Filter these alternatives explicitly. Unknown mod input remainder semantics also require a later trusted adapter; no dynamic remainder method is called. Retain safe alternatives of a mixed ingredient with exclusions. Custom ingredient implementations and unsupported/smithing/special classes are whole-recipe exclusions. Reviewed acquisition anchors still value their outputs independently of excluded recipes. No mod output is automatically derived; internal contributed explicit bases remain supported but derivation opt-in/public SPI is not delivered here.

Caps: 100,000 recipes including whole-recipe exclusions, 4,096 selectors/alternatives per ingredient, nine recipe slots, 32 MiB detached graph capture budget, plus existing source/tag and full-manifest limits. An aggregate limit rejects the candidate; it does not publish a truncated graph. Actual pinned capture witnessed 1,191 supported nodes and 119 whole-recipe exclusions across 1,310 loaded recipes; partial ingredient exclusions are additional metadata, not evidence all nodes are priceable.

## Versioning and retained reads

`ValuationData.Manifest` v2 uses `economy:ordinary-recipes/v1` and embeds `recipes`. V1 `economy:base-values/v1` has no recipes field in canonical output, preserving its exact historical JSON shape. The strict decoder reconstructs all provenance under the corresponding version; v1 requests still use BaseValues, with no new inferred recipe value. Both versions use unchanged staged chunk/publication fact codecs and retention/reopen validation.

Live startup/reload now prepares v2 candidates. The existing generation/readiness gate, worker scheduling and final atomic pointer commit remain authoritative. Snapshot-scoped compiled/root caches now dispatch through `RecipeValues`; keys include snapshot UUID, FX UUID, exact identity, currency and quantity. No ancestor-dependent recursive result enters a shared cache. There is no new public integration surface, quote consumer, source switch or change to existing financial history.

## Exact search, cycles and limits

Anchors/overrides terminate before canonical rules or traversal. Every input cost converts to exact target minor units under one captured FX publication, without unit rounding between recipe levels. Ordinary nodes sum each consumed slot's cheapest supported alternative then divide by output count. Sort recipes by registry ID and alternatives by item ID; equal costs retain the first recipe/ordered choices. True holes add nothing; missing/unsupported children never contribute zero. READY can record SUPPORTED_PATH_WITH_EXCLUSIONS; an excluded path is not a proof it is numerically more expensive.

DFS uses path-local ancestors and no local memoization, avoiding context-poisoning between sibling paths. A cycle on one path does not reject a different supported acyclic route. Depth is at most 32 nonterminal dependencies; work is at most 10,000 dependency visits, recipe candidates and alternative evaluations, including anchors. Numeric rationals are bounded to 4,096 bits. Any depth/work/numeric limit aborts the root, including after an earlier candidate was found. Never return a partial minimum. Root cache only stores completed request outcomes; repeated identical requests reuse the same snapshot/FX result.

After exact root completion, HALF_UP the unit minor amount with minimum one, then checked multiply by quantity. Unknowns and overflows return no partial money. Free water remains a typed input only. Existing FX fee/conversion settlement algorithms remain unchanged.

Proof nodes bind manifest/FX/currency, recipe/canonical-rule ID, output count, chosen input identities and complete child hashes, exact unit cost, source provenance and processing delta. Returned explanation is at most 256 selected nodes with a truncation flag; its root hash binds the complete selected derivation before display truncation. Numeric computation is complete first. Future durable consumers must retain their result and required selected witness, not use a hash as permission to recompute under new data.

## Canonical sticks and calibrated counterparts

Canonical stick source is two oak planks divided by four, after explicit sources but before alternate stick recipes. At approved anchors: oak planks CAD .50 and sticks .25; cherry planks CAD 2.00, without raising generic stick/tool-handle costs. Actual crafting consumption is unchanged.

The frozen 13 raw→processed pairs use raw cost divided by .75 as a **canonical calibrated source**, after explicit sources and before ordinary alternatives. Require a captured supported one-to-one cooking witness containing the named raw input; choose the first qualifying recipe ID for provenance. Record exactly one PROCESSING_VALUE delta. No witness means unavailable rather than guessed processing. A processed override terminates directly; downstream nuggets/blocks/tools/food inherit that exact processed cost without another uplift. Other cooking remains ingredient-only, excluding fuel/time/XP.

This precedence resolves a concrete pinned-source incompatibility found by the first client witness: nether gold ore is an approved CAD8 anchor and standard smelting yields one gold ingot. Unrestricted cheapest-path evaluation would price the ingot at CAD8, contradicting the approved CAD24 target and raw gold CAD18 ratio. The canonical frozen counterpart rule preserves the settled calibration without changing ore anchors, recipes or historical values. Copper/iron/gold are CAD3/6/24; cooked meats/fish and potato/kelp/chorus follow their approved raw anchors and exact / .75. This refines engineering precedence, not approved amounts.

## Acceptance boundary

Focused real SQLite fixtures cover exact output quantities, species propagation, canonical stick/processing precedence, downstream costs, cycle-context correctness, deterministic ties, budget refusal, empty-tag semantics, v1/v2 persistence/reopen and cache separation. A disposable client captured all seven serializers and witnessed a real datapack recipe output change and retained old graph results. No full suite, arbitrary-state coverage or automatic pricing of every catalog row is claimed. Some paths remain unavailable until brewing/variant packages are implemented; unsupported mod remainder behavior still needs explicit support.

A9-03-02 brewing is next eligible only upon explicit selection. Keep v1 base-only and v2 ordinary encodings/semantics intact when adding later manifest fields/algorithms. Do not reinterpret saved manifests with a newer graph or processing table. Working registered client/server development runs and pinned baseline remain required downstream.
