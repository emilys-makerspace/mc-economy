package ca.emilybelnavis.mcmods.economy.recovery;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher;
import ca.emilybelnavis.mcmods.economy.clock.EconomicClock;
import ca.emilybelnavis.mcmods.economy.clock.FinancialCalendar;
import ca.emilybelnavis.mcmods.economy.foundation.SchedulingConfig;
import ca.emilybelnavis.mcmods.economy.fx.FxDiagnostics;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.BankProviders;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCapital;
import ca.emilybelnavis.mcmods.economy.journal.CapitalPosting;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Storage recovery -> compatibility/migrations -> projections -> catch-up -> consistent
 * publication.
 */
public final class FoundationRuntime implements FoundationStore {
  public record Configuration(
      RecordCatalog records,
      List<ProjectionManager.Definition> projections,
      List<EconomicClock.SystemDefinition> systems,
      Clock wall,
      Map<String, EconomicClock.DeferredSystem> deferredSystems,
      boolean financialCalendar,
      List<DeadlineDispatcher.Provider> deadlineProviders,
      FinancialCalendar.Config calendarConfig,
      ca.emilybelnavis.mcmods.economy.pricing.WealthValuation valuation) {
    public Configuration(
        RecordCatalog records,
        List<ProjectionManager.Definition> projections,
        List<EconomicClock.SystemDefinition> systems,
        Clock wall) {
      this(records, projections, systems, wall, Map.of());
    }

    public Configuration(
        RecordCatalog records,
        List<ProjectionManager.Definition> projections,
        List<EconomicClock.SystemDefinition> systems,
        Clock wall,
        Map<String, EconomicClock.DeferredSystem> deferredSystems) {
      this(
          records,
          projections,
          systems,
          wall,
          deferredSystems,
          false,
          List.of(),
          FinancialCalendar.Config.defaults());
    }

    public Configuration(
        RecordCatalog records,
        List<ProjectionManager.Definition> projections,
        List<EconomicClock.SystemDefinition> systems,
        Clock wall,
        Map<String, EconomicClock.DeferredSystem> deferredSystems,
        boolean financialCalendar,
        List<DeadlineDispatcher.Provider> deadlineProviders,
        FinancialCalendar.Config calendarConfig) {
      this(
          records,
          projections,
          systems,
          wall,
          deferredSystems,
          financialCalendar,
          deadlineProviders,
          calendarConfig,
          ca.emilybelnavis.mcmods.economy.pricing.PricingRuntime.wealth());
    }

    public Configuration {
      Objects.requireNonNull(records);
      projections = List.copyOf(projections);
      systems = List.copyOf(systems);
      Objects.requireNonNull(wall);
      deferredSystems = Map.copyOf(deferredSystems);
      deadlineProviders = List.copyOf(deadlineProviders);
      Objects.requireNonNull(calendarConfig);
      Objects.requireNonNull(valuation);
      if (!financialCalendar && !deadlineProviders.isEmpty())
        throw new IllegalArgumentException("Deadline providers require calendar activation");
    }

    public static Configuration production(Clock wall) {
      return production(wall, FinancialCalendar.Config.defaults());
    }

    public static Configuration production(Clock wall, FinancialCalendar.Config config) {
      return production(wall, config, List.of());
    }

    public static Configuration production(
        Clock wall,
        FinancialCalendar.Config config,
        List<ca.emilybelnavis.mcmods.economy.pricing.WealthValuation.Source> sources) {
      var valuation = ca.emilybelnavis.mcmods.economy.pricing.PricingRuntime.wealth(sources);
      return new Configuration(
          RecordCatalog.core(),
          List.of(),
          List.of(FxService.definition()),
          wall,
          Map.of(FxService.SYSTEM, FxService.deferred()),
          true,
          java.util.stream.Stream.of(
                  ca.emilybelnavis.mcmods.economy.pricing.PricingRuntime.providers(valuation),
                  ca.emilybelnavis.mcmods.economy.credit.CreditRuntime.providers(),
                  java.util.List.of(
                      ca.emilybelnavis.mcmods.economy.loan.LoanRuntime.provider(),
                      ca.emilybelnavis.mcmods.economy.autopay.AutopayRuntime.provider()),
                  ca.emilybelnavis.mcmods.economy.debt.DebtRuntime.providers(),
                  ca.emilybelnavis.mcmods.economy.credit.CreditScoreRuntime.providers(),
                  java.util.List.<DeadlineDispatcher.Provider>of(
                      new ca.emilybelnavis.mcmods.economy.simulation.macro.MacroProvider()))
              .flatMap(java.util.Collection::stream)
              .toList(),
          config,
          valuation);
    }

    public static Configuration core() {
      return new Configuration(RecordCatalog.core(), List.of(), List.of(), Clock.systemUTC());
    }
  }

