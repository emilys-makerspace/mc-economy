# Server guide

[Back to README](../README.md) · [Player guide](PLAYER_GUIDE.md)

No additional mods are required; SQLite JDBC is bundled. Each world stores its economy database and identity under `<world>/economy/` and must be owned by a single Minecraft server.

## First startup

New worlds initialize automatically and offer the first connected player a world-currency chooser. Use `/economy choose CAD` to select a currency, with tab completion and lowercase support. Only the current chooser lease holder can confirm this first selection; an operator command does not grant another player that lease. `/economy choose` is not a general command for changing an already selected world currency. Read the upgrade procedure below before opening an existing economy world with a newer schema.

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

Internal GUI diagnostics are hidden by default; authorized transaction details show their original transaction and operation references. Set `debugGui=true` in the same configuration file and restart the server when those diagnostics are needed.

## Status and diagnostics

If a placed terminal only flashes and disappears, check `/economy status`: placement is refused while Economy is unavailable or busy, without consuming the item. A `STORAGE_UPGRADE_REQUIRED` failure indicates an upgrade awaiting completion; `STORAGE_SCHEMA` can indicate a legacy or unrecognized database. Use the administrator upgrade review below to inspect supported formats. Existing data is preserved.

Use `/economy status` to check availability and startup or catch-up progress. The server console and permission-level 2 operators can also run `/economy diagnostics` for storage and journal checkpoints, queue waits, possible stalls, and the most recent failure code. Storage checkpoints reflect the latest completed sample. Economic rates describe observed progress and are shown only when measurable. Detailed diagnostics also show the latest FX sample, including selection status, the committed day, backlog, and effective and pending policy revisions. During shutdown, check the server log for unresolved work.

Detailed diagnostics include the financial calendar’s UTC origin, elapsed whole days/months/years, retained clock target, next boundaries and catch-up state. `/economy diagnostics calendar` shows policy versions and active work in pages of eight registry entries; follow the printed `after` command for the next page. Reports use their displayed sample timestamp. Day/month/year values count completed periods from zero. Fixed-interval backlog is separate from the domain pending-event indicator, which is not a total debt or obligation count. These commands are read-only and remain available from cached samples while financial work is catching up.

## FX policy

After a world has an opening FX publication, in-game operators with permission level 2 can use
`/economy fx policy <operation-uuid> <currency> <baseline> <variation-ppm> <pull-ppm> <bounds-ppm>`.
Enter the baseline in permanent-reference units per major unit of the selected currency.
The edit takes effect at the next UTC midnight. Variation and pull each accept 0–1,000,000 ppm.
Bounds accept 0–999,999 ppm, with the resulting band limited to 0.000000000001–1,000,000.
Review the exact currency and all four values in the command before submitting. This is a direct operator command, not a terminal screen confirmation. It requires the actual connected operator player; console automation and `/execute as` cannot supply that player's authority. At most16 edits can be pending. Disconnect, demotion, death or session replacement revokes queued authority for new effects. A committed original remains recoverable by the same operation UUID and identical command, even if the current pending policy changed. Do not invent another UUID to recover an uncertain outcome.

Once currency-prime activation or retirement of the legacy daily-FX system is configured, this legacy edit is refused with `FX_TAKEOVER_CONFIGURED`. Use the existing `/economy macro status` and future `pause`, `manual` or `config` paths described below. The refusal does not erase saved policies or permit editing the permanent reference. Core FX admission/refusal/retry messages are localized in UK English, Canadian French and French; technical diagnostic identifiers remain stable.

## Terminal administration and reviewed settings

Owners sneak-right-click their physical terminal with an empty hand. A connected permission-level2 operator can use `/economy admin config` while looking at a loaded terminal within five blocks; it opens the same existing terminal settings surface. `/economy admin binding` inspects the looked-at terminal within six blocks, returning at most six lines of scope, placement, geometry, bank/native currency, cash mode and blocking-work guidance. It neither changes configuration nor edits an account. The separate global admin GUI remains deferred.

