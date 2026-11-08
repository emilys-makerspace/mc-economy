# A9-01 source feasibility and interface freeze

Current extension: [A9-04 public API/SPI](item-valuation-api.md) delivers v5 provider data and authenticated reads; prior package descriptions below remain historical in their recorded scope.

Current extension: [A9-03-03 component/container architecture](item-valuation-variants.md) records v4 typed identity, premium overrides and recursive publication. Earlier version/package descriptions below remain historical in their recorded scope.

Accepted source/document checkpoint, 2026-11-07. Supplements [technical revision 1](a9-shared-contracts.md), preserving approved prices and gameplay. No runtime service, adapter, migration or activation is delivered. [Evidence](../verification/A9-01.md) separates source feasibility from runtime acceptance.

## Pinned source map

Archive: `sourcesWithNeoForge_cc7c60e94efba2ed86f41906afc13210f16b17a8_output.zip`; metadata names NeoForge 21.1.252; SHA-256 `c46df93e0d3228bf52dddc5edf79dd2c338ee097e866af551bee2ac8dc734825`. Paths in this table are archive-relative under `net/minecraft/`, except NeoForge events.

| Concern | Pinned seam and restriction |
| --- | --- |
| Recipes | `world/item/crafting/RecipeManager.java:147–155`: `getOrderedRecipes/getRecipes`. `ShapedRecipe.getIngredients/getResultItem`, `ShapelessRecipe`, `SingleItemRecipe` (stonecutting), `AbstractCookingRecipe` supply detached ingredients/output. Trust exact reviewed classes and serializer identity, not overriding subclasses. Never call `assemble/matches` or world-sensitive remainder methods. |
| Ingredients | `Ingredient.java:181–199`: `getValues/isCustom`; `ItemValue.item()` and `TagValue.tag()` permit detachment. Reject custom values before accessing them, then expand captured rebound item tags and sort identities. `isSimple` alone is insufficient. |
| Reload | `server/ReloadableServerResources.java:113–143`: listeners precede separate `updateRegistryTags`. `server/MinecraftServer.java:1502–1518` installs resources then rebinds tags on the server executor. `net/neoforged/neoforge/event/AddReloadListenerEvent` alone does not prove current rebound tags. |
| Brewing | `world/item/alchemy/PotionBrewing.java:19–22,51,125–164,167–299`: private potion/container lists, package-private `Mix<T>`. `getRecipes()` exposes additional NeoForge recipes only. Server `potionBrewing()` owns the bootstrapped instance; ordinary recipe reload does not recreate it. |
| Brewing allocation | `world/level/block/entity/BrewingStandBlockEntity.java:32,167–187`: 20 operations per powder, three bottles, one consumed ingredient and remainder. Read source rules; do not invoke brew hooks for valuation. |
| Components | `core/component/DataComponents.java`: damage, applied/stored enchants, potion, container, bees, bucket entity data, pot decorations, fireworks, stew, books and unresolved loot. Exact custody serialization and valuation projection are separate. |
| Enchantments | `world/item/enchantment/Enchantment.java`: `definition().supportedItems/maxLevel`, `exclusiveSet`, `areCompatible` (186–187). Detach holder membership/ranges; no mod `supportsEnchantment` callbacks on workers. |
| Containers | `ItemContainerContents.nonEmptyItemsCopy/getSlots/getStackInSlot`, `BundleContents.itemCopyStream/itemsCopy` in `world/item/component/`. Copy at capture and preserve slot/order/count. Bundle remains feature-disabled/unpriced in the baseline. |
| Special variants | `FireworkStarRecipe`, `FireworkStarFadeRecipe`, `FireworkRocketRecipe`, `TippedArrowRecipe`, `SuspiciousStewRecipe` under crafting: explicit source-to-material mapping, never special recipe execution. |