  private final Configuration configuration;
  private final SqliteStorage store;
  private ProjectionManager projections;
  private EconomicClock clock;
  private DeadlineDispatcher deadlines;
  private InstitutionCapital capital;
  private ca.emilybelnavis.mcmods.economy.interest.DepositYield depositYield;
  private ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityRuntime activity;
  private boolean providersMigrated;
  private boolean debtMigrated;
  private boolean legacyDebtReconciled;
  private boolean scoreSourcesMigrated;
  private boolean historicalScoresMigrated;
  private boolean autopayMigrated;
  private int phase;
  private boolean ready;
  private boolean valuationSourcesReady;
  private boolean economicProjectionsPending;
  private Path world;
  private volatile WorldShutdown.Evidence closedEvidence;
  private final AtomicLong observedDeadlineTurns = new AtomicLong();

  /** Actual dispatcher advances observed in this runtime session. */
  public long observedDeadlineTurns() {
    return observedDeadlineTurns.get();
  }

  public FoundationRuntime(Configuration configuration) {
    this(configuration, SchedulingConfig.defaults().sqliteLockTimeoutMillis());
  }

  public FoundationRuntime(Configuration configuration, int sqliteLockTimeoutMillis) {
    this.configuration = configuration;
    this.store = new SqliteStorage(configuration.records(), sqliteLockTimeoutMillis);
  }

  private ca.emilybelnavis.mcmods.economy.simulation.SimulationConfig simulationConfig;
  private ca.emilybelnavis.mcmods.economy.simulation.SimulationLifecycle.Status simulationStatus =
      new ca.emilybelnavis.mcmods.economy.simulation.SimulationLifecycle.Status(
          false, "NOT_CONFIGURED");

  public FoundationRuntime(
      Configuration configuration,
      int timeout,
      ca.emilybelnavis.mcmods.economy.simulation.SimulationConfig simulation) {
    this(configuration, timeout);
    this.simulationConfig = java.util.Objects.requireNonNull(simulation);
  }

  public ca.emilybelnavis.mcmods.economy.simulation.SimulationConfig simulationConfig() {
    return simulationConfig == null
        ? ca.emilybelnavis.mcmods.economy.simulation.SimulationConfig.defaults()
        : simulationConfig;
  }

  public ca.emilybelnavis.mcmods.economy.simulation.SimulationLifecycle.Status simulationStatus() {
    return simulationStatus;
  }

  private void prepareSimulation() {
    if (simulationConfig != null && deadlines != null)
      simulationStatus =
          ca.emilybelnavis.mcmods.economy.simulation.SimulationLifecycle.prepare(
              store, clock, deadlines, simulationConfig, configuration.wall().instant());
  }

  public FoundationRuntime() {
    this(Configuration.core());
  }

  public void initialize(Path world) {
    this.world = world.toAbsolutePath().normalize();
    store.beginInitialization(world);
    try {
      WorldShutdown.active(this.world);
    } catch (java.io.IOException e) {
      throw new StorageException(
          StorageException.Kind.CORRUPTION_IO, "Cannot invalidate clean-world marker", e);
    }
  }

