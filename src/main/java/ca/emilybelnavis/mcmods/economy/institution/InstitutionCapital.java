package ca.emilybelnavis.mcmods.economy.institution;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.FxFacts;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Worker-confined, one-time finite funding. Every turn commits at most one allocation. */
public final class InstitutionCapital {
  public static final String CATALOG = "economy:a6/catalog-publication";
  public static final String MANIFEST = "economy:a6/capital-manifest";
  public static final String ALLOCATION_RECORD = "economy:a6/capital-allocation";
  public static final String ACCOUNT = "economy:a6/institution-account";
  public static final long TOTAL_MAJOR = 28_000_000_000L;
  public static final InstitutionCatalog.InstitutionId TREASURY =
      new InstitutionCatalog.InstitutionId("economy:treasury/institution-capital");
  public static final Journal.Kind KIND =
      new Journal.Kind("economy:a6/capital", "economy:a6/capital-step");

  public enum State {
    WAITING_DENOMINATION,
    WAITING_FX,
    READY,
    ALLOCATING,
    ACTIVE
  }

  public record Status(
      State state, int nextAllocation, EconomyCurrency serverCurrency, UUID publicationId) {
    public boolean active() {
      return state == State.ACTIVE;
    }
  }

  public record Opening(
      WorldId world,
      EconomyCurrency serverCurrency,
      long totalServerMinor,
      FinancialAccounts.Account treasuryCash,
      FinancialAccounts.Account treasuryEquity,
      FinancialAccounts.Account treasuryRounding,
      FxFacts.Publication publication) {}

  public record Allocation(
      WorldId world,
      int ordinal,
      InstitutionCatalog.Entry institution,
      EconomyCurrency serverCurrency,
      long serverMinor,
      long baseMinor,
      BigDecimal exactBaseMajor,
      BigDecimal residualMajor,
      long operatingFloorMinor,
      FxFacts.Publication publication,
      FinancialAccounts.Account treasuryCash,
      FinancialAccounts.Account treasuryEquity,
      FinancialAccounts.Account treasuryRounding,
      FinancialAccounts.Account cash,
      FinancialAccounts.Account equity,
      FinancialAccounts.Account rounding) {
    /**
     * Exact rational major-unit residual; the decimal accessor is its precision-contract
     * representation.
     */
    public BigDecimal residualNumerator() {
      return BigDecimal.valueOf(serverMinor)
          .divide(BigDecimal.valueOf(serverCurrency.minorUnitsPerMajor()))
          .multiply(publication.rates().get(serverCurrency))
          .subtract(
              BigDecimal.valueOf(baseMinor)
                  .divide(BigDecimal.valueOf(institution.baseCurrency().minorUnitsPerMajor()))
                  .multiply(publication.rates().get(institution.baseCurrency())));
    }

    public BigDecimal residualDenominator() {
      return publication.rates().get(institution.baseCurrency());
    }
  }

  /**
   * Trusted A4 composition only; supplied identities are registered before its projection applies.
   */
  public interface FundingPoster {
    JournalEngine.Commit issue(JournalEngine.ReadView reads, Opening opening);

    JournalEngine.Commit allocate(JournalEngine.ReadView reads, Allocation allocation);
  }

  private record Manifest(
      EconomyCurrency currency,
      long revision,
      FxFacts.Publication publication,
      int next,
      boolean active) {}

  private final FoundationStore store;
  private final SystemActorId actor;
  private final FundingPoster poster;

  public InstitutionCapital(FoundationStore store, SystemActorId actor, FundingPoster poster) {
    this.store = Objects.requireNonNull(store);
    this.actor = Objects.requireNonNull(actor);
    this.poster = Objects.requireNonNull(poster);
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog
        .with(new RecordCatalog.Key(CATALOG, 1), InstitutionCapital::validateCatalog)
        .with(new RecordCatalog.Key(MANIFEST, 1), InstitutionCapital::validateManifest)
        .with(new RecordCatalog.Key(ALLOCATION_RECORD, 1), InstitutionCapital::validateAllocation)
        .with(new RecordCatalog.Key(ACCOUNT, 1), InstitutionCapital::validateAccount);
  }

