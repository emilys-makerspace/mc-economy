# B4 draft package queue

Planning revision1, 2026-11-13. All package drafts were [approved on 2026-11-17](../planning/B-PACKAGES.md) and await explicit selection by ID. [O01–O10](POLICY-REVIEW.md) order policies are approved 2026-11-14. Resolve actual prerequisite/source/lot/authority feasibility and other affected decisions before selecting a package. [Phase contract](CONTRACT.md) · [Stage B shared contract](../planning/B-SHARED.md) · [decision register](../planning/B-DECISIONS.md)

| Package | Outcome | Prerequisites | Status |
| --- | --- | --- | --- |
| [B4-00](B4-00.md) | Source checkpoint and settlement contract | Accepted B2 foundation and B3 quote/liquidity/session contracts; current Stage A and B0 if corporate use | Approved draft |
| [B4-01](B4-01.md) | Order intents and fund/share reservations | B4-00; B2 claims and B3 quote DTOs; approved BD05 rules | Approved draft |
| [B4-02](B4-02.md) | Finite matching plans and cancellation races | B4-01; accepted B3 liquidity and approved BD05 priority/session/player matching | Approved draft |
| [B4-03](B4-03.md) | Atomic cash/custody/liquidity settlement and fees | B4-02; accepted B2 atomic participants and current Stage A pricing/FX | Approved draft |
| [B4-04](B4-04.md) | Order reports and public market API | B4-03; supported API source signatures and B0 for corporate context | Approved draft |
| [B4-05](B4-05.md) | Trading menus and native user flow | B4-04; B2 funding queries; accepted hardware access contract; approved BD05 interface/consent rules | Approved draft |
| [B4-06](B4-06.md) | Four-cell trading computer and original resources | B4-00 geometry/resource inventory and B4-04 service admission; B4-05 menu seam before activation | Approved draft |
| [B4-07](B4-07.md) | Cash-market acceptance and downstream handoff | B4-05 and B4-06 accepted; production B3 participants and B2 reconciliation ready | Approved draft |

Source/checkpoint packages are bounded source/document work; completion does not automatically run Gradle/tests/game. Implementation and acceptance follow [project verification scope](../../.codex/VERIFICATION.md), reusing unchanged evidence and with no full-suite completion gate. One owner runs necessary delivery checks sequentially. Each selected package hands off and stops before dependents. Draft source numbering ending00 in B4 is an added source checkpoint; existing brief B4-01…05 topics remain, with hardware separated into06 and phase acceptance07.
