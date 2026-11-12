package ca.emilybelnavis.mcmods.economy.overdraft;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.loan.Loans;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.math.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Bank debt is the signed BANK ledger balance; this table stores contractual terms and clocks. */
public final class Overdrafts {
  public static final Journal.Kind APPLY = new Journal.Kind("economy:overdraft", "economy:apply");
  public static final Journal.Kind DEBIT = new Journal.Kind("economy:overdraft", "economy:debit");
  public static final String FACT = "economy:overdraft-state";
  public static final long DAY = FinancialCalendar.DAY_MILLIS;
  private static final MathContext MC = MathContext.DECIMAL128;

  private Overdrafts() {}

  /** These authorities are supplied by trusted mod code, never by a banking network packet. */
  public enum DebitKind {
    NORMAL,
    PRE_AUTHORIZED,
    INTEGRATION_OVERRIDE,
    BANK_CHARGE
  }

  public record Protection(
      WorldId world,
      PlayerId owner,
      UUID account,
      EconomyCurrency currency,
      String bank,
      long limitMinor,
      BigDecimal apr,
      long enrolledAt,
      long nextDay,
      long nextMonth,
      long episodeAt,
      long positiveAt,
      BigDecimal residual,
      long revision,
      boolean closed,
      long interestAt,
      BigDecimal accruedMinor) {
    public Protection(
        WorldId world,
        PlayerId owner,
        UUID account,
        EconomyCurrency currency,
        String bank,
        long limitMinor,
        BigDecimal apr,
        long enrolledAt,
        long nextDay,
        long nextMonth,
        long episodeAt,
        long positiveAt,
        BigDecimal residual,
        long revision,
        boolean closed) {
      this(
          world,
          owner,
          account,
          currency,
          bank,
          limitMinor,
          apr,
          enrolledAt,
          nextDay,
          nextMonth,
          episodeAt,
          positiveAt,
          residual,
          revision,
          closed,
          Math.max(0, nextDay - DAY),
          BigDecimal.ZERO);
    }

    public Protection {
      Objects.requireNonNull(world);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(account);
      Objects.requireNonNull(currency);
      Objects.requireNonNull(bank);
      Objects.requireNonNull(apr);
      Objects.requireNonNull(residual);
      Objects.requireNonNull(accruedMinor);
      if (limitMinor < 0
          || apr.signum() < 0
          || apr.compareTo(new BigDecimal("0.18")) > 0
          || enrolledAt < -1
          || nextDay < 0
          || nextMonth < 0
          || episodeAt < -1
          || positiveAt < -1
          || revision < 1
          || residual.abs().compareTo(BigDecimal.ONE) >= 0
          || interestAt < 0
          || accruedMinor.signum() < 0
          || accruedMinor.precision() > 40
          || accruedMinor.scale() > 40) throw new IllegalArgumentException("Overdraft state");
    }

    public boolean protectedAccount() {
      return limitMinor > 0;
    }
  }