Repository seams under `src/main/java/ca/emilybelnavis/mcmods/economy/`: `api/Money/EconomyCurrency` define long minor units; `fx/EffectiveFxSnapshot` captures immutable rates and identity; `fx/FxQuotes` establishes source-rate/target-rate orientation. `foundation/ServerServices.calculate/storage` provides bounded workers and session ownership; `Economy.starting/stopping` owns lifetime. `journal/JournalEngine.process` resolves authenticated original outcomes before preparing a new operation. `recovery/RecordCatalog` and storage validation need additive codecs later. No valuation listener, manifest persistence or active source registry exists today.

## Detached API/SPI freeze

Implementation contracts below are not compiled Java declarations. Public package: `ca.emilybelnavis.mcmods.economy.api`; `ItemValuationIntegration.VERSION=1`, exposed through current `ServerServices`. Authenticate the current session's `PartyAuthorization.Grant` on every read.

- `Identity(String itemId, String canonicalComponents, int adapterVersion)`: immutable canonical UTF-8 JSON object, sorted keys, preserved array order, at most 64 KiB. Capture supported effective component state, including defaults, rather than treating an absent patch as an absent component. Canonicalization/registry versions enter the manifest. Preserve exact original custody state even when unsupported for valuation.
- `SnapshotSelector`: `Current` or `Retained(UUID valuationSnapshot, UUID fxPublication)`. Current captures manifest and one effective FX publication at admission; retained requires both identities, with no current-FX substitution or fabricated historical timestamp.
- `Request(Identity identity, long quantity, EconomyCurrency currency, SnapshotSelector selector)`; quantity 1–1,000,000. `Exact(BigInteger numerator, BigInteger denominator)` is reduced, positive denominator, at most 4,096 bits each.
- `Result(Status status, String reasonCode, Coverage coverage, Identity identity, long quantity, Optional<Exact> exactUnitMinor, Optional<Money> unitMoney, Optional<Money> totalMoney, Instant capturedAt, Optional<Instant> effectiveFrom, Optional<SnapshotRef> snapshot, Optional<Explanation> explanation)`. READY requires all numeric and captured identity fields; unavailable has no spendable partial amount. Capture time is admission time; effective-from is durable manifest publication, with FX effective time separately retained in provenance.
- `SnapshotRef` binds valuation UUID/revision, config/recipe hashes, provider manifest hash, algorithm/adapter versions and FX UUID. Coverage is COMPLETE or SUPPORTED_PATH_WITH_EXCLUSIONS. Explanation carries selected derivation hash/root, bounded nodes and truncation flag; numeric completeness never depends on explanation length.
- `Result currentCached(Grant grant, Request request)` accepts Current only; miss returns NOT_READY with PENDING reason. `CompletionStage<Result> evaluate(Grant grant, Request request)` handles both selectors. `CompletionStage<List<SnapshotMetadata>> history(Grant grant, Optional<UUID> beforeSnapshot, int limit)` uses exclusive durable publication sequence resolved from UUID, limit 1–16. Reads never advance economic time.
- Statuses: READY, UNPRICED_OPERATOR, MOD_UNREGISTERED, MISSING_INPUT, UNSUPPORTED_IDENTITY, UNSUPPORTED_RECIPE, CYCLE, LIMIT_EXCEEDED, OVERFLOW, FX_UNAVAILABLE, SNAPSHOT_UNAVAILABLE, NOT_READY. Reject malformed or unauthenticated calls at admission, rather than treating them as missing prices.
- `Contribution(providerId, revision, ownedItemIds, bases, exactSelectors, derivationOptIns, trustedAdapterVersions)` is immutable validated lifecycle data. Authenticate provider identity; validate claims against registry ownership or explicit integration registration. Two providers claiming an overlapping source identity reject the entire candidate, including identical prices. Operator override wins value precedence but does not hide conflicts. Removal publishes a new manifest. No public callback, SQL or settlement/mint authority.

