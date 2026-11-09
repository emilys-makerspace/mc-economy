# Terminal UI verification — 2026-11-09

Java21; Minecraft1.21.1 / NeoForge21.1.252 retained, with main mod source set registered and SQLite on development runtimes. Main agent owned all sequential Gradle checks.

- `./gradlew help --task runClient`: passed.
- `./gradlew runClient --dry-run`: passed.
- `./gradlew build -x test`: first compile found new ActorId/PlayerId mismatches; fixed and affected packaging check passed. `jar sourcesJar` refreshed final navigation artifacts.
- Focused selection: `FundedClosureTest`, `BankingDashboardTest`, `TerminalPolicyTest`. Fixed new test Kind import ambiguity. Dashboard and terminal-policy classes passed. Cash closure needed the existing standalone NeoForge/Minecraft bootstrap setup; only the affected closure class was rerun. Final result: all10 selected tests passed across the three classes (seven dashboard/policy checks, then three closure checks).

Added real SQLite coverage for bank/owner scope, five history rows and paging, new finite wire choices, bound-kiosk native service settings without saved-setting mutation, and atomic closure cash/custody/replay without crediting primary. Read-only review also corrected dashboard null handling, original history currency and left/right transaction layout; assisted admission retained owner/issuer/token checks with world/expiry/design parity.

No full suite or native game launch. Visual/gameplay acceptance of new layouts remains manual; task checks are not a live visual witness. No external-client evidence claimed.

Final integration review fixed automatic same-bank deposit account selection, added the initial withdrawal balance, completed primary-provider source/target navigation, and advanced banking protocol to11 for the new finite actions. Clients and servers must update together. Final `jar sourcesJar` passed after these presentation/admission changes; the scoped bank-account selection helper was covered by the dashboard regression.