  public record ApplicationRequest(
      WorldId world,
      PlayerId owner,
      AccountId account,
      long limitMinor,
      long accountRevision,
      long profileRevision,
      UUID fxPublication) {
    public ApplicationRequest {
      Objects.requireNonNull(world);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(account);
      Objects.requireNonNull(fxPublication);
      if (limitMinor <= 0 || accountRevision < 1 || profileRevision < 0)
        throw new IllegalArgumentException("Overdraft application");
    }

    public Journal.CanonicalRequest request() {
      return new Journal.CanonicalRequest(
          1,
          Map.of(
              "world",
              world.value().toString(),
              "economy:owner",
              Journal.actorKey(owner),
              "account",
              account.value().toString(),
              "limit",
              Long.toString(limitMinor),
              "account_revision",
              Long.toString(accountRevision),
              "profile_revision",
              Long.toString(profileRevision),
              "fx",
              fxPublication.toString()));
    }
  }

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE overdraft_accounts(account_id TEXT PRIMARY KEY REFERENCES account_catalog(account_id),world_id TEXT NOT NULL,owner_id TEXT NOT NULL,currency TEXT NOT NULL,bank TEXT NOT NULL,limit_minor INTEGER NOT NULL CHECK(limit_minor>=0),apr TEXT NOT NULL,enrolled_at INTEGER NOT NULL,next_day INTEGER NOT NULL,next_month INTEGER NOT NULL,episode_at INTEGER NOT NULL,positive_at INTEGER NOT NULL,residual TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>=1),closed INTEGER NOT NULL CHECK(closed IN(0,1)),interest_at INTEGER NOT NULL CHECK(interest_at>=0),accrued_minor TEXT NOT NULL)",
        "CREATE INDEX overdraft_owner ON overdraft_accounts(world_id,owner_id,account_id)",
        "CREATE INDEX overdraft_daily ON overdraft_accounts("
            + OverdraftRuntime.DUE
            + ",account_id) WHERE closed=0",
        "CREATE TRIGGER overdraft_identity BEFORE UPDATE ON overdraft_accounts WHEN NEW.account_id<>OLD.account_id OR NEW.world_id<>OLD.world_id OR NEW.owner_id<>OLD.owner_id OR OLD.closed=1 OR NEW.revision<=OLD.revision BEGIN SELECT RAISE(ABORT,'invalid overdraft transition'); END",
        "CREATE TRIGGER overdraft_delete BEFORE DELETE ON overdraft_accounts BEGIN SELECT RAISE(ABORT,'retained overdraft history'); END");
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog.with(new RecordCatalog.Key(FACT, 1), Overdrafts::decode);
  }

  public static List<DeadlineDispatcher.Provider> providers() {
    return List.of(new OverdraftRuntime());
  }

  public static Optional<Protection> find(JournalEngine.ReadView reads, UUID account) {
    return reads
        .query(
            "SELECT * FROM overdraft_accounts WHERE account_id=?", List.of(account.toString()), 1)
        .stream()
        .map(Overdrafts::read)
        .findFirst();
  }

  /** Score bands match existing revolving-credit bands, with the approved 12–18% APR range. */
  public static BigDecimal apr(int score) {
    if (score < 300 || score > 900) throw new IllegalArgumentException("Credit score");
    return new BigDecimal(
        score < 580
            ? "0.18"
            : score < 650
                ? "0.17"
                : score < 700
                    ? "0.16"
                    : score < 750 ? "0.15" : score < 800 ? "0.14" : score < 850 ? "0.13" : "0.12");
  }

  public static long minimum(JournalEngine.ReadView reads, EconomyCurrency currency, long now) {
    return converted(
        reads, currency, new BigDecimal("100"), currency == EconomyCurrency.CAD ? 1 : 20, now);
  }

  public static long tolerance(JournalEngine.ReadView reads, EconomyCurrency currency, long now) {
    return converted(
        reads, currency, new BigDecimal("5"), currency == EconomyCurrency.CAD ? 1 : 5, now);
  }

  /** Current published conversion; fees use minor-unit HALF_UP, allowances use whole multiples. */
  public static long converted(
      JournalEngine.ReadView reads,
      EconomyCurrency currency,
      BigDecimal cadMajor,
      int multiple,
      long now) {
    BigDecimal major = cadMajor;
    if (currency != EconomyCurrency.CAD) {
      var fx = FxService.at(reads, now);
      if (fx == null
          || !fx.rates().containsKey(EconomyCurrency.CAD)
          || !fx.rates().containsKey(currency))
        throw new IllegalStateException("OVERDRAFT_FX_UNAVAILABLE");
      var pair =
          fx.rates()
              .get(currency)
              .divide(fx.rates().get(EconomyCurrency.CAD), 8, RoundingMode.DOWN);
      major = major.multiply(pair, MC).setScale(8, RoundingMode.DOWN);
    }
    if (multiple > 1)
      major =
          major
              .divide(BigDecimal.valueOf(multiple), 0, RoundingMode.HALF_UP)
              .multiply(BigDecimal.valueOf(multiple));
    return major
        .multiply(BigDecimal.valueOf(currency.minorUnitsPerMajor()), MC)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  /**
   * Credits remain possible when fees/overrides have already pushed the account beyond its limit.
   */
  public static boolean permits(
      JournalEngine.ReadView reads,
      UUID account,
      EconomyCurrency currency,
      long oldBalance,
      long nextBalance,
      long reserved,
      DebitKind kind) {
    if (nextBalance >= oldBalance) return true;
    if (nextBalance >= reserved) return true;
    if (kind == DebitKind.BANK_CHARGE || kind == DebitKind.INTEGRATION_OVERRIDE) return true;
    var state = find(reads, account).orElse(null);
    if (state != null && state.closed()) return false;
    if (state != null && state.protectedAccount()) {
      var provider = BankProviders.find(reads, new AccountId(account)).orElse(null);
      if (state.currency() != currency
          || provider == null
          || !provider.bankKey().equals(state.bank())) return false;
    }
    long allowance;
    try {
      allowance =
          state != null && state.protectedAccount()
              ? state.limitMinor()
              : tolerance(reads, currency, now(reads));
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      return false;
    }
    return nextBalance >= Math.subtractExact(reserved, allowance);
  }

  public static JournalEngine.Registration application(
      PartyRef owner, ApplicationRequest request, LongSupplier clock) {
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!request.request().equals(intent.request())
              || !matches(owner, request.world(), request.owner())
              || !intent.actor().equals(request.owner())) return reject("OVERDRAFT_ACCESS");
          long now = clock.getAsLong();
          var account = AccountCatalog.find(reads, owner, request.account()).orElse(null);
          if (account == null
              || account.purpose() != AccountIdentity.Purpose.BANK
              || account.status() != AccountIdentity.Status.OPEN
              || account.revision() != request.accountRevision())
            return reject("OVERDRAFT_ACCOUNT_STALE");
          var old = find(reads, request.account().value()).orElse(null);
          if (old != null && old.closed()) return reject("OVERDRAFT_CLOSED");
          if (old != null && request.limitMinor() <= old.limitMinor())
            return reject("OVERDRAFT_INCREASE_REQUIRED");
          var profile = CreditProfiles.find(reads, owner).orElse(null);
          if ((profile == null ? 0 : profile.revision()) != request.profileRevision())
            return reject("CREDIT_PROFILE_STALE");
          int score = profile == null ? CreditProfiles.INITIAL_SCORE : profile.score();
          if (score < 500) return reject("SCORE_BELOW_PRODUCT_FLOOR");
          var fx = FxService.at(reads, now);
          if (fx == null || !fx.id().equals(request.fxPublication()))
            return reject("OVERDRAFT_FX_STALE");
          long minimum = minimum(reads, account.currency(), now);
          long step =
              Math.multiplyExact(
                  account.currency().minorUnitsPerMajor(),
                  account.currency() == EconomyCurrency.CAD ? 1 : 20);
          if (request.limitMinor() < minimum || request.limitMinor() % step != 0)
            return reject("OVERDRAFT_LIMIT_INCREMENT");
          var denomination = RebaseSettlement.denomination(reads).orElse(null);
          if (denomination == null) return reject("DENOMINATION_UNAVAILABLE");
          var source =
              new CreditUnderwriting.FxSnapshot(
                  fx.id(), fx.effectiveMillis(), now, fx.rates(), true);
          long requestedWorld =
              CreditUnderwriting.exposureToWorld(
                      new Money(account.currency(), request.limitMinor()),
                      denomination.currency(),
                      source)
                  .minorUnits();
          long oldNative =
              old == null
                  ? 0
                  : Math.max(
                      old.limitMinor(),
                      Math.max(
                          0,
                          Math.negateExact(
                              Settlement.posted(
                                  reads, request.account().value(), account.currency()))));
          long oldWorld =
              CreditUnderwriting.exposureToWorld(
                      new Money(account.currency(), oldNative), denomination.currency(), source)
                  .minorUnits();
          long exposure =
              Math.addExact(
                  CreditFacilities.exposure(reads, owner, denomination.currency(), source),
                  Loans.exposure(reads, owner, denomination.currency(), source, now));
          exposure =
              Math.addExact(
                  exposure,
                  OverdraftExposure.exposure(reads, owner, denomination.currency(), source));
          if (Loans.blocked(reads, owner, now)
              || creditBlocked(reads, owner, now)
              || OverdraftExposure.blocked(reads, owner, now))
            return reject("ARREARS_DEFAULT_OR_COLLECTION");
          long ceiling =
              Math.multiplyExact(
                  CreditUnderwriting.exposureCeiling(score, false),
                  denomination.currency().minorUnitsPerMajor());
          long revolving =
              Math.multiplyExact(
                  CreditUnderwriting.revolvingCeiling(score),
                  denomination.currency().minorUnitsPerMajor());
          if (requestedWorld > revolving
              || Math.addExact(Math.subtractExact(exposure, oldWorld), requestedWorld) > ceiling)
            return reject("COMBINED_REVOLVING_CEILING");
          var binding = BankProviders.find(reads, request.account()).orElse(null);
          if (binding == null || binding.legacy()) return reject("OVERDRAFT_BANK_UNAVAILABLE");
          InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(binding.bankKey()));
          long balance = Settlement.posted(reads, request.account().value(), account.currency());
          if (old != null) old = accrue(old, balance, now);
          Protection next =
              new Protection(
                  owner.world(),
                  request.owner(),
                  request.account().value(),
                  account.currency(),
                  binding.bankKey(),
                  request.limitMinor(),
                  apr(score),
                  old != null && old.protectedAccount() ? old.enrolledAt() : now,
                  old == null || !old.protectedAccount() ? Math.addExact(now, DAY) : old.nextDay(),
                  old != null && old.protectedAccount()
                      ? old.nextMonth()
                      : Math.addExact(now, FinancialCalendar.MONTH_MILLIS),
                  old == null ? balance < 0 ? now : -1 : old.episodeAt(),
                  old == null ? -1 : old.positiveAt(),
                  old == null ? BigDecimal.ZERO : old.residual(),
                  old == null ? 1 : old.revision() + 1,
                  false,
                  now,
                  old == null ? BigDecimal.ZERO : old.accruedMinor());
          return stateCommit(
              next, new Journal.Result(1, "OVERDRAFT_APPROVED", Long.toString(next.limitMinor())));
        });
  }

  public static long exposure(
      JournalEngine.ReadView reads,
      PartyRef owner,
      EconomyCurrency worldCurrency,
      CreditUnderwriting.FxSnapshot fx) {
    return OverdraftExposure.exposure(reads, owner, worldCurrency, fx);
  }

  /** Advisory ceiling; application rechecks revisions and full exposure under its journal lock. */
  public static long maximum(
      JournalEngine.ReadView reads, PartyRef owner, EconomyCurrency currency, long now) {
    var profile = CreditProfiles.find(reads, owner).orElse(null);
    int score = profile == null ? CreditProfiles.INITIAL_SCORE : profile.score();
    if (score < 500
        || Loans.blocked(reads, owner, now)
        || creditBlocked(reads, owner, now)
        || OverdraftExposure.blocked(reads, owner, now)) return 0;
    var fx = FxService.at(reads, now);
    var denomination = RebaseSettlement.denomination(reads).orElse(null);
    if (fx == null || denomination == null) return 0;
    var source =
        new CreditUnderwriting.FxSnapshot(fx.id(), fx.effectiveMillis(), now, fx.rates(), true);
    long exposure =
        Math.addExact(
            CreditFacilities.exposure(reads, owner, denomination.currency(), source),
            Loans.exposure(reads, owner, denomination.currency(), source, now));
    exposure =
        Math.addExact(
            exposure, OverdraftExposure.exposure(reads, owner, denomination.currency(), source));
    long room =
        Math.max(
            0,
            Math.subtractExact(
                Math.multiplyExact(
                    CreditUnderwriting.exposureCeiling(score, false),
                    denomination.currency().minorUnitsPerMajor()),
                exposure));
    long maximum =
        Math.min(
            room,
            Math.multiplyExact(
                CreditUnderwriting.revolvingCeiling(score),
                denomination.currency().minorUnitsPerMajor()));
    long converted =
        CreditUnderwriting.ceilingToFacility(
                new Money(denomination.currency(), maximum), currency, source)
            .minorUnits();
    long step =
        Math.multiplyExact(currency.minorUnitsPerMajor(), currency == EconomyCurrency.CAD ? 1 : 20);
    return converted / step * step;
  }

  public static Journal.CanonicalRequest debitRequest(
      PartyRef owner, UUID source, UUID target, Money amount, DebitKind kind) {
    return new Journal.CanonicalRequest(
        1,
        Map.of(
            "world",
            owner.world().value().toString(),
            "economy:owner",
            Journal.actorKey(((PartyRef.Player) owner.owner()).id()),
            "source",
            source.toString(),
            "target",
            target.toString(),
            "currency",
            amount.currency().name(),
            "amount",
            Long.toString(amount.minorUnits()),
            "debit_kind",
            kind.name()));
  }

  public static JournalEngine.Registration debit(
      PartyRef owner, UUID source, UUID target, Money amount, DebitKind kind, LongSupplier clock) {
    if (source.equals(target) || amount.minorUnits() <= 0)
      throw new IllegalArgumentException("Overdraft debit");
    var request = debitRequest(owner, source, target, amount, kind);
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!request.equals(intent.request())
              || !(owner.owner() instanceof PartyRef.Player p)
              || !intent.actor().equals(p.id())) return reject("OVERDRAFT_ACCESS");
          var account = AccountCatalog.find(reads, owner, new AccountId(source)).orElse(null);
          if (account == null
              || account.status() != AccountIdentity.Status.OPEN
              || account.currency() != amount.currency()) return reject("OVERDRAFT_ACCOUNT");
          if (reads
              .query(
                  "SELECT account_id FROM account_catalog WHERE account_id=? AND purpose='BANK' AND status='OPEN' AND currency=?",
                  List.of(target.toString(), amount.currency().name()),
                  1)
              .isEmpty()) return reject("OVERDRAFT_DESTINATION");
          long before = Settlement.posted(reads, source, amount.currency());
          long next = Math.subtractExact(before, amount.minorUnits());
          if (!permits(
              reads,
              source,
              amount.currency(),
              before,
              next,
              Holds.reserved(reads, source, amount.currency()),
              kind)) {
            var state = find(reads, source).orElse(null);
            if (kind != DebitKind.PRE_AUTHORIZED
                || state != null && state.protectedAccount()
                || next >= -tolerance(reads, amount.currency(), clock.getAsLong()))
              return reject("INSUFFICIENT_FUNDS");
            var binding = BankProviders.find(reads, new AccountId(source)).orElse(null);
            if (binding == null || binding.legacy()) return reject("OVERDRAFT_BANK_UNAVAILABLE");
            var fee =
                new Money(
                    amount.currency(),
                    converted(
                        reads, amount.currency(), new BigDecimal("49.99"), 0, clock.getAsLong()));
            var charged =
                FeeSettlement.charge(
                    reads,
                    owner.world(),
                    p.id(),
                    new InstitutionCatalog.InstitutionId(binding.bankKey()),
                    fee,
                    source);
            if (!(charged instanceof JournalEngine.Commit commit)) return charged;
            return new JournalEngine.Commit(
                commit.records(),
                new Journal.Result(1, "PAD_DECLINED_FEE_CHARGED", Long.toString(fee.minorUnits())),
                commit.projections());
          }
          return Settlement.prepareTrusted(
              reads,
              List.of(
                  new PostingBatch.Leg(
                      source, PostingBatch.Role.ACCOUNT, amount.currency(), amount.negate()),
                  new PostingBatch.Leg(
                      target, PostingBatch.Role.ACCOUNT, amount.currency(), amount)),
              PostingBatch.Authority.TRANSFER,
              kind);
        });
  }

  /** Every posting-based balance change starts/resets the debt clock in the same journal commit. */
  public static JournalEngine.Decision decorate(
      Journal.Intent intent, JournalEngine.ReadView reads, JournalEngine.Commit base) {
    long effective = now(reads);
    for (var record : base.records()) {
      if (record.type().equals(EconomicRecord.TYPE)) {
        effective = EconomicRecord.decode(record.payload()).endMillis();
        break;
      }
      if (record.type().equals(DeadlineDispatcher.PROGRESS_RECORD)
          || record.type().equals(DeadlineDispatcher.COMPLETION)) {
        effective = Long.parseLong(record.payload().split("\\|", -1)[3]);
        break;
      }
    }
    return decorate(intent, reads, base, effective);
  }

  public static JournalEngine.Decision decorate(
      Journal.Intent intent, JournalEngine.ReadView reads, JournalEngine.Commit base, long now) {
    record AccountCurrency(UUID account, EconomyCurrency currency) {}
    var deltas = new LinkedHashMap<AccountCurrency, Long>();
    var prefixes = new HashMap<AccountCurrency, Long>();
    var planned = new HashMap<UUID, Protection>();
    for (var fact : base.records()) {
      if (fact.type().equals("economy:posting")) {
        var p = fact.payload().split("\\|", -1);
        if (p.length == 5 && p[1].equals("ACCOUNT")) {
          var key = new AccountCurrency(UUID.fromString(p[0]), EconomyCurrency.valueOf(p[2]));
          deltas.merge(key, Long.parseLong(p[3]), Math::addExact);
        }
      } else if (fact.type().equals(FACT)) {
        var state = decode(fact.payload());
        planned.put(state.account(), state);
        var key = new AccountCurrency(state.account(), state.currency());
        prefixes.put(key, deltas.getOrDefault(key, 0L));
      }
    }
    var result = base;
    for (var e : deltas.entrySet()) {
      UUID accountId = e.getKey().account();
      var currency = e.getKey().currency();
      long prefix = prefixes.getOrDefault(e.getKey(), 0L);
      if (planned.containsKey(accountId) && e.getValue() == prefix) continue;
      var accountRows =
          reads.query(
              "SELECT owner_id,status,currency FROM account_catalog WHERE account_id=? AND purpose='BANK'",
              List.of(accountId.toString()),
              1);
      if (accountRows.isEmpty()
          || !accountRows.getFirst().get("status").equals("OPEN")
          || !accountRows.getFirst().get("currency").equals(currency.name())) continue;
      long posted = Settlement.posted(reads, accountId, currency);
      long previous = Math.addExact(posted, prefix);
      long balance = Math.addExact(posted, e.getValue());
      var state =
          planned.containsKey(accountId)
              ? planned.get(accountId)
              : find(reads, accountId).map(s -> refreshUnprotected(reads, s)).orElse(null);
      if (state == null && balance >= 0) continue;
      if (state != null && state.closed()) continue;
      if (state == null) {
        var binding = BankProviders.find(reads, new AccountId(accountId)).orElse(null);
        var world = world(reads);
        state =
            new Protection(
                world,
                new PlayerId(UUID.fromString(accountRows.getFirst().get("owner_id"))),
                accountId,
                currency,
                binding == null ? "" : binding.bankKey(),
                0,
                BigDecimal.ZERO,
                -1,
                Math.addExact(now, DAY),
                Long.MAX_VALUE,
                now,
                -1,
                BigDecimal.ZERO,
                1,
                false,
                now,
                BigDecimal.ZERO);
      } else {
        state = accrue(state, previous, now);
        state =
            transition(
                state, balance, now, state.nextDay(), state.nextMonth(), state.residual(), false);
      }
      if (expired(state, balance, now)) {
        long interest =
            state
                .accruedMinor()
                .add(state.residual(), MC)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        if (interest > 0)
          result =
              combine(
                  result,
                  charge(FeeSettlement.overlay(reads, result.records()), state, interest),
                  base.result());
        var closure =
            OverdraftCollections.defaultAccount(
                FeeSettlement.overlay(reads, result.records()), accountId, now);
        if (!(closure instanceof JournalEngine.Commit accepted)) return closure;
        result = combine(result, accepted, base.result());
        state = closed(state);
      }
      result = combine(result, stateCommit(state, base.result()), base.result());
    }
    return result;
  }

  static Protection transition(
      Protection old,
      long balance,
      long now,
      long nextDay,
      long nextMonth,
      BigDecimal residual,
      boolean closed) {
    long episode = old.episodeAt(), positive = old.positiveAt();
    if (episode >= 0 && positive >= 0 && now >= Math.addExact(positive, DAY)) {
      episode = -1;
      positive = -1;
    }
    if (balance < 0) {
      if (episode < 0) episode = now;
      positive = -1;
    } else if (balance > 0 && episode >= 0 && positive < 0) positive = now;
    else if (balance == 0) positive = -1;
    return new Protection(
        old.world(),
        old.owner(),
        old.account(),
        old.currency(),
        old.bank(),
        old.limitMinor(),
        old.apr(),
        old.enrolledAt(),
        nextDay,
        nextMonth,
        episode,
        positive,
        residual,
        old.revision() + 1,
        closed,
        old.interestAt(),
        old.accruedMinor());
  }

  public static JournalEngine.Decision daily(JournalEngine.ReadView reads, UUID id, long now)
      throws Exception {
    var state = refreshUnprotected(reads, find(reads, id).orElseThrow());
    if (state.closed() || deadline(state) != now) return reject("OVERDRAFT_DEADLINE_STALE");
    boolean accrue = now >= state.nextDay();
    long balance = Settlement.posted(reads, id, state.currency());
    state = accrue(state, balance, now);
    state =
        transition(
            state,
            balance,
            now,
            accrue ? Math.addExact(state.nextDay(), DAY) : state.nextDay(),
            state.nextMonth(),
            state.residual(),
            false);
    var account =
        reads.query(
            "SELECT status,currency FROM account_catalog WHERE account_id=?",
            List.of(id.toString()),
            1);
    if (account.isEmpty() || account.getFirst().get("status").equals("CLOSED")) {
      var closed =
          new Protection(
              state.world(),
              state.owner(),
              state.account(),
              state.currency(),
              state.bank(),
              state.limitMinor(),
              state.apr(),
              state.enrolledAt(),
              Long.MAX_VALUE,
              Long.MAX_VALUE,
              state.episodeAt(),
              state.positiveAt(),
              state.residual(),
              state.revision(),
              true,
              state.interestAt(),
              BigDecimal.ZERO);
      return stateCommit(closed, new Journal.Result(1, "OVERDRAFT_CLOSED", id.toString()));
    }
    if (!account.getFirst().get("currency").equals(state.currency().name()))
      throw new IllegalStateException("Overdraft account currency changed");
    JournalEngine.Commit charges = null;
    BigDecimal residual = state.residual();
    BigDecimal pending = state.accruedMinor();
    if ((accrue || expired(state, balance, now)) && state.protectedAccount()) {
      BigDecimal earned = pending.add(residual, MC);
      long interest = earned.setScale(0, RoundingMode.HALF_UP).longValueExact();
      residual = earned.subtract(BigDecimal.valueOf(interest), MC);
      pending = BigDecimal.ZERO;
      if (interest > 0) charges = charge(reads, state, interest);
    }
    long nextMonth = state.nextMonth();
    if (state.protectedAccount() && now >= nextMonth) {
      long fee = converted(reads, state.currency(), new BigDecimal("5"), 0, now);
      var charged =
          charge(
              charges == null ? reads : FeeSettlement.overlay(reads, charges.records()),
              state,
              fee);
      charges = charges == null ? charged : combine(charges, charged, charged.result());
      nextMonth = Math.addExact(nextMonth, FinancialCalendar.MONTH_MILLIS);
    }
    long finalBalance = balance;
    if (charges != null)
      for (var fact : charges.records())
        if (fact.type().equals("economy:posting")) {
          var p = fact.payload().split("\\|", -1);
          if (p[0].equals(id.toString()) && p[1].equals("ACCOUNT"))
            finalBalance = Math.addExact(finalBalance, Long.parseLong(p[3]));
        }
    state = withAccrued(state, now, pending);
    var next = transition(state, finalBalance, now, state.nextDay(), nextMonth, residual, false);
    if (expired(next, finalBalance, now)) {
      var closure =
          OverdraftCollections.defaultAccount(
              charges == null ? reads : FeeSettlement.overlay(reads, charges.records()), id, now);
      if (!(closure instanceof JournalEngine.Commit accepted)) return closure;
      charges = charges == null ? accepted : combine(charges, accepted, accepted.result());
      next = closed(next);
    }
    var saved = stateCommit(next, new Journal.Result(1, "OVERDRAFT_SERVICED", id.toString()));
    return charges == null ? saved : combine(charges, saved, saved.result());
  }

  private static Protection refreshUnprotected(JournalEngine.ReadView reads, Protection state) {
    if (state.protectedAccount() || state.episodeAt() >= 0 || state.closed()) return state;
    var rows =
        reads.query(
            "SELECT currency FROM account_catalog WHERE account_id=?",
            List.of(state.account().toString()),
            1);
    if (rows.isEmpty()) return state;
    var currency = EconomyCurrency.valueOf(rows.getFirst().get("currency"));
    var binding = BankProviders.find(reads, new AccountId(state.account())).orElse(null);
    String bank = binding == null ? "" : binding.bankKey();
    if (currency == state.currency() && bank.equals(state.bank())) return state;
    if (currency != state.currency()
        && Settlement.posted(reads, state.account(), state.currency()) != 0)
      throw new IllegalStateException("Unsettled overdraft currency changed");
    return new Protection(
        state.world(),
        state.owner(),
        state.account(),
        currency,
        bank,
        0,
        BigDecimal.ZERO,
        -1,
        state.nextDay(),
        Long.MAX_VALUE,
        -1,
        -1,
        BigDecimal.ZERO,
        state.revision(),
        false);
  }

  private static Protection withAccrued(Protection state, long at, BigDecimal earned) {
    return new Protection(
        state.world(),
        state.owner(),
        state.account(),
        state.currency(),
        state.bank(),
        state.limitMinor(),
        state.apr(),
        state.enrolledAt(),
        state.nextDay(),
        state.nextMonth(),
        state.episodeAt(),
        state.positiveAt(),
        state.residual(),
        state.revision(),
        state.closed(),
        at,
        earned);
  }

  /**
   * Earn only for actual elapsed negative-balance time; posting occurs at completed daily cutoffs.
   */
  private static Protection accrue(Protection state, long balance, long now) {
    if (now < state.interestAt())
      throw new IllegalStateException("Overdraft interest clock reversed");
    BigDecimal earned = state.accruedMinor();
    if (balance < 0 && state.protectedAccount() && now > state.interestAt()) {
      BigDecimal amount =
          BigDecimal.valueOf(Math.negateExact(balance))
              .multiply(state.apr(), MC)
              .multiply(BigDecimal.valueOf(now - state.interestAt()), MC)
              .divide(BigDecimal.valueOf(Math.multiplyExact(364, DAY)), MC);
      earned =
          earned.add(amount, MC).setScale(8 - state.currency().fractionDigits(), RoundingMode.DOWN);
    }
    return withAccrued(state, now, earned);
  }

  private static boolean expired(Protection state, long balance, long now) {
    return state.protectedAccount()
        && state.episodeAt() >= 0
        && balance <= 0
        && now >= Math.addExact(state.episodeAt(), 90 * DAY);
  }

  private static Protection closed(Protection state) {
    return new Protection(
        state.world(),
        state.owner(),
        state.account(),
        state.currency(),
        state.bank(),
        state.limitMinor(),
        state.apr(),
        state.enrolledAt(),
        Long.MAX_VALUE,
        Long.MAX_VALUE,
        state.episodeAt(),
        state.positiveAt(),
        state.residual(),
        state.revision(),
        true,
        state.interestAt(),
        BigDecimal.ZERO);
  }

  /** Every recovery/default cutoff is serviced at its exact instant, independently of accrual. */
  static long deadline(Protection state) {
    long due = Math.min(state.nextDay(), state.nextMonth());
    if (state.episodeAt() >= 0 && state.protectedAccount()) {
      long cutoff = Math.addExact(state.episodeAt(), 90 * DAY);
      // Positive recovery pending at cutoff is checked at the one-day recovery deadline.
      if (state.positiveAt() < 0) due = Math.min(due, cutoff);
      else due = Math.min(due, Math.addExact(state.positiveAt(), DAY));
    }
    return due;
  }

  private static JournalEngine.Commit charge(
      JournalEngine.ReadView reads, Protection state, long amount) {
    if (state.bank().isEmpty()) throw new IllegalStateException("OVERDRAFT_BANK_UNAVAILABLE");
    var charged =
        FeeSettlement.charge(
            reads,
            state.world(),
            state.owner(),
            new InstitutionCatalog.InstitutionId(state.bank()),
            new Money(state.currency(), amount),
            state.account());
    if (!(charged instanceof JournalEngine.Commit commit))
      throw new IllegalStateException("Overdraft charge rejected");
    return commit;
  }

  static JournalEngine.Commit stateCommit(Protection state, Journal.Result result) {
    return new JournalEngine.Commit(
        List.of(new Journal.Record(FACT, 1, encode(state))), result, tx -> write(tx, state));
  }

  private static JournalEngine.Commit combine(
      JournalEngine.Commit a, JournalEngine.Commit b, Journal.Result result) {
    var records = new ArrayList<>(a.records());
    records.addAll(b.records());
    return new JournalEngine.Commit(
        records,
        result,
        tx -> {
          a.projections().apply(tx);
          b.projections().apply(tx);
        });
  }

  private static boolean creditBlocked(JournalEngine.ReadView reads, PartyRef owner, long now) {
    var cards = CreditFacilities.owned(reads, owner);
    if (cards.size() > 128) return true;
    return cards.stream()
        .anyMatch(
            c ->
                c.status() == CreditFacilities.Status.DEFAULTED
                        && ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.blocks(
                            reads,
                            ca.emilybelnavis.mcmods.economy.debt.DebtRecords.Kind.CARD,
                            c.id(),
                            now)
                    || c.requirements().stream()
                        .anyMatch(r -> r.unmetMinor() > 0 && r.dueAt() <= now));
  }

  public static long now(JournalEngine.ReadView reads) {
    var row =
        reads.query(
            "SELECT observed_wall_ms,last_effective_ms FROM financial_calendar WHERE singleton=1",
            List.of(),
            1);
    return row.isEmpty()
        ? 0
        : Math.max(
            Long.parseLong(row.getFirst().get("observed_wall_ms")),
            Long.parseLong(row.getFirst().get("last_effective_ms")));
  }

  private static WorldId world(JournalEngine.ReadView reads) {
    return new WorldId(
        UUID.fromString(
            reads
                .query("SELECT world_id FROM foundation_metadata", List.of(), 1)
                .getFirst()
                .get("world_id")));
  }

  private static boolean matches(PartyRef owner, WorldId world, PlayerId player) {
    return owner.world().equals(world)
        && owner.owner() instanceof PartyRef.Player p
        && p.id().equals(player);
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }

  private static Protection read(Map<String, String> r) {
    return new Protection(
        new WorldId(UUID.fromString(r.get("world_id"))),
        new PlayerId(UUID.fromString(r.get("owner_id"))),
        UUID.fromString(r.get("account_id")),
        EconomyCurrency.valueOf(r.get("currency")),
        r.get("bank"),
        Long.parseLong(r.get("limit_minor")),
        new BigDecimal(r.get("apr")),
        Long.parseLong(r.get("enrolled_at")),
        Long.parseLong(r.get("next_day")),
        Long.parseLong(r.get("next_month")),
        Long.parseLong(r.get("episode_at")),
        Long.parseLong(r.get("positive_at")),
        new BigDecimal(r.get("residual")),
        Long.parseLong(r.get("revision")),
        r.get("closed").equals("1"),
        Long.parseLong(r.get("interest_at")),
        new BigDecimal(r.get("accrued_minor")));
  }

  private static String encode(Protection s) {
    return String.join(
        "|",
        s.world().value().toString(),
        s.owner().value().toString(),
        s.account().toString(),
        s.currency().name(),
        s.bank(),
        Long.toString(s.limitMinor()),
        s.apr().toPlainString(),
        Long.toString(s.enrolledAt()),
        Long.toString(s.nextDay()),
        Long.toString(s.nextMonth()),
        Long.toString(s.episodeAt()),
        Long.toString(s.positiveAt()),
        s.residual().toPlainString(),
        Long.toString(s.revision()),
        s.closed() ? "1" : "0",
        Long.toString(s.interestAt()),
        s.accruedMinor().toPlainString());
  }

  private static Protection decode(String payload) {
    var p = payload.split("\\|", -1);
    if (p.length != 17) throw new IllegalArgumentException("Overdraft fact");
    var state =
        new Protection(
            new WorldId(UUID.fromString(p[0])),
            new PlayerId(UUID.fromString(p[1])),
            UUID.fromString(p[2]),
            EconomyCurrency.valueOf(p[3]),
            p[4],
            Long.parseLong(p[5]),
            new BigDecimal(p[6]),
            Long.parseLong(p[7]),
            Long.parseLong(p[8]),
            Long.parseLong(p[9]),
            Long.parseLong(p[10]),
            Long.parseLong(p[11]),
            new BigDecimal(p[12]),
            Long.parseLong(p[13]),
            p[14].equals("1"),
            Long.parseLong(p[15]),
            new BigDecimal(p[16]));
    if (!encode(state).equals(payload))
      throw new IllegalArgumentException("Canonical overdraft fact");
    return state;
  }

  private static void write(FoundationStore.Transaction tx, Protection s) {
    tx.execute(
        "INSERT INTO overdraft_accounts VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(account_id) DO UPDATE SET currency=excluded.currency,bank=excluded.bank,limit_minor=excluded.limit_minor,apr=excluded.apr,enrolled_at=excluded.enrolled_at,next_day=excluded.next_day,next_month=excluded.next_month,episode_at=excluded.episode_at,positive_at=excluded.positive_at,residual=excluded.residual,revision=excluded.revision,closed=excluded.closed,interest_at=excluded.interest_at,accrued_minor=excluded.accrued_minor",
        List.of(
            s.account().toString(),
            s.world().value().toString(),
            s.owner().value().toString(),
            s.currency().name(),
            s.bank(),
            Long.toString(s.limitMinor()),
            s.apr().toPlainString(),
            Long.toString(s.enrolledAt()),
            Long.toString(s.nextDay()),
            Long.toString(s.nextMonth()),
            Long.toString(s.episodeAt()),
            Long.toString(s.positiveAt()),
            s.residual().toPlainString(),
            Long.toString(s.revision()),
            s.closed() ? "1" : "0",
            Long.toString(s.interestAt()),
            s.accruedMinor().toPlainString()));
  }
}
