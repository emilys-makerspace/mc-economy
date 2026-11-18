package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.corporation.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

/** Server-session implementation of the supported corporation API. */
public final class CorporationProviders implements CorporationIntegration {
  private static final Map<MinecraftServer, Map<String, CorporationProviders>> SESSIONS =
      new IdentityHashMap<>();
  private static final Map<MinecraftServer, Map<String, Set<UUID>>> UNCERTAIN =
      new IdentityHashMap<>();
  private static final java.util.concurrent.atomic.AtomicLong GENERATION =
      new java.util.concurrent.atomic.AtomicLong();
  private final MinecraftServer server;
  private final ServerServices services;
  private final WorldId world;
  private final String provider;
  private final SystemActorId actor;
  private final Set<Capability> declarations;
  private final long generation;
  private volatile boolean enabled = true;
  private final Set<UUID> unresolved;

  private CorporationProviders(
      MinecraftServer server,
      ServerServices services,
      String provider,
      Set<Capability> declarations) {
    this.server = server;
    this.services = services;
    this.world = services.worldId();
    this.provider = provider;
    this.unresolved =
        UNCERTAIN
            .computeIfAbsent(server, unused -> new HashMap<>())
            .computeIfAbsent(provider, unused -> new HashSet<>());
    this.actor = IntegrationBanking.actor(world, provider);
    this.declarations = Set.copyOf(declarations);
    this.generation = GENERATION.incrementAndGet();
  }

  public static CorporationIntegration register(
      MinecraftServer server, ModContainer mod, String provider, Set<Capability> capabilities) {
    if (!server.isSameThread()) throw new IllegalStateException("Requires server thread");
    var services = Economy.serverServices(server);
    if (services == null || services.state() != ServerServices.State.READY)
      throw new IllegalStateException("Corporation storage unavailable");
    var caller =
        StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
            .walk(
                frames ->
                    frames
                        .map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(
                            c ->
                                c != CorporationProviders.class
                                    && c != CorporationIntegration.class)
                        .findFirst()
                        .orElseThrow())
            .getModule();
    var info = mod.getModInfo();
    new IntegrationBindings.Key(provider, "registration");
    if (!caller.isNamed()
        || !info.getOwningFile().moduleName().equals(caller.getName())
        || FMLLoader.getGameLayer().findModule(caller.getName()).orElse(null) != caller
        || ModList.get().getModContainerById(info.getModId()).orElse(null) != mod
        || !provider.startsWith(info.getModId() + ":"))
      throw new SecurityException("Provider namespace mismatch");
    if (capabilities.isEmpty()) throw new IllegalArgumentException("Declare a capability");
    var providers = SESSIONS.computeIfAbsent(server, unused -> new HashMap<>());
    var result = new CorporationProviders(server, services, provider, capabilities);
    if (providers.putIfAbsent(provider, result) != null)
      throw new IllegalStateException("Provider already registered");
    return result;
  }

  public static void stop(MinecraftServer server) {
    var providers = SESSIONS.remove(server);
    if (providers != null) providers.values().forEach(p -> p.enabled = false);
    UNCERTAIN.remove(server);
    CorporationConsent.stop(server);
  }

  @Override
  public Set<Capability> capabilities() {
    return declarations;
  }

  @Override
  public CompletionStage<Void> disable() {
    if (!server.isSameThread()) throw new IllegalStateException("Requires server thread");
    enabled = false;
    var providers = SESSIONS.get(server);
    if (providers != null) providers.remove(provider, this);
    if (Economy.serverServices(server) != services
        || services.state() != ServerServices.State.READY)
      return CompletableFuture.completedStage(null);
    return services.storage(actor, () -> null);
  }

  private boolean live() {
    if (!server.isSameThread()) throw new IllegalStateException("Requires server thread");
    return enabled
        && Economy.serverServices(server) == services
        && services.state() == ServerServices.State.READY
        && services.worldId().equals(world);
  }

  private static <T> Result<T> fail(Status status, String code) {
    return new Result<>(status, code, Optional.empty());
  }

  private static <T> Result<T> ok(T value) {
    return new Result<>(Status.OK, "OK", Optional.of(value));
  }

  private static <T> CompletionStage<Result<T>> immediate(Result<T> value) {
    return CompletableFuture.completedStage(value);
  }

