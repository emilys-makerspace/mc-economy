# Banking menus — A5-09

Minecraft 1.21.1 / NeoForge 21.1.252 / Java 21. Schema remains 17. BankingCatalog registers the shared menu and protocol; EconomyClient alone registers BankingScreen. No stock trading or credit facility is activated.

## Entry and authority

Physical ATM/teller/register interactions resolve the anchor and require complete current geometry, generation and READY policy admission. Server-issued menu access limits ATM/teller sessions to banking, registers to shop/claims, and cards to remote accounts/history/FX/claims without credit; forged actions reject on the server. Privileged terminal settings open only through `/economy admin config` while an operator looks at a ready terminal. The first-player denomination chooser opens a separate server-issued lease menu, retaining the existing command fallback. Primary accounts use the authoritative world denomination; secondary currency accounts open at a teller. GUI debug identifiers are withheld unless `debugGui=true` is loaded from the server configuration.

Each menu has a server-owned actor, container ID, random session, physical generation, revision and mode. Packets carry no actor, world, financial postings or evidence. Admission checks the current player object, menu instance/session/revision, dimension, reach (six blocks), geometry/generation, card or chooser lease, and readiness. A busy menu blocks edits and new actions; in-flight retries cannot start duplicate callback chains. Responses cannot publish into a replaced menu. Account/history/FX/settings use fresh PartyAuthorization grants and authoritative A2–A5 reads.

Account pages offer ten owned bank accounts, history eight entries, and claims eight collectible cases with server-owned exclusive cursors. Selections are limited to offered IDs/numbers. FX preview freezes its existing quote and source/target revisions; confirmation retains the original exchange operation. Owner/operator settings and Shop price share existing terminal/register handlers and revisions. Screens use original code-authored dark/teal panels with clipped scrolling text. Backend amounts are formatted in currency units, never computed from client values.

## Physical money and goods

Deposit uses 24 actual menu slots. Every input change mirrors exact stack NBT into the same player's persistent data, so a normal player save includes both the changed main inventory and its transient inputs. Reopening after transient menu loss recovers that mirror exactly once; known leftovers are stashed and journaled into owner claims. Close/page change never automatically drops input cash. New staging reserves capacity under a 48-stash budget; accepted originals are retained even if legacy data exceeds it.

Normal deposit sequence: server preview → journal RESERVE → exact removal into DepositInventory originals → observed player save → SAVED → COMMIT → retire payable originals → direct-return BEGIN, actual partial insertion, observed save and COMPLETE. Skipped/remaining originals become existing protected deposit claims. Close before settlement cancels known originals; uncertainty quarantines. Only actually held originals can be returned or claimed.

Withdrawal uses the bounded A5-06 planner with optional bill edits. Full capacity is checked at preview, reservation and current commit admission. COMMIT creates its protected issuance/custody obligation atomically. Register purchase freezes exact components/batch, owner price, primaries and below-container generation/revision/fingerprint. It reserves stock physically before payment, verifies one source-chunk save, journals SAVED, then rechecks current source/capacity at COMMIT.

Delivery always re-reads current custody state. BEGIN_DELIVERY/COLLECT creates an operation-bound attempt; only its current DELIVERING case can materialize items. Capacity loss before insertion creates a known-undelivered claim. Register claims consume the original held source, including seller cancellation returns; they never clone the template. Deposit/menu-original collectors shrink their retained original before inserting the full batch and record replay markers. Marker cleanup requires observed saves and a fresh DELIVERED case; final deposit claim cleanup also frees its empty deposit reservation. Unknown partial effects quarantine. Resolved sale flows release their session reservation; transient input ownership is tracked separately from stock reservations.

## Save evidence and retries

BankingSaves saves one participating player and reads the actual UUID.dat, comparing Inventory and NeoForgeData including fresh world/operation/save markers. For the integrated owner it additionally saves world metadata without saving all chunks and reads level.dat's Data.Player, the source Minecraft actually reloads. Both player sources must agree. Access transformers expose PlayerList/IntegratedPlayerList save and MinecraftServer.storageSource; the cfg is packaged at NeoForge's default META-INF path.

A register source snapshot writes one already-loaded chunk through ChunkSerializer and awaits that chunk's write future. A bounded independent RegionFile read compares the exact source BE (including originals and replay state), excluding only the serializer's keepPacked transport field. It does not force chunks or flush every chunk. Delivered stock evidence retains both the player reference and source region/chunk hash reference. Player/metadata/chunk observations are bounded to 1 MiB; item decoding has an 8192-byte NBT budget. NeoForge save events and SQLite acknowledgment are not witnesses.

Persisted pending intents retain actor/world, original operation/request IDs, kind and canonical fields. Same-menu retry rebuilds fresh detached admission while retaining that intent. New-session lookup is ordered on the existing storage worker and scoped to the original owner/kind/canonical request. Only a terminal result clears a found operation; conflicts remain held. A confirmed absent operation is archived without submitting a replacement. Pure local pre-submission failures do not manufacture unknown operations. Close/after-save cleanup obligations can outlive a menu, but an old disconnected player object cannot overwrite a newer login's file.

