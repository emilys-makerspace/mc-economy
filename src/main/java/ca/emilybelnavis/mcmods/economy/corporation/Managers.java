package ca.emilybelnavis.mcmods.economy.corporation;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.util.*;
import java.util.stream.Collectors;

/** Worker-side corporate membership. The founder and the delegated actor remain separate. */
public final class Managers {
  private Managers() {}

  public static final Journal.Kind KIND =
      new Journal.Kind("economy:corporations", "economy:corporations/managers");
  public static final String FACT = "economy:corporations/membership";
  public static final String PAYMENT_DISCLOSURE =
      "Payments and transfers may spend available company funds, including payments to this"
          + " manager.";

  public enum Capability {
    VIEW_HISTORY,
    PAY_TRANSFER,
    MANAGE_ACCOUNTS
  }

  public enum Mutation {
    APPOINT,
    CHANGE,
    REVOKE
  }

  public enum Use {
    HISTORY(Capability.VIEW_HISTORY),
    PAYMENT(Capability.PAY_TRANSFER),
    OPEN(Capability.MANAGE_ACCOUNTS),
    CLOSE(Capability.MANAGE_ACCOUNTS);
    private final Capability capability;

    Use(Capability capability) {
      this.capability = capability;
    }
  }

  public record Scope(boolean allAccounts, Set<UUID> accounts) {
    public Scope {
      accounts = Set.copyOf(accounts);
      if (allAccounts && !accounts.isEmpty() || accounts.size() > 4)
        throw new IllegalArgumentException("Account scope");
    }

    public static Scope all() {
      return new Scope(true, Set.of());
    }

    String encode() {
      return allAccounts
          ? "*"
          : accounts.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
    }

    static Scope decode(String value) {
      if (value.equals("*")) return all();
      if (value.isEmpty()) return new Scope(false, Set.of());
      return new Scope(
          false,
          Arrays.stream(value.split(",", -1)).map(UUID::fromString).collect(Collectors.toSet()));
    }
  }

  public record Rights(Set<Capability> capabilities, Scope scope) {
    public Rights {
      capabilities = Set.copyOf(capabilities);
      Objects.requireNonNull(scope);
    }

    public static Rights viewOnly(Scope scope) {
      return new Rights(Set.of(Capability.VIEW_HISTORY), scope);
    }

    public static Rights none() {
      return new Rights(Set.of(), new Scope(false, Set.of()));
    }

    String encode() {
      return capabilities.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    static Rights decode(String caps, String scope) {
      return new Rights(
          caps.isEmpty()
              ? Set.of()
              : Arrays.stream(caps.split(",", -1))
                  .map(Capability::valueOf)
                  .collect(Collectors.toSet()),
          Scope.decode(scope));
    }
  }

  public record Change(
      UUID company,
      long companyRevision,
      UUID manager,
      long membershipRevision,
      Mutation mutation,
      Rights rights,
      boolean acceptsSelfPayment) {
    public Change {
      Objects.requireNonNull(company);
      Objects.requireNonNull(manager);
      Objects.requireNonNull(mutation);
      Objects.requireNonNull(rights);
      if (companyRevision < 1
          || membershipRevision < 0
          || mutation == Mutation.APPOINT && !rights.equals(Rights.viewOnly(rights.scope))
          || mutation == Mutation.REVOKE && !rights.equals(Rights.none())
          || acceptsSelfPayment != rights.capabilities.contains(Capability.PAY_TRANSFER))
        throw new IllegalArgumentException(PAYMENT_DISCLOSURE);
    }

    public Journal.CanonicalRequest request() {
      return new Journal.CanonicalRequest(
          1,
          Map.of(
              "company",
              company.toString(),
              "company_revision",
              Long.toString(companyRevision),
              "manager",
              manager.toString(),
              "membership_revision",
              Long.toString(membershipRevision),
              "mutation",
              mutation.name(),
              "capabilities",
              rights.encode(),
              "scope",
              rights.scope.encode(),
              "self_payment_disclosure",
              acceptsSelfPayment ? PAYMENT_DISCLOSURE : ""));
    }
  }