  private <T> CompletionStage<Result<T>> read(Function<JournalEngine.ReadView, Result<T>> query) {
    if (!live()) return immediate(fail(Status.UNAVAILABLE, "PROVIDER_DISABLED"));
    var stamp = CorporateBanking.audienceStamp(services);
    try {
      return services
          .<Result<T>>tradeStorage(
              actor,
              store ->
                  store.transaction(
                      tx ->
                          enabled
                              ? query.apply(tx::query)
                              : fail(Status.UNAVAILABLE, "PROVIDER_DISABLED")))
          .<Result<T>>handleAsync(
              (result, error) -> {
                if (!live()) return fail(Status.UNAVAILABLE, "PROVIDER_DISABLED");
                if (!Objects.equals(stamp, CorporateBanking.audienceStamp(services)))
                  return fail(Status.STALE, "AUTHORITY_CHANGED");
                if (error != null)
                  return fail(Status.UNAVAILABLE, ServerServices.failureCode(error));
                return result;
              },
              server);
    } catch (IllegalStateException busy) {
      return immediate(fail(Status.UNAVAILABLE, "STORAGE_UNAVAILABLE"));
    }
  }

  private IntegrationBindings.Key key(String external) {
    return new IntegrationBindings.Key(provider, external);
  }

  private Optional<IntegrationBindings.Mandate> mandate(
      JournalEngine.ReadView reads, String external) {
    return IntegrationBindings.mandate(reads, key(external))
        .filter(IntegrationBindings.Mandate::active);
  }

  @Override
  public CompletionStage<Result<Company>> find(String external) {
    return read(
        reads -> {
          var m = mandate(reads, external).orElse(null);
          if (m == null
              || !declarations.contains(Capability.FIND)
              || !m.capabilities().contains(Capability.FIND))
            return fail(Status.DENIED, "SCOPE_DENIED");
          var rows =
              reads.query(
                  "SELECT name,state FROM corporations WHERE id=?",
                  List.of(m.company().toString()),
                  1);
          if (rows.isEmpty()) return fail(Status.UNAVAILABLE, "COMPANY_UNAVAILABLE");
          return ok(
              new Company(
                  m.company(),
                  rows.getFirst().get("name"),
                  m.companyRevision(),
                  rows.getFirst().get("state")));
        });
  }

  @Override
  public CompletionStage<Result<Permission>> permissions(String external) {
    return read(
        reads -> {
          var m = mandate(reads, external).orElse(null);
          if (m == null) return fail(Status.DENIED, "SCOPE_DENIED");
          var caps = EnumSet.noneOf(Capability.class);
          caps.addAll(m.capabilities());
          caps.retainAll(declarations);
          if (caps.isEmpty()) return fail(Status.DENIED, "SCOPE_DENIED");
          Set<UUID> accounts =
              caps.contains(Capability.ACCOUNTS) || caps.contains(Capability.PAYMENTS)
                  ? m.accounts().stream().map(AccountId::value).collect(Collectors.toSet())
                  : Set.of();
          return ok(new Permission(m.company(), m.companyRevision(), m.revision(), caps, accounts));
        });
  }

  private IntegrationBanking.Access access(
      String external, UUID company, long revision, long mandate) {
    return new IntegrationBanking.Access(
        key(external), actor, world, company, revision, mandate, generation);
  }

  private CorporatePayments.Endpoint endpoint(Endpoint e) {
    PartyRef.Owner owner =
        switch (e.kind()) {
          case PLAYER -> new PartyRef.Player(new PlayerId(e.owner()));
          case CORPORATION -> new PartyRef.Organization(new OrganizationId(e.owner()));
        };
    return new CorporatePayments.Endpoint(
        new PartyRef(world, owner),
        new AccountId(e.account()),
        e.accountRevision(),
        e.bank(),
        e.providerRevision(),
        e.companyRevision());
  }

  @Override
  public CompletionStage<Result<List<Account>>> accounts(
      String external, long companyRevision, long mandateRevision) {
    return read(
        reads -> {
          var m = mandate(reads, external).orElse(null);
          if (m == null
              || !declarations.contains(Capability.ACCOUNTS)
              || !m.capabilities().contains(Capability.ACCOUNTS))
            return fail(Status.DENIED, "SCOPE_DENIED");
          if (m.companyRevision() != companyRevision || m.revision() != mandateRevision)
            return fail(Status.STALE, "AUTHORITY_CHANGED");
          var rows =
              IntegrationBanking.accounts(
                  reads,
                  access(external, m.company(), companyRevision, mandateRevision),
                  () -> enabled);
          return rows.map(
                  list ->
                      ok(
                          list.stream()
                              .map(
                                  a ->
                                      new Account(
                                          new Endpoint(
                                              OwnerKind.CORPORATION,
                                              m.company(),
                                              a.account().id().value(),
                                              a.account().revision(),
                                              a.provider().bankKey(),
                                              a.provider().revision(),
                                              companyRevision),
                                          a.funds()))
                              .toList()))
              .orElseGet(() -> fail(Status.DENIED, "SCOPE_DENIED"));
        });
  }