| Existing surface/path | Authority and action |
| --- | --- |
| World-currency chooser / `/economy choose <currency>` | Current first-player lease; initial selection only. No arbitrary world-currency reset. |
| Physical terminal Settings | Placement owner or currently authorized operator; same menu, placement, dimension, reach and live session. |
| Bank selection → confirmation | Review the exact terminal position/revision and bank/native currency. Existing binding and pending-custody rules still apply. |
| ATM/teller cash mode → **Review settings** → **Confirm these settings** | Review this terminal's position/revision, mode, currency and every bill/coin appearance before one configuration commit. Preview alone writes nothing. |
| Stale-settings refusal → **Refresh settings** | Reload current state, review again, then confirm. A stale draft never silently applies to a newer revision; refresh itself writes nothing. |
| Branch account opening/assignment/switch/eligible closure | Use the current branch-offered owner flow and its review/proof. Terminal ownership or operator status alone does not make a customer's account yours. Recover original outcomes and protected payout/Claims through their current services. |
| `/economy status` | Safe availability/progress; does not disclose another owner's balances or arbitrary backend exceptions. |
| `/economy diagnostics [calendar [after <cursor>]]` | Existing operator/console gate; cached bounded technical samples, with calendar pages of eight. |
| Credit/loan/debt owner inspection commands below | Player-backed operator inspection, separately audited and bounded; inspection grants are not owner mutation grants. |

Settings review retains the displayed placement/revision. Another edit, replacement or removal invalidates it. Closing or navigating away discards the review. Every confirmation still uses the current owner/operator checks, revocable physical lease and authoritative journal revision check. Refreshing or inspecting does not advance financial time, create money, cancel a pending delivery or compensate an uncertain physical effect.

Configuration, bank-binding and FX mutations retain their original canonical operations and journal/audit records. Preserve the original operation ID for uncertain work and use existing outcome recovery before attempting a new operation. Detailed financial inspection pages remain audited; terminal hardware inspection is a read of terminal metadata, not a new customer-account audit route. Do not edit the database or force-return money/items to resolve ambiguity.

## Wider kiosks and legacy upgrades

New branch kiosks occupy two blocks across and two blocks high. Existing narrow kiosks keep their original footprint when loaded. Owners or operators can sneak-right-click with an empty hand, open settings and select **Upgrade kiosk**. The upgrade is free and preserves the kiosk's bank, native currency and placement identity.

The extra column must be loaded, empty, supported and clear of entities. Protected/blocked space or unfinished cash/custody work prevents the upgrade. A blocked preflight leaves the narrow kiosk working. Successful upgrades invalidate stale banking sessions; reopen the kiosk after completion.

An interrupted upgrade can briefly disable service while its saved outcome is checked. Complete narrow or wide geometry can reconcile without adding blocks. A partial footprint stays disabled and is never filled or rebuilt automatically; an operator must restore a complete original or wide footprint before reconciliation can finish. Removing hardware never changes customer accounts, balances or debts.

## Village branch diagnostics

Use `/locate structure #economy:banks` or `/locate structure economy:bank` to find bank structure locations through Minecraft's normal structure search, including unexplored areas. Choose the world's currency first so new villages can capture their bank plans. Native coordinates identify the village start chunk; the bank is nearby. Villages without a reserved bank site are excluded. Existing placed banks gain this structure index when their source village chunks load and reconcile. As with vanilla structures, an indexed location can remain after demolition; a locate result does not guarantee active banking service.

Operators can find nearby branded branch kiosks with `/locate poi economy:bank` or `/locate poi #economy:banks`. Vanilla POI search covers 256 blocks in the current dimension and uses indexed, already discovered kiosks; it does not search unexplored terrain for new bank buildings. Generated and player-placed branded kiosks qualify, including legacy narrow forms. Neutral kiosks and satellite cells do not. Existing kiosks enter the index when their chunks load. A result identifies physical hardware, not a guarantee that banking service is active.

New eligible vanilla plains, desert, savanna, snowy and taiga villages use a shared 17×15×17 bank layout. Each branch has the selected bank's colors, two native-currency ATMs, one branded kiosk and one banker desk. Abandoned and empty villages receive no branches. Existing placed buildings keep their original design and hardware.

Branch counts use the village's original qualifying houses and workplaces. Farms, roads, decorations, wells, pens and stables do not add to that count.

