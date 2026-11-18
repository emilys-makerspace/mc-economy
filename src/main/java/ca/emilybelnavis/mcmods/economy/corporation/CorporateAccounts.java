package ca.emilybelnavis.mcmods.economy.corporation;

import ca.emilybelnavis.mcmods.economy.api.AccountIdentity;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity.Purpose;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity.Status;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.OrganizationId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.BankProviders;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.InstitutionId;
import ca.emilybelnavis.mcmods.economy.journal.Holds;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.journal.Settlement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Worker-side lifecycle for corporate BANK accounts with trusted branch admission. */
public final class CorporateAccounts {
  public static final String FACT = "economy:corporate-account";
  private static final String CLAIM_DOMAIN = "economy:corporate-bank-account";

  private CorporateAccounts() {}

  public record Open(
      UUID company,
      long companyRevision,
      long membershipRevision,
      InstitutionId bank,
      EconomyCurrency currency) {
    public Open {
      requireAuthority(company, companyRevision, membershipRevision, bank);
      Objects.requireNonNull(currency);
    }

    public Map<String, String> fields() {
      var fields = authorityFields(company, companyRevision, membershipRevision, bank);
      fields.put("currency", currency.code());
      return Map.copyOf(fields);
    }
  }

  public record Close(
      UUID company,
      long companyRevision,
      long membershipRevision,
      InstitutionId bank,
      AccountId source,
      long accountRevision,
      long providerRevision,
      AccountId destination,
      Long destinationRevision) {
    public Close {
      requireAuthority(company, companyRevision, membershipRevision, bank);
      Objects.requireNonNull(source);
      if (accountRevision < 1
          || providerRevision < 1
          || (destination == null) != (destinationRevision == null)
          || destination != null && (destination.equals(source) || destinationRevision < 1))
        throw new IllegalArgumentException("Corporate closure target or revision");
    }

    public Map<String, String> fields() {
      var fields = authorityFields(company, companyRevision, membershipRevision, bank);
      fields.put("source", source.value().toString());
      fields.put("account_revision", Long.toString(accountRevision));
      fields.put("provider_revision", Long.toString(providerRevision));
      if (destination != null) {
        fields.put("destination", destination.value().toString());
        fields.put("destination_revision", destinationRevision.toString());
      }
      return Map.copyOf(fields);
    }
  }

  /** Prepares the reviewed exact debit and checks current destination and payment authority. */
  @FunctionalInterface
  public interface FundedSettlement {
    JournalEngine.Decision prepare(
        Journal.Intent intent,
        JournalEngine.ReadView reads,
        AccountIdentity source,
        AccountId destination,
        long destinationRevision,
        long fullBalance)
        throws Exception;
  }

  public static Optional<AccountIdentity> find(
      JournalEngine.ReadView reads, WorldId world, UUID company, AccountId account) {
    return reads
        .query(
            "SELECT * FROM account_catalog WHERE account_id=? AND owner_id=? AND purpose='BANK'",
            List.of(account.value().toString(), ownerKey(company)),
            1)
        .stream()
        .findFirst()
        .map(row -> decode(world, company, row));
  }

  public static List<AccountIdentity> list(
      JournalEngine.ReadView reads, WorldId world, UUID company) {
    var rows =
        reads.query(
            """
            SELECT * FROM account_catalog WHERE owner_id=? AND purpose='BANK' AND status='OPEN'
            ORDER BY account_id LIMIT 5
            """,
            List.of(ownerKey(company)),
            5);
    if (rows.size() > AccountIdentity.MAX_OPEN_BANK_ACCOUNTS)
      throw new IllegalStateException("Corporate account cap exceeded");
    return rows.stream().map(row -> decode(world, company, row)).toList();
  }

