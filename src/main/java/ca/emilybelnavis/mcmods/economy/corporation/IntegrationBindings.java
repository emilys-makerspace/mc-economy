package ca.emilybelnavis.mcmods.economy.corporation;

import ca.emilybelnavis.mcmods.economy.api.CorporationIntegration.Capability;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/** Durable, world-local integration authority. The session adapter authenticates both audiences. */
public final class IntegrationBindings {
  public static final String FACT = "economy:corporations/integration-binding";

  private IntegrationBindings() {}

  public static Journal.Kind kind(Key key) {
    return new Journal.Kind(key.provider(), "economy:corporations/owner-authorization");
  }

  public static boolean isRegistration(Journal.Intent intent) {
    return intent.kind().operationType().equals("economy:corporations/owner-authorization")
        && Action.CREATE.name().equals(intent.request().fields().get("integration_action"));
  }

  public record Key(String provider, String externalKey) {
    public Key {
      // Match the journal's namespaced grammar and bind a namespaced external key to its provider.
      new Journal.Kind(provider, provider);
      Objects.requireNonNull(externalKey);
      if (externalKey.length() > 128
          || !(externalKey.matches("[A-Za-z0-9_.-]+")
              || (externalKey.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                  && externalKey.startsWith(provider.substring(0, provider.indexOf(':') + 1)))))
        throw new IllegalArgumentException("Integration external key");
    }
  }

  public record Binding(Key key, UUID company) {}

  /** Active means that the persisted grant matches the company's current wind-down epoch. */
  public record Mandate(
      UUID company,
      long companyRevision,
      long revision,
      boolean active,
      long epoch,
      Set<Capability> capabilities,
      Set<AccountId> accounts) {
    public Mandate {
      capabilities = Set.copyOf(capabilities);
      accounts = Set.copyOf(accounts);
    }
  }

  public sealed interface Command permits Bind, Create, Approve, Revoke {
    Key key();

    Map<String, String> fields();

    default Journal.CanonicalRequest request(PlayerId founder) {
      var fields = new HashMap<>(fields());
      fields.put("economy:owner", Journal.actorKey(founder));
      return new Journal.CanonicalRequest(1, fields);
    }
  }

  public record Bind(Key key, UUID company, long companyRevision) implements Command {
    public Bind {
      requireCompany(key, company, companyRevision);
    }

    @Override
    public Map<String, String> fields() {
      return Map.copyOf(companyFields(key, Action.BIND, company, companyRevision));
    }
  }

  public record Create(Key key, Corporations.Register registration) implements Command {
    public Create {
      Objects.requireNonNull(key);
      Objects.requireNonNull(registration);
    }

    @Override
    public Map<String, String> fields() {
      var fields = new HashMap<>(registration.fields());
      fields.putAll(keyFields(key, Action.CREATE));
      return Map.copyOf(fields);
    }
  }

  public record Approve(
      Key key,
      UUID company,
      long companyRevision,
      long mandateRevision,
      Set<Capability> capabilities,
      Set<AccountId> accounts)
      implements Command {
    public Approve {
      requireCompany(key, company, companyRevision);
      capabilities = Set.copyOf(capabilities);
      accounts = Set.copyOf(accounts);
      requireScope(capabilities, accounts);
      if (mandateRevision < 0) throw new IllegalArgumentException("Mandate revision");
    }

    @Override
    public Map<String, String> fields() {
      var fields = companyFields(key, Action.APPROVE, company, companyRevision);
      fields.put("mandate_revision", Long.toString(mandateRevision));
      fields.put("capabilities", capabilityKeys(capabilities));
      fields.put("accounts", accountKeys(accounts));
      return Map.copyOf(fields);
    }
  }

  public record Revoke(Key key, UUID company, long companyRevision, long mandateRevision)
      implements Command {
    public Revoke {
      requireCompany(key, company, companyRevision);
      if (mandateRevision < 1) throw new IllegalArgumentException("Mandate revision");
    }

    @Override
    public Map<String, String> fields() {
      var fields = companyFields(key, Action.REVOKE, company, companyRevision);
      fields.put("mandate_revision", Long.toString(mandateRevision));
      return Map.copyOf(fields);
    }
  }

  private enum Action {
    BIND,
    CREATE,
    REUSE,
    APPROVE,
    REVOKE
  }

  private record StoredBinding(UUID company, String registrationRequest) {}

  public static Optional<Binding> find(JournalEngine.ReadView reads, Key key) {
    return binding(reads, key).map(row -> new Binding(key, row.company()));
  }