| Qualifying buildings | Required branches | Optional third branch |
| --- | --- | --- |
| 1–8 | One | None |
| 9–16 | Two | 25% chance |
| 17 or more | Two | 50% chance |

Each village uses distinct banks. Its first branch needs an eligible bank in the world's selected currency. Each branch serves its own bank's currency, and a later world-currency change preserves captured choices.

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

Use Minecraft 1.21.1, NeoForge 21.1.252 and Java 21. Update the server and connecting clients together: the current banking transport is version 13, and older endpoints cannot use it. Keep the old mod, configuration and complete-world backup until the new server has reached readiness and stopped cleanly. An upgrade is not permission to reset the world or remove its economy data.

New worlds initialize automatically. Existing databases need explicit approval before upgrading to this release’s current schema (55). This applies to supported foundation schemas and the recognized original legacy database. Economy stays unavailable until the approved conversion finishes; ordinary Minecraft remains usable so an administrator can log in.

1. Join the server as a permission-level 2 operator and run `/economy upgrade review`. Review the source/target schema, account count, imported balance totals and any archived features.
2. From that same login, within two minutes, run `/economy upgrade confirm <token>` using the token shown by the review. Console, RCON, command blocks and `/execute as` cannot approve. Disconnecting, losing operator permission or replacing the session invalidates an unfinished review. Approval binds the exact source database, world path and target release; changed source data or another target needs a fresh review.
3. Wait for the approval-saved message, stop the server normally and wait for the process to exit. From the Economy project, run:

```sh
./gradlew upgradeEconomyDatabase -PeconomyWorld=/absolute/path/to/world
```

4. Retain the reported backup and start the server normally. Confirm `/economy status` reaches availability.

The offline tool automatically makes a verified complete-world backup beside the world in `<world-name>-economy-upgrade-backups/`, stages the new database and validates it before installation. It holds Minecraft’s and Economy’s ownership locks and never converts a running world. It does not reset or replace the Minecraft world. Keep the backup at its recorded location. If installation is interrupted, keep the same mod release, stop normally and rerun the same tool; normal startup refuses incomplete installation. The tool resumes the exact staged result without repeating imported balances.

The recognized legacy minor-unit format imports bank/trading account IDs, player owners, primary flags, account currencies and exact balances. Bank providers start unassigned and can be assigned through ordinary branch services. Every original table is retained as a read-only `legacy_v0_` archive, including old ledger rows, stock holdings, orders, trades and FX history. Those historical market features do not become active current facilities. The legacy world currency is retained; the current FX model starts with its standard opening rates. Legacy physical items are not automatically granted current cash issuance or card authority. The review explains this scope before confirmation. Unknown layouts, malformed identities/balances, incompatible account limits and databases from newer releases are refused without changing the source.

`prepareEconomyUpgrade` remains available for a separate offline pre-upgrade backup of supported foundation databases. A backup receipt by itself does not grant admin approval or cause a production startup upgrade. New migration steps target the release’s migration registry rather than a fixed version in the importer; future releases still require a matching review and approval. A pre-upgrade backup is retained stopped-state evidence, not a certified clean general-restore backup.

## Complete-world backup and restore

1. Stop new player activity and retain the original operation UUIDs for any uncertain actions. Use the normal server `stop` command, or Save and Quit for an integrated world. Economy stops admitting new work and drains accepted work within the configured deadline (30 seconds by default).
2. Wait for the process to exit and the log message `Economy complete-world clean shutdown recorded`. `Economy foundation stopped; drained=true` alone is not the complete-world certificate: Minecraft saves and the post-save identity/checkpoint check must also finish.
3. Create the backup while the world remains stopped:

```sh
./gradlew backupEconomyWorld -PeconomyWorld=/absolute/path/to/world
```

Backups include the world and economy together, with a file inventory/checksums, format/schema/identity information, journal position and economic checkpoint. They appear under the sibling `<world-name>-economy-upgrade-backups/<backup-id>/` directory. Preserve the entire backup directory, including its receipts and manifests. Keep Minecraft player, entity, container and chunk saves with the economy database from the same snapshot; a database-only copy or a mixture of snapshots is not a supported backup. Do not remove a WAL file or copy files from a running world.

