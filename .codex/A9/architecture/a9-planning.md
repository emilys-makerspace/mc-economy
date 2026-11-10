# A9 — Settled planning decisions

Planning began 2026-11-07. The user settled the gameplay policies below, authorized routine engineering defaults, and requested drafting. **All A9 drafts approved 2026-11-07, subject to prerequisites; implementation packages remain unselected and unexecuted.** Preserve code, worlds, backups, balances and historical outcomes.

## Approved planning artifacts

- [Shared technical contract, revision 1](a9-shared-contracts.md): exact arithmetic, component identity, deterministic recipe/brewing derivation, lifecycle/storage, API/SPI, limits and trader-source transition.
- [Vanilla catalog](a9-item-catalog.md) and [per-item classification](a9-item-catalog.csv): approved catalog amounts plus revised log/meat/disc calibration and explicit recipe/variant classifications.
- [Approved enchantment premiums](a9-enchantment-premiums.md): per-level amounts, damage, curse and book composition.
- [Phase entry](../../../prompts/A9-item-valuation/README.md), [shared package contract](../../../prompts/A9-item-valuation/CONTRACT.md) and [selection queue](../../../prompts/A9-item-valuation/EXECUTION.md).
- [A8 downstream contract](../../A8/architecture/a8-downstream.md) supplies the existing implementation boundary. This A9 record supersedes its prospective cumulative-carry policy for new A9 goods pricing only.

The user approved other catalog prices and requested the log/meat/disc revisions recorded in the catalog. Routine engineering choices are authorized; pinned-source feasibility is normal package work, not a reason to repeatedly ask for policy approval.

## Final decisions before drafting

1. Replace villager and wandering-trader original price baselines with item reference values. Preserve demand/recovery, reputation, spreads/cohort protection, currency assignments, stock/restock and progression. A reference value supplies the original principal, not a guaranteed buyback or new listing.
2. Choose the lowest fully known supported recipe/ingredient cost, excluding cyclic paths. Explicit server override precedes configured base, then supported derivation; unknown values never become zero.
3. Common logs cost CAD 2; rare species use the revised catalog tiers and sticks use the canonical oak reference. Ingots/gems/rare ores use triple the original starter calibration. Meats/fish use the latest per-species raw anchors below. Raw metals and raw meats/fish are 75% of refined/cooked values. Apply that processing difference once, not again to downstream recipes.
4. Price noncraftables, including mob drops and loot, explicitly by acquisition difficulty: rarity, danger, effort, progression and renewability. Complete vanilla coverage is the target. Operator-only items remain unpriced; AIR is a non-merchandise sentinel. Special states need explicit classification.
5. Mod items have no built-in assigned values and no automatic recipe-derived valuation. Provide an API/SPI for explicit registered contribution data or opt-in supported derivation. No specific integrations are selected.
6. Support ordinary deterministic shaped/shapeless crafting, stonecutting, smelting, blasting, smoking and campfire cooking. Defer smithing, ordinary recipes returning containers/byproducts, and special/dynamic recipes without supported adapters. Acquisition-based anchors may value obtainable outputs without executing deferred recipes.
7. Derive potion values from supported brewing paths and exact potion identities. Include ingredients, bottles and allocated blaze-powder fuel; shared steps use three-output allocation. Water is free. No labour or brewing-time fee. Preserve recipe provenance and count each bottle once.
8. Damage reduces value proportionally to remaining durability. Add approved compatible beneficial-enchantment premiums before durability. Each distinct vanilla curse subtracts 25% of combined base/premiums before durability; both subtract 50%. Enchanted books use ordinary book value plus stored premiums. Names add no value and do not remove other modifiers.
9. Filled containers equal empty-container value plus contained item values. Contents retain their quantities and individual variants. Value non-item contents such as milk/lava by explicit acquisition-based content entries; water is free. Bound nested evaluation and avoid double counting the same shell.
10. Publish prices rounded HALF_UP to the nearest cent (registered minor unit for currencies without cents), with a one-minor-unit minimum **per priced item**. Free water is an explicit exception. Keep intermediate recipe arithmetic exact. New A9 goods trades do not accumulate fractional carry.
11. Unsupported listings are disabled without player-facing explanations. Keep typed internal/API/operator availability and provenance. Missing data is not a one-cent fallback.
12. Engineering defaults are authorized: bounded searches/queues, atomic versioned reloads, immutable manifests/provenance, deterministic provider conflict rejection, authenticated server APIs and preserved historical trades. The main agent may finalize routine interfaces/limits without another policy round.

## Accepted calibration

All amounts are CAD per item. This is the approved core of the full approved catalog.