  public Progress startupStep() {
    if (phase == 0) {
      var progress = store.advanceInitialization();
      if (!progress.ready()) return progress;
      store.cleanShutdownEligible(false);
      projections = new ProjectionManager(store, configuration.projections());
      clock =
          new EconomicClock(
              store,
              configuration.wall(),
              configuration.systems(),
              configuration.deferredSystems(),
              DeadlineDispatcher.implementations(configuration.deadlineProviders()));
      if (!configuration.financialCalendar()
          && !store
              .transaction(tx -> tx.query("SELECT singleton FROM financial_calendar", List.of(), 1))
              .isEmpty())
        throw new StorageException(
            StorageException.Kind.SCHEMA, "Installed financial calendar requires its scheduler");
      if (configuration.financialCalendar())
        deadlines =
            new DeadlineDispatcher(
                store,
                configuration.wall(),
                configuration.deadlineProviders(),
                configuration.calendarConfig().pageSize());
      if (configuration.financialCalendar()
          && configuration.deferredSystems().containsKey(FxService.SYSTEM))
        capital =
            new InstitutionCapital(
                store, systemActor("institution-capital"), CapitalPosting.poster());
      if (configuration.deadlineProviders().stream()
          .anyMatch(
              p ->
                  p.id()
                      .equals(ca.emilybelnavis.mcmods.economy.pricing.TierReevaluation.PROVIDER))) {
        depositYield =
            ca.emilybelnavis.mcmods.economy.pricing.PricingRuntime.yield(
                store.snapshot().worldId(), configuration.valuation());
      }
      if (configuration.financialCalendar())
        activity =
            new ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityRuntime(
                store, configuration.wall());
      if (depositYield != null || activity != null) {
        store.commitObserver(
            (intent, reads, commit) -> {
              long effective = effectiveMillis(commit);
              ca.emilybelnavis.mcmods.economy.journal.JournalEngine.Decision decision = commit;
              if (depositYield != null) {
                decision =
                    ca.emilybelnavis.mcmods.economy.pricing.PricingAdmission.check(
                        intent,
                        reads,
                        commit,
                        store.snapshot().worldId(),
                        java.time.Instant.ofEpochMilli(effective),
                        configuration.valuation());
                if (decision
                    instanceof
                    ca.emilybelnavis.mcmods.economy.journal.JournalEngine.Commit accepted)
                  decision = depositYield.observe(intent, reads, accepted, effective);
              }
              return activity != null
                      && decision
                          instanceof
                          ca.emilybelnavis.mcmods.economy.journal.JournalEngine.Commit accepted
                  ? activity.observe(intent, reads, accepted, effective)
                  : decision;
            });
      }
      phase = 1;
    }
    if (!providersMigrated) {
      if (!migrateProviders()) return new Progress("bank-provider-migration", 0, 1, false);
      providersMigrated = true;
    }
    if (!legacyDebtReconciled
        && configuration.deadlineProviders().stream()
            .anyMatch(p -> p.id().equals("economy:a6/debt-default"))) {
      if (!ca.emilybelnavis.mcmods.economy.debt.DebtLegacyReconciliation.step(store))
        return new Progress("debt-history-reconciliation", 0, 1, false);
      legacyDebtReconciled = true;
    }
    if (!debtMigrated
        && configuration.deadlineProviders().stream()
            .anyMatch(p -> p.id().equals("economy:a6/debt-default"))) {
      if (!ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.bootstrap(store))
        return new Progress("debt-obligation-migration", 0, 1, false);
      debtMigrated = true;
    }
    if (configuration.deadlineProviders().stream()
        .anyMatch(
            p ->
                p.id()
                    .equals(ca.emilybelnavis.mcmods.economy.credit.CreditScoreRuntime.PROVIDER))) {
      if (!scoreSourcesMigrated) {
        if (!ca.emilybelnavis.mcmods.economy.credit.CreditReporting.bootstrap(store))
          return new Progress("score-source-migration", 0, 1, false);
        scoreSourcesMigrated = true;
      }
      if (!historicalScoresMigrated) {
        if (!ca.emilybelnavis.mcmods.economy.credit.CreditScoreRuntime.bootstrap(store))
          return new Progress("score-history-reconciliation", 0, 1, false);
        historicalScoresMigrated = true;
      }
    }
    if (!autopayMigrated
        && configuration.deadlineProviders().stream()
            .anyMatch(
                p ->
                    p.id()
                        .equals(ca.emilybelnavis.mcmods.economy.autopay.AutopayRuntime.PROVIDER))) {
      if (!ca.emilybelnavis.mcmods.economy.autopay.Autopay.bootstrapHistory(store))
        return new Progress("autopay-statement-history", 0, 1, false);
      autopayMigrated = true;
    }
    if (depositYield != null && !valuationSourcesReady) {
      if (!ca.emilybelnavis.mcmods.economy.pricing.ValuationSources.prepare(
          store, configuration.valuation()))
        return new Progress("valuation-source-registration", 0, 1, false);
      valuationSourcesReady = true;
    }
    if (phase == 1 || phase == 3) {
      var progress = projections.step(16);
      if (!progress.ready())
        return new Progress(
            "projection:" + progress.name(), progress.applied(), progress.target(), false);
      if (phase == 3) {
        long before = store.lastCommittedSequence();
        var finalClock = advanceScheduled();
        if (!finalClock.ready()) {
          phase = 2;
          return new Progress(
              "catch-up", finalClock.completedSequence(), finalClock.outstandingIntervals(), false);
        }
        if (configuration.deferredSystems().containsKey(FxService.SYSTEM))
          FxService.prepareChfActivation(store, clock, configuration.wall());
        if (before != store.lastCommittedSequence())
          return new Progress("projection-final-validation", 0, 1, false);
        if (!activatePricing()) return new Progress("pricing-activation", 0, 1, false);
        if (activity != null && !activity.prepare())
          return new Progress("activity-source-registration", 0, 1, false);
        prepareSimulation();
        ready = true;
        store.cleanShutdownEligible(true);
        phase = 4;
        return new Progress("ready", 1, 1, true);
      }
      clock.prepare();
      if (deadlines != null) deadlines.prepare();
      phase = 2;
    }
    if (phase == 2) {
      var progress = advanceScheduled();
      if (!progress.ready())
        return new Progress(
            progress.waitingForWall() ? "waiting-for-wall-clock" : "catch-up",
            progress.completedSequence(),
            progress.outstandingIntervals(),
            false);
      phase = 3;
      return new Progress("projection-final-validation", 0, 1, false);
    }
    return new Progress("ready", 1, 1, ready);
  }