  public static JournalEngine.Decision prepare(
      Journal.Intent intent, JournalEngine.ReadView reads, Open command) {
    if (!(intent.actor() instanceof PlayerId actor)
        || !bound(intent, command.fields())
        || !permits(
            reads,
            actor,
            command.company(),
            command.companyRevision(),
            command.membershipRevision(),
            Managers.Use.OPEN,
            null)) return reject("CORPORATION_UNAVAILABLE");

    var world = world(intent);
    var bank = ordinaryBank(reads, world, command.bank()).orElse(null);
    if (bank == null) return reject("BANK_UNAVAILABLE");
    if (bank.baseCurrency() != command.currency()) return reject("BANK_NATIVE_CURRENCY");
    if (!FxService.availableCurrencies(reads).contains(command.currency()))
      return reject("CURRENCY_UNAVAILABLE");
    var accounts = list(reads, world, command.company());
    if (accounts.size() >= AccountIdentity.MAX_OPEN_BANK_ACCOUNTS)
      return reject("BANK_CAP_REACHED");
    if (accounts.stream().anyMatch(account -> account.currency() == command.currency()))
      return reject("BANK_CURRENCY_EXISTS");

    var account = new AccountId(UUID.randomUUID());
    String id = account.value().toString();
    return new JournalEngine.Commit(
        List.of(
            fact(
                Status.OPEN,
                world,
                command.company(),
                actor,
                account,
                command.bank(),
                command.currency(),
                1,
                1,
                0,
                command.companyRevision(),
                command.membershipRevision())),
        new Journal.Result(1, "CORPORATE_BANK_OPENED", id),
        tx -> {
          tx.execute(
              """
              INSERT INTO account_catalog(account_id,owner_id,purpose,currency,status,revision,is_primary)
              VALUES(?,?,'BANK',?,'OPEN',1,0)
              """,
              List.of(id, ownerKey(command.company()), command.currency().code()));
          tx.execute(
              "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
              List.of(id, command.bank().key()));
          tx.execute(
              "INSERT INTO corporation_claims(company,domain,claim_id,kind) VALUES(?,?,?,'ACCOUNT')",
              List.of(command.company().toString(), CLAIM_DOMAIN, id));
        });
  }

