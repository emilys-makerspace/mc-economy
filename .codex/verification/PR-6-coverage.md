# PR #6 coverage improvements

2026-11-08: added 23 behavior tests in response to the [42.82130% Codecov patch report](https://github.com/emilys-makerspace/mc-economy/pull/6#issuecomment-6725324716). The 60% strict patch target is not yet reached.

## Result

One full `./gradlew build` passed: **672 tests, zero failures/errors/skips**. Reports: `build/reports/jacoco/test/html/index.html` and `build/reports/jacoco/test/jacocoTestReport.xml`.

| Measure | Result |
| --- | --- |
| Local strict patch estimate | 2,012 / 3,589 = **56.06018%** |
| Executed patch lines, including branch partials | 2,798 / 3,589 = **77.96043%** |
| Repository line coverage | 20,712 / 32,404 = **63.91803%** |
| Repository branch coverage | 18,944 / 42,011 = **45.09295%** |

The patch estimate intersects added Java lines from `git diff --unified=0 origin/main -- src/main/java` with JaCoCo XML source-line records. Fully covered means `ci > 0` and `mb == 0`; executed lines with `mb > 0` are partial. There are 786 partial and 791 missed changed executable lines. Base: `8cb46ef887b789f571d1516d0f8c9395f1cc4895`; current working-tree production edits are included. Codecov has not been rerun remotely and its final classification may differ slightly.

## Protected behavior

- Native crafting/cooking/stonecutting recipes, ingredient expressions, tags, returned containers, deterministic capture and refusal of custom callbacks/components.
- Native brewing effects/forms and altered/custom brewing exclusions.
- Native component identities for containers, books, fireworks, bees and cosmetic state; unsafe data refuses capture.
- Prototype properties, enchantment premiums, flower effects and dye materials. The enchantment fixture uses vanilla definitions with explicit direct holder membership; it does not establish loaded-world tag membership. Datagen holder wrappers remain refused.
- Exact variant material pricing, strict configuration reloads, canonical identity bounds and typed provider conversion.
- Public result prices/proofs/provenance from real SQLite publication and effective FX.
- Damaged retained snapshot projection/content/provenance detection and reopening intact history.
- Cash/account untouched cancellation, witnessed account return, owner/proof checks, one protected claim and retained original replay after reopening.

Four small package-private overloads permit native object tests without a running server. Public server-thread guards remain. JaCoCo instruments only the mod namespace to avoid JVM method-size errors in Minecraft static initializers; report class exclusions are unchanged.

## Verification and remaining scope

Focused checks passed for the initial six new valuation classes and the integrity/cash/account classes. The first registry-capture fixture failed because datagen holder sets intentionally fail production trust checks; the corrected explicit-holder fixture passed in the final full build. One full suite was run to establish aggregate coverage after focused runs replaced execution data.

Delivery checks passed: `./gradlew build -x test`, `./gradlew help --task runClient`, `./gradlew runClient --dry-run`, and `git diff --check`. Java 21, NeoForge 21.1.252, main source-set registration and SQLite development runtime remain configured. No game was launched or player world modified.

The largest remaining gaps are server publication/reload/provider/API admission (`ValuationRuntime`, `ValuationApi`, `ValuationCapture`) and commands/menus. At this denominator, 142 more changed lines must become fully covered to reach 60%. A real server lifecycle/admission fixture would address a substantial remaining gap. No coverage threshold, report exclusion or automatic suite gate was introduced.