Internal shapes: `RecipeNode(id, serializerId, orderedSlots, outputIdentity, outputCount, sourceHash)`, `IngredientAlternatives(sorted identities)`, `ProcessingRule(rawId, processedId, factor=4/3)`, `BrewEdge(fromPotion, fromForm, ingredient, toPotion, toForm, remainderRule)`, immutable `Manifest`. A9-02 owns canonical codecs/publication; A9-03 children populate detached data; A9-04 exposes API/SPI. Revision-1 quotas remain in force.

## Exact arithmetic and deterministic search

Convert decimal source anchors exactly to source-currency minor units divided by reference count. Target minor cost is `sourceMinor × rate(source)/rate(target) × targetMinorPerMajor/sourceMinorPerMajor`. Rates are reference-major per currency-major; use rational decimal rates from one captured effective FX publication. Missing historical currency membership is FX_UNAVAILABLE. Convert mixed-currency contributions under that same capture before combining. Cache keys include FX UUID; graph topology does not require rebuilding for each FX update.

Sort recipes by ID, preserve slot order, sort ingredient identities. Anchors and canonical rules terminate traversal. Select least exact cost per output; ties use recipe ID then ordered chosen identities. Ancestor sets are path-local; local memo keys include ancestors and remaining depth. Only fully completed root results enter the shared cache. Count candidate/alternative evaluations (including local cached visits) against the 10,000 budget, not elapsed time. A depth/work/numeric limit on a potentially cheaper supported route yields unavailable, never a partial minimum. Unsupported serializers are exclusions. CYCLE applies only if no supported acyclic route succeeds; anchored ingot→block remains valid despite block→ingot.

Named processing table, once per exact edge; different serializers share the same rule:

| Raw → processed | CAD raw → exact processed |
| --- | --- |
| raw_copper → copper_ingot | 2.25 → 3 |
| raw_iron → iron_ingot | 4.50 → 6 |
| raw_gold → gold_ingot | 18 → 24 |
| porkchop → cooked_porkchop | 1.50 → 2 |
| chicken → cooked_chicken | 2.48 → 248/75 |
| beef → cooked_beef | 3.43 → 343/75 |
| salmon → cooked_salmon | 4.29 → 5.72 |
| rabbit → cooked_rabbit | 5.25 → 7 |
| cod → cooked_cod | 6.83 → 683/75 |
| mutton → cooked_mutton | 7.80 → 10.40 |
| potato → baked_potato | .25 → 1/3 |
| kelp → dried_kelp | .10 → 2/15 |
| chorus_fruit → popped_chorus_fruit | .50 → 2/3 |

A9-03-01 source finding: named processed counterparts use this calibrated source canonically after explicit sources, before ordinary alternatives, with a supported one-to-one cooking witness. This prevents the approved CAD8 Nether gold ore from undercutting the approved CAD24 ingot. Multiply captured raw cost by 4/3, record PROCESSING_VALUE separately; do not apply again to nuggets/blocks/tools/downstream meals. Processed-item override terminates derivation. Sticks use two oak planks/four, CAD .25, regardless of bamboo/rare-wood alternatives.

Publish `max(1, HALF_UP(exactUnitMinor))`, then checked multiply by quantity. A 1/3-cent item publishes one cent, quantity three totals three cents. JPY floor is one yen. Half-durability iron sword with Sharpness III, Unbreaking II and Vanishing: CAD 73.96875 exact →73.97. Water is a zero-cost typed input/content, not a zero-priced spendable item.

## Brewing freeze

[A9-03-02](item-valuation-brewing.md) implements this capture. It confirms46 registered definitions but no `minecraft:empty` ID: EMPTY below means absent PotionContents identity. Captured default cure sets and hidden-effect rejection also bind the supported effect state.

A9-03-02 must add narrow access-transformer access to the actual `PotionBrewing` lists and `PotionBrewing$Mix` type/accessors; Gradle already wires an access-transformer file. Capture reviewed vanilla tuples from the server instance with registry/feature and accessor-version hashes. Source has 41 direct potion edges and 11 `addStartMix` calls, each adding WATER→MUNDANE and AWKWARD→target: 63 potion edges, two form edges, three container kinds. `getRecipes()` alone misses these. Additional/custom or shadowing behavior needs registered trusted data; do not execute `IBrewingRecipe` or event callbacks. Record exclusions; vanilla cost reference does not claim arbitrary modded brewing behavior.