  public static JournalEngine.Decision prepare(
      Journal.Intent intent, JournalEngine.ReadView reads, Close command, FundedSettlement settle)
      throws Exception {
    if (!(intent.actor() instanceof PlayerId actor)
        || !bound(intent, command.fields())
        || !permits(
            reads,
            actor,
            command.company(),
            command.companyRevision(),
            command.membershipRevision(),
            Managers.Use.CLOSE,
            command.source().value())) return reject("CORPORATION_UNAVAILABLE");

    var world = world(intent);
    var bank = ordinaryBank(reads, world, command.bank()).orElse(null);
    if (bank == null) return reject("BANK_UNAVAILABLE");
    var source = find(reads, world, command.company(), command.source()).orElse(null);
    if (source == null || source.status() != Status.OPEN) return reject("ACCOUNT_UNAVAILABLE");
    if (source.revision() != command.accountRevision()) return reject("ACCOUNT_STALE");
    var binding = BankProviders.find(reads, source.id()).orElse(null);
    if (binding == null
        || binding.legacy()
        || binding.revision() != command.providerRevision()
        || !binding.bankKey().equals(command.bank().key())) return reject("PROVIDER_STALE");
    if (bank.baseCurrency() != source.currency()) return reject("BANK_NATIVE_CURRENCY");
    if (source.revision() == Long.MAX_VALUE
        || binding.revision() == Long.MAX_VALUE
        || binding.debitEpoch() == Long.MAX_VALUE) return reject("REVISION_EXHAUSTED");

    var id = source.id().value();
    var balance = Settlement.posted(reads, id, source.currency());
    if (Holds.hasActiveClaim(reads, id)) return reject("ACTIVE_HOLD");
    if (balance < 0 || blocked(reads, command.company(), source))
      return reject("UNRESOLVED_OBLIGATION");

    List<Journal.Record> records = new ArrayList<>();
    JournalEngine.Commit settlement = null;
    if (balance > 0) {
      if (command.destination() == null || settle == null) return reject("DESTINATION_UNAVAILABLE");
      var decision =
          settle.prepare(
              intent, reads, source, command.destination(), command.destinationRevision(), balance);
      if (!(decision instanceof JournalEngine.Commit posted)) return decision;
      settlement = posted;
      records.addAll(posted.records());
    } else if (command.destination() != null) {
      return reject("EMPTY_CLOSURE_DESTINATION");
    }

    String key = id.toString();
    var posted = settlement;
    records.add(
        fact(
            Status.CLOSED,
            world,
            command.company(),
            actor,
            source.id(),
            command.bank(),
            source.currency(),
            source.revision() + 1,
            binding.revision() + 1,
            binding.debitEpoch() + 1,
            command.companyRevision(),
            command.membershipRevision()));
    return new JournalEngine.Commit(
        records,
        new Journal.Result(
            1,
            "CORPORATE_BANK_CLOSED",
            posted == null ? key : key + "|" + posted.result().payload()),
        tx -> {
          if (posted != null) posted.projections().apply(tx);
          if (!tx.query("SELECT 1 FROM ledger_balances WHERE account_id=? LIMIT 1", List.of(key), 1)
                  .isEmpty()
              || Holds.hasActiveClaim(tx::query, id))
            throw new IllegalStateException("Corporate closure did not clear the source exposure");
          if (tx.execute(
                      """
                  UPDATE account_catalog SET status='CLOSED',revision=revision+1
                  WHERE account_id=? AND owner_id=? AND status='OPEN' AND revision=?
                  """,
                      List.of(key, ownerKey(command.company()), Long.toString(source.revision())))
                  != 1
              || tx.execute(
                      """
                  UPDATE bank_account_providers SET revision=revision+1,debit_epoch=debit_epoch+1
                  WHERE account_id=? AND bank_key=? AND revision=?
                  """,
                      List.of(key, command.bank().key(), Long.toString(binding.revision())))
                  != 1) throw new IllegalStateException("Corporate closure target changed");
          tx.execute(
              "DELETE FROM corporation_claims WHERE company=? AND domain=? AND claim_id=? AND kind='ACCOUNT'",
              List.of(command.company().toString(), CLAIM_DOMAIN, key));
        });
  }

  private static boolean blocked(
      JournalEngine.ReadView reads, UUID company, AccountIdentity source) {
    String account = source.id().value().toString();
    return !reads
            .query(
                """
            SELECT 1 FROM corporation_claims WHERE company=?
            AND ((claim_id=? AND (domain<>? OR kind<>'ACCOUNT'))
                 OR kind IN ('OBLIGATION','UNCERTAIN','UNAVAILABLE')) LIMIT 1
            """,
                List.of(company.toString(), account, CLAIM_DOMAIN),
                1)
            .isEmpty()
        || !reads
            .query(
                "SELECT 1 FROM ledger_balances WHERE account_id=? AND currency<>? LIMIT 1",
                List.of(account, source.currency().code()),
                1)
            .isEmpty();
  }

  private static boolean permits(
      JournalEngine.ReadView reads,
      PlayerId actor,
      UUID company,
      long companyRevision,
      long membershipRevision,
      Managers.Use use,
      UUID account) {
    if (membershipRevision != 0)
      return Managers.permits(
          reads,
          actor,
          new Managers.Requirement(company, companyRevision, membershipRevision, use, account));
    var found = Corporations.find(reads, actor, company).orElse(null);
    return found != null
        && found.revision() == companyRevision
        && found.state() != Corporations.State.DISSOLVED
        && (use != Managers.Use.OPEN || found.state() == Corporations.State.ACTIVE);
  }

  static Optional<InstitutionCatalog.Entry> ordinaryBank(
      JournalEngine.ReadView reads, WorldId world, InstitutionId id) {
    return InstitutionCatalog.find(id)
        .filter(InstitutionCatalog.Entry::tier1)
        .filter(InstitutionCatalog.Entry::enabled)
        .filter(
            bank ->
                !reads
                    .query(
                        "SELECT 1 FROM institution_parties WHERE world_id=? AND institution_key=? AND enabled=1",
                        List.of(world.value().toString(), bank.id().key()),
                        1)
                    .isEmpty());
  }

