# Server guide

[Back to README](../README.md) · [Player guide](PLAYER_GUIDE.md)

No additional mods are required; SQLite JDBC is bundled. Each world stores its economy database and identity under `<world>/economy/` and must be owned by a single Minecraft server.

## First startup

New worlds initialize automatically and offer the first connected player a world-currency chooser. Use `/economy choose CAD` to select a currency, with tab completion and lowercase support. Read the upgrade procedure below before opening an existing economy world with a newer schema.

## Scheduling configuration

Create `config/economy-scheduling.properties` in the Minecraft instance directory to override settings. Use one `name=value` entry per line and restart the server to apply changes. Missing entries use the defaults below. Invalid values, unknown keys, or unreadable files leave the economy unavailable and log a configuration error. Storage uses one worker and one SQLite connection.

| Setting | Default | Accepted range |
| --- | ---: | ---: |
| `calculationWorkers` | 2 | 1–16 |
| `storageQueueCapacity` | 256 tasks | 1–4096 |
| `calculationQueueCapacity` | 64 tasks | 1–4096 |
| `callbackBudgetMillis` | 1 ms per tick | 1–10 ms |
| `sqliteLockTimeoutMillis` | 5000 ms | 1–60000 ms |
| `drainSeconds` | 30 s | 1–300 s |

The callback budget limits when the server starts additional callbacks in a tick. A callback that has already started runs to completion. The drain setting controls how long economy waits for its workers to shut down.

Internal GUI identifiers and journal details are hidden by default. Set `debugGui=true` in the same configuration file and restart the server when those diagnostics are needed.

## Status and diagnostics

Use `/economy status` to check availability and startup or catch-up progress. The server console and permission-level 2 operators can also run `/economy diagnostics` for storage and journal checkpoints, queue waits, possible stalls, and the most recent failure code. Storage checkpoints reflect the latest completed sample. Economic rates describe observed progress and are shown only when measurable. Detailed diagnostics also show the latest FX sample, including selection status, the committed day, backlog, and effective and pending policy revisions. During shutdown, check the server log for unresolved work.

Detailed diagnostics include the financial calendar’s UTC origin, elapsed whole days/months/years, retained clock target, next boundaries and catch-up state. `/economy diagnostics calendar` shows policy versions and active work in pages of eight registry entries; follow the printed `after` command for the next page. Reports use their displayed sample timestamp. Day/month/year values count completed periods from zero. Fixed-interval backlog is separate from the domain pending-event indicator, which is not a total debt or obligation count. These commands are read-only and remain available from cached samples while financial work is catching up.

## FX policy

After a world has an opening FX publication, in-game operators with permission level 2 can use
`/economy fx policy <operation-uuid> <currency> <baseline> <variation-ppm> <pull-ppm> <bounds-ppm>`.
Enter the baseline in permanent-reference units per major unit of the selected currency.
The edit takes effect at the next UTC midnight. Variation and pull each accept 0–1,000,000 ppm.
Bounds accept 0–999,999 ppm, with the resulting band limited to 0.000000000001–1,000,000.
For an uncertain result, repeat the same command with the same operation UUID.

## Wider kiosks and legacy upgrades

New branch kiosks occupy two blocks across and two blocks high. Existing narrow kiosks keep their original footprint when loaded. Owners or operators can sneak-right-click with an empty hand, open settings and select **Upgrade kiosk**. The upgrade is free and preserves the kiosk's bank, native currency and placement identity.

The extra column must be loaded, empty, supported and clear of entities. Protected/blocked space or unfinished cash/custody work prevents the upgrade. A blocked preflight leaves the narrow kiosk working. Successful upgrades invalidate stale banking sessions; reopen the kiosk after completion.

An interrupted upgrade can briefly disable service while its saved outcome is checked. Complete narrow or wide geometry can reconcile without adding blocks. A partial footprint stays disabled and is never filled or rebuilt automatically; an operator must restore a complete original or wide footprint before reconciliation can finish. Removing hardware never changes customer accounts, balances or debts.

## Village branch diagnostics

