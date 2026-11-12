# Public server API reference

Minecraft1.21.1, NeoForge21.1.252, Java21. These are trusted server-mod contracts. Client packets, UUIDs and copied DTOs grant no financial authority. See the [integration guide](INTEGRATION_API.md) for product and provider rules.

The [qualified core acceptance matrix](../.codex/A10/architecture/a10-acceptance.md) records reused checks and runtime limits. [Downstream handoff](../.codex/A10/architecture/a10-downstream.md) keeps B-series products and optional third-party modules separate; core acceptance changes no signature, capability or financial authority.

## Acquisition and compatibility

Call `Economy.serverServices(server)` on the server thread. It can return `null` outside an active Economy lifecycle. Require `ServerServices.State.READY` before requesting work. READY means foundation availability; it does not imply that scheduled finance, rates, item valuation or a physical terminal is currently usable.

| Accessor on current `ServerServices` | Interface / version |
| --- | --- |
| `ledger()` | `api.BankingIntegration`,3 |
| `simulation()` | `api.EconomicSimulationIntegration`,1 |
| `simulationHistory()` | `api.EconomicSimulationHistory`,1, additive cursor-bearing projection |
| `itemValuation(server)` | `api.ItemValuationIntegration`,1; identity adapter1 |
| `itemValuationProvider(server)` | `ItemValuationIntegration.Provider`, same session and loaded-module authentication |

Existing facade methods remain available. BankingIntegration3 adds trusted debit authority and flagged credit overloads. No legacy domain class is moved, removed or deprecated. Existing consumers can keep their compiled calls; consumers needing cursor-bearing rate history can adopt `simulationHistory()` independently. There is no unavoidable break or migration of saved data in A10-06. Interface version, current banking wire13, storage schema55, item identity adapter version and retained record/manifest versions are separate identifiers.

Legacy exposure is deliberate and remains supported: BankingIntegration uses `foundation.PartyAuthorization.Grant`, `pricing.PricingPolicy/WealthValuation/ServicePricing`, `institution.InstitutionCatalog`, `foundation.LedgerService.CreditDrawQuote`, `credit.CreditFacilities/CreditDrawAdmission`, `journal.TerminalPolicy/JournalService/RegisterSales`, and `loan.Loans.Capabilities`. EconomicSimulationIntegration uses `simulation.SimulationDiagnostics.Snapshot` and `simulation.macro.MacroPlans.Outcome`, transitively currency-prime policies, effective FX snapshots, model ratios and mode enums. Do not serialize these Java implementation names as an independent persistence format. Retain their explicit identities/versions with durable consumer data.

ItemValuationIntegration already owns its value records in `api`; its constructors currently use internal validation helpers. Its ownership grant still lives in foundation. New EconomicSimulationHistory DTO components use only Java and `api` value types, with defensive collection copies; its admission parameter intentionally reuses that same sealed grant. This is an additive value projection, not a replacement authority model.

## Authentication, completion and cancellation

Construct the target from the authenticated `ServerPlayer` and active world, then call `PartyAuthorization.player(player, Economy::serverServices, target)` on the server thread for each request. Never accept an actor/owner/grant from client fields. Factories refuse detached player instances; constructors are sealed. Inspection grants belong to separately audited operator paths and cannot substitute for owner reads or financial writes.

Services revalidate grant world/session/ownership at admission and on the worker where applicable. A detached grant is not a live player/menu audience: consumers must recheck current player identity, active services/session, world and their own menu/view revision before displaying completion. Reacquire after restart/login. Do not retain provider handles across server sessions.

Asynchronous service work uses the existing bounded storage/calculation/publication queues. Normal queued results publish during server ticks; immediate completed/failed stages are also possible. A continuation registered after completion can execute on its registering thread, and an explicitly asynchronous continuation uses its chosen executor. Route game/player access through `server.execute(...)`; never `join`, `get` or wait on the server/generation thread. Keep callbacks short: the publication start budget does not preempt a slow consumer callback.

No facade has a cancellation/rollback endpoint. Canceling a consumer's future or closing a view does not promise to interrupt admitted work, remove a committed provider publication or undo money/physical effects. Abandon display locally; retain original operation identity for writes and resolve the original outcome before any new submission. The [compiled authenticated example](../src/test/java/ca/emilybelnavis/mcmods/economy/examples/SimulationHistoryExample.java) returns a display-abandon action and revalidates its audience explicitly.

