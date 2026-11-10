# A7 vanilla template inventory — classification revision 1

Pinned-resource content audit complete for the ordinary house pools and all town-center starts. No production classifier is installed. Counts are unique resources, not generated building instances. The explicit identity lists below are the revision-1 manifest.

Source: `build/moddev/artifacts/neoforge-21.1.252-client-extra-aka-minecraft-resources.jar`
SHA-256: `8e9cf6f4b6eafb1c6bf745a67a61dd1736f31865cad409e5b2bec918667dab5e`

Count selected house/workplace instances after original layout selection. Do not count weights or empty entries. Farms, animal pens and stables are approved exclusions; accessory pieces and meeting points are candidate exclusions as decorations. Approved scope excludes abandoned villages entirely: they receive no A7 content. Abandoned-start totals below support detection, not bank planning.

| Style | House/workplace | Excluded house-pool resources | Unresolved | Empty entries | Normal / abandoned starts |
| --- | ---: | ---: | ---: | ---: | ---: |
| plains | 26 | 10 | 0 | 1 | 4 / 4 |
| desert | 23 | 5 | 0 | 1 | 3 / 3 |
| savanna | 25 | 6 | 0 | 1 | 4 / 4 |
| snowy | 26 | 4 | 0 | 1 | 3 / 3 |
| taiga | 23 | 4 | 0 | 1 | 2 / 2 |

Roads, terminators, decorations, entity spawns and town-center-only pieces are outside the inclusion set. They are excluded, while still participating in preservation/collision bounds.

## Content evidence and classification rules

The read-only [audit script](../../../tools/a7-template-audit.py) parses the pinned jar's actual gzipped NBT palettes and block instances, not resource names alone. [Per-resource evidence](../verification/a7-template-audit.json) records SHA-256, dimensions, actual bed/workstation counts, doors, entities, start identities/processors and source-pool fingerprints. All 123 included resources contain at least one actual bed or profession workstation (including filled water cauldrons in all five tanneries). None of the 29 excluded house-pool resources contains those signals. The excluded accessory/meeting pieces, farms, pens and stables remain excluded by explicit approved category, even if a future resource revision adds a bed. Door counts count upper/lower blocks, not entrances. These signals substantiate this pinned manifest; they are not a general rule to classify arbitrary datapack content. No rendered-template or generated-world observation is claimed.

For each original selected `PoolElementStructurePiece`, resolve its template element before counting. A reviewed single/legacy-single element whose identity **and raw NBT content fingerprint** match the manifest contributes exactly one instance, regardless of beds, residents, weight or repeated connectors. Repeated selected instances each count once. Empty elements contribute zero. Known non-building vanilla pieces contribute zero. Do not count bank additions or recursively count a composite's children as separate buildings. Reviewed pinned feature elements listed in the reachable graph are decorations and count zero. For list/composite elements, unmanifested feature elements, inline templates, unresolved IDs, replaced house templates or an unrecognized start, capture `UNSUPPORTED_TEMPLATE` and preserve vanilla generation without bank placement; do not silently undercount and select a smaller bank set. A7-04 may expand coverage only with an explicit reviewed classification revision. If a resource reload changes a pool or relevant template fingerprint, the affected style is not ready for new A7 plans until reviewed; existing captured plans retain their revisions.

## Reachable normal layout graph

The audit follows actual NBT jigsaw `pool` targets and JSON fallback edges from each style's town-center pool, traversing normal start alternatives only. This includes common entity spawn pools and well bottoms rather than assuming the style's folder is complete. Every reachable pool has its raw JSON fingerprint, elements, weights and fallback recorded. Every reachable single/legacy-single template has a raw NBT fingerprint, dimensions, jigsaw targets, bed/workstation summary and entity summary. Referenced processor-list JSON and direct placed/configured-feature JSON are fingerprinted. Unknown element kinds/composites and unclassified reachable resources fail the audit rather than silently becoming non-buildings.

| Style | Reachable pools | Reachable single templates | Decoration features | Processor lists |
| --- | ---: | ---: | ---: | ---: |
| plains | 14 | 85 | 3 | 5 |
| desert | 13 | 69 | 2 | 1 |
| savanna | 13 | 83 | 3 | 2 |
| snowy | 12 | 79 | 3 | 2 |
| taiga | 12 | 79 | 5 | 3 |