  private static Optional<StoredBinding> binding(JournalEngine.ReadView reads, Key key) {
    return reads
        .query(
            """
            SELECT company,registration_request FROM corporation_integration_bindings
            WHERE provider=? AND external_key=?
            """,
            List.of(key.provider(), key.externalKey()),
            1)
        .stream()
        .findFirst()
        .map(row -> new StoredBinding(uuid(row.get("company")), row.get("registration_request")));
  }

  public static Optional<Mandate> mandate(JournalEngine.ReadView reads, Key key) {
    return reads
        .query(
            """
            SELECT b.company,c.revision AS company_revision,c.state,m.revision,m.epoch,m.active,
                   m.capabilities,m.accounts,COALESCE(e.epoch,0) AS company_epoch
            FROM corporation_integration_bindings b
            JOIN corporation_integration_mandates m
              ON m.provider=b.provider AND m.external_key=b.external_key
            JOIN corporations c ON c.id=b.company
            LEFT JOIN corporation_integration_epochs e ON e.company=b.company
            WHERE b.provider=? AND b.external_key=?
            """,
            List.of(key.provider(), key.externalKey()),
            1)
        .stream()
        .findFirst()
        .map(
            row -> {
              long epoch = Long.parseLong(row.get("epoch"));
              boolean active =
                  "1".equals(row.get("active"))
                      && "ACTIVE".equals(row.get("state"))
                      && epoch == Long.parseLong(row.get("company_epoch"));
              return new Mandate(
                  uuid(row.get("company")),
                  Long.parseLong(row.get("company_revision")),
                  Long.parseLong(row.get("revision")),
                  active,
                  epoch,
                  capabilities(row.get("capabilities")),
                  accounts(row.get("accounts")));
            });
  }

  public static boolean permits(
      JournalEngine.ReadView reads,
      Key key,
      UUID company,
      long companyRevision,
      long mandateRevision,
      Capability use,
      AccountId account) {
    if (use == Capability.CREATE || use == Capability.PAYMENTS && account == null) return false;
    return mandate(reads, key)
        .filter(
            grant ->
                grant.active()
                    && grant.company().equals(company)
                    && grant.companyRevision() == companyRevision
                    && grant.revision() == mandateRevision
                    && grant.capabilities().contains(use)
                    && (account == null || grant.accounts().contains(account)))
        .isPresent();
  }

  public static JournalEngine.Decision prepare(
      Journal.Intent intent,
      JournalEngine.ReadView reads,
      PartyRef owner,
      Command command,
      LongSupplier financialTime) {
    if (!(owner.owner() instanceof PartyRef.Player founder)
        || !founder.id().equals(intent.actor())
        || !kind(command.key()).equals(intent.kind())
        || !command.request(founder.id()).equals(intent.request()))
      return reject("INTEGRATION_AUTHORITY");

    try {
      var existing = binding(reads, command.key()).orElse(null);
      return switch (command) {
        case Create create ->
            create(intent, reads, owner, founder.id(), create, existing, financialTime);
        case Bind bind -> bind(reads, founder.id(), bind, existing);
        case Approve approve -> approve(reads, owner, founder.id(), approve, existing);
        case Revoke revoke -> revoke(reads, founder.id(), revoke, existing);
      };
    } catch (ArithmeticException overflow) {
      return reject("INTEGRATION_OVERFLOW");
    }
  }

  private static JournalEngine.Decision create(
      Journal.Intent intent,
      JournalEngine.ReadView reads,
      PartyRef owner,
      PlayerId actor,
      Create command,
      StoredBinding existing,
      LongSupplier financialTime) {
    if (existing != null) {
      if (!intent.canonical().equals(existing.registrationRequest())
          || Corporations.find(reads, actor, existing.company()).isEmpty())
        return reject("INTEGRATION_BINDING_CONFLICT");
      return new JournalEngine.Commit(
          List.of(bindingFact(Action.REUSE, command.key(), existing.company(), actor, 1)),
          result("INTEGRATION_CREATED", existing.company()),
          tx -> {});
    }

    var decision =
        Corporations.register(
            owner, actor, command.registration(), reads, financialTime.getAsLong());
    if (!(decision instanceof JournalEngine.Commit registration)) return decision;
    UUID company = uuid(registration.result().payload().split("\\|", -1)[0]);
    var records = new ArrayList<>(registration.records());
    records.add(bindingFact(Action.CREATE, command.key(), company, actor, 1));
    return new JournalEngine.Commit(
        records,
        result("INTEGRATION_CREATED", company),
        tx -> {
          registration.projections().apply(tx);
          insertBinding(tx, command.key(), company, intent.canonical());
        });
  }

