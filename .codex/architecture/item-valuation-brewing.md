# Potion brewing reference costs — A9-03-02

Current extension: [A9-03-03 component/container architecture](item-valuation-variants.md) records v4 typed identity, premium overrides and recursive publication. Earlier version/package descriptions below remain historical in their recorded scope.

Delivered 2026-11-08. [Handoff](../A9-03-02.md), [evidence](../verification/A9-03-02.md), [shared policy](a9-shared-contracts.md), [pinned source freeze](a9-source-contracts.md). Extends [ordinary recipes](item-valuation-recipes.md) and the [V50 snapshot store](item-valuation-snapshots.md). No schema, financial authority, source activation, dependency or scheduler change.

## Source capture and identity

`VanillaBrewingCapture` reads the actual server's final `PotionBrewing` instance on the server thread. The narrow access transformer exposes its three lists and nested Mix type; generated record accessors suffice. One further read access exposes MobEffectInstance.hiddenEffect so hidden chains reject instead of being silently dropped. Existing Gradle access-transformer wiring and bundled platform are preserved.

A reference whitelist is created with the reviewed `new PotionBrewing.Builder(enabledFeatures)`, `addVanillaMixes(builder)` and `build()` sequence, which posts no NeoForge registration event. Compare the actual ordered container/potion/form tuples to that baseline. Check exact brewing class, registry-reference holders and plain single-item Ingredient.ItemValue; custom/tag/patched ingredient semantics reject. No `mix`, `assemble`, custom recipe predicate/output, generic item expansion or mod codec is invoked. `getRecipes()` inspects only the extra registry's list/class/count, never the recipes' behavior.

Pinned vanilla has 63 potion transitions, two form transitions and three container forms. Expanding potion transitions across forms and form transitions across 46 definitions yields 281 concrete state edges. Contrary to an ambiguous earlier EMPTY shorthand, this registry has **no `minecraft:empty` potion**: empty PotionContents means absent identity and is unavailable. LUCK is registered but has no accepted survival brewing path and remains unavailable. Mundane, thick and awkward are actual supported intermediate identities, not guessed premiums.

`BrewingGraph` is detached immutable version-one data with adapter/rules hash, actual ordered-lists hash, vanilla compatibility, concrete form+potion states, semantic mix IDs, ingredient/remainder identity, and definitions with effect ID, amplifier, duration, ambient/visible/icon flags and sorted known cure names. Exact Potion/MobEffectInstance classes, no hidden effects, bounded ranges and only milk/honey/protected_by_totem cure names are supported. Custom colors/effect lists, extra stack components or absent/unknown potion IDs are not stripped to a vanilla identity.

Internal request projection is canonical JSON `{"minecraft:potion_contents":{"potion":"minecraft:awkward"}}`, alongside the form item ID. Only this supported typed projection is admitted now; public stack/identity/grant routing remains A9-04 work. Definitions bind the exact effect/strength/duration semantics in the retained manifest; names alone are not the entire provenance.

Any extra custom registry recipe, list/order mismatch, unsupported container or definition sets vanillaCompatible=false with captured exclusions. All brewing values then refuse conservatively, while ordinary reference values can still use that manifest. Custom recipes can run before vanilla in Minecraft; capturing only familiar tuples would not prove absence of shadowing. Unknown callback internals are not serialized or represented as a complete supported source. No custom adapter is installed in this package.

Capture limits include 4,096 definitions/diagnostic entries, 100,000 mixes and 32 MiB graph/list payloads, additionally constrained by the complete manifest quota. Oversize capture rejects the candidate rather than publishing partial ready data.

## Exact costing and supported acquisition

`BrewingValues` selects the cheapest fully known supported acyclic path through exact concrete potion states. Mixes sort by deterministic semantic identity for ties. One `ValuationBudget` is shared by brewing state/candidate visits and `RecipeValues.Materials` dependency visits, including their ordinary recipe traversal and absolute depth. The 10,000-work/32-depth/4,096-bit limits abort the root; a cheaper supported candidate found before exhaustion is not returned as a partial minimum. No ancestor-dependent recursive result is cached globally. Existing root caches remain keyed by full snapshot, FX, identity, currency and quantity.

