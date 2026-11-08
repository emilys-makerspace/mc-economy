package ca.emilybelnavis.mcmods.economy.foundation;

import java.nio.file.Path;
import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.account.AccountRebasePlanner;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity;
import ca.emilybelnavis.mcmods.economy.api.AccountFunds;
import ca.emilybelnavis.mcmods.economy.api.FundingEligibility;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import ca.emilybelnavis.mcmods.economy.fx.FxDiagnostics;
import ca.emilybelnavis.mcmods.economy.fx.DenominationChange;
import ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.journal.JournalService;
import ca.emilybelnavis.mcmods.economy.journal.MerchantPayments;
import ca.emilybelnavis.mcmods.economy.journal.Custody;
import ca.emilybelnavis.mcmods.economy.journal.CashIssuance;
import ca.emilybelnavis.mcmods.economy.journal.Deposits;
import ca.emilybelnavis.mcmods.economy.journal.Withdrawals;
import ca.emilybelnavis.mcmods.economy.journal.RegisterSales;
import ca.emilybelnavis.mcmods.economy.journal.RegisterSaleSettlement;
import ca.emilybelnavis.mcmods.economy.journal.DepositReturns;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.ActorId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.OperationId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.RequestId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import java.util.Optional;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/** One instance per server. Minecraft objects must never be captured in worker suppliers. */
public final class ServerServices {
    public enum State { INITIALIZING, READY, UNAVAILABLE, STOPPING, STOPPED }
    public static final int STORAGE_CAPACITY = 256;
    public static final int CALCULATION_CAPACITY = 64;
    private final UUID sessionId = UUID.randomUUID();
    private final Thread owner = Thread.currentThread();
    private final StorageLifecycle storage;
    private final ThreadPoolExecutor storageWorker;
    private final SchedulingConfig config;
    private final ThreadPoolExecutor calculations;
    private final ArrayBlockingQueue<TimedTask> callbacks;
    private final int publicationCapacity;
    private final Map<ActorId, Integer> actorPending = new HashMap<>();
    private final Map<Object, CompletionStage<?>> sharedReads = new HashMap<>();
    private final boolean scheduleEconomy;
    private CompletableFuture<StorageLifecycle.EconomicStep<?>> prepared;
    private EconomicCalculation<?> calculating;
    private long nextEconomicProbe;
    private record EconomicCalculation<T>(CompletableFuture<T> result,
                                          java.util.function.Function<T, StorageLifecycle.EconomicProgress> commit) {
        StorageLifecycle.EconomicProgress finish() { return commit.apply(result.join()); }
    }
    private CompletableFuture<StorageLifecycle.EconomicProgress> committed;
    private StorageLifecycle.EconomicProgress economy;
    private boolean economyObserved;
    private int unresolvedAtShutdown;
    private LedgerService ledger;
    private Runnable denominationChange = () -> {};
    private final OperationDiagnostics operationDiagnostics = new OperationDiagnostics();
    private final Map<Thread, Long> activeStorage = new ConcurrentHashMap<>();
    private final Map<Thread, Long> activeCalculations = new ConcurrentHashMap<>();
    private volatile DiagnosticsReport.StorageDetails storageDetails;
    private volatile ca.emilybelnavis.mcmods.economy.simulation.SimulationDiagnostics.Snapshot simulationSample;
    private volatile String simulationFailure="NOT_SAMPLED";
    private record FxSample(FxDiagnostics snapshot, String readFailure) {}
    private volatile FxSample fxSample = new FxSample(null, null);
    private record CalendarSample(ca.emilybelnavis.mcmods.economy.clock.CalendarDiagnostics snapshot, String readFailure) {}
    private volatile CalendarSample calendarSample = new CalendarSample(null, null);
    private long nextStartupDiagnosticsSample;
    private String economicRetry;
    private long nextDiagnosticsSample;
    private long nextPaymentExpiry;
    private CompletionStage<Void> paymentExpiry;
    private long rateSampleTime;
    private long rateSampleSequence;
    private Double observedRate;
    private CompletableFuture<Void> diagnosticProbe;

    private record TimedTask(long enqueued, Runnable action) implements Runnable {
        public void run() { action.run(); }
    }
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CountDownLatch disposed = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private CompletableFuture<StorageLifecycle.Progress> initialized;
    private volatile StorageLifecycle.Progress progress = new StorageLifecycle.Progress("storage", 0, 0, false);
    private volatile State state = State.INITIALIZING;
    private volatile Throwable failure;
    private volatile Throwable disposalFailure;
    private boolean stoppedCleanly;

    private final Path worldDirectory;

    Path worldDirectory() {
        checkOwner();
        return worldDirectory;
    }

    public boolean debugGui() { return config.debugGui(); }

    public ServerServices(Path worldDirectory, StorageLifecycle storage) {
        this(worldDirectory, storage, SchedulingConfig.defaults());
    }