| Identity/family | Raw/base | Refined/cooked target |
| --- | ---: | ---: |
| Oak/birch/spruce/acacia logs | 2.00 | Species planks /4 |
| Dark oak logs | 4.00 | Species planks /4 |
| Jungle/mangrove logs | 6.00 | Species planks /4 |
| Cherry logs | 8.00 | Species planks /4 |
| Bamboo | 0.10 | Recipe-derived |
| Cobblestone/cobbled deepslate, dirt/sand/gravel | 0.10 | Recipe-derived |
| Clay ball | 0.25 | Recipe-derived |
| Coal | 1.00 | Recipe-derived |
| Copper | 2.25 | 3.00 |
| Iron | 4.50 | 6.00 |
| Gold | 18.00 | 24.00 |
| Redstone dust | 0.50 | Recipe-derived |
| Lapis/quartz | 3.00 | Recipe-derived |
| Diamond | 300.00 | Recipe-derived |
| Emerald | 75.00 | Item reference only |
| Ancient debris | 750.00 | Recipe-derived |
| Wheat/carrot/potato/beetroot | 0.25 | Classified in catalog |
| Sugar cane/kelp | 0.10 | Classified in catalog |
| String/bone/gunpowder | 0.50 | Recipe-derived |
| Leather/feather | 0.50 | Recipe-derived |
| porkchop | 1.50 | 2.00 |
| chicken | 2.48 | 3.31 |
| beef | 3.43 | 4.57 |
| salmon | 4.29 | 5.72 |
| rabbit | 5.25 | 7.00 |
| cod | 6.83 | 9.11 |
| mutton | 7.80 | 10.40 |

The metal ratio is raw / processed = .75, so the processing increase over raw is one-third, not 25%. Explicit target rows are calibration constraints, not a second fee. [Enchantment amounts](a9-enchantment-premiums.md) are also approved.

## Numerical examples

| Case | Result |
| --- | --- |
| One CAD 2 log yields four planks | CAD 0.50 per plank |
| Two such planks yield four sticks | CAD 0.25 per stick |
| Three raw iron at CAD 4.50 | CAD 13.50 |
| Nine CAD 6 ingots produce an iron block | CAD 54 |
| Raw pork CAD 1.50 becomes cooked pork | CAD 2; other meats use revised raw values /0.75 |
| One cent of inputs split among three outputs | Internal 1/3 cent; published unit minimum CAD .01, three units CAD .03 |
| Combined equipment value CAD 100, one curse, half durability | CAD 37.50 |
| Filled container | Individually valued shell + contained item prices; shell modifiers do not discount contents |

## Transition and boundaries

Reference values create no money, reserve, redemption promise, collateral eligibility, Tier2 wealth qualification or automatic trade. Currencies remain fiat-like; funded bank spending and existing financial authority remain unchanged.

A9-05/06 add source-neutral durable terms, active source epochs and a new rounded-unit pricing version. Uncommitted old quotes become invalid before consumption. Committed originals replay unchanged. Preserve stock/demand, exact custody, balances, observations, immutable windows and prime/FX/loan history. Old fractional carry stays in its original historical epoch with a recorded retirement boundary, without reuse, deletion or a monetary payout. New A9 trades have no fractional carry.

Round adjusted goods unit prices and multiply by quantity; retain the complete cohort/spread proof. A rounded one-cent ask and one-cent bid cannot satisfy the retained 20% gap, so that quote remains disabled. A minimum price never authorizes free trades or bypasses spread checks.

## Drafting and execution handoff

The [ordered package queue](../../../prompts/A9-item-valuation/EXECUTION.md) separates source feasibility, snapshots, ordinary recipes, brewing, variants/containers, public API, source transition and trader activation. All finalized prompts are approved; implement only an explicitly selected package after its prerequisites.

Apply [verification scope](../../VERIFICATION.md) to all packages/subtasks. Planning uses diff, local-link, pinned inventory and arithmetic review only. No Gradle/tests/game processes for this draft. Ordinary runnable delivery later runs build excluding tests and both required client-task checks once; focused tests only when useful, no automatic full suite. One owner runs Gradle/game sequentially. Preserve Minecraft 1.21.1, NeoForge 21.1.252, Java 21, registered main source set, required runtime dependencies and working client/server tasks.

## Catalog price approval and revisions

The user approved all other catalog prices and requested these revisions: easy oak/birch/spruce/acacia logs CAD 2, dark oak CAD 4, jungle/mangrove CAD 6, cherry CAD 8; all sticks use the canonical two-oak-planks/four-sticks reference (CAD 0.25), including bamboo-origin sticks. Per-species raw meat/fish anchors and cooked /0.75 amounts are in the catalog; pork=porkchop, lamb=mutton. Discs use a CAD 45 common creeper-drop base and higher acquisition bands, with disc 5 derived from nine unchanged CAD 40 fragments. Species products inherit wood costs, generic sticks do not. These revisions supersede earlier uniform wood/meat/disc values without changing historical outcomes. Price approval does not select an implementation package.
