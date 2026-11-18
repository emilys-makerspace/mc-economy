# B2 — Brokerage, custody and portfolios — draft package queue

Prepared 2026-11-13. All package drafts were [approved on 2026-11-17](../planning/B-PACKAGES.md) and await explicit selection by ID. [R01–R09](POLICY-REVIEW.md) brokerage policy is approved 2026-11-14; actual source/representation/fee/proof feasibility remains preparation work. Explicit implementation selection and satisfied prerequisites/decision gates are required. [Contract](CONTRACT.md) · [Shared rules](../planning/B-SHARED.md) · [Decisions](../planning/B-DECISIONS.md).

| Package | Outcome | Prerequisite / decision gate | Status |
| --- | --- | --- | --- |
| [B2-01](B2-01.md) | Brokerage source checkpoint, lifecycle and funding | Accepted Stage A accounting and B1-01/05 reference contracts; BD04 funding/lifecycle policy; accepted B0 for corporate use | Approved draft |
| [B2-02](B2-02.md) | Balanced custody postings and claims | B2-01; B1 quantity/relationship rules; approved issuance authority and bounds | Approved draft |
| [B2-03](B2-03.md) | Lots, basis and bounded portfolio history | B2-02; BD04 approved lot/fee/FX/residual policy | Approved draft |
| [B2-04](B2-04.md) | Atomic settlement participants and reconciliation seam | B2-02 and B2-03; current Stage A transaction limits/participants reviewed | Approved draft |
| [B2-05](B2-05.md) | Accepted securities marks and wealth valuation adapter | B2-04 and accepted B3-04 mark/history API; BD04 source/coverage policy | Approved draft |
| [B2-06](B2-06.md) | Custody and portfolio acceptance handoff | B2-05 and recorded B2-01–04 outcomes; accepted B0 for claimed corporate mode | Approved draft |

Finish and hand off each selected package before a dependent starts. Prerequisites mean accepted contracts/evidence in their recorded scope, not automatic reruns. Drafting requires no Gradle/tests/game. Apply the shared verification policy to every sub-task.
