# B1 design brief — Market registries

Design revision 4, 2026-11-13. Planning only. Venue quotas, catalog policies K01–K07 and delegated replacement selection are approved as recorded in [the current policy record](POLICY-REVIEW.md); implementation remains unselected. Earlier proposals below are historical where superseded by that record. Read with the [phase scope](README.md) and [shared B-series contract](../planning/B-SERIES-DESIGN.md). No catalog rows, licenses, upstream APIs or behavior have been verified by this brief.

## Intended outcome and authority

B1 supplies a normalized, versioned catalog that can identify a company, security, share class, exchange and listing independently. It supplies descriptive reference data and seed provenance; it does not create holdings, liquidity, executable prices or trading authority.

Preserve the inventoried catalog identities/display names and their source-price provenance, subject to the two approved OTC replacements below; independent entity counts; reviewed expansion toward 800 listings; and replacement of five proposed NOK Oslo rows with EUR Euronext rows. Venue counts are approved below; the individual listing rows remain unreviewed. The historical inventory describes seed prices dated 2025-12-31, USD source conversion to CAD and offline runtime; actual artifacts and their permitted reuse must be located and reviewed before import preparation.

A1 supplies world identity, transactional migrations, journal/outcome durability and bounded storage work. A2 supplies the authoritative currency registry, FX and denomination history. B2 owns issuance/custody; B3 owns seed consumption, sessions and evolving marks; B4 owns order rules and execution. World reporting currency must never redefine a listing's contractual currency.

## Approved venue allocation — 2026-11-13

The user selected the following allocation. Counts refer to listings, with companies and securities counted separately. Euronext totals **95** across the six named venues; the complete allocation totals **800**. These quotas include the original preserved listings where their venue mapping is valid; they are not 800 additional listings.

| Venue | Listings |
| --- | ---: |
| NYSE | 100 |
| NASDAQ | 100 |
| Euronext Paris | 30 |
| Euronext Amsterdam | 20 |
| Euronext Milan | 20 |
| Euronext Brussels | 10 |
| Euronext Dublin | 10 |
| Euronext Lisbon | 5 |
| London | 80 |
| Shanghai | 75 |
| Tokyo | 75 |
| TSX | 50 |
| Shenzhen | 50 |
| Hong Kong | 50 |
| Australia | 50 |
| Copenhagen | 25 |
| New Zealand | 25 |
| Beijing | 25 |
| **Total** | **800** |

This resolves venue quotas and the exact target count. Specific companies, securities, symbols, primary/secondary relationships, source prices and licenses still require row-level review. The six Euronext quotas define the distribution; the earlier five Oslo-to-EUR replacements are included within these counts, not added on top. Exact exchange/MIC mappings for geographic labels remain a catalog-preparation task; no venue identity or currency is inferred solely from a display label.

## Approved OTC replacements — 2026-11-13

The user directed replacement of these two original entries:

| Original ID | In-game name | Source company | Original ticker | Disposition |
| --- | --- | --- | --- | --- |
| `fannie_mine` | Fannie Mine | Fannie Mae | FNMA | Replace with an eligible exchange-listed company |
| `freddie_map` | Freddie Map | Freddie Mac | FMCC | Replace with an eligible exchange-listed company |

No OTC venue or extra slots are added. The release remains **800 listings** within the approved venue quotas. Selected replacements are BlackBlock/BlackRock BLK and Charles Shulker/Charles Schwab SCHW within NYSE, following the documented former-NYSE interpretation because the original records have no venue field. Source prices remain to be validated at the approved 2026-11-13 cutoff; the full row manifest is not yet approved. Use distinct identities for the replacements; do not repoint either original ID or reuse its historical price as another company's seed. Retain both original records in the provenance/coverage ledger as approved replacements, while preserving the other 98 original identities subject to valid venue mapping.

The original 100-row catalog and provenance document were recovered for inspection from Git at `61256d4^`, paths `src/main/resources/data/economy/finance/stocks.json` and `docs/stock-data.md`. This identifies the historical source; it does not validate present-day listings or authorize importing financial state.

