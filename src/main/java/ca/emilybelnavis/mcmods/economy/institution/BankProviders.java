package ca.emilybelnavis.mcmods.economy.institution;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Journaled provider attribution. Call only from the financial worker with server branch admission.
 */
public final class BankProviders {
  public static final String UNASSIGNED_PROVIDER = "economy:unassigned-provider";
  public static final Journal.Kind MIGRATE = kind("migrate"),
      PRIMARY = kind("primary"),
      SECONDARY = kind("secondary"),
      ASSIGN = kind("assign"),
      SWITCH_SOURCE = kind("switch-source"),
      SWITCH_TARGET = kind("switch-target"),
      CLOSE = kind("close");
  private static final String FACT = "economy:bank-provider";
  public static final long SWITCH_LIFETIME = 7L * 1_200_000;

  private BankProviders() {}

  private static Journal.Kind kind(String action) {
    return new Journal.Kind("economy:providers", "economy:" + action);
  }

  public record Binding(
      AccountId account, String bankKey, boolean legacy, long revision, long debitEpoch) {}

  /** Eligibility must be computed by a trusted worker service for this exact attempt. */
  public record TierSnapshot(boolean available, boolean eligible, long revision) {
    public TierSnapshot {
      if (revision < 0) throw new IllegalArgumentException("Tier revision");
    }
  }

  @FunctionalInterface
  public interface TierEligibility {
    TierSnapshot current(JournalEngine.ReadView reads, PartyRef owner);
  }

  public static final TierEligibility NO_TIER2 =
      (reads, owner) -> new TierSnapshot(false, false, 0);

  public record Request(
      BranchAdmission.Proof proof,
      long expectedTierRevision,
      Map<String, String> settlementFields) {
    public Request(BranchAdmission.Proof proof, long expectedTierRevision) {
      this(proof, expectedTierRevision, Map.of());
    }

    public Request {
      Objects.requireNonNull(proof);
      settlementFields = Map.copyOf(settlementFields);
      if (expectedTierRevision < 0) throw new IllegalArgumentException("Tier revision");
      if (!settlementFields.isEmpty() && proof.context().action() != BranchAdmission.Action.CLOSE)
        throw new IllegalArgumentException("Settlement fields only belong to closure");
    }

    public Journal.CanonicalRequest request() {
      var c = proof.context();
      var f = new TreeMap<String, String>();
      f.put("lease", proof.id().toString());
      f.put("world", c.world().value().toString());
      f.put("session", c.session().toString());
      f.put("actor", c.actor().value().toString());
      f.put("economy:owner", Journal.actorKey(c.actor()));
      f.put("action", c.action().name());
      f.put("bank", c.bank().key());
      f.put("target", c.target() == null ? "" : c.target().key());
      f.put("account", c.account() == null ? "" : c.account().value().toString());
      f.put("account_revision", Long.toString(c.accountRevision()));
      f.put("provider_revision", Long.toString(c.providerRevision()));
      f.put("denomination_revision", Long.toString(c.denominationRevision()));
      f.put("tier_revision", Long.toString(expectedTierRevision));
      settlementFields.forEach(
          (key, value) -> {
            if (f.putIfAbsent(key, value) != null)
              throw new IllegalArgumentException("Reserved branch field");
          });
      return new Journal.CanonicalRequest(1, f);
    }
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog
        .with(
            new RecordCatalog.Key(FACT, 1),
            payload -> {
              var p = payload.split("\\|", -1);
              if (p.length != 6
                  || !Set.of("legacy", "opened", "assigned", "switch-request", "closed")
                      .contains(p[0])) throw new IllegalArgumentException("Provider fact");
              uuid(p[1]);
              key(p[2]);
              positive(p[3]);
              if (!Set.of("0", "1").contains(p[4]) || Long.parseLong(p[5]) < 0)
                throw new IllegalArgumentException("Provider state");
            })
        .with(
            new RecordCatalog.Key("economy:provider-account", 1),
            payload -> {
              var p = payload.split("\\|", -1);
              if (p.length != 7) throw new IllegalArgumentException("Provider identity");
              uuid(p[0]);
              uuid(p[1]);
              uuid(p[2]);
              EconomyCurrency.fromCode(p[3]);
              if (!p[4].equals("OPEN") || !p[5].equals("1") || !Set.of("0", "1").contains(p[6]))
                throw new IllegalArgumentException("Provider identity state");
            })
        .with(
            new RecordCatalog.Key("economy:provider-switch", 1),
            payload -> {
              var p = payload.split("\\|", -1);
              if (p.length != 7) throw new IllegalArgumentException("Switch fact");
              uuid(p[0]);
              key(p[1]);
              key(p[2]);
              positive(p[3]);
              positive(p[4]);
              if (Long.parseLong(p[5]) < 0 || Long.parseLong(p[6]) < 0)
                throw new IllegalArgumentException("Switch bounds");
            })
        .with(new RecordCatalog.Key("economy:provider-migration-cursor", 1), BankProviders::uuid);
  }