  @Override
  public boolean schedulesEconomy() {
    return !configuration.systems().isEmpty() || configuration.financialCalendar();
  }

  @Override
  public void requireEconomicCurrent() {
    if (schedulesEconomy() && (!scheduledProgress(true).ready() || economicProjectionsPending)) {
      throw new StorageException(
          StorageException.Kind.ECONOMIC_CATCH_UP, "Economic catch-up is in progress");
    }
  }

  @Override
  public EconomicStep<EconomicClock.Output> prepareEconomicStep() {
    if (baseScheduledProgress(true).ready()) prepareSimulation();
    // Domain planning and effects are bounded worker-only work; one journal child per turn.
    // Existing fixed simulations retain their detached calculation pipeline.
    if (baseScheduledProgress(true).ready() && capitalPending()) {
      capital.advance(configuration.wall().instant());
      economicProjectionsPending = true;
      return new EconomicStep<>(economicProgress(scheduledProgress(true)), null, null);
    }
    if (domainFirst()) {
      observedDeadlineTurns.updateAndGet(Math::incrementExact);
      deadlines.advance();
      economicProjectionsPending = true;
      return new EconomicStep<>(economicProgress(scheduledProgress(true)), null, null);
    }
    var prepared = clock.prepareStep();
    var work = prepared.work();
    var progress = economicProgress(scheduledProgress(true));
    if (work == null) return new EconomicStep<>(progress, null, null);
    return new EconomicStep<>(
        progress,
        work::calculate,
        output -> {
          if (domainFirst()) return economicProgress(scheduledProgress(true));
          economicProjectionsPending = true;
          clock.commit(work, output);
          return economicProgress(scheduledProgress(true));
        });
  }

  private EconomicProgress economicProgress(EconomicClock.Progress progress) {
    if (progress.ready() && economicProjectionsPending) {
      economicProjectionsPending = !projections.step(16).ready();
      progress = scheduledProgress(false);
    }
    return new EconomicProgress(
        progress.completedSequence(),
        progress.outstandingIntervals(),
        progress.waitingForWall(),
        progress.ready() && !economicProjectionsPending);
  }

  private boolean domainFirst() {
    if (deadlines == null) return false;
    var event = deadlines.nextEvent();
    Long fixed = clock.nextDueMillis();
    // All existing fixed systems publish at stage zero. Preserve their dependency order and IDs.
    return event != null
        && event.effectiveMillis() <= configuration.wall().millis()
        && (fixed == null || event.effectiveMillis() < fixed);
  }

  private EconomicClock.Progress scheduledProgress(boolean observe) {
    var progress = baseScheduledProgress(observe);
    boolean pending = capitalPending();
    return new EconomicClock.Progress(
        progress.completedSequence(),
        Math.addExact(progress.outstandingIntervals(), pending ? 1 : 0),
        progress.waitingForWall(),
        progress.ready() && !pending);
  }

  private EconomicClock.Progress baseScheduledProgress(boolean observe) {
    var fixed = observe ? clock.observeCurrentDebt() : clock.currentProgress();
    if (deadlines == null) return fixed;
    var domain = observe ? deadlines.observeCurrentDebt() : deadlines.progress();
    return new EconomicClock.Progress(
        fixed.completedSequence(),
        Math.addExact(fixed.outstandingIntervals(), domain.outstandingEvents()),
        fixed.waitingForWall() || domain.waitingForWall(),
        fixed.ready() && domain.ready());
  }

