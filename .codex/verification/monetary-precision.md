# Eight-decimal monetary precision verification

2026-11-09 — Direct requested change; no phase/package status change.

## Selected evidence

One focused selection covered four named classes because the change affects independent FX settlement, card accrual, loan schedule replay and actual SQLite rate publication/restart paths:

```
./gradlew test --tests 'ca.emilybelnavis.mcmods.economy.api.CurrencyConversionTest' --tests 'ca.emilybelnavis.mcmods.economy.credit.CardServicingTest' --tests 'ca.emilybelnavis.mcmods.economy.loan.LoanServicingTest' --tests 'ca.emilybelnavis.mcmods.economy.simulation.macro.MacroProviderTest'
```

Result: 30 selected tests, initially29 passed and one new historical-loan golden assertion failed. The first attempt also exposed an incorrect test call signature, fixed before execution. A direct Java21 comparison of the original HEAD LoanServicing class against scheduleLegacy proved the complete original/legacy schedules identical. Correct original final residual is -0.000962495913064378; the independently derived initial golden had clipped signed carry incorrectly. New eight-place residual is -0.000962570000000000. Corrected only the golden and reran only LoanServicingTest.historicalSchedulesRetainEighteenPlaceResidualWitnesses: PASS. All30 selected cases therefore pass across the focused selection and single affected-case retry. No full suite run.

Cases include the requested CAD/JPY and GBP/EUR examples, nearest funding debit around a half-cent, shared account quote arithmetic, interest accrual0.67799050 with posted0.68, legacy/current credit daily witness policy binding, original loan schedule residuals, and an actual SQLite legacy12-place FX opening → new8-place macro publication → restart with original payload unchanged. Selected-plan history accepts new factv2.

## Delivery checks

- ./gradlew build -x test: PASS after fixing a formatting violation from a late test edit. Compilation and packaging succeeded on the first build; only formatting failed. The successful retry reused unchanged compilation/packaging outputs.
- ./gradlew help --task runClient: PASS.
- ./gradlew runClient --dry-run: PASS; compile/resources/assets/classpath/prepareClientRun/runClient chain available.
- git diff --check: PASS.
- Java formatter applied; final build includes all configured format checks.

Minecraft1.21.1, NeoForge21.1.252, Java21 and main-source/development-runtime wiring retained. Existing runServer configuration retained. Deliverable: build/libs/economy-0.5.0-alpha.jar.

## Limits

No game was launched and no player world was opened or rewritten. GUI quotient formatting is source/build verified, not visually witnessed. Twelve/eighteen-place serialization containers remain for legacy compatibility; new numerical values have no nonzero digits beyond eight places. Existing historical algorithms and persisted outputs remain frozen. At an exact model bound, AUTO can back off before eight-place quantization; invalid MANUAL/PAUSED quantization is refused rather than silently changing the requested basket or relaxing bounds.


## Reported suite failure repair (2026-11-09)

The reported 13 failures exposed stale numerical/version expectations plus zero-credit quote and signed-residual validation regressions. Restored zero-credit exact-debit quote issuance for funded closure. New disbursement/repayment FX receipts use v2 signed residuals while preserving v1 validation. Updated loan FX activity/history consumers and contract-derived test expectations.

One focused invocation selected 14 named classes: CardReplacementPricingTest, CreditAcceptanceTest, ChfFxActivationTest, FxPublicationTest, DepositYieldTest, FundedClosureTest, WithdrawalsTest, DebtLifecycleTest, LoanActivityAttributionTest, LoanIntegrationTest, SimulationHistoryTest, LoanRepaymentFxTest, CreditRepaymentFxTest and DebtPaymentsTest (each under its existing fully qualified package). Result: 72/73 passed; the only failure was a new assertion assuming the collection fixture's seed-dependent residual must be negative. Corrected it to compare the actual signed receipt and independently check v2 accepts/v1 rejects a negative residual. Reran only DebtLifecycleTest.foreignCollectionRepaymentChargesSeparateFeeConsumesOnceAndRollsBackFeeFailure: PASS. All 73 selected cases pass across that invocation and affected-case retry. No full suite run.

New repayment cases use real SQLite commits and replay, asserting USD1.02 debt funded by CAD1.42 with a negative reference residual and retained v1/v2 validation boundaries. Existing cases cover original quote consumption, fee-failure rollback, zero-payout closure, loan activity and saved-selection history. Java21 used throughout. `./gradlew build -x test`, `./gradlew help --task runClient` and `./gradlew runClient --dry-run`: PASS, each run once for this repair. `git diff --check`: PASS. Minecraft1.21.1, NeoForge21.1.252, main-source registration, runtime dependencies and runServer configuration retained. No game launched or player worlds opened. Updated artifact: `build/libs/economy-0.5.0-alpha.jar`.
