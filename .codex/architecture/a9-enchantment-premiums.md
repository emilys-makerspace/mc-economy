# A9 approved enchantment premiums

The user approved the beneficial-enchantment premium amounts on 2026-11-07. The user also approved additive compatible premiums, durability discounting of the combined item and premiums, ordinary book value plus stored premiums, and a 25% discount per distinct vanilla curse before durability (50% for both). All amounts are editable CAD additions to the item's unenchanted reference value. These are gameplay calibration values, not Minecraft prices or real-world prices. They use the accepted resource calibration (diamond CAD 300, iron CAD 6). Approval of these amounts does not select an implementation package.

Inventory and maximum levels were read from `data/minecraft/enchantment/*.json` in the cached official Minecraft 1.21.1 client artifact. All 42 definitions are represented below, including the two curses. The [official 1.21 release notes](https://feedback.minecraft.net/hc/en-us/articles/27547857163917-Minecraft-Java-Edition-1-21-Tricky-Trials) also document the three mace enchantments. No Gradle, tests or game processes were run.

## Reading the table

Each level column is the **total premium for that level**, not an amount to add to all lower levels. Sharpness V adds CAD 150, not the sum of I through V. Add distinct compatible beneficial enchantment premiums together once. A dash means that level is outside the pinned normal range.

Registry IDs below use the `minecraft:` namespace. The approved relative prices favor broadly useful upgrades, resource yield and rare utility while retaining modest premiums for situational effects. They are game calibration choices, not claims about a universal ranking.

| Enchantment (registry ID) | I | II | III | IV | V |
| --- | ---: | ---: | ---: | ---: | ---: |
| Aqua Affinity (`aqua_affinity`) | 40 | — | — | — | — |
| Bane of Arthropods (`bane_of_arthropods`) | 10 | 20 | 35 | 55 | 80 |
| Blast Protection (`blast_protection`) | 15 | 35 | 60 | 90 | — |
| Breach (`breach`) | 40 | 90 | 160 | 250 | — |
| Channeling (`channeling`) | 100 | — | — | — | — |
| Density (`density`) | 25 | 55 | 95 | 145 | 210 |
| Depth Strider (`depth_strider`) | 40 | 90 | 150 | — | — |
| Efficiency (`efficiency`) | 20 | 45 | 80 | 130 | 200 |
| Feather Falling (`feather_falling`) | 30 | 65 | 110 | 170 | — |
| Fire Aspect (`fire_aspect`) | 50 | 120 | — | — | — |
| Fire Protection (`fire_protection`) | 15 | 35 | 60 | 90 | — |
| Flame (`flame`) | 80 | — | — | — | — |
| Fortune (`fortune`) | 100 | 250 | 500 | — | — |
| Frost Walker (`frost_walker`) | 80 | 180 | — | — | — |
| Impaling (`impaling`) | 10 | 25 | 45 | 70 | 100 |
| Infinity (`infinity`) | 250 | — | — | — | — |
| Knockback (`knockback`) | 15 | 40 | — | — | — |
| Looting (`looting`) | 80 | 180 | 350 | — | — |
| Loyalty (`loyalty`) | 25 | 60 | 110 | — | — |
| Luck of the Sea (`luck_of_the_sea`) | 50 | 120 | 220 | — | — |
| Lure (`lure`) | 30 | 70 | 130 | — | — |
| Mending (`mending`) | 300 | — | — | — | — |
| Multishot (`multishot`) | 120 | — | — | — | — |
| Piercing (`piercing`) | 15 | 35 | 60 | 90 | — |
| Power (`power`) | 20 | 45 | 75 | 110 | 150 |
| Projectile Protection (`projectile_protection`) | 15 | 35 | 60 | 90 | — |
| Protection (`protection`) | 25 | 60 | 110 | 180 | — |
| Punch (`punch`) | 20 | 50 | — | — | — |
| Quick Charge (`quick_charge`) | 35 | 85 | 160 | — | — |
| Respiration (`respiration`) | 25 | 60 | 110 | — | — |
| Riptide (`riptide`) | 60 | 140 | 250 | — | — |
| Sharpness (`sharpness`) | 20 | 45 | 75 | 110 | 150 |
| Silk Touch (`silk_touch`) | 300 | — | — | — | — |
| Smite (`smite`) | 15 | 30 | 50 | 75 | 110 |
| Soul Speed (`soul_speed`) | 80 | 180 | 320 | — | — |
| Sweeping Edge (`sweeping_edge`) | 25 | 60 | 110 | — | — |
| Swift Sneak (`swift_sneak`) | 100 | 225 | 400 | — | — |
| Thorns (`thorns`) | 30 | 75 | 140 | — | — |
| Unbreaking (`unbreaking`) | 50 | 110 | 200 | — | — |
| Wind Burst (`wind_burst`) | 150 | 350 | 650 | — | — |

## Curses and unusual enchantments

| Enchantment | Approved handling |
| --- | --- |
| Curse of Binding (`binding_curse`), I | No positive premium; subtract 25% of combined base and beneficial premiums before durability |
| Curse of Vanishing (`vanishing_curse`), I | No positive premium; subtract 25% of combined base and beneficial premiums before durability |

Count each distinct vanilla curse once. No curse gives factor 1, one gives 0.75, both give 0.50; discounts add rather than compound. Unregistered enchantments, unsupported combinations/applicability or levels outside the configured supported range yield explained unavailability; never clamp or ignore a level silently. Mod-added enchantments need explicit premium entries and supported semantics. A visually enchanted glint alone adds no premium.

## Approved composition and examples

For a supported ordinary equipment item, the approved formula is:

`equipment value = (unenchanted reference value + sum of applicable beneficial premiums) × (1 − 0.25 × distinct vanilla curse count) × remaining durability / maximum durability`

Non-damageable items use factor 1. The approved durability discount applies to the combined base value and premiums. Unbreaking and Mending receive their listed premium without changing the actual durability fraction or projecting future repairs. Renaming adds zero.

An enchanted book uses the approved ordinary book reference value plus the stored enchantment premiums, without a durability discount. Its stored enchantments are not also counted as applied equipment enchantments. Freeze book-specific applicability/compatibility rules in source review before implementation. The pricing formula is approved; exact identity/applicability validation remains source-review work.

A supported filled container remains its empty-container value plus recursively valued contents under the approved formula. Apply durability/enchantments to the relevant individual object; container damage does not discount its contents. Use one captured configuration/recipe/FX snapshot and bounded nesting/work; round only the final display total under the eventual rounding policy.

| Example | Approved calculation | CAD value |
| --- | --- | ---: |
| Full-durability iron sword, Sharpness III and Unbreaking II | 2 × 6 for iron + 0.25 for a recipe-derived stick + 75 + 110 | 197.25 |
| Same sword at 50% durability | 197.25 × 0.5 | 98.625 exact |
| Same sword with Curse of Vanishing at 50% durability | 197.25 × 0.75 × 0.5 | 73.96875 exact |
| Full-durability diamond pickaxe, Efficiency V, Fortune III, Unbreaking III, Mending | 3 × 300 for diamonds + 2 × 0.25 for sticks + 200 + 500 + 200 + 300 | 2,100.50 |
| Renamed version of that pickaxe | Name adds zero | 2,100.50 |
| Mending book | Ordinary book reference value + 300 | Depends on book inputs |

The stick examples follow the canonical CAD 2 oak-log reference: four oak planks per log, four sticks per two oak planks. Sticks remain CAD 0.25 even if crafted from more expensive species or bamboo. Actual recipe/variant feasibility still belongs to A9-01. These premiums do not create new trader offers, grant collateral/wealth eligibility or authorize enchanting/repair recipe execution.

Premium amounts, additive compatible stacking, discounting premiums with damage, stored-book pricing and the 25%-per-curse discount are approved. Review next: technical identity/applicability limits. This table does not select or execute an implementation package.

[A9 planning](a9-planning.md) · [shared contract](../../prompts/A9-item-valuation/CONTRACT.md) · [verification policy](../VERIFICATION.md)