## Proposed information model

- Company: immutable ID, reviewed fictional display identity, industry taxonomy reference and provenance; names are mutable labels, never foreign keys.
- Security/share class: immutable ID, issuer, class, quantity unit, outstanding/authorized quantity metadata with effective date and source, and lifecycle status. Custody issuance is a later B2 operation, not a registry side effect.
- Exchange: immutable internal ID, sourced MIC where applicable, display name, IANA time-zone identifier, calendar version and supported currencies/rule references. MIC changes retain aliases and effective history.
- Listing: immutable ID, security, venue, venue symbol, trading currency, tick rule, lot rule, effective lifecycle and primary/secondary relationship. Symbol uniqueness is venue- and effective-period-specific.
- Instrument relationship: explicit fungible, convertible or nonfungible relation with direction, exact ratio, effective interval and restrictions. Sharing an issuer or display name establishes no conversion right.
- Catalog release: revision, reviewed manifest hash, per-row source/license references, import diagnostics and activation outcome. Seeds carry original currency/value/date, source status and captured conversion provenance separately from display values.

Proposed lifecycle is DRAFT → VALIDATED → ACTIVE → RETIRED. A retired ID stays resolvable for history. Updating a catalog creates a prospective revision; historical order/mark records retain the exact release used. Invalid dependencies or missing seed evidence may leave a listing descriptively visible but explicitly unavailable for price-dependent use.

A trusted operator stages a bounded manifest; validation produces errors without partial activation. Activation compares the reviewed hash and prerequisite revisions and atomically writes the catalog release, relationships, journal fact and outcome. Client queries receive bounded immutable public reference snapshots; no client can activate, rename or issue securities. Detached validation must be rechecked against current references at commit.

## Concrete examples and failure rules

Two listings may both show symbol EXAMPLE on different venues, but two active overlapping rows for the same venue/symbol are rejected. One company with two share classes and three listings counts as one company, two securities and three listings, not three companies.

If a listing trades in EUR while world reporting changes CAD → USD, its EUR tick rule, source seed and listing ID remain unchanged. A reporting projection can use captured FX; it cannot rewrite the original EUR fact.

A missing source price produces SEED_UNAVAILABLE with a reason and source identity. It cannot silently become zero, a random seed or the last unrelated listing's value. Import retries with the same canonical operation return the original result; a different manifest under that operation conflicts. A failed activation leaves the previous complete release active.

Quantity and ratio fields use checked exact representations with explicit positive bounds; reject overflow, zero denominators, relationship cycles that imply contradictory ratios and unsupported currencies. Tick validity is a registry rule, distinct from currency minor units. Exact limits and effective-date overlap rules require the B1-01 contract before implementation.

## Feature coverage

| ID | Historical behavior | Proposed owner/replacement | Acceptance and later handoff |
| --- | --- | --- | --- |
| F15 | 100 identities/names with real-company/ticker metadata | B1-03 preservation manifest and identity mapping | Every original identity has a disposition: 98 preserved and two approved replacements; replacement rows have distinct IDs. B2/B3 consume stable IDs. |
| F15 | Historical price import, conversion and offline runtime | B1-03 source/seed manifest and explicit unavailable status | No fabricated seed or live-feed claim; B3 owns publication/administration. |
| F15 | Flat stock catalog | B1-01 normalized entities; B1-04 reviewed allocation | Companies, securities and listings counted separately; exact 800-row review required. |
| F06 | World denomination/rebase affects market labels | B1-01 contractual currency separated from reporting | Rebase preserves raw listing/seed currency; B3/B4 retain historical quote/trade labels. |

The normalized replacement and expanded catalog are design scope, not an approved renaming or permission to discard historical provenance.

## Proposed ordered packages

### B1-01 — Entity and compatibility contract

Requires current A1/A2 contracts and this brief. Registry owner defines IDs, effective revisions, currency/tick/lot types, query bounds and migration mapping. Deliver reviewable entity/validation contracts and a preservation ledger. Exclude catalog expansion, calendar implementation, prices and custody. Gate: every entity/relationship can be identified without symbol/name authority; unresolved legacy mappings are listed.

