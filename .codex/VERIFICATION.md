# Choose verification for the change

This policy applies to all current and future modules, phases, packages and sub-tasks in this project, including delegated work. New prompts, templates and module instructions must inherit it rather than introduce their own automatic test gates.

- **Docs or Codex configuration:** review the diff and relevant links or configuration. No Gradle, tests or game launch for prose-only edits.
- **Ordinary implementation sub-task:** run tests sparingly, only for a specific changed behavior or unresolved concern. Completion itself does not require a test run; use review or still-applicable evidence when sufficient. If useful, choose at most one focused test invocation, normally one to three named test classes. Use `./gradlew test --tests '<fully.qualified.ChangedBehaviorTest>'` with additional `--tests` filters only for directly affected behavior. Do not run unfiltered `test`, a whole package, or the full suite by default. Fix a failure and rerun only the affected check; broaden only for a demonstrated unresolved risk and record why.
- **Ordinary runnable delivery:** use Java 21 and run `./gradlew build -x test`, `./gradlew help --task runClient` and `./gradlew runClient --dry-run` once. The build checks compilation/packaging; report focused test results separately. Keep the mod registered and dependencies on development runtimes. Standard `build` still includes all tests, so do not use it as a routine packaging check.
- **Full suite, including phase acceptance and releases:** run only when explicitly requested by the user or when a concrete concern cannot reasonably be addressed by focused checks, such as a cross-cutting infrastructure change whose impact cannot be isolated. State the reason and why narrower checks are insufficient before running. Neither a phase boundary nor a release automatically triggers it. When justified, run `./gradlew build` once; do not precede it with a separate full `test` run or repeat it without new evidence. Otherwise use scoped checks and `build -x test` where a build is needed.
- **Platform, dependency or lifecycle changes:** inspect Gradle tasks if wiring changed. Use an isolated client/server smoke check only for affected runtime loading or lifecycle behavior; stop the server normally. A dry run alone does not establish runtime loading. Menu edits alone do not require repeated client/server launches.
- **Storage, journal, recovery or clock:** choose named tests for the exact changed invariant. For example, use `JournalMigrationTest` for a changed migration, or the relevant backup/clock/WAL class for changed maintenance behavior; these are alternatives selected by impact, not a cumulative checklist. Keep actual fixture-process termination separate from exception injection; never kill a user's game.
- **Worlds and packaging:** use disposable fixtures, never player worlds, and restore only to a new disposable path. Check the clean witness before general backup. Do not accept the EULA or package fixtures.

## Keep test creation proportional

A10-04 external-client testing is manual-only and currently pending by explicit user instruction. Do not include it in CircleCI jobs, pass/fail gates or coverage requirements. Existing self-contained integrated-client CI scenarios remain in scope and do not count as external-client evidence.

Do not add tests for every helper, accessor, constant, internal branch or permutation. Add a small behavior test only when it protects a meaningful outcome or reproduces a concrete defect. Prefer existing fixtures using real services and SQLite. Do not introduce mock-heavy interaction tests, tests that restate the implementation, or new fixture frameworks for routine work. Reuse an existing acceptance scenario rather than layering unit, integration, restart, crash, abuse and performance tests over the same unchanged behavior. Add a process/restart/game scenario only when the changed behavior needs that environment and existing evidence does not cover it.

One owner runs Gradle and game checks sequentially. Reuse still-applicable evidence; rerun when changed code, a failure, or a required gate justifies it. Record selected commands, results, scope and caveats once in the package evidence. Distinguish the repository suite total from tests added or selected for the sub-task; a larger test count is not an acceptance goal.

This guide controls verification scope across the project. Older prompt instructions to run targeted tests followed by an unfiltered build, or automatically run the full suite at completion, are superseded. Historical verification records remain factual records of what ran, not instructions to repeat those runs.

Historical evidence: [A1-01](verification/A1-01.md) · [A1-02](verification/A1-02.md) · [A1-03](verification/A1-03.md) · [A1-04](verification/A1-04.md).

Current integrated foundation gate: [A1-05-03](verification/A1-05-03.md).