Restore into a **new, separate directory** whose parent already exists, outside the backup directory. Keep the current world stopped and intact while checking the copy:

```sh
./gradlew restoreEconomyWorld -PeconomyBackup=/absolute/path/to/backup-id -PeconomyRestoreDestination=/absolute/path/to/new-world
```

The restore command validates the receipt, full inventory/checksums, supported format/schema, world identity, journal and economic checkpoints, and offline financial invariants. It validates the staged copy again before publishing the new destination and refuses an existing destination. A successful command reports the restored destination, world ID, journal position and economic checkpoint; retain that output with the backup record. Restore supports clean schema 4 through 55 backups in this mod’s backup format. A restored world on an older schema requires a fresh in-game administrator review and approval, followed by the offline upgrade tool.

Switching the server's selected world to the restored copy is a separate operator action. Start only that selected copy with the matching mod/configuration, check readiness as described below and stop it normally before treating the recovery as established. A successful offline restore does not establish runtime readiness. Do not run the original and restored copies concurrently: they retain the same economy world identity. A restore returns to the snapshot; it cannot retain player actions or financial outcomes recorded after that backup.

There is no online backup, automatic replacement, or retention/deletion service. Operators own backup storage and retention. Failed restores can leave a hidden staging directory without publishing the destination. Preserve failure output and the source backup; choose a fresh destination for a later attempt after resolving the reported cause. Do not publish an incomplete staging directory manually.

## Recovery readiness and shutdown troubleshooting

After upgrade or restore, use `/economy status` and authorized `/economy diagnostics` to check storage availability, failure code/correlation, journal/checkpoints, queue age and calendar progress. Economy requests become available after startup recovery and required catch-up; a responsive Minecraft server alone does not establish economy readiness. Ordinary Minecraft can continue during that work. With an economic system registered, ongoing catch-up processes UTC intervals in chronological order and gates affected writes until current. Compare successive timestamped diagnostic samples when checking progress. Sleep and `/time` do not advance economic time. Daily FX starts at the UTC midnight after the world’s opening publication. A fresh world offers the first connected player a denomination chooser.

If startup is unavailable or catch-up stops progressing, retain the first logged error, correlation and diagnostic samples, stop normally, and preserve the world and backup. Check the matching Java/mod/configuration, free space and reported lock, checksum, identity or readiness cause before restarting. Do not edit journal facts, advance the host clock, enlarge queues or reset the database to force readiness. Storage/schema corruption and malformed provenance require diagnosis; retrying a player mutation cannot fix them.

An interrupted or disconnected action has an uncertain outcome until its original operation is resolved. Use its existing owner receipt/lookup or authorized bounded diagnostic path before creating a fresh reviewed action. A committed original is not reversed by disconnect or shutdown. Do not issue compensating money, restore cash, move custody records or resubmit with a new UUID merely because a callback or item presentation was not observed.

If the log reports unresolved shutdown work, `drained=false`, a save/post-save failure or `Complete-world clean shutdown not certified`, do not describe that stop as clean or edit the clean-witness file. The general backup utility refuses missing or mismatched clean evidence. Preserve the files and logs, resolve the cause, then let the same world recover and complete a normal stop before creating a clean backup. Increasing the drain deadline does not establish that previously unresolved work completed.

Legacy economy databases are not imported. Incompatible, corrupt, or mismatched identity data makes the economy unavailable while ordinary Minecraft play can continue. Preserve the files and inspect the logged error rather than deleting them.

Storage requires filesystem support for exclusive file locks, atomic file publication, and directory synchronization. These checks have been exercised on macOS; other platforms have not been runtime-tested.

## Operational reference measurements

A local dedicated test on the Apple M5 Pro/24GB reference Mac, with a2–4GiB Java21 heap and sparse flat world, passed the fixed20-synthetic-owner workload:2 top-level actions/s, a300-second sample and one20-request burst. Owner history p95 was100.081ms; confirmation-to-financial-outcome p95 was at most50.106ms. The sample reconciled balances, original receipts, trader stock and protected custody, then stopped cleanly. [Measurement scope and results](../.codex/A10/verification/A10-08.md) retain the exact workload, quantiles and restore evidence.

