# B8 draft package queue

Planning only, prepared 2026-11-13. All package drafts were [approved on 2026-11-17](../planning/B-PACKAGES.md) and await explicit selection by ID. [Phase contract](CONTRACT.md) · [Shared contract](../planning/B-SHARED.md) · [Decision register](../planning/B-DECISIONS.md). Select one exact ID only after accepted prerequisite evidence and its consequential decisions are available. Finish its scoped handoff before dependents; do not auto-start the next row.

Player-corporation equity/action scope and the three [recorded answers](CORPORATIONS-EXPANSION.md) are approved 2026-11-16; the [approved equity and action rules](EQUITY-AND-ACTIONS-REVIEW.md) were accepted on 2026-11-17. The launch listing and valuation rules were also approved on 2026-11-17. Stop and halt rules are approved under the [approved stop and halt rules](STOPS-AND-HALTS-REVIEW.md). Fictional news calibration is approved. Use the [approved index methodology](INDEX-METHODOLOGY-REVIEW.md) for B8-06. Use the [approved curated issuer action rules](CURATED-ISSUER-ACTIONS-REVIEW.md) for B8-03 and B8-04. See the [approved news calibration](NEWS-CALIBRATION-REVIEW.md). Four B8-08 children are added; no implementation selected.

Required phase inputs: Accepted B0 identity/accounts/API and B5/B6/B7 scoped handoffs, plus B1/B2/B3/B4 identity/custody/session/order contracts.

| Package | Outcome | Prerequisite | Status |
| --- | --- | --- | --- |
| [B8-00](B8-00.md) | Source and action-coordination checkpoint | None beyond phase upstream handoffs | Approved draft |
| [B8-08-01](B8-08-01.md) | Player issuer/equity/classes | B8-00, accepted B0/B1/B2 and approved class rules | Approved draft |
| [B8-08-02](B8-08-02.md) | Private issues/subscriptions/transfers | B8-08-01; approved issuance and class protections with compatible transfer contracts | Approved draft |
| [B8-08-03](B8-08-03.md) | One-way founder→share-class governance | B8-08-02; approved transition, voting thresholds and class protections | Approved draft |
| [B8-01](B8-01.md) | Advanced trigger contracts | B8-00, accepted B4 order lifecycle and approved stop policy | Approved draft |
| [B8-02](B8-02.md) | Halts and bounded reopening | B8-01, B1/B3/B4 venue and session authority, and approved halt policy | Approved draft |
| [B8-08-04](B8-08-04.md) | Optional player listing and market price display | B8-08-03, B8-02 and compatible B1–B4 interfaces. Apply the [approved listing and valuation rules](LISTING-AND-VALUATION-REVIEW.md) | Approved draft |
| [B8-03](B8-03.md) | Dividend entitlements and funded payments | B8-02, B8-08-03; accepted B2/B7 claim interface and approved funded dividend and entitlement rules | Approved draft |
| [B8-04-01](B8-04-01.md) | Splits, reverse splits and fractional entitlements | B8-03; every affected owner interface and approved split, fraction and order treatment | Approved draft |
| [B8-04-02](B8-04-02.md) | Mergers, successor claims and delistings | B8-04-01, B8-08-03; matching company approvals and compatible successor, funding and order contracts | Approved draft |
| [B8-05](B8-05.md) | Persisted earnings and simulated news | B8-04-02 and accepted B3 factor API | Approved draft |
| [B8-06](B8-06.md) | Index methodology and historical analytics | B8-04-02, B8-05, reviewed B1 coverage and B3 price history | Approved draft |
| [B8-07](B8-07.md) | Action controls and integrated B9 handoff | B8-01–03, B8-04-01/02, B8-05/06, B8-08-01–04 | Approved draft |

B8-04 is a nonselectable overview. Its split children B8-04-01 and B8-04-02 replace the single oversized DESIGN B8-04 package; later IDs retain their prior meaning. B8-00 is the added source checkpoint.

All package/subtask verification inherits [.codex/VERIFICATION.md](../../.codex/VERIFICATION.md). No tests/Gradle/game for drafting, no automatic full-suite phase gate.

B8-08 is a nonselectable overview with four selectable children. Existing IDs retain their domains; table order/prerequisites govern, not numeric sorting. B8 now has 13 selectable packages.
