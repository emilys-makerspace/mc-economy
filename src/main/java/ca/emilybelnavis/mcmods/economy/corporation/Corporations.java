package ca.emilybelnavis.mcmods.economy.corporation;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.BankingFees;
import ca.emilybelnavis.mcmods.economy.storage.LedgerProjection;
import com.ibm.icu.text.Normalizer2;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.function.LongSupplier;

/** World-local founder operations. The company is the subject, never the submitting actor. */
public final class Corporations {
  private Corporations() {}

  public static final Journal.Kind KIND =
      new Journal.Kind("economy:corporations", "economy:corporations/founder");
  public static final String FACT = "economy:corporations/lifecycle";

  public enum State {
    ACTIVE,
    WINDING_DOWN,
    DISSOLVED
  }

  public enum Action {
    REGISTER,
    RENAME,
    WIND_DOWN,
    CANCEL,
    DISSOLVE
  }

  /** The public identifier can change on rename. partyId links retained financial history. */
  public record Company(
      UUID partyId, UUID founder, String name, String identifier, State state, long revision) {
    public Company(UUID partyId, UUID founder, String name, State state, long revision) {
      this(partyId, founder, name, Corporations.identifier(name), state, revision);
    }

    /** Compatibility accessor for existing accounting and integration callers. */
    public UUID id() {
      return partyId;
    }
  }

  private static final String COMPANY_SELECT =
      "SELECT c.*,r.identifier FROM corporation_registry r JOIN corporations c ON c.id=r.party ";

  public record ShareIssue(long quantity, Money price) {
    public ShareIssue {
      Objects.requireNonNull(price);
      if (quantity <= 0 || price.minorUnits() <= 0)
        throw new IllegalArgumentException("Share quantity and price must both be positive");
      Math.multiplyExact(quantity, price.minorUnits());
    }

    public Money total() {
      return new Money(price.currency(), Math.multiplyExact(quantity, price.minorUnits()));
    }
  }

  /** Public identifier. The journal retains its original, immutable ledger-party handle. */
  public static String identifier(String name) {
    return key(name).replace(' ', '-');
  }

  public record Fee(
      AccountId account, long revision, EconomyCurrency currency, long minor, UUID publication) {
    public Fee {
      Objects.requireNonNull(account);
      Objects.requireNonNull(currency);
      if (revision < 1 || minor <= 0 || (currency == EconomyCurrency.CAD) != (publication == null))
        throw new IllegalArgumentException("Registration fee witness");
    }
  }

  public sealed interface Command permits Register, Change, Issue {
    Map<String, String> fields();

    default Journal.CanonicalRequest request() {
      return new Journal.CanonicalRequest(1, fields());
    }
  }

  public record Register(String name, Fee fee, ShareIssue issue) implements Command {
    /** Older integrations can create a company shell; its founder must issue and fund shares. */
    public Register(String name, Fee fee) {
      this(name, fee, null);
    }

    public Register {
      name = validateName(name);
      Objects.requireNonNull(fee);
    }

    public Map<String, String> fields() {
      var values =
          new HashMap<>(
              Map.of(
                  "action",
                  "REGISTER",
                  "name",
                  name,
                  "funding",
                  fee.account.value().toString(),
                  "account_revision",
                  Long.toString(fee.revision),
                  "currency",
                  fee.currency.name(),
                  "minor",
                  Long.toString(fee.minor),
                  "fx",
                  Objects.toString(fee.publication, "")));
      if (issue != null) addShareFields(values, issue);
      return Map.copyOf(values);
    }
  }

  private static void addShareFields(Map<String, String> values, ShareIssue issue) {
    values.put("shares", Long.toString(issue.quantity()));
    values.put("share_price", Long.toString(issue.price().minorUnits()));
    values.put("share_currency", issue.price().currency().code());
  }

  public record Issue(UUID id, long revision, ShareIssue issue) implements Command {
    public Issue {
      Objects.requireNonNull(id);
      Objects.requireNonNull(issue);
      if (revision < 1) throw new IllegalArgumentException("Invalid corporate revision");
    }

    public Map<String, String> fields() {
      var values =
          new HashMap<>(
              Map.of(
                  "action",
                  "ISSUE",
                  "company",
                  id.toString(),
                  "revision",
                  Long.toString(revision)));
      addShareFields(values, issue);
      return Map.copyOf(values);
    }
  }

