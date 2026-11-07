# Planned A7–A10 scope

A7 is accepted in the bounded scope recorded by [A7-06](../A7-06.md). A8–A10 remain planning material and require explicit selection. This document does not select an implementation prompt. Preserve existing worlds, accounts, balances and historical evidence.

## Sequence

| Phase | Scope |
| --- | --- |
| A7 | Village Banking Package |
| A8 | Economic simulation, formerly A7 |
| A9 | Basic item valuation |
| A10 | Core integration, carrying forward former A8 player experience and broader playtesting |

B2 securities, B4 trading and B6 expanded lending keep their own identities and prerequisites. Renumber current planning references; retain historical evidence as written.

## A7 — Village Banking Package

[A7-01 feasibility outcome](a7-feasibility-plan.md) and [revision-1 shared contracts](a7-shared-contracts.md) now freeze the source/API-backed hook, geometry and interfaces. [Evidence](../verification/A7-01.md) distinguishes completed contract/API work from future actual generation/terrain acceptance. Production generation, trusted kiosk activation, staffing and optional dialogue are delivered. [Current downstream contracts](a7-downstream.md) and [acceptance evidence](../verification/A7-06.md) bind the approved policies below to the implementation and its limits.

Target newly generated vanilla villages only. Retrofitting explored villages, player-built settlements and modded villages requires separate compatibility rules. Never overwrite player construction.

Approved village-style scope: support plains, desert, savanna, snowy and taiga vanilla villages. Bank buildings must match their village's architectural style.

Approved abandoned-village policy: abandoned (zombie) villages receive no A7 content—no bank buildings, paths, hardware, desks or bankers. Exclude abandoned starts before bank planning; the branch guarantees apply only to targeted non-abandoned villages.

Approved sizing rule: count only houses and workplaces, before any bank is placed. Farms, wells, decorations, roads, animal pens and stables do not count. The resulting building count determines village size: small: 1–8 buildings; medium: 9–16 buildings; large: 17 or more buildings. Physical footprint does not determine the size category. Bank placement must not feed back into this count.

Approved zero-count policy: a non-abandoned village with zero qualifying houses/workplaces receives no bank. It falls below the small-village threshold and is excluded from bank planning and the branch requirements.

| Size (approved building count) | Presence | Currency/institution policy |
| --- | --- | --- |
| Small (1–8) | One compact branch | Guaranteed server-currency Tier1/hybrid institution eligible for normal primary-account opening |
| Medium (9–16) | One larger branch; 25% chance of a second compact branch | Guaranteed local branch; possible eligible foreign institution |
| Large (17+) | Two guaranteed larger branches from distinct institutions; 50% chance of a third compact branch from a distinct institution | Guaranteed local branch and at least one foreign institution when eligible |

Select from the existing 23 institutions with fixed native currencies and eligibility; never randomly change their currencies. Every targeted village must receive a persistent plan for its selected branches. Placement may remain pending when no safe site exists under the approved search order below; a plan is not a completed bank. Weighted pool entries alone cannot satisfy this policy.

Approved institution-selection rule: every bank within a village belongs to a different institution, including the optional second bank in medium villages. Prefer eligible foreign-currency institutions for additional branches; large villages retain the requirement for at least one foreign institution when eligible. Approved foreign-provider fallback: when no eligible foreign institution exists, select another eligible institution in the server currency, preserving distinct institutions within the village. Never change an institution's native currency to fill a slot.

Approved configuration-readiness policy: report A7 generation as not ready when no eligible local institution exists or too few distinct eligible institutions exist to meet the village's required branch count. Existing banks keep working. Never silently omit required branches. Exact generation gating and recovery behavior must be defined in A7-01; this policy does not authorize blocking ordinary world generation or deleting village content.

Approved optional-provider exception: if an optional branch is selected but no distinct eligible institution remains after the approved foreign-provider fallback, omit only that optional branch and report the reason. Required branches retain the configuration-readiness policy. Record the optional selection and its omission so reloads do not reroll it. This exception concerns provider availability only; missing safe terrain still follows the pending-placement policy.

Approved server-currency change policy: existing branches retain their institution and native currency. Newly generated villages select their required local bank using the current server currency.

