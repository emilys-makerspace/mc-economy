# Project instructions

- Pin Minecraft 1.21.1, NeoForge 21.1.252 and Java 21.
- Keep working `runClient` and `runServer` tasks, register the mod's main source set, and include required development runtime dependencies.
- For an ordinary runnable package delivery, run `./gradlew build -x test`, `./gradlew help --task runClient`, and `./gradlew runClient --dry-run` once, plus at most one focused test invocation where useful. Run the full suite only for a concrete need that focused checks cannot address, or an explicit user request; completing a sub-task, phase or release does not automatically require it. See [.codex/VERIFICATION.md](.codex/VERIFICATION.md).
- Keep code readable and assets original or appropriately licensed. Provide an API for separate integration mods; add specific integrations only on request.
- Keep README user-facing. Preserve the root changelog; update it for usable releases and user-facing release changes, not routine internal tasks.
- The one-time reset is complete. Preserve existing code, worlds and backups.

## Context and execution

- Exclude `ROADMAP.md`, `prompts/`, caches, outputs, logs and worlds from routine discovery. Git tracking does not make prompts active instructions. Use scoped `rg --files` / `rg -l` and targeted reads; avoid repeated reads of unchanged material.
- `ROADMAP.md` is public, human-facing planning material. Do not open, search, summarize or follow links into it unless the user explicitly requests work on the roadmap. Exclude it from broad searches with `-g '!ROADMAP.md'`; a README link does not authorize reading it. Use `.codex/PROJECT.md` and the selected package contracts for implementation scope; roadmap entries never authorize execution.
- For explicit prompt work, resolve only the selected ID and its stated prerequisites/references; do not ingest the rest of the folder. Reading a prompt does not authorize execution. Implement only the explicitly invoked package; verify and hand off before starting its dependents.
- Use [.codex/PROJECT.md](.codex/PROJECT.md) for current state and subsystem links, and [.codex/WORKFLOW.md](.codex/WORKFLOW.md) for package boundaries, handoffs and timing. Use a fresh chat at phase boundaries or after a completed package if needed.

## Delegation

- Main agent: `gpt-6.1-sol` medium, shared contracts, integration and final checks. Use `economy_researcher` (`gpt-6.1-terra` low) for researching and fleshing out ideas or bounded read-only review, and `economy_worker` (`gpt-6.1-sol` low) for implementation. For substantial independent work, delegate using these project profiles with a maximum concurrency of five subagent threads per session. Routine work stays with the main agent.
- Establish shared interfaces first. Assign narrow acceptance criteria, explicit disjoint write ownership or read-only boundaries, and allowed checks. Only the main agent delegates. One owner runs Gradle/game tasks sequentially.
- Respect the user's settings and task-specific check limits. Do not silently substitute unavailable models.

## Verification scope

- These rules apply project-wide to every current and future module, phase, package and sub-task, including delegated work. Carry them into new prompts, templates, module instructions and worker assignments. Do not reintroduce automatic test runs or full-suite completion gates in those files; older conflicting checklists are superseded.
- For routine changes, run at most one targeted check if it adds confidence. For prose-only changes, review the diff.
- Select named test classes for changed behavior; do not run whole packages or the full suite for each sub-task. Reuse prior evidence for unchanged behavior.
- Run tests sparingly. No test run is required merely to mark a sub-task complete. Before a full-suite run, state the concrete reason and why narrower checks are insufficient.
- Add tests only for meaningful behavior or a concrete regression. Prefer existing real-service/SQLite fixtures; do not add mock-heavy tests, implementation-mirroring assertions or duplicate coverage to satisfy a checklist.
- Run required delivery checks once. If a check fails, fix the issue and rerun only the affected check. Historical package commands and broad prompt checklists do not override this scope.
