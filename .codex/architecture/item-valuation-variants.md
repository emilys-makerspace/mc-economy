# Component valuation and containers

Delivered by [A9-03-03](../A9-03-03.md); [evidence](../verification/A9-03-03.md). This extends [ordinary recipes](item-valuation-recipes.md) and [brewing](item-valuation-brewing.md), without changing their retained historical versions.

## Snapshot and identity

Manifest v4 uses `economy:component-values/v1`. It retains detached item metadata, enchantment membership/exclusions/levels, flower effects, dye colors, all 109 approved variant rules and effective premiums. The source hash and canonical payload bind these facts. V1–v3 omit the new fields and retain their dispatch. SQLite remains V50; staged bounded immutable manifests and captured FX are reused.

`VariantCapture` runs on the server thread. `VariantIdentity` reads explicitly typed vanilla components and known native classes; it neither mutates stacks nor invokes arbitrary component codecs, recipe assembly or custom callbacks. Unregistered mod items, unsafe custom prototypes and unknown/removal patches refuse valuation. Canonical identities retain names and supported state rather than flattening an item to its registry ID. Root caching includes snapshot, FX, full identity, currency and quantity.

Supported component families include applied/stored enchantments, measured durability, literal names, repair cost (no XP charge), potion contents, container slots, pot decorations, fireworks, suspicious stew, ominous levels, bounded book/map metadata and whitelisted bee/bucket entity state. Plain native literal text is limited to 16,384 characters and books to 100 pages. Unsupported text styles, custom data/effects/modifiers or unknown fields return typed unavailability. Bundle remains operator-unpriced with its feature disabled. These adapters support reviewed states, not arbitrary component permutations.

## Exact pricing

Equipment uses `(base + distinct compatible beneficial premiums) * (1 - distinct curses / 4) * remaining / max`. Validate levels, membership and exclusions; never clamp or silently strip. Stored books use ordinary book plus compatible stored premiums, with curses and no equipment membership requirement. Names and repair cost add zero without suppressing other properties. Exact zero for a fully broken supported item reaches the ordinary one-minor-unit publication floor.

`enchantments.json` preserves all 42 approved definitions (40 beneficial and two curses). Optional `enchantmentPremiums` configuration supplies a complete positive CAD array for each selected beneficial enchantment's supported levels. Unknown IDs, curses, wrong lengths or invalid amounts reject the candidate. Overrides enter immutable snapshots, so reload never reinterprets historical proofs.

`variants.json` maps approved aliases, degradation ratios and explicit typed contents. Buckets use shell plus content, water content is free, and pot patterns sum their actual four materials. Explicit pot shell overrides retain the pattern material difference. Brewing is reused as one complete potion value with its bottle already included. No salvage, labour or speculative callback cost is added.

## Recursive publication

A container is its individually modified empty object plus each child's published unit amount times count. Round each object/child HALF_UP with the normal minimum once; then use checked integer sums, avoiding cross-child fractional carry. Parent curses/durability do not discount children. Missing child values make the whole result unavailable.

Limits are eight container levels (including empty holders), 4,096 visited children, 64 KiB identity, JSON depth64, existing graph depth32/work10,000, exact-number bit bounds and 256 returned proof nodes. Slot bounds follow captured supported vanilla holder sizes. One evaluation shares material/brewing work limits throughout recursion. A limit failure returns no money. Proofs bind exact component state, shell/content steps, manifest and FX publication.

The public item SPI, financial source transition and trader activation remain A9-04/A9-05/A9-06 work. A reference cost does not qualify an asset or authorize a trade.