The JSON explicitly categorizes all non-building templates as approved house-pool exclusion, road, terminator, decoration, town center, well or entity spawn; each contributes zero. Common iron-golem and desert camel-spawn templates are entity spawns. Graph counts include common resources separately for each reachable style and are not village counts. The `minecraft:empty` fallback pool terminates traversal. All supported single elements remain preservation obstacles regardless of classification; feature output is decoration, not a building, and requires terrain/collision protection during placement. This is resource evidence, not proof of generated terrain safety. The jar hash pins the complete resource baseline; direct feature hashes do not establish compatibility with datapacks replacing transitive configured-feature dependencies. Any such replacement is outside revision-1 support and must fail style readiness until explicitly reviewed.

The auditor asserts 123 total included identities with actual bed/workstation signals and 29 excluded house-pool resources without those signals, and rejects manifest IDs absent from the pinned pools. Runtime counting must use this captured identity/fingerprint manifest, not infer categories from arbitrary names.

## Abandoned-start detection

Resolve the selected **first original jigsaw piece's actual template identity**, not biome, nearby zombie entities or the structure definition (ordinary and abandoned villages share that definition). The evidence lists every supported start: plains has fountain_01 and meeting_point_1–3; desert meeting_point_1–3; savanna meeting_point_1–4; snowy meeting_point_1–3; taiga meeting_point_1–2. Their ordinary IDs are `minecraft:village/<style>/town_centers/<name>`. Each has a corresponding `minecraft:village/<style>/zombie/town_centers/<name>` abandoned ID, 16 ordinary and 16 abandoned in total. Check those exact manifested IDs/fingerprints; `/zombie/` is descriptive, not a generic authorization rule. Exclude a recognized abandoned start before house counting and append no A7 piece, path or marker. Unknown/composite/replaced starts are unsupported, never assumed ordinary. Processor references in the audit corroborate zombie starts; a changed start pool/processor definition requires compatibility review rather than assuming the selected ID proves unchanged behavior.

Audit command: `python3 tools/a7-template-audit.py` (JSON to stdout). It reads local resources and this manifest; it does not launch Minecraft, modify templates or touch worlds. Gradle/test/game checks are outside this document-only classification work.


## plains

Source pools: `minecraft:village/plains/houses`, `minecraft:village/plains/town_centers`.

### House/workplace

- `minecraft:village/plains/houses/plains_armorer_house_1`
- `minecraft:village/plains/houses/plains_big_house_1`
- `minecraft:village/plains/houses/plains_butcher_shop_1`
- `minecraft:village/plains/houses/plains_butcher_shop_2`
- `minecraft:village/plains/houses/plains_cartographer_1`
- `minecraft:village/plains/houses/plains_fisher_cottage_1`
- `minecraft:village/plains/houses/plains_fletcher_house_1`
- `minecraft:village/plains/houses/plains_library_1`
- `minecraft:village/plains/houses/plains_library_2`
- `minecraft:village/plains/houses/plains_masons_house_1`
- `minecraft:village/plains/houses/plains_medium_house_1`
- `minecraft:village/plains/houses/plains_medium_house_2`
- `minecraft:village/plains/houses/plains_shepherds_house_1`
- `minecraft:village/plains/houses/plains_small_house_1`
- `minecraft:village/plains/houses/plains_small_house_2`
- `minecraft:village/plains/houses/plains_small_house_3`
- `minecraft:village/plains/houses/plains_small_house_4`
- `minecraft:village/plains/houses/plains_small_house_5`
- `minecraft:village/plains/houses/plains_small_house_6`
- `minecraft:village/plains/houses/plains_small_house_7`
- `minecraft:village/plains/houses/plains_small_house_8`
- `minecraft:village/plains/houses/plains_tannery_1`
- `minecraft:village/plains/houses/plains_temple_3`
- `minecraft:village/plains/houses/plains_temple_4`
- `minecraft:village/plains/houses/plains_tool_smith_1`
- `minecraft:village/plains/houses/plains_weaponsmith_1`

### Exclusion

- `minecraft:village/plains/houses/plains_accessory_1`
- `minecraft:village/plains/houses/plains_animal_pen_1`
- `minecraft:village/plains/houses/plains_animal_pen_2`
- `minecraft:village/plains/houses/plains_animal_pen_3`
- `minecraft:village/plains/houses/plains_large_farm_1`
- `minecraft:village/plains/houses/plains_meeting_point_4`
- `minecraft:village/plains/houses/plains_meeting_point_5`
- `minecraft:village/plains/houses/plains_small_farm_1`

