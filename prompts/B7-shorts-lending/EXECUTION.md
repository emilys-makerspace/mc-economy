# B7 draft package queue

Planning only, prepared 2026-11-13. All package drafts were [approved on 2026-11-17](../planning/B-PACKAGES.md) and await explicit selection by ID. [Phase contract](CONTRACT.md) · [Shared contract](../planning/B-SHARED.md) · [Decision register](../planning/B-DECISIONS.md). [L01–L11](POLICY-REVIEW.md) policies are approved 2026-11-16. Actual lineage/pool, shared risk, fee/source and recall/return/B8 compatibility remains preparation work. Select one exact ID only after accepted prerequisite evidence and any actual policy gaps are resolved. Finish its scoped handoff before dependents; do not auto-start the next row.

Required phase inputs: Accepted B6 risk/credit and B2/B3/B4 custody, current marks and execution.

| Package | Outcome | Prerequisite | Status |
| --- | --- | --- | --- |
| [B7-00](B7-00.md) | Source and lending feasibility checkpoint | None beyond phase upstream handoffs | Approved draft |
| [B7-01](B7-01.md) | Lending consent, inventory and locates | B7-00; approved lending eligibility and locate policy | Approved draft |
| [B7-02](B7-02.md) | Covered sell and partial return | B7-01 and accepted B4 settlement | Approved draft |
| [B7-03](B7-03.md) | Unified short risk and restricted proceeds | B7-02 and accepted B6 risk authority | Approved draft |
| [B7-04](B7-04.md) | Borrow rates and fee servicing | B7-03; approved rate/utilization/reset/fee policy | Approved draft |
| [B7-05](B7-05.md) | Recalls, buy-ins and unresolved returns | B7-04 and B6/B4 forced-order authority | Approved draft |
| [B7-06](B7-06.md) | Owner views, controls and B8/B9 handoff | B7-05 | Approved draft |

B7-00 adds an explicit source/contract checkpoint. The existing DESIGN B7-01–06 meanings remain stable; this queue makes them individually selectable drafts.

All package/subtask verification inherits [.codex/VERIFICATION.md](../../.codex/VERIFICATION.md). No tests/Gradle/game for drafting, no automatic full-suite phase gate.
