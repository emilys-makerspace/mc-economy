# A9 approved selection queue

The user approved all A9 drafts on 2026-11-07: technical revision 1, catalog pricing revision 2, shared contracts and all eight selectable packages, subject to prerequisites. **A9-01 is complete and accepted in source/document scope** ([handoff](../../.codex/A9-01.md), [evidence](../../.codex/verification/A9-01.md), [source/interface freeze](../../.codex/architecture/a9-source-contracts.md)). The other seven selectable packages remain approved, unselected and unexecuted. A9-02 is next eligible for explicit selection. Approval does not select or execute a package. Settled gameplay and routine engineering defaults are in [CONTRACT](CONTRACT.md), [technical contracts](../../.codex/architecture/a9-shared-contracts.md), [catalog](../../.codex/architecture/a9-item-catalog.md) and the [decision record](../../.codex/architecture/a9-planning.md#final-decisions-before-drafting). No further routine policy approval is needed for drafting. Source feasibility is A9-01; a concrete incompatibility must be recorded, not hidden behind guessed behavior.

| Order | Selectable package | Prerequisite |
| --- | --- | --- |
| 1 | [A9-01 source feasibility/shared contracts](A9-01.md) | Accepted A8 and settled A9 decisions |
| 2 | [A9-02 configuration and snapshots](A9-02.md) | A9-01 accepted |
| 3 | [A9-03-01 ordinary recipes/processing](A9-03-01.md) | A9-02 accepted |
| 4 | [A9-03-02 brewing](A9-03-02.md) | A9-03-01 accepted |
| 5 | [A9-03-03 variants/containers](A9-03-03.md) | A9-03-02 accepted |
| 6 | [A9-04 public API/SPI and valuation acceptance](A9-04.md) | All three A9-03 children accepted |
| 7 | [A9-05 durable source transition](A9-05.md) | A9-04 accepted |
| 8 | [A9-06 trader activation/acceptance](A9-06.md) | A9-05 accepted |

[A9-03](A9-03.md) is an overview only, not a selectable execution package. Execute one explicitly selected package, verify its bounded outcome and hand off before a dependent. Completion never automatically selects the next package.

## Settled direction

Complete ordinary vanilla coverage through reviewed acquisition anchors, supported derivation and variants. Keep operator-only items unpriced and unregistered mod items unavailable. Mod data arrives through the API, never automatic recipe guesses. Use exact internal costs, captured FX, HALF_UP unit prices with one-minor-unit floor (water excepted), quantity totals from published units and no new fractional carry. Retain demand/reputation/spreads and original history; unsupported listings stay disabled without player-facing explanation.

Apply the approved 75% raw/refined-cooked ratio once; brewing uses ingredients/bottles/allocated fuel across three outputs, free water and no labour/time fee. Apply approved enchantment premiums, curse/durability order, name-neutral and container/content rules. Details are in the linked shared contracts rather than a new open-decision checklist.

## Checks and handoffs

Follow [verification scope](../../.codex/VERIFICATION.md) project-wide. Draft/source-document work uses diff/link review only, no Gradle/tests/game. Each runtime package may use at most one meaningful focused named-test invocation if useful; no automatic test or full-suite completion gate. Ordinary runnable delivery runs `build -x test`, `help --task runClient` and `runClient --dry-run` once. One owner runs Gradle/game tasks sequentially. Preserve Minecraft 1.21.1, NeoForge 21.1.252, Java 21, registered main source set, working client/server tasks and required runtime dependencies.

Each selected package writes a short `.codex/<id>.md` handoff linking current architecture and scoped evidence, states limits and stops. Reuse unchanged upstream evidence. Preserve worlds/backups and historical financial data. A10 receives read-only valuation/provenance and recorded trader changes; other integrations stay separately selected.