  public record Membership(
      UUID company, UUID manager, long revision, boolean active, Rights rights) {}

  private static Membership row(Map<String, String> r) {
    return new Membership(
        UUID.fromString(r.get("company")),
        UUID.fromString(r.get("manager")),
        Long.parseLong(r.get("revision")),
        "1".equals(r.get("active")),
        Rights.decode(r.get("capabilities"), r.get("scope")));
  }

  private static Optional<Membership> membership(
      JournalEngine.ReadView reads, UUID company, UUID manager) {
    return reads
        .query(
            "SELECT * FROM corporation_managers WHERE company=? AND manager=?",
            List.of(company.toString(), manager.toString()),
            1)
        .stream()
        .findFirst()
        .map(Managers::row);
  }

  public static Optional<List<Membership>> roster(
      JournalEngine.ReadView reads, PlayerId founder, UUID company, UUID after, int limit) {
    if (limit < 1 || limit > 63) throw new IllegalArgumentException("Manager page size");
    return Corporations.find(reads, founder, company)
        .map(
            c ->
                reads
                    .query(
                        "SELECT * FROM corporation_managers WHERE company=? AND manager>? ORDER BY"
                            + " manager LIMIT ?",
                        List.of(
                            company.toString(),
                            Objects.toString(after, ""),
                            Integer.toString(limit)),
                        limit)
                    .stream()
                    .map(Managers::row)
                    .toList());
  }

  private static boolean ownsAccount(JournalEngine.ReadView reads, UUID company, UUID account) {
    return !reads
        .query(
            "SELECT 1 FROM account_catalog WHERE account_id=? AND owner_id=? AND purpose='BANK'",
            List.of(account.toString(), "organization:" + company),
            1)
        .isEmpty();
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }

  public static JournalEngine.Registration mutation(PartyRef owner, Change change) {
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!(owner.owner() instanceof PartyRef.Player player)
              || !player.id().equals(intent.actor())
              || !intent.kind().equals(KIND)) return reject("CORPORATION_UNAVAILABLE");
          var fields = new HashMap<>(change.request().fields());
          fields.put("economy:owner", Journal.actorKey(player.id()));
          if (!fields.equals(intent.request().fields())) return reject("CORPORATION_REQUEST");
          var company = Corporations.find(reads, player.id(), change.company).orElse(null);
          if (company == null) return reject("CORPORATION_UNAVAILABLE");
          if (company.revision() != change.companyRevision) return reject("CORPORATION_STALE");
          if (company.state() != Corporations.State.ACTIVE
              && !(company.state() == Corporations.State.WINDING_DOWN
                  && change.mutation == Mutation.REVOKE)) return reject("CORPORATION_STATE");
          if (change.manager.equals(company.founder())) return reject("FOUNDER_MEMBERSHIP");
          var old = membership(reads, change.company, change.manager).orElse(null);
          long revision = old == null ? 0 : old.revision;
          if (revision != change.membershipRevision) return reject("MEMBERSHIP_STALE");
          boolean wasActive = old != null && old.active;
          if ((change.mutation == Mutation.APPOINT) == wasActive) return reject("MEMBERSHIP_STATE");
          if (change.rights.scope.accounts.stream()
              .anyMatch(a -> !ownsAccount(reads, change.company, a)))
            return reject("CORPORATION_UNAVAILABLE");
          if (revision == Long.MAX_VALUE) return reject("MEMBERSHIP_OVERFLOW");
          long next = revision + 1;
          String caps = change.rights.encode(), scope = change.rights.scope.encode();
          var args =
              List.of(
                  change.company.toString(),
                  change.manager.toString(),
                  Long.toString(next),
                  change.mutation == Mutation.REVOKE ? "0" : "1",
                  caps,
                  scope);
          String payload =
              String.join("|", args)
                  + "|"
                  + change.mutation
                  + "|"
                  + company.revision()
                  + "|"
                  + company.founder();
          return new JournalEngine.Commit(
              List.of(new Journal.Record(FACT, 1, payload)),
              new Journal.Result(1, "MEMBERSHIP_UPDATED", Long.toString(next)),
              tx ->
                  tx.execute(
                      "INSERT INTO"
                          + " corporation_managers(company,manager,revision,active,capabilities,scope)"
                          + " VALUES(?,?,?,?,?,?) ON CONFLICT(company,manager) DO UPDATE SET"
                          + " revision=excluded.revision, active=excluded.active,"
                          + " capabilities=excluded.capabilities, scope=excluded.scope",
                      args));
        });
  }

  public static void validateFact(String payload) {
    var p = payload.split("\\|", -1);
    if (p.length != 9) throw new IllegalArgumentException("Membership fact");
    UUID.fromString(p[0]);
    UUID manager = UUID.fromString(p[1]);
    UUID founder = UUID.fromString(p[8]);
    var rights = Rights.decode(p[4], p[5]);
    var mutation = Mutation.valueOf(p[6]);
    if (manager.equals(founder)
        || Long.parseLong(p[2]) < 1
        || Long.parseLong(p[7]) < 1
        || !rights.encode().equals(p[4])
        || !rights.scope.encode().equals(p[5])
        || !(mutation == Mutation.REVOKE ? "0" : "1").equals(p[3])
        || mutation == Mutation.REVOKE && !rights.equals(Rights.none())
        || mutation == Mutation.APPOINT && !rights.equals(Rights.viewOnly(rights.scope)))
      throw new IllegalArgumentException("Membership fact state");
  }

  /**
   * Bind these fields into a domain intent. The required use and account come from the trusted
   * domain operation and its resolved source, never from a client-selected capability.
   */
  public record Requirement(
      UUID company, long companyRevision, long membershipRevision, Use use, UUID account) {
    public Requirement {
      Objects.requireNonNull(company);
      Objects.requireNonNull(use);
      if (companyRevision < 1 || membershipRevision < 1 || (use == Use.OPEN) != (account == null))
        throw new IllegalArgumentException("Manager authority scope");
    }

    public Map<String, String> fields() {
      return Map.of(
          "company",
          company.toString(),
          "company_revision",
          Long.toString(companyRevision),
          "membership_revision",
          Long.toString(membershipRevision),
          "corporate_use",
          use.name(),
          "corporate_account",
          Objects.toString(account, ""));
    }
  }

  /** Shared predicate for fresh effects and fresh readership checks on the storage worker. */
  public static boolean permits(JournalEngine.ReadView reads, PlayerId actor, Requirement r) {
    var companies =
        reads.query(
            "SELECT state FROM corporations WHERE id=? AND revision=?",
            List.of(r.company.toString(), Long.toString(r.companyRevision)),
            1);
    if (companies.isEmpty()) return false;
    var state = Corporations.State.valueOf(companies.getFirst().get("state"));
    if (state == Corporations.State.DISSOLVED
        || state == Corporations.State.WINDING_DOWN && r.use == Use.OPEN) return false;
    var m = membership(reads, r.company, actor.value()).orElse(null);
    if (m == null
        || !m.active
        || m.revision != r.membershipRevision
        || !m.rights.capabilities.contains(r.use.capability)) return false;
    if (r.account == null) return m.rights.scope.allAccounts;
    return (m.rights.scope.allAccounts || m.rights.scope.accounts.contains(r.account))
        && ownsAccount(reads, r.company, r.account);
  }

  public static JournalEngine.Registration guarded(
      Requirement required, JournalEngine.Registration domain) {
    return new JournalEngine.Registration(
        domain.canonicalVersion(),
        (intent, reads) -> {
          if (!(intent.actor() instanceof PlayerId actor)
              || !intent.request().fields().entrySet().containsAll(required.fields().entrySet())
              || !permits(reads, actor, required)) return reject("CORPORATION_UNAVAILABLE");
          return domain.handler().prepare(intent, reads);
        });
  }
}
