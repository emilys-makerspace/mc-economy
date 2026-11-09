package ca.emilybelnavis.mcmods.economy.institution;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.FxFacts;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.math.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstitutionCapitalTest {
  @TempDir Path path;
  private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  private static final SystemActorId ACTOR = new SystemActorId(new UUID(0, 4321));

  @Test
  void rosterAndFiniteFundingPreserveCashAndExactResidual() {
    assertEquals(23, InstitutionCatalog.banks().size());
    assertEquals(
        18, InstitutionCatalog.banks().stream().filter(InstitutionCatalog.Entry::tier1).count());
    assertEquals(
        5,
        InstitutionCatalog.banks().stream()
            .filter(e -> e.tier() == InstitutionCatalog.Tier.HYBRID)
            .count());
    assertEquals(
        5,
        InstitutionCatalog.banks().stream()
            .filter(e -> e.tier() == InstitutionCatalog.Tier.TIER2_ONLY)
            .count());
    assertTrue(
        InstitutionCatalog.banks().stream()
                .filter(e -> e.baseCurrency() == CHF && e.tier1())
                .count()
            == 2);
    var world = new WorldId(UUID.randomUUID());
    var rates = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
    for (var currency : EconomyCurrency.all())
      rates.put(currency, new BigDecimal("1.000000000000"));
    rates.put(CHF, new BigDecimal("3.000000000000"));
    var publication =
        new FxFacts.Publication(
            LocalDate.of(2026, 11, 5), CAD, UUID.randomUUID(), UUID.randomUUID(), rates);
    long total = 0;
    for (int i = 0; i < 25; i++) {
      var allocation = InstitutionCapital.allocation(world, i, CAD, publication);
      total = Math.addExact(total, allocation.serverMinor());
      assertEquals(
          BigDecimal.valueOf(allocation.baseMinor())
              .movePointLeft(1)
              .setScale(0, RoundingMode.CEILING)
              .longValueExact(),
          allocation.operatingFloorMinor());
      assertTrue(allocation.residualNumerator().signum() >= 0);
      assertTrue(
          allocation
                  .residualNumerator()
                  .compareTo(
                      allocation
                          .residualDenominator()
                          .divide(
                              BigDecimal.valueOf(
                                  allocation.institution().baseCurrency().minorUnitsPerMajor())))
              < 0);
      if (allocation.institution().baseCurrency() == CHF)
        assertEquals(new BigDecimal("0.01000000000000"), allocation.residualNumerator());
    }
    assertEquals(2_800_000_000_000L, total);
    var bank = InstitutionCatalog.banks().getFirst().id();
    assertNotEquals(
        FinancialAccounts.id(world, bank, FinancialAccounts.Role.CASH, CAD),
        FinancialAccounts.id(world, bank, FinancialAccounts.Role.EQUITY, CAD));
    assertNotEquals(
        new InstitutionCatalog.SystemActor(world, bank).actorId(),
        new InstitutionCatalog.SystemActor(new WorldId(UUID.randomUUID()), bank).actorId());
    assertTrue(
        Arrays.stream(FinancialAccounts.Role.values())
            .filter(FinancialAccounts.Role::spendable)
            .toList()
            .equals(List.of(FinancialAccounts.Role.CASH)));
  }

  @Test
  void activationIsBoundedRestartableAndCannotRepeatIssuance() throws Exception {
    var store = open();
    try {
      var capital = capital(store);
      assertEquals(InstitutionCapital.State.WAITING_DENOMINATION, capital.prepare(NOW).state());
      assertEquals(Journal.Status.NOT_ACCEPTED, capital.advance(NOW).status());
      seedFx(store);
      assertEquals(
          InstitutionCapital.State.WAITING_FX,
          capital.prepare(NOW.plus(Duration.ofDays(3))).state());
      assertEquals(Journal.Status.COMMITTED, capital.advance(NOW).status());
      assertEquals(0, count(store, "institution_capital_allocations"));
      assertEquals(1, count(store, "institution_parties"));
      for (int i = 0; i < 8; i++) {
        assertEquals(Journal.Status.COMMITTED, capital.advance(NOW).status());
        assertEquals(i + 1, count(store, "institution_capital_allocations"));
      }
    } finally {
      store.close();
    }
    store = open();
    try {
      var capital = capital(store);
      assertEquals(8, capital.prepare(NOW).nextAllocation());
      for (int i = 8; i < 25; i++)
        assertEquals(
            Journal.Status.COMMITTED, capital.advance(NOW.plus(Duration.ofDays(4))).status());
      assertTrue(capital.prepare(NOW).active());
      assertEquals(26, count(store, "institution_parties"));
      assertEquals(25, count(store, "institution_capital_allocations"));
      long frontier = store.lastCommittedSequence();
      for (int i = 0; i < 3; i++)
        assertEquals("CAPITAL_ACTIVE", capital.advance(NOW).result().code());
      assertEquals(frontier, store.lastCommittedSequence());
      var world = store.snapshot().worldId();
      var treasury = InstitutionCapital.opening(world, CAD, latest(store));
      assertEquals(0, balance(store, treasury.treasuryCash()));
      assertEquals(0, balance(store, treasury.treasuryEquity()));
      long allocated =
          store.transaction(
              tx ->
                  Long.parseLong(
                      tx.query(
                              "SELECT sum(server_minor) AS amount FROM institution_capital_allocations",
                              List.of(),
                              1)
                          .getFirst()
                          .get("amount")));
      assertEquals(2_800_000_000_000L, allocated);
      LedgerProjection.rebuild(store);
      assertEquals(0, balance(store, treasury.treasuryCash()));
      for (var institution : InstitutionCatalog.all(CAD)) {
        var allocation = capital.inspect(institution.id()).orElseThrow();
        assertEquals(allocation.baseMinor(), balance(store, allocation.cash()));
        assertEquals(-allocation.baseMinor(), balance(store, allocation.equity()));
      }
    } finally {
      store.close();
    }
  }

  @Test
  void committedButUncertainIssuanceIsResolvedByOriginalIdentity() throws Exception {
    var raw = open();
    try {
      seedFx(raw);
      var uncertain = new UncertainStore(raw);
      var capital = capital(uncertain);
      uncertain.arm = true;
      assertEquals("CAPITAL_STARTED", capital.advance(NOW).result().code());
      assertEquals(1, count(raw, "institution_parties"));
      assertEquals(0, count(raw, "institution_capital_allocations"));
      assertEquals("CAPITAL_ALLOCATED", capital.advance(NOW).result().code());
      assertEquals(1, count(raw, "institution_capital_allocations"));
    } finally {
      raw.close();
    }
  }

  @Test
  void oldPostingVersionsAndOrdinarySettlementCannotAdmitInstitutionMoney() {
    String payload = UUID.randomUUID() + "|INSTITUTION_CASH|CAD|100|CAPITAL_ISSUE";
    assertThrows(IllegalArgumentException.class, () -> LedgerProjection.validatePosting(payload));
    assertThrows(IllegalArgumentException.class, () -> LedgerProjection.validatePostingV2(payload));
    LedgerProjection.validatePostingV3(payload);
    var legs =
        List.of(
            new PostingBatch.Leg(
                UUID.randomUUID(),
                PostingBatch.Role.INSTITUTION_CASH,
                CAD,
                new ca.emilybelnavis.mcmods.economy.api.Money(CAD, 100)),
            new PostingBatch.Leg(
                UUID.randomUUID(),
                PostingBatch.Role.INSTITUTION_CLAIM,
                CAD,
                new ca.emilybelnavis.mcmods.economy.api.Money(CAD, -100)));
    assertInstanceOf(
        JournalEngine.Reject.class,
        PostingBatch.prepareMinor(legs, PostingBatch.Authority.CAPITAL_ISSUE));
    assertInstanceOf(
        JournalEngine.Reject.class, PostingBatch.prepare(legs, PostingBatch.Authority.TRANSFER));
  }

  private SqliteStorage open() throws Exception {
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    return store;
  }

  private InstitutionCapital capital(FoundationStore store) {
    return new InstitutionCapital(store, ACTOR, CapitalPosting.poster());
  }

  private static void seedFx(FoundationStore store) {
    var engine =
        new JournalEngine(
            store,
            (actor, kind) -> actor.equals(ACTOR),
            Map.of(FxService.OPEN, FxService.opening(Clock.fixed(NOW, ZoneOffset.UTC))));
    var identity = UUID.randomUUID();
    assertEquals(
        Journal.Status.COMMITTED,
        engine
            .submit(
                new Journal.Intent(
                    new OperationId(identity),
                    new RequestId(identity),
                    ACTOR,
                    FxService.OPEN,
                    new Journal.CanonicalRequest(1, Map.of("currency", "CAD")),
                    null,
                    null))
            .status());
  }

  private static FxFacts.Publication latest(FoundationStore store) {
    return store.transaction(tx -> FxService.latest(tx::query));
  }

  private static long count(FoundationStore store, String table) {
    return store.transaction(
        tx ->
            Long.parseLong(
                tx.query("SELECT count(*) AS amount FROM " + table, List.of(), 1)
                    .getFirst()
                    .get("amount")));
  }

  private static long balance(FoundationStore store, FinancialAccounts.Account account) {
    return store.transaction(tx -> Settlement.posted(tx::query, account.id(), account.currency()));
  }

  private static final class UncertainStore implements FoundationStore {
    private final FoundationStore delegate;
    boolean arm;

    UncertainStore(FoundationStore delegate) {
      this.delegate = delegate;
    }

    public Snapshot snapshot() {
      return delegate.snapshot();
    }

    public long lastCommittedSequence() {
      return delegate.lastCommittedSequence();
    }

    public RecordCatalog recordCatalog() {
      return delegate.recordCatalog();
    }

    public void initialize(Path world) throws Exception {
      delegate.initialize(world);
    }

    public void close() throws Exception {
      delegate.close();
    }

    public void recover() {
      delegate.recover();
    }

    public <T> T transaction(Work<T> work) {
      long before = delegate.lastCommittedSequence();
      T result = delegate.transaction(work);
      if (arm && delegate.lastCommittedSequence() > before) {
        arm = false;
        throw new StorageException(
            StorageException.Kind.UNKNOWN_COMMIT, "Fixture lost commit acknowledgement");
      }
      return result;
    }
  }
}
