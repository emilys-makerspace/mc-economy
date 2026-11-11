# Separate optional integration modules

Status: planned outside A10. The user removed all third-party mod compatibility from A10 and requested separately installable modules to reduce core component loading. This note preserves the requirements and does not select implementation or establish verified compatibility.

Core Economy retains generic public API/SPI support and runs without any compatibility modules or their target mods. Integration code, registrations, item catalogs, assets and third-party dependencies belong in the relevant separately distributed JAR; do not bundle one mandatory compatibility package or load absent integration components through core.

| Module | Planned scope |
| --- | --- |
| Sophisticated Backpacks | Item/upgrade/content values; authorized backpack item/cash access; Wallet Upgrade with card/cash storage separate from main inventory |
| Sophisticated Storage | Item/upgrade/content values; inventory-access location policy needs reconciliation before implementation |
| Sophisticated shared support, if needed | Small shared implementation used only by installed Sophisticated modules, not required by core or AE2 |
| Applied Energistics 2 (AE2) | Item/component/cell-content values and banking/trading access to items/cash in an explicitly selected authorized ME network |
| Extension modules/data | Values and relevant access/coexistence support for the 33 inventoried Sophisticated/AE2 extensions, independently selectable by applicable dependencies |
| MineColonies | Later independently specified integration |
| Casino | Later independently specified integration with the planned casino mod |

Preserve explicit source selection, server-side authorization, original-operation recovery, exact cash/card identities and conservative physical custody. Prevent duplicate valuation or consumption through overlapping direct/bridge/network views. Each target module's ordinary items need calibrated explicit or supported derived values; special states remain explicitly classified. Numeric catalogs, exact APIs, wallet capacity/recipe, artifact IDs and independently selectable execution packages still need source-backed specification.

Use explicit pinned target versions, without modpack naming or dynamic dependencies. Installing an adapter may require its actual parent mods/libraries; those remain optional to core Economy. A10 performance/acceptance does not depend on these modules, and no loading/performance improvement is claimed by planning alone.

Current detailed plans: [module boundaries](../integrations/README.md), [shared contract](../integrations/CONTRACT.md), [dependency targets](../integrations/DEPENDENCIES.md), [extension scope](../integrations/EXTENSIONS.md), [complete item values](../integrations/ITEM_VALUES.md), [wallet design](../integrations/WALLET.md). Resolve the recorded Storage inventory-location conflict in the selected module's design rather than silently choosing a policy.

All module packages/subtasks inherit [verification scope](../../.codex/VERIFICATION.md): prose uses diff/link review only; routine code uses at most one focused named-test invocation when useful, no automatic full-suite or completion-test gate. Future runnable module delivery keeps the pinned platform, registered main mod/source set, required development runtimes and working client/server runs, and runs build excluding tests plus runClient help/dry-run once. Use disposable runtime fixtures; preserve user worlds and backups.
