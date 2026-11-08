# Economy

![Economy — Banking. Cash. Commerce.](docs/branding/economy-banner.png)

Banking, physical cash and player shops for Minecraft, with credit, loans and a persistent world economy. Currently in alpha.

**Requires Minecraft 1.21.1 · NeoForge 21.1.252 · Java 21.** No additional mods are required.

## What's included

- Eleven currencies, activity-driven exchange rates and currency-specific loan prime every 20 real minutes, plus collectible notes and coins.
- Accounts at 23 fictional banks, deposit interest and issued debit cards.
- ATMs, tellers, branch kiosks, banker desks and registers with original artwork.
- Cash deposits and withdrawals, account transfers, player shops and monetary villager/wandering-trader commerce.
- Credit cards, fixed unsecured loans, credit reports and Collection Agents.
- Optional automatic payments, transaction history and recovery of interrupted payments and item deliveries.

New kiosks are two blocks wide and two blocks high. Existing narrow kiosks remain usable and offer a free **Upgrade kiosk** action in owner/operator settings when space and support are clear.

New eligible vanilla villages can generate mid-century modern bank branches, with compact and larger designs for all 23 banks across plains, desert, savanna, snowy and taiga styles. Buildings use local wood, working branch kiosks and banker desks. Right-click an active kiosk to see that bank’s account options. Choose **Open primary account** or **Open \<native currency\> account** when offered. Selecting the button submits the account-opening request. A banker is optional. Their private dialogue starts with questions to choose from, and answers appear when you select one.

Choose the world's currency before exploring for new banks. Operators can use `/locate structure #economy:banks` to find bank locations in unexplored areas. The coordinates lead to the source village, with its bank nearby. Existing explored villages are preserved, and difficult terrain can leave a selected branch pending. Superflat village/bank generation has an unresolved limitation tracked in [issue #3](https://github.com/emilybelnavis/economy/issues/3). See the [server guide](docs/SERVER_GUIDE.md#village-branch-diagnostics) for supported generation, status and safe terrain retry.

Active branches staff their assigned desks with persistent bankers. Operators can inspect `/economy branch status <branch-id>` and pause or resume staffing with `/economy branch staffing <branch-id> pause|resume`. A confirmed banker death starts a replacement wait of 24,000 eligible loaded ticks. Saved progress resumes after a normal restart. Accounts, balances and debts survive the loss of a branch, kiosk, desk or banker.

A player-placed banker desk is decorative and unbound. Older vanilla desk furniture remains unchanged; it is not automatically upgraded into a staffed job station.

Securities trading, variable-rate loans and collateral-backed loans are future work. The Stock Trading Computer is currently decorative.

## Get started

1. Install the Economy jar in your NeoForge instance's `mods` folder.
2. Start a world and choose its currency when prompted; `/economy choose CAD` also supports selection.
3. Right-click an active branch kiosk and review its bank and native currency. Select **Open primary account** or **Open \<native currency\> account** when offered to submit the request. You can do this directly without speaking to a banker. Machine owners bind their own terminals to a bank through **sneak + empty-hand right-click** and enable cash services separately.
4. Use an ATM or teller to deposit, withdraw or exchange funds. An active issued card provides remote banking access.

Accounts are limited to four per player, with one per currency. Creative cards are collectibles and do not grant banking access. Financial time continues while the server is offline: one financial day lasts 20 real minutes, and a month lasts 28 financial days.

## Villager trading and economic rates

Right-click a villager or wandering trader to choose an offer, quantity and payment rail. Select a specific account, or select the currency of the cash you want to present. Review the quote and confirm it; foreign trades show conversion costs and fees. Goods, secondary ingredients, stock and inventory capacity are checked again before checkout. Retained outputs appear under **Claims**. Interrupted or uncertain deliveries remain protected for reconciliation rather than being paid or delivered again.

Cash checkout requires exact representable change in the selected currency. If it is unavailable, choose another cash currency, quantity or account explicitly. Unsupported offers or missing pricing inputs remain unavailable.

Villagers share their local market prices while keeping individual sale stock. Their stock replenishes after 10,000 eligible loaded work ticks; wandering traders do not restock. Banker guides remain separate from traders.

After currency selection and source preparation, prime and exchange rates start with a future full observation day, then update every 20 real minutes. The rolling window covers up to seven complete financial days, with no invented pre-activation history. Empty observed windows hold rates. Existing fixed-loan terms remain unchanged. Operators can inspect `/economy macro status` and schedule future pause/manual/resume policies; see the [simulation server guide](docs/SERVER_GUIDE.md#economic-simulation-and-trading).

## Creative Collection Agents

Use `/summon economy:collection_agent ~ ~ ~` to place a Collection Agent, or find the **Collection Agent Spawn Egg** in the Economy and Spawn Eggs creative tabs. Operators can also use `/give @s economy:collection_agent_spawn_egg`.

Placed agents stay where you put them and persist with the world. Right-click within six blocks to review and pay your own eligible collection cases. Each player sees only their own debts. Placing an agent creates no debt and does not change the cooldown of automatic agents.

## Guides

- [Player guide](docs/PLAYER_GUIDE.md): accounts, terminals, cash, shops, credit, loans and automatic payments.
- [Server guide](docs/SERVER_GUIDE.md): configuration, diagnostics, upgrades, backups and restore.
- [Server integration API](docs/INTEGRATION_API.md): versioned banking entry points and downstream contracts.
- [Changelog](CHANGELOG.md): completed features and notable fixes.
- [Roadmap](ROADMAP.md): planned features and future direction.

**Upgrading an existing world?** Stop Minecraft and [prepare a complete-world upgrade backup](docs/SERVER_GUIDE.md#schema-upgrades) before opening it with a newer economy schema. Keep world and economy data together.

## Build and run

With Java 21 selected:

```sh
./gradlew build -x test
./gradlew runClient
./gradlew runServer
```

The jar is written to `build/libs/` using the configured project version. Development client and server data are separate in `run/client` and `run/server`; use disposable development worlds.