  public Status status() {
    return prepare(Instant.now());
  }

  public Status prepare(Instant now) {
    Objects.requireNonNull(now);
    return store.transaction(tx -> status(tx::query, now));
  }

  private Status status(JournalEngine.ReadView reads, Instant now) {
    var manifest = manifest(reads, store.snapshot().worldId());
    if (manifest != null)
      return new Status(
          manifest.active ? State.ACTIVE : State.ALLOCATING,
          manifest.next,
          manifest.currency,
          manifest.publication.updateId());
    var denomination = RebaseSettlement.denomination(reads).orElse(null);
    if (denomination == null) return new Status(State.WAITING_DENOMINATION, 0, null, null);
    var publication = FxService.latest(reads);
    if (!fresh(reads, publication, now))
      return new Status(
          State.WAITING_FX,
          0,
          denomination.currency(),
          publication == null ? null : publication.updateId());
    return new Status(State.READY, 0, denomination.currency(), publication.updateId());
  }

  /** Stable step identities survive restart and A1 resolves uncertain commits before any retry. */
  public Journal.Outcome advance(Instant now) {
    Objects.requireNonNull(now);
    var status = prepare(now);
    int step =
        status.state == State.ALLOCATING || status.state == State.ACTIVE
            ? status.nextAllocation + 1
            : 0;
    if (status.state == State.ACTIVE)
      step = 25; // Replay the final committed allocation, never create a new operation.
    var world = store.snapshot().worldId();
    var identity = stable(world, "step/" + step);
    var intent =
        new Journal.Intent(
            new OperationId(identity),
            new RequestId(stable(world, "request/" + step)),
            actor,
            KIND,
            new Journal.CanonicalRequest(
                1, Map.of("world", world.value().toString(), "step", Integer.toString(step))),
            null,
            null);
    var engine =
        new JournalEngine(
            store,
            (candidate, kind) -> candidate.equals(actor) && kind.equals(KIND),
            Map.of(
                KIND,
                new JournalEngine.Registration(
                    1, (request, reads) -> commit(reads, now, stepOf(request)))));
    return engine.submit(intent);
  }

  private static int stepOf(Journal.Intent intent) {
    if (!intent.request().fields().keySet().equals(Set.of("world", "step")))
      throw new IllegalArgumentException("Capital request");
    int step = Integer.parseInt(intent.request().fields().get("step"));
    if (step < 0 || step > 25) throw new IllegalArgumentException("Capital step");
    return step;
  }

