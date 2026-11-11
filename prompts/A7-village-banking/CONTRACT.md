# A7 Village Banking — shared package contract

Status: A7-01 through A7-06 approved by the user for implementation on 2026-11-06, subject to documented prerequisites. A7-01 was subsequently selected and executed as recorded in the [handoff](../../.codex/A7/A7-01.md); A7-02 was subsequently explicitly selected and implemented as recorded in [its handoff](../../.codex/A7/A7-02.md); A7-03 was subsequently selected and completed as recorded in [its handoff](../../.codex/A7/A7-03.md); this approval record alone selects no later package. Execute one explicitly invoked package, verify its scope and hand off before starting a dependent package. Approval is not implementation or verification.

## Authoritative scope

Use [approved A7 policies](../../.codex/architecture/planned-a7-a10.md), [A7-01 planning](../../.codex/A7/architecture/a7-feasibility-plan.md), [source research](../../.codex/A7/architecture/a7-generation-research.md) and [template inventory](../../.codex/A7/architecture/a7-template-inventory.md). Approved policies override older unconditional bank-placement language. The inventory is now content-audited classification revision 1, not installed production code. [Frozen shared contracts](../../.codex/A7/architecture/a7-shared-contracts.md) supersede the original planning proposals. Do not read ROADMAP.md or unrelated prompts.

- Only newly generated, non-abandoned vanilla villages in the five approved styles. Zero qualifying houses/workplaces means no bank. Count houses/workplaces before banks; exclude farms, wells, decorations, roads, pens and stables.
- Small 1–8: one compact branch. Medium 9–16: one larger, 25% optional compact. Large 17+: two larger, 50% optional compact. Each village uses distinct institutions, with a primary-eligible server-currency provider and the approved foreign-provider preference/fallback.
- Missing required providers means generation not ready, without stopping vanilla play or disabling existing banks. A selected optional branch lacking a distinct eligible provider may be omitted with a persisted reason. Terrain failure instead retains a pending branch plan.
- Placement order: interior; nearby edge within 32 blocks; farther site within 96 blocks; then pending with a reported reason. Distances are outward from the original village boundary. Allow foundations/modest leveling, initially limited to 6 blocks foundation depth and 3 blocks leveling, subject to validation. Preserve existing buildings, farms, roads and player construction. Paths may include stairs/small bridges. Pending terrain retries require an operator and fresh checks.
- Choices are deterministic from seed/location and captured once. Existing branches retain institution/native currency after denomination changes. Branch creation creates no customer accounts, capital or money.
- Compact: one kiosk, desk and banker. Larger: two of each. Services remain those supported by existing institution/account policies. Bank buildings and employees are presentation/access; their loss never changes accounts, money or obligations. No automatic rebuilding or replacement of removed building blocks/kiosks/desks.
- Banker behavior resembles villagers without trading. Explicit selectable questions request explanations; menu routing requires fresh kiosk-grade server authorization. Talking is optional and never a prerequisite for direct kiosk account opening.
- Banker replacement: 24,000 running-server ticks; saved progress survives restarts, offline time and sleep do not advance it. An unloaded banker is not missing. Require a loaded active branch and valid vacant desk, deduplicate replacements, enforce staffing caps. Operator pause freezes progress; resume retains checks.
- Preserve narrow kiosks and registered IDs/bindings. Owner/operator settings offer a free explicit wider-kiosk upgrade with clear-space/support checks and safe failure.
- Original/style-matching bank templates include institution signs, lighting and furnishings. Assets must be original or appropriately licensed. No specific external integration mods are added.

## Shared interfaces and ownership

A7-01 freezes versioned detached village plans, branch IDs/slots, placement progress, service/workstation anchors, bootstrap requests/results and staffing assignments before dependent implementation. Proposed names in planning documents are not existing APIs. Separate generation-local serialization and server-thread physical effects from worker-owned journal/storage work. Never block generation on JDBC, trust client/NPC appearance as authority, or add a parallel financial store.

Recover interrupted initial placement separately from terrain-pending operator retries. Persist enough evidence to distinguish interrupted placement from later player dismantling. Retain original operation IDs and resolve uncertain outcomes before resubmission. Loaded-only integrity and existing custody/revision protections remain authoritative.

Main agent owns shared interfaces, integration and checks. Delegate only substantial independent work using the project profiles and explicit disjoint file ownership/read-only boundaries; workers are not alone and must preserve others' edits. One owner runs Gradle/game tasks sequentially.

## Verification and handoff — all packages

Follow [VERIFICATION.md](../../.codex/VERIFICATION.md). Prose-only work uses diff/link review. Tests are optional unless a meaningful changed invariant or concrete concern requires one: at most one focused invocation, normally one to three named classes, with real-service/SQLite fixtures. Do not automatically run full packages, unfiltered tests or full suites. A full suite requires explicit user request or a stated concrete concern narrower checks cannot address.

For an ordinary runnable delivery, run once: `./gradlew build -x test`, `./gradlew help --task runClient`, `./gradlew runClient --dry-run`. Fix failures and rerun only affected checks. Keep Minecraft 1.21.1, NeoForge 21.1.252, Java 21, working runClient/runServer, registered main source set and development runtime dependencies. Game/generation evidence must use disposable worlds, never player worlds; do not accept the EULA. Preserve existing code/worlds/backups and historical migrations/evidence.

Each completed selected package updates `.codex/PROJECT.md`, a short `.codex/<ID>.md` handoff, affected current architecture and scoped `.codex/verification/<ID>.md` evidence. Report actual checks and limits; do not mark dependents complete or execute them. Keep README user-facing; update the preserved root changelog only for usable release/user-facing changes.