- `minecraft:village/plains/houses/plains_stable_1`
- `minecraft:village/plains/houses/plains_stable_2`


## desert

Source pools: `minecraft:village/desert/houses`, `minecraft:village/desert/town_centers`.

### House/workplace

- `minecraft:village/desert/houses/desert_armorer_1`
- `minecraft:village/desert/houses/desert_butcher_shop_1`
- `minecraft:village/desert/houses/desert_cartographer_house_1`
- `minecraft:village/desert/houses/desert_fisher_1`
- `minecraft:village/desert/houses/desert_fletcher_house_1`
- `minecraft:village/desert/houses/desert_library_1`
- `minecraft:village/desert/houses/desert_mason_1`
- `minecraft:village/desert/houses/desert_medium_house_1`
- `minecraft:village/desert/houses/desert_medium_house_2`
- `minecraft:village/desert/houses/desert_shepherd_house_1`
- `minecraft:village/desert/houses/desert_small_house_1`
- `minecraft:village/desert/houses/desert_small_house_2`
- `minecraft:village/desert/houses/desert_small_house_3`
- `minecraft:village/desert/houses/desert_small_house_4`
- `minecraft:village/desert/houses/desert_small_house_5`
- `minecraft:village/desert/houses/desert_small_house_6`
- `minecraft:village/desert/houses/desert_small_house_7`
- `minecraft:village/desert/houses/desert_small_house_8`
- `minecraft:village/desert/houses/desert_tannery_1`
- `minecraft:village/desert/houses/desert_temple_1`
- `minecraft:village/desert/houses/desert_temple_2`
- `minecraft:village/desert/houses/desert_tool_smith_1`
- `minecraft:village/desert/houses/desert_weaponsmith_1`

### Exclusion

- `minecraft:village/desert/houses/desert_animal_pen_1`
- `minecraft:village/desert/houses/desert_animal_pen_2`
- `minecraft:village/desert/houses/desert_farm_1`
- `minecraft:village/desert/houses/desert_farm_2`
- `minecraft:village/desert/houses/desert_large_farm_1`


## savanna

Source pools: `minecraft:village/savanna/houses`, `minecraft:village/savanna/town_centers`.

### House/workplace

- `minecraft:village/savanna/houses/savanna_armorer_1`
- `minecraft:village/savanna/houses/savanna_butchers_shop_1`
- `minecraft:village/savanna/houses/savanna_butchers_shop_2`
- `minecraft:village/savanna/houses/savanna_cartographer_1`
- `minecraft:village/savanna/houses/savanna_fisher_cottage_1`
- `minecraft:village/savanna/houses/savanna_fletcher_house_1`
- `minecraft:village/savanna/houses/savanna_library_1`
- `minecraft:village/savanna/houses/savanna_mason_1`
- `minecraft:village/savanna/houses/savanna_medium_house_1`
- `minecraft:village/savanna/houses/savanna_medium_house_2`
- `minecraft:village/savanna/houses/savanna_shepherd_1`
- `minecraft:village/savanna/houses/savanna_small_house_1`
- `minecraft:village/savanna/houses/savanna_small_house_2`
- `minecraft:village/savanna/houses/savanna_small_house_3`
- `minecraft:village/savanna/houses/savanna_small_house_4`
- `minecraft:village/savanna/houses/savanna_small_house_5`
- `minecraft:village/savanna/houses/savanna_small_house_6`
- `minecraft:village/savanna/houses/savanna_small_house_7`
- `minecraft:village/savanna/houses/savanna_small_house_8`
- `minecraft:village/savanna/houses/savanna_tannery_1`
- `minecraft:village/savanna/houses/savanna_temple_1`
- `minecraft:village/savanna/houses/savanna_temple_2`
- `minecraft:village/savanna/houses/savanna_tool_smith_1`
- `minecraft:village/savanna/houses/savanna_weaponsmith_1`
- `minecraft:village/savanna/houses/savanna_weaponsmith_2`

### Exclusion

- `minecraft:village/savanna/houses/savanna_animal_pen_1`
- `minecraft:village/savanna/houses/savanna_animal_pen_2`
- `minecraft:village/savanna/houses/savanna_animal_pen_3`
- `minecraft:village/savanna/houses/savanna_large_farm_1`
- `minecraft:village/savanna/houses/savanna_large_farm_2`
- `minecraft:village/savanna/houses/savanna_small_farm`