  private JournalEngine.Decision commit(JournalEngine.ReadView reads, Instant now, int step) {
    var world = store.snapshot().worldId();
    var current = manifest(reads, world);
    if (step == 0) {
      if (current != null) return retry("CAPITAL_ALREADY_STARTED");
      var denomination = RebaseSettlement.denomination(reads).orElse(null);
      if (denomination == null) return retry("CAPITAL_DENOMINATION_MISSING");
      var publication = FxService.latest(reads);
      if (!fresh(reads, publication, now)) return retry("CAPITAL_FX_UNAVAILABLE");
      var opening = opening(world, denomination.currency(), publication);
      var financial = poster.issue(reads, opening);
      var records = new ArrayList<>(financial.records());
      records.add(new Journal.Record(CATALOG, 1, catalogPayload(world, denomination.currency())));
      records.add(
          new Journal.Record(
              MANIFEST,
              1,
              manifestPayload(
                  world, denomination.currency(), denomination.revision(), publication)));
      var accounts =
          List.of(opening.treasuryCash, opening.treasuryEquity, opening.treasuryRounding);
      accounts.forEach(a -> records.add(new Journal.Record(ACCOUNT, 1, accountPayload(a))));
      return new JournalEngine.Commit(
          records,
          new Journal.Result(1, "CAPITAL_STARTED", "0"),
          tx -> {
            registerParty(
                tx,
                world,
                new InstitutionCatalog.Entry(
                    TREASURY,
                    "Institution capital treasury",
                    denomination.currency(),
                    InstitutionCatalog.Tier.INTERNAL,
                    Set.of(),
                    Set.of()));
            accounts.forEach(a -> FinancialAccounts.register(tx, a));
            tx.execute(
                "INSERT INTO institution_capital_manifest(world_id,version,state,server_currency,denomination_revision,publication_day,publication_reference,publication_id,publication_policy,rates,total_server_minor,next_allocation) VALUES(?,1,'ALLOCATING',?,?,?,?,?,?,?,?,0)",
                List.of(
                    world.value().toString(),
                    denomination.currency().name(),
                    Long.toString(denomination.revision()),
                    publication.day().toString(),
                    publication.reference().name(),
                    publication.updateId().toString(),
                    publication.policyId().toString(),
                    rates(publication),
                    Long.toString(opening.totalServerMinor)));
            financial.projections().apply(tx);
          });
    }
    if (current == null || current.active || current.next != step - 1)
      return retry("CAPITAL_CURSOR_CHANGED");
    var allocation = allocation(world, current.next, current.currency, current.publication);
    var financial = poster.allocate(reads, allocation);
    var records = new ArrayList<>(financial.records());
    records.add(new Journal.Record(ALLOCATION_RECORD, 1, allocationPayload(allocation)));
    var accounts = List.of(allocation.cash, allocation.equity, allocation.rounding);
    accounts.forEach(a -> records.add(new Journal.Record(ACCOUNT, 1, accountPayload(a))));
    int next = current.next + 1;
    return new JournalEngine.Commit(
        records,
        new Journal.Result(
            1, next == 25 ? "CAPITAL_ACTIVE" : "CAPITAL_ALLOCATED", Integer.toString(next)),
        tx -> {
          registerParty(tx, world, allocation.institution);
          accounts.forEach(a -> FinancialAccounts.register(tx, a));
          tx.execute(
              "INSERT INTO institution_capital_allocations(world_id,institution_key,ordinal,server_currency,server_minor,base_currency,base_minor,exact_base_major,residual_major,residual_numerator,residual_denominator,operating_floor_minor,publication_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
              List.of(
                  world.value().toString(),
                  allocation.institution.id().key(),
                  Integer.toString(allocation.ordinal),
                  current.currency.name(),
                  Long.toString(allocation.serverMinor),
                  allocation.institution.baseCurrency().name(),
                  Long.toString(allocation.baseMinor),
                  allocation.exactBaseMajor.toPlainString(),
                  allocation.residualMajor.toPlainString(),
                  allocation.residualNumerator().toPlainString(),
                  allocation.residualDenominator().toPlainString(),
                  Long.toString(allocation.operatingFloorMinor),
                  current.publication.updateId().toString()));
          tx.execute(
              "UPDATE institution_capital_manifest SET next_allocation=?,state=? WHERE world_id=? AND next_allocation=?",
              List.of(
                  Integer.toString(next),
                  next == 25 ? "ACTIVE" : "ALLOCATING",
                  world.value().toString(),
                  Integer.toString(current.next)));
          financial.projections().apply(tx);
        });
  }

  public static Opening opening(
      WorldId world, EconomyCurrency currency, FxFacts.Publication publication) {
    return new Opening(
        world,
        currency,
        Math.multiplyExact(TOTAL_MAJOR, currency.minorUnitsPerMajor()),
        account(world, TREASURY, FinancialAccounts.Role.CASH, currency),
        account(world, TREASURY, FinancialAccounts.Role.EQUITY, currency),
        account(world, TREASURY, FinancialAccounts.Role.ROUNDING, currency),
        publication);
  }

