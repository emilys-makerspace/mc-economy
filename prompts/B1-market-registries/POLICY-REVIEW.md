# Catalog policy and replacement record

Approved catalog direction, recorded 2026-11-13. The user accepted the proposed catalog selection rules with two directions: choose replacement companies from the applicable venue allocation, and base starting prices on **2026-11-13 closing prices**, replacing the proposed 2025-12-31 cutoff. The user explicitly requires cross-listings to be handled by arbitrage. This approves selection policy and delegates the two choices below; it does not approve an unseen 800-row manifest, licenses, seed values, implementation or verification.

## Approved selection policies

| ID | Policy | Owner / limits |
| --- | --- | --- |
| K01 Universe | Exactly 800 listings under the already approved 18 venue quotas, including 95 Euronext. Preserve 98 original identities/display names and provenance, replace the two OTC entries. Choose established/liquid companies with reasonable industry diversity for the remaining slots. | B1-03/04 prepare the exact sourced manifest. The quota includes originals and replacements, not 800 additional rows. |
| K02 Instruments | Ordinary company shares initially; no ETFs, funds, preferred shares, warrants or derivatives in this release catalog. Genuine cross-listings and distinct ordinary share classes may be included and each listing consumes its venue quota. | Distinct classes need separate security IDs; shared issuer/name does not establish fungibility or a conversion right. Historical-original instrument classification conflicts must be surfaced, not silently relabeled/discarded to satisfy the ordinary-share policy. |
| K03 Names | Preserve the 98 existing Minecraft-themed identities/names; new entries use matching fictional names while keeping sourced real company/ticker metadata separately. | New IDs must not repoint historical original entries. Names are labels; IDs and reviewed instruments establish identity. |
| K04 Seed cutoff | Starting prices use the venue's regular-session closing price on 2026-11-13, in that listing's actual quote currency/unit. If no session/close exists that date, use the last available regular-session close on or before it, with actual source date and explicit carry-forward status. Never substitute intraday, after-hours, a later date or fabricated value. | All new-start seed rows, including preserved identities, use this cutoff; recovered 2025-12-31 seeds remain historical provenance and are not silently treated as the new baseline. Record price type, unit, actual session/date/time, source/version and adjustment basis. A source needing split adjustment must reconcile to the cutoff instrument units without introducing later corporate actions. |
| K05 Missing data | Descriptive identity may remain visible, but price-dependent behavior is unavailable until a valid seed is supplied. | Never invent a price or claim historical close validation from a current quote. Runtime remains offline; acquire/review source/license evidence during catalog preparation. |
| K06 Supply | Keep sourced real outstanding-share metadata separate from simulated issued/tradeable supply. | B2/B3 decide capitalization and funded participant allocations; a registry reference does not issue stock or invent a counterparty. |
| K07 Arbitrage | Genuine eligible cross-listings are explicit B5 arbitrage inputs, using B1 relationships, B2 conserved custody, B3 executable depth and Stage A FX through B4 fills. | Net executable prices include fees/spreads, exact ratios, transfer restrictions/delays and session availability. No direct price-equality rewrite, unlimited liquidity or guaranteed profit. BD11 numerical friction, budgets and leg-risk policy remain to settle before activation. |

## Selected OTC replacements

The recovered original 100-row file at Git `61256d4^` has no venue/exchange field; these two instruments were OTC rather than members of an approved OTC venue quota. The user's request is therefore applied to their former NYSE venue as the documented allocation interpretation, without inventing an OTC release venue. Both replacement rows consume two of the existing 100 NYSE slots. The total remains 800; no other venue quota changes.

| Retained historical disposition | New independent identity | Display name | Real company / ordinary common stock | Venue |
| --- | --- | --- | --- | --- |
| `fannie_mine` / Fannie Mine / FNMA → replaced | `blackblock` | BlackBlock | BlackRock, Inc. / BLK | NYSE |
| `freddie_map` / Freddie Map / FMCC → replaced | `charles_shulker` | Charles Shulker | The Charles Schwab Corporation / SCHW | NYSE |

Neither BLK nor SCHW appears in the recovered original 100 ticker set, so these choices do not duplicate the 98 preserved entries. New company/security/listing IDs and source manifests remain separate; the IDs above are selected catalog identity keys, not reuse of old IDs. Their 2026-11-13 closes, complete metadata and redistribution permissions remain to be acquired/validated. No seed price is asserted by this policy record.

## Source evidence for replacement venue choice

- [FHFA's NYSE delisting direction for both companies](https://www.fhfa.gov/news/news-release/fhfa-directs-delisting-of-fannie-mae-and-freddie-mac-stock-from-new-york-stock-exchange) identifies their former NYSE relationship. [Freddie Mac's investor FAQ](https://www.freddiemac.com/investors/faq) distinguishes former NYSE trading from OTC common stock; no claim that FNMA/FMCC currently occupy NYSE listings is made.
- [SEC BlackRock issuer record](https://www.sec.gov/edgar/browse/?CIK=0002012383) identifies BLK on NYSE; the [issuer-specific SEC prospectus](https://www.sec.gov/Archives/edgar/data/2012383/000119312526290821/d158855d424b2.htm) identifies its common stock listing.
- [Charles Schwab's stock page](https://www.aboutschwab.com/stock) identifies NYSE:SCHW.

These sources establish selection/venue facts only. They do not license catalog distribution or verify a 2026-11-13 closing price. Research and historical-original ticker inspection were read-only; no runtime changes, builds/tests/game/assets or price import occurred.

## Cross-phase acceptance requirements

B1-02/04 records same-security, convertible and nonfungible relationships explicitly, with source-backed exact ratios and effective restrictions. B2 holds shared underlying supply once and distinguishes venue availability/in-transit/encumbered claims. B3 retains per-listing prices and common underlying-factor identity; different local closing times/FX do not imply simultaneous executable profit. B4 must enforce applicable cross-venue custody/eligibility rules and refuse cross-venue use while required B5 authority is unavailable; venue-local cash-market functionality can still be accepted separately.

B5-01–04 must cover every catalog cross-listing relationship with an eligible route or precise unsupported/restricted status; genuine opportunities use finite ordinary B4 orders and their original receipts. Example: buy 10 eligible units at venue A, transfer/convert only allowed units, and sell only delivered available units at B. If B closes or only 4 sell, the remaining 6 and actual leg exposure remain recorded. Exact net spread can be negative after FX/fees; that route is not an opportunity. A separate class with no conversion right must never be offered as risk-free interlisting arbitrage. B5 acceptance reconciles cash, securities, transfer claims and both leg outcomes across replay/restart.

## Remaining work and execution boundary

Initial catalog selection policy is settled. Exact 800 row preparation, source/license verification, historical close collection, MIC/zone/tick/unit research and instrument-relationship evidence remain work, not requests to reapprove the selection rules. Demonstrated preservation/classification conflicts need a specific decision. Institutional funding, numeric model rules and BD11 arbitrage friction/exposure settings are later product decisions. No package is selected for implementation; use [B1 queue](EXECUTION.md) and [B5 queue](../B5-international-arbitrage/EXECUTION.md) with [project verification scope](../../.codex/VERIFICATION.md).