  private static AccountIdentity decode(WorldId world, UUID company, Map<String, String> row) {
    return new AccountIdentity(
        new AccountId(canonicalUuid(row.get("account_id"))),
        new PartyRef(world, new PartyRef.Organization(new OrganizationId(company))),
        Purpose.BANK,
        EconomyCurrency.fromCode(row.get("currency")),
        Status.valueOf(row.get("status")),
        Long.parseLong(row.get("revision")),
        "1".equals(row.get("is_primary")));
  }

  private static String ownerKey(UUID company) {
    return "organization:" + company;
  }

  private static WorldId world(Journal.Intent intent) {
    return new WorldId(canonicalUuid(intent.request().fields().get("world")));
  }

  private static boolean bound(Journal.Intent intent, Map<String, String> fields) {
    return intent.request().version() == 1
        && intent.request().fields().entrySet().containsAll(fields.entrySet());
  }

  private static void requireAuthority(
      UUID company, long companyRevision, long membershipRevision, InstitutionId bank) {
    Objects.requireNonNull(company);
    Objects.requireNonNull(bank);
    if (companyRevision < 1 || membershipRevision < 0)
      throw new IllegalArgumentException("Corporate authority revision");
  }

  private static HashMap<String, String> authorityFields(
      UUID company, long companyRevision, long membershipRevision, InstitutionId bank) {
    return new HashMap<>(
        Map.of(
            "company", company.toString(),
            "company_revision", Long.toString(companyRevision),
            "membership_revision", Long.toString(membershipRevision),
            "bank", bank.key()));
  }

  private static Journal.Record fact(
      Status status,
      WorldId world,
      UUID company,
      PlayerId actor,
      AccountId account,
      InstitutionId bank,
      EconomyCurrency currency,
      long revision,
      long providerRevision,
      long debitEpoch,
      long companyRevision,
      long membershipRevision) {
    return new Journal.Record(
        FACT,
        1,
        String.join(
            "|",
            status.name(),
            world.value().toString(),
            company.toString(),
            actor.value().toString(),
            account.value().toString(),
            bank.key(),
            currency.code(),
            Long.toString(revision),
            Long.toString(providerRevision),
            Long.toString(debitEpoch),
            Long.toString(companyRevision),
            Long.toString(membershipRevision)));
  }

  public static void validateFact(String payload) {
    var parts = payload.split("\\|", -1);
    if (parts.length != 12) throw new IllegalArgumentException("Corporate account fact fields");
    Status status = Status.valueOf(parts[0]);
    var bank = new InstitutionId(parts[5]);
    var currency = EconomyCurrency.fromCode(parts[6]);
    long revision = Long.parseLong(parts[7]);
    long providerRevision = Long.parseLong(parts[8]);
    long epoch = Long.parseLong(parts[9]);
    long companyRevision = Long.parseLong(parts[10]);
    long membershipRevision = Long.parseLong(parts[11]);
    boolean lifecycle =
        status == Status.OPEN
            ? revision == 1 && providerRevision == 1 && epoch == 0
            : revision >= 2 && providerRevision >= 2 && epoch >= 1;
    if (!lifecycle
        || companyRevision < 1
        || membershipRevision < 0
        || !fact(
                status,
                new WorldId(canonicalUuid(parts[1])),
                canonicalUuid(parts[2]),
                new PlayerId(canonicalUuid(parts[3])),
                new AccountId(canonicalUuid(parts[4])),
                bank,
                currency,
                revision,
                providerRevision,
                epoch,
                companyRevision,
                membershipRevision)
            .payload()
            .equals(payload)) throw new IllegalArgumentException("Invalid corporate account fact");
  }

  private static UUID canonicalUuid(String value) {
    var key = UUID.fromString(value);
    if (!key.toString().equals(value)) throw new IllegalArgumentException("Noncanonical UUID");
    return key;
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }
}
