# Separate integration-module contract

Status: design requirements retained from the former A10 compatibility planning. The user moved all mod compatibility outside A10 into separately distributed opt-in modules to reduce component loading. No module implementation or package execution begins through this document. [Module boundaries](README.md#module-boundaries) and the detailed linked specifications define the remaining work.

## Loading and dependencies

Core Economy must not bundle, eagerly load or register target-specific compatibility code/data, upgrades or item catalogs. It retains generic public API/SPI seams and must start with none of the integration modules or target mods installed. No integration module is an A10 dependency or acceptance requirement.

Each independently installed module includes only its responsibility and depends on Economy plus its actual target requirements. Parent mods may remain required dependencies of their dedicated adapter module; they remain optional to core Economy. Isolate target class loading and registrations so that an absent extension disables only its own integration. A shared Sophisticated support library is permitted only for genuinely shared behavior and must not load either parent family or its extensions eagerly. AE2-only use must not pull in Sophisticated support.

Extension-specific modules or separately selectable data contributions must not make the whole extension set mandatory. A bridge module may require its specific participating integrations, but must not require unrelated bridges. Do not bundle third-party target JARs. Use [explicit pinned artifact targets](DEPENDENCIES.md), without modpack naming, moving modpack sources, dynamic versions or silent upstream-latest substitution. Resolve transitive dependencies, incompatible/overlapping extension entries and side constraints during source feasibility.

Missing/incompatible integration support must preserve saved integration references and return appropriate unavailability rather than delete contents, fabricate values or silently switch access paths. Do not assume uninstalling a module safely migrates its item/content data; define that module's lifecycle/removal/recovery contract before claiming it.

## Retained functional scope

- **Item values:** every registered item in the four selected core targets and 33 extension entries is accounted for with explicit calibrated values or supported deterministic derivations, component/container rules and visible special-state classifications. The new Wallet Upgrade is included. Follow [ITEM_VALUES.md](ITEM_VALUES.md); numerical catalogs and actual registry inventory remain future module work.
- **Backpacks:** preserve the selected valuation and explicit-source inventory-access work. The integration adds the [Wallet Upgrade](WALLET.md), with dedicated actual card/cash storage separate from the backpack main inventory, similar to the jukebox upgrade interaction model. Capacity, recipe and selected-card admission remain design work.
- **Storage:** preserve registered item/upgrade/content valuation. The conflicting prior nearby/placed-source versus valuation-only policies are recorded in [the module plan](README.md#contract-reconciliation-before-implementation) and must be reconciled before enabling Storage inventory access. This extraction does not silently choose a policy.
- **AE2:** preserve item/component/cell-content valuation and banking/trading access to items/cash in an explicitly selected authorized ME network. Storage references do not confer permissions or automatic spending across accessible networks.
- **Extensions:** retain all inventoried extension entries under [EXTENSIONS.md](EXTENSIONS.md). Classify each extension's valuation/access/coexistence responsibilities rather than assuming every helper or decorative mod needs a payment adapter. Parent integration support alone is not proof that an extension is covered.
- **Future targets:** direct MineColonies and casino integrations stay separately planned. AE2Colonies remains an extension target, not authorization to implement unrelated colony finance.

## Authority, custody and overlap

Preserve complete item/components, cash world/issuance/lot identity, actual card/token identity and quantities. Valuation reads grant no permission to extract/spend, quote a trade, qualify wealth/collateral or mint funds. Holding a card in a wallet does not bypass live owner/token/session/product checks. Preserve exact captured denomination/FX and current server-offered confirmation/fee/source terms.

Inventory mutation needs current server-side access, explicit source selection, canonical backing identity and revision checks. Never spend automatically from other inventories or networks. Map direct, wallet, bridge and ME views to backing identities to prevent double valuation, reservation or extraction. Concurrent automation is an affected source-state problem, not permission to accept stale snapshots.

Retain original operation/outcome lookup before new effects, independently observed saves, finite cash lots and conservative uncertain-custody/delivery handling. Source simulation/extraction alone is not durable save evidence. Unsupported API guarantees must be recorded and resolved before claiming the mutation path complete. Non-item energy/fluids/chemicals/XP/EMC and creative/infinite states are not financial rails by implication.

## Package and verification boundaries

Each module needs independently selectable source-feasibility, catalog/calibration, relevant storage/authority/custody implementation and qualified native acceptance boundaries. Freeze shared backing identities and public interfaces before dependent adapters; finish a selected package and handoff before dependents. This is a module plan, not Gradle scaffolding or a promise that every module needs the same set of tests.

Apply [VERIFICATION.md](../../.codex/VERIFICATION.md) to all module prompts, templates, sub-tasks and delegated assignments. Prose uses diff/link review only. Routine changes use at most one focused named-test invocation when useful for changed behavior or a concrete regression, normally one to three classes using existing real-service/SQLite fixtures. Reuse unchanged evidence; no automatic tests/full-suite or exhaustive combination matrix. Full suite requires an explicit request or a concrete stated reason narrower checks cannot address.

An ordinary runnable module delivery uses Minecraft 1.21.1, NeoForge 21.1.252 and Java 21, working client/server Gradle runs, registered main source set, and required runtime dependencies for that module's development run. Run `./gradlew build -x test`, `./gradlew help --task runClient` and `./gradlew runClient --dry-run` once, rerunning only affected failures after fixes. Keep core runs usable without integrations. One owner runs Gradle/game checks sequentially; necessary runtime evidence uses disposable worlds. Preserve existing source, worlds, backups and historical evidence. User-facing README/changelog rules remain project-wide.
