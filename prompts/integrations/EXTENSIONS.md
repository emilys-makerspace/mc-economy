# Sophisticated and AE2 extension support — separate module contract

The user selected separate optional integration-module support for the extensions accompanying Sophisticated Core, Storage, Backpacks and AE2. [Explicit target artifacts](DEPENDENCIES.md) cover four core mods and 33 extension entries. This expands design scope; no adapter implementation or runtime compatibility is established yet.

## Responsibilities

- **Valuation:** explicitly contribute supported extension items/blocks/upgrades/tiers/variants through the existing SPI. Include actual stored items where the approved container/cell model supports them; count shells, upgrades and contents once, preserve exact identity/quantity and retained provenance. Do not automatically price arbitrary mod items or unsupported states.
- **Inventory access:** integrate extension-backed item/cash stores and network access paths where relevant. Preserve explicit source selection and current server-side authority; honor the target API's actual permissions, range/dimension and availability rules. Wireless/infinity extensions require source-backed capability review, not blanket global access or an invented bypass.
- **Source overlap:** an ME network, bridge and directly selectable Sophisticated store may expose the same backing contents. Canonicalize backing identities and prevent duplicate valuation/extraction/reservations; a bridge is not a second store of the same money. Check automation/concurrent mutation and retention/invalidation before financial effects.
- **Custody and recovery:** retain exact cash lots/goods identity, original operation/outcome, independently observed saves and conservative uncertain-delivery handling. Prove supported source mutation/save APIs; generic simulation or a successful extraction is not proof of persistence. If the target API cannot supply required guarantees, record the precise unsupported path and resolve it before calling that behavior complete.
- **Specialized resources:** item/cash access is the selected banking/trading rail. Energy, fluids, chemicals, XP, EMC and generated/virtual/infinite storage require explicit semantics; extension presence does not make these spendable money or finite physical cash. Bridge item/block valuation may be in scope while a non-item resource rail stays unsupported.
- **Cross-mod bridges:** include AE2-facing items/access behavior of bridges to Create, Mekanism, Oritech, PneumaticCraft, ProjectE, Dyenamics and MineColonies as applicable. This does not select those mods' unrelated financial/gameplay systems. AE2Colonies does not authorize colony banking/wages/taxes. Preserve later MineColonies and casino integration boundaries.
- **Presentation/helpers:** tools, facades, decorative variants, JEI integration and diagnostics need item classification and relevant coexistence review; do not invent a payment adapter when no storage/access path exists.
- **Dependencies:** use separate optional integration modules and the exact version targets. Resolve each extension's actual dependency/API requirements, license and side constraints before adding it to development runtime. Keep core client/server runs usable without optional integrations; selected integration runs include their required runtime dependencies.

## Inventory grouping

Sophisticated group: Allthemodium backpack/storage tiers, Sophisticated Item Actions, official Backpacks/Storage Create integrations, and Create: Sophisticated Backpacks Compat.

AE2 group: AdvancedAE, ExtendedAE, MEGA Cells, AE2 Things, Import Export Card, Network Analyser, Overclocked, Tweaks, Helpers, Wireless Terminals, AEInfinityBooster, Applied Create/Dyenamics/Experienced/Flux/Mekanistics/Oritech/Pneumatics/AppliedE, Create: Applied Kinetics, AE2Colonies, Cable Facades, Cablephobic, both Rechiseled extension entries, Transparent AE2 and JEI Integration.

Each artifact must receive an explicit supported valuation/access/coexistence classification. Do not declare blanket extension compatibility from a parent-mod API check, or silently omit a selected extension. Distinct Rechiseled entries and overlapping Create bridge entries require duplicate registry/dependency/co-install review. Names suggest areas to inspect; exact behavior comes from pinned source/artifacts.

## Package boundaries and verification

Each independently selected module needs bounded source-feasibility, valuation, inventory/custody and native acceptance packages with explicit dependencies before coding. These are outside A10 and are not A10 prerequisites or acceptance gates. Shared contracts and backing identities come before adapters; finish each selected package before dependents. No new implementation package is selected by this inventory.

Inherit [VERIFICATION.md](../../.codex/VERIFICATION.md) for every package/sub-task/delegate: prose uses diff/link review; routine code uses at most one focused named-test invocation when useful. No automatic full suite or test matrix per artifact. Reuse proven shared paths and choose checks for genuinely different storage/authority behavior. Runnable delivery uses build excluding tests plus runClient help/dry-run once; preserve pinned platform, registration and required development runtimes. One owner runs Gradle/game checks sequentially; necessary game witnesses use disposable worlds only.

## Optional integration dependencies — approved

All targeted third-party mods and extensions are optional to Economy. Economy must start and retain its core banking/trading features with none installed. Activate each adapter only when its actual required parent mods/libraries are present and compatible; optional class references must not load on absent-mod paths. An absent extension disables only its integration, not the entire integration family. Target mods keep their own dependency requirements; optionality to Economy does not waive those requirements.

Use separate integration mods/modules, avoid bundling third-party mod JARs into Economy, and keep their runtime dependencies scoped to the applicable integration development runs. Such runs must register the integration and include all required dependencies; retain working core runClient/runServer without them. Incompatible installed versions must not cause an implicit upstream-version substitution or unsupported adapter activation. Existing saved integration references must preserve their data and return appropriate unavailability rather than lose contents or gain fallback access.

Select proportionate absent-mod/parent-only/extension evidence where loading changes need it, reusing shared-path evidence. This introduces no automatic full combination matrix, test invocation or full-suite gate.

Sophisticated Backpacks wallet decision: the integration adds a Wallet Upgrade item with dedicated card/cash storage separate from the backpack main inventory, like the jukebox upgrade model. Follow [the wallet design](WALLET.md) for distinct source identity, retained card authority, cash custody and removal/save recovery; exact capacity/recipe and pinned API feasibility remain to be settled.

Complete item-value coverage is selected for all items in the four core targets and 33 extension entries, plus the new Wallet Upgrade. Follow [the item catalog requirement](ITEM_VALUES.md): actual registry inventory, per-item explicit values/supported derivations and component/container treatment, with visible special-state classifications and reviewed numerical calibration. No automatic pricing of unrelated mods or implicit new financial eligibility.
