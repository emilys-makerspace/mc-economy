# A9 — Item valuation and trader reference prices

Status: all A9 drafts approved 2026-11-07, including technical revision 1, catalog pricing revision 2 and all eight selectable packages, subject to prerequisites. **Implementation packages are approved, unselected and unexecuted.** A9-01 is next eligible for explicit selection. Reading a prompt authorizes no implementation, migration, dependency change or game process.

Read the [shared contract](CONTRACT.md), [technical contracts](../../.codex/architecture/a9-shared-contracts.md), [catalog](../../.codex/architecture/a9-item-catalog.md), [approved premiums](../../.codex/architecture/a9-enchantment-premiums.md) and [selection queue](EXECUTION.md). The [decision record](../../.codex/architecture/a9-planning.md#final-decisions-before-drafting) preserves user choices. A9 follows accepted A8 and its [source-transition boundary](../../.codex/architecture/a8-downstream.md).

## Selected outcome

Provide complete vanilla item valuation coverage through acquisition-based anchors, lowest fully known supported acyclic recipes, brewing and component-aware rules. Replace villager/wandering-trader original price principals prospectively, retaining demand/recovery, reputation, spreads/cohorts, saved currencies, stock and settlement safety. A reference value does not discover a new offer or authorize a trade.

- Overrides precede bases, then supported derivation. Preserve exact internal arithmetic, captured FX and immutable configuration/recipe/provider provenance.
- Publish HALF_UP unit prices with at least one currency minor unit per priced item; quantity totals use the published unit. Free water is explicit. Missing inputs never become zero or a one-cent fallback. New A9 trades have no cumulative fractional carry.
- Raw metals and listed raw meats/fish cost 75% of refined/cooked values; apply processing value once. Preserve accepted log/metal/gem/food calibration.
- Support ordinary crafting, stonecutting and cooking. Brewing includes ingredients, bottles and allocated blaze-powder fuel across standard three-output batches, with free water and no labour/time fee. Unsupported special recipe execution remains excluded; acquisition anchors may still price obtainable outputs.
- Damage reduces value proportionally. Add compatible approved premiums, apply additive 25% discounts per distinct vanilla curse before durability, value stored enchanted books, and sum containers/contents once. Names add no value.
- Mod items receive no built-in or automatic recipe prices. A contribution API/SPI admits explicit values or opted-in supported derivation; no specific integration mod is selected.
- Operator-only items remain unpriced. Unsupported listings stay disabled without player-facing explanations; API/operator diagnostics retain structured availability.

Currencies remain fiat-like. Values create no money, backing, redemption, collateral/wealth eligibility, automatic trading or arbitrary shop repricing. Preserve code, worlds, backups and historical receipts/custody/observations/rates/loans. No reset, parallel financial database or B-series integration is selected.

## Execution and verification

Use the ordered [queue](EXECUTION.md). A9-01 is source/document feasibility only; A9-03 is a non-executable overview of three bounded packages. Explicitly select one package after its prerequisites; completion does not select dependents. Catalog registry coverage is not proof that every component state or live path is implemented.

Apply [project verification scope](../../.codex/VERIFICATION.md) to every package/sub-task. Drafting/source-document work uses diff/link review only: no Gradle/tests/game. Routine runtime work uses at most one useful focused named-test invocation; completion never automatically requires tests or a full suite. Reuse unchanged evidence. A full suite requires an explicit request or concrete stated concern narrower checks cannot address.

Ordinary runnable delivery runs `./gradlew build -x test`, `./gradlew help --task runClient` and `./gradlew runClient --dry-run` once. One owner runs Gradle/game tasks sequentially. Preserve Minecraft 1.21.1, NeoForge 21.1.252, Java 21, registered main source set, working client/server tasks and required development runtime dependencies. Use disposable fixtures for necessary runtime evidence. Write a short `.codex/<selected-id>.md` handoff linking current architecture and scoped evidence. A10 receives read-only valuation/provenance and recorded trader behavior; financial eligibility stays separately selected.
