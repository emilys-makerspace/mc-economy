# B9 draft package queue

Planning revision1, 2026-11-13. All package drafts were [approved on 2026-11-17](../planning/B-PACKAGES.md) and await explicit selection by ID. Resolve prerequisite evidence and affected decisions before selecting a package. [Phase contract](CONTRACT.md) · [Stage B shared contract](../planning/B-SHARED.md) · [decision register](../planning/B-DECISIONS.md)

The [approved operations policy](OPERATIONS-REVIEW.md) covers B9-02 history and queue bounds and B9-03 measurement and recovery targets.

| Package | Outcome | Prerequisites | Status |
| --- | --- | --- | --- |
| [B9-01](B9-01.md) | Feature, authority and accounting audit | Accepted selected B0–B8 handoffs; current Stage A contracts and decision dispositions | Approved draft |
| [B9-02](B9-02.md) | Synchronization and history lifecycle | B9-01; approved BD09 snapshot/retention/archive policy | Approved draft |
| [B9-03](B9-03.md) | Full-universe bounded workload evidence | B9-02; exact B1 release manifest; approved BD09 workload/hardware/budgets | Approved draft |
| [B9-04](B9-04.md) | Stopped-world upgrade, restore and combined recovery | B9-03 measured scope; current upgrade contracts; specific affected recovery concerns | Approved draft |
| [B9-05](B9-05.md) | Product explanations, balancing and extension guidance | B9-04; accepted actual product policies and measured limits | Approved draft |
| [B9-06](B9-06.md) | Release artifact and qualified handoff | B9-05; explicit resolution/disposition of release gaps | Approved draft |

Source/checkpoint packages are bounded source/document work; completion does not automatically run Gradle/tests/game. Implementation and acceptance follow [project verification scope](../../.codex/VERIFICATION.md), reusing unchanged evidence and with no full-suite completion gate. One owner runs necessary delivery checks sequentially. Each selected package hands off and stops before dependents.