| Condition | Existing failure/absence contract |
| --- | --- |
| Wrong calling thread, invalid arguments, lifecycle misuse | Can throw synchronously; validation inside queued work can instead fail its stage. Handle both. |
| Banking owner misuse, unsupported TRADING quote | `IllegalArgumentException`; unavailable owner data can be `Optional.empty()` or an explained unavailable snapshot. |
| Legacy simulation denied snapshot/history | Empty optional / empty list, respectively. An empty legacy list does not distinguish denial from no history. |
| New simulation history denied session/world/inspection | `Optional.empty()`; an authorized empty history is `Optional.of(Page(empty, emptyCursor))`. |
| Queue/actor admission limit | Stage can fail with `storage.StorageException.Kind.OVERLOAD`; live monetary catch-up uses `ECONOMIC_CATCH_UP`. These legacy exception types remain exposed; do not parse translated text. |
| Valuation unauthenticated/expired session | Admission rejection, including `SecurityException`. |
| Valuation evaluation unavailable/backpressure | API-owned `Result.status()`/`reasonCode()`; `NOT_READY/BACKPRESSURE` at its32-read bound. No partial money in an unavailable result. |
| Valuation history backpressure | Exceptional stage, including `RejectedExecutionException("BACKPRESSURE")` at its32-read bound. |
| Mutation uncertain/declined | Inspect the original journal outcome/status/result. An exceptional callback or disconnection is not proof that a write did not commit. |

Storage/worker failure and shutdown can fail pending stages. Do not silently turn failures into zero balances, empty portfolios, current FX or another payment. This package adds no universal error envelope over existing signatures.

## BankingIntegration3 signature inventory

Types below are the existing Java types; `Grant` means `PartyAuthorization.Grant` and identifiers are `api.Identifiers` records.

| Method | Return type |
| --- | --- |
| `prime(Grant)` | `CompletionStage<Optional<PricingPolicy.Policy>>` |
| `prime(Grant, EconomyCurrency)` | `CompletionStage<Optional<CurrencyPrimes.Policy>>` |
| `primeAt(Grant, EconomyCurrency, long effectiveMillis)` | Same explicit-currency policy type |
| `valuation(Grant)` | `CompletionStage<Optional<WealthValuation.Snapshot>>` |
| `feeQuote(Grant, InstitutionId, ServicePricing.Service, Money)` | `CompletionStage<Optional<ServicePricing.Quote>>` |
| `quoteCreditDraw(Grant, UUID token, TerminalPolicy.Anchor, UUID generation, AccountId funding, long amount, boolean purchase)` | `CompletionStage<Optional<LedgerService.CreditDrawQuote>>` |
| `drawCredit(Grant, OperationId, RequestId, CreditFacilities.DrawRequest, CreditDrawAdmission.Proof)` | `JournalService.Submission` |
| `creditCheckout(Grant, CreditFacilities.DrawRequest, CreditDrawAdmission.Proof)` | `RegisterSales.SettlementComposer` |
| `drawCredit(Grant, OperationId, RequestId, CreditFacilities.DrawRequest, CreditDrawAdmission.Proof, boolean allowOverLimit)` | `JournalService.Submission` |
| `creditCheckout(Grant, CreditFacilities.DrawRequest, CreditDrawAdmission.Proof, boolean allowOverLimit)` | `RegisterSales.SettlementComposer` |
| `debitAccount(Grant, OperationId, RequestId, AccountId source, AccountId destination, Money, Overdrafts.DebitKind)` | `JournalService.Submission` |
| `version()` / `collateralCapabilities()` | `int` / `Loans.Capabilities` |

Global prime does not infer currency. `primeAt` requires a trusted past/current instant and preserves the saved policy identity; it does not reprice an old loan. Quotes retain exact currency, amounts, source revisions and expiry. Positive draw amounts must satisfy the current facility/product and sealed physical admission; a quote alone is not a draw capability. Purchase composition belongs only in the existing register COMMIT transaction. Current fixed unsecured lending remains supported; variable/secured products remain unsupported with the existing reason. No broad remote lending, account access or financial event stream is added.

## Simulation signatures and exact history

Legacy `EconomicSimulationIntegration.snapshot(Grant)` returns cached `Optional<SimulationDiagnostics.Snapshot>`; `history(Grant, long beforeDay, int limit)` returns `CompletionStage<List<MacroPlans.Outcome>>`; `version()` returns1. Snapshot fields preserve sampled time, availability/reason, mode, coverage/baseline, FX age, A/F/H boundaries, config/policy revisions, prime/FX identities and source/limit manifests. Reads do not activate sources or observe/advance financial time. Legacy history returns selected plans, not proof that their whole basket or dispatcher event completed. Its outcomes have no day field, so new paginating consumers should use the additive interface.