Approved placement order:

1. Try safe sites inside the village.
2. Try nearby village-edge sites with connecting paths, allowing foundations and modest terrain leveling.
3. Try somewhat farther sites with connecting paths.
4. If none works, retain a persistent pending branch plan and report the reason; do not silently omit branches or report them as placed.

Placement must preserve existing buildings, farms, roads and player construction, and have supported terrain. Approved initial terrain limits: up to 3 blocks of leveling and 6 blocks of foundation depth, pending terrain validation. Exact measurement rules must be defined before implementation. Approved search distances, measured outward from the original village boundary: nearby sites up to 32 blocks; farther fallback sites up to 96 blocks. The bank entrance must have a safe connecting path. Define the original boundary and the site-distance measurement precisely before implementation. This replaces an unconditional immediate-placement guarantee: every targeted village receives a bank plan, but terrain may prevent immediate placement.

Approved pending-placement recovery policy: retries are operator-triggered, not automatic. Each retry must recheck the current site and preserve later construction; an operator retry does not override placement protections. Retain the captured branch choices rather than rerolling them. Pending recovery must not rebuild banks players dismantled. This policy governs plans pending because no safe site was found; interrupted placement and hardware-activation recovery still need their separate idempotent design.

Before implementation, map vanilla village templates to the approved house/workplace counting rule, identify supported start templates within the five approved village styles, and settle layout timing and safe placement limits. Establish a deliberate generation strategy that measures layout and reserves or finds sites with street access, terrain support and collision checks before finalizing placement. Verify the approved placement/pending behavior across vanilla village types; silently skipping a selected branch fails the policy. Define the exact generation gating and recovery behavior for the approved configuration-readiness policy.

Persist branch identity separately from employees, with institution/catalog reference, dimension/location, template/size, service anchors and lifecycle revision. Approved determinism policy: branch placement and institution selection are deterministic from the world seed and village location; reloads must not reroll bank choices or create duplicates. Seed-stable selection and durable IDs must suppress duplicate branches and configuration across reloads and partial generation. Branch creation must not reseed institution capital or create customer money/accounts. Define recoverable placement/configuration activation because Minecraft world writes and ledger transactions are separate effects.

Current player-placed hardware starts unbound/disabled. Generated branches need an explicit trusted server bootstrap adapter through existing policy/journal services, with native-currency bindings and idempotent activation. Do not authorize banking from block appearance, NPC profession or client IDs. Reuse kiosk-grade live server checks for actor/session, reach, loaded placement/generation, institution, revisions, permissions and offered action. Preserve account caps, primary eligibility, specialist checks, quote consent and custody.

Bankers are villager-like NPCs with dedicated behavior and persistent branch/workstation assignment. Approved interaction policy: explanations are player-led; players must explicitly ask specific questions about account opening, eligibility, branch institution/currency or banking mechanics to receive the corresponding explanation. Do not automatically launch an explanation or require a guided dialogue sequence. Bankers can route customers into existing supported banking menus on request. Financial menu access requires the same server authorization as kiosks. Dialogue alone moves no money. Banker interaction is optional: players can go directly to a kiosk to open an account without speaking to a banker or completing dialogue. ATMs, tellers and kiosks work without bankers.

Approved dialogue interface: use a selectable question menu with explicit questions, such as “How do I open an account?” and “What currency does this bank use?”. Selecting a question requests its corresponding explanation; opening the menu does not start a guided explanation. Supported banking-menu access remains an explicit player request.

Approved banker behavior: use villager-like behavior with persistent branch/workstation assignment, but no trading. Exact work, wandering and rest scheduling must fit that behavior and the approved optional question-menu interaction; do not introduce merchant offers.

The banker job block is a two-wide, two-high, one-deep desk with a computer monitor and ledger. Define anchor/satellites, facing, support/collision, placement rollback, teardown and single-drop behavior. Staffing follows branch size and valid desk capacity. Replacement is bounded and persistent: only active branches with valid vacant workstations can replace missing employees; deduplicate pending replacements across death, unload and restart. Losing a desk suspends its job slot until reassignment. Define operator controls before implementation. Death/workstation loss never deletes the branch, accounts or money.