This is service capacity evidence, not20 connected players, physical-delivery throughput or a minimum-hardware specification. No natural background publication occurred during the sample. Populated worlds, rendering, network clients and storage conditions can change observed performance. Use authorized diagnostics to observe queue depth/age and readiness on the actual server; retain controlled overload refusals rather than enlarging queues to hide them.

## Server integrations

Server integrations can consume `ca.emilybelnavis.mcmods.economy.api.BankingIntegration` version 2 through the server ledger service. It exposes versioned prime, owner valuation and fee quotes, physically authorized credit payment entry points and fixed/variable/secured capability reporting. Financial authority and existing journal/custody rules apply to integrations. A village structure, desk, banker or branded appearance does not issue an integration grant. Use authenticated server context and the existing account and physical-service checks. The interface declares `VERSION = 2` and reports it through `version()`. [BankingIntegration source](../src/main/java/ca/emilybelnavis/mcmods/economy/api/BankingIntegration.java).

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

[Acceptance evidence](../.codex/A8/verification/A8-08.md) and [A9 downstream boundary](../.codex/A8/architecture/a8-downstream.md) distinguish demonstrated behavior, reused evidence and future work.


## Item reference configuration

The A9 foundation loads 349 approved vanilla base anchors, captures ordinary crafting/stonecutting/cooking recipes and preserves the full vanilla classification inventory. A9-03-02 also values supported vanilla potion identities through captured brewing paths. A9-03-03 adds supported component variants and bounded container contents. A9-04 exposes authenticated item-reference reads and typed provider registration; A9-06 adds explicit operator opt-in for reference-price trading. Item reference costs remain separate from financial qualification.

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

Restart or use vanilla `/reload` after editing the file. A valid candidate becomes active only after durable publication. Invalid configuration is left untouched and the prior durable snapshot is retained; fresh valuation stays unavailable until a valid matching reload. Look for `VALUATION_SNAPSHOT_READY` or `VALUATION_RELOAD_REJECTED` in the server log. Unselected traders retain legacy pricing. Activated reference scopes require matching valuation readiness for new quotes and settlement effects.

Ordinary recipe costs use exact ingredient/output quantities and the cheapest supported acyclic path. Sticks use two oak planks per four sticks regardless of crafting origin. Named refined metals and cooked foods use their calibrated raw input divided by 0.75, once; this canonical rule prevents cheaper intact ores from bypassing the approved refined values. Explicit overrides still take precedence. Other cooking adds no generic fuel/time fee. Unsupported dynamic recipes, returned containers and unregistered mod items are not inferred as free or guessed prices. A search limit makes a value unavailable rather than returning a partial minimum.

Brewing reference costs use the prior per-potion cost plus one-third of the shared ingredient and one-sixtieth of blaze powder per step. Water content is free; the potion bottle is counted once. Dragon breath contributes its collected content and its own shared ingredient bottle, with no returned-bottle credit. Prices use a standard three-potion batch regardless of actual stand occupancy. Known effect, strength, duration and form identities remain distinct. Extra/custom brewing registrations or changed vanilla mix lists require a supported adapter; affected potion values are unavailable rather than inferred by running custom recipe code.

Snapshot history and incomplete staging are retained without automatic deletion. A manifest is capped at 32 MiB and retained canonical data at 256 MiB, including incomplete unpublished stages; quota exhaustion rejects new publication. Compiled history and result caches are bounded independently. Keep existing complete-world backups and follow the schema-upgrade procedure before opening an older world.

Supported equipment adds compatible enchantment premiums, applies 25% per distinct curse, then multiplies by remaining durability. Names add no price and preserve other state. Stored books use book plus stored premiums. Containers add their individually modified shell and each published child unit times count; potion bottles are already included. Missing or unsupported child state makes the whole container unavailable. Limits include eight container levels and 4,096 children. Unknown components/custom data are not discarded. Native text supports plain literals up to 16,384 characters and books up to 100 pages; custom entity state requires an adapter.

