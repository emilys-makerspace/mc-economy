# Account NPC settlement — V44

A8-04-01 delivers a worker-confined service, with production interaction inactive. It consumes [market/pricing](trade-markets.md), [stock/progression](trader-entities.md), existing bank/service-pricing/FX and [activity attribution](activity-attribution.md). The authenticated server adapter and physical saver are later owners; a packet cannot supply trusted Admission.

## API and quote boundary

`NpcTrades(store, npcAccountFx, clock, admission)` exposes `quote(Terms)`, `intent(world, original, actor)` and `settle(intent)`. Terms bind original operation, actor/session/trader, catalog/offer/executions, selected account, pricing epoch, EmeraldV1 source/publication, reputation/revision/witness, comparisons, exact input custody/stacks and output capacity/custody reservations. Required-component offer predicates remain distinct from actual exact reserved stacks. Secondary ingredients retain their original semantics and require input custody even on a BUY.

A quote captures account currency/revision and issuer binding, market/binding/catalog/stock, demand/carry and eligible-tick revisions, detached exact pricing, optional FX/service fee quotes, timestamps and canonical financial intent. Lifetime is at most30 seconds and is bounded by FX expiry. Existing daily FX publication retains its48-hour freshness policy until A8-06 replaces the reader. Missing account/provider/currency/quote authority rejects explicitly. Only personal OPEN BANK accounts with an installed enabled issuer are admitted; balances respect holds and no implicit credit is created.

Admission is a mandatory trusted callback at book and settle. It proves live loaded entity/session, distance<=6, exact current offer/source/reputation/equivalence, actual reserved stacks satisfying predicates, independently saved input custody, output capacity and flushed trader tick buffers. Internal SQL witnesses revalidate account/provider, stock/market/ticks, price/carry and FX. Current Admission is an API contract, not installed gameplay admission. Original JournalEngine outcome lookup happens before any new expiry or admission decision.

Foreign quotes are additionally bound to the live `NpcAccountFx`/`FxQuotes`/`ServicePricing` instance. An uncommitted foreign quote cannot be resumed from just its serialized capture after process restart; a fresh original quote is required. Completed originals replay from journal history without live quote authority. Expired book operations do not silently reprice themselves.

## Balanced posting examples

BUY and SELL mean the player's purchase and sale respectively. Values below are major units for readability; actual records contain integer minor units. A is the selected account, B the canonical world/currency NPC bridge, I issuance and C FX clearing. Each row is a separately balanced typed group; all rows share one journal transaction. B starts and ends at zero, with no negative intermediate balance.

| Case | Group | Postings |
| --- | --- | --- |
| BUY CAD10 | TRANSFER | A_CAD -10; B_CAD +10 |
| | REDEEM | B_CAD -10; I_CAD +10 |
| SELL CAD10 | ISSUE | I_CAD -10; B_CAD +10 |
| | TRANSFER | B_CAD -10; A_CAD +10 |
| Foreign BUY CAD10, USD1=CAD1.25 | Fee TRANSFER | A_USD -0.04; supplier CASH_USD +0.04 |
| | Fee recognition TRANSFER | player EXPENSE_USD +0.04; supplier INCOME_USD -0.04 |
| | FX | A_USD -8; C_USD +8; C_CAD -10; B_CAD +10 |
| | REDEEM | B_CAD -10; I_CAD +10 |
| Foreign SELL CAD10, USD1=CAD1.25 | ISSUE | I_CAD -10; B_CAD +10 |
| | Fee TRANSFER | B_CAD -0.05; supplier CASH_CAD +0.05 |
| | Fee recognition TRANSFER | player EXPENSE_CAD +0.05; supplier INCOME_CAD -0.05 |
| | FX | B_CAD -9.95; C_CAD +9.95; C_USD -7.96; A_USD +7.96 |

The account issuer is the supplier even when SELL fees are denominated in trader currency. Tier1 fee is0.50%; eligible Tier2 is zero under existing ServicePricing freshness/eligibility rules. BUY quotes exact trader-currency credit and charges the fee on source principal. SELL charges the fee on gross issued goods value and converts gross-minus-fee. NPC goods payouts never debit finite bank capital. Fee CASH increases are ordinary fee income, separate from goods money creation/retirement.

## Atomic effects and recovery

`NpcSettlement` admits only bounded typed ISSUE/REDEEM/TRANSFER/FX groups; it checks canonical roles, authorization, balanced currencies, holds and an overlay of intermediate balances, then writes final balances once. NPC_BRIDGE is a new private ledger role outside customer/institution catalogs. Old posting versions1–3 and generic PostingBatch/Settlement paths explicitly reject it. Version4 shares the existing posting payload layout and is accepted by ledger replay/history, wealth and diagnostic readers. Fee recognition uses existing financial accounts; no FEE or CAPITAL_ISSUE authority is added to this route.

Input custody must already be WITNESSED with exact owner/items/source/destination and original canonical financial intent. Its COMMIT is composed with the actual financial commit. BUY output is a committed delivery intent; this service does not move Minecraft items. Market price/carry/demand, individual BUY stock and PENDING progression, service fee capture, consumed FX quote, adapter installation and NPC receipt share the transaction. SELL has no native purchase quota. Physical progression still requires the existing fresh-dispatch and independent save proofs.

The explicit `economy:a8/npc-adapter` v1 source installs `npc-account-v1:goods,creation,retirement,customer-fx;cash=absent` at the first successful receipt. It preserves the frozen earlier activity manifest and all historical sources. Each receipt produces GOODS_SALE and trader-currency NPC_RETIRE or NPC_CREATE, plus actual customer FX_SELL/FX_BUY principal when foreign. Fees/bridges create no duplicate monetary-flow or FX observations. Generic FX facts cannot accompany an NPC receipt. Production PricingAdmission recognizes this settlement kind for existing Tier2 new-credit eligibility; activity capacity sees posting4.

V44 appends `npc_bridges`, `npc_trade_adapter`, `npc_trade_quotes` and `npc_trade_receipts`, immutable triggers/fact indexes and the v4 ledger-history index. Original migrations remain unchanged. Online open/recovery and offline inspection validate canonical source/projection counts, world/identity/chronology, captured historical price/stock/tick witnesses, custody linkage, same-sequence exact quote-to-posting-group correspondence, FX consumption, fee facts and terminal bridge zero. LedgerProjection rebuild replays v4. Clean backup/restore recognizes44; old worlds retain the stopped complete-world upgrade-backup requirement.

This package supplies no physical cash rail, active menu, client packet, implicit account fallback or comprehensive foreign-confirmation UI. A8-04-02 owns physical custody; A8-07 owns production admission/menus and confirmation; A8-06 owns exact-effective-time FX replacement. No A9 valuation is installed.