File-token versions bank-v1/bank-source-v1 are live-adapter semantic witnesses. The older standalone CustodyDiagnostics full-file v1 observer may report UNSUPPORTED for these tokens; immutable audit records retain their references and digests. Later inventory saves/marker cleanup naturally change a file hash and do not undo a previously observed delivery. Operator resolution must inspect current conclusive semantics, not infer loss from a changed hash.

## Bounds and recovery

Action text is at most 2048 UTF-8 bytes, action/page names are fixed/bounded, snapshots contain at most 32 lines and a 12,000-byte text budget, and accepted actions are rate-limited to one per two ticks. Existing worker quotas, 16 KiB canonical intents, 24 deposit slots, planner work bounds, 16 outstanding physical batches/attempts and stock bounds remain enforced. Only detached immutable observations cross to workers.

Startup keeps existing quarantine rules for restored cash/custody. Confirmed original outcomes do not authorize replay of physical delivery. Restored or uncertain originals may retain staging capacity until evidence resolution; there is no automatic compensation or speculative item creation. Production has no online world-backup feature. Verified offline backup/restore and deliberately skewed disposable fixture snapshots are distinct. Hardware power loss, arbitrary third-party inventory mutation and proof-based operator compensation through a new GUI are not claimed.

[Acceptance evidence](../A5/verification/A5-09.md).

## CHF integration

Account choices now filter published currency availability; settings place eleven currency toggles in six columns to avoid overlap. CHF-pair exchange inputs accept exact cents and previews match v2 settlement; legacy pairs retain whole-unit input. CHF deposit zero-credit messages use minor thresholds. New resources/catalog entries are additive and all existing session/menu/server authority and save/custody constraints remain. See [the current CHF contract](chf.md) and [verification](../A6/verification/A6-03-01.md).


## A6-03 current contract (2026-11-05)

[Institution/provider contracts](institutions.md) supersede earlier lifecycle/cap references: schema V20, four total open BANK accounts including primary, version3 institution postings, explicit legacy provider migration, and sealed branch-only lifecycle admission. Live opening/closing/provider changes are unavailable until A6-05-01. Existing account services and original outcome lookup remain.


## A6-04 current contract (2026-11-05)

[Wealth, pricing and funded yield](wealth-pricing.md) defines schema V21, persisted required-source contracts, fresh qualification/fee witnesses, assigned-bank FX pricing and time-weighted bank-funded deposit yield. Production installs bounded accrual/payout providers at stage 1 and daily Tier reevaluation at stage 7; empty domains remain dormant. Source snapshots and unpaid receivables remain distinct from spendable funds. Live branch adapters, issued cards, lending and trading are still separate packages. Earlier no-product/default-Tier2-denial descriptions apply to their historical delivery checkpoints.

## A6-05-01 current branch and cash admission

[Branch hardware/session contract](branch-hardware.md) supersedes older teller-opening and automatic cash defaults. Protocol3 adds KIOSK access and owner brand selection. All fresh cash/terminal FX requires authoritative binding; withdrawal cash currency comes from bank metadata. Exact-bank kiosk proofs gate lifecycle. Generic card/teller lifecycle bypasses remain closed. Replacement/applications are unavailable.

## A6-06 issued-card menus

[Issued debit cards](issued-cards.md) add protocol4 owner card views/lost reporting and issuer-kiosk issuance/replacement/funding/combined-fee review. Kiosks collect protected card claims. Generic cards retain independent access; new issued items need worker validation on entry/actions and final FX mutation. Credit/loan applications remain unavailable.

## A6-07 profile/history views

[Credit profile contract](credit-profiles.md) adds protocol5 read-only owner views and exclusive history pages. Reads, login and debit issuance create no credit profile. Credit/loan applications remain unavailable; there is no client score initializer/recalculator. Separate operator APIs use audited inspection authority.

## A6-08-01 extension

A6-08-01 exposes funded credit origination/increase/draw/payment APIs through owner grants and sealed branch/physical-card admission. Existing read access recognizes both debit and credit token appearances. No product mutation packet or integrated application/payment screen is added; see [credit facilities](credit-facilities.md).

## A6-08-02 diagnostics

Protocol 6 adds read-only owner credit facility/details/frozen-statement navigation on ATM/teller/kiosk/card surfaces, with offered IDs and bounded per-menu cursors. No application, draw, payment or editing packet is installed. See [credit diagnostics](credit-diagnostics.md).

## A6-12 menus and integration

Protocol 7 adds issuer-kiosk applications and replacement, remote payments and owner wealth/debt reports. Held-credit register purchases compose charge and protected goods atomically. Approved advances settle into an owned account before separate cash withdrawal. The [current integration contract](banking-integration.md) supersedes earlier application-screen availability notes and records exact request, recovery and API boundaries.


## D15 automatic-payment extension

Banking protocol8 adds the finite `auto_*` configuration actions and `finance_autopay` page. The Bank and credit hub exposes reviewed named-source consent on owner-authorized banking surfaces. Public owner configuration uses `LedgerService.autopay` and `configureAutopay`. Integration API1 remains unchanged. Existing accounting and original-outcome lookup are reused. See [automatic-payment contracts](autopay.md) and [final acceptance](../A6/verification/A6-13.md).
