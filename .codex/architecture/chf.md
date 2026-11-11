# CHF currency and cash contract

[A6-03-01 handoff](../A6/A6-03-01.md) · [verification](../A6/verification/A6-03-01.md) · [approved D16 policy](../../prompts/A6-credit-debt/POLICIES-BANKING.md#chf-opening-publication-and-physical-change--d16)

## Registry and authoritative availability

CHF is appended as the eleventh EconomyCurrency, with 100 minor units/major and two decimal digits. Existing currency names/order are preserved. The approved game calibration is 1 CHF= 1.600000000000 CAD, not a historical/live market quote. Money/Price/formatting and overflow checks use the existing exact A2 types; electronic CHF is not restricted to five-cent multiples.

Static item/enum registration declares support; financial availability is the committed FX publication's currency membership. `FxService.availableCurrencies` is a bounded worker read. Account-opening choices filter it, and AccountLifecycle rechecks CHF availability inside settlement (`CURRENCY_UNAVAILABLE` otherwise). Missing preactivation rebase rates reject `REBASE_MISSING_RATE`; no synthetic quote or implicit denomination change occurs. The account cap remains 3 until A6-03.

## FX journal and clock compatibility

FX publication/policy types retain their names and indexed ten-character UTC date prefix. Version1 has the frozen original ten currencies, in their original order; version2 appends CHF. Constructors/decoders accept only those complete memberships. Typed historical decoders reject version/membership mismatch; old payload-only reads infer membership without filling a missing CHF rate. Publication payloads remain `day|reference|updateUUID|policyUUID|rate...`; policies remain `day|policyUUID|reference|baseline,variation,pull,bounds...`.

Fresh FX opening emits v2 policy/publication with all 11 currencies. `EconomicClock.DeferredSystem` adds an optional InitialVersion reader (old two-argument constructor retained). The earliest immutable opening selects the first implementation: existing worlds retain initial1 and historical `fx-daily-v1` signatures; fresh v2 openings use `fx-daily-v2-chf`. UTC daily cadence/origin and historical update IDs remain intact.

Production first catches up legacy v1 work, then idempotently schedules v2 at the next strictly future UTC publication boundary. At that boundary the existing currencies are advanced under their effective policy; CHF is captured from THAT publication's reference-units/CAD×1.6, HALF_EVEN at scale12. CHF starts at that baseline with variation3000ppm, pull100000ppm and band50000ppm. The expanded v2 policy/publication and economic completion commit atomically. Later days use the captured CHF policy. No older publication, policy, account/world denomination or old operation outcome is rewritten. Missing prior publication cannot activate CHF.

Quotes involving absent CHF reject `FX_RATE_UNAVAILABLE`; pair history marks prior CHF days as currencyUnavailable, rather than fabricating rates or counting those days as unexpected missing publications. FX diagnostics show13 cash denominations, ledger precision, availability and scheduled/effective activation date. An opening already containing CHF reports its opening date, not the next daily-update date.

## Exact cash and electronic settlement

The original 118 cash IDs, eight access-only cards and their resources are preserved. Add 13 recipe-free cash IDs:

| Kind | Item suffix / face value | Minor units |
| --- | --- | --- |
| Notes | chf_bill_10 /20 /50 /100 /200 /1000 |1000 /2000 /5000 /10000 /20000 /100000 |
| Coins | chf_coin_5 /10 /20 /50 /100 /200 /500 |5 /10 /20 /50 /100 /200 /500 |

There are 131 cash items and 13 creative tabs, including the additive CHF tab. Models/textures/localization are additive. Editable deterministic64×64 pixel-art sources and original CC0 provenance are in [tools/chf-assets](../../tools/chf-assets/README.md); no official Swiss artwork or old asset was copied into the new designs.

ATM uses notes only; teller uses notes/coins. Owner allowlists may restrict them. Caps become11 currencies/131 denominations; packet choice/line limits32, deposit slots24, component/canonical budgets, lot/custody bounds and planner work/item/normalized-amount budgets remain unchanged. The settings grid uses six columns so the eleventh currency does not overlap All currencies. Unrepresentable1.02CHF rejects; there is no automatic1.00 replacement, five-cent electronic rounding or invented coin.

CHF-aware deposit previews carry canonical field `settlement=CHF_MINOR_V2` in envelope version1 and an explicit sixth stored-preview field. They emit `economy:deposit` fact version2 for every reservation stage. Each source group settles to account minor units iff the source or account is CHF; other groups retain historical whole-major flooring, even in a mixed batch. Thus0.05CHF credits5CHF minor units or8CAD minor units at the captured1.6 rate. Withdrawal previews involving CHF cash/account carry the same explicit canonical marker in their stored map, emit `economy:withdrawal` v2, and debit at minor-unit ceiling. Legacy preview bytes, requests, pending reservations and whole-major rules remain unchanged. V1/v2 validators are strict and reject missing/injected/mismatched markers.

CHF same-currency transfers and holds accept cents. `economy:hold` v2 is CHF-only. CHF-pair FX uses both currencies' minor units and `economy:fx-settlement` v2; ordinary non-CHF exchanges retain old whole-major rules. A full-source closure of a newly fractional legacy-currency balance also uses explicit v2 minor settlement, so CHF conversion cannot strand cents. Rebase likewise drains fractional source balances through v2 postings, while retaining the existing approved whole-major target-payout rounding. User previews reflect these exact settlement rules.

## A4 postings, history and V19 migration

`economy:posting` v1 remains strict legacy whole-major with its original five fields. V2 retains the same `accountUUID|role|currency|signedMinorUnits|authority` fields but permits exact nonzero minor units. Normal CHF legs select v2; composed CHF cash conversions with only legacy-currency account legs use `Settlement.minorRequest` (`precision=minor-v2`), `registrationMinor` or the trusted `prepareMinor` path. Role/authority, per-currency balance, funds/holds and overflow validation are unchanged. Old legacy-only generic requests still reject fractions.

LedgerProjection validates/folds versions1/2; history reads both through the same exclusive account cursor. V19 atomically replaces only the posting-history partial index with `record_version IN (1,2)` and records the normal migration metadata. V10's original index definition remains frozen for old schema/backup validation. No financial table or parallel balance store is added. Existing schemas1–18 require the existing validated stopped-world upgrade backup gate; IDs/balances/world files are preserved. Supported clean backup manifests include19.

## Authority and effects

Cash recognition, authentic/exhausted/foreign-world lot checks, unmarked operator cash trust, quantity conservation, save witnesses, reservation, custody delivery and ambiguity quarantine remain the A5 protocols. Financial facts, balanced postings, quantity changes, reservation state and terminal outcome are one journal transaction; physical inventories remain a separate save/reconciliation boundary. Replays use original IDs/outcomes and never authorize another delivery. No survival mint recipe, bank funding, credit issuance, real-bank integration or client-authoritative rate is added.

This package provides CHF to A6-03 institutions and A6-05-01 branded terminals. Institution catalog, four-account expansion and branded bindings remain unimplemented until their own selected package.
