# PR #6 source refresh bound fix

2026-11-08: addressed [the review finding](https://github.com/emilys-makerspace/mc-economy/pull/6#discussion_r4507254635).

`TradeSources.prepareRefresh` now allows its SQLite read to return all64 rows selected by SQL:63 supported active scopes plus one overflow sentinel. The existing atomic scope limit remains 63. Updated the [source contract](../architecture/trade-source-transition.md) and root changelog.

Checks passed once each:

- `./gradlew test --tests 'ca.emilybelnavis.mcmods.economy.trade.pricing.TradeSourcesTest'`:3 tests, zero failures/errors. The new real SQLite regression publishes a changed valuation with63 scopes, checks every source revision/manifest and their shared publication transaction, then checks that64 scopes raise the explicit scope-limit error.
- `./gradlew build -x test`.
- `./gradlew help --task runClient`.
- `./gradlew runClient --dry-run`.
- `git diff --check`.

Minecraft1.21.1, NeoForge21.1.252, Java21, main mod registration, client/server runs and SQLite development runtime wiring remain configured. No full suite or game launch was needed for this query-bound fix; dry-run evidence establishes task wiring only.