  /**
   * Defined 34-digit/18-decimal accrual precision, FLOOR settlement and retained major-unit
   * residual.
   */
  public static Allocation allocation(
      WorldId world, int ordinal, EconomyCurrency serverCurrency, FxFacts.Publication publication) {
    if (ordinal < 0 || ordinal >= 25 || !publication.rates().containsKey(EconomyCurrency.CHF))
      throw new IllegalArgumentException("Capital allocation inputs");
    var institution = InstitutionCatalog.all(serverCurrency).get(ordinal);
    long major =
        institution.tier() == InstitutionCatalog.Tier.TIER2_ONLY ? 4_000_000_000L : 400_000_000L;
    long sourceMinor = Math.multiplyExact(major, serverCurrency.minorUnitsPerMajor());
    var numerator = BigDecimal.valueOf(major).multiply(publication.rates().get(serverCurrency));
    var exact =
        numerator
            .divide(
                publication.rates().get(institution.baseCurrency()),
                new MathContext(34, RoundingMode.HALF_EVEN))
            .setScale(18, RoundingMode.HALF_EVEN);
    long baseMinor =
        numerator
            .multiply(BigDecimal.valueOf(institution.baseCurrency().minorUnitsPerMajor()))
            .divide(publication.rates().get(institution.baseCurrency()), 0, RoundingMode.FLOOR)
            .longValueExact();
    if (baseMinor <= 0) throw new IllegalArgumentException("Capital below one minor unit");
    var residual =
        exact.subtract(
            BigDecimal.valueOf(baseMinor)
                .divide(BigDecimal.valueOf(institution.baseCurrency().minorUnitsPerMajor())));
    long floor =
        BigDecimal.valueOf(baseMinor)
            .movePointLeft(1)
            .setScale(0, RoundingMode.CEILING)
            .longValueExact();
    var opening = opening(world, serverCurrency, publication);
    return new Allocation(
        world,
        ordinal,
        institution,
        serverCurrency,
        sourceMinor,
        baseMinor,
        exact,
        residual,
        floor,
        publication,
        opening.treasuryCash,
        opening.treasuryEquity,
        opening.treasuryRounding,
        account(world, institution.id(), FinancialAccounts.Role.CASH, institution.baseCurrency()),
        account(world, institution.id(), FinancialAccounts.Role.EQUITY, institution.baseCurrency()),
        account(
            world, institution.id(), FinancialAccounts.Role.ROUNDING, institution.baseCurrency()));
  }

  public Optional<Allocation> inspect(InstitutionCatalog.InstitutionId institution) {
    return store.transaction(
        tx -> {
          var manifest = manifest(tx::query, store.snapshot().worldId());
          if (manifest == null) return Optional.empty();
          var entries = InstitutionCatalog.all(manifest.currency);
          for (int i = 0; i < Math.min(manifest.next, entries.size()); i++)
            if (entries.get(i).id().equals(institution))
              return Optional.of(
                  allocation(
                      store.snapshot().worldId(), i, manifest.currency, manifest.publication));
          return Optional.empty();
        });
  }

  private static FinancialAccounts.Account account(
      WorldId world,
      InstitutionCatalog.InstitutionId owner,
      FinancialAccounts.Role role,
      EconomyCurrency currency) {
    return FinancialAccounts.account(world, owner, role, currency);
  }

  private static boolean fresh(
      JournalEngine.ReadView reads, FxFacts.Publication publication, Instant now) {
    if (publication == null || !publication.rates().containsKey(EconomyCurrency.CHF)) return false;
    var start = Instant.ofEpochMilli(FxService.effectiveMillis(reads, publication.updateId()));
    return !now.isBefore(start) && now.isBefore(start.plus(Duration.ofHours(48)));
  }

  private static JournalEngine.Retry retry(String code) {
    return new JournalEngine.Retry(new Journal.Result(1, code, ""));
  }

  private static UUID stable(WorldId world, String suffix) {
    return UUID.nameUUIDFromBytes(
        ("economy:a6/capital:v1|" + world.value() + "|" + suffix).getBytes(StandardCharsets.UTF_8));
  }