  private static JournalEngine.Decision bind(
      JournalEngine.ReadView reads, PlayerId actor, Bind command, StoredBinding existing) {
    var company = Corporations.find(reads, actor, command.company()).orElse(null);
    if (company == null || company.state() != Corporations.State.ACTIVE)
      return reject("INTEGRATION_BINDING_UNAVAILABLE");
    if (company.revision() != command.companyRevision()) return reject("CORPORATION_STALE");
    if (existing != null) return reject("INTEGRATION_BINDING_CONFLICT");
    return new JournalEngine.Commit(
        List.of(bindingFact(Action.BIND, command.key(), company.id(), actor, company.revision())),
        result("INTEGRATION_BOUND", company.id()),
        tx -> insertBinding(tx, command.key(), company.id(), ""));
  }

  private static JournalEngine.Decision approve(
      JournalEngine.ReadView reads,
      PartyRef owner,
      PlayerId actor,
      Approve command,
      StoredBinding existing) {
    var company = boundCompany(reads, actor, command.company(), existing).orElse(null);
    if (company == null || company.state() != Corporations.State.ACTIVE)
      return reject("INTEGRATION_BINDING_UNAVAILABLE");
    if (company.revision() != command.companyRevision()) return reject("CORPORATION_STALE");
    long prior = mandate(reads, command.key()).map(Mandate::revision).orElse(0L);
    if (prior != command.mandateRevision()) return reject("INTEGRATION_MANDATE_STALE");
    for (AccountId account : command.accounts()) {
      if (CorporateAccounts.find(reads, owner.world(), company.id(), account)
          .filter(
              a -> a.status() == ca.emilybelnavis.mcmods.economy.api.AccountIdentity.Status.OPEN)
          .isEmpty()) return reject("INTEGRATION_ACCOUNT_UNAVAILABLE");
    }
    return changeMandate(
        reads,
        Action.APPROVE,
        command.key(),
        actor,
        company,
        prior,
        command.capabilities(),
        command.accounts());
  }

  private static JournalEngine.Decision revoke(
      JournalEngine.ReadView reads, PlayerId actor, Revoke command, StoredBinding existing) {
    var company = boundCompany(reads, actor, command.company(), existing).orElse(null);
    if (company == null || company.state() == Corporations.State.DISSOLVED)
      return reject("INTEGRATION_BINDING_UNAVAILABLE");
    if (company.revision() != command.companyRevision()) return reject("CORPORATION_STALE");
    long prior = mandate(reads, command.key()).map(Mandate::revision).orElse(0L);
    if (prior != command.mandateRevision()) return reject("INTEGRATION_MANDATE_STALE");
    return changeMandate(
        reads, Action.REVOKE, command.key(), actor, company, prior, Set.of(), Set.of());
  }

  private static Optional<Corporations.Company> boundCompany(
      JournalEngine.ReadView reads, PlayerId founder, UUID company, StoredBinding binding) {
    if (binding == null || !binding.company().equals(company)) return Optional.empty();
    return Corporations.find(reads, founder, company);
  }

  private static JournalEngine.Commit changeMandate(
      JournalEngine.ReadView reads,
      Action action,
      Key key,
      PlayerId actor,
      Corporations.Company company,
      long prior,
      Set<Capability> capabilities,
      Set<AccountId> accounts) {
    long epoch =
        reads
            .query(
                "SELECT epoch FROM corporation_integration_epochs WHERE company=?",
                List.of(company.id().toString()),
                1)
            .stream()
            .findFirst()
            .map(row -> Long.parseLong(row.get("epoch")))
            .orElse(0L);
    long revision = Math.incrementExact(prior);
    var fact =
        fact(
            action,
            key,
            company.id(),
            actor,
            company.revision(),
            revision,
            epoch,
            capabilities,
            accounts);
    boolean active = action == Action.APPROVE;
    return new JournalEngine.Commit(
        List.of(fact),
        new Journal.Result(
            1,
            active ? "INTEGRATION_APPROVED" : "INTEGRATION_REVOKED",
            company.id() + "|" + revision),
        tx ->
            tx.execute(
                """
            INSERT INTO corporation_integration_mandates
              (provider,external_key,revision,epoch,active,capabilities,accounts)
            VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(provider,external_key) DO UPDATE SET revision=excluded.revision,
              epoch=excluded.epoch,active=excluded.active,capabilities=excluded.capabilities,
              accounts=excluded.accounts
            """,
                List.of(
                    key.provider(),
                    key.externalKey(),
                    Long.toString(revision),
                    Long.toString(epoch),
                    active ? "1" : "0",
                    capabilityKeys(capabilities),
                    accountKeys(accounts))));
  }