  @Override
  public CompletionStage<Result<Quote>> quote(Route route, long principal) {
    if (!declarations.contains(Capability.PAYMENTS))
      return immediate(fail(Status.DENIED, "UNDECLARED_CAPABILITY"));
    return read(
        reads -> {
          var m = mandate(reads, route.externalKey()).orElse(null);
          if (m == null
              || !m.company().equals(route.source().owner())
              || !m.capabilities().contains(Capability.PAYMENTS))
            return fail(Status.DENIED, "SCOPE_DENIED");
          if (m.revision() != route.mandateRevision()
              || m.companyRevision() != route.source().companyRevision())
            return fail(Status.STALE, "AUTHORITY_CHANGED");
          var a = access(route.externalKey(), m.company(), m.companyRevision(), m.revision());
          return IntegrationBanking.quote(
                  reads,
                  a,
                  () -> enabled,
                  endpoint(route.source()),
                  endpoint(route.target()),
                  principal,
                  services.financialMillis())
              .map(
                  t ->
                      ok(
                          new Quote(
                              route,
                              t.principal(),
                              t.fee(),
                              t.credit(),
                              Optional.ofNullable(t.publication()))))
              .orElseGet(() -> fail(Status.UNAVAILABLE, "QUOTE_UNAVAILABLE"));
        });
  }

  @Override
  public CompletionStage<Result<Receipt>> pay(UUID operation, Quote quote) {
    if (!live()) return immediate(fail(Status.UNAVAILABLE, "PROVIDER_DISABLED"));
    if (!declarations.contains(Capability.PAYMENTS))
      return immediate(fail(Status.DENIED, "UNDECLARED_CAPABILITY"));
    if (!unresolved.isEmpty()) return immediate(fail(Status.UNKNOWN, "LOOKUP_REQUIRED"));
    var route = quote.route();
    var access =
        access(
            route.externalKey(),
            route.source().owner(),
            route.source().companyRevision(),
            route.mandateRevision());
    var payment =
        new CorporatePayments.Payment(
            endpoint(route.source()),
            endpoint(route.target()),
            0,
            false,
            new CorporatePayments.Terms(
                quote.principalMinor(),
                quote.feeMinor(),
                quote.creditMinor(),
                quote.publication().orElse(null)));
    var intent =
        new Journal.Intent(
            new OperationId(operation),
            new RequestId(UUID.randomUUID()),
            actor,
            IntegrationBanking.PAYMENT,
            IntegrationBanking.request(access, payment),
            null,
            null);
    var handler =
        new JournalEngine.Registration(
            1,
            (i, r) ->
                IntegrationBanking.prepare(
                    i, r, access, () -> enabled, payment, services.financialMillis()));
    return services
        .journal(
            (id, k) -> actor.equals(id) && IntegrationBanking.PAYMENT.equals(k),
            Map.of(IntegrationBanking.PAYMENT, handler),
            null)
        .submit(intent)
        .completion()
        .handleAsync(
            (delivery, error) -> {
              if (error != null) {
                unresolved.add(operation);
                return fail(Status.UNKNOWN, "LOOKUP_REQUIRED");
              }
              var outcome = delivery.outcome();
              if (outcome.status() == Journal.Status.UNKNOWN) unresolved.add(operation);
              if (!live()) return fail(Status.UNAVAILABLE, "PROVIDER_DISABLED");
              return receipt(operation, outcome.status(), outcome.result().code());
            },
            server);
  }

  private static Result<Receipt> receipt(UUID operation, Journal.Status state, String code) {
    Status status =
        switch (state) {
          case COMMITTED -> Status.OK;
          case UNKNOWN -> Status.UNKNOWN;
          default ->
              code.contains("CONFLICT")
                  ? Status.CONFLICT
                  : code.contains("STALE")
                      ? Status.STALE
                      : code.contains("DENIED") || code.contains("AUTHORITY")
                          ? Status.DENIED
                          : state == Journal.Status.NOT_ACCEPTED || code.contains("UNAVAILABLE")
                              ? Status.UNAVAILABLE
                              : Status.REJECTED;
        };
    return new Result<>(status, code, Optional.of(new Receipt(operation, state.name())));
  }