  private static String rates(FxFacts.Publication publication) {
    var values = new StringJoiner(",");
    for (var currency : EconomyCurrency.all())
      values.add(currency.name() + "=" + publication.rates().get(currency).toPlainString());
    return values.toString();
  }

  private static FxFacts.Publication publication(Map<String, String> row) {
    var rates = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
    for (var item : row.get("rates").split(",", -1)) {
      var pair = item.split("=", -1);
      if (pair.length != 2) throw new IllegalArgumentException("Capital FX");
      rates.put(EconomyCurrency.valueOf(pair[0]), new BigDecimal(pair[1]));
    }
    return new FxFacts.Publication(
        LocalDate.parse(row.get("publication_day")),
        EconomyCurrency.valueOf(row.get("publication_reference")),
        UUID.fromString(row.get("publication_id")),
        UUID.fromString(row.get("publication_policy")),
        rates);
  }

  private static Manifest manifest(JournalEngine.ReadView reads, WorldId world) {
    var rows =
        reads.query(
            "SELECT * FROM institution_capital_manifest WHERE world_id=?",
            List.of(world.value().toString()),
            1);
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    var frozen = publication(row);
    var source = FxService.snapshot(reads, frozen.updateId());
    if (source == null || !source.world().equals(world) || !source.publication().equals(frozen))
      throw new IllegalArgumentException("Capital original FX snapshot unavailable or changed");
    return new Manifest(
        EconomyCurrency.valueOf(row.get("server_currency")),
        Long.parseLong(row.get("denomination_revision")),
        frozen,
        Integer.parseInt(row.get("next_allocation")),
        row.get("state").equals("ACTIVE"));
  }

  private static void registerParty(
      FoundationStore.Transaction tx, WorldId world, InstitutionCatalog.Entry entry) {
    tx.execute(
        "INSERT INTO institution_parties(world_id,institution_key,actor_id,display_name,base_currency,tier,enabled) VALUES(?,?,?,?,?,?,1)",
        List.of(
            world.value().toString(),
            entry.id().key(),
            new InstitutionCatalog.SystemActor(world, entry.id()).actorId().value().toString(),
            entry.name(),
            entry.baseCurrency().name(),
            entry.tier().name()));
  }

  private static String catalogPayload(WorldId world, EconomyCurrency currency) {
    var value = new StringJoiner("\n");
    value.add(world.value() + "|" + currency.name());
    for (var entry : InstitutionCatalog.all(currency))
      value.add(
          entry.id().key() + "|" + entry.name() + "|" + entry.baseCurrency() + "|" + entry.tier());
    return value.toString();
  }

  private static String manifestPayload(
      WorldId world, EconomyCurrency currency, long revision, FxFacts.Publication publication) {
    return world.value()
        + "|"
        + currency
        + "|"
        + revision
        + "|"
        + publication.day()
        + "|"
        + publication.reference()
        + "|"
        + publication.updateId()
        + "|"
        + publication.policyId()
        + "|"
        + rates(publication)
        + "|"
        + TOTAL_MAJOR;
  }

  private static String allocationPayload(Allocation a) {
    return a.world.value()
        + "|"
        + a.ordinal
        + "|"
        + a.institution.id().key()
        + "|"
        + a.serverCurrency
        + "|"
        + a.serverMinor
        + "|"
        + a.institution.baseCurrency()
        + "|"
        + a.baseMinor
        + "|"
        + a.exactBaseMajor.toPlainString()
        + "|"
        + a.residualMajor.toPlainString()
        + "|"
        + a.residualNumerator().toPlainString()
        + "|"
        + a.residualDenominator().toPlainString()
        + "|"
        + a.operatingFloorMinor
        + "|"
        + a.publication.updateId();
  }

  private static String accountPayload(FinancialAccounts.Account a) {
    return a.id()
        + "|"
        + a.world().value()
        + "|"
        + a.owner().key()
        + "|"
        + a.role()
        + "|"
        + a.currency();
  }