### B1-02 — Exchange rules and instrument relationships

Requires B1-01 and approved session/calendar decisions. Registry owner specifies calendar versions, DST/holiday source handling, relationship ratios/restrictions and rule availability. Define bounded lookup outputs for B3/B5 and audited diagnostics. Exclude session scheduling, conversion execution and corporate actions. Gate: venue ambiguity, DST boundary, unsupported calendar and conflicting relationship examples have explicit outcomes.

### B1-03 — Preservation and source-import manifest

Requires B1-01, source artifact access and approved provenance/licensing policy. Catalog owner accounts for all 100 original identities, display names and source facts, preserving 98 and recording the two approved replacements; defines staged validation and all-or-nothing release activation. Capture unavailable/failed import states and exact historical seed policy. Exclude online runtime feeds and invented replacements. Gate: every inventory row has a source or named blocker, and retry/restart cannot activate a mixed release.

### B1-04 — Target allocation and registry delivery

Requires B1-02/B1-03 and the approved venue quotas above. Catalog owner prepares the explicit candidate rows within all 18 venue quotas, separate entity counts, currency/venue distribution and five EUR replacements within the Euronext totals. Deliver bounded search/reference services and the B2/B3 manifest. Exclude trading and simulation. Gate: reviewed release count and row provenance, no duplicate active venue symbols, and no unresolved requirement hidden by aggregate totals.

Each package inherits the shared transaction/thread/replay rules. These are proposed package briefs; execution prompts still require explicit selection and resolved policies.

## Decisions and open inputs

| Decision | Recommendation (proposed) | Alternative and consequence |
| --- | --- | --- |
| Individual listing selection | Venue quotas and the 800-listing total are approved; review the row-level manifest before activation | Changing a quota or release total needs explicit approval; padding with invented tickers is unacceptable. |
| Fictional display identities | Preserve the remaining 98 names and expose sourced real metadata separately; select new names for the two approved replacements | Renaming needs explicit per-identity review and alias mapping. |
| Calendar | Version real venue-local session rules with documented holidays | Game-scaled calendars improve availability but change session/arbitrage gameplay and need approval. |
| Relationship precision | Exact integer ratios and explicit unit definitions | Decimal approximations can create conversion dust or loops. |
| Missing seed | Visible descriptive listing, price-dependent behavior unavailable | Excluding the listing changes reviewed release counts and must be disclosed. |

Open inputs: review of recovered catalog/provenance and remaining importer artifacts; replacement company selection; source licenses; exact venue/MIC/time-zone data; outstanding-share policy; holiday horizon and offline updates; individual listing selection within approved quotas; symbol-change and delisting policy. External data validation belongs to a separately selected preparation task, not this first draft.

## Future evidence and exit

Specify meaningful registry/import fixtures for duplicate symbols, all-or-nothing activation, unavailable seeds, rebase separation and exact count reconciliation. A restart scenario is useful only for changed durable release activation behavior. Do not automatically run every category or the full suite. Follow [verification scope](../../.codex/VERIFICATION.md); this prose draft receives diff/link review only. Future runnable deliveries retain runClient/runServer, mod registration and required development runtime dependencies and perform the ordinary checks once.

B1 exits with reviewed IDs/rules/source manifest and explicit blockers. It does not claim 800 approved rows, accepted simulated prices, available brokerage or verified catalog licensing merely because this brief exists.

## Catalog policy approval and cross-listing requirement — 2026-11-13

[Approved K01–K07](POLICY-REVIEW.md) supersede the earlier open selection/naming/seed proposals: starting prices are based on 2026-11-13 regular-session closes, not the recovered 2025 seed date. Ordinary-share catalog selection and Minecraft-themed names are accepted; exact row/source/license and instrument classification review remains. The user explicitly requires B5 arbitrage to handle eligible cross-listings through net executable FX/fee/transfer-aware finite fills, with no duplicated underlying supply. No implementation selected.