  private static void uuid(String text) {
    if (!UUID.fromString(text).toString().equals(text))
      throw new IllegalArgumentException("Canonical UUID");
  }

  private static void positive(String text) {
    long value = Long.parseLong(text);
    if (value < 1 || !Long.toString(value).equals(text))
      throw new IllegalArgumentException("Positive revision");
  }

  private static void key(String text) {
    if (!text.equals(UNASSIGNED_PROVIDER)
        && InstitutionCatalog.find(new InstitutionCatalog.InstitutionId(text)).isEmpty())
      throw new IllegalArgumentException("Provider key");
  }

  public static Optional<Binding> find(JournalEngine.ReadView reads, AccountId account) {
    return reads
        .query(
            "SELECT * FROM bank_account_providers WHERE account_id=?",
            List.of(account.value().toString()),
            1)
        .stream()
        .findFirst()
        .map(
            r ->
                new Binding(
                    account,
                    r.get("bank_key"),
                    r.get("legacy").equals("1"),
                    Long.parseLong(r.get("revision")),
                    Long.parseLong(r.get("debit_epoch"))));
  }

  public static Optional<Binding> find(
      JournalEngine.ReadView reads, PartyRef owner, AccountId account) {
    return AccountCatalog.find(reads, owner, account).flatMap(a -> find(reads, account));
  }