Potion identity: form item ID, potion registry ID and captured effect/amplifier/duration definition. WATER = free content plus one bottle. AWKWARD/MUNDANE/THICK are supported graph intermediates. EMPTY, LUCK or another state without a supported survival path is unavailable absent an explicit supported acquisition rule. Custom effects/colors need an explicit adapter. Choose cheapest supported acyclic path for duration/strength/corruption alternatives.

Each edge adds ingredient/3 + powder/60 to prior exact per-bottle cost, with no extra splash/lingering bottle or actual partial-stand adjustment. Charge dragon breath's whole bottle+content value once per batch; retain its returned empty bottle as provenance without salvage credit. Current anchors: glass bottle .10, powder 4, wart 1 CAD. Water→awkward: `.10+1/3+4/60=.50`; splash awkward: `.50+.50/3+4/60=11/15` (.73 published); lingering: `11/15+25.10/3+4/60=55/6` (9.17 published). No intermediate rounding.

## Variant applicability and nesting

Applied equipment enchantments require captured supported-item membership, configured/pinned level range and pairwise nonexclusive definitions. Stored books accept configured vanilla enchants in range without equipment membership, still pairwise compatible. Distinct vanilla curses apply `(base+beneficial premiums) × (1−curses/4)` before actual remaining/max durability. Non-damageable factor is one; reject negative/excess damage or unsupported altered maximum damage. No mod applicability callbacks on workers.

- Exactly one adapter owns each shell. Potion, filled bucket, honey/dragon-breath bottle, generic inventory and equipment/book rules cannot double count it. Honey bottle anchor already includes its shell. Generic container total is published empty-shell amount plus each child's published unit × count at the same manifest/FX. Its `exactUnitMinor` is that integer sum, with child exact derivations in provenance. This resolves the older premiums document's final-only rounding wording.
- Generic CONTAINER applies only to explicitly reviewed vanilla portable holders, including shulker/chest/barrel/furnace/hopper/dispenser/dropper families and decorated pot where present. A component attached to an arbitrary item does not authorize support. Preserve slots; unresolved CONTAINER_LOOT is unsupported, never rolled. Outer container depth is one; refuse >8 levels or >4,096 contained stack visits, counting repeats. Unknown child makes whole value unavailable. Bundle remains unpriced unless explicitly enabled and supported.
- Bucket identity uses its item plus typed supported entity and whitelisted vanilla state, never an arbitrary NBT entity ID. Water is free; approved fluid/entity content bands apply once. Supported fish/axolotl appearance is explicitly neutral; unknown semantic data refuses. Bee containers accept at most three validated vanilla bee occupants, CAD 2 each, without spawning; preserve bounded saved identity and reject other entities. Nest shell is CAD 5; hive uses derived shell. Pot decorations use four captured brick/sherd materials; held contents counted once.
- Written books/maps retain exact payload but use writable-book/map reference principal. Custom names/glint and reviewed vanilla cosmetics are neutral only by explicit rules; colored shulkers still add the named dye premium. Unknown semantic patches remain unsupported.
- Tipped arrow = `(8 arrows + supported exact lingering potion)/8`, no additional bottle. Stew = bowl + both mushrooms + cheapest vanilla flower yielding the exact captured effect/duration; detach the pinned flower table, reject arbitrary effect lists.
- Star = gunpowder + dyes matching ordered encoded base colors + shape material + diamond for trail + glowstone for twinkle + dyes matching final fade colors. Small ball has no shape material; large ball uses fire charge, burst feather, star gold nugget; creeper shape uses cheapest priced supported skull/head. Repeated colors consume repeated dyes. Reject unknown RGB/shape or lists incompatible with vanilla nine-slot base/fade recipes. Fade replaces its list; price a cheapest final fade step, not unknown history. Rocket = `(paper + flight(1–3) gunpowder + captured stars)/3`, respecting nine input slots. These are data mappings, never special crafting execution.
- Named stripping/oxidation/concrete/carved-pumpkin rules retain their material principal without waiting fee/byproduct credit. Anvil condition factors remain 2/3 and 1/3. Unregistered mod identities/components/enchants require contribution support even if ordinary recipes exist.