  public record Change(UUID id, long revision, Action action, String name) implements Command {
    public Change {
      Objects.requireNonNull(id);
      Objects.requireNonNull(action);
      if (revision < 1 || action == Action.REGISTER)
        throw new IllegalArgumentException("Lifecycle change");
      if (action == Action.RENAME) name = validateName(name);
      else if (!"".equals(name)) throw new IllegalArgumentException("Unexpected company name");
    }

    public Map<String, String> fields() {
      return Map.of(
          "action",
          action.name(),
          "company",
          id.toString(),
          "revision",
          Long.toString(revision),
          "name",
          name);
    }
  }

  private static String key(String name) {
    return Normalizer2.getNFKCCasefoldInstance().normalize(name);
  }

  public static String validateName(String raw) {
    if (raw == null
        || raw.length() > 192
        || raw.codePoints()
            .anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT))
      throw new IllegalArgumentException("Invalid company name");
    String name = Normalizer.normalize(raw, Normalizer.Form.NFKC);
    if (!name.equals(name.strip()) || raw.codePointCount(0, raw.length()) > 32)
      throw new IllegalArgumentException(
          "Enter a company name up to 32 characters without surrounding spaces");
    int length = name.codePointCount(0, name.length());
    if (length < 1
        || length > 32
        || !name.matches("[\\p{L}\\p{N} .,‘’'&()_-]+")
        || name.codePoints().noneMatch(Character::isLetterOrDigit))
      throw new IllegalArgumentException("Invalid company name");
    String slug = slug(name);
    if (Set.of("system", "server", "economy", "treasury", "institutioncapital", "registrationfees")
            .contains(slug)
        || InstitutionCatalog.all(EconomyCurrency.CAD).stream()
            .anyMatch(
                bank ->
                    slug(bank.name()).equals(slug)
                        || slug(bank.id().key().substring(bank.id().key().lastIndexOf('/') + 1))
                            .equals(slug)))
      throw new IllegalArgumentException("Reserved company name");
    return name;
  }

  private static String slug(String name) {
    return key(name).replaceAll("[^\\p{L}\\p{N}]", "");
  }

  public static Optional<Fee> quote(
      JournalEngine.ReadView reads, PartyRef founder, AccountId account, long now) {
    return AccountCatalog.find(reads, founder, account)
        .filter(
            a ->
                a.status() == AccountIdentity.Status.OPEN
                    && a.purpose() == AccountIdentity.Purpose.BANK)
        .flatMap(
            a -> {
              if (a.currency() == EconomyCurrency.CAD)
                return Optional.of(new Fee(account, a.revision(), a.currency(), 50000, null));
              var fx = FxService.at(reads, now);
              if (fx == null
                  || !fx.world().equals(founder.world())
                  || now - fx.effectiveMillis() >= 172800000L
                  || !fx.rates().containsKey(a.currency())) return Optional.empty();
              long minor =
                  BankingFees.convert(
                          BigDecimal.valueOf(500),
                          a.currency(),
                          fx.rates().get(EconomyCurrency.CAD),
                          fx.rates().get(a.currency()))
                      .minorUnits();
              return minor <= 0
                  ? Optional.empty()
                  : Optional.of(new Fee(account, a.revision(), a.currency(), minor, fx.id()));
            });
  }

  private static Company company(Map<String, String> r) {
    return new Company(
        UUID.fromString(r.get("id")),
        UUID.fromString(r.get("founder")),
        r.get("name"),
        r.get("identifier"),
        State.valueOf(r.get("state")),
        Long.parseLong(r.get("revision")));
  }

  public static Optional<Company> find(JournalEngine.ReadView reads, PlayerId owner, UUID id) {
    return reads
        .query(
            COMPANY_SELECT + "WHERE c.founder=? AND c.id=?",
            List.of(owner.value().toString(), id.toString()),
            1)
        .stream()
        .findFirst()
        .map(Corporations::company);
  }

  public static Optional<Company> resolve(
      JournalEngine.ReadView reads, PlayerId owner, String identifier) {
    return reads
        .query(
            COMPANY_SELECT + "WHERE c.founder=? AND r.identifier=?",
            List.of(owner.value().toString(), identifier),
            1)
        .stream()
        .findFirst()
        .map(Corporations::company);
  }

  public static List<Company> page(
      JournalEngine.ReadView reads, PlayerId owner, UUID after, int limit) {
    if (limit < 1 || limit > 63) throw new IllegalArgumentException("Company page size");
    return reads
        .query(
            COMPANY_SELECT + "WHERE c.founder=? AND c.id>? ORDER BY c.id LIMIT ?",
            List.of(owner.value().toString(), Objects.toString(after, ""), Integer.toString(limit)),
            limit)
        .stream()
        .map(Corporations::company)
        .toList();
  }

  public static JournalEngine.Registration handler(
      PartyRef owner, Command command, LongSupplier now) {
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!(owner.owner() instanceof PartyRef.Player player)
              || !player.id().equals(intent.actor())
              || !KIND.equals(intent.kind())) return reject("CORPORATION_AUTHORITY");
          var fields = new HashMap<>(command.fields());
          fields.put("economy:owner", Journal.actorKey(intent.actor()));
          if (!new Journal.CanonicalRequest(1, fields).equals(intent.request()))
            return reject("CORPORATION_REQUEST");
          try {
            if (command instanceof Register register)
              return register(owner, player.id(), register, reads, now.getAsLong());
            if (command instanceof Issue issue)
              return CorporateCapital.issue(reads, owner.world(), player.id(), issue);
            return change(player.id(), (Change) command, reads);
          } catch (ArithmeticException overflow) {
            return reject("CORPORATION_OVERFLOW");
          }
        });
  }

  private static JournalEngine.Reject reject(String reason) {
    return new JournalEngine.Reject(new Journal.Result(1, reason, ""));
  }

  private static Optional<String> alias(JournalEngine.ReadView reads, String name) {
    return reads
        .query(
            "SELECT party AS company FROM corporation_registry WHERE identifier=?",
            List.of(identifier(name)),
            1)
        .stream()
        .findFirst()
        .map(r -> r.get("company"));
  }

  static JournalEngine.Decision register(
      PartyRef owner, PlayerId founder, Register command, JournalEngine.ReadView reads, long now) {
    if (alias(reads, command.name).isPresent()) return reject("CORPORATION_NAME_RESERVED");
    Fee fee = command.fee;
    if (quote(reads, owner, fee.account, now).filter(fee::equals).isEmpty())
      return reject("CORPORATION_FEE_STALE");
    var currency = fee.currency;
    UUID source = fee.account.value();
    if (Holds.available(reads, source, currency) < fee.minor) return reject("INSUFFICIENT_FUNDS");
    UUID sink =
        UUID.nameUUIDFromBytes(
            ("economy:registration-fee:v1|" + owner.world().value() + "|" + currency)
                .getBytes(StandardCharsets.UTF_8));
    long balance = Math.subtractExact(Settlement.posted(reads, source, currency), fee.minor);
    long retired = Math.addExact(Settlement.posted(reads, sink, currency), fee.minor);
    var decision =
        PostingBatch.prepareMinor(
            List.of(
                new PostingBatch.Leg(
                    source, PostingBatch.Role.ACCOUNT, currency, new Money(currency, -fee.minor)),
                new PostingBatch.Leg(
                    sink, PostingBatch.Role.ISSUANCE, currency, new Money(currency, fee.minor))),
            PostingBatch.Authority.REDEEM);
    if (!(decision instanceof JournalEngine.Commit posting)) return decision;
    var company = new Company(UUID.randomUUID(), founder.value(), command.name, State.ACTIVE, 1);
    var records = new ArrayList<>(posting.records());
    records.add(fact(company, Action.REGISTER));
    if (command.issue != null)
      records.add(CorporateCapital.issueFact(company.partyId, command.issue));
    return new JournalEngine.Commit(
        records,
        result(company),
        tx -> {
          posting.projections().apply(tx);
          LedgerProjection.write(tx, source, currency, balance);
          LedgerProjection.write(tx, sink, currency, retired);
          tx.execute(
              "INSERT INTO corporations(id,founder,name,state,revision) VALUES(?,?,?,'ACTIVE',1)",
              List.of(company.partyId.toString(), founder.value().toString(), company.name));
          tx.execute(
              "INSERT INTO corporation_registry(identifier,party) VALUES(?,?)",
              List.of(identifier(company.name), company.partyId.toString()));
          if (command.issue != null)
            CorporateCapital.writeIssue(tx, identifier(company.name), command.issue);
        });
  }

  private static JournalEngine.Decision change(
      PlayerId founder, Change change, JournalEngine.ReadView reads) {
    var old = find(reads, founder, change.id).orElse(null);
    if (old == null) return reject("CORPORATION_UNAVAILABLE");
    if (old.revision != change.revision) return reject("CORPORATION_STALE");
    State next =
        switch (change.action) {
          case RENAME -> old.state == State.ACTIVE ? State.ACTIVE : null;
          case WIND_DOWN -> old.state == State.ACTIVE ? State.WINDING_DOWN : null;
          case CANCEL -> old.state == State.WINDING_DOWN ? State.ACTIVE : null;
          case DISSOLVE -> old.state == State.WINDING_DOWN ? State.DISSOLVED : null;
          default -> null;
        };
    if (next == null) return reject("CORPORATION_STATE");
    if (next == State.DISSOLVED && !clearForDissolution(reads, change.id))
      return reject("CORPORATION_DISSOLUTION_BLOCKED");
    boolean rename = change.action == Action.RENAME;
    var alias = rename ? alias(reads, change.name) : Optional.<String>empty();
    if (alias.isPresent() && !alias.get().equals(change.id.toString()))
      return reject("CORPORATION_NAME_RESERVED");
    var company =
        new Company(
            old.partyId,
            old.founder,
            rename ? change.name : old.name,
            rename ? identifier(change.name) : old.identifier,
            next,
            Math.incrementExact(old.revision));
    return new JournalEngine.Commit(
        List.of(fact(company, change.action)),
        result(company),
        tx -> {
          tx.execute(
              "UPDATE corporations SET name=?,state=?,revision=? WHERE id=? AND revision=?",
              List.of(
                  company.name,
                  next.name(),
                  Long.toString(company.revision),
                  company.partyId.toString(),
                  Long.toString(old.revision)));
          if (rename)
            tx.execute(
                "UPDATE corporation_registry SET identifier=? WHERE party=?",
                List.of(identifier(company.name), company.partyId.toString()));
        });
  }

  private static boolean clearForDissolution(JournalEngine.ReadView reads, UUID id) {
    // Each admitted domain must own a claim from first exposure through settlement, in its
    // financial transaction. UNAVAILABLE fences an incomplete reconstruction or domain upgrade.
    String owner = "organization:" + id;
    return reads
            .query(
                "SELECT 1 FROM corporation_claims WHERE company=? LIMIT 1",
                List.of(id.toString()),
                1)
            .isEmpty()
        && reads
            .query(
                "SELECT 1 FROM account_catalog WHERE owner_id=? AND status='OPEN' LIMIT 1",
                List.of(owner),
                1)
            .isEmpty();
  }

  private static Journal.Record fact(Company c, Action action) {
    return new Journal.Record(
        FACT,
        1,
        action
            + "|"
            + c.partyId
            + "|"
            + c.founder
            + "|"
            + c.name
            + "|"
            + c.state
            + "|"
            + c.revision);
  }

  public static void validateFact(String payload) {
    var p = payload.split("\\|", -1);
    if (p.length != 6) throw new IllegalArgumentException("Corporate fact fields");
    var action = Action.valueOf(p[0]);
    var company =
        new Company(
            UUID.fromString(p[1]),
            UUID.fromString(p[2]),
            p[3],
            State.valueOf(p[4]),
            Long.parseLong(p[5]));
    State expected =
        switch (action) {
          case REGISTER, RENAME, CANCEL -> State.ACTIVE;
          case WIND_DOWN -> State.WINDING_DOWN;
          case DISSOLVE -> State.DISSOLVED;
        };
    if (company.name.isBlank()
        || company.revision < 1
        || (action == Action.REGISTER) != (company.revision == 1)
        || expected != company.state
        || !fact(company, action).payload().equals(payload))
      throw new IllegalArgumentException("Invalid corporation lifecycle fact");
  }

  private static Journal.Result result(Company c) {
    return new Journal.Result(1, "CORPORATION_" + c.state, c.partyId + "|" + c.revision);
  }
}
