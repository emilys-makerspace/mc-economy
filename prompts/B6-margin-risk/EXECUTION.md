# B6 draft package queue

Planning only, prepared 2026-11-13. All package drafts were [approved on 2026-11-17](../planning/B-PACKAGES.md) and await explicit selection by ID. [Phase contract](CONTRACT.md) · [Shared contract](../planning/B-SHARED.md) · [Decision register](../planning/B-DECISIONS.md). [G01–G11](POLICY-REVIEW.md) policies are approved 2026-11-16. Actual secured/revolving/variable adapter, risk/pledge/rate/source and debt/collection compatibility remains preparation work. Select one exact ID only after accepted prerequisite evidence and any actual policy gaps are resolved. Finish its scoped handoff before dependents; do not auto-start the next row.

Required phase inputs: Accepted A6/Stage A lending lifecycle and B2/B3/B4 custody/mark/execution handoffs.

| Package | Outcome | Prerequisite | Status |
| --- | --- | --- | --- |
| [B6-00](B6-00.md) | Source and margin feasibility checkpoint | None beyond phase upstream handoffs | Approved draft |
| [B6-01](B6-01.md) | Agreement and unified buying power | B6-00 | Approved draft |
| [B6-02](B6-02.md) | Funded lending adapter | B6-01; approved lender/rate/repayment terms | Approved draft |
| [B6-03](B6-03.md) | Exclusive collateral and commitment boundary | B6-02 | Approved draft |
| [B6-04](B6-04.md) | Calls, UTC deadlines and servicing | B6-03 and dispatcher readiness | Approved draft |
| [B6-05](B6-05.md) | Finite liquidation and residual debt | B6-04 plus accepted B4 forced-order authority | Approved draft |
| [B6-06](B6-06.md) | Product controls and B7/B9 handoff | B6-05 | Approved draft |

B6-00 adds an explicit source/contract checkpoint. The existing DESIGN B6-01–06 meanings remain stable; this queue makes them individually selectable drafts.

All package/subtask verification inherits [.codex/VERIFICATION.md](../../.codex/VERIFICATION.md). No tests/Gradle/game for drafting, no automatic full-suite phase gate.
