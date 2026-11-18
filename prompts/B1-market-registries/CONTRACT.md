# B1 — Market registries — package contract

Draft revision1, 2026-11-13. Planning only; no package is selected, implemented or verified. Read the [original scope](README.md), [design brief](DESIGN.md), [shared B contract](../planning/B-SHARED.md) and [decision register](../planning/B-DECISIONS.md). The queue below refines the brief's proposed package boundaries; approvals explicitly recorded in the brief remain intact.

## Ownership and domain contract

B1 owns independent company, security/share-class, exchange and listing identities, instrument relationships, effective rule revisions and source/catalog manifests. It creates no custody inventory, execution or simulated prices. A player corporation from B0 is a distinct organization party; no automatic public listing or issuance follows registration. B1 can be delivered independently of B0 until an explicitly approved issuer-link feature is selected.

The [existing design brief](DESIGN.md) records approved quotas: exactly 800 listings, including 95 Euronext rows across six venues, within its 18 venue quotas. It also records preservation of 98 originals and approved replacement of `fannie_mine` and `freddie_map`, retaining their historical disposition without reusing those IDs. The original artifacts are identified at Git `61256d4^`; source inspection is not license/current-listing verification. The user approved [K01–K07 selection policies](POLICY-REVIEW.md), delegated the two replacement choices and selected the 2026-11-13 closing-price cutoff. BlackBlock/BlackRock BLK and Charles Shulker/Charles Schwab SCHW consume two NYSE slots under the documented former-venue interpretation. Exact 800 rows, historical closes, source/license verification, MIC/time-zone mappings and instrument-unit/relationship evidence remain preparation work; demonstrated preservation/classification conflicts need a specific decision. Do not invent tickers, prices or names to complete a count.

Proposed lifecycle: staged DRAFT → VALIDATED → ACTIVE → RETIRED; retired IDs remain resolvable. A reviewed manifest hash and prerequisite revisions define release identity. Large release validation/preparation is bounded and resumable; publish a complete active generation atomically rather than write 800 rows into one oversized journal fact. A failed/stale activation preserves the prior active generation. Original listing/seed currency and source dates survive world denomination changes.

## Coverage ledger

All rows are draft contracts, implementation not started, verification not run. Historical behavior comes from the [feature inventory](../planning/01-current-feature-inventory.md); current accepted Stage A contracts supersede old implementation assumptions.

| Feature | Historical behavior | Package owner / replacement | Acceptance and downstream handoff |
| --- | --- | --- | --- |
| F15 identities and names | Flat 100-record catalog | B1-01 identity contract; B1-03 preservation ledger | 98 preserve IDs; two approved replacements get new IDs; B2/B3 consume stable identities. |
| F15 importer/provenance | Historical source prices, conversion and offline runtime | B1-03 staged source/seed manifest | Missing seed is unavailable, not zero; B3 consumes captured provenance. |
| F15 expansion | Flat catalog | B1-04 exact 800-row reviewed release | Per-venue quotas and separate entity counts reconcile; B1-05 supplies queries. |
| F06 currency consequences | Rebase/reporting history | B1-01/05 currency-separated reference reads | EUR listing remains EUR after CAD→USD reporting change; B3/B4 preserve raw facts. |

## Approved catalog and arbitrage handoff

[Policy record K01–K07](POLICY-REVIEW.md) is normative: ordinary company shares, established/liquid/industry-diverse selection, preserved/new Minecraft-themed identities, sourced real metadata,2026-11-13 local regular closes with actual-date carry-forward only when no close/session exists, unavailable missing seeds and sourced outstanding-share metadata separate from game supply. Historical2025 seeds remain provenance; new-start prices use the new cutoff. Do not silently remove an original instrument if classification conflicts with ordinary-share eligibility.

Every cross-listing has an explicit underlying/security relationship and B5 route eligibility/restriction result; B5 arbitrage handles executable net opportunities via finite B4 fills and B2 custody. No shared-name fungibility, double-issued underlying supply or direct price nudging. Listing seed closes are not simultaneous executable quotes. Cross-venue capability stays gated until required B5 restrictions/transfer authority is accepted.

## Decision gates

BD01 selection policy is approved; row/source/license/relationship validation still gates catalog activation; BD02 and [T01–T07](CALENDAR-REVIEW.md) are approved: real venue holidays/early closes/breaks, regular continuous sessions only, UTC intervals derived from retained venue-zone/DST rules, chronological catch-up, local reviewed calendar coverage with affected-market refusal on gaps and prospective audited exceptional closures. Opening is inclusive and closing exclusive. Actual sourced venue/date coverage and implementation feasibility remain for B1-01/02. Exact ratio bounds, lifecycle overlap rules, query caps and transaction-generation sizing must be frozen in B1-01/02 before implementation. Venue quotas and the two original OTC dispositions are already approved and are not reopened. Source inspection and explicit unknowns can proceed without approving missing prices or licenses.

## Package boundaries and delivery

[EXECUTION.md](EXECUTION.md) is the authoritative selectable draft queue. Earlier proposed brief headings are refined by this queue: existing IDs retain their domain; additional acceptance/query/activation packages split later boundaries. No approval, implementation or verification follows from this refinement.

All packages inherit shared authentication, worker/journal, exact arithmetic, original-outcome, effective-time, bounded-read/configuration and recovery semantics. Reject stale, overflow, absent authority and unsupported sources explicitly; do not fabricate fallback quantities/prices. Each selected package must finalize its affected schema/state/DTO/configuration bounds before code and preserve historical decoders. No public SQL or alternate money ledger.

Planning uses diff/link review only; future packages follow [.codex/VERIFICATION.md](../../.codex/VERIFICATION.md), including at most one useful focused test invocation and the three ordinary runnable delivery commands once. No automatic full-suite/test-per-package gate. Keep runClient/runServer, registered main source set and required development runtime dependencies; one owner runs Gradle/game checks sequentially. Each package records its handoff/evidence and stops before dependents.

## Approved cross-market handoff — 2026-11-16

B5 [X01–X10](../B5-international-arbitrage/POLICY-REVIEW.md) are approved, including sourced backed exact ratios/whole-unit output, same-security transfer vs distinct conversion and captured due-time/fee semantics. B1 supplies relationship/lot/source evidence; shared issuer/name does not confer conversion rights and cross-listings do not duplicate supply. Actual compatibility is B1/B5 preparation, not a waived source check.

## Player equity/action expansion handoff — 2026-11-16

[B8 scope/answers](../B8-advanced-markets/CORPORATIONS-EXPANSION.md) add player company/class/cap-table, private issuance/subscriptions, founder-to-voting governance, optional listing and same/cross-player merger rights. Preserve original catalog/account/security histories, actual cash/custody/supply, unreturned/pledged/action entitlements and successor claim authority. Registration or arbitrary issue price grants no mark/wealth/collateral; optional player listings do not silently displace the 800 curated reference rows. Audit this newly requested scope explicitly; exact class/action/governance/listing policy and source feasibility remain unapproved/preparatory, no implementation selected.
