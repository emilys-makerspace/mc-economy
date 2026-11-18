# B1 — Market registries — draft package queue

Prepared 2026-11-13. All package drafts were [approved on 2026-11-17](../planning/B-PACKAGES.md) and await explicit selection by ID. Catalog K01–K07 and the delegated BLK/SCHW NYSE replacement selections are [recorded](POLICY-REVIEW.md); exact manifest/close/source/license evidence remains. Explicit implementation selection and satisfied prerequisites/decision gates are required. [Contract](CONTRACT.md) · [Shared rules](../planning/B-SHARED.md) · [Decisions](../planning/B-DECISIONS.md).

| Package | Outcome | Prerequisite / decision gate | Status |
| --- | --- | --- | --- |
| [B1-01](B1-01.md) | Entity, source and compatibility checkpoint | Accepted A1/A2 and current Stage A state; BD01 known-source inventory | Approved draft |
| [B1-02](B1-02.md) | Exchange rules and instrument relationships | B1-01; approved BD02/T01–T07 policies with sourced calendar coverage and approved relationship/unit policy | Approved draft |
| [B1-03](B1-03.md) | Original catalog preservation and seed provenance | B1-01; BD01 row/source/license review for supplied material | Approved draft |
| [B1-04](B1-04.md) | Reviewed 800-listing release and activation | B1-02 and B1-03; BD01 approved row-level manifest/replacements/licenses | Approved draft |
| [B1-05](B1-05.md) | Bounded registry queries and diagnostics | B1-04; B1-01 frozen query and publication contract | Approved draft |
| [B1-06](B1-06.md) | Registry acceptance and downstream handoff | B1-05 and recorded B1-01–04 outcomes | Approved draft |

Finish and hand off each selected package before a dependent starts. Prerequisites mean accepted contracts/evidence in their recorded scope, not automatic reruns. Drafting requires no Gradle/tests/game. Apply the shared verification policy to every sub-task.
