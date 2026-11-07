# World storage, identifiers and migrations

## Storage ownership and API

Production startup supplies FoundationRuntime to ServerServices; it owns SqliteStorage on the storage worker. One storage worker owns its
connection from initialize through close; all filesystem, JDBC and migration work stays
there. No extra read connections. ServerServices supplies the existing bounded 256-task
storage queue, 64-task calculation queue, 320 outstanding result cap and 1ms callback-start
budget. Admission errors now carry StorageException.Kind.OVERLOAD. Fatal persistence errors
transition to UNAVAILABLE on the server thread; accepted failure completions still drain.
Initialization failure rejects readiness. Driver failure is logged on client loading and
propagated through server readiness rather than replacing a store or stopping vanilla play.

<world>/economy/owner.lock is an OS FileLock, held until the connection is actually closed,
including after a drain timeout. Never unlink lock files: file existence is not ownership.
Do not use network/shared multi-server storage. Symlinked storage paths are rejected.
The filesystem must support file forcing, atomic rename and directory synchronization;
unsupported providers fail safely. macOS is verified, other platforms are not runtime-tested.

FoundationStore.transaction takes a trusted worker-only callback with no exposed JDBC
handle. Call it through services.storage(() -> store.transaction(...)). Inputs/results must
be detached immutable values. Never join on ticks. Transaction methods support one data
statement at a time (SELECT/INSERT/UPDATE/DELETE/WITH), at most 64KiB SQL, 256 parameters,
1MiB total UTF-8 parameter bytes, and immutable results capped at 1024 rows/1MiB values.
SQL and callbacks must never be accepted from clients. Escaped transaction objects reject
use after the callback returns. DDL belongs to the migration registry, not this interface.
Commit success returns the callback result; failure rolls back without retries. Rollback
failure poisons the connection and never enables autocommit (which could commit partial work).
Financial writes use [durable operation identity and uncertain commit resolution](journal.md).

## Identity publication protocol

1. Acquire ownership, then inspect database, world-identity and identity.pending without
   substituting absent/corrupt data. A completed identity without its database is a hard error.
2. For truly fresh storage, generate a UUID and durably publish identity.pending first.
   Files are written to a unique temporary sibling, forced, atomically renamed, then their
   directory forced. No promise of atomicity across SQLite and external files is made.
3. Create the entire foundation schema and identity/history in one SQLite transaction.
4. Publish world-identity using the same UUID; only then delete the intent and force its
   directory. Interrupted final publication can resume from matching committed DB + intent.

An intent without a DB, or with an empty uninitialized DB, can finish initial creation.
A committed DB + matching intent can finish external metadata publication. Missing both
world markers for an existing DB, conflicting markers, mismatched UUIDs, unsupported
metadata formats and corrupt data are unavailable states, never permission to create anew.
Temporary partial files are not trusted as identity. A full world copy retains valid
matching identity and may run independently; UUIDs do not prohibit cloning.

## SQLite and schema

Driver org.xerial:sqlite-jdbc:3.53.4.0 is pinned, jar-in-jar bundled with native binaries
and upstream license files, and explicitly in additionalRuntimeClasspath for development.
Effective pragmas are verified: WAL, synchronous=2 (FULL), foreign_keys=1, busy_timeout=5000ms.
No automatic busy retry is performed beyond SQLite's finite busy timeout.

