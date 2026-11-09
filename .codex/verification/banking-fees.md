# Banking fees — 2026-11-09

The original local-currency policy below is superseded for new transactions by the CAD-based revision recorded at the end.

Implemented by explicit user request; “out of network” means a different issuing bank, independent of payment-network compatibility.

- Advance: 3.00 issuer fee; different-bank ATM adds 2.50, teller/same-bank ATM exempt. Full proceeds reach the BANK destination; financed fees enter advance principal, limits, funded reservation and daily accrual. Supplying banks receive their own cash/income fees atomically.
- Withdrawal: 2.50 when the account provider differs from the ATM bank or is missing/unassigned. Review freezes bank/provider/fee evidence and total debit; commit cannot overdraw or charge twice on replay. V4 facts preserve the original surcharge, with V1–V3 retained decoding. History combines FX/ATM fees; activity excludes surcharge from FX principal.
- Fees use card or funding-account currency. JPY surcharge rounds upward to 3 because its registry cannot represent 2.50. English and French reviews show fees before confirmation. Unrelated workspace edits were retained.

## Checks

- Focused invocation: `./gradlew test --tests 'ca.emilybelnavis.mcmods.economy.credit.CreditFacilitiesTest' --tests 'ca.emilybelnavis.mcmods.economy.journal.WithdrawalsTest' --tests 'ca.emilybelnavis.mcmods.economy.journal.HistoryDetailsTest'` — 23 passed (9 credit, 12 withdrawal, 2 history). An initial JUnit overload compilation error was fixed before this successful rerun. Gradle required approved access to its existing cache after sandbox lock denial.
- `./gradlew build -x test` — passed.
- `./gradlew help --task runClient` — passed.
- `./gradlew runClient --dry-run` — passed.
- Main source set remains registered; NeoForge21.1.252/Java21 retained; SQLite on development runtime.
- `git diff --check` — passed. Bounded independent source review reported no concrete defects.

Output: `build/libs/economy-0.5.0-alpha.jar`. No game launched or full suite run; live UI rendering and player-world behavior are not new evidence from this change.

## CAD-based revision — 2026-11-09

User redefined the bases as CAD3.00 and CAD2.50. New fees use the captured published CAD/target rate ratio and round once to the nearest 0.25 target major unit, or 25 JPY, with HALF_UP ties. Advance canonical requests bind the FX publication and reviewed amounts. Withdrawal V5 freezes original CAD and target rates; V4 local-currency records remain readable without repricing committed history. A changed FX publication requires fresh review. Same-bank/teller exemptions and fee-inclusive advance limits remain.

Focused checks: `BankingFeesTest`2, `CreditFacilitiesTest`9 and `WithdrawalsTest`13 — all 24 passed across scoped invocations. The first invocation exposed a new test fixture trying to mutate immutable account currency; the fixture now creates a separate EUR account. Only affected credit/withdrawal checks were rerun after the fix and fee-consistency tightening. Coverage includes direct ratio conversion, nonCAD reference rates, HALF_UP boundaries without intermediate minor-unit rounding,25JPY rounding, exemptions, frozen converted withdrawal rates and legacy fee compatibility.

Required delivery checks all passed once: `./gradlew build -x test`, `./gradlew help --task runClient`, `./gradlew runClient --dry-run`. Java21/NeoForge21.1.252 and development runtime registration/dependencies are retained. No game launched or full-suite run. Output remains `build/libs/economy-0.5.0-alpha.jar`.