Use `/locate structure #economy:banks` or `/locate structure economy:bank` to find bank structure locations through Minecraft's normal structure search, including unexplored areas. Choose the world's currency first so new villages can capture their bank plans. Native coordinates identify the village start chunk; the bank is nearby. Villages without a reserved bank site are excluded. Existing placed banks gain this structure index when their source village chunks load and reconcile. As with vanilla structures, an indexed location can remain after demolition; a locate result does not guarantee active banking service.

Operators can find nearby branded branch kiosks with `/locate poi economy:bank` or `/locate poi #economy:banks`. Vanilla POI search covers 256 blocks in the current dimension and uses indexed, already discovered kiosks; it does not search unexplored terrain for new bank buildings. Generated and player-placed branded kiosks qualify, including legacy narrow forms. Neutral kiosks and satellite cells do not. Existing kiosks enter the index when their chunks load. A result identifies physical hardware, not a guarantee that banking service is active.

New eligible vanilla plains, desert, savanna, snowy and taiga villages can generate bank branches. Original houses and workplaces determine branch eligibility and capacity; abandoned and empty villages receive none. Existing explored villages are not retrofitted. The mid-century modern buildings have institution signs, local wood, lighting, furnishings and working kiosks. New branch templates include original two-wide banker desks in local wood. Active branches can staff only their captured desks; player-placed desks remain unbound and do not create bankers or grant banking access. Older vanilla desk furniture is preserved and is not automatically converted.

Branch counts use the village's original qualifying houses and workplaces. Farms, roads, decorations, wells, pens and stables do not add to that count.

| Qualifying buildings | Required branches | Optional compact branch |
| --- | --- | --- |
| 1–8 | One compact | None |
| 9–16 | One larger | 25% chance |
| 17 or more | Two larger | 50% chance |

A compact branch has one kiosk, desk and banker slot. A larger branch has two of each. Each village uses distinct banks. Its first branch needs an eligible bank in the world's selected currency. Choices are retained after a later world-currency change.