## snowy

Source pools: `minecraft:village/snowy/houses`, `minecraft:village/snowy/town_centers`.

### House/workplace

- `minecraft:village/snowy/houses/snowy_armorer_house_1`
- `minecraft:village/snowy/houses/snowy_armorer_house_2`
- `minecraft:village/snowy/houses/snowy_butchers_shop_1`
- `minecraft:village/snowy/houses/snowy_butchers_shop_2`
- `minecraft:village/snowy/houses/snowy_cartographer_house_1`
- `minecraft:village/snowy/houses/snowy_fisher_cottage`
- `minecraft:village/snowy/houses/snowy_fletcher_house_1`
- `minecraft:village/snowy/houses/snowy_library_1`
- `minecraft:village/snowy/houses/snowy_masons_house_1`
- `minecraft:village/snowy/houses/snowy_masons_house_2`
- `minecraft:village/snowy/houses/snowy_medium_house_1`
- `minecraft:village/snowy/houses/snowy_medium_house_2`
- `minecraft:village/snowy/houses/snowy_medium_house_3`
- `minecraft:village/snowy/houses/snowy_shepherds_house_1`
- `minecraft:village/snowy/houses/snowy_small_house_1`
- `minecraft:village/snowy/houses/snowy_small_house_2`
- `minecraft:village/snowy/houses/snowy_small_house_3`
- `minecraft:village/snowy/houses/snowy_small_house_4`
- `minecraft:village/snowy/houses/snowy_small_house_5`
- `minecraft:village/snowy/houses/snowy_small_house_6`
- `minecraft:village/snowy/houses/snowy_small_house_7`
- `minecraft:village/snowy/houses/snowy_small_house_8`
- `minecraft:village/snowy/houses/snowy_tannery_1`
- `minecraft:village/snowy/houses/snowy_temple_1`
- `minecraft:village/snowy/houses/snowy_tool_smith_1`
- `minecraft:village/snowy/houses/snowy_weapon_smith_1`

### Exclusion

- `minecraft:village/snowy/houses/snowy_animal_pen_1`
- `minecraft:village/snowy/houses/snowy_animal_pen_2`
- `minecraft:village/snowy/houses/snowy_farm_1`
- `minecraft:village/snowy/houses/snowy_farm_2`


## taiga

Source pools: `minecraft:village/taiga/houses`, `minecraft:village/taiga/town_centers`.

### House/workplace

- `minecraft:village/taiga/houses/taiga_armorer_2`
- `minecraft:village/taiga/houses/taiga_armorer_house_1`
- `minecraft:village/taiga/houses/taiga_butcher_shop_1`
- `minecraft:village/taiga/houses/taiga_cartographer_house_1`
- `minecraft:village/taiga/houses/taiga_fisher_cottage_1`
- `minecraft:village/taiga/houses/taiga_fletcher_house_1`
- `minecraft:village/taiga/houses/taiga_library_1`
- `minecraft:village/taiga/houses/taiga_masons_house_1`
- `minecraft:village/taiga/houses/taiga_medium_house_1`
- `minecraft:village/taiga/houses/taiga_medium_house_2`
- `minecraft:village/taiga/houses/taiga_medium_house_3`
- `minecraft:village/taiga/houses/taiga_medium_house_4`
- `minecraft:village/taiga/houses/taiga_shepherds_house_1`
- `minecraft:village/taiga/houses/taiga_small_house_1`
- `minecraft:village/taiga/houses/taiga_small_house_2`
- `minecraft:village/taiga/houses/taiga_small_house_3`
- `minecraft:village/taiga/houses/taiga_small_house_4`
- `minecraft:village/taiga/houses/taiga_small_house_5`
- `minecraft:village/taiga/houses/taiga_tannery_1`
- `minecraft:village/taiga/houses/taiga_temple_1`
- `minecraft:village/taiga/houses/taiga_tool_smith_1`
- `minecraft:village/taiga/houses/taiga_weaponsmith_1`
- `minecraft:village/taiga/houses/taiga_weaponsmith_2`

### Exclusion

- `minecraft:village/taiga/houses/taiga_animal_pen_1`
- `minecraft:village/taiga/houses/taiga_large_farm_1`
- `minecraft:village/taiga/houses/taiga_large_farm_2`
- `minecraft:village/taiga/houses/taiga_small_farm_1`

