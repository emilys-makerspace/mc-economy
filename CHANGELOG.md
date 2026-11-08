# Changelog

Completed features, gameplay changes and notable fixes, grouped by date and phase/task. Newest dates appear first. Internal housekeeping, version-only changes and verification logs are omitted.

## 2026-11-08

### A9-06 — Opt-in reference-price trading

- Fixed valuation refresh failing with 18–63 activated market/item scopes.
- Added operator coverage review and explicit prospective trader activation, keeping existing market/currency assignments and original receipts.
- Added exact native item valuation linkage, published unit/total review, and rounded-gap admission without new fractional carry or an emerald-price multiplier.
- Unsupported listings are omitted in activated scopes. Native required-component buybacks remain unsupported; work limits and component rules can refuse other listings.
- Added schema52 activation history and reviewed NeoForge ingredient expression capture; retained earlier source/pricing implementations.

### A9-05 — Prospective trade-source transition foundation

- Added durable reference-source epochs and account/cash quote formats that preserve original receipts across source changes.
- Added explicit retirement records for legacy fractional carry and prospective unit-rounded pricing, with no carry payout or historical balance/price rewrite.
- Added schema51 recovery validation and atomic valuation updates for already-enabled scopes. Production reference-price activation remains pending.

### A9-04 — Public item-reference API and provider data

- Added authenticated current/retained reference reads with currency, snapshot and bounded provenance identities.
- Added explicit mod-provider bases, exact selectors and supported recipe/material data, with atomic conflict rejection and retained values after removal.
- Added bounded operator valuation diagnostics and generic integration documentation. Financial qualification and trader source activation remain separate.

### A9-03-03 — Component and container reference costs

- Added compatible enchantment premiums, curse and durability discounts, stored books and name-neutral exact identities, with configurable beneficial premiums.
- Added reviewed vanilla variants and bounded container shell/content totals, including typed bucket/bee contents and potion bottles counted once.
- Retained original snapshot prices across reloads. Unsupported component state remains unavailable; public integration and trader activation remain pending.

### A9-03-02 — Potion reference brewing costs

- Added captured vanilla brewing paths for potion effects, strength, duration and forms, with standard shared ingredient/fuel allocation and one potion bottle.
- Retained exact potion snapshots and original values across material reloads. Custom brewing requires an adapter; generic variants, public integration and trader activation remain pending.

### A9-03-01 — Ordinary reference recipe costs

- Added exact ordinary crafting, stonecutting and cooking derivation with bounded acyclic search, deterministic provenance and retained recipe snapshots across reloads.
- Added canonical oak-based sticks and the approved raw/refined-food calibration, preserving explicit overrides and historical base-only values. Brewing, component valuation and trader activation remain pending.

### A9 — Item reference base snapshots

- Added validated optional base/override configuration with exact currency arithmetic and retained immutable snapshots across reloads and restarts. Recipe/component valuation and trader price replacement remain pending.
- Storage advances to schema 50; existing worlds require the stopped complete-world upgrade backup. Invalid valuation reloads preserve the prior durable snapshot.

## 2026-11-07

### A8 — Monetary trading and economic rates

- Added explicit villager/wandering-trader checkout with cash or a selected account, quantity selection, foreign conversion confirmation, protected delivery/claims and original secondary ingredients.
- Enabled local market pricing, individual loaded-work restock and single original-receipt native trade progression while blocking native checkout bypasses. Banker guides remain separate.
- Added prospective activity-driven currency prime and coherent FX every20 real minutes after full-day warm-up, retaining old FX history and fixed-loan terms.
- Added validated initial simulation settings, authenticated future pause/manual/configuration/resume controls, cached diagnostics and bounded immutable history API. Storage advances to schema49 with the existing complete-world upgrade-backup requirement.

- Fixed generated kiosks spanning chunk boundaries being permanently disabled when their neighboring chunk was unloaded.

## 2026-11-06

### A7 — Village banking foundation

- Added `/locate poi economy:bank` and `/locate poi #economy:banks` for nearby branded kiosk anchors, with existing-kiosk indexing on chunk load.
- Added the native `economy:bank` structure and `#economy:banks` structure tag for normal `/locate structure` searches, including unexplored areas, without changing village or bank placement rules.

**A7-06 — Integrated village banking guidance**

