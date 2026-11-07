# Trade markets and detached pricing — A8-03-01

V42 delivers worker-only market/binding/catalog persistence and pure price calculations. No entity hook, tick mutation, stock refill, item movement, monetary posting or menu is activated. A7 bankers remain non-trading guides.

## Identity and bounded membership

Generated markets use BranchPlans.id(world,source), the enclosing envelope of captured original A7 village geometry expanded32 blocks, and a captured source anchor. VillageMarketSource reads actual A7 plans and slot0 choices/fills through Branches.find; a captured plan with no bank uses server currency. CAPTURE_PENDING is unavailable until A7 resolves its choices, rather than being mistaken for a bankless village. Later bank arrival/server denomination changes do not overwrite saved currency.

The explicit source importer journals a frontier/cursor and performs at most one mutation per step: source reset, one market import, a cell page, cursor or completion. It keyset-reads one A7 plan, and source readiness compares an indexed latest A7 plan fact. New source facts invalidate completion; ordinary assignment stays pending until the source import is complete. No automatic startup/import loop is installed by this package.

Markets persist128-block spatial cells, indexing at most16 cells per page. Candidate searches read at most16 single-cell candidates per turn, retain cursor/best distance/ID and never choose from a truncated page. Villages take priority; overlapping villages choose nearest captured anchor then lexical ID. Otherwise dynamic anchors within64 blocks compete, or a new fixed workstation anchor is created. Incomplete dimension indexes return pending. A per-dimension spatial generation restarts pending searches when new markets/indexes change their candidates. Existing bindings remain stable until authoritative workstation reassignment; JOB_SITE loss alone cannot relocate them. Traders first lacking a workstation/market capture their current-position fallback only on an actual trade request. Stock/restock/progression state is not stored or modified here.

Wandering markets use world/entity UUID, saved seed and sorted enabled manifest, SHA-256 and rejection-sampled index. Currency, initial anchor and manifest are saved once; changed seed/manifest/travel/reload do not reroll. Separate wandering entities have separate markets.

## Catalog and source boundaries

TradeCatalog detaches exact item IDs, canonical component objects with explicit EXACT_COMPONENTS or REQUIRED_COMPONENTS semantics, goods/emerald counts, secondary item predicates/counts, original maxUses and XP. Quantity-independent component/predicate hashes identify demand; offer identity also retains quantities/secondary requirements. Unsupported catalog items/predicates require an explicit adapter, never an item-ID-only fallback. The future native extractor must round-trip supported predicates and prove complete comparison cohorts; these internal records do not authorize client-supplied facts.

CatalogStore journals original captures in contiguous revisions and at most32 offers. Original replay retains captured components/quantities rather than rerolling factories. One head plus original offer facts share the journal transaction; ordinary fact/transaction bounds reject oversize compositions atomically.

StandardQuantities v1 has308 base-item families from the exact pinned vanilla constructors across ordinary/experimental/wandering catalogs and helper bodies. The original tools/trade-catalog extractor records source hashes and call counts, including potion ItemCost, biome boats, maps, book fallback and component variants. All pinned goods-count domains are fixed; random values change components/emerald costs rather than standard quantities. No factory invocation, first-loaded-trader sampling, recipe scan or secondary-ingredient imputation is used.

TradePriceSource is the explicit A9 replacement interface. EmeraldV1 defaults to1 CAD/emerald and computes exact market-currency minor principal per standard quantity from captured FX. Basis retains source UUID/revision, currency and publication UUID. CatalogPricing requires a complete bounded equivalent cohort, conservatively rejects unresolved components/secondary ratios, and establishes neutral ask/bid. A one-sided entry receives only a hypothetical comparison. Narrow bids are capped before quoting; existing larger spreads are preserved. It does not invent another native offer.

## Arithmetic and stored price state

Pricing integrates a linear marginal multiplier exactly, with .01 change per standard quantity and [.5,2] saturation. Cumulative actor/market/component/side/epoch carry uses CEILING purchases and FLOOR payouts; same-instant split/bulk orders have equal exact principal/final demand and cumulative minor rounding. A zero incremental payment rejects with a larger-batch explanation.

Reputation collapses to[-.25,.25]; favorable benefit is jointly constrained by all complete equivalent witnesses and the20% gap. Worst-case opposite carry and conservative integer spread checks can require a larger batch. Good reputation cannot worsen a neutral quote. Missing comparison evidence rejects.

Recovery computes deviation from the original trade anchor, halves per24,000 eligible ticks and interpolates exactly within each period. Checkpoints must preserve that anchor. Exact arithmetic has a32,768-bit allocation guard plus the owning fact envelope bound; unrepresentable recovery is unavailable, never approximated/reanchored. Actual once-per-market loaded-tick observation/checkpointing remains A8-03-02.

MarketStore.preparePriceTransition composes one revision-checked price/demand/carry fact with a future settlement commit; it does not submit finance. Each trade anchors at the captured eligible tick. The first transition captures its source-policy epoch, later transitions preserve it and all actor carries. Arbitrary epoch changes/reset are rejected: a future source-policy change requires a separately journaled residual-handling transition. No reset/migration policy is silently invented here.

## Recovery and downstream use

V42 adds trade market/event/spatial/index/search/binding/price/carry tables, immutable catalog snapshots and source-sync facts. Strict codecs and startup/recovery/offline validators check journal correspondence, source chronology, spatial pages/generations, deterministic wandering assignment, current projections, catalog hashes and price/carry histories. Clean restore admits V42 and preserves the stopped-world upgrade-backup gate. Historical facts and migrations remain installed.

A8-03-02 must supply trusted JOB_SITE/loaded-POI/entity evidence, supported native offer/component extraction, complete comparison witnesses, individual stock and eligible ticks; then prove vanilla bypass suppression separately. A8-04 owns financial composition and custody; A8-07 owns production admission/menus. Saved-price proposals alone grant none of these authorities. See [handoff](../A8-03-01.md) and [evidence](../verification/A8-03-01.md).