  private EconomicClock.Progress advanceScheduled() {
    if (baseScheduledProgress(true).ready() && capitalPending())
      capital.advance(configuration.wall().instant());
    else if (domainFirst()) {
      observedDeadlineTurns.updateAndGet(Math::incrementExact);
      deadlines.advance();
    } else clock.runBatch(1);
    return scheduledProgress(true);
  }

  private long effectiveMillis(
      ca.emilybelnavis.mcmods.economy.journal.JournalEngine.Commit commit) {
    for (var record : commit.records()) {
      if (record.type().equals(ca.emilybelnavis.mcmods.economy.clock.EconomicRecord.TYPE))
        return ca.emilybelnavis.mcmods.economy.clock.EconomicRecord.decode(record.payload())
            .endMillis();
      if (record.type().equals(DeadlineDispatcher.PROGRESS_RECORD)
          || record.type().equals(DeadlineDispatcher.COMPLETION))
        return Long.parseLong(record.payload().split("\\|", -1)[3]);
    }
    return configuration.wall().millis();
  }

  private boolean activatePricing() {
    if (depositYield == null) return true;
    var rows =
        store.transaction(
            tx ->
                tx.query(
                    "SELECT start_ms FROM pricing_activation WHERE singleton=1", List.of(), 1));
    if (rows.isEmpty()) {
      activate(
          ca.emilybelnavis.mcmods.economy.pricing.TierReevaluation.ACTIVATE,
          ca.emilybelnavis.mcmods.economy.pricing.TierReevaluation.activation(configuration.wall()),
          new ca.emilybelnavis.mcmods.economy.journal.Journal.CanonicalRequest(1, Map.of()));
      return false;
    }
    if (store
        .transaction(tx -> tx.query("SELECT singleton FROM deposit_yield_activation", List.of(), 1))
        .isEmpty()) {
      var instant = java.time.Instant.ofEpochMilli(Long.parseLong(rows.getFirst().get("start_ms")));
      activate(
          ca.emilybelnavis.mcmods.economy.interest.DepositYield.ACTIVATE,
          depositYield.activation(instant),
          new ca.emilybelnavis.mcmods.economy.journal.Journal.CanonicalRequest(
              1, Map.of("effective", Long.toString(instant.toEpochMilli()))));
      return false;
    }
    return true;
  }

  private void activate(
      ca.emilybelnavis.mcmods.economy.journal.Journal.Kind kind,
      ca.emilybelnavis.mcmods.economy.journal.JournalEngine.Registration handler,
      ca.emilybelnavis.mcmods.economy.journal.Journal.CanonicalRequest request) {
    var actor = systemActor("pricing-activation");
    var id =
        UUID.nameUUIDFromBytes(
            (store.snapshot().worldId() + "|" + kind.operationType())
                .getBytes(StandardCharsets.UTF_8));
    var engine =
        new ca.emilybelnavis.mcmods.economy.journal.JournalEngine(
            store, (a, k) -> a.equals(actor) && k.equals(kind), Map.of(kind, handler));
    var outcome =
        engine.submit(
            new ca.emilybelnavis.mcmods.economy.journal.Journal.Intent(
                new OperationId(id), new RequestId(id), actor, kind, request, null, null));
    if (outcome.status() != ca.emilybelnavis.mcmods.economy.journal.Journal.Status.COMMITTED)
      throw new StorageException(StorageException.Kind.MIGRATION, "Pricing activation failed");
  }

  private boolean capitalPending() {
    if (capital == null) return false;
    var state = capital.prepare(configuration.wall().instant()).state();
    return state == InstitutionCapital.State.READY || state == InstitutionCapital.State.ALLOCATING;
  }

  /**
   * Missing denomination/FX is dormant; once funding starts its captured publication survives
   * catch-up.
   */
  public InstitutionCapital.Status readInstitutionCapitalStatus() {
    return capital == null ? null : capital.prepare(configuration.wall().instant());
  }

  private SystemActorId systemActor(String purpose) {
    return new SystemActorId(
        UUID.nameUUIDFromBytes(
            ("economy:runtime:v1|" + store.snapshot().worldId().value() + "|" + purpose)
                .getBytes(StandardCharsets.UTF_8)));
  }