Supported generation covers the five vanilla village styles in the Overworld. Modified village resources can prevent new bank generation when their layout cannot be safely recognized. Superflat village/bank generation has a known unresolved limitation tracked in [issue #3](https://github.com/emilybelnavis/economy/issues/3). Ordinary world generation and existing banks remain usable while a new bank plan is waiting for an eligible provider.

Terrain can leave a selected branch pending. Generation searches inside the village, nearby edges within 32 blocks, then connected sites within 96 blocks, with bounded foundations and leveling. Banking remains disabled until physical placement and trusted hardware activation complete. These commands require an authenticated operator or the server console:

- `/economy branch plans [after <village-id>]` and `/economy branch list [after <branch-id>]`: pages of up to 16 records.
- `/economy branch status <branch-id>`: captured bank/currency, plan revisions, service generations, loaded/unknown observations, staffing state and recent operation history.
- `/economy branch staffing <branch-id> pause|resume`: persist staffing pause state. Pausing prevents new hires and replacement progress; it does not remove an existing banker. Resuming retains already earned replacement time.
- `/economy branch retry <branch-id> <expected-revision>`: explicitly retry missing required-provider selection or a pending terrain site while retaining captured bank/currency choices. Terrain retry uses already loaded chunks, supported empty space and fresh obstruction checks; load the area before retrying. It does not clear later construction or force chunks to generate. Removed, conflicted or interrupted partial buildings cannot be rebuilt with this command.

Read the state and reason together in branch status. `PROVIDER_PENDING` means a required branch is waiting for an eligible bank. `OMITTED` with `NOT_SELECTED` means the optional branch was not selected, while `NO_DISTINCT_PROVIDER` means no additional eligible bank was available for it. `SITE_PENDING` retains a selected bank whose terrain placement is waiting. An omitted optional branch is not a terrain failure.

Before retrying, use `/economy branch list` to find the branch ID, inspect its status, load the intended area and copy the current revision into `/economy branch retry <branch-id> <expected-revision>`. If the revision has changed, inspect status again. Keep the operation ID if the outcome is uncertain and check status before making another attempt.

Branch records and generated hardware never create customer accounts, capital or money. Keep the operation ID shown by a write command if its outcome is uncertain; do not assume it failed. Dismantling hardware never closes accounts or changes debts.

## Banker desks and staffing

Bankers belong to a branch and a captured desk slot, rather than to a nearby building or a workstation’s appearance. A desk occupies two blocks across and two blocks high, needs support beneath both lower cells, and must remain intact. Breaking any cell removes the desk with one item drop in Survival and none in Creative. Replacing it manually does not restore its former branch assignment.

Right-click an active kiosk to see its bank-specific account options, independently of banker dialogue or staffing. Review the bank and native currency before selecting **Open primary account** or **Open \<native currency\> account** when offered. Selecting either button submits the request immediately. The bank’s eligibility checks still apply. Removing a branch, kiosk, desk or banker leaves existing customer accounts, balances and debts intact.

Right-click a banker within six blocks for a private dialogue. It opens with explicit questions about account opening, eligibility, the institution, native currency and how banking works. Select a question to see its answer. **Open banking services** routes to the usual banking menu when you are also close enough to an available branch kiosk. It applies the same account permissions and available actions as opening the kiosk directly. If the banker, assigned desk or service becomes unavailable, reopen the dialogue after it is available again.

A positively observed banker death leaves the desk vacant. Replacement requires 24,000 eligible loaded ticks (about 20 minutes at 20 ticks per second), an active unpaused branch, an intact assigned desk and a clear safe staffing location. Unloaded chunks, missing entity observations and uncertain interrupted spawns do not count as death or authorize another employee. Removing a desk stops new staffing for that assignment. Nothing forces chunks to stay loaded.

Offline time and skipping the night do not advance the replacement wait. Replacement time is saved in batches of at most 200 ticks. A crash can lose up to 200 ticks of recently earned time; it does not complete the wait early. Pause/resume and normal restart retain saved progress. Use the branch status and staffing controls above when investigating a vacant or uncertain slot; there is no automatic desk repair or legacy-furniture upgrade.

## Schema upgrades

New worlds initialize automatically. Worlds on schemas 1 through 49 require a validated complete-world backup before upgrading to schema 50. Prepare it while Minecraft is fully stopped:

```sh
./gradlew prepareEconomyUpgrade -PeconomyWorld=/absolute/path/to/world
```

Backups are placed beside the world in `<world-name>-economy-upgrade-backups/`. Then start Minecraft normally. Missing or changed backups prevent upgrades; they do not erase data. The utility refuses a world whose Minecraft or economy ownership lock is held. It does not copy a running world.

## Complete-world backup and restore

Stop Minecraft normally and wait for the log message `Economy complete-world clean shutdown recorded`. Then create a backup:

```sh
./gradlew backupEconomyWorld -PeconomyWorld=/absolute/path/to/world
```

Backups include the world and economy together, with checksums, version/identity information, journal position, and economic checkpoint. They appear under the sibling `<world-name>-economy-upgrade-backups/<backup-id>/` directory.

Restore into a **new, separate directory**:

```sh
./gradlew restoreEconomyWorld -PeconomyBackup=/absolute/path/to/backup-id -PeconomyRestoreDestination=/absolute/path/to/new-world
```

The restore command validates the backup and refuses an existing destination. It preserves the current world; switching to the restored copy is a separate operator action. Restore supports clean schema 4 through 50 backups in this mod’s backup format. A restored world on an older schema still requires a pre-upgrade backup.

There is no online backup, automatic replacement, or retention/deletion service. Operators own backup storage and retention. Failed restores can leave a hidden staging directory without publishing the destination.

Economy requests become available after startup recovery and required catch-up. Ordinary Minecraft can continue during that work. With an economic system registered, ongoing catch-up processes UTC intervals in chronological order and gates affected writes until current. Sleep and `/time` do not advance economic time. Daily FX starts at the UTC midnight after the world’s opening publication. A fresh world offers the first connected player a denomination chooser.

Legacy economy databases are not imported. Incompatible, corrupt, or mismatched identity data makes the economy unavailable while ordinary Minecraft play can continue. Preserve the files and inspect the logged error rather than deleting them.

Storage requires filesystem support for exclusive file locks, atomic file publication, and directory synchronization. These checks have been exercised on macOS; other platforms have not been runtime-tested.

## Server integrations

Server integrations can consume `ca.emilybelnavis.mcmods.economy.api.BankingIntegration` version 1 through the server ledger service. It exposes versioned prime, owner valuation and fee quotes, physically authorized credit payment entry points and fixed/variable/secured capability reporting. Financial authority and existing journal/custody rules apply to integrations. A village structure, desk, banker or branded appearance does not issue an integration grant. Use authenticated server context and the existing account and physical-service checks. The interface declares `VERSION = 1` and reports it through `version()`. [BankingIntegration source](../src/main/java/ca/emilybelnavis/mcmods/economy/api/BankingIntegration.java).

See the [server integration contract](INTEGRATION_API.md) for entry points, version boundaries and downstream consumers.

## Financial inspection commands

Player operators can inspect a selected owner's facility with `/economy credit inspect <owner-uuid> <facility-uuid> daily`, `payments` or `events`. Each operator page is audited. Use the reported `after <sequence> <position>` cursor for more records; payment allocations also accept an offset after that cursor. Reconciliation says incomplete if its bounded portfolio or journal window is exceeded. These commands provide no balance or limit editing.

Player-backed operators can use `/economy loan inspect <owner-uuid> <loan-uuid>` and `/economy loan inspect_application <owner-uuid> <application-uuid>`. Each inspection is audited; event pages show their next sequence/position cursor. Views include captured underwriting and funding, payment totals, bounded reconciliation and catch-up progress. These commands provide no financial editing or borrowing activation. If repayment reports accounting unavailable, an operator can use the inspection view to check claim reconciliation and portfolio completeness.

Player-backed operators can use `/economy debt inspect <card|loan> <owner-uuid> <facility-uuid>` for audited original obligations, precise clock reasons, reconciliation and provider progress. Operators can inspect report revisions with `/economy debt inspect_reports <owner-uuid>` and collection receipts with `/economy debt inspect_history <owner-uuid> <case-uuid>`. Each page is audited. The view shows installed scoring or catch-up status and the owner’s next score boundary.

Player operators with permission level 2 can run `/economy ledger reconcile <owner-uuid> <account-uuid>` to compare the current-currency balance with up to 63 journal postings. Larger histories return a limit message.

## Economic simulation and trading

A financial day is 1,200,000 real milliseconds. On a ready world with a selected currency, the installed source manifest schedules activation A strictly beyond observed/processed time. The first shared prime/FX event is F=A+one day; it uses only the complete observations since A. Old daily FX retires prospectively, preserving its history. Missing input blocks new work with an unavailable reason. `/economy macro status` reports cached mode, coverage/baseline counts, rate age, A/F/H, revisions and limits; it never advances rates.

Optional `config/economy-simulation.properties` is read once per lifecycle. Unknown keys, invalid booleans, invalid weight sums and out-of-range values reject configuration. Defaults:

```properties
rates=true
traders=true
cadPerEmerald=1
dailyFloor=1000
capacityFactor=0.01
primeWeights=1/3,1/3,1/3
fxWeights=1/2,1/2
```

These initial settings are captured immutably with the production manifest. After installation, use future policy controls for model/rate changes; changing the captured initial file produces an explicit configuration conflict. Turning off initial rate activation does not erase an already saved activation or its obligations. Model configuration uses bounded rational values; the positive daily floor is at most1,000,000,000, capacity factor is0..1, and CAD/emerald is positive and at most1,000,000. Currency membership, financial-day length and rate caps stay fixed.

Connected operator players can use:

- `/economy macro status`
- `/economy macro history <beforeFinancialDay>` — up to16 saved outcomes, exclusive day-index cursor; use9223372036854775807 for the newest page.
- `/economy macro pause <effectiveEpochMillis>`
- `/economy macro resume <effectiveEpochMillis>`
- `/economy macro manual <effectiveEpochMillis> <json>` — JSON contains exactly `primeDeltas` and `fxMultipliers`, each with all 11 uppercase currency keys. APR deltas are fractions (0.001 is0.10 percentage points); FX multipliers are normalized to the permanent reference. Requests must meet all absolute, per-step and rounded ordered-pair bounds. Invalid requests reject without clamping.
- `/economy macro config <effectiveEpochMillis> <json>` — schedules a PAUSED configuration; JSON must contain all four string fields `dailyFloor`, `capacityFactor`, `primeWeights`, `fxWeights`. Resume separately at a later boundary.

All writes append at strictly future financial boundaries beyond every wall/high-water/processed frontier. At most16 policies may be pending. Manual scheduling requires an actually completed paused/manual rate event, not just a future pause row. It validates the immediately preceding pending sequence, executes once and then holds. Resume appends after pending changes and uses current observations without deferred automatic moves. Operator UUIDs are retained in journal outcomes. Legacy global-prime/daily-FX editors do not override an installed takeover.

Trading replaces native checkout for ordinary villagers and wandering traders when enabled. Banker guides are excluded. Choose rail, account/cash currency and quantity explicitly, then request and confirm a quote. Foreign quotes show principal, conversion and issuer fee. No account fallback, implicit credit or ground-drop fallback is used. Unsupported catalogs, incomplete comparison cohorts, unavailable amounts, insufficient stock or capacity show a controlled refusal. A market comparison is bounded to32 saved traders and1,024 detached entries; equivalent comparisons must fit the catalog's32-entry cohort limit. Accounts remain limited by the existing per-player catalog.

Claims retain committed goods/cash and independently witnessed original-input returns. A failed or cancelled untouched quote closes the original financial intent before releasing its marked cash locks. A saved held-input rejection uses an explicit return claim. Uncertain HELD/delivery/progression after disconnect or restart requires the existing custody/operator reconciliation process; do not manually duplicate items or repeat the payment. Village restock earns only loaded eligible work ticks, and wandering traders never restock. No offline/sleep tick credit is invented.

Storage schema 50 also retains item reference base snapshots; upgrades retain the stopped complete-world backup requirement. Keep world and economy files together.

### A8 acceptance and operational limits

The delivered A8 scope connects completed merchant/NPC activity to immutable observation days and a coherent currency-prime/FX basket. Prices and rates are gameplay policies, not a calibrated economic forecast. A financial day is 20 real minutes: a seven-day window is seven financial days, not a real week. Missing coverage stops the affected new work; it does not become zero activity. Last captured fixed-loan terms are preserved.

Acceptance combines a real merchant-to-rate SQLite witness with the earlier real client foreign-cash checkout, native guard and independent save evidence. It does not establish every seed, third-party catalog, multiplayer load, operating system, abrupt process-termination or XP-orb persistence case. Bounded caches/queues can refuse admission; the suite is not a benchmark or an exactly-once guarantee across Minecraft files and SQLite. Account/foreign backend cases and normal reopen/replay evidence are separate from the physical client witness.

Clear output capacity before quoting. If exact cash change or a rounded positive payout cannot be represented, choose a supported larger quantity or another explicit rail/currency; no rounding shortcut or account fallback is used. Keep saved worlds and economy data together. Ambiguous interrupted item/progression attempts remain for reconciliation instead of obtaining another execution capability. Source replacement/recipe valuation belongs to a separately selected A9 adapter; the current temporary emerald source is retained.

[Acceptance evidence](../.codex/verification/A8-08.md) and [A9 downstream boundary](../.codex/architecture/a8-downstream.md) distinguish demonstrated behavior, reused evidence and future work.


## Item reference configuration

The A9 foundation loads 349 approved vanilla base anchors, captures ordinary crafting/stonecutting/cooking recipes and preserves the full vanilla classification inventory. A9-03-02 also values supported vanilla potion identities through captured brewing paths. A9-03-03 adds supported component variants and bounded container contents. A9-04 exposes authenticated item-reference reads and typed provider registration; villager/trader price replacement remains pending. Item reference costs remain separate from financial qualification.

Optional `config/economy-valuation.json` supplies plain-item bases and overrides. A missing file uses built-in defaults. For example:

```json
{
  "bases": [],
  "overrides": [
    {"item": "minecraft:diamond", "currency": "CAD", "amount": "301", "referenceCount": 1}
  ]
}
```

Use registered item IDs, uppercase currency codes, positive decimal amount strings with at most 12 decimal places, and reference counts from 1 to 1,000,000. Duplicate entries within a layer, unknown fields, operator-only item prices and unregistered mod-item contributions reject the candidate. An override takes precedence over a base; component-bearing items require a supported typed adapter. Missing prices never become zero or a floor-price fallback. Reference values publish HALF_UP unit prices with a one-minor-unit minimum, then multiply by quantity; water is a typed free input only.

Restart or use vanilla `/reload` after editing the file. A valid candidate becomes active only after durable publication. Invalid configuration is left untouched and the prior durable snapshot is retained; fresh valuation stays unavailable until a valid matching reload. Look for `VALUATION_SNAPSHOT_READY` or `VALUATION_RELOAD_REJECTED` in the server log. Valuation readiness currently has no effect on the existing trader price source.

Ordinary recipe costs use exact ingredient/output quantities and the cheapest supported acyclic path. Sticks use two oak planks per four sticks regardless of crafting origin. Named refined metals and cooked foods use their calibrated raw input divided by 0.75, once; this canonical rule prevents cheaper intact ores from bypassing the approved refined values. Explicit overrides still take precedence. Other cooking adds no generic fuel/time fee. Unsupported dynamic recipes, returned containers and unregistered mod items are not inferred as free or guessed prices. A search limit makes a value unavailable rather than returning a partial minimum.

Brewing reference costs use the prior per-potion cost plus one-third of the shared ingredient and one-sixtieth of blaze powder per step. Water content is free; the potion bottle is counted once. Dragon breath contributes its collected content and its own shared ingredient bottle, with no returned-bottle credit. Prices use a standard three-potion batch regardless of actual stand occupancy. Known effect, strength, duration and form identities remain distinct. Extra/custom brewing registrations or changed vanilla mix lists require a supported adapter; affected potion values are unavailable rather than inferred by running custom recipe code.

Snapshot history and incomplete staging are retained without automatic deletion. A manifest is capped at 32 MiB and retained canonical data at 256 MiB, including incomplete unpublished stages; quota exhaustion rejects new publication. Compiled history and result caches are bounded independently. Keep existing complete-world backups and follow the schema-upgrade procedure before opening an older world.

Supported equipment adds compatible enchantment premiums, applies 25% per distinct curse, then multiplies by remaining durability. Names add no price and preserve other state. Stored books use book plus stored premiums. Containers add their individually modified shell and each published child unit times count; potion bottles are already included. Missing or unsupported child state makes the whole container unavailable. Limits include eight container levels and 4,096 children. Unknown components/custom data are not discarded. Native text supports plain literals up to 16,384 characters and books up to 100 pages; custom entity state requires an adapter.

Optional `enchantmentPremiums` in the item-valuation config supplies a complete positive CAD list for a supported beneficial enchantment, for example `"enchantmentPremiums": {"minecraft:sharpness": ["20", "45", "80", "110", "150"]}`. Unknown enchantments, curse overrides, incorrect level counts and invalid amounts reject the whole candidate. Reload creates a new immutable snapshot while original values remain reproducible.

### Item reference diagnostics

Connected operator players can use `/economy valuation status`, `/economy valuation coverage`, `/economy valuation value minecraft:diamond USD` and `/economy valuation history 8`. The value command reads one unmodified item (`{}` component identity), defaults to CAD when currency is omitted and reports typed availability, published unit money and bounded provenance identifiers. Integrations use the exact-component API for supported stateful identities. History accepts 1–16 rows. The per-server diagnostic queue admits at most 16 pending reads; a disconnected/demoted player or replaced session receives no stale result. These commands neither change prices nor enable listings.

Coverage distinguishes all 1,333 classified vanilla identities (349 base, 760 derived, 109 variant and 115 intentionally operator-unpriced), supported component states and unregistered mod items. Registry classification is not a promise of a numeric value for every component permutation, custom recipe or exhausted-budget case. Operator diagnostics and the API retain reasons; unsupported ordinary trader listings remain disabled without exposing a valuation explanation to players. Generic integrations must explicitly register their mod identities; there are no built-in mod prices or automatic mod-recipe guesses.

### Prospective trade-source support

A9-05 installs schema51 support for source epochs, reference account/cash receipts and retained carry-retirement history. Use the existing complete-world backup procedure before a schema upgrade. Existing EmeraldV1 trader pricing remains selected; this package adds no activation command or startup switch.

After a separately enabled scope exists, valid valuation reloads advance its source epoch atomically with the valuation snapshot. Uncommitted old-source quotes refuse; committed originals retain their original receipts. Old fractional carry is recorded as retired without erasure or payout. New reference goods prices use rounded units without carry, and unsupported exact components or unproved rounded bid/ask gaps remain unavailable. Initial production activation and player presentation belong to A9-06.
