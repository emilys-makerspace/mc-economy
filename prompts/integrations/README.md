# Optional integration modules

The user removed all mod compatibility from A10 and selected separately distributed, opt-in modules to reduce component loading. The previously selected Sophisticated/AE2 functionality, extensions, Wallet Upgrade and complete item-value coverage are retained here. None is an A10 implementation task, dependency or acceptance gate. No integration module has been implemented or selected for execution by this planning extraction.

Core Economy exposes its existing integration APIs and runs without these modules or their target mods. Installing an integration module should load only its relevant compatibility code/data and actual dependencies. Do not ship one mandatory compatibility bundle or make installing one integration require every extension.

## Module boundaries

Names below describe responsibility boundaries; final artifact/mod IDs and package prompts need source-feasibility work before implementation.

| Separate module | Retained responsibility | Dependency boundary |
| --- | --- | --- |
| Sophisticated shared support, only if needed | Small genuinely shared adapters/types; no eagerly registered Backpacks/Storage/extension behavior | Economy API and required Sophisticated Core types only; do not install it for AE2-only use |
| Sophisticated Backpacks | All registered item values and component/contents valuation; explicit-source carried-backpack inventory access; dedicated Wallet Upgrade for cards/cash | Economy, Backpacks and its actual required libraries; no Storage or AE2 requirement |
| Sophisticated Storage | All registered item values, upgrades and contents; any inventory-access behavior only after the location-policy conflict below is reconciled | Economy, Storage and its actual required libraries; no Backpacks or AE2 requirement |
| AE2 | All registered item values and supported component/cell-content valuation; explicit-source authorized ME-network item/cash access | Economy and AE2's actual required libraries; no Sophisticated requirement |
| Extension-specific modules/data | All items and relevant storage/access/coexistence behavior for each of the 33 selected extension entries | Only the relevant parent integration(s), installed extension and actual required libraries; avoid references to absent unrelated extensions |
| MineColonies | Later separately specified integration | No implicit colony banking, wages or taxes from AE2Colonies support |
| Casino | Later separately specified integration | No artifact/version or complete behavior assigned yet |

Data-only contributions can use the smallest separately selectable unit supported by the eventual packaging design. If common code is shared, it must not trigger unrelated component registration or class loading. Resolve real dependencies and side requirements before finalizing artifacts. Cross-mod bridge modules require only the exact integration pair they serve, rather than a family-wide dependency.

## Retained specifications

- [Shared module contract](CONTRACT.md)
- [Explicit dependency targets and artifact metadata](DEPENDENCIES.md)
- [Sophisticated/AE2 extension scope](EXTENSIONS.md)
- [Complete item-value catalog requirement](ITEM_VALUES.md)
- [Backpacks Wallet Upgrade](WALLET.md)

## Contract reconciliation before implementation

Earlier A10 notes contain both “carried backpacks only / Storage valuation-only” and “carried backpacks plus nearby authorized storage.” Moving the work out of A10 does not select between those access-location policies. Preserve both as a known contract conflict; resolve the intended Storage inventory-source scope in the selected module's design before coding. AE2's separately approved authorized ME-network access and the Wallet Upgrade's separate card/cash storage remain retained requirements. This extraction adds no placed-container access authority.

Exact catalog values, registry counts, wallet capacity/recipe, selected-card presentation, pinned source APIs and module package prompts remain to be specified. Explicit source selection, owner/session checks, captured financial terms and conservative custody/recovery remain required.

## Verification

All modules, packages, templates and delegated tasks inherit [project verification scope](../../.codex/VERIFICATION.md). This planning extraction uses diff/link review only; no Gradle, tests or game. Routine implementation uses at most one focused named-test invocation when useful for meaningful changed behavior, reusing existing real-service/SQLite fixtures and unchanged evidence. No automatic test, combination matrix or full-suite completion gate.

An ordinary runnable module delivery uses Java 21 and runs `./gradlew build -x test`, `./gradlew help --task runClient` and `./gradlew runClient --dry-run` once. Preserve Minecraft 1.21.1, NeoForge 21.1.252, working `runClient`/`runServer`, registered module main source set and all required development runtime dependencies. Keep core runs working without integrations. One owner runs Gradle/game checks sequentially; necessary runtime witnesses use disposable worlds, never player worlds or backups.