## Atomic publication and recovery

A9-02 adds new valuation wiring to the existing lifecycle. Mark reload admission pending; after successful resources installation/tag rebound, capture on server executor, derive immutable candidates through `ServerServices.calculate`, publish through its storage worker. Startup performs equivalent capture before fresh reads. Generation token is rechecked before publication; superseded work cannot publish. A failed reload without resource installation can restore matching old readiness; installed recipes with rejected valuation remain unavailable until matching publication. Fresh trader admission rechecks recipe generation, including worker-time preparation.

One journal transaction accepts immutable manifest records, active pointer and any already-enabled scope epoch. Use additive bounded chunk/reference schemas respecting actual journal record limits, never a 32 MiB payload in a legacy 16 KiB fact. Retain all accepted manifests, 32 MiB individually/256 MiB aggregate canonical payload; reject publication over quota, never automatically delete history. Keep active plus ≤16 compiled historical entries in memory. Historical eviction affects memory only. Reopen validates hashes, active pointers and provenance; current readiness additionally compares live inputs. Store consumer result and selected derivation as replay data, not hash-only references. Interrupted publication leaves old durable state or complete new state. No second scheduler/database.

## Source-neutral quote and carry transition

The new pricing implementation identity is `economy:reference-unit/v1`; its source-neutral account/cash payloads use additive versioned fact types, never the old EmeraldV1 discriminator. The publication manifest binds codec versions as well as algorithm/rounding versions. A9-05 adds `SourceRef(world, market/itemScope, sourceUuid, revision, epoch, valuationManifest, pricingVersion, roundingVersion, activationId)` and additive account/cash facts. Keep EmeraldV1 codecs/validators and old pricing unchanged. Initial activation is explicit/prospective. Valid reload advances only already-enabled scopes, atomically with manifest publication. Old carry keeps original exact residual/key/epoch plus retirement fact; no payout, reuse or destructive rewrite. New A9 facts have no accumulating carry.

`JournalEngine.process` resolves authenticated original outcomes before handler preparation. New controller/recovery entry points must preserve original lookup before source/expiry/readiness checks, with exact original requests. Committed account/cash outcomes remain replayable after source switch. For a new settlement compare SourceRef and recipe/provider generation against authoritative current state in the same preparation before any money/goods/stock/custody effect. Old uncommitted quotes refuse. No balances, stock, observations, rates, windows or fixed loans are revalued.

A9-06 uses published reference unit × pinned standard quantity as principal; preserve original complete-cohort quantity-normalized `r=min(.8,B/A)` (hypothetical .8 if one-sided), demand integral/recovery, reputation and secondary ratios. Adjust exact total for execution quantity, divide by goods count, HALF_UP unit with minimum one, then multiply. Prove rounded reachable maximum bid ≤80% of rounded reachable minimum ask over the complete cohort/quantity/reputation domain. Neutral ask 2 cents/bid 1.6 rounds to 2/2 and refuses; ask 5/bid 4 passes that neutral witness but still needs reachable-domain proof. Unsupported predicate, representation or proof disables listing without a player-facing explanation. Never alter rounding/minimum to force admission.

## Handoff limits

No source-level incompatibility blocks this design. Arbitrary component/mod states and worst-case resource limits remain explicit unavailability. Source access and reload wiring still require implementation/compilation evidence in their owner packages. Static identity closure is not numeric valuation, complete variant support or quote admission. Keep pinned Minecraft 1.21.1, NeoForge 21.1.252, Java 21, registered main mod, client/server tasks and required development runtime dependencies. Only A9-01 is selected and complete; dependents await explicit selection.
