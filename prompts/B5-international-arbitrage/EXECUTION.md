# B5 draft package queue

Planning only, prepared 2026-11-13. All package drafts were [approved on 2026-11-17](../planning/B-PACKAGES.md) and await explicit selection by ID. [Phase contract](CONTRACT.md) · [Shared contract](../planning/B-SHARED.md) · [Decision register](../planning/B-DECISIONS.md). [X01–X10](POLICY-REVIEW.md) policies are approved 2026-11-16; actual source/route/fee/custody/strategy/activity-manifest feasibility and prerequisite evidence remain. Select one exact ID only after accepted prerequisite evidence and any actual policy gaps are resolved. Finish its scoped handoff before dependents; do not auto-start the next row.

Required phase inputs: Accepted B4 execution/settlement, B1 instrument relationships, B2 custody, B3 finite books and Stage A FX.

| Package | Outcome | Prerequisite | Status |
| --- | --- | --- | --- |
| [B5-00](B5-00.md) | Source and conversion feasibility checkpoint | None beyond the phase upstream handoffs | Approved draft |
| [B5-01](B5-01.md) | Eligibility and conversion rules | B5-00 | Approved draft |
| [B5-02](B5-02.md) | Durable cross-market transfer | B5-01 and accepted B2 atomic custody interface | Approved draft |
| [B5-03](B5-03.md) | Net executable opportunity view | B5-02 and accepted B3 quote/depth plus Stage A FX contracts | Approved draft |
| [B5-04](B5-04.md) | Funded institutional arbitrage | B5-03 and accepted B4 order coordinator | Approved draft |
| [B5-05](B5-05.md) | Controls and bounded acceptance handoff | B5-04 | Approved draft |

B5-00 adds an explicit source/contract checkpoint. The existing DESIGN B5-01–05 meanings remain stable; this queue makes them individually selectable drafts.

All package/subtask verification inherits [.codex/VERIFICATION.md](../../.codex/VERIFICATION.md). No tests/Gradle/game for drafting, no automatic full-suite phase gate.