  private static void insertBinding(
      ca.emilybelnavis.mcmods.economy.storage.FoundationStore.Transaction tx,
      Key key,
      UUID company,
      String request) {
    tx.execute(
        "INSERT INTO corporation_integration_bindings VALUES(?,?,?,?)",
        List.of(key.provider(), key.externalKey(), company.toString(), request));
  }

  private static Map<String, String> keyFields(Key key, Action action) {
    return Map.of(
        "integration_action",
        action.name(),
        "provider",
        key.provider(),
        "external_key",
        key.externalKey());
  }

  private static HashMap<String, String> companyFields(
      Key key, Action action, UUID company, long revision) {
    var fields = new HashMap<>(keyFields(key, action));
    fields.put("company", company.toString());
    fields.put("company_revision", Long.toString(revision));
    return fields;
  }

  private static void requireCompany(Key key, UUID company, long revision) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(company);
    if (revision < 1) throw new IllegalArgumentException("Company revision");
  }

  private static void requireScope(Set<Capability> capabilities, Set<AccountId> accounts) {
    boolean scoped =
        capabilities.contains(Capability.ACCOUNTS) || capabilities.contains(Capability.PAYMENTS);
    if (capabilities.isEmpty()
        || capabilities.contains(Capability.CREATE)
        || scoped == accounts.isEmpty()
        || accounts.size() > 4) throw new IllegalArgumentException("Integration mandate scope");
  }

  private static String capabilityKeys(Set<Capability> capabilities) {
    return capabilities.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
  }

  private static String accountKeys(Set<AccountId> accounts) {
    return accounts.stream()
        .map(id -> id.value().toString())
        .sorted()
        .collect(Collectors.joining(","));
  }

  private static Set<Capability> capabilities(String keys) {
    return keys.isEmpty()
        ? Set.of()
        : Arrays.stream(keys.split(",", -1))
            .map(Capability::valueOf)
            .collect(Collectors.toUnmodifiableSet());
  }

  private static Set<AccountId> accounts(String keys) {
    return keys.isEmpty()
        ? Set.of()
        : Arrays.stream(keys.split(",", -1))
            .map(key -> new AccountId(uuid(key)))
            .collect(Collectors.toUnmodifiableSet());
  }

  private static UUID uuid(String key) {
    var id = UUID.fromString(key);
    if (!id.toString().equals(key)) throw new IllegalArgumentException("Noncanonical UUID");
    return id;
  }

  private static Journal.Result result(String code, UUID company) {
    return new Journal.Result(1, code, company.toString());
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }

  private static Journal.Record bindingFact(
      Action action, Key key, UUID company, PlayerId actor, long revision) {
    return fact(action, key, company, actor, revision, 0, 0, Set.of(), Set.of());
  }

  private static Journal.Record fact(
      Action action,
      Key key,
      UUID company,
      PlayerId actor,
      long companyRevision,
      long mandateRevision,
      long epoch,
      Set<Capability> capabilities,
      Set<AccountId> accounts) {
    return new Journal.Record(
        FACT,
        1,
        String.join(
            "|",
            action.name(),
            key.provider(),
            key.externalKey(),
            company.toString(),
            actor.value().toString(),
            Long.toString(companyRevision),
            Long.toString(mandateRevision),
            Long.toString(epoch),
            capabilityKeys(capabilities),
            accountKeys(accounts)));
  }

  public static void validateFact(String payload) {
    var fields = payload.split("\\|", -1);
    if (fields.length != 10) throw new IllegalArgumentException("Integration fact fields");
    var action = Action.valueOf(fields[0]);
    var key = new Key(fields[1], fields[2]);
    var company = uuid(fields[3]);
    var actor = new PlayerId(uuid(fields[4]));
    long companyRevision = Long.parseLong(fields[5]);
    long revision = Long.parseLong(fields[6]);
    long epoch = Long.parseLong(fields[7]);
    var capabilities = capabilities(fields[8]);
    var accounts = accounts(fields[9]);
    requireCompany(key, company, companyRevision);
    boolean mandate = action == Action.APPROVE || action == Action.REVOKE;
    if (action == Action.APPROVE) requireScope(capabilities, accounts);
    else if (!capabilities.isEmpty() || !accounts.isEmpty())
      throw new IllegalArgumentException("Unexpected scope");
    if ((mandate
            ? revision < 1 || epoch < 0 || epoch > companyRevision
            : revision != 0 || epoch != 0)
        || ((action == Action.CREATE || action == Action.REUSE) && companyRevision != 1)
        || !fact(
                action,
                key,
                company,
                actor,
                companyRevision,
                revision,
                epoch,
                capabilities,
                accounts)
            .payload()
            .equals(payload)) throw new IllegalArgumentException("Invalid integration fact");
  }
}
