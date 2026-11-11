# Trader entity hooks, stock and progression — A8-03-02

V43 adds explicitly installed individual stock, observed running-tick checkpoints and original-operation progression intents. Native hooks load through economy.mixins.json. **No production trader is claimed automatically**; the saved economy_a8_owned_v1 marker gates ordinary villagers/wandering traders, and A7 bankers remain excluded. Monetary admission, settlement, custody and payment UI remain later packages.

## Native catalog and interaction boundary

NativeCatalog reads the entity's existing MerchantOffers (initializing its native catalog once when needed), never rerolls listing factories for quotes. It uses original ItemCost quantities/predicates and result identity/components/patch, original maxUses and XP. Exactly supported predicates round-trip through registry-aware codecs; transient/unsupported components, extra monetary predicates or unrepresentable catalogs return controlled unavailable reasons. Emerald input becomes a BUY principal, emerald output a SELL payout; secondary non-emerald inputs remain detached. CatalogStore captures the original bytes/revisions; recovery never runs the native generator again.

TradeHooks consumes both entity interaction routes under ownership, cancels offhand duplicates and routes a main-hand action once per player/entity/server tick. The current offered response says monetary trading is not active; there is no payment UI. Native direct/default openTradingScreen and Villager.startTrading are guarded. MerchantMenu selection/tryMoveItems, quickMoveStack and validity cannot admit an owned native checkout. MerchantContainer clears/hides stale output and guards both extraction methods and direct output insertion. MerchantResultSlot removal/onTake/crafting and inherited Slot pickup/getItem are narrowly gated, including creative cloning. Unowned merchants retain native behavior. Restock, shouldRestock, catchUpDemand, updateDemand and resetNumberOfRestocks cancel at method entry before native clocks/demand change.

## Individual stock and running ticks

StockStore.install requires captured catalog plus persistent binding. BUY remaining counts use captured original maxUses; SELL acceptance has no purchase-use quota. Exact offer identities retain remaining counts across catalog revisions. prepareTrade checks binding/catalog/stock revisions and composes the stock debit plus original PENDING progression intent into the owning future money/custody transaction. It moves no money/items itself.

TickSample binds a fresh server-session UUID, observed server tick and at most200 actually observed/eligible ticks. No elapsed wall/game-time gap earns credit; session changes allow a restarted tick counter without offline progress. Village stock refills after10,000 eligible loaded WORK ticks with a loaded valid assigned profession workstation within2 blocks, subtracting one period. Only BUY stocks refill; wandering traders never accrue restock/refill. Checkpoints and relocation preserve partial progress.

TradeRuntime listens only to actual owned entity ticks. A persisted-binding cache has at most256 watched traders. It checks JOB_SITE dimension, loaded chunk/POI profession predicate, WORK activity and distance on the server thread; detached writes go through the existing storage worker. Each market counts a server tick once regardless of assigned loaded trader count. Buffers freeze at200, during checkpoint/barrier pressure or unavailable state. At most4 submissions begin per coordinator tick; each trader has at most16 flush waiters. Unreferenced quiescent market caches retire after relocation. Shutdown queues bounded remaining checkpoints before the existing storage drain.

The flush barrier must complete before future trade/restock-bound quotes. Authoritative new workstation assignment flushes old progress before bounded MarketStore resolution; binding revision changes invalidate quotes without refilling stock. Workstation loss alone retains the captured market. Price recovery uses the persisted market eligible-tick total with the existing original demand anchor; checkpoints never re-anchor deviation. Actual source ownership/admission remains unselected A8-07 work.

## Original progression and save evidence

The stock commit records original operation, trader, captured offer identity/revision, executions and catalog XP. PENDING can dispatch once. StockStore.FreshDispatch is a nonpersistent one-use capability minted only by the first durable dispatch; a recovered DISPATCHED row cannot mint it again.

TradeProgression stages at most16 entity receipt markers, consumes the fresh capability and creates a one-use live Attempt. An independent saved STARTED entity witness is required before execution. Apply marks/consumes the attempt before effects and enters TradeHooks.withProgression for exactly the native notifyTrade path; it does not call result-slot effects or override XP separately. Entity uses, XP/leveling and normal native events retain their original behavior. A saved APPLIED witness supports ACKNOWLEDGED and recovered AMBIGUOUS acknowledgment without granting another execution capability. Acknowledged marker removal requires matching evidence. Restarted STARTED/uncertain attempts remain ambiguous rather than repeating uses/XP/orbs.

The adapter validates a caller-supplied independently read persisted entity NBT witness. It does not itself implement entity-region force/read I/O. The owning custody/saver integration must install that real save path before applying progression, and bind the actor to the original financial intent. expectedEntity() is detached expected state, never independent evidence. Entity save evidence does not establish XP-orb persistence; uncertain physical XP delivery is not blindly retried. There is no exactly-once Minecraft/SQLite claim.

## Persistence and downstream use

V43 adds trade_stock_events/traders/markets/progressions with explicit strict v1 facts, indexed history, source/catalog chronology, original-operation identities, transitions and latest projections. Startup/recovery/offline clean inspection and restore admit V43; old migrations/facts and stopped-world pre-upgrade backup requirements remain. Existing fact/transaction payload budgets reject oversized compositions atomically.

A8-04-01 must compose money, market price, stock and semantic observations in one transaction and resolve original financial outcomes first. A8-04-02 owns physical custody/save integration. A8-07 owns explicit production activation and complete menus. See [handoff](../A8/A8-03-02.md) and [evidence](../A8/verification/A8-03-02.md) for actual checks and limits.


A8-07 delivers [V49 production lifecycle and offered trader checkout](simulation-production.md), including future rate activation, immutable initial configuration/source manifests, operator controls, cached/history API, currentness-gated money work and independent entity-save progression. This supersedes the earlier dormant-production notes for the new delivered scope; historical package evidence remains unchanged. A8-08 acceptance is still unselected.