application_id=0x45434F4E identifies redesigned storage. Current schema=49; minimum=1. V49 adds the [immutable production simulation manifest](simulation-production.md). V48 adds [financial-day activation, controls, frozen rate plans and basket completion](macro-rates.md). V47 adds [fixed retirement and exact-time FX snapshots](fx-effective-time.md). V46 adds [currency-prime policies and activation-scoped plans](currency-prime.md). V45 adds [NPC cash and physical custody](npc-cash-custody.md). V44 adds [account/NPC/FX settlement and private zero-terminal bridges](npc-account-settlement.md). V43 adds [individual trader stock/tick/progression evidence](trader-entities.md). V42 adds [trade market/catalog/pricing state](trade-markets.md). V41 adds [immutable activity windows and deterministic model plans](activity-windows.md). V40 adds [atomic activity attribution and indexed capacity history](activity-attribution.md); intermediate subsystems retain their own migration contracts. The earlier foundation milestones below describe their original scopes. V3 adds the [journal](journal.md); V4 adds [recovery and clock metadata](recovery-clock.md). V5 adds the [FX journal indexes and activation-scoped observation](fx.md). V6 adds the [account catalog](accounts.md). V7 adds the [ledger balance projection](postings.md). V8 adds [holds](postings.md#holds). V9 adds [quote consumption and the denomination index](postings.md#fx-closure-and-rebase). V10 adds the [posting history index](postings.md#service-and-history).
V11–V17 add merchant payments, custody, cash issuance, terminal policy, deposits/returns, withdrawals and register sales. V18 adds the [financial calendar](financial-calendar.md) and paged domain checkpoints, retaining prior IDs, balances and history.
V1 creates only foundation_metadata (world UUID, product, schema version) and migration_history.
V2 adds explicit identity_protocol=1. user_version must match metadata and contiguous
recognized migration history. Newer/older unsupported versions and legacy/foreign databases
reject. Startup also performs SQLite quick_check. Only foundation tables are created.

SchemaMigrations validates ordered contiguous registry versions and applies all pending
steps, history entries, metadata version and user_version in ONE transaction. DDL failure
rolls back the whole chain to the prior supported schema. A rollback failure closes the
connection; startup reports failure and does not keep executing. On a later explicit
restart, inspect the actual committed metadata/history, not an assumed last attempted step.
No implementation can promise recovery from arbitrary filesystem/hardware failure; ambiguous
commit failures are not blindly retried. SQLite atomicity protects old-or-new schema state.

## Offline pre-upgrade backup gate

Before upgrading a supported older schema, stop Minecraft completely and run:

    ./gradlew prepareEconomyUpgrade -PeconomyWorld=/absolute/path/to/world

UpgradeBackup acquires Minecraft session.lock AND economy owner.lock. It copies the entire
world to a unique sibling <world-name>-economy-upgrade-backups/<uuid>/world, preserving the
original files. Runtime lock files, SQLite shared-memory cache and prior backup receipt are
excluded; SQLite DB and WAL, identity markers and all world/player/region files are included.
Symlinks and special files reject. Copied files and directories are forced, all file hashes
and inventory validated, and a receipt is atomically published LAST in the source economy
directory. A crash during backup leaves no usable new receipt; no incomplete backup can gate
an upgrade. Existing receipts can be replaced only by another completed offline backup.

Startup does NOT copy a world. It checks the saved receipt, every backup file hash and the
DB/WAL/identity fingerprint captured before opening JDBC. Missing, stale or tampered backups
prevent migration and keep the economy unavailable. The snapshot is a complete stopped-state
world backup, not a claim that mutable vanilla world files remain identical after startup.
Identity publication after an interrupted initial creation needs no upgrade backup because
the schema already matches current. No online backup, automatic restore, or backup deletion.
General offline backup and restore have separate [clean-world requirements](recovery-clock.md).

## Identifier conventions

Identifiers records prevent UUID entity/actor type confusion. Players retain Minecraft
UUIDs. Organization/system actor types are distinct. Currency codes are uppercase 3–12
ASCII letters/digits beginning with a letter. Catalog keys are stable lowercase namespaced
keys (128 characters max), with company/security/exchange/listing kinds, independent of names.
Payment/loan/order types are conventions only. JournalSequence is positive, database-assigned
by the journal; EconomicSequence is nonnegative and independent; AuditInstant is UTC Instant.

## CHF history compatibility

V19 replaces only the posting-history index predicate with record_version IN (1,2), enabling [CHF minor-unit posting history](chf.md). V10 remains frozen for old schema/backup validation. Existing schemas1–18 require the normal stopped-world backup gate; no balance/account/cash table is changed. Strict v1 and v2 record decoders coexist. Supported clean backup manifests include19.


## A6-03 current contract (2026-11-05)

[Institution/provider contracts](institutions.md) supersede earlier lifecycle/cap references: schema V20, four total open BANK accounts including primary, version3 institution postings, explicit legacy provider migration, and sealed branch-only lifecycle admission. Live opening/closing/provider changes are unavailable until A6-05-01. Existing account services and original outcome lookup remain.


## A6-04 current contract (2026-11-05)

[Wealth, pricing and funded yield](wealth-pricing.md) defines schema V21, persisted required-source contracts, fresh qualification/fee witnesses, assigned-bank FX pricing and time-weighted bank-funded deposit yield. Production installs bounded accrual/payout providers at stage 1 and daily Tier reevaluation at stage 7; empty domains remain dormant. Source snapshots and unpaid receivables remain distinct from spendable funds. Live branch adapters, issued cards, lending and trading are still separate packages. Earlier no-product/default-Tier2-denial descriptions apply to their historical delivery checkpoints.

## A6-05-01 schema V22

Adds `terminal_bank_bindings` and preserves legacy terminal settings as explicit unbound metadata. [Exact binding/fact/migration contract](branch-hardware.md). Open/recovery/offline validation and complete-world restore recognize V22; original history and backup-before-upgrade rules remain.

## A6-06 schema V23

Adds `issued_cards` and one-use `issued_card_fee_quotes`; [facts/encoding and custody](issued-cards.md). Old IDs, journals and settings remain intact. V23 participates in open/recovery/offline schema validation and complete-world manifest restore under the existing backup-before-upgrade gate.

## A6-07 schema V24

Adds four unseeded credit profile/history/inquiry tables and three indexes; [exact fact/first-issuance contract](credit-profiles.md). V23 upgrade preserves existing accounts, tokens, settings, capital and journal history. Open/recovery/offline schema and complete-world manifests recognize V24 under the existing backup gate.

## A6-08-01 extension

V25 adds funded credit facilities, separate tokens and immutable statements with five lookup/uniqueness indexes. Main/offline schema checks and complete-world backup/restore accept V25; historical migrations remain unchanged. See [credit persistence](credit-facilities.md).

## A6-08-02 diagnostics

V26 appends four partial/expression indexes for bounded credit diagnostics; no table or monetary schema changes. New daily witness and payment allocation facts are registered v1 records; old financial facts are preserved. See [diagnostic persistence](credit-diagnostics.md).

## A6-08-03 acceptance

V27 appends one partial credit_event_feed index for ordered supported credit facts; no new monetary entity/table or fact family is added. Main/offline validation and backup/restore accept V27. Existing v1 facts and V1–V26 migrations are unchanged. See [immutable lifecycle source](credit-acceptance.md).

## A6-09-01 loan foundation

V28 appends four loan tables and five indexes for bounded ownership, exposure, servicing and application identity lookups. Main/offline validation and complete-world backup accept V28. New loan/funding/FX v1 records are registered; Ironbridge-only inquiry/pricing v2 decoders preserve the old bank-only v1 contracts. See [exact persisted entities, facts and replay](loans.md). V1–V27 migrations remain unchanged.

## A6-09-02 diagnostic lookup

V29 appends only loan_diagnostic_records over encoded loan UUID, record type, journal sequence and position. Main/offline validators and complete-world backup accept V29. No table, financial field, new fact family or historical migration is changed. See [diagnostic query boundaries](loan-diagnostics.md).

## A6-09-03 obligation source

V30 appends loan_event_feed over sequence and position for existing loan and installment v1 facts. Main and offline validators and complete-world backup support V30. The reader captures a world-bound immutable frontier and uses indexed tuple cursors. See [accepted loan source](loan-acceptance.md).

## A6-10-01 shared lifecycle

V31 adds nine lifecycle/witness tables, twelve indexes and five claim-maintenance triggers. The institution financial-account table is copied with the ALLOWANCE role and its original IDs. New allowance-account facts use v2. Main/offline validators and complete-world backup support V31. Bounded bootstrap rejects unsafe historical backdating. See [exact lifecycle storage and records](debt-lifecycle.md).

## Debt diagnostic indexes

V32 adds `debt_diagnostic_obligations`, `debt_accounting_history` and `debt_sale_diagnostic_history` for bounded owner/operator reads. Existing lifecycle entities and fact encodings retain their versions. Online validation, offline inspection and backup manifests support V32. See [diagnostic read and authority contracts](debt-diagnostics.md).

V33 adds six debt/reporting cursor/source/daily-area tables and five indexes. Five new v1 record families support bounded legacy replay, utilization sources and resumable score folds. Online/offline validation and backup compatibility support V33. See [accepted persistence and replay contracts](debt-acceptance.md).

V34 adds one Collection Agent presentation-state table and an eligible-case partial index. Its v1 presentation facts share the immutable journal. Online/offline validation and backup compatibility support V34. See [presentation and cooldown contracts](collection-agent.md).


## V35 automatic payments

V35 adds per-facility consent, immutable due-window progress, latest statement payment-adjusted targets and a bounded source-bootstrap cursor. Exact schema/index validation and canonical projection validation run at storage/offline inspection. Version1 settings/window/bootstrap decoders are registered. Existing journal facts and versions retain their meaning. Current schema is35, stopped-world upgrade backups are required for1–34 and clean restore manifests support4–35. See [automatic payments](autopay.md).


## V36 village branch foundation

V36 appends five presentation tables and four indexes for captured village plans, independent branch lifecycle/history, service anchors, explicit required-provider fills and saved staffing pause state. Online open/recovery/offline inspection validate exact DDL, canonical identities/plans and projection/history completeness. Clean restore recognizes36; worlds 1–35 require the normal stopped-world upgrade backup. No customer account, capital or monetary schema is changed. [Current branch contract](village-branches.md) records journal decoders, limits and loaded-only trusted activation; generation/placement/staff remain separate packages.


## V37 recoverable kiosk upgrades

V37 appends kiosk_geometry_upgrades keyed by world/generation, preserving original upgrade operation, expected terminal revision and PREPARED/COMPLETE/ABORTED phase. New economy:kiosk_geometry v1 facts accompany unchanged v1 terminal policy facts. Exact schema/canonical/historical generation linkage validates in online open/recovery and offline inspection. Clean backup/restore recognizes37; older worlds 1–36 require the existing stopped-world backup gate. [Upgrade coordination and no-auto-rebuild boundary](kiosk-upgrades.md). No account/capital/currency data or historical migration definitions change.

## V38 later-site placement provenance

V38 appends `village_placement_attempts` and its phase/latest index. Version-one `economy:branch_placement` facts persist a later accepted site/anchor choice without changing original village captures or bank/currency/slot/generation choices. Exact DDL, canonical bounded choices, world/branch identities, original choice constraints and revision linkage validate during open/recovery/offline inspection. Clean backup/restore recognizes38; worlds 1–37 need the existing stopped-world complete backup gate. No monetary or customer schema changes. [Generation and explicit retry boundaries](village-generation.md).

## V39 banker assignments and replacement progress

V39 adds `banker_slots`, `banker_employee_history` and `banker_slot_events`. Exact DDL, captured current desk identity/capacity, canonical bounded records, contiguous valid transitions, immutable staffing facts and employee-history consistency validate on open, recovery and offline inspection. Existing worlds 1–38 require the normal stopped-world complete backup gate; clean restore recognizes 4–39. Staffing creates no accounts or money. [Current staffing contract](banker-staffing.md) records loaded-only physical proofs, positive vacancy, 24,000-tick progress, atomic pause and conservative spawn recovery.


## V44 account NPC settlement

V44 appends npc_bridges, npc_trade_adapter, npc_trade_quotes and npc_trade_receipts with immutable evidence and a v4-inclusive ledger-history index. Posting4 is registered alongside unchanged prior versions; generic authorities and old versions reject NPC_BRIDGE. Online recovery and offline inspection validate exact quote-to-group/receipt linkage, canonical bridges and terminal zero, historical pricing/stock witnesses, custody, fee/FX consumption and immutable projection counts. Clean restore recognizes44; old worlds retain the stopped complete-world backup gate. See [account NPC settlement](npc-account-settlement.md).


## V45 NPC cash and custody

V45 appends npc_cash_adapter, npc_cash_quotes, npc_cash_receipts and npc_cash_returns with immutable evidence and source indexes. New cash/return facts preserve old account and activity manifests. Online/recovery/offline validators bind captured pricing/FX, exact posting groups, original cash quantities/protected outputs, custody and conclusively closed input returns. Clean restore recognizes45; old worlds retain the stopped complete-world backup gate. See [cash and physical custody](npc-cash-custody.md).


## V46 currency-prime provenance

V46 appends currency_prime_activation, currency_prime_policies, currency_prime_completions and activation_model_plans, indexed immutable source facts and exact startup/recovery/offline validation. Currency loan/application fact v2 appends policy UUID/revision without changing old v1 meanings or existing fixed terms. Validators enforce future activation inheritance, actual source/child chronology, scoped day indices/model outputs, per-currency bounds and basket completion. Clean restore recognizes46; old worlds retain the stopped complete-world upgrade-backup gate. See [currency prime](currency-prime.md).


## V47 prospective retirement and effective FX

V47 appends fixed_system_retirements and effective_fx_snapshots, immutable source triggers and exact-time/original-ID indexes. Existing daily FX facts/publication-day index and economic completion correspondence are unchanged. Startup/recovery/offline validators bind original fixed signatures, excluded-boundary/successor evidence, exact model/provider-child snapshots and source chronology. Clean restore recognizes47; old worlds retain the stopped complete-world upgrade-backup requirement. See [retirement and effective FX](fx-effective-time.md).


## V48 financial-day provider

V48 appends macro_activations, macro_control_policies, macro_rate_plans and macro_rate_completions with immutable source guards/indexes. Atomic activation binds future A/F/H, original fixed retirement, bootstrap primes and configuration. Version-two source linkage preserves old activation/window/prime/FX fact meanings while binding exact dispatcher operations. Startup/recovery/offline validators and clean restore recognize48. Existing worlds retain the stopped complete-world upgrade-backup requirement. See [macro rates](macro-rates.md).


## V49 production simulation

V49 adds simulation_production with immutable guards and a registered initial configuration/activity/catalog/cash/provider manifest. Untouched NPC quote-abandon source evidence binds permanently rejected originals, quarantined untouched reservations and cash-lock release. Startup/recovery/offline validation and restore support49. Historical facts retain their meanings and the complete-world pre-upgrade backup gate stays in place. See [production simulation](simulation-production.md).