- Clarified native bank discovery, direct kiosk account opening and optional banker questions, with operator instructions for branch status, terrain retry and staffing pause/resume.
- Documented village size and staffing limits, account survival after branch or staff loss, and the deferred superflat generation limitation tracked in [issue #3](https://github.com/emilybelnavis/economy/issues/3).

**A7-05 — Banker desks, staffing and dialogue**

- Turned generated branch consoles and banker desks around 180 degrees within their existing bays.
- Refined console and desk models with detailed wood, metal, displays and controls. Bankers now have detailed 64×64 skins, textured suit details, separate eyebrows, inward-facing pupils and a compact nose.

- Added original two-wide, two-high banker desks with local wood, monitors and ledgers. New branch templates use real job stations; older vanilla furniture remains unchanged and player-placed desks stay unbound.
- Added persistent branch bankers and private dialogue that routes available banking services through the branch’s trusted kiosks and existing player permissions.
- Confirmed deaths start a 24,000 eligible loaded-tick replacement wait. Unloading, missing observations and uncertain interrupted spawns never authorize duplicate employees. Desk removal stops staffing, and operator pause/resume retains earned replacement time.
- Added durable staffing identities, death/removal history and bounded progress checkpoints. Storage advances to schema 39 with the normal stopped-world complete-world upgrade backup.

**A7-04 — Village bank buildings and generation**

- Added 230 original GenMC mid-century modern templates: compact and larger branches for all 23 banks, with five village styles, local wood, signs, furnishings and real wide kiosks.
- New eligible vanilla villages capture bank choices and generate connected branches where terrain permits. Abandoned villages and existing explored villages are preserved; exhausted terrain searches retain explicit pending plans.
- Added loaded-only operator terrain retry, durable placement provenance and conservative interruption handling. Later construction and removed branches are never automatically rebuilt. Storage advances to schema 38 with the normal stopped-world upgrade backup.

**A7-03 — Wider kiosks and legacy upgrades**

- Added original broad-screen kiosk consoles with institution/currency headers, counters, recessed bases and side panels across all 23 bank appearances and the neutral design.
- New placements are two blocks wide and two blocks high. Existing narrow kiosks keep their footprint and can be upgraded explicitly for free in owner/operator settings, preserving identity and bank bindings.
- Added recoverable geometry upgrades, stale-session invalidation, loaded space/support checks and fail-closed interrupted placement. Storage is now schema 37 with the normal stopped-world upgrade-backup requirement.

**A7-02 — Persistent branches and trusted activation**

- Added durable village/branch plans, service-anchor identities, removal history and bounded operator inspection, pause/resume and required-provider retry controls.
- Added scoped, recoverable activation for generated kiosks without creating customer accounts or institutional capital. Village bank generation, wider kiosks and bankers are not installed yet.
- Advanced storage to schema 36; existing worlds require the normal stopped-world upgrade backup.

### A6 — Banking, credit and debt

**Creative Collection Agents**

- Added `/summon economy:collection_agent` and a Collection Agent Spawn Egg in the Economy and Spawn Eggs creative tabs.
- Placed agents persist with the world and offer each visitor their own private collection payment dialogue without changing automatic debtor encounters or cooldowns.

**Card collectibles and debit artwork**

- Removed the eight old generic bank and credit cards. The Bank & Credit Cards tab now includes every current debit and credit design, with bank, type and applicable tier tooltips.
- Removed debit-card tier names and badges; each bank now has one collectible debit design. Account tiers and existing issued cards remain compatible. Banking access still requires an active, owned issued token.

**A6-13 — Automatic payments and integrated banking fixes**

- Added default-off automatic payments with reviewed consent for each facility and a selected owned bank account in the same currency.
- Added credit-card minimum, remaining statement balance and current full-payoff targets, plus loan installment payments.
- Automatic payments are all-or-nothing, retry twice daily and recover after offline time before delinquency and reporting at the same instant.
- Fixed debit issuance, lost-card reporting and branch closure failing during owner authentication.
- Corrected finance amount labels and issued-credit-card names, and made longer terms scrollable.

**A6-12 — Bank and credit menus**

- Added issuer-kiosk credit applications, limit increases, credit-token replacement and fixed bank/subprime loan applications.
- Added remote credit, loan and collection repayment, loan payoff and schedules, credit reports and net-worth explanations.
- Added held-credit-card register purchases with atomic charging and protected goods delivery, plus reviewed advances to an account before separate cash withdrawal.
- Published the version-one server banking integration API, with recovery of original operations across finance menus.

### A5 — Cash and terminals

**Cash selection and ATM appearance**

- Added bill-count controls with exact-total validation and an option to restore the default minimum-bill mix.
- Added a 1-in-20 chance of vertical artwork for each eligible withdrawn note, fixed when the batch is reviewed.
- Recessed ATM displays and bezels to keep keypads visible across plain, coloured and bank-branded models, including inventory appearances.

## 2026-11-05

### A6 — Banking, credit and debt

**A6-11 — Collection Agent**

- Added an original nonhostile Collection Agent that spawns in loaded chunks and follows only its debtor.
- Added private case and funding selection, reviewed partial/full payments, explicit FX fees and recovery of original payment receipts.
- Added persistent encounter tracking and seven-financial-day cooldowns after an agent dies.

**A6-10 — Debt lifecycle, reporting and diagnostics**

- Added late reporting, cure revisions, default boundaries and independent collection/report expiry clocks for credit cards and loans.
- Added funded debt sales, expiry write-offs and protected collection repayments in native currency or with explicit FX.
- Activated resumable 28-day credit scoring using daily utilization and captured historical exchange rates.
- Added private collection-clock, report-revision and accounting-history commands, plus audited operator explanations.
- Added historical obligation/report reconstruction. Legacy histories that cannot be safely reconstructed require reconciliation before affected financial services become ready.

**A6-09 — Fixed loans and diagnostics**

- Added fixed unsecured bank/subprime loans with captured rates, monthly amortization schedules and finite lender funding.
- Added protected partial repayment and early payoff, explicit currency conversion and separate fees without requiring another bank account.
- Included loan debt and accrued interest in net worth and shared exposure checks.
- Added owner views for contracts, schedules, installments, application decisions and payoff previews, plus audited operator diagnostics.
- Fixed full settlement leaving inconsistent borrower or lender claims.

**A6-08 — Credit facilities and diagnostics**

- Added funded credit facilities, separate network credit tokens, once-only starter issuance, underwritten increases, closure and replacement APIs.
- Added purchase/advance interest, fixed 28-day statements and minimums, balanced draws and same-currency or explicit-FX repayments.
- Included credit liabilities in net worth. New borrowing requires the accepted default/reporting lifecycle safeguards.
- Added private facility, APR, minimum, due-date and statement views, plus audited operator servicing and payment diagnostics. Older records identify unavailable detail.
- Fixed credit API owner authentication.

**A6-07 — Credit profiles**

- Added deterministic 300–900 scores, underwriting ceilings/APRs, time-weighted utilization and correction-aware inputs.
- Added persistent score history, cross-issuer inquiry grouping, owner profile/history views and audited operator reads.
- Debit cards and failed applications do not create credit profiles.

**A6-06 — Issued debit cards**

- Added bank debit-card issuance and replacement at issuer kiosks, with server-owned tokens, expiry and lost-card reporting.
- Added free first issuance and a free renewal window, plus a reviewed 25-world-unit replacement fee and any FX cost.
- Replacement revokes the old token atomically. Copies share token status; provider changes and account closure revoke old tokens.

**A6-05 / A6-05-01 — Banking artwork and branch hardware**

- Added original marks for 23 banks and four networks, debit/credit artwork, menu icons, bank-branded ATM/teller resources, branch kiosks and Collection Agent artwork, with editable sources and CC0 provenance.
- Added bank selection across 23 ATM/teller/kiosk designs and a craftable two-cell branch kiosk. ATMs dispense bills; tellers also support coins.
- Added in-person account opening, protected closure, legacy provider assignment and provider switching through both branches.
- Preserved existing machine settings and custody during migration; existing machines require owner-confirmed bank binding. Retired ATM dye recipes while retaining existing coloured block IDs.

**A6-04 — Wealth qualification, pricing and deposit yield**

- Added net-worth qualification at 100 million world-currency units, including unpaid liabilities and earned receivables.
- Added bank-owned Tier 1 FX fees, zero Tier 2 FX fees and explicit cash-exchange previews.
- Added time-weighted deposit yield funded by bank capital, deferred payouts during distress and retained claims across provider/currency changes. Account closure settles earned amounts.
- Added future-day prime-rate configuration and integration APIs for securities and trading fees.

**A6-03 — Institution accounts**

- Added a 23-bank catalog, persistent account providers and a one-time 28-billion-world-unit capital allocation across banks and two service institutions.
- Raised the open bank-account limit to four, retaining primary-account protection, currency uniqueness, balances and history.

**A6-03-01 — CHF currency and cash**

- Added CHF accounts, FX support, thirteen original notes and coins, and a CHF creative tab.
- Existing worlds activate CHF FX prospectively at the game's initial calibration of 1 CHF = 1.60 CAD, preserving earlier rates and world currency.
- Added exact CHF-cent settlement and support for fractional balances from CHF conversion.

**A6-02-02 — Financial calendar diagnostics**

- Added operator calendar diagnostics for UTC timing, elapsed periods, upcoming boundaries and work progress, with bounded reports during catch-up or storage pressure.

## 2026-11-04

### A5 — Cash, terminals and banking menus

**Banking interface fixes**

- Replaced manual account and collection numbers with selectable accounts and item batches. My accounts opens transaction history directly.
- Added a banking home page and account-management screen with balances, available currencies and an Open a new account button; separated history headings from the scrolling list.
- Restored the compact banking frame and Deposit/Withdrawal wording, restricted player inventory display to deposits, tied withdrawal currency to its account and added From/To exchange selectors.
- Added reviewed teller account closure with balance transfer to the primary account.
- Added specific deposit rejection messages and shorter completion messages that mention returned or collectible bills only when relevant.
- Made teller cash services always available, enabled all cash services on new ATMs and added owner configuration by empty-hand right-click.

**A5-09 — Banking menus**

- Added world-currency setup, accounts/history, FX review, terminal settings, cash deposit/withdrawal, register purchase and claim screens.
- Added remote banking through bank cards. Server-validated sessions reject stale or remote cash actions.
- Protected cash and goods claims survive menu changes and interrupted delivery, with player/world save checks before delivery is considered complete.

**A5-08 — Physical terminals**

- Added 17 ATM finishes, 17 register finishes and a teller, with original art, recipes, recolouring and a creative tab.
- Wide machines occupy four cells, face the player and drop once in Survival when any cell breaks. Registers fit above stock containers.
- Bound each placed terminal to its owner's policy and preserved removal records during queue pressure.

**A5-07 — Register sales**

- Added owner-priced exact-batch checkout with stock reservations, matching primary-account currencies, atomic payment receipts and protected delivery claims.
- Added stock/capacity checks, persistent duplicate-sale protection and an owner-authorized shop price command.

## 2026-11-03

### A5 — Cash and payments

**A5-06 — Withdrawals**

- Added exact ATM bill and teller bill/coin planning, editable bill counts and account debits rounded up to the settlement unit.
- Added full-batch inventory-capacity checks before debit, with protected delivery and recovery after interruption.

**A5-05 — Deposits**

- Added 24-slot deposits with reviewed account, terminal and FX terms, source-currency rounding and authentic cash reservations.
- Added exact-original cancellation, skipped-item returns and recoverable claims when inventory space is limited.

**A5-04 — Terminal policy**

- Added owner/operator controls for deposit and withdrawal modes, accepted currencies and denominations.
- Persistent placement identities reject stale settings after a machine is replaced.

**A5-03 — Cash and cards**

- Restored 118 cash collectibles, eight banking-card collectibles, twelve creative tabs and both Canadian bill artworks.
- Added world-bound cash issuance and quantity validation for trusted integrations; copied markers share the original lot's remaining quantity.

**A5-02 — Custody foundation**

- Added trusted custody APIs, persistent owner claims, capacity checks before settlement/collection and evidence-based operator resolution.
- Restored custody cases enter review before new custody operations are admitted.

### A4 — Ledger and transactions

- Added balanced settlement, persistent holds, quoted FX, funded account closure and atomic world-currency rebasing.
- Added payment, retry, balance, history and operator reconciliation commands. History retains original amounts and links entries by transaction ID.
- Added a shared ledger API for trusted server integrations. Existing worlds require a complete-world backup before upgrading.

### A2 — World currency

**Currency setup**

- Simplified initial selection to `/economy choose CAD`, with tab completion, lowercase support and no UUID to copy.

### A1 — Foundation

**A1-05 — Scheduling and diagnostics**

- Added restart-only worker, queue and storage settings, with bounded UTC catch-up that pauses affected writes while behind.
- Added public `/economy status` and operator `/economy diagnostics` for storage health, recovery progress and sanitized failures.

**A1-04 — Recovery, backups and financial time**

- Added startup validation, resumable state rebuilds and persistent financial time independent of sleep and `/time` changes.
- Added offline complete-world backup and separate-destination restore commands with integrity and compatibility checks.

**A1-02 / A1-03 — Persistent storage and journal**

- Added world-local SQLite storage, recoverable world identities and atomic schema upgrades that require a stopped-world backup.
- Added an immutable journal and persistent operation results so authorized retries recover the original outcome without duplicating effects.
- Rejects incompatible, corrupt or wrong-world stores without replacing them.

**A1-01 — Initial foundation**

- Established the Economy mod for Minecraft 1.21.1, NeoForge 21.1.252 and Java 21, with client/server development runs and a server integration API.
- Added isolated per-server services, bounded background work, readiness checks and orderly shutdown.