Approved staffing rule: each compact branch has one banker and one desk; each larger branch has two bankers and two desks. Each additional branch is staffed according to its own size. Active staffing remains limited by valid desk capacity.

Approved equipment policy: each compact branch has one full-service kiosk; each larger branch has two full-service kiosks. Both branch sizes offer the same supported banking services, subject to the existing institution, account and authorization policies. Larger branches provide more access points and staff rather than additional financial capabilities.

Approved player-modification and account-independence policy: bank buildings are presentation and physical service access points, not owners or prerequisites of player accounts. Players may dismantle bank buildings normally. Do not automatically rebuild removed building blocks or replace broken kiosks/desks. Losing a desk suspends its staffing slot. Damage, dismantling, staff loss or loss of branch access must not close, freeze, delete or otherwise alter a player's accounts, balances or financial obligations. Existing institution/account services remain authoritative, and players may use other authorized access points under the existing service policies. Banker replacement remains subject to the approved valid-desk and staffing rules.

Approved replacement policy: replace a missing banker after 24,000 game ticks while the server is running, only when the active branch has a valid vacant desk. Persist the timer across restarts; offline time does not count, and sleeping through the night does not shorten it. Replacement waits until the branch is loaded and its vacant desk is verified. An unloaded banker is not missing. Replacements must remain within the branch's approved staffing limit.

Approved operator controls: provide branch-status inspection and pause/resume for banker replacements. Pausing freezes elapsed replacement-timer progress; resuming continues from the saved progress, preserving branch staffing limits and valid-desk checks. It does not bypass replacement eligibility. Exact command syntax and scope remain to be specified.

Broaden the kiosk to a two-wide/two-high service console with a broad screen, lower counter, institution header, recessed base and substantial side panels readable from several angles. Preserve registered IDs and authoritative bindings. Approved compatibility policy: existing narrow kiosks remain functional; offer an explicit upgrade to the wider model only when the extra space is clear. Never automatically occupy an adjacent player block. Preserve loaded-only integrity, rotation, rollback, one survival drop and revision invalidation. Assets must be original or appropriately licensed.

Approved upgrade interaction: provide an explicit “Upgrade kiosk” action in kiosk settings for the owner/operator, with no material cost. Upgrade succeeds only when the extra space and support are valid. Preserve authoritative binding and reject stale access under the existing revision rules; failed upgrades must leave the original kiosk functional.

Approved path features: connecting paths may use stairs and small bridges. They remain subject to safe access, collision, support, terrain limits and preservation of existing structures/player construction. Define bridge spans, supports and stair geometry during technical planning.

Approved bank appearance: compact and larger branches include institution signs, lighting and interior furnishings, matching the approved local village architectural style. Exact layouts and original assets remain design work; decoration does not grant banking authority.

Implementation is organized into six independently selectable packages. See the [shared contract](../../prompts/A7-village-banking/CONTRACT.md) and [selection queue](../../prompts/A7-village-banking/EXECUTION.md). The user approved A7-01 through A7-06 for implementation on 2026-11-06, subject to their prerequisites; A7-01 was subsequently explicitly selected and completed in its recorded scope; approval alone selects no dependent package.

1. [A7-01](../../prompts/A7-village-banking/A7-01.md): shared contracts and generation feasibility.
2. [A7-02](../../prompts/A7-village-banking/A7-02.md): persistent branches and trusted activation.
3. [A7-03](../../prompts/A7-village-banking/A7-03.md): wider kiosks and legacy upgrades.
4. [A7-04](../../prompts/A7-village-banking/A7-04.md): bank templates, paths and village generation.
5. [A7-05](../../prompts/A7-village-banking/A7-05.md): desks, banker staffing and optional dialogue.
6. [A7-06](../../prompts/A7-village-banking/A7-06.md): integrated acceptance and documentation.

Prepare exact prerequisites when selecting a package. Generation and staffing consume the durable branch contract, not parallel financial services.

## A8 — Economic simulation