Optional `enchantmentPremiums` in the item-valuation config supplies a complete positive CAD list for a supported beneficial enchantment, for example `"enchantmentPremiums": {"minecraft:sharpness": ["20", "45", "80", "110", "150"]}`. Unknown enchantments, curse overrides, incorrect level counts and invalid amounts reject the whole candidate. Reload creates a new immutable snapshot while original values remain reproducible.

### Item reference diagnostics

Connected operator players can use `/economy valuation status`, `/economy valuation coverage`, `/economy valuation value minecraft:diamond USD` and `/economy valuation history 8`. The value command reads one unmodified item (`{}` component identity), defaults to CAD when currency is omitted and reports typed availability, published unit money and bounded provenance identifiers. Integrations use the exact-component API for supported stateful identities. History accepts 1–16 rows. The per-server diagnostic queue admits at most 16 pending reads; a disconnected/demoted player or replaced session receives no stale result. These commands neither change prices nor enable listings.

Coverage distinguishes all 1,333 classified vanilla identities (349 base, 760 derived, 109 variant and 115 intentionally operator-unpriced), supported component states and unregistered mod items. Registry classification is not a promise of a numeric value for every component permutation, custom recipe or exhausted-budget case. Operator diagnostics and the API retain reasons; unsupported ordinary trader listings remain disabled without exposing a valuation explanation to players. Generic integrations must explicitly register their mod identities; there are no built-in mod prices or automatic mod-recipe guesses.

### Prospective trade-source support

A9-05 installs schema51 support for source epochs, reference account/cash receipts and retained carry-retirement history. Use the existing complete-world backup procedure before a schema upgrade. Existing EmeraldV1 trader pricing remains selected until an operator explicitly activates reference pricing using the controls below.

After a separately enabled scope exists, valid valuation reloads advance its source epoch atomically with the valuation snapshot. Uncommitted old-source quotes refuse; committed originals retain their original receipts. Old fractional carry is recorded as retired without erasure or payout. New reference goods prices use rounded units without carry, and unsupported exact components or unproved rounded bid/ask gaps remain unavailable. A9-06 supplies the initial production activation and player presentation described below.

### Opt-in reference-price trader activation

Reference-price trading is available in 0.4.0-alpha, with native checkout/presentation acceptance recorded for A9-06. It does not switch existing worlds automatically. Preserve complete-world backups before the additive schema52 upgrade.

A connected permission-level2 operator can review a loaded, captured trader with `/economy valuation review <trader UUID>`. The report shows pinned vanilla classifications, representative values, missing sources, component/work limits and each captured offer's neutral availability. Review these limits, then publish that exact report with `/economy valuation activate <trader UUID> <review hash>`. A changed snapshot/catalog/FX/readiness requires a new review. Activation publishes prospective source epochs; reopen trading afterward. Subsequent valid valuation reloads update only already-enabled scopes.

Activated offers use reference unit prices and show the effective unit and total before confirmation. Unsupported entries are omitted. There is no fallback to old emerald prices within activated policy. Exact supported outputs can trade; native required-component buybacks remain unsupported, including empty required predicates. Unknown custom data, operator-only goods, missing mod adapters, bounded work and failed rounded-spread proofs can also disable entries. The review never promises every component permutation.

Activation covers at most32 captured keys and the world supports at most63 enabled keys in one atomic valuation publication; more refuses publication. Pending control requests/reviews are bounded. No old balance, stock/restock, demand anchor, receipt, activity/rate window or fixed loan is revalued, and retired carry is not paid out. Reference values remain separate from financial eligibility.

## Posted history index

Schema53 adds an owner history read index without changing original journal records, balances or legacy cursors. Follow the stopped-world upgrade-backup procedure before opening an older world. Initialization builds and validates the index in bounded batches and resumes persisted construction progress after reopen. Reads stay unavailable until source coverage is ready; malformed provenance refuses readiness instead of rewriting history. Complete-world backup and restore validate this projection against the original journal too.

Player History uses recorded UTC timestamps, separate from the twenty-minute financial calendar. Account/currency/type/date filters read only owned facts; pages retain a committed upper boundary until Refresh. Sparse filters can reach an explicit work limit; refine the query. Original fees, counterparties and FX are shown only when retained facts establish them. Pending/refused operations are resolved through their original-operation controls, not fabricated as posted transactions.