Drinkable water is one exact glass-bottle material plus typed free-water content. Every step is:

`prior per-bottle exact cost + exact ingredient / 3 + exact blaze powder / 60`

Fuel comes from 20 operations per powder and three reference outputs. Partial stand occupancy changes no reference cost. Ingredients, bottle and powder use the ordinary exact-material seam with the same captured FX and no intermediate published-unit rounding. No labor, time or potion-effect premium is added. Strength/duration/corruption and splash/lingering paths preserve the prior bottle/content value instead of dividing it again or adding another potion bottle.

Dragon breath is a required **brewing-specific acquisition input**: one empty bottle plus approved CAD25 collected content, or an explicit whole-item source if configured. The catalog already classified it as VARIANT, so the general ordinary evaluator correctly refused it; the initial focused check exposed this prerequisite. `Materials.dragonBreath` supplies the narrow typed rule, under the same budget/FX. It does not enable general variant/container recursion or make arbitrary component-patched dragon breath priceable. The direct generic dragon-breath item view remains deferred to A9-03-03.

Charge the dragon-breath input's full bottle+content cost once per three outputs. Its returned empty bottle is explicit GLASS_BOTTLE_NO_SALVAGE metadata, with no value credit, extra input or second potion shell. A lingering proof can legitimately contain **two bottle derivations**: the potion's single bottle, plus the bottle already purchased inside the shared dragon-breath ingredient. That is not two potion bottles. Ordinary returned-container crafting remains excluded.

Default exact CAD path: water .10; awkward `.10+1/3+4/60=.50`; splash awkward `.50+.50/3+4/60=11/15`; lingering awkward `11/15+25.10/3+4/60=55/6`. Publish .10/.50/.73/9.17. Quantity three uses the published 9.17 unit, total27.51. Under captured CAD-per-USD1.396, lingering exact minor amount is 687500/1047 USD cents, published657 cents, quantity three1971 cents. These reference prices do not include or replace settlement FX fees.

## Publication, history and proof

Manifest v3 `economy:brewing-recipes/v1` embeds the brewing graph alongside ordinary graph/source data. Its hashes include the acquisition/allocation/accessor rules and captured definitions/lists. V1 base-only and v2 ordinary manifests omit the new field from canonical JSON and keep their prior interpretation and proof format. Old manifests never acquire new potion values merely because the running binary has brewing support.

The existing generation-gated lifecycle captures/prepares/publishes v3 on startup/reload. Valid material/config changes create new snapshots; retained snapshots keep original sources/definitions and require captured retained FX for historical reads. The actual server brewing instance is bootstrapped at construction, not recreated by ordinary recipe reload, so capture reads that actual instance each time rather than assuming RecipeManager JSON is a brewing registry. No new record schema, automatic activation or mutable callback is used.

Proofs retain full selected child hashes and source/mix/state identities, with explicit allocation and remainder kinds. The root hash is complete before returned explanation truncation at256 nodes; child truncation propagates. Numeric completion precedes display truncation. Final unit money uses existing HALF_UP/minimum-one rules and checked quantity multiplication. Future durable consumers must save their original result and required witness; a hash is not permission to reinterpret history.

## Acceptance and downstream limits

Real SQLite fixtures cover transformed/shared-batch arithmetic, foreign rounding, single potion bottle versus shared dragon-breath bottle, cheapest acyclic alternatives, unknown/custom refusal, combined material/brewing depth limits, retained v3/config changes and v1/v2 behavior. Two directly affected ordinary budget/history methods passed as well. Actual client capture validated all46 definitions/281 edges/three forms, strength/duration identities, material reload and old values; it stopped normally with clean save evidence.

No every-potion permutation, arbitrary mod callback/hidden state or broad performance acceptance is claimed. Some long/complex requests may explicitly hit the shared limit. A9-03-03 is next eligible only upon explicit selection: add generic variants/containers, reuse the typed potion total exactly once, and keep v1/v2/v3 encodings/algorithms intact. Public SPI/API and trader source changes remain separate later packages. Preserve pinned versions and working registered client/server runs.