  private static void validateCatalog(String payload) {
    var lines = payload.split("\n", -1);
    if (lines.length != 26) throw new IllegalArgumentException("Catalog count");
    var header = lines[0].split("\\|", -1);
    if (header.length != 2) throw new IllegalArgumentException("Catalog header");
    if (!catalogPayload(new WorldId(UUID.fromString(header[0])), EconomyCurrency.valueOf(header[1]))
        .equals(payload)) throw new IllegalArgumentException("Catalog manifest");
  }

  private static void validateManifest(String payload) {
    var p = payload.split("\\|", -1);
    if (p.length != 9 || Long.parseLong(p[2]) < 0 || Long.parseLong(p[8]) != TOTAL_MAJOR)
      throw new IllegalArgumentException("Capital manifest");
    var row =
        Map.of(
            "publication_day",
            p[3],
            "publication_reference",
            p[4],
            "publication_id",
            p[5],
            "publication_policy",
            p[6],
            "rates",
            p[7]);
    var publication = publication(row);
    if (!manifestPayload(
            new WorldId(UUID.fromString(p[0])),
            EconomyCurrency.valueOf(p[1]),
            Long.parseLong(p[2]),
            publication)
        .equals(payload)) throw new IllegalArgumentException("Capital manifest encoding");
  }

  private static void validateAllocation(String payload) {
    var p = payload.split("\\|", -1);
    if (p.length != 13) throw new IllegalArgumentException("Capital allocation");
    UUID.fromString(p[0]);
    int ordinal = Integer.parseInt(p[1]);
    var server = EconomyCurrency.valueOf(p[3]);
    var institution = InstitutionCatalog.all(server).get(ordinal);
    if (!institution.id().key().equals(p[2])
        || !institution.baseCurrency().name().equals(p[5])
        || Long.parseLong(p[4])
            != Math.multiplyExact(
                institution.tier() == InstitutionCatalog.Tier.TIER2_ONLY
                    ? 4_000_000_000L
                    : 400_000_000L,
                server.minorUnitsPerMajor()))
      throw new IllegalArgumentException("Capital allocation manifest");
    long minor = Long.parseLong(p[6]);
    var exact = new BigDecimal(p[7]);
    var residual = new BigDecimal(p[8]);
    if (minor <= 0
        || exact.scale() != 18
        || residual.signum() < 0
        || residual.compareTo(
                BigDecimal.ONE.divide(
                    BigDecimal.valueOf(institution.baseCurrency().minorUnitsPerMajor())))
            >= 0
        || exact
                .subtract(
                    BigDecimal.valueOf(minor)
                        .divide(
                            BigDecimal.valueOf(institution.baseCurrency().minorUnitsPerMajor())))
                .compareTo(residual)
            != 0
        || Long.parseLong(p[11])
            != BigDecimal.valueOf(minor)
                .movePointLeft(1)
                .setScale(0, RoundingMode.CEILING)
                .longValueExact()) throw new IllegalArgumentException("Capital amounts");
    var numerator = new BigDecimal(p[9]);
    var denominator = new BigDecimal(p[10]);
    if (numerator.signum() < 0
        || denominator.signum() <= 0
        || numerator.compareTo(
                denominator.divide(
                    BigDecimal.valueOf(institution.baseCurrency().minorUnitsPerMajor())))
            >= 0) throw new IllegalArgumentException("Capital exact residual");
    UUID.fromString(p[12]);
  }

  private static void validateAccount(String payload) {
    var p = payload.split("\\|", -1);
    if (p.length != 5 || p[3].equals("ALLOWANCE"))
      throw new IllegalArgumentException("Institution account");
    new FinancialAccounts.Account(
        UUID.fromString(p[0]),
        new WorldId(UUID.fromString(p[1])),
        new InstitutionCatalog.InstitutionId(p[2]),
        FinancialAccounts.Role.valueOf(p[3]),
        EconomyCurrency.valueOf(p[4]));
  }
}