`EconomicSimulationHistory.page(Grant, long beforeDay, int limit)` returns `CompletionStage<Optional<Page>>`. Start with `Long.MAX_VALUE`; limit is1–16 and the cursor must be nonnegative. Results descend by captured zero-based financial-day index, strictly less than `beforeDay`. This is the recorded window index, not a UTC calendar date; a completed window boundary is origin+(day+1)×1,200,000ms. Follow `page.nextBeforeDay()` only when present. It equals the last emitted day when a bounded lookahead proves more rows; empty means terminal. Newer appends cannot repeat above an already consumed cursor. No frozen-query token or server-held cursor is promised.

`Page` contains immutable `List<Entry>` and `OptionalLong nextBeforeDay`. `Entry` contains `recordVersion`, `financialDay`, `effectiveMillis`, window/world/policy identity and revision, API-owned `Mode`, input/automatic hashes, exact prime and FX maps, API-owned numerator/denominator `Ratio`, explanations and original hash. There are at most16 entries,11 currencies per map and32 explanations. Persisted selected-plan record version1 is supported and explicitly returned; maps/ratios/hashes are copied without rounding, normalization or recomputation. A selected-only plan can appear before publication completes; history presence is not financial readiness or settlement authority. No read repairs projections, advances a clock or registers a provider.

## ItemValuationIntegration1 signature inventory

| Method | Return type |
| --- | --- |
| `currentCached(Grant, Request)` | `Result` |
| `evaluate(Grant, Request)` | `CompletionStage<Result>` |
| `history(Grant, Optional<UUID> beforeSnapshot, int limit)` | `CompletionStage<List<SnapshotMetadata>>` |
| `version()` | `int`,1 |
| `Provider.register(Contribution)` / `Provider.remove(String providerId)` | `CompletionStage<SnapshotMetadata>` |

API-owned request/value types are `Identity`, `SnapshotSelector` (`Current`/`Retained`), `Request`, `Exact`, `SnapshotRef`, `Step`, `Explanation`, `Result`, `SnapshotMetadata`, `Status` and `Coverage`. Contribution types are `Base`, `ExactSelector`, `Material`, `MaterialRule` and `Contribution`. Requests allow quantity1–1,000,000 and adapter1; canonical components are bounded to64KiB. Explanations have at most256 steps. Snapshot history has limit1–16 and resolves the exclusive snapshot UUID to its retained publication revision; an unknown cursor rejects.

`currentCached` accepts only Current. A cache miss starts bounded asynchronous evaluation and returns `NOT_READY/PENDING`; it is not a promise of a ready value or an entirely passive lookup. Current reads bind their effective FX/epoch horizon. Retained reads require both original valuation and FX UUIDs and preserve manifest/algorithm/identity-adapter/config/recipe/provider versions; they never substitute today's FX. Money/reference values confer no qualification, collateral, trade or redemption rights.

Provider acquisition must originate in the contributing loaded mod's own module/namespace. One candidate mutation is pending per session; busy/reloading candidates can reject with `VALUATION_BUSY`, superseded reload work with `RELOAD_SUPERSEDED_PROVIDER_CHANGE`, and session shutdown with `SESSION_ENDED`. A changed revision is a complete replacement contribution; conflicting claims reject atomically. Await successful publication, not handle construction. Removal/restart preserves retained snapshots; accepted contributions survive reload in the session but must be registered again next session. Detailed contribution limits and examples remain in the [guide](INTEGRATION_API.md#generic-provider-registration).

## Existing hooks and event decision

The concrete history consumer uses its completion stage. Provider contributors use publication completion. Existing core financial consumers compose effects through the journal/register COMMIT path, whose preparation must preserve original request identity and transactional retry semantics. None requires a new broadcast event, so A10-06 adds no event bus or event type.

NeoForge server lifecycle and resource-reload hooks install/stop services and trigger valuation capture/publication internally; they are not financial success events. `SqliteStorage.commitObserver`, projection definitions, `storage`/`tradeStorage` callbacks and scheduler/provider registries are implementation hooks, not public external mutation APIs. Replacing the single commit observer would interfere with core activity/projection handling. Do not attach arbitrary I/O or game access on the storage worker.

No exactly-once notification, durable subscription, callback retry, event queue/backpressure policy or global cross-facade snapshot is promised. Preserve original operation IDs and use transactional composition/outcome lookup for money. Any future event needs its own concrete consumer and explicit thread, order, failure, backpressure and idempotency contract. Third-party adapters, wallets and compatibility catalogs remain separate modules outside A10.