    public ServerServices(Path worldDirectory, StorageLifecycle storage, SchedulingConfig config) {
        this(worldDirectory,storage,config,new ca.emilybelnavis.mcmods.economy.pricing.ServicePricing(storage instanceof FoundationRuntime runtime?runtime.valuation():ca.emilybelnavis.mcmods.economy.pricing.PricingRuntime.wealth()));
    }
    public ServerServices(Path worldDirectory, StorageLifecycle storage, SchedulingConfig config,
                          ca.emilybelnavis.mcmods.economy.pricing.ServicePricing servicePricing) {
        this.servicePricing=java.util.Objects.requireNonNull(servicePricing);
        this.worldDirectory = worldDirectory.toAbsolutePath().normalize();
        this.storage = storage;
        this.config = config;
        scheduleEconomy = storage.schedulesEconomy();
        economy = new StorageLifecycle.EconomicProgress(0, 0, false, !scheduleEconomy);
        publicationCapacity = config.storageQueueCapacity() + config.calculationQueueCapacity()
                + config.calculationWorkers() + 1;
        callbacks = new ArrayBlockingQueue<>(publicationCapacity);
        // Reserve one waiting position per worker path for the single internal economic step.
        int reserved = scheduleEconomy ? 1 : 0;
        calculations = worker(config.calculationWorkers(), config.calculationQueueCapacity() + reserved, "economy-calculation");
        storageWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(config.storageQueueCapacity() + reserved), runnable -> {
                    Thread thread = new Thread(() -> {
                        try { runnable.run(); }
                        finally {
                            try { this.storage.close(); }
                            catch (Exception e) { disposalFailure = e; failure = e; }
                            finally { closed.set(true); disposed.countDown(); }
                        }
                    }, "economy-storage");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        initialized = runOn(() -> {
            try { storage.initialize(worldDirectory); return startupStep(); }
            catch (Exception e) { throw new CompletionException(e); }
        }, storageWorker);
    }

    private static ThreadPoolExecutor worker(int threads, int capacity, String name) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Bind server-thread cache invalidation before denomination-changing outcomes are acknowledged. */
    public void onDenominationChange(Runnable listener) {
        checkOwner();
        denominationChange = java.util.Objects.requireNonNull(listener);
    }

    /** Called only after a journal outcome has been durably committed or recovered. */
    public void denominationCommitted(Journal.Kind kind) {
        checkOwner();
        if (ca.emilybelnavis.mcmods.economy.fx.FxService.OPEN.equals(kind)
                || ca.emilybelnavis.mcmods.economy.journal.RebaseSettlement.KIND.equals(kind))
            denominationChange.run();
    }

    /** Bind a trusted journal registration set to this server's existing storage worker. */
    public JournalService journal(
            JournalEngine.AccessPolicy access,
            Map<Journal.Kind, JournalEngine.Registration> handlers,
            JournalService.Publisher publisher) {
        checkOwner();
        if (!(storage instanceof FoundationStore store))
            throw new IllegalStateException("Journal requires persistent foundation storage");
        return new JournalService(this,
                new JournalEngine(store, access, handlers), publisher == null ? new JournalService.Publisher() {
                    public void invalidate(Journal.Outcome outcome) {}
                    public void publish(Journal.Outcome outcome) {}
                } : publisher);
    }

    private final ca.emilybelnavis.mcmods.economy.pricing.ServicePricing servicePricing;
    public ca.emilybelnavis.mcmods.economy.pricing.ServicePricing servicePricing() { checkOwner(); return servicePricing; }
    public ca.emilybelnavis.mcmods.economy.simulation.SimulationConfig simulationConfig(){checkOwner();return storage instanceof FoundationRuntime r?r.simulationConfig():ca.emilybelnavis.mcmods.economy.simulation.SimulationConfig.defaults();}
    public ca.emilybelnavis.mcmods.economy.api.EconomicSimulationIntegration simulation(){checkOwner();return new ca.emilybelnavis.mcmods.economy.api.EconomicSimulationIntegration(){
        public Optional<ca.emilybelnavis.mcmods.economy.simulation.SimulationDiagnostics.Snapshot> snapshot(PartyAuthorization.Grant grant){checkOwner();return allowsAccountRead(grant,partyWorld(),false)&&simulationFailure.isEmpty()?Optional.ofNullable(simulationSample):Optional.empty();}
        public CompletionStage<List<ca.emilybelnavis.mcmods.economy.simulation.macro.MacroPlans.Outcome>> history(PartyAuthorization.Grant grant,long before,int limit){checkOwner();if(before<0||limit<1||limit>16)throw new IllegalArgumentException("Simulation history bounds");if(!allowsAccountRead(grant,partyWorld(),false)||!(storage instanceof FoundationStore store))return CompletableFuture.completedStage(List.of());return storage(grant.actor(),()->allowsAccountRead(grant,store.snapshot().worldId(),false)?ca.emilybelnavis.mcmods.economy.simulation.SimulationDiagnostics.history(store,before,limit):List.of());}
    };}
    public List<String> macroDiagnostics(){checkOwner();if(state==State.UNAVAILABLE){String reason=failure instanceof IllegalArgumentException&&failure.getMessage()!=null?failure.getMessage():failureCode(failure);return List.of("Simulation unavailable: "+reason.substring(0,Math.min(240,reason.length())));}var s=simulationSample;if(s==null||!simulationFailure.isEmpty())return List.of("Simulation unavailable: "+simulationFailure);return List.of("Simulation "+s.reason()+" mode="+s.mode()+" coverage="+s.coverageDays()+"/7 baseline="+s.baselineDays()+"/7 ageMs="+s.fxAgeMillis(),"Activation A="+s.activationMillis()+" F="+s.firstEffectiveMillis()+" H="+s.handoverMillis(),"Config="+s.config()+" policyRevision="+s.policyRevision()+" source="+s.sources(),String.join("; ",s.limits()));}
    /** Operator capability and revocable server proof are issued before this trusted worker call. */
    public CompletionStage<ca.emilybelnavis.mcmods.economy.simulation.macro.MacroControls.Policy> scheduleMacro(PartyAuthorization.Grant grant,java.util.concurrent.atomic.AtomicBoolean live,ca.emilybelnavis.mcmods.economy.simulation.MacroPolicyRequests.Request request){checkOwner();if(!allowsAccountRead(grant,partyWorld(),true)||!live.get())return CompletableFuture.failedStage(new IllegalStateException("Operator permission required"));return tradeMonetaryStorage(grant.actor(),store->{if(!live.get()||!allowsAccountRead(grant,store.snapshot().worldId(),true))throw new IllegalStateException("Operator session ended");return ca.emilybelnavis.mcmods.economy.simulation.MacroPolicyRequests.schedule(store,request,java.time.Instant.now(),grant.actor());});}
    /** Server-only valuation reads authenticated on every admission. */
    public ca.emilybelnavis.mcmods.economy.api.ItemValuationIntegration itemValuation(MinecraftServer server) {
        checkOwner();return ca.emilybelnavis.mcmods.economy.valuation.ValuationApi.integration(server,this);
    }
    public ca.emilybelnavis.mcmods.economy.api.ItemValuationIntegration.Provider itemValuationProvider(MinecraftServer server) {
        checkOwner();requireReady();
        return ca.emilybelnavis.mcmods.economy.valuation.ValuationApi.provider(server,this);
    }
    public void authenticateValuationRead(PartyAuthorization.Grant grant) {
        checkOwner();requireReady();
        if(grant==null||!allowsAccountRead(grant,partyWorld(),false))throw new SecurityException("Current owner grant required");
    }
    public WorldId worldId() { return partyWorld(); }

    WorldId partyWorld() {
        checkOwner();
        var details = storageDetails;
        return state == State.READY && details != null ? new WorldId(details.worldId()) : null;
    }

    public JournalService.Submission submitOwned(PartyAuthorization.Grant grant, Journal.Kind kind,
                                                 OperationId operationId, RequestId requestId,
                                                 Journal.CanonicalRequest request, JournalEngine.Registration handler,
                                                 JournalService.Publisher publisher) {
        var operation = PartyOperation.owned(grant, kind, operationId, requestId, request, handler);
        return partyJournal(operation, publisher).submit(operation.intent());
    }

    CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.collection.CollectionPresence.Snapshot>> readCollectionAgentState(
            PartyAuthorization.Grant grant,UUID after,int limit) {
        checkOwner();requireReady();
        if(!(storage instanceof FoundationStore store)||!allowsAccountRead(grant,partyWorld(),false))
            return CompletableFuture.completedStage(Optional.empty());
        return storage(grant.actor(),()->{
            store.requireEconomicCurrent();
            return store.transaction(tx->allowsAccountRead(grant,store.snapshot().worldId(),false)
                ?Optional.of(ca.emilybelnavis.mcmods.economy.collection.CollectionPresence.snapshot(tx::query,grant.target(),after,limit,System.currentTimeMillis())):Optional.empty());
        });
    }

    /** Only presentation receipts bypass economic catch-up so removal/death can drain at shutdown. */
    public CompletionStage<Journal.Outcome> collectionAgentTransition(PartyAuthorization.Grant grant,
            ca.emilybelnavis.mcmods.economy.collection.CollectionPresence.Action action,UUID lease,long observedDeathAt) {
        checkOwner();requireReady();
        if(!(storage instanceof FoundationStore store)||!allowsAccountRead(grant,partyWorld(),false))
            return CompletableFuture.failedStage(new IllegalArgumentException("Collection presentation authority"));
        var request=ca.emilybelnavis.mcmods.economy.collection.CollectionPresence.request(grant.target(),action,lease,observedDeathAt);
        var fields=new java.util.HashMap<>(request.fields());fields.remove("economy:owner");
        var id=ca.emilybelnavis.mcmods.economy.collection.CollectionPresence.operation(grant.target(),action,lease);
        var registration=ca.emilybelnavis.mcmods.economy.collection.CollectionPresence.registration(grant.target(),action,lease,observedDeathAt,System::currentTimeMillis);
        var operation=PartyOperation.owned(grant,ca.emilybelnavis.mcmods.economy.collection.CollectionPresence.KIND,new OperationId(id),new RequestId(UUID.randomUUID()),new Journal.CanonicalRequest(1,fields),registration);
        return storage(grant.actor(),()->{
            if(action==ca.emilybelnavis.mcmods.economy.collection.CollectionPresence.Action.RESERVE)store.requireEconomicCurrent();
            return operation.engine(store,sessionId).submit(operation.intent());
        });
    }

    public CompletionStage<Optional<Journal.Outcome>> lookupOwned(
            PartyAuthorization.Grant grant, Journal.Kind kind, OperationId operationId, RequestId requestId,
            Journal.CanonicalRequest request, JournalEngine.Registration handler) {
        var operation = PartyOperation.owned(grant, kind, operationId, requestId, request, handler);
        return partyJournal(operation, null).lookup(operation.intent());
    }

    /** Invoke only after an authenticated player's first authorized banking interaction. */
    public JournalService.Submission initializePrimary(PartyAuthorization.Grant grant, OperationId operationId,
                                                       RequestId requestId, JournalService.Publisher publisher) {
        var operation = accountInitialization(grant, operationId, requestId, AccountIdentity.Purpose.BANK);
        return partyJournal(operation, publisher).submit(operation.intent());
    }

    /** Separate funding identity for a trusted trading integration. */
    public JournalService.Submission initializeTrading(PartyAuthorization.Grant grant, OperationId operationId,
                                                       RequestId requestId, JournalService.Publisher publisher) {
        var operation = accountInitialization(grant, operationId, requestId, AccountIdentity.Purpose.TRADING);
        return partyJournal(operation, publisher).submit(operation.intent());
    }

    public CompletionStage<Optional<Journal.Outcome>> lookupPrimaryOperation(
            PartyAuthorization.Grant grant, OperationId operationId, RequestId requestId) {
        var operation = accountInitialization(grant, operationId, requestId, AccountIdentity.Purpose.BANK);
        return partyJournal(operation, null).lookup(operation.intent());
    }

    public CompletionStage<Optional<Journal.Outcome>> lookupTradingOperation(
            PartyAuthorization.Grant grant, OperationId operationId, RequestId requestId) {
        var operation = accountInitialization(grant, operationId, requestId, AccountIdentity.Purpose.TRADING);
        return partyJournal(operation, null).lookup(operation.intent());
    }

    /** Immutable catalog metadata; provider ownership still requires an authenticated grant. */
    public java.util.List<ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.Entry> institutionCatalog() {
        checkOwner();
        return ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.banks();
    }

    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.institution.BankProviders.Binding>> accountProvider(
            PartyAuthorization.Grant grant, AccountId account) {
        return readAccounts(grant, false, reads -> ca.emilybelnavis.mcmods.economy.institution.BankProviders.find(reads, grant.target(), account));
    }

    public CompletionStage<ca.emilybelnavis.mcmods.economy.institution.InstitutionCapital.Status> institutionCapitalStatus() {
        checkOwner();
        return storage(() -> storage instanceof FoundationRuntime runtime ? runtime.readInstitutionCapitalStatus() : null);
    }

    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.pricing.WealthValuation.Snapshot>> wealthSnapshot(PartyAuthorization.Grant grant) {
        return readAccounts(grant,false,reads->grant.target().owner() instanceof PartyRef.Player player
                ? Optional.of(servicePricing.valuation().snapshot(reads,grant.target().world(),player.id(),java.time.Instant.now())):Optional.empty());
    }
    public CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.pricing.TierReevaluation.Status>> serviceTierStatus(PartyAuthorization.Grant grant) {
        return readAccounts(grant,false,reads->grant.target().owner() instanceof PartyRef.Player player
                ? ca.emilybelnavis.mcmods.economy.pricing.TierReevaluation.status(reads,player.id()):Optional.empty());
    }
    /** Earned receivables are displayed independently of the spendable AccountFunds balance. */
    public CompletionStage<Optional<List<ca.emilybelnavis.mcmods.economy.api.Money>>> depositYieldReceivables(PartyAuthorization.Grant grant) {
        return readAccounts(grant,false,reads->grant.target().owner() instanceof PartyRef.Player player
                ? Optional.of(ca.emilybelnavis.mcmods.economy.interest.DepositYield.receivables(reads,grant.target().world(),player.id())):Optional.empty());
    }
    public JournalService.Submission settleDepositYield(PartyAuthorization.Grant grant,AccountId source,
            ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.InstitutionId bank,ca.emilybelnavis.mcmods.economy.api.EconomyCurrency currency,
            AccountId destination,OperationId operationId,RequestId requestId,JournalService.Publisher publisher) {
        checkOwner();
        var player=(PartyRef.Player)grant.target().owner();
        var domain=ca.emilybelnavis.mcmods.economy.pricing.PricingRuntime.yield(grant.target().world());
        var handler=domain.settlement(player.id(),source,bank,currency,destination,()->java.time.Instant.now().toEpochMilli(),false);
        var request=ca.emilybelnavis.mcmods.economy.interest.DepositYield.settlementRequest(source,bank,currency,destination,false);
        var kind=ca.emilybelnavis.mcmods.economy.interest.DepositYield.SETTLE;
        var session=sessionId(); var world=grant.target().world();
        return journal((actor,k)->grant.allows(world,session,actor,k),Map.of(kind,handler),publisher).submit(
                new Journal.Intent(operationId,requestId,grant.actor(),kind,request,null,null));
    }

    /** Trusted branch adapter SPI. No live issuer is installed in this package. */
    public JournalService.Submission bankLifecycle(PartyAuthorization.Grant grant, OperationId operationId,
            RequestId requestId, ca.emilybelnavis.mcmods.economy.institution.BankProviders.Request request,
            JournalService.Publisher publisher) {
        checkOwner();
        var action = request.proof().context().action();
        var kind = switch(action) {
            case PRIMARY -> ca.emilybelnavis.mcmods.economy.institution.BankProviders.PRIMARY;
            case SECONDARY -> ca.emilybelnavis.mcmods.economy.institution.BankProviders.SECONDARY;
            case ASSIGN -> ca.emilybelnavis.mcmods.economy.institution.BankProviders.ASSIGN;
            case SWITCH_SOURCE -> ca.emilybelnavis.mcmods.economy.institution.BankProviders.SWITCH_SOURCE;
            case SWITCH_TARGET -> ca.emilybelnavis.mcmods.economy.institution.BankProviders.SWITCH_TARGET;
            case CARD_ISSUE, CARD_REPLACE, CREDIT_ISSUE, CREDIT_INCREASE, CREDIT_REPLACE, LOAN_APPLY -> throw new IllegalArgumentException("Use issued-card service");
            case CLOSE -> throw new IllegalArgumentException("Use protected funded closure composition");
        };
        var fields = new java.util.HashMap<>(request.request().fields());
        fields.remove("economy:owner");
        var handler = ca.emilybelnavis.mcmods.economy.institution.BankProviders.lifecycle(grant.target(), request,
                servicePricing.valuation().tierEligibility(grant.target().world(), java.time.Clock.systemUTC()), () -> java.time.Instant.now().toEpochMilli());
        return submitOwned(grant, kind, operationId, requestId, new Journal.CanonicalRequest(1, fields), handler, publisher);
    }

    /** Trusted owner lookup. Missing and unauthorized IDs have the same result. */
    public CompletionStage<Optional<AccountIdentity>> findOwnedAccount(PartyAuthorization.Grant grant, AccountId id) {
        return readAccounts(grant, false, reads -> AccountCatalog.find(reads, grant.target(), id));
    }

    /** Resolve a fresh owner grant at admission for each requested page. */
    public CompletionStage<Optional<AccountQueries.Page>> listOwnedAccounts(
            PartyAuthorization.Grant grant, String cursor, int limit) {
        var anchor = AccountQueries.cursor(cursor, limit);
        return readAccounts(grant, false, reads -> AccountQueries.page(reads, grant.target(), anchor, limit));
    }

    /** The financial service for this server session. */
    private BranchService branches;
    public BranchService branches() {
        checkOwner();
        if (!(storage instanceof FoundationStore store)) throw new IllegalStateException("Branches require persistent storage");
        if (state != State.READY) throw new IllegalStateException("Branches unavailable");
        if (branches == null) branches = new BranchService(this,store);
        return branches;
    }

    private StaffingService staffing;
    public StaffingService staffing() {
        checkOwner();
        if (!(storage instanceof FoundationStore store)) throw new IllegalStateException("Staffing requires persistent storage");
        if (state != State.READY) throw new IllegalStateException("Staffing unavailable");
        if (staffing == null) staffing = new StaffingService(this,store);
        return staffing;
    }

    public LedgerService ledger() {
        checkOwner();
        if (!(storage instanceof FoundationStore store))
            throw new IllegalStateException("Ledger requires persistent foundation storage");
        if (ledger == null) ledger = new LedgerService(this, store);
        return ledger;
    }

    public CompletionStage<Optional<AccountFunds>> accountFunds(PartyAuthorization.Grant grant, AccountId id) {
        return ledger().funds(grant, id);
    }

    public CompletionStage<Optional<FundingEligibility.Status>> evaluateFunding(
            PartyAuthorization.Grant grant, AccountId id, long expectedRevision, Money amount) {
        return readAccounts(grant, false, reads -> AccountCatalog.find(reads, grant.target(), id).map(account -> {
            var metadata = FundingEligibility.withoutLedger().evaluate(account, expectedRevision, amount);
            if (metadata != FundingEligibility.Status.LEDGER_UNAVAILABLE || account.purpose() != AccountIdentity.Purpose.BANK) {
                return metadata;
            }
            var funds = LedgerService.bankFunds(reads, account).orElseThrow();
            return funds.availableMinorUnits() >= amount.minorUnits()
                    ? FundingEligibility.Status.ELIGIBLE : FundingEligibility.Status.INSUFFICIENT_FUNDS;
        }));
    }

    CompletionStage<Optional<AccountIdentity>> inspectAccount(
            PartyAuthorization.Grant grant, AccountId id, BooleanSupplier live) {
        return inspectAccounts(grant, Map.of("account", id.value().toString()), live,
                reads -> AccountCatalog.find(reads, grant.target(), id));
    }

    CompletionStage<Optional<AccountQueries.Page>> inspectAccounts(
            PartyAuthorization.Grant grant, String cursor, int limit, BooleanSupplier live) {
        var anchor = AccountQueries.cursor(cursor, limit);
        if (anchor != null && !anchor.matches(grant.target()))
            return CompletableFuture.completedStage(Optional.empty());
        return inspectAccounts(grant, Map.of("cursor", cursor == null ? "" : cursor, "limit", Integer.toString(limit)),
                live, reads -> AccountQueries.page(reads, grant.target(), anchor, limit));
    }

    <T> CompletionStage<Optional<T>> inspectAccounts(
            PartyAuthorization.Grant grant, Map<String, String> scope, BooleanSupplier live,
            Function<JournalEngine.ReadView, Optional<T>> query) {
        checkOwner();
        if (!allowsAccountRead(grant, partyWorld(), true) || !live.getAsBoolean())
            return CompletableFuture.completedStage(Optional.empty());

        var operation = PartyOperation.inspection(grant, new OperationId(UUID.randomUUID()),
                new RequestId(UUID.randomUUID()), scope);
        return partyJournal(operation, null).submit(operation.intent()).completion().thenCompose(delivery -> {
            var outcome = delivery.outcome();
            if (outcome.status() != Journal.Status.COMMITTED) {
                String code = outcome.result().code();
                var kind = switch (code) {
                    case "OVERLOADED" -> StorageException.Kind.OVERLOAD;
                    case "ECONOMIC_CATCH_UP" -> StorageException.Kind.ECONOMIC_CATCH_UP;
                    default -> outcome.status() == Journal.Status.UNKNOWN ? StorageException.Kind.UNKNOWN_COMMIT : null;
                };
                return CompletableFuture.failedStage(kind == null ? new IllegalStateException(code)
                        : new StorageException(kind, code));
            }
            if (!live.getAsBoolean()) return CompletableFuture.completedStage(Optional.empty());
            return readAccounts(grant, true, query).thenApply(result -> live.getAsBoolean() ? result : Optional.empty());
        });
    }

    /** Inspection-only audit can be committed while scheduled finance catches up; it cannot post money. */
    <T> CompletionStage<Optional<T>> inspectAccountsDuringCatchup(
            PartyAuthorization.Grant grant,Map<String,String> scope,BooleanSupplier live,
            Function<JournalEngine.ReadView,Optional<T>> query) {
        checkOwner();requireReady();
        if(!(storage instanceof FoundationStore store))throw new IllegalStateException("Inspection requires foundation storage");
        if(!allowsAccountRead(grant,partyWorld(),true)||!live.getAsBoolean())return CompletableFuture.completedStage(Optional.empty());
        var operation=PartyOperation.inspection(grant,new OperationId(UUID.randomUUID()),new RequestId(UUID.randomUUID()),scope);
        // This sealed operation can only produce InspectionAudit records, and retains original-ID recovery.
        return storage(grant.actor(),()->operation.engine(store,sessionId).submit(operation.intent())).thenCompose(outcome->{
            if(outcome.status()!=Journal.Status.COMMITTED)return CompletableFuture.failedStage(new IllegalStateException("Inspection audit unavailable"));
            if(!live.getAsBoolean())return CompletableFuture.completedStage(Optional.empty());
            return readAccounts(grant,true,query).thenApply(result->live.getAsBoolean()?result:Optional.empty());
        });
    }

    <T> CompletionStage<Optional<T>> readAccounts(
            PartyAuthorization.Grant grant, boolean inspection, Function<JournalEngine.ReadView, Optional<T>> query) {
        checkOwner();
        if (!(storage instanceof FoundationStore store))
            throw new IllegalStateException("Account lookup requires persistent foundation storage");
        requireReady();
        if (!allowsAccountRead(grant, partyWorld(), inspection))
            return CompletableFuture.completedStage(Optional.empty());
        return storage(grant.actor(), () -> store.transaction(tx ->
                allowsAccountRead(grant, store.snapshot().worldId(), inspection) ? query.apply(tx::query) : Optional.empty()));
    }

    private boolean allowsAccountRead(PartyAuthorization.Grant grant, WorldId world, boolean inspection) {
        return grant.target().owner() instanceof PartyRef.Player && (inspection
                ? grant.allows(world, sessionId, grant.actor(), InspectionAudit.KIND)
                : grant.allowsOwnership(world, sessionId));
    }

    /** Internal A4 planning entry point. The readers must be detached and use only the supplied snapshot. */
    public CompletionStage<AccountRebasePlanner.Result> planAccountRebase(
            CommandSourceStack source, Function<MinecraftServer, ServerServices> sessions,
            DenominationChange.Confirmed confirmed, AccountRebasePlanner.DenominationReader denomination,
            AccountRebasePlanner.ClaimsReader claims) {
        checkOwner();
        var intent = confirmed.intent();
        var target = new PartyRef(intent.world(), new PartyRef.Player(intent.actor()));
        var authority = PartyAuthorization.inspection(source, sessions, target);
        var denied = new AccountRebasePlanner.Result(AccountRebasePlanner.Status.UNAUTHORIZED, null);
        if (authority.isEmpty() || sessions.apply(source.getServer()) != this
                || !intent.actor().equals(authority.get().actor()) || !sessionId.equals(intent.session()))
            return CompletableFuture.completedStage(denied);

        if (!(storage instanceof FoundationStore store))
            throw new IllegalStateException("Rebase planning requires persistent foundation storage");
        PlayerId actor = intent.actor();
        return storage(actor, () -> store.transaction(tx -> {
            var world = store.snapshot().worldId();
            if (!world.equals(intent.world())) return denied;
            JournalEngine.ReadView reads = tx::query;
            return AccountRebasePlanner.plan(reads, world, sessionId, actor, confirmed,
                    denomination.read(reads).orElse(null), claims);
        })).thenApply(result -> sessions.apply(source.getServer()) == this
                && PartyAuthorization.inspection(source, sessions, target).isPresent() ? result : denied);
    }

    private static PartyOperation accountInitialization(PartyAuthorization.Grant grant, OperationId operationId,
                                                        RequestId requestId, AccountIdentity.Purpose purpose) {
        var kind = purpose == AccountIdentity.Purpose.BANK
                ? AccountCatalog.INITIALIZE_PRIMARY : AccountCatalog.INITIALIZE_TRADING;
        return PartyOperation.owned(grant, kind, operationId, requestId, new Journal.CanonicalRequest(1, Map.of()),
                AccountCatalog.initialization(grant.target(), purpose));
    }

    /** A3-04 must await a committed receipt and recheck the live audience before disclosing data. */
    public JournalService.Submission auditInspection(PartyAuthorization.Grant grant, OperationId operationId,
                                                     RequestId requestId) {
        var operation = PartyOperation.inspection(grant, operationId, requestId);
        return partyJournal(operation, null).submit(operation.intent());
    }

    private JournalService partyJournal(PartyOperation operation, JournalService.Publisher publisher) {
        checkOwner();
        if (!(storage instanceof FoundationStore store))
            throw new IllegalStateException("Journal requires persistent foundation storage");
        return new JournalService(this, operation.engine(store, sessionId),
                publisher == null ? new JournalService.Publisher() {
                    public void invalidate(Journal.Outcome committed) {}
                    public void publish(Journal.Outcome terminal) {}
                } : publisher);
    }

    public UUID sessionId() { return sessionId; }
    public StorageLifecycle.Progress progress() { return progress; }
    public State state() { return state; }
    public Throwable failure() { return failure; }
    public OperationDiagnostics operationDiagnostics() { return operationDiagnostics; }

    public List<String> diagnostics(boolean privileged, boolean detailed) {
        checkOwner();
        var last = operationDiagnostics.lastFailure();
        String code = failure == null ? null : failureCode(failure);
        UUID correlation = null;
        if (last != null && (code == null || (code.startsWith("STORAGE_")
                && (last.code().equals(OperationDiagnostics.Event.OPERATION_STORAGE_FAILED.name())
                || last.code().equals(OperationDiagnostics.Event.OPERATION_OUTCOME_UNKNOWN.name()))))) {
            code = last.code();
            correlation = last.correlation();
        }
        var details = storageDetails;
        if (details != null && state != State.READY) {
            String health = state == State.UNAVAILABLE ? "unavailable" : "closed";
            details = new DiagnosticsReport.StorageDetails(details.worldId(), details.schemaVersion(),
                    details.lastCommittedSequence(), health);
        }
        var lines = DiagnosticsReport.render(privileged, detailed, state, metrics(), details, code, correlation, observedRate);
        if (!privileged || !detailed || !(storage instanceof FoundationRuntime)) return lines;
        var sample = fxSample;
        String reason = state == State.UNAVAILABLE ? failureCode(failure)
                : storageWorker.getQueue().size() >= config.storageQueueCapacity() || pending.size() >= publicationCapacity
                ? "STORAGE_OVERLOAD" : economicRetry != null ? economicRetry : sample.readFailure();
        var withFx = DiagnosticsReport.withFx(lines, state, sample.snapshot(), reason);
        var calendar = calendarSample;
        String calendarReason = inspectionReason(calendar.readFailure());
        var combined = new java.util.ArrayList<>(withFx);
        combined.addAll(CalendarReport.summary(calendar.snapshot(), state, calendarReason, metrics()));
        return List.copyOf(combined);
    }

    /** Command admission uses EconomyCommands' live console/operator check; returns cached data only. */
    public List<String> calendarDiagnostics(String after) {
        checkOwner();
        var sample = calendarSample;
        return CalendarReport.render(sample.snapshot(), state, inspectionReason(sample.readFailure()), metrics(), after);
    }

    private String inspectionReason(String readFailure) {
        return state == State.UNAVAILABLE ? failureCode(failure)
                : storageWorker.getQueue().size() >= config.storageQueueCapacity() || pending.size() >= publicationCapacity
                ? "STORAGE_OVERLOAD" : economicRetry != null ? economicRetry : readFailure;
    }

    public static String failureCode(Throwable error) {
        if (error == null) return "LIFECYCLE_FAILURE";
        if (error instanceof StorageException storageError) return "STORAGE_" + storageError.kind();
        if (error instanceof TimeoutException) return "DRAIN_TIMEOUT";
        return "LIFECYCLE_FAILURE";
    }

    private StorageLifecycle.Progress startupStep() {
        var step = storage.startupStep();
        if (step.ready() && storage instanceof FoundationStore store) {
            if (!RegisterSales.reconcileBatch(store, RegisterSaleSettlement::returnOriginals))
                return new StorageLifecycle.Progress("register-reconciliation", 0, -1, false);
            if (!Withdrawals.reconcileBatch(store))
                return new StorageLifecycle.Progress("withdrawal-reconciliation", 0, -1, false);
            if (!Deposits.reconcileBatch(store))
                return new StorageLifecycle.Progress("deposit-reconciliation", 0, -1, false);
            if (!DepositReturns.reconcileBatch(store))
                return new StorageLifecycle.Progress("deposit-return-reconciliation", 0, -1, false);
            if (!Custody.reconcileBatch(store))
                return new StorageLifecycle.Progress("custody-reconciliation", 0, -1, false);
            if (!CashIssuance.reconcileBatch(store))
                return new StorageLifecycle.Progress("cash-reconciliation", 0, -1, false);
        }
        long now = System.nanoTime();
        if (step.ready() || nextStartupDiagnosticsSample == 0 || now - nextStartupDiagnosticsSample >= 0) {
            nextStartupDiagnosticsSample = now + TimeUnit.SECONDS.toNanos(1);
            sampleDiagnosticsOnWorker();
        }
        return step;
    }

    private void sampleStorage() {
        if (!(storage instanceof FoundationStore store)) return;
        try {
            var snapshot = store.snapshot();
            long sequence = store.lastCommittedSequence();
            storageDetails = new DiagnosticsReport.StorageDetails(snapshot.worldId().value(),
                    snapshot.schemaVersion(), sequence, "healthy");
        } catch (RuntimeException error) {
            // A failed read-only sample never changes admission or replaces the last known checkpoint.
            var previous = storageDetails;
            if (previous != null) storageDetails = new DiagnosticsReport.StorageDetails(
                    previous.worldId(), previous.schemaVersion(), previous.lastCommittedSequence(), "degraded");
        }
    }

    private void sampleDiagnosticsOnWorker() {
        sampleStorage();
        if(storage instanceof FoundationStore store){try{simulationSample=ca.emilybelnavis.mcmods.economy.simulation.SimulationDiagnostics.read(store,System.currentTimeMillis(),storage instanceof FoundationRuntime r?r.simulationStatus().reason():"NOT_CONFIGURED");simulationFailure="";}catch(RuntimeException e){simulationFailure=failureCode(e);}}
        if (storage instanceof FoundationRuntime runtime) {
            try {
                fxSample = new FxSample(runtime.readFxDiagnostics(), null);
            } catch (RuntimeException error) {
                fxSample = new FxSample(fxSample.snapshot(), failureCode(error));
            }
            try {
                calendarSample = new CalendarSample(runtime.readCalendarDiagnostics(), null);
            } catch (RuntimeException error) {
                calendarSample = new CalendarSample(calendarSample.snapshot(), failureCode(error));
            }
        }
    }

    private void sampleDiagnostics() {
        long now = System.nanoTime();
        if (nextDiagnosticsSample != 0 && now - nextDiagnosticsSample < 0) return;
        nextDiagnosticsSample = now + TimeUnit.SECONDS.toNanos(1);
        if (scheduleEconomy && economyObserved) {
            long sequence = economy.completedSequence();
            if (rateSampleTime != 0 && sequence >= rateSampleSequence) {
                observedRate = (sequence - rateSampleSequence) * 1_000_000_000.0 / (now - rateSampleTime);
            }
            rateSampleTime = now;
            rateSampleSequence = sequence;
        }
        if (storage instanceof FoundationStore && (diagnosticProbe == null || diagnosticProbe.isDone())
                && storageWorker.getQueue().isEmpty() && activeStorage.isEmpty()) {
            diagnosticProbe = runOn(() -> { sampleDiagnosticsOnWorker(); return null; }, storageWorker);
        }
    }
    /** Current-process expiry failure code. No storage access or scheduling side effects. */
    public String paymentReleaseFailure() {
        checkOwner();
        if (paymentExpiry == null || !paymentExpiry.toCompletableFuture().isCompletedExceptionally()) return null;
        try {
            paymentExpiry.toCompletableFuture().join();
            return null;
        } catch (CompletionException error) {
            return failureCode(error.getCause());
        }
    }

    private void schedulePaymentExpiry() {
        if (!(storage instanceof FoundationStore store)) return;
        long now = System.nanoTime();
        if ((nextPaymentExpiry != 0 && now - nextPaymentExpiry < 0)
                || (paymentExpiry != null && !paymentExpiry.toCompletableFuture().isDone())) return;
        nextPaymentExpiry = now + TimeUnit.SECONDS.toNanos(1);
        paymentExpiry = storage(() -> {
            MerchantPayments.expireBatch(store, Clock.systemUTC());
            return null;
        });
    }

    public CompletionStage<Void> readiness() { return ready.minimalCompletionStage(); }

    public void requireReady() {
        checkOwner();
        if (state != State.READY) throw new IllegalStateException("Economy is " + state, failure);
    }

    public SchedulingMetrics metrics() {
        checkOwner();
        int reserved = scheduleEconomy ? 1 : 0;
        return new SchedulingMetrics(queueMetrics(storageWorker.getQueue(), config.storageQueueCapacity() + reserved, activeStorage),
                queueMetrics(calculations.getQueue(), config.calculationQueueCapacity() + reserved, activeCalculations),
                queueMetrics(callbacks, publicationCapacity, Map.of()), progress, economy,
                prepared != null || calculating != null || committed != null, unresolvedAtShutdown);
    }

    private static SchedulingMetrics.Queue queueMetrics(BlockingQueue<? extends Runnable> queue, int capacity,
                                                         Map<Thread, Long> active) {
        TimedTask oldest = (TimedTask) queue.peek();
        long now = System.nanoTime();
        long activeAge = 0;
        for (long started : active.values()) activeAge = Math.max(activeAge, now - started);
        return new SchedulingMetrics.Queue(capacity, queue.size(),
                oldest == null ? 0 : Math.max(0, now - oldest.enqueued()), activeAge);
    }

    /** Trusted internal work. Futures complete on the server tick. Never join on the server thread. */
    public <T> CompletionStage<T> calculate(Supplier<T> immutableWork) {
        return calculate(null, immutableWork);
    }

    /** Share admission fairly across authenticated actor identities. */
    public <T> CompletionStage<T> calculate(ActorId actor, Supplier<T> immutableWork) {
        return submit(calculations, config.calculationQueueCapacity(), actor, immutableWork);
    }

    public <T> CompletionStage<T> storage(Supplier<T> immutableWork) {
        requireReady();
        sharedReads.clear();
        return submit(storageWorker, config.storageQueueCapacity(), null, immutableWork);
    }

    /** Trusted detached trader adapters only; no entity, packet or external SQL callbacks. */
    public <T> CompletionStage<T> tradeStorage(java.util.function.Function<FoundationStore,T> work) {
        return tradeStorage(null,work);
    }
    public <T> CompletionStage<T> tradeStorage(ActorId actor,java.util.function.Function<FoundationStore,T> work) {
        checkOwner();requireReady();
        if(!(storage instanceof FoundationStore store))throw new IllegalStateException("Trade storage requires foundation");
        return storage(actor,()->work.apply(store));
    }

    /** Live commerce must pass both cached admission and authoritative worker-time catch-up gates. */
    public <T> CompletionStage<T> tradeMonetaryStorage(ActorId actor,java.util.function.Function<FoundationStore,T> work){
        checkOwner();if(!(storage instanceof FoundationStore store))throw new IllegalStateException("Trade storage requires foundation");
        return economicWrite(actor,()->work.apply(store));
    }
    public <T> CompletionStage<T> tradeMonetaryStorage(java.util.function.Function<FoundationStore,T> work){return tradeMonetaryStorage(null,work);}
    public <T> CompletionStage<T> storage(ActorId actor, Supplier<T> immutableRead) {
        return submit(storageWorker, config.storageQueueCapacity(), actor, immutableRead);
    }

    /** Equal keys must have identical authorization and result types. A write ends the sharing window. */
    @SuppressWarnings("unchecked")
    public <T> CompletionStage<T> shareStorageRead(ActorId actor, Object key, Supplier<T> immutableRead) {
        requireReady();
        var existing = (CompletionStage<T>) sharedReads.get(key);
        if (existing != null) return existing;
        var result = storage(actor, immutableRead);
        sharedReads.put(key, result);
        result.whenComplete((value, error) -> sharedReads.remove(key, result));
        return result;
    }

    public <T> CompletionStage<T> economicWrite(ActorId actor, Supplier<T> immutableWrite) {
        requireReady();
        if (!economy.ready()) {
            return CompletableFuture.failedStage(new StorageException(StorageException.Kind.ECONOMIC_CATCH_UP,
                    "Economic catch-up in progress"));
        }
        sharedReads.clear();
        return submit(storageWorker, config.storageQueueCapacity(), actor, () -> {
            storage.requireEconomicCurrent();
            return immutableWrite.get();
        });
    }

    private <T> CompletionStage<T> submit(ThreadPoolExecutor executor, int capacity, ActorId actor, Supplier<T> work) {
        requireReady();
        int actorLimit = Math.max(1, Math.min(8, capacity / 2));
        if (pending.size() >= publicationCapacity
                || executor.getQueue().size() >= capacity
                || (actor != null && actorPending.getOrDefault(actor, 0) >= actorLimit)) {
            return CompletableFuture.failedStage(new StorageException(StorageException.Kind.OVERLOAD, "Work admission limit reached"));
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        pending.add(result);
        if (actor != null) actorPending.merge(actor, 1, Integer::sum);
        result.whenComplete((value, error) -> {
            pending.remove(result);
            if (actor != null) actorPending.computeIfPresent(actor, (key, count) -> count == 1 ? null : count - 1);
        });
        runOn(work, executor).whenComplete((value, error) -> deliver(() -> {
            if (error instanceof StorageException storageError && storageError.fatal()) {
                failure = error;
                state = State.UNAVAILABLE;
            }
            if (error == null) result.complete(value);
            else result.completeExceptionally(error);
        }));
        return result.minimalCompletionStage();
    }

    private final java.util.concurrent.atomic.AtomicLong valuationStorageEpoch=new java.util.concurrent.atomic.AtomicLong();
    /** Conservative cache witness: every storage admission/start invalidates earlier FX samples. */
    public long valuationStorageEpoch(){return valuationStorageEpoch.get();}
    private <T> CompletableFuture<T> runOn(Supplier<T> work, ThreadPoolExecutor executor) {
        if(executor==storageWorker)valuationStorageEpoch.incrementAndGet();
        CompletableFuture<T> result = new CompletableFuture<>();
        var active = executor == storageWorker ? activeStorage : activeCalculations;
        executor.execute(new TimedTask(System.nanoTime(), () -> {
            if(executor==storageWorker)valuationStorageEpoch.incrementAndGet();
            var thread = Thread.currentThread();
            active.put(thread, System.nanoTime());
            try { result.complete(work.get()); }
            catch (Throwable error) { result.completeExceptionally(error); }
            finally { active.remove(thread); }
        }));
        return result;
    }

    private void deliver(Runnable callback) {
        synchronized (callbacks) {
            if (state == State.STOPPING || state == State.STOPPED) return;
            // Total outstanding work is capped at the callback queue capacity.
            if (!callbacks.offer(new TimedTask(System.nanoTime(), callback)))
                throw new IllegalStateException("Completion bound violated");
        }
    }

    private void advanceEconomy() {
        if (!scheduleEconomy) return;
        if (prepared != null) {
            if (!prepared.isDone()) return;
            var step = prepared.join();
            prepared = null;
            economicRetry = null;
            economy = step.progress();
            economyObserved = true;
            if (step.calculation() != null) {
                startCalculation(step);
                return;
            }
            if (economy.ready() || economy.waitingForWall())
                nextEconomicProbe = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
        }
        if (calculating != null) {
            var calculation = calculating;
            if (!calculation.result().isDone()) return;
            // Observe failures before the next storage task is accepted.
            calculation.result().join();
            calculating = null;
            sharedReads.clear();
            committed = runOn(calculation::finish, storageWorker);
            return;
        }
        if (committed != null) {
            if (!committed.isDone()) return;
            economy = committed.join();
            economicRetry = null;
            committed = null;
        }
        if (nextEconomicProbe == 0 || System.nanoTime() - nextEconomicProbe >= 0) {
            nextEconomicProbe = 0;
            prepared = runOn(storage::prepareEconomicStep, storageWorker);
        }
    }

    private <T> void startCalculation(StorageLifecycle.EconomicStep<T> step) {
        calculating = new EconomicCalculation<>(runOn(step.calculation(), calculations), step.commit());
    }

    /** Starts callbacks within the configured budget; an individual callback must itself be short. */
    public void tick() {
        checkOwner();
        if (state == State.INITIALIZING && initialized.isDone()) {
            try {
                progress = initialized.join();
                if (progress.ready()) {
                    state = State.READY;
                    nextDiagnosticsSample = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    ready.complete(null);
                }
                else initialized = runOn(this::startupStep, storageWorker);
            }
            catch (CompletionException e) {
                failure = e.getCause(); state = State.UNAVAILABLE; ready.completeExceptionally(failure);
            }
        }
        if (state == State.READY) {
            try {
                advanceEconomy();
                sampleDiagnostics();
                schedulePaymentExpiry();
            }
            catch (CompletionException e) {
                prepared = null;
                calculating = null;
                committed = null;
                if (e.getCause() instanceof StorageException error && error.kind() == StorageException.Kind.LOCK_TIMEOUT) {
                    economicRetry = failureCode(error);
                } else {
                    failure = e.getCause();
                    state = State.UNAVAILABLE;
                }
            }
        }
        long start = System.nanoTime();
        while (state != State.STOPPING && state != State.STOPPED && System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(config.callbackBudgetMillis())) {
            Runnable callback = callbacks.poll();
            if (callback == null) break;
            callback.run();
        }
    }

    /** Only the stopping lifecycle may wait. Never used by ticks or request handlers. */
    public boolean stop() { return stop(Duration.ofSeconds(config.drainSeconds())); }

    public boolean stop(Duration timeout) {
        checkOwner();
        if (state == State.STOPPED) return stoppedCleanly;
        if (state == State.STOPPING) return false;
        state = State.STOPPING;
        ready.completeExceptionally(new IllegalStateException("Server stopping"));
        long deadline = System.nanoTime() + timeout.toNanos();
        calculations.shutdown();
        // The storage thread disposes its adapter after all accepted work.
        storageWorker.shutdown();
        boolean drained = await(storageWorker, deadline) & await(calculations, deadline);
        if (drained) {
            try { drained = disposed.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); drained = false; }
            drained &= closed.get() && disposalFailure == null;
        }
        if (!drained) {
            unresolvedAtShutdown = pending.size() + (metrics().economicStepInFlight() ? 1 : 0);
            if (failure == null) failure = new TimeoutException(
                    "Shutdown drain failed; " + unresolvedAtShutdown + " work items without publication");
            storageWorker.shutdownNow();
            calculations.shutdownNow();
        }
        pending.forEach(f -> f.completeExceptionally(new CancellationException("Server stopped")));
        synchronized (callbacks) { callbacks.clear(); }
        sharedReads.clear();
        prepared = null;
        calculating = null;
        committed = null;
        stoppedCleanly = drained;
        state = State.STOPPED;
        return drained;
    }

    private boolean await(ExecutorService executor, long deadline) {
        try { return executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Requires server thread");
    }
}
