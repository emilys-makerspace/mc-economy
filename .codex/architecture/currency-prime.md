# Currency-specific prime — V46

A8-05 delivers prospective prime policy/history and ordinary-loan capture. It installs no macro provider, FX retirement or production rate activation. [Activity windows](activity-windows.md) and the frozen v1 model remain installed; [loan contracts](loans.md) preserve existing fixed/subprime terms.

## Policy and prospective activation

CurrencyPrimes.Policy binds UUID, world, explicit currency, revision, APR, effective milliseconds, activation, config, window and mode. APR is0..0.25 inclusive at six fractional APR digits. Each automatic publication moves at most0.001 APR (0.10 percentage points), with exact model computation and checked publication bounds. Empty covered activity holds the prior value. PAUSED publishes the prior value while advancing chronological evidence; MANUAL is reserved but rejected until an explicit frozen override adapter is installed.

activate(store,id,A,config,trustedNow) is an internal trusted worker command. A must be an aligned future financial boundary strictly beyond real trusted time, financial observation/processing, fixed-system frontier and activity observation. F=A+1,200,000ms is the first model event. Activation captures current configured prime and latest committed fresh legacy FX as immutable bootstrap/anchor evidence; all11 initial prime rows at A commit together. Legacy prime outside0..25% rejects, rather than clamping history. Existing future legacy edits conflict explicitly. Once takeover is reserved, new global prime edits reject rather than changing the captured inheritance. Historical global policies retain their original0..50% validation and meaning.

The API does not manufacture a past clock for catch-up. Trusted event preparation validates retained boundary/source chronology directly; it does not call the operator future-policy editor. Actual stage-0 scheduling and shared FX takeover are A8-06-02/A8-07 work. No production caller activates this foundation yet.

## Activation-scoped model input

Existing WindowStore v1 summaries can start before future A and remain immutable. ActivationModelPlans adds a separate plan source, using their exact saved day rows filtered to day index >=(A-origin)/day. It verifies every selected index and complete coverage, retains at most14 days (latest up to7 W, preceding up to7 B), excludes pre-A totals/capacity and hashes original open/snapshot sources plus activation/horizon. This avoids resetting source observation or silently treating pre-A days as startup activity.

prepare(store,activation,window,reference,priorFx,config) freezes the exact scoped input/output once. Worker/provider overloads take the actual journal child operation; source facts and projections use that identity. Configuration contents are immutable under UUID/revision, anchors come from activation, and prior prime comes from each currency at boundary-1 with previous-day continuity. The existing pure MacroModel remains the mathematical source of all output. Prior FX is a trusted detached provider input as in the earlier plan seam; its exact source-UUID/effective-time adapter belongs to A8-06.

CurrencyPrimes.prepare(reads,world,actualChildOperation,window,currency,mode) consumes only that retained activation plan and validates activation identity, current previous basket, warm-up counts, exact source boundary, candidate APR and step. Each child writes one currency. prepareCompletion requires all11 matching event policies. Standalone publish/complete wrappers preserve original-operation replay; future DeadlineDispatcher composition uses the actual-child overloads. Bounded reads are <=16 and no provider is registered here.

ready/select block the expected due boundary until its full prime basket completion exists. New lending and public coherent reads cannot use a partially published basket or fall back to global prime on missing activated state. The future combined provider must retain overall readiness through FX/30-complete/dispatcher completion; a prime-basket receipt alone is not the full macro event.

## Immutable reads and loan capture

BankingIntegration version2 adds authenticated owner reads prime(grant,currency) and primeAt(grant,currency,effectiveMillis). Both require explicit nonnull currency, use the storage worker and gate on basket readiness. Historical instants must be past/current. Before currency activation these return empty. The old prime(grant) remains explicitly the legacy global configured-policy read; it never guesses world/player currency.

Internal current/byId provide immutable policy/history reads; current is a raw committed history view, including an individual child when requested internally. select is the lending boundary: before A it explicitly returns LEGACY configured policy identity/APR/version; after A it requires ready currency state and returns its UUID/revision/APR, with legacyVersion0.

Ordinary loan menus select the lender/loan currency before requesting loanContext(grant,currency). Preview and confirmation preserve the same captured selection. Application canonical version2 includes prime_policy_id and prime_policy_revision, with prime_version0. Application/origination reselect the same loan currency and reject stale, wrong-currency or missing binding. Loan/accepted-decision fact version2 appends UUID/revision to the original captured fields. Legacy constructors and strict v1 payload decoding remain available; v1 global prime_version is never reinterpreted as a currency revision. New currency facts cannot decode as v1.

Fixed servicing, collection/debt copies and source feeds preserve original UUID/revision/APR. Historical validation requires the exact policy fact to precede the loan/application, matches effective currency policy at the original instant, and prevents fixed bindings appearing, disappearing or changing across snapshots. Subprime still uses its separate score premium, prime0 and v1 facts. Existing fixed loans remain byte-identical and ignore future prime changes. Score/product premiums, variable/secured capability and funding rules are unchanged.

## Storage and handoff

V46 appends currency_prime_activation, currency_prime_policies, currency_prime_completions and activation_model_plans with indexed immutable evidence. Registered source codecs, startup/recovery/offline validation and clean backup/restore support46; old worlds retain the stopped complete-world upgrade-backup requirement. Validators bind exact schema, facts/projections, activation inheritance/FX source, source day indices/hashes, model output, chronological currency bounds, child/completion ordering and loan policy provenance. Old migrations, windows/plans, global policy facts and fixed contracts retain their meanings.

A8-06-01 owns prospective fixed retirement and effective-time FX readers. A8-06-02 composes the full stage-0 provider over this scoped plan and prime child/completion interface, with shared activation/readiness and exact FX provenance. A8-07 owns public controls/manual override scheduling and production activation. No automatic dependent execution follows A8-05.


## A8-06-01 shared FX migration

[Exact-time FX](fx-effective-time.md) now supplies original-UUID/latest/frontier adapters for activation and historical prime-source validation. Publication day remains display/legacy history only; the48-hour activation freshness rule uses exact source milliseconds. A8-06-01 delivers retirement/read infrastructure without registering the combined provider or activating takeover.


A8-06-02 now delivers the dormant [financial-day macro provider](macro-rates.md) under V48: atomic A/F/H handover, actual child-linked v2 window/publication facts, immutable selected baskets, chronological paused/manual/resume policies, and readiness through the separate dispatcher completion. Historical facts and fixed loan terms remain unchanged; A8-07 lifecycle activation is still unselected.