  @Override
  public CompletionStage<Result<Receipt>> lookup(UUID operation) {
    return this.<Receipt>read(
            reads -> {
              var rows =
                  reads.query(
                      "SELECT status,result_code FROM operation_outcomes WHERE world_id=? AND operation_id=? AND ((actor=? AND service=? AND operation_type=?) OR (service=? AND operation_type=?))",
                      List.of(
                          world.value().toString(),
                          operation.toString(),
                          Journal.actorKey(actor),
                          IntegrationBanking.PAYMENT.service(),
                          IntegrationBanking.PAYMENT.operationType(),
                          provider,
                          IntegrationBindings.kind(key("lookup")).operationType()),
                      1);
              if (rows.isEmpty()) return fail(Status.UNAVAILABLE, "ORIGINAL_ABSENT");
              return receipt(
                  operation,
                  Journal.Status.valueOf(rows.getFirst().get("status")),
                  rows.getFirst().get("result_code"));
            })
        .thenApply(
            outcome -> {
              if (outcome.value().isPresent() || outcome.code().equals("ORIGINAL_ABSENT"))
                unresolved.remove(operation);
              return outcome;
            });
  }

  private boolean connected(ServerPlayer founder) {
    return live()
        && founder.getServer() == server
        && server.getPlayerList().getPlayer(founder.getUUID()) == founder;
  }

  private Result<Receipt> offer(
      ServerPlayer founder, UUID operation, IntegrationBindings.Command command) {
    if (!connected(founder)) return fail(Status.UNAVAILABLE, "FOUNDER_UNAVAILABLE");
    if (!CorporationConsent.offer(founder, services, operation, command, () -> enabled))
      return fail(Status.UNAVAILABLE, "CONSENT_UNAVAILABLE");
    return new Result<>(
        Status.APPROVAL_REQUIRED,
        "FOUNDER_CONFIRMATION_REQUIRED",
        Optional.of(new Receipt(operation, "PENDING_CONSENT")));
  }

  @Override
  public CompletionStage<Result<Receipt>> requestCreate(
      ServerPlayer founder, UUID operation, String external, String name, UUID funding) {
    if (!connected(founder) || !declarations.contains(Capability.CREATE))
      return immediate(fail(Status.DENIED, "REGISTRATION_UNAVAILABLE"));
    var k = key(external);
    return new CorporationService(founder)
        .quoteRegistration(new AccountId(funding))
        .handleAsync(
            (fee, error) -> {
              if (!connected(founder)) return fail(Status.UNAVAILABLE, "FOUNDER_UNAVAILABLE");
              if (error != null || fee.isEmpty()) {
                founder.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal(
                        "Integration "
                            + provider
                            + " requested incorporation. The selected funding account is unavailable."));
              } else if (!CorporationConsent.offer(
                  founder,
                  services,
                  operation,
                  new IntegrationBindings.Create(k, new Corporations.Register(name, fee.get())),
                  () -> enabled)) {
                founder.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal(
                        "Integration "
                            + provider
                            + " requested incorporation. A confirmation is currently unavailable."));
              }
              return new Result<>(
                  Status.APPROVAL_REQUIRED,
                  "FOUNDER_CONFIRMATION_REQUIRED",
                  Optional.of(new Receipt(operation, "PENDING_CONSENT")));
            },
            server);
  }

  @Override
  public CompletionStage<Result<Receipt>> requestBinding(
      ServerPlayer founder, UUID operation, String external, UUID company, long revision) {
    return immediate(
        offer(founder, operation, new IntegrationBindings.Bind(key(external), company, revision)));
  }

  @Override
  public CompletionStage<Result<Receipt>> requestPermissions(
      ServerPlayer founder, UUID operation, String external, Permission requested) {
    if (!declarations.containsAll(requested.capabilities()))
      return immediate(fail(Status.DENIED, "UNDECLARED_CAPABILITY"));
    var accounts = requested.accounts().stream().map(AccountId::new).collect(Collectors.toSet());
    return immediate(
        offer(
            founder,
            operation,
            new IntegrationBindings.Approve(
                key(external),
                requested.company(),
                requested.companyRevision(),
                requested.revision(),
                requested.capabilities(),
                accounts)));
  }
}