Planning began on 2026-11-07. The user selected a broader macroeconomy driving prime and FX from player/server economic activity only, with a separate prime rate for each currency. [A8 planning](a8-planning.md) records the source constraints, proposed package split and unresolved activity/formula policies. This explicitly expands the previous prime-only planning boundary; it does not approve an implementation or any particular FX driver/formula.

Retain bounded registered scheduling, versioned financial-day rates, historical FX implementations and captured fixed-loan terms. Initial configured prime and existing FX remain authoritative until explicitly selected adapters replace them prospectively. Synthetic cycles, random macro shocks, commodity backing and unrequested downstream integrations are outside the selected scope.

The user approved merchant sales, borrowing/repayment, FX exchanges and monetary issuance/retirement (operator and NPC origins separately recorded), and added monetary villager commerce. [Technical revision 1](a8-shared-contracts.md) and [12 package prompts](../../prompts/A8-economic-simulation/EXECUTION.md) are now prepared, including split attribution/model, markets/hooks, account/cash settlement and FX foundation/provider packages. The user approved technical revision 1 and all 12 packages on 2026-11-07, subject to prerequisites. A8-01 is complete as a source/document checkpoint ([handoff](../A8-01.md)); [A8-02-01](../A8-02-01.md) now delivers source/counter attribution. Other packages remain unselected; A8-02-02 is next. A7 bankers retain their guide role; B4 financial markets remain separate.

## A9 — Basic item valuation

Currencies remain fiat-like. Authorized one-time 28B institutional capital and operator-created cash/deposits remain separate from reference valuations. Banks spend finite funded cash; claims do not fund spending. Reference prices create no commodity reserve, guaranteed redemption, automatic trading, collateral eligibility or wealth qualification.

- Configurable base resource values: per-item values with explicit currency, exact monetary representation, validation and immutable configuration revisions. Iron/gold/diamond values do not make those items currency backing.
- Recipe-derived costs: per-output reference costs from supported inputs and output quantities. Set deterministic rules for ingredient alternatives, multiple recipes, reload/version, rounding and bounded work/depth. Detect cycles and unsupported/dynamic recipes; return explained unavailability rather than zero. Define container returns, byproducts, fuel/processing assumptions and reversible recipe handling before implementation.
- Currency and valuation APIs: immutable server results identifying item/quantity, supported component-sensitive identity, denomination, effective time and source/configuration/recipe revisions. Reuse existing money/FX contracts with captured conversion versions and rounding. Provide an integration SPI for separate mods; specific integrations require a request. Keep reference reads separate from settlement and financial qualification.
- Overrides and provenance: explicit item override, then configured base resource, then supported recipe derivation, otherwise unavailable. Preserve selected source and bounded derivation explanations. Overrides may intentionally break recipe consistency and must be identified. Reload invalidates affected cached values; historical consumers retain captured versions.

Suggested packages: A9-01 shared value/configuration/provenance contract; A9-02 bounded recipe derivation; A9-03 APIs/diagnostics and scoped acceptance. Finalize policies before implementation. Do not silently feed reference values into Tier2 wealth, collateral, FX, shop pricing or trading.

## A10 — Core integration

Carry forward banking explanations/navigation, window/GUI-scale review, external multiplayer/network conditions and combined borrowing-to-default-to-collection gameplay. Integrate branch discovery, banker availability, institution/currency explanations and valuation provenance. Reuse unchanged A6 evidence and retain explicit composite gameplay limits. Unsupported B-series capabilities remain unavailable.

## Verification

Apply the project verification policy to every package: [VERIFICATION.md](../VERIFICATION.md). Prose uses diff/link review only. Routine changes use at most one focused test invocation when useful, with named behavior tests and existing real-service/SQLite fixtures. No automatic test/full-suite phase gates. Use disposable worlds for necessary generation/placement/reload witnesses; never player worlds.

Ordinary runnable deliveries run `./gradlew build -x test`, `./gradlew help --task runClient` and `./gradlew runClient --dry-run` once. Keep Minecraft 1.21.1, NeoForge 21.1.252, Java 21, working client/server tasks, registered main source set and required development runtime dependencies. One owner runs Gradle/game checks sequentially. Full-suite checks require an explicit request or a concrete concern narrower checks cannot address.
