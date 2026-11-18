# B3 — Prices, liquidity and market simulation — draft package queue

Prepared 2026-11-13. All package drafts were [approved on 2026-11-17](../planning/B-PACKAGES.md) and await explicit selection by ID. Funding/liquidity [M01–M08](LIQUIDITY-REVIEW.md) are approved with the 100× cash-only amendment to CAD 2.8T equivalent; P01–P07 numeric dynamics/mark policies are also approved; exact formulas and actual feasibility remain to resolve. Explicit implementation selection and satisfied prerequisites/decision gates are required. [Contract](CONTRACT.md) · [Shared rules](../planning/B-SHARED.md) · [Decisions](../planning/B-DECISIONS.md).

| Package | Outcome | Prerequisite / decision gate | Status |
| --- | --- | --- | --- |
| [B3-01](B3-01.md) | Source, factor and participant contract checkpoint | Accepted A8 effective-time readers, B1-06 and B2-04 foundation; BD02/03 known inputs | Approved draft |
| [B3-02-01](B3-02-01.md) | Persistent scheduling and prospective configuration | B3-01; resolved BD02/03 cadence/model and BD09 engineering bounds | Approved draft |
| [B3-02-02](B3-02-02.md) | Bounded simulation diagnostics and readiness | B3-02-01 | Approved draft |
| [B3-02-03](B3-02-03.md) | Simulation foundation acceptance and readiness handoff | B3-02-02 and recorded B3-01/02-01 outcomes | Approved draft |
| [B3-03](B3-03.md) | Session-aware finite participant liquidity | B3-02-03 and accepted B2-04; BD02 calendars and BD03 participant/funding/quote rules resolved | Approved draft |
| [B3-04](B3-04.md) | Mark/history publication and prospective price administration | B3-03; BD03 mark selection and BD09 retention/bounds resolved | Approved draft |
| [B3-05](B3-05.md) | Simulation acceptance and execution handoff | B3-04 and recorded foundation/liquidity outcomes | Approved draft |

Finish and hand off each selected package before a dependent starts. Prerequisites mean accepted contracts/evidence in their recorded scope, not automatic reruns. Drafting requires no Gradle/tests/game. Apply the shared verification policy to every sub-task.
