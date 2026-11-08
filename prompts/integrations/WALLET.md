# Sophisticated Backpacks wallet upgrade — separate module design

## Approved behavior

The user requested an integration-provided **Wallet Upgrade item** for Sophisticated Backpacks. Installing it gives the backpack dedicated storage for cards and cash, separate from its ordinary item inventory, following the interaction model of upgrades such as the jukebox. This is selected separate integration-module design scope, outside A10; no implementation package is started by this document.

The upgrade itself is included in the [complete integration item-value catalog](ITEM_VALUES.md), with its proposed price/recipe to be calibrated. The upgrade belongs to the optional Sophisticated Backpacks integration. Core Economy remains usable without Sophisticated mods; their exact targets and extension requirements remain in [DEPENDENCIES.md](DEPENDENCIES.md). Do not add the wallet as an always-loaded class requiring optional APIs.

## Storage and use contracts

- Provide a dedicated wallet inventory and upgrade UI; wallet stacks do not consume ordinary backpack inventory slots. Do not represent the wallet as a virtual bank balance or merge it invisibly with the main inventory.
- Accept recognized cards and cash through explicit item classification, preserving complete stack/components, cash issuance/world/lot identity and card/token identity. Holding a card is separate from authorization to use it: revoked, expired, foreign-owner or otherwise invalid cards cannot gain authority by being stored here.
- Expose the wallet as a distinct selectable source under the existing explicit-source banking/trading policy. Do not automatically scan/spend from every wallet, backpack, main inventory or network. Physical cash use retains existing custody, money conservation and reviewed source/fee/FX rules.
- Store actual card items. Define selected-card presentation through the existing live owner/token/session/product checks before enabling card-based actions from a wallet; storage is not blanket permission to replace held-card checks. The corresponding server admission adapter must be specified explicitly.
- Preserve wallet contents and identity through backpack save/reload, moving/removing the upgrade and full-inventory situations. Do not delete contents, spill them automatically or create a second usable copy when the upgrade is removed, moved or restored. Establish the pinned upstream persistence/removal hooks and existing Economy uncertain-effect handling before mutation support is claimed.
- Distinguish wallet, backpack main inventory, installed upgrade item and any bridge/network views of the same contents. No duplicate valuation, reservation or consumption. Wallet cash is fiat face-value currency under existing services; item reference valuation alone does not mint funds or qualify financial wealth.
- Keep upgrade storage isolated from unrelated upgrade inventories. Determine actual hopper/automation/bridge exposure from the pinned APIs and specify the supported behavior; do not assume separate UI means inaccessible to automation.

## Remaining design/feasibility details

Specify card/cash slot counts and stack limits, crafting/upgrade recipe and original icon/localized name, upgrade multiplicity, supported carried-backpack locations, selected-card UX, authorized automation, and removal/recovery rules before implementation. Preserve existing gameplay choices; do not silently assign new capacity or price rules. No changes to placed-storage access policy are selected by this wallet design.

## Upstream source observation

An illustrative upstream [JukeboxUpgradeWrapper](https://github.com/P3pp3rF1y/SophisticatedCore/blob/94e7e6b6070950f9d8f617c3c8f5fa63721a02c3/src/main/java/net/p3pp3rf1y/sophisticatedcore/upgrades/jukebox/JukeboxUpgradeWrapper.java) owns a disc-specific item handler attached to the upgrade and invokes save on changes; its [container](https://github.com/P3pp3rF1y/SophisticatedCore/blob/94e7e6b6070950f9d8f617c3c8f5fa63721a02c3/src/main/java/net/p3pp3rf1y/sophisticatedcore/upgrades/jukebox/JukeboxUpgradeContainer.java) provides separate handler slots. This illustrates the requested separation, not a reusable wallet API guarantee.

That inspected revision reports Sophisticated Core 1.5.31, newer than the selected 1.5.14.2413 artifact. Its APIs are **not pinned-version feasibility evidence**; implementation must inspect the selected Core 1.5.14.2413 / Backpacks 3.26.12.2208 source/artifacts. No API signature from that newer revision is frozen here.

## Verification and handoff

Apply [VERIFICATION.md](../../.codex/VERIFICATION.md) to all packages/subtasks/delegates. This design update uses diff/link review only; no Gradle/tests/game. Plan narrowly for meaningful slot filtering, exact custody/card admission and saved upgrade-content recovery behavior using existing fixtures where useful. No automatic tests/full-suite or exhaustive combination gate. Future runnable delivery retains build excluding tests and both client-task checks once, optional integration registration/runtime dependencies, disposable runtime fixtures and one sequential Gradle/game owner.