  /** Read at most one page; no account is assigned an invented historical provider. */
  private boolean migrateProviders() {
    var cursor =
        store.transaction(
            tx ->
                tx.query(
                        "SELECT cursor FROM bank_provider_migration WHERE singleton=1",
                        List.of(),
                        1)
                    .getFirst()
                    .get("cursor"));
    boolean empty =
        store.transaction(
            tx ->
                tx.query(
                        "SELECT account_id FROM account_catalog WHERE purpose='BANK' AND account_id>? AND NOT EXISTS (SELECT 1 FROM bank_account_providers p WHERE p.account_id=account_catalog.account_id) ORDER BY account_id LIMIT 1",
                        List.of(cursor),
                        1)
                    .isEmpty());
    if (empty) return true;
    var actor = systemActor("bank-provider-migration");
    var operation =
        UUID.nameUUIDFromBytes(
            ("economy:provider-migration:v1|" + store.snapshot().worldId().value() + "|" + cursor)
                .getBytes(StandardCharsets.UTF_8));
    var engine =
        new JournalEngine(
            store,
            (candidate, kind) -> candidate.equals(actor) && kind.equals(BankProviders.MIGRATE),
            Map.of(BankProviders.MIGRATE, BankProviders.migrateStep()));
    var outcome =
        engine.submit(
            new Journal.Intent(
                new OperationId(operation),
                new RequestId(operation),
                actor,
                BankProviders.MIGRATE,
                new Journal.CanonicalRequest(1, Map.of()),
                null,
                null));
    if (outcome.status() != Journal.Status.COMMITTED)
      throw new StorageException(
          StorageException.Kind.MIGRATION, "Bank provider migration could not commit a page");
    return false;
  }

  /** Read-only worker sample, also available during bounded startup catch-up. */
  public ca.emilybelnavis.mcmods.economy.clock.CalendarDiagnostics readCalendarDiagnostics() {
    if (clock == null) return null;
    return store.transaction(
        tx ->
            ca.emilybelnavis.mcmods.economy.clock.CalendarDiagnostics.read(
                tx,
                configuration.wall().instant(),
                deadlines == null ? reads -> null : deadlines::readNextEvent));
  }

  public FxDiagnostics readFxDiagnostics() {
    if (clock == null || !configuration.deferredSystems().containsKey(FxService.SYSTEM))
      return null;
    return store.transaction(tx -> FxDiagnostics.read(tx, configuration.wall().instant()));
  }

  @Override
  public ca.emilybelnavis.mcmods.economy.journal.JournalEngine.Decision decorateCommit(
      ca.emilybelnavis.mcmods.economy.journal.Journal.Intent intent,
      ca.emilybelnavis.mcmods.economy.journal.JournalEngine.ReadView reads,
      ca.emilybelnavis.mcmods.economy.journal.JournalEngine.Commit commit) {
    return store.decorateCommit(intent, reads, commit);
  }

  public ca.emilybelnavis.mcmods.economy.pricing.WealthValuation valuation() {
    return configuration.valuation();
  }

  public RecordCatalog recordCatalog() {
    return configuration.records();
  }

  public Snapshot snapshot() {
    return store.snapshot();
  }

  public long lastCommittedSequence() {
    return store.lastCommittedSequence();
  }

  public <T> T transaction(Work<T> work) {
    return store.transaction(work);
  }

  public void recover() {
    store.recover();
  }

  public void close() throws Exception {
    WorldShutdown.Evidence evidence = null;
    try {
      if (ready)
        evidence =
            store.transaction(
                tx ->
                    new WorldShutdown.Evidence(
                        store.snapshot().worldId().value().toString(),
                        store.snapshot().schemaVersion(),
                        Long.parseLong(
                            tx.query(
                                    "SELECT COALESCE(max(sequence),0) AS sequence FROM journal_transactions",
                                    List.of(),
                                    1)
                                .getFirst()
                                .get("sequence")),
                        Long.parseLong(
                            tx.query("SELECT global_sequence FROM economic_metadata", List.of(), 1)
                                .getFirst()
                                .get("global_sequence"))));
    } finally {
      store.close();
    }
    closedEvidence = evidence;
  }

  /** Invoke only after Minecraft has finished saving this world's inventories/chunks. */
  public void afterMinecraftStopped() throws java.io.IOException {
    if (closedEvidence == null)
      throw new java.io.IOException("No clean completed foundation shutdown");
    WorldShutdown.complete(world, closedEvidence);
  }

  public ProjectionManager projections() {
    if (!ready) throw new IllegalStateException("Foundation not ready");
    return projections;
  }

  public EconomicClock clock() {
    if (!ready) throw new IllegalStateException("Foundation not ready");
    return clock;
  }
}
