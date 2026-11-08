# Standalone compatibility module handoff

The user moved **all mod compatibility out of A10** into independently installable modules to reduce core component loading. [Module plan](../prompts/integrations/README.md) and [shared module contract](../prompts/integrations/CONTRACT.md) own this scope. No implementation package is started.

Preserve explicit target versions, 33 inventoried extensions, optional dependency/class-loading boundaries, complete item values, authorized item/cash inventory access and the Sophisticated Backpacks Wallet Upgrade with separate storage. MineColonies/casino remain their own later modules. [Dependency targets](../prompts/integrations/DEPENDENCIES.md), [extension scope](../prompts/integrations/EXTENSIONS.md), [item values](../prompts/integrations/ITEM_VALUES.md), [wallet](../prompts/integrations/WALLET.md).

Build separately distributed integration JARs instead of putting dormant third-party adapters in the core JAR. Module absence means its code/registrations/assets are absent from the core load path. Share contracts through the public API; dependency/library requirements stay local to installed modules. Each selected runtime module must register its mod/source set and required development dependencies and retain working client/server runs.

Exact package/version/API feasibility, artifact partitioning, inventory-location wording reconciliation and numerical item catalog still need source-backed planning. Required feature support is retained; loading/performance improvement is not measured or implemented by this documentation change. No Gradle/tests/game for prose work; all future module/subtask checks inherit [VERIFICATION.md](VERIFICATION.md), with no automatic full suite or completion test gate.
