# A9 vanilla item catalog — pricing revision 2

Prepared 2026-11-07 under the instruction to start drafting. This is a planning catalog, not runtime configuration or an implemented valuation service. **The user approved all drafts on 2026-11-07, including the full catalog and applied log, meat and disc revisions.** [A9-01 source feasibility](a9-source-contracts.md) is now accepted with [static audit evidence](../verification/A9-01.md); runtime adapters remain unimplemented. This catalog selects no dependent package.

Read [settled gameplay decisions](a9-planning.md#final-decisions-before-drafting), [technical contracts](a9-shared-contracts.md), [approved enchantment premiums](a9-enchantment-premiums.md) and the [complete per-item CSV](a9-item-catalog.csv).

## Coverage and provenance

The inventory has **1,333 distinct vanilla item identities**, including AIR as an unpriced sentinel. It comes from the pinned `net/minecraft/world/item/Items.java` declarations and actual registration arguments, not language strings or item model filenames. In particular, the source field `CUT_STANDSTONE_SLAB` registers `minecraft:cut_sandstone_slab`; the CSV uses the registered identity. Direct item keys, reference keys and block registrations were reconciled. This is a static vanilla inventory, not a claim that every component combination or modded runtime registry is supported.

| Classification | Items | Meaning |
| --- | ---: | --- |
| `BASE` | 349 | Explicit CAD anchor based on approved calibration or acquisition difficulty. |
| `DERIVED` | 760 | A supported standard-recipe witness exists; actual runtime value chooses the lowest fully known acyclic path. |
| `VARIANT` | 109 | A named component/container/environmental transformation rule is required; details below. |
| `UNPRICED_OPERATOR` | 115 | Operator-only, unobtainable-as-item or feature-disabled identity; intentionally no price. |

Every inventory identity appears once in the CSV. Blank `cad_value` for DERIVED/VARIANT is intentional: the source rule, captured recipe/variant data and current FX determine its price. It is not an unclassified missing base. DERIVED recipe IDs are valid closure witnesses, not a precomputed assertion that the witness is globally cheapest. The implementation must still perform the approved deterministic search, cycle checks, component validation and resource bounds. Operator rows never receive a one-cent fallback.

Local source evidence:

- Official client artifact: `minecraft_1.21.1_client.jar`; embedded `version.json` identifies 1.21.1, world version 3955, protocol 767, Java 21.
- Client artifact SHA-256: `499f6897d1837516680f3114072d8106e11c9adcd933fe5cf051b551089b0c99`.
- Cached NeoForge source `Items.java` SHA-256: `1c0dd4593b6b60f6b20a6389fd0c364aab0c0c2dc1260765d9c898f4ffa19353`.
- Read 1,290 official `data/minecraft/recipe/*.json` files and their item-tag definitions. Standard shaped/shapeless, stonecutting and ordinary cooking forms supplied witnesses. Recipes involving known crafting remainders were excluded; special/dynamic/smithing recipes were not executed or treated as standard witnesses.
- Static source inventory and witness closure are document checks only. No Gradle, tests, game, world or runtime migration was run. A9-01 confirms source APIs and static coverage under the pinned build; actual runtime coverage belongs to implementation acceptance.

## Calibration rules

All amounts are CAD per one ordinary item. New anchors weigh rarity, danger, effort, progression and renewability/farmability; they are fixed editable calibration, not a live farming simulator. Explicit anchors win over compression/decompression recipes. High ore/trophy anchors need not equal their processed-drop yield. A recipe cannot recursively discount an anchored resource.

- Easy oak/birch/spruce/acacia logs CAD 2; dark oak CAD 4; jungle/mangrove CAD 6; cherry CAD 8. Nether stems retain CAD 2. Species products inherit their wood cost; generic sticks use canonical oak-plank cost, currently CAD 0.25. Other resource prices are unchanged.
- Raw copper/iron/gold CAD 2.25/4.50/18; refined targets CAD 3/6/24. Processing adds the approved difference once.
- Raw meats/fish use the revised per-species table below; cooked values remain raw /0.75. Downstream recipes keep exact ingredient costs without another generic uplift.
- Keep exact internal arithmetic, then nearest-cent HALF_UP with minimum CAD 0.01 per priced unit. Other currencies use their registered minor unit. Quantity totals use published unit prices. Water content is explicitly free; bottles/buckets are not.
- Mod items have no automatic assigned or recipe-derived prices. The API/SPI explicitly opts them into valuation. This catalog contains vanilla identities only.

## Starter anchors and revised prices

The following 43 starter anchors include the user-requested log/meat revisions. Refined/cooked targets are listed separately as derivation rules rather than duplicate base anchors. IDs in the tables use the `minecraft:` namespace.

| Item IDs | CAD each | Basis |
| --- | ---: | --- |
| `acacia_log`, `birch_log`, `oak_log`, `spruce_log` | 2.00 | easy-to-find wood tier |
| `dark_oak_log` | 4.00 | restricted dark-forest acquisition tier |
| `jungle_log`, `mangrove_log` | 6.00 | less readily found jungle/swamp tier |
| `cherry_log` | 8.00 | user-specified rarer log price |
| `ancient_debris` | 750.00 | approved tripled rare-ore anchor |
| `bamboo`, `kelp`, `sugar_cane` | 0.10 | approved renewable plant anchor |
| `porkchop` | 1.50 | revised raw price; cooked value below |
| `chicken` | 2.48 | revised raw price; cooked value below |
| `beef` | 3.43 | revised raw price; cooked value below |
| `salmon` | 4.29 | revised raw price; cooked value below |
| `rabbit` | 5.25 | revised raw price; cooked value below |
| `cod` | 6.83 | revised raw price; cooked value below |
| `mutton` | 7.80 | revised raw price; cooked value below |
| `beetroot`, `carrot`, `clay_ball`, `potato`, `wheat` | 0.25 | approved common resource/crop anchor |
| `bone`, `feather`, `gunpowder`, `leather`, `redstone`, `string` | 0.50 | approved resource/drop anchor |
| `coal` | 1.00 | approved fuel anchor |
| `cobbled_deepslate`, `cobblestone`, `dirt`, `gravel`, `sand` | 0.10 | approved common bulk resource anchor |
| `diamond` | 300.00 | approved tripled gem anchor |
| `emerald` | 75.00 | approved tripled gem anchor; no currency backing |
| `lapis_lazuli`, `quartz` | 3.00 | approved tripled mineral anchor |
| `raw_copper` | 2.25 | approved 75% of copper-ingot CAD3 target |
| `raw_gold` | 18.00 | approved 75% of gold-ingot CAD24 target |
| `raw_iron` | 4.50 | approved 75% of iron-ingot CAD6 target |

## Revised meat prices

User amounts are raw-item anchors, consistent with the existing raw-item catalog. Pork maps to `porkchop`; lamb maps to `mutton`. Cooked values remain raw /0.75 with final HALF_UP rounding.

| Item | Raw CAD | Cooked CAD |
| --- | ---: | ---: |
| `porkchop` | 1.50 | 2.00 |
| `chicken` | 2.48 | 3.31 |
| `beef` | 3.43 | 4.57 |
| `salmon` | 4.29 | 5.72 |
| `rabbit` | 5.25 | 7.00 |
| `cod` | 6.83 | 9.11 |
| `mutton` | 7.80 | 10.40 |

## Standardized sticks

Every stick uses two oak planks divided by four outputs: currently CAD 0.25. This canonical material rule overrides cheapest-alternative search for sticks, including bamboo and rare-plank recipes. Actual crafting consumption/outputs do not change. Higher wood prices propagate into species planks and products but not generic sticks or tool handles. A future oak-price revision updates this canonical reference prospectively.

## Approved acquisition anchors

These amounts complete ordinary survival-item roots, including items obtainable through loot, gathering, world interaction or recipe classes deliberately not executed in the first valuation engine. The user approved these amounts with the specific revisions recorded here. The complete item-level entries are in the CSV. Items grouped in a row share a specific acquisition rationale and value, not an automatic residual-item fallback.

### Mob drops and animal products

| Item IDs | CAD each | Acquisition basis |
| --- | ---: | --- |
| `armadillo_scute` | 2.00 | renewable brushing/growth access |
| `blaze_rod` | 8.00 | fortress and combat progression; renewable |
| `breeze_rod` | 12.00 | trial-chamber combat and cooldown effort |
| `egg` | 0.25 | passive renewable animal product |
| `ender_pearl` | 5.00 | hostile/progression drop with renewable trading/farm alternatives |
| `ghast_tear` | 15.00 | Nether combat and collection risk |
| `glow_ink_sac` | 1.00 | cave-water mob access; renewable |
| `honeycomb` | 1.00 | renewable bee harvest; setup and handling |
| `ink_sac` | 0.50 | renewable aquatic drop |
| `nautilus_shell` | 20.00 | rare drowned/fishing or trader acquisition |
| `phantom_membrane` | 8.00 | nighttime hostile encounter; repair/brewing utility |
| `prismarine_crystals` | 2.00 | guardian/sea-lantern collection |
| `prismarine_shard` | 1.00 | guardian combat; farmable after setup |
| `rabbit_foot` | 5.00 | uncommon renewable animal drop |
| `rabbit_hide` | 0.20 | small renewable animal product |
| `rotten_flesh` | 0.10 | abundant hostile drop; low utility |
| `shulker_shell` | 40.00 | End-city combat; renewable only with complex setup |
| `slime_ball` | 2.00 | renewable hostile-mob drop; biome/time constraints |
| `spider_eye` | 1.00 | hostile drop; brewing input |
| `turtle_scute` | 5.00 | breeding/growth wait |

### Food, crops and deferred-remainder outputs

| Item IDs | CAD each | Acquisition basis |
| --- | ---: | --- |
| `apple` | 0.50 | renewable tree/farming food |
| `beetroot_seeds`, `melon_seeds`, `pumpkin_seeds`, `wheat_seeds` | 0.10 | renewable crop starter; cheap propagation |
| `cake` | 4.00 | approved prepared-food acquisition anchor; milk-bucket-return recipe excluded initially |
| `chorus_fruit` | 0.50 | renewable End crop |
| `cocoa_beans` | 0.25 | renewable crop with biome access |
| `glow_berries` | 0.40 | renewable cave crop; initial exploration |
| `honey_block` | 6.00 | bee-product acquisition anchor; four honey bottles at CAD1.50; returned-bottle craft excluded initially |
| `honey_bottle` | 1.50 | bottle plus bee product acquisition anchor; content adapter preserves bottle once |
| `melon_slice`, `sweet_berries` | 0.20 | renewable harvested food |
| `nether_wart` | 1.00 | renewable after fortress access |
| `poisonous_potato` | 0.10 | low-utility rare crop byproduct |
| `pufferfish` | 2.00 | fishing/biome access; brewing utility |
| `torchflower_seeds` | 3.00 | sniffer acquisition and digging wait |
| `tropical_fish` | 1.00 | biome-specific catch |

### Geology and natural materials

| Item IDs | CAD each | Acquisition basis |
| --- | ---: | --- |
| `amethyst_cluster`, `large_amethyst_bud`, `medium_amethyst_bud`, `small_amethyst_bud` | 3.00 | geode growth and careful collection; decorative intact growth |
| `amethyst_shard` | 2.00 | geode access and growth delay |
| `basalt`, `blackstone` | 0.20 | abundant Nether stone |
| `calcite` | 0.30 | geode/mountain deposit collection |
| `coal_ore` | 2.00 | intact ore requires appropriate collection; explicit acquisition anchor |
| `cobweb` | 1.00 | specialized collection from structures/mineshafts; shear/silk-touch effort |
| `copper_ore` | 4.00 | intact ore requires appropriate collection; explicit acquisition anchor |
| `crimson_nylium`, `warped_nylium` | 0.50 | Nether-specific substrate and careful collection |
| `crying_obsidian` | 6.00 | Nether loot/bartering; limited direct harvesting |
| `deepslate_coal_ore` | 2.20 | intact deepslate ore; collection effort/scarcity uplift |
| `deepslate_copper_ore` | 4.40 | intact deepslate ore; collection effort/scarcity uplift |
| `deepslate_diamond_ore` | 385.00 | intact deepslate ore; collection effort/scarcity uplift |
| `deepslate_emerald_ore` | 110.00 | intact deepslate ore; collection effort/scarcity uplift |
| `deepslate_gold_ore` | 26.40 | intact deepslate ore; collection effort/scarcity uplift |
| `deepslate_iron_ore` | 6.60 | intact deepslate ore; collection effort/scarcity uplift |
| `deepslate_lapis_ore` | 8.80 | intact deepslate ore; collection effort/scarcity uplift |
| `deepslate_redstone_ore` | 3.30 | intact deepslate ore; collection effort/scarcity uplift |
| `diamond_ore` | 350.00 | intact ore requires appropriate collection; explicit acquisition anchor |
| `emerald_ore` | 100.00 | intact ore requires appropriate collection; explicit acquisition anchor |
| `end_stone` | 0.30 | End progression and bulk collection |
| `flint` | 0.30 | gravel processing; common renewable mineral |
| `gilded_blackstone` | 8.00 | bastion-specific decorative block |
| `glowstone_dust` | 1.00 | Nether collection; renewable witch alternative |
| `gold_ore` | 24.00 | intact ore requires appropriate collection; explicit acquisition anchor |
| `grass_block`, `mud`, `podzol`, `rooted_dirt` | 0.20 | common terrain; collection or simple environment conversion |
| `ice` | 0.20 | renewable freezing; special collection |
| `iron_ore` | 6.00 | intact ore requires appropriate collection; explicit acquisition anchor |
| `lapis_ore` | 8.00 | intact ore requires appropriate collection; explicit acquisition anchor |
| `nether_gold_ore` | 8.00 | intact Nether ore; collectible and renewable gold alternatives |
| `nether_quartz_ore` | 4.00 | intact Nether ore collection |
| `netherrack` | 0.10 | very abundant Nether bulk material |
| `obsidian` | 3.00 | slow mining and tool prerequisite; renewable lava casting |
| `pointed_dripstone` | 0.50 | cave exploration and renewable growth |
| `red_sand` | 0.20 | biome-specific bulk material |
| `redstone_ore` | 3.00 | intact ore requires appropriate collection; explicit acquisition anchor |
| `snowball` | 0.05 | easy renewable collection |
| `soul_sand`, `soul_soil` | 0.30 | Nether biome-specific collection |
| `tuff` | 0.15 | common underground deposit |
| `wet_sponge` | 10.00 | ocean-monument access; scarce reusable utility |

### Plants, natural decorations and renewable blocks

| Item IDs | CAD each | Acquisition basis |
| --- | ---: | --- |
| `acacia_leaves`, `azalea_leaves`, `birch_leaves`, `cherry_leaves`, `dark_oak_leaves`, `flowering_azalea_leaves`, `jungle_leaves`, `mangrove_leaves`, `oak_leaves`, `spruce_leaves` | 0.20 | renewable foliage; appropriate collection tool |
| `acacia_sapling`, `birch_sapling`, `cherry_sapling`, `dark_oak_sapling`, `jungle_sapling`, `mangrove_propagule`, `oak_sapling`, `spruce_sapling` | 0.50 | renewable tree starter; low harvesting effort |
| `allium`, `azure_bluet`, `blue_orchid`, `cornflower`, `dandelion`, `lilac`, `lily_of_the_valley`, `orange_tulip`, `oxeye_daisy`, `peony`, `pink_tulip`, `poppy`, `red_tulip`, `rose_bush`, `sunflower`, `white_tulip` | 0.25 | renewable flower; biome collection |
| `azalea`, `flowering_azalea` | 0.50 | renewable lush-cave plant |
| `big_dripleaf`, `small_dripleaf` | 0.50 | lush-cave/trader access; propagation differences |
| `brain_coral`, `brain_coral_block`, `brain_coral_fan`, `bubble_coral`, `bubble_coral_block`, `bubble_coral_fan`, `fire_coral`, `fire_coral_block`, `fire_coral_fan`, `horn_coral`, `horn_coral_block`, `horn_coral_fan`, `tube_coral`, `tube_coral_block`, `tube_coral_fan` | 2.00 | reef collection; live material needs careful handling |
| `brown_mushroom`, `crimson_fungus`, `red_mushroom`, `warped_fungus` | 0.25 | renewable fungus |
| `brown_mushroom_block`, `mushroom_stem`, `red_mushroom_block` | 0.30 | huge-fungus harvesting with appropriate collection |
| `cactus` | 0.20 | easy renewable desert crop |
| `chorus_flower` | 1.00 | End access and renewable growth |
| `crimson_roots`, `dead_bush`, `fern`, `nether_sprouts`, `pink_petals`, `seagrass`, `short_grass`, `warped_roots` | 0.10 | common renewable/abundant decorative plant |
| `crimson_stem`, `warped_stem` | 2.00 | renewable Nether wood; calibrated to logs |
| `dead_brain_coral`, `dead_brain_coral_block`, `dead_brain_coral_fan`, `dead_bubble_coral`, `dead_bubble_coral_block`, `dead_bubble_coral_fan`, `dead_fire_coral`, `dead_fire_coral_block`, `dead_fire_coral_fan`, `dead_horn_coral`, `dead_horn_coral_block`, `dead_horn_coral_fan`, `dead_tube_coral`, `dead_tube_coral_block`, `dead_tube_coral_fan` | 1.00 | dead coral decoration; no life-preservation requirement |
| `glow_lichen`, `hanging_roots`, `twisting_vines`, `vine`, `weeping_vines` | 0.20 | renewable growth; initial biome access |
| `large_fern`, `tall_grass` | 0.20 | large decorative plant; appropriate collection |
| `lily_pad` | 0.25 | swamp collection; fishing alternative |
| `mangrove_roots` | 0.30 | renewable biome wood byproduct |
| `moss_block` | 0.30 | renewable spreading vegetation |
| `mycelium` | 1.00 | rare biome substrate; special collection |
| `ochre_froglight`, `pearlescent_froglight`, `verdant_froglight` | 5.00 | frog/magma-cube setup; renewable but progression-gated |
| `pitcher_plant`, `torchflower` | 2.00 | sniffer progression and growth |
| `pitcher_pod` | 3.00 | sniffer acquisition and digging wait |
| `pumpkin` | 0.50 | renewable whole crop |
| `sculk`, `sculk_vein` | 0.50 | deep-dark or catalyst access; careful collection |
| `sculk_catalyst` | 10.00 | deep-dark/drop acquisition; farm setup potential |
| `sculk_sensor`, `sculk_shrieker` | 8.00 | deep-dark collection and hazard |
| `sea_pickle` | 0.50 | warm-ocean access; renewable colony |
| `shroomlight` | 2.00 | renewable huge-fungus harvesting |
| `sniffer_egg` | 100.00 | archaeology/ocean exploration; renewable after breeding |
| `spore_blossom` | 5.00 | lush-cave exploration; non-propagating decoration |
| `turtle_egg` | 10.00 | breeding and careful transport |
| `warped_wart_block` | 0.50 | renewable huge-fungus canopy collection |
| `wither_rose` | 5.00 | dangerous wither-assisted acquisition; farmable with setup |

### Exploration, trophies and loot

| Item IDs | CAD each | Acquisition basis |
| --- | ---: | --- |
| `angler_pottery_sherd`, `archer_pottery_sherd`, `arms_up_pottery_sherd`, `blade_pottery_sherd`, `brewer_pottery_sherd`, `burn_pottery_sherd`, `danger_pottery_sherd`, `explorer_pottery_sherd`, `flow_pottery_sherd`, `friend_pottery_sherd`, `guster_pottery_sherd`, `heart_pottery_sherd`, `heartbreak_pottery_sherd`, `howl_pottery_sherd`, `miner_pottery_sherd`, `mourner_pottery_sherd`, `plenty_pottery_sherd`, `prize_pottery_sherd`, `scrape_pottery_sherd`, `sheaf_pottery_sherd`, `shelter_pottery_sherd`, `skull_pottery_sherd`, `snort_pottery_sherd` | 15.00 | archaeology exploration; cosmetic pattern has equal acquisition band |
| `bell` | 30.00 | village/trade acquisition; heavy utility decoration |
| `bolt_armor_trim_smithing_template`, `coast_armor_trim_smithing_template`, `dune_armor_trim_smithing_template`, `eye_armor_trim_smithing_template`, `flow_armor_trim_smithing_template`, `host_armor_trim_smithing_template`, `raiser_armor_trim_smithing_template`, `rib_armor_trim_smithing_template`, `sentry_armor_trim_smithing_template`, `shaper_armor_trim_smithing_template`, `silence_armor_trim_smithing_template`, `snout_armor_trim_smithing_template`, `spire_armor_trim_smithing_template`, `tide_armor_trim_smithing_template`, `vex_armor_trim_smithing_template`, `ward_armor_trim_smithing_template`, `wayfinder_armor_trim_smithing_template`, `wild_armor_trim_smithing_template` | 100.00 | initial structure-loot template anchor; duplication recipe does not erase acquisition anchor |
| `creeper_head`, `piglin_head`, `skeleton_skull`, `zombie_head` | 100.00 | charged-creeper acquisition and mob handling |
| `diamond_horse_armor` | 2100.00 | loot anchor calibrated to seven diamonds |
| `disc_fragment_5` | 40.00 | ancient-city loot component |
| `dragon_egg` | 10000.00 | first-dragon trophy; intentionally scarce acquisition anchor |
| `dragon_head` | 500.00 | End-ship exploration trophy |
| `echo_shard` | 100.00 | ancient-city exploration and danger |
| `elytra` | 1500.00 | End-ship exploration; mobility progression |
| `enchanted_golden_apple` | 1000.00 | rare structure loot; powerful consumable |
| `experience_bottle` | 10.00 | trade/loot consumable; explicit acquisition anchor |
| `flow_banner_pattern`, `guster_banner_pattern`, `piglin_banner_pattern` | 100.00 | bastion/trial loot acquisition |
| `globe_banner_pattern` | 25.00 | cartographer trade acquisition |
| `goat_horn` | 20.00 | goat behavior/environment setup; instrument identity adds no automatic premium |
| `golden_horse_armor` | 168.00 | loot anchor calibrated to seven gold ingots |
| `heart_of_the_sea` | 250.00 | buried-treasure exploration; limited chest reward |
| `heavy_core` | 1500.00 | rare ominous-vault reward; trial progression |
| `iron_horse_armor` | 42.00 | loot/trade anchor calibrated to seven iron ingots; ordinary crafting unsupported |
| `music_disc_11`, `music_disc_13`, `music_disc_cat`, `music_disc_blocks`, `music_disc_chirp`, `music_disc_far`, `music_disc_mall`, `music_disc_mellohi`, `music_disc_stal`, `music_disc_strad`, `music_disc_wait`, `music_disc_ward` | 45.00 | common renewable creeper-drop route; shared tag |
| `music_disc_relic` | 135.00 | rare trail-ruins archaeology acquisition band |
| `music_disc_otherside`, `music_disc_creator_music_box` | 180.00 | restricted structure-chest routes / trial corridor pots |
| `music_disc_pigstep`, `music_disc_precipice` | 225.00 | bastion acquisition / standard vault unique rewards |
| `music_disc_creator` | 270.00 | ominous-vault progression and unique reward |
| `name_tag` | 15.00 | loot/fishing/trade acquisition |
| `nether_star` | 1000.00 | wither summoning/combat and prerequisite skulls |
| `netherite_upgrade_smithing_template` | 200.00 | initial structure-loot template anchor; duplication recipe does not erase acquisition anchor |
| `ominous_trial_key` | 40.00 | ominous-trial combat reward |
| `saddle` | 20.00 | loot/fishing/trade acquisition |
| `totem_of_undying` | 250.00 | evoker/raid combat; renewable with setup |
| `trial_key` | 15.00 | trial-spawner combat reward |
| `trident` | 300.00 | rare drowned drop; renewable combat farming |
| `wither_skeleton_skull` | 150.00 | rare fortress combat drop; wither prerequisite |

### Loot/trade equipment

| Item IDs | CAD each | Acquisition basis |
| --- | ---: | --- |
| `chainmail_boots` | 24.00 | mob/trade acquisition calibrated to iron |
| `chainmail_chestplate` | 48.00 | mob/trade acquisition calibrated to iron |
| `chainmail_helmet` | 30.00 | unobtainable through ordinary crafting; mob/trade acquisition calibrated to iron |
| `chainmail_leggings` | 42.00 | mob/trade acquisition calibrated to iron |

### Deferred smithing outputs

| Item IDs | CAD each | Acquisition basis |
| --- | ---: | --- |
| `netherite_axe`, `netherite_pickaxe` | 4196.50 | explicit acquisition anchor: diamond-equivalent gear + four debris/scrap and four gold + template; smithing recipe execution deferred |
| `netherite_boots` | 4496.00 | explicit acquisition anchor: diamond-equivalent gear + four debris/scrap and four gold + template; smithing recipe execution deferred |
| `netherite_chestplate` | 5696.00 | explicit acquisition anchor: diamond-equivalent gear + four debris/scrap and four gold + template; smithing recipe execution deferred |
| `netherite_helmet` | 4796.00 | explicit acquisition anchor: diamond-equivalent gear + four debris/scrap and four gold + template; smithing recipe execution deferred |
| `netherite_hoe` | 3896.50 | explicit acquisition anchor: diamond-equivalent gear + four debris/scrap and four gold + template; smithing recipe execution deferred |
| `netherite_leggings` | 5396.00 | explicit acquisition anchor: diamond-equivalent gear + four debris/scrap and four gold + template; smithing recipe execution deferred |
| `netherite_shovel` | 3596.50 | explicit acquisition anchor: diamond-equivalent gear + four debris/scrap and four gold + template; smithing recipe execution deferred |
| `netherite_sword` | 3896.25 | explicit acquisition anchor: diamond-equivalent gear + four debris/scrap and four gold + template; smithing recipe execution deferred |

## Disc rarity basis

The CAD 45 base applies to all twelve pinned `creeper_drop_music_discs` tag members because they share a renewable acquisition route. Higher bands reflect restricted access, rarity and progression rather than a strict inverse-probability formula. Disc 5 remains DERIVED: nine unchanged approved CAD 40 fragments produce CAD 360, without a conflicting independent disc anchor.

Primary evidence: the official 1.21.1 artifact's creeper-drop tag and entity loot, rare trail-ruins archaeology, ancient-city/dungeon/stronghold/bastion chest loot, trial-chamber corridor pots, and standard/ominous vault reward tables. Weights across these contexts are not global discovery probabilities. No live loot/world scan reprices the catalog.

## Derived resources and processing examples

| Item/family | Rule |
| --- | --- |
| Copper/iron/gold ingots | Approved raw input divided by 0.75 for the named processing transition; target CAD 3/6/24. |
| Listed cooked meat/fish | Revised per-species raw value /0.75; published values in the meat table above. |
| Baked potato / dried kelp / popped chorus fruit | Approved named application of raw-food processing: raw input /0.75; published CAD 0.33 / 0.13 / 0.67 using potato 0.25, kelp 0.10 and approved chorus fruit 0.50. This applies the broad prepared-food direction; this extension is included in the catalog price approval. |
| Planks/sticks | Planks inherit species log /4; sticks always use oak planks, CAD 0.25. Cherry planks cost CAD 2 each, but a stick made from them still values at CAD 0.25. |
| Metal/gem blocks and nuggets | Supported output quantities; anchored raw/gem resources terminate reversible searches. |
| Gear, machines and building products | Cheapest fully known supported recipe; exact ingredient/output counts, no duplicate category multiplier. |
| Ordinary smelted materials outside named uplift transitions | Ingredient-only cost; no additional generic labour/time/XP charge. |
| Brewing | Each step adds ingredient/3 + blaze powder/60 to the prior exact per-bottle cost; preserve exact effect/strength/duration/form. |

All 760 DERIVED rows contain an actual pinned recipe witness. Ordinary recipe witnesses never execute dynamic assembly. Outputs from excluded recipe families can instead have an explicit acquisition anchor (for example cake, honey block and netherite equipment) or a named variant rule, preserving full vanilla classification without pretending that the excluded recipe is supported.

## Variant and environmental rules

The CSV has 109 VARIANT rows. These are explicit valuation identities/transformations, not authorization to execute every special recipe. Ordinary priced items additionally use the approved damage, compatible-enchantment, curse and rename rules where applicable; they need not change their CSV classification just because a damaged stack exists.

| Family | Catalog pricing rule (adapter feasibility pending) |
| --- | --- |
| Water bucket | Bucket shell + free water. |
| Lava/milk/powder-snow buckets | Bucket shell + CAD 3 lava / CAD 0.50 milk / CAD 0.50 powder-snow content respectively. |
| Fish buckets | Bucket + corresponding fish + free water; no cosmetic fish-pattern premium. |
| Axolotl/tadpole buckets | Bucket + CAD 10 axolotl / CAD 2 tadpole content + free water; no rare-color premium without a configured rule. |
| Potions/splash/lingering | Exact brewing graph, ingredients and allocated fuel; potion bottle counted once. |
| Enchanted books | Ordinary book + approved stored premiums; approved curse multiplier; no durability discount. |
| Written books/filled maps | Underlying writable-book/map reference value; no authorship, text or coordinates premium; preserve exact state. |
| Fireworks/tipped arrows/suspicious stew | Bounded vanilla component-to-material valuation mapping; rocket cost is (paper + gunpowder + priced stars) divided by three output rockets. No arbitrary special recipe assembly. Unknown effects/custom shapes are unavailable. This is an explicit adapter detail to validate in A9-01, not a standard JSON recipe witness. |
| Ominous bottle | Bottle + approved CAD 25 per actual level I–V (total content CAD 25/50/75/100/125); no invented brewing recipe. |
| Dragon breath | Bottle + approved CAD 25 collected content; no bottle double count. |
| Decorated pots/bee containers | Explicit empty shell/pattern material plus supported bounded contents. Bee content approved CAD 2 each; empty nest CAD 5. |
| Stripped logs/stems/bamboo block | Same source material value; manual stripping adds no premium. |
| Oxidized copper states | Same corresponding unoxidized product value; no waiting premium. |
| Concrete | Corresponding powder value + free-water hardening; no water premium. |
| Colored shulker boxes | Empty shulker-box value + one matching dye; recursively add exact contained item values once. |
| Chipped/damaged anvils | Approved 2/3 and 1/3 of an ordinary anvil value; separate named block-condition states. |
| Carved pumpkin | Pumpkin value; no carving premium or seed byproduct credit. |

These typed mappings distinguish normal vanilla identities while unknown or unsupported components remain unavailable; trader listings stay disabled without a player-facing explanation. Bounded nested container semantics, exact custody matching and provider identities are defined by the shared contract. Zero-price unknowns, guessed mod content and a blanket component-stripping rule are forbidden.

## Intentionally unpriced identities

The 115 UNPRICED_OPERATOR rows explicitly include AIR, spawn eggs, command/structure/debug blocks, inaccessible block-item states, player heads and feature-disabled bundle. Bundle is registered in 1.21.1 but gated by the experimental bundle feature and lacks ordinary enabled vanilla survival production here; an enabled-feature integration needs explicit snapshot support rather than silently becoming priced. These rows are deliberate exclusions, not ordinary noncraftable drops. The CSV records every exact identity.

## Review and handoff limits

- This inventory covers base item identities and their named rules, not every possible custom potion, component patch, map/book payload, mod recipe or entity NBT state.
- Other catalog prices and non-item content bands are approved. Log/disc tiers apply the user-requested rarity calibration. Exact component/adapter feasibility remains checkpoint work.
- Static supported-recipe closure confirms an anchored witness for every DERIVED classification, not numeric runtime evaluation or a proof that every gameplay listing is admitted. A9-01 validates source adapters and the recipe/variant manifest; later packages implement and verify only their changed behavior.
- Rounding floors and retained bid/ask spread proofs can disable a very cheap two-sided quote even though its catalog value exists. Do not invent higher prices or bypass the approved rounding to force admission.
- Validate eventual production coverage against the actual trader catalog, exact supported components, current currencies/FX and authoritative source epochs. This catalog does not add native listings or settlement authority.
- Planning verification is inventory/CSV consistency, source witness review, arithmetic and diff/link review only, following [verification policy](../VERIFICATION.md). No automatic test/full-suite gate.
