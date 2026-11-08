# A9 planning review — 2026-11-07

Approval update: the user explicitly approved **all A9 drafts**, including technical revision 1, catalog pricing revision 2, shared contracts and all eight selectable packages, subject to their prerequisites. Approval is recorded in the queue, package headers, architecture and project index. All packages remain unselected/unexecuted; this approval does not establish A9-01 acceptance. Approval-record changes were reviewed with `git diff --check`; no Gradle/tests/game processes ran.

Scope: drafting only, not execution or acceptance of A9-01. [Handoff](../A9-planning.md), [technical draft](../architecture/a9-shared-contracts.md), [catalog](../architecture/a9-item-catalog.md) and [queue](../../prompts/A9-item-valuation/EXECUTION.md).

## Evidence

- Two `economy_worker` agents owned disjoint catalog and prompt files; one `economy_researcher` performed read-only source/contract review. The main agent owned shared contracts, integration and final document checks. No agent ran Gradle, tests or game processes.
- Pinned source inventory reconciled actual registration keys against all 1,333 `Items.java` declarations, including AIR and the misspelled source constant registering `cut_sandstone_slab`. CSV identities are unique and exhaustive for this pinned static inventory, not all runtime component states.
- Final classifications: 349 BASE (43 prior-approved anchor amounts), 761 DERIVED, 108 VARIANT, 115 UNPRICED_OPERATOR. Each DERIVED row references an actual recipe in the official 1.21.1 artifact. Static closure provides a known path, not a proof of runtime cheapest cost or component admission.
- Source review confirmed brewing fuel20, three outputs, vanilla mix getter exclusion, trusted ingredient/remainder detachment, separate stored/applied enchantments and container components. Source artifact identities/hashes are recorded in the technical/catalog documents.
- Numerical review: raw/refined .75 ratio; log→plank→stick quantities; brewing ingredient/3 + fuel/60; additive premiums/curses before durability; nearest-unit HALF_UP minimum1; three output rockets share their material costs. Ingot target witnesses use raw inputs. Chorus plant was corrected to unobtainable-as-item rather than assigned an ordinary acquisition price.
- Contract review resolved deterministic spread preservation using the dimensionless captured cohort ratio, and valid reloads atomically advancing already-active A9 source revisions/epochs without activating a new scope. Existing 20% gap after rounding can disable a cheap quote; minimum prices do not bypass spread admission.
- Scoped local-link review and `git diff --check` passed. No public roadmap or unrelated prompt was used. Existing staged work was preserved.

## Limits and next step

Pricing revision 2: the user approved all other catalog prices and requested species-log tiers, canonical oak-valued sticks, individual raw meat/fish values and disc rarity bands with CAD 45 common base. Catalog coverage is now 349 BASE, 760 DERIVED, 109 VARIANT and 115 UNPRICED_OPERATOR; stick moved from generic recipe derivation to its explicit canonical rule. Other prices, including CAD 40 disc fragments, are unchanged. Cooked values follow raw /0.75 and final HALF_UP rounding. Pinned loot/tag source review informed disc acquisition bands; they are not claims of global find probabilities. Documentation/CSV/arithmetic review only; no runtime or Gradle checks.

No runtime data, migrations, assets, dependencies or code changed. No packaged mod delivery, `runClient` verification, game observation, performance claim or A9 implementation acceptance is established by this draft. Under [verification scope](../VERIFICATION.md), Gradle/tests/game are inappropriate for prose-only planning.

New acquisition prices, special-state maps and concrete limits/interfaces are reviewable draft artifacts. A9-01 confirms exact pinned API/codec feasibility and coverage; runtime packages implement only after explicit selection and prerequisites. B-series integrations, financial eligibility and arbitrary mod support remain outside scope. No automatic full-suite gate is introduced.