  /**
   * One bounded journal transaction. Reinvoke with a new operation ID until result
   * MIGRATION_COMPLETE.
   */
  public static JournalEngine.Registration migrateStep() {
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!intent.request().fields().isEmpty())
            throw new IllegalArgumentException("Migration request");
          var cursor =
              reads
                  .query(
                      "SELECT cursor FROM bank_provider_migration WHERE singleton=1", List.of(), 1)
                  .getFirst()
                  .get("cursor");
          var rows =
              reads.query(
                  "SELECT account_id FROM account_catalog WHERE purpose='BANK' AND account_id>? AND NOT EXISTS (SELECT 1 FROM bank_account_providers p WHERE p.account_id=account_catalog.account_id) ORDER BY account_id LIMIT 16",
                  List.of(cursor),
                  16);
          if (rows.isEmpty())
            return new JournalEngine.Reject(new Journal.Result(1, "MIGRATION_COMPLETE", ""));
          var records = new ArrayList<Journal.Record>();
          rows.forEach(
              r ->
                  records.add(
                      fact(
                          "legacy",
                          new AccountId(UUID.fromString(r.get("account_id"))),
                          UNASSIGNED_PROVIDER,
                          1,
                          true,
                          0)));
          records.add(
              new Journal.Record(
                  "economy:provider-migration-cursor", 1, rows.getLast().get("account_id")));
          return new JournalEngine.Commit(
              records,
              new Journal.Result(1, "MIGRATION_PAGE", Integer.toString(rows.size())),
              tx -> {
                for (var r : rows)
                  tx.execute(
                      "INSERT OR IGNORE INTO bank_account_providers VALUES(?,?,1,1,0)",
                      List.of(r.get("account_id"), UNASSIGNED_PROVIDER));
                tx.execute(
                    "UPDATE bank_provider_migration SET cursor=? WHERE singleton=1",
                    List.of(rows.getLast().get("account_id")));
              });
        });
  }

  public static JournalEngine.Registration lifecycle(
      PartyRef owner, Request request, TierEligibility tiers, LongSupplier financialTime) {
    Objects.requireNonNull(tiers);
    Objects.requireNonNull(financialTime);
    var c = request.proof().context();
    if (!(owner.owner() instanceof PartyRef.Player player)
        || !player.id().equals(c.actor())
        || !owner.world().equals(c.world()))
      throw new IllegalArgumentException("Branch owner mismatch");
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!request.request().equals(intent.request())
              || !intent.kind().equals(actionKind(c.action())))
            throw new IllegalArgumentException("Branch request mismatch");
          if (!request.proof().valid(intent, reads)) return reject("BRANCH_UNAVAILABLE");
          if (!reads
              .query(
                  "SELECT lease_id FROM bank_provider_leases WHERE lease_id=?",
                  List.of(request.proof().id().toString()),
                  1)
              .isEmpty()) return reject("LEASE_USED");
          var denomination = RebaseSettlement.denomination(reads).orElse(null);
          if (denomination == null || denomination.revision() != c.denominationRevision())
            return reject("DENOMINATION_STALE");
          var bank = InstitutionCatalog.find(c.bank()).orElse(null);
          if (bank == null || !bank.customerBank() || !enabled(reads, owner.world(), bank))
            return reject("BANK_UNAVAILABLE");
          var account =
              c.account() == null
                  ? null
                  : AccountCatalog.find(reads, owner, c.account()).orElse(null);
          if (c.action() == BranchAdmission.Action.PRIMARY
              || c.action() == BranchAdmission.Action.SECONDARY) {
            boolean primary = c.action() == BranchAdmission.Action.PRIMARY;
            var existing = AccountCatalog.primary(reads, owner).orElse(null);
            if (primary && existing != null) return reject("PRIMARY_EXISTS");
            if (!primary && (existing == null || existing.revision() != c.accountRevision()))
              return reject("PRIMARY_STALE");
            if (primary && (!bank.tier1() || bank.baseCurrency() != denomination.currency()))
              return reject("PRIMARY_BANK_CURRENCY");
            var denied = tier(reads, owner, request, tiers, bank);
            if (denied != null) return denied;
            var banks =
                reads.query(
                    "SELECT currency FROM account_catalog WHERE owner_id=? AND purpose='BANK' AND status='OPEN'",
                    List.of(player.id().value().toString()),
                    5);
            if (banks.size() >= AccountIdentity.MAX_OPEN_BANK_ACCOUNTS)
              return reject("BANK_CAP_REACHED");
            if (banks.stream().anyMatch(r -> r.get("currency").equals(bank.baseCurrency().code())))
              return reject("BANK_CURRENCY_EXISTS");
            if (!FxService.availableCurrencies(reads).contains(bank.baseCurrency()))
              return reject("CURRENCY_UNAVAILABLE");
            var id = new AccountId(UUID.randomUUID());
            var identity =
                new Journal.Record(
                    "economy:provider-account",
                    1,
                    String.join(
                        "|",
                        owner.world().value().toString(),
                        id.value().toString(),
                        player.id().value().toString(),
                        bank.baseCurrency().code(),
                        "OPEN",
                        "1",
                        primary ? "1" : "0"));
            return commit(
                request,
                intent,
                "opened",
                id,
                List.of(fact("opened", id, bank.id().key(), 1, false, 0), identity),
                tx -> {
                  tx.execute(
                      "INSERT INTO account_catalog(account_id,owner_id,purpose,currency,status,revision,is_primary) VALUES(?,?,'BANK',?,'OPEN',1,?)",
                      List.of(
                          id.value().toString(),
                          player.id().value().toString(),
                          bank.baseCurrency().code(),
                          primary ? "1" : "0"));
                  tx.execute(
                      "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
                      List.of(id.value().toString(), bank.id().key()));
                });
          }
          if (account == null
              || account.purpose() != AccountIdentity.Purpose.BANK
              || account.status() != AccountIdentity.Status.OPEN)
            return reject("ACCOUNT_UNAVAILABLE");
          if (account.revision() != c.accountRevision()) return reject("ACCOUNT_STALE");
          var binding = find(reads, account.id()).orElse(null);
          if (binding == null || binding.revision() != c.providerRevision())
            return reject("PROVIDER_STALE");
          if (binding.revision() == Long.MAX_VALUE || binding.debitEpoch() == Long.MAX_VALUE)
            return reject("REVISION_EXHAUSTED");
          if (c.action() == BranchAdmission.Action.CLOSE)
            return reject("CLOSURE_COMPOSITION_REQUIRED");
          if (c.action() == BranchAdmission.Action.ASSIGN) {
            if (!binding.bankKey().equals(UNASSIGNED_PROVIDER) || !binding.legacy())
              return reject("PROVIDER_ALREADY_ASSIGNED");
            if (bank.baseCurrency() != account.currency() || account.primary() && !bank.tier1())
              return reject("BANK_CURRENCY");
            var denied = tier(reads, owner, request, tiers, bank);
            if (denied != null) return denied;
            return replace(reads, request, intent, binding, bank.id().key());
          }
          if (binding.bankKey().equals(UNASSIGNED_PROVIDER))
            return reject("PROVIDER_ASSIGNMENT_REQUIRED");
          if (c.action() == BranchAdmission.Action.SWITCH_SOURCE
              || c.action() == BranchAdmission.Action.SWITCH_TARGET) {
            var overdraft =
                ca.emilybelnavis.mcmods.economy.overdraft.Overdrafts.find(
                        reads, account.id().value())
                    .orElse(null);
            if (Settlement.posted(reads, account.id().value(), account.currency()) < 0
                || overdraft != null && !overdraft.closed() && overdraft.protectedAccount())
              return reject("OVERDRAFT_TRANSITION_BLOCKED");
          }
          if (c.action() == BranchAdmission.Action.SWITCH_SOURCE) {
            if (!binding.bankKey().equals(bank.id().key())
                || c.target() == null
                || c.target().equals(c.bank())) return reject("SOURCE_BANK");
            var target = InstitutionCatalog.find(c.target()).orElse(null);
            if (target == null
                || !target.customerBank()
                || !enabled(reads, owner.world(), target)
                || target.baseCurrency() != account.currency()) return reject("TARGET_BANK");
            long expiry = Math.addExact(financialTime.getAsLong(), SWITCH_LIFETIME);
            var authorization =
                new Journal.Record(
                    "economy:provider-switch",
                    1,
                    String.join(
                        "|",
                        account.id().value().toString(),
                        binding.bankKey(),
                        target.id().key(),
                        Long.toString(account.revision()),
                        Long.toString(binding.revision() + 1),
                        Long.toString(denomination.revision()),
                        Long.toString(expiry)));
            return commit(
                request,
                intent,
                "switch-request",
                account.id(),
                List.of(
                    fact(
                        "switch-request",
                        account.id(),
                        binding.bankKey(),
                        binding.revision() + 1,
                        binding.legacy(),
                        binding.debitEpoch()),
                    authorization),
                tx -> {
                  tx.execute(
                      "UPDATE bank_account_providers SET revision=revision+1 WHERE account_id=?",
                      List.of(account.id().value().toString()));
                  tx.execute(
                      "DELETE FROM bank_provider_switches WHERE account_id=?",
                      List.of(account.id().value().toString()));
                  tx.execute(
                      "INSERT INTO bank_provider_switches VALUES(?,?,?,?,?,?,?)",
                      List.of(
                          account.id().value().toString(),
                          target.id().key(),
                          binding.bankKey(),
                          Long.toString(account.revision()),
                          Long.toString(binding.revision() + 1),
                          Long.toString(denomination.revision()),
                          Long.toString(expiry)));
                });
          }
          if (c.action() == BranchAdmission.Action.SWITCH_TARGET) {
            var rows =
                reads.query(
                    "SELECT * FROM bank_provider_switches WHERE account_id=?",
                    List.of(account.id().value().toString()),
                    1);
            if (rows.isEmpty()) return reject("SWITCH_UNAVAILABLE");
            var s = rows.getFirst();
            if (!s.get("target_key").equals(bank.id().key())
                || !s.get("source_key").equals(binding.bankKey())
                || Long.parseLong(s.get("expires_at")) <= financialTime.getAsLong()
                || Long.parseLong(s.get("account_revision")) != account.revision()
                || Long.parseLong(s.get("provider_revision")) != binding.revision()
                || Long.parseLong(s.get("denomination_revision")) != denomination.revision())
              return reject("SWITCH_STALE");
            if (bank.baseCurrency() != account.currency() || account.primary() && !bank.tier1())
              return reject("BANK_CURRENCY");
            var denied = tier(reads, owner, request, tiers, bank);
            if (denied != null) return denied;
            return replace(reads, request, intent, binding, bank.id().key());
          }
          return reject("ACTION_UNAVAILABLE");
        });
  }

  /**
   * Compose the existing protected/funded close transition; its handler must accept this canonical
   * request.
   */
  public static JournalEngine.Registration closure(
      PartyRef owner, Request request, JournalEngine.Registration protectedClose) {
    Objects.requireNonNull(protectedClose);
    var c = request.proof().context();
    if (c.action() != BranchAdmission.Action.CLOSE
        || !(owner.owner() instanceof PartyRef.Player player)
        || !player.id().equals(c.actor())
        || !owner.world().equals(c.world()))
      throw new IllegalArgumentException("Closure owner/action");
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!request.request().equals(intent.request()) || !intent.kind().equals(CLOSE))
            throw new IllegalArgumentException("Closure canonical request");
          if (!request.proof().valid(intent, reads)) return reject("BRANCH_UNAVAILABLE");
          if (!reads
              .query(
                  "SELECT lease_id FROM bank_provider_leases WHERE lease_id=?",
                  List.of(request.proof().id().toString()),
                  1)
              .isEmpty()) return reject("LEASE_USED");
          var account = AccountCatalog.find(reads, owner, c.account()).orElse(null);
          var binding = find(reads, owner, c.account()).orElse(null);
          var denomination = RebaseSettlement.denomination(reads).orElse(null);
          var bank = InstitutionCatalog.find(c.bank()).orElse(null);
          if (bank == null || !enabled(reads, owner.world(), bank) || !bank.customerBank())
            return reject("BANK_UNAVAILABLE");
          if (account == null
              || account.status() != AccountIdentity.Status.OPEN
              || account.revision() != c.accountRevision()) return reject("ACCOUNT_STALE");
          if (binding == null
              || binding.revision() != c.providerRevision()
              || !binding.bankKey().equals(c.bank().key())) return reject("PROVIDER_STALE");
          if (denomination == null || denomination.revision() != c.denominationRevision())
            return reject("DENOMINATION_STALE");
          if (binding.debitEpoch() == Long.MAX_VALUE || binding.revision() == Long.MAX_VALUE)
            return reject("REVISION_EXHAUSTED");
          var decision = protectedClose.handler().prepare(intent, reads);
          if (!(decision instanceof JournalEngine.Commit close)) return decision;
          var revoke =
              ca.emilybelnavis.mcmods.economy.card.IssuedCards.invalidateAccount(
                      reads, owner.world(), account.id())
                  .orElse(null);
          var records = new ArrayList<>(close.records());
          if (revoke != null) records.addAll(revoke.records());
          records.add(
              fact(
                  "closed",
                  account.id(),
                  binding.bankKey(),
                  binding.revision() + 1,
                  binding.legacy(),
                  binding.debitEpoch() + 1));
          return new JournalEngine.Commit(
              records,
              close.result(),
              tx -> {
                tx.execute(
                    "INSERT INTO bank_provider_leases VALUES(?,?,?)",
                    List.of(
                        request.proof().id().toString(),
                        intent.operationId().value().toString(),
                        intent.canonical()));
                close.projections().apply(tx);
                if (revoke != null) revoke.projections().apply(tx);
                tx.execute(
                    "UPDATE bank_account_providers SET revision=revision+1,debit_epoch=debit_epoch+1 WHERE account_id=?",
                    List.of(account.id().value().toString()));
                tx.execute(
                    "DELETE FROM bank_provider_switches WHERE account_id=?",
                    List.of(account.id().value().toString()));
              });
        });
  }

  private static boolean enabled(
      JournalEngine.ReadView reads, WorldId world, InstitutionCatalog.Entry bank) {
    var rows =
        reads.query(
            "SELECT enabled FROM institution_parties WHERE world_id=? AND institution_key=?",
            List.of(world.value().toString(), bank.id().key()),
            1);
    return bank.enabled() && (rows.isEmpty() || rows.getFirst().get("enabled").equals("1"));
  }

  private static Journal.Kind actionKind(BranchAdmission.Action action) {
    return switch (action) {
      case PRIMARY -> PRIMARY;
      case SECONDARY -> SECONDARY;
      case ASSIGN -> ASSIGN;
      case SWITCH_SOURCE -> SWITCH_SOURCE;
      case SWITCH_TARGET -> SWITCH_TARGET;
      case CLOSE -> CLOSE;
      case CARD_ISSUE, CARD_REPLACE, CREDIT_ISSUE, CREDIT_INCREASE, CREDIT_REPLACE, LOAN_APPLY ->
          throw new IllegalArgumentException("Use issued-card service");
    };
  }

  private static JournalEngine.Decision tier(
      JournalEngine.ReadView reads,
      PartyRef owner,
      Request request,
      TierEligibility tiers,
      InstitutionCatalog.Entry bank) {
    if (bank.tier1()) return null;
    var state = Objects.requireNonNull(tiers.current(reads, owner));
    return state.available()
            && state.eligible()
            && state.revision() == request.expectedTierRevision()
        ? null
        : reject("TIER2_UNAVAILABLE");
  }

  private static JournalEngine.Commit replace(
      JournalEngine.ReadView reads,
      Request request,
      Journal.Intent intent,
      Binding old,
      String bank) {
    var revoke =
        ca.emilybelnavis.mcmods.economy.card.IssuedCards.invalidateAccount(
                reads, request.proof().context().world(), old.account())
            .orElse(null);
    var base =
        commit(
            request,
            intent,
            "assigned",
            old.account(),
            List.of(
                fact(
                    "assigned",
                    old.account(),
                    bank,
                    old.revision() + 1,
                    old.legacy(),
                    old.debitEpoch() + 1)),
            tx -> {
              tx.execute(
                  "UPDATE bank_account_providers SET bank_key=?,revision=revision+1,debit_epoch=debit_epoch+1 WHERE account_id=?",
                  List.of(bank, old.account().value().toString()));
              tx.execute(
                  "DELETE FROM bank_provider_switches WHERE account_id=?",
                  List.of(old.account().value().toString()));
            });
    if (revoke == null) return base;
    var records = new ArrayList<>(base.records());
    records.addAll(revoke.records());
    return new JournalEngine.Commit(
        records,
        base.result(),
        tx -> {
          base.projections().apply(tx);
          revoke.projections().apply(tx);
        });
  }

  private static Journal.Record fact(
      String action, AccountId id, String bank, long revision, boolean legacy, long epoch) {
    return new Journal.Record(
        FACT,
        1,
        String.join(
            "|",
            action,
            id.value().toString(),
            bank,
            Long.toString(revision),
            legacy ? "1" : "0",
            Long.toString(epoch)));
  }

  private static JournalEngine.Commit commit(
      Request request,
      Journal.Intent intent,
      String action,
      AccountId id,
      List<Journal.Record> records,
      JournalEngine.Projection projection) {
    return new JournalEngine.Commit(
        records,
        new Journal.Result(
            1,
            "PROVIDER_" + action.toUpperCase(Locale.ROOT).replace('-', '_'),
            id.value().toString()),
        tx -> {
          tx.execute(
              "INSERT INTO bank_provider_leases VALUES(?,?,?)",
              List.of(
                  request.proof().id().toString(),
                  intent.operationId().value().toString(),
                  intent.canonical()));
          projection.apply(tx);
        });
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }
}
