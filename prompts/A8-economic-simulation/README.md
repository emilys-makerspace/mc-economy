# A8 — Activity-driven economy and monetary villager commerce

Status: technical revision 1 and all 12 package prompts approved by the user on 2026-11-07; A8-01 source review and A8-02-01 attribution are complete in their recorded scopes; A8-02-02 windows/model is complete; A8-03-01 markets/catalog/pricing is complete; A8-03-02 entity hooks/stock/progression is complete; A8-04-01 account/NPC/FX APIs are complete; A8-04-02 cash/physical-custody APIs are complete; A8-05 currency-prime/loan capture is complete; A8-06-01 is next and unselected. The user approved [the gameplay decisions](../../.codex/architecture/a8-planning.md). This entry point replaces the former broad draft; it records phase approval without selecting a package or starting implementation, Gradle/tests/game launches, dependency changes or world migration.

## Start here

- [Shared package contract](CONTRACT.md): scope, authority and verification rules.
- [Revision-1 technical contracts](../../.codex/architecture/a8-shared-contracts.md): formulas, interfaces, transaction boundaries, timing, recovery and engineering defaults.
- [Selection queue](EXECUTION.md): 12 ordered packages and prerequisites; A8-01 through A8-05 complete; next candidate A8-06-01 is unselected.
- [Gameplay decision record](../../.codex/architecture/a8-planning.md): approved policies and distinction from technical proposals.

A8 consumes accepted A1–A7 clock, journal, custody, banking and village interfaces. Minecraft 1.21.1, NeoForge 21.1.252 and Java 21 remain pinned. Preserve code, local worlds and backups; no reset or hypothetical deployed-trader conversion work.

## Resulting behavior

Player/server activity drives currency-specific prime and coherent FX every 20 real minutes, using seven completed financial days. Empty activity holds rates unchanged. Prime stays within 0%–25% APR with a ±0.10 percentage-point step cap; every FX pair has a ±0.5% step cap and 50%–200% activation-anchor range. Channel weights are equal after normalization. Subprime pricing and existing fixed terms remain captured.

Villager commerce uses cash or explicitly selected debit/account settlement, including confirmed foreign FX. NPC purchasing funds are unlimited with no accepted-goods purchase quota; payouts create money and player payments retire it. Village prices share supply/demand state, individual traders hold finite sale stock, and wandering traders have independent markets. Retain catalogs/progression and secondary ingredients. Temporary prices use 1 CAD per emerald before conversion and local adjustments; A9 is expected to replace that baseline through the price-source API.

A8 adds neither commodity backing nor synthetic business cycles, random macro shocks, securities trading, secured/variable lending or offers for A7 bankers.

## Feature coverage and handoff

| Coverage | Existing behavior | A8 owner | Acceptance focus / handoff |
| --- | --- | --- | --- |
| F05 simulation/monetary evolution | Configured recording, global prime and historical daily FX | A8-02-01/02, A8-05, A8-06-01/02 | Captured source/version/window, empty-window hold, coherent bounds and prospective handover; A8-08 publishes API limits. |
| F17 clock coordination only | Fixed UTC scheduler plus arbitrary financial deadlines | A8-06-01/02 | Bounded cursor/restart/readiness and explicit old-FX retirement; no second clock or rewritten fixed signature. |
| Added monetary NPC commerce | Item-currency trader checkout; accepted A5 player-commerce custody | A8-03-01/02, A8-04-01/02, A8-07 | Original outcomes, goods/cash custody, finite stock/unlimited buying and explicit FX consent. A7 banking authority stays separate. |
| A9 valuation handoff | No comprehensive item-value source | A8-08 | Versioned replacement of temporary price source for future quotes; preserve balances/completed trades; A9 is not an A8 prerequisite. |

F05/F17 labels are carried from this phase's original entry point; this table does not activate unrelated features or prompts.

## Review and selection

The documents distinguish approved gameplay and engineering defaults from unverified implementation, including normalization scale and membership algorithms. A8-01 validates pinned-source feasibility and freezes technical revision 1 before implementation. A material gameplay conflict returns to the user; routine implementation choices do not require repeated confirmation. Completing a prerequisite never selects a dependent.

Planning verification is diff/link/source review only. Future checks follow [VERIFICATION.md](../../.codex/VERIFICATION.md): ordinary runnable delivery uses build excluding tests and the two client-task checks once, plus at most one useful focused named-test invocation. No automatic full-suite or repeated phase gates. Root README/changelog release changes belong to usable releases, not this preparation.
