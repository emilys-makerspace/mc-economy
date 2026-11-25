package ca.emilybelnavis.mcmods.economy.corporation;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.card.CorporateCards;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.math.BigInteger;
import java.util.*;

/** Revolving corporate cash advances, backed by the lender's segregated reserves. */
public final class CorporateCredit {
  private CorporateCredit() {}

  public static final String FACT = "economy:corporations/credit";
  private static final long DENOMINATOR = 364L * 10000;

  public enum Action {
    APPLY,
    BORROW,
    REPAY,
    CLOSE
  }

  public record Request(
      Action action,
      UUID company,
      long companyRevision,
      long membershipRevision,
      AccountId account,
      long accountRevision,
      Network network,
      long amount,
      long lineRevision,
      long day) {
    public Map<String, String> fields() {
      return Map.of(
          "credit_action",
          action.name(),
          "company",
          company.toString(),
          "company_revision",
          Long.toString(companyRevision),
          "membership_revision",
          Long.toString(membershipRevision),
          "account",
          account.value().toString(),
          "account_revision",
          Long.toString(accountRevision),
          "network",
          network.key(),
          "amount",
          Long.toString(amount),
          "line_revision",
          Long.toString(lineRevision),
          "day",
          Long.toString(day));
    }
  }

  public record Line(
      Network network,
      long limit,
      long principal,
      long interest,
      long carry,
      long lastDay,
      long revision) {
    public long debt() {
      return Math.addExact(principal, interest);
    }

    public long available() {
      return Math.max(0, limit - debt());
    }
  }

  public static long day(long now) {
    return Math.floorDiv(now, CreditFacilities.DAY);
  }

  public static Optional<Line> find(JournalEngine.ReadView reads, AccountId account, long now) {
    return reads
        .query(
            "SELECT * FROM corporate_credit_lines WHERE account_id=? AND active=1",
            List.of(account.value().toString()),
            1)
        .stream()
        .findFirst()
        .map(
            row -> {
              var network = Network.valueOf(row.get("network"));
              long principal = number(row, "principal"), last = number(row, "last_day");
              long periods = Math.max(0, day(now) - last);
              long basisPoints =
                  CreditFacilities.apr(network, true).movePointRight(4).longValueExact();
              var accrual =
                  BigInteger.valueOf(principal)
                      .multiply(BigInteger.valueOf(periods))
                      .multiply(BigInteger.valueOf(basisPoints))
                      .add(BigInteger.valueOf(number(row, "carry")))
                      .divideAndRemainder(BigInteger.valueOf(DENOMINATOR));
              return new Line(
                  network,
                  number(row, "limit_minor"),
                  principal,
                  Math.addExact(number(row, "interest"), accrual[0].longValueExact()),
                  accrual[1].longValueExact(),
                  Math.max(last, day(now)),
                  number(row, "revision"));
            });
  }

  private static long number(Map<String, String> row, String field) {
    return Long.parseLong(row.get(field));
  }

  public static JournalEngine.Decision prepare(
      Journal.Intent intent,
      JournalEngine.ReadView reads,
      WorldId world,
      PlayerId actor,
      Request r,
      long now) {
    if (!actor.equals(intent.actor())
        || !world.value().toString().equals(intent.request().fields().get("world"))
        || !intent.request().fields().entrySet().containsAll(r.fields().entrySet())
        || r.day() != day(now)) return reject("CORPORATE_CREDIT_STALE");
    try {
      return checked(intent, reads, world, actor, r, now);
    } catch (ArithmeticException overflow) {
      return reject("CREDIT_AMOUNT_OVERFLOW");
    }
  }

  private static JournalEngine.Decision checked(
      Journal.Intent intent,
      JournalEngine.ReadView reads,
      WorldId world,
      PlayerId actor,
      Request r,
      long now) {
    var acct = CorporateAccounts.find(reads, world, r.company(), r.account()).orElse(null);
    if (acct == null
        || acct.status() != AccountIdentity.Status.OPEN
        || acct.revision() != r.accountRevision()) return reject("CORPORATION_ACCOUNT_STALE");
    boolean newBusiness = r.action() == Action.APPLY || r.action() == Action.BORROW;
    var rows =
        reads.query(
            "SELECT founder,state,revision FROM corporations WHERE id=?",
            List.of(r.company().toString()),
            1);
    if (rows.isEmpty()) return reject("CORPORATION_UNAVAILABLE");
    var row = rows.getFirst();
    boolean founder = actor.value().toString().equals(row.get("founder"));
    if (number(row, "revision") != r.companyRevision()
        || row.get("state").equals("DISSOLVED")
        || newBusiness && !row.get("state").equals("ACTIVE")
        || !(r.membershipRevision() == 0
            ? founder
            : r.membershipRevision() > 0
                && Managers.permits(
                    reads,
                    actor,
                    new Managers.Requirement(
                        r.company(),
                        r.companyRevision(),
                        r.membershipRevision(),
                        Managers.Use.BORROW,
                        r.account().value())))) return reject("CORPORATION_UNAVAILABLE");
    var profile = CorporateCapital.profile(reads, r.company()).orElse(null);
    if (profile == null || !profile.funded()) return reject("CORPORATION_CAPITAL_REQUIRED");
    var binding = BankProviders.find(reads, r.account()).orElse(null);
    if (binding == null) return reject("BANK_UNAVAILABLE");
    var bank = new InstitutionId(binding.bankKey());
    var lender = CorporateAccounts.ordinaryBank(reads, world, bank).orElse(null);
    if (lender == null || !lender.networks().contains(r.network()))
      return reject("CREDIT_NETWORK_UNAVAILABLE");
    var line = find(reads, r.account(), now).orElse(null);
    if ((line == null ? 0 : line.revision()) != r.lineRevision()
        || line != null && line.network() != r.network()) return reject("CORPORATE_CREDIT_STALE");
    if (r.action() != Action.CLOSE && r.amount() <= 0
        || r.action() == Action.CLOSE && r.amount() != 0) return reject("CREDIT_AMOUNT");
    if (r.action() == Action.APPLY) {
      if (line != null
          || profile.required().currency() != acct.currency()
          || r.amount() > profile.required().minorUnits()) return reject("CORPORATE_CREDIT_LIMIT");
      var reserve = CreditTreasury.reserve(reads, world, bank, acct.currency(), r.amount(), now);
      if (!(reserve instanceof JournalEngine.Commit funds)) return reserve;
      var previous =
          reads.query(
              "SELECT revision FROM corporate_credit_lines WHERE account_id=?",
              List.of(r.account().value().toString()),
              1);
      long version =
          previous.isEmpty() ? 1 : Math.incrementExact(number(previous.getFirst(), "revision"));
      var opened = new Line(r.network(), r.amount(), 0, 0, 0, r.day(), version);
      var commit = record(intent, r, profile.identifier(), opened, funds);
      return CorporateCards.issueCredit(
          intent, reads, world, r.company(), r.account(), bank, r.network().key(), actor, commit);
    }
    if (line == null) return reject("CORPORATE_CREDIT_UNAVAILABLE");
    long amount = r.amount();
    if (r.action() == Action.BORROW && amount > line.available())
      return reject("CREDIT_DRAW_LIMIT");
    if (r.action() == Action.REPAY && amount > line.debt()) return reject("CREDIT_REPAY_AMOUNT");
    if (r.action() == Action.CLOSE && line.debt() != 0)
      return reject("CORPORATE_CREDIT_OUTSTANDING");
    long principal = line.principal(), interest = line.interest();
    var borrower = new InstitutionId("economy:corporate-borrower/" + r.company());
    var reserve =
        FinancialAccounts.account(world, bank, FinancialAccounts.Role.RESERVATION, acct.currency());
    var cash = FinancialAccounts.account(world, bank, FinancialAccounts.Role.CASH, acct.currency());
    var claim =
        FinancialAccounts.account(world, bank, FinancialAccounts.Role.RECEIVABLE, acct.currency());
    var liability =
        FinancialAccounts.account(
            world, borrower, FinancialAccounts.Role.LIABILITY, acct.currency());
    var income =
        FinancialAccounts.account(world, bank, FinancialAccounts.Role.INCOME, acct.currency());
    var expense =
        FinancialAccounts.account(world, borrower, FinancialAccounts.Role.EXPENSE, acct.currency());
    var accounts = List.of(reserve, cash, claim, liability, income, expense);
    var legs = new ArrayList<PostingBatch.Leg>();
    if (r.action() == Action.CLOSE) {
      leg(legs, reserve, -line.limit());
      leg(legs, cash, line.limit());
    } else {
      long delta = r.action() == Action.BORROW ? amount : -amount;
      legs.add(
          new PostingBatch.Leg(
              acct.id().value(),
              PostingBatch.Role.ACCOUNT,
              acct.currency(),
              new Money(acct.currency(), delta)));
      long paidInterest = r.action() == Action.REPAY ? Math.min(interest, amount) : 0;
      long principalDelta = delta + paidInterest;
      leg(legs, reserve, -principalDelta);
      leg(legs, claim, principalDelta);
      leg(legs, liability, -principalDelta);
      leg(legs, cash, paidInterest);
      leg(legs, income, -paidInterest);
      leg(legs, expense, paidInterest);
      principal = Math.addExact(principal, principalDelta);
      interest -= paidInterest;
    }
    var result = InstitutionSettlement.prepareCorporateCredit(reads, legs, accounts);
    if (!(result instanceof JournalEngine.Commit posted)) return result;
    var facts = new ArrayList<>(posted.records());
    var actorKey = new SystemActor(world, borrower).actorId().value().toString();
    boolean createParty =
        reads
            .query(
                "SELECT 1 FROM institution_parties WHERE world_id=? AND institution_key=?",
                List.of(world.value().toString(), borrower.key()),
                1)
            .isEmpty();
    if (createParty)
      facts.add(
          new Journal.Record(
              FACT,
              1,
              "PARTY|"
                  + world.value()
                  + "|"
                  + r.company()
                  + "|"
                  + actorKey
                  + "|"
                  + acct.currency().name()));
    for (var a : accounts) {
      if (FinancialAccounts.find(reads, a.id()).isEmpty())
        facts.add(
            new Journal.Record(
                InstitutionCapital.ACCOUNT,
                1,
                a.id()
                    + "|"
                    + world.value()
                    + "|"
                    + a.owner().key()
                    + "|"
                    + a.role()
                    + "|"
                    + a.currency()));
    }
    var registered =
        new JournalEngine.Commit(
            facts,
            posted.result(),
            tx -> {
              if (createParty)
                tx.execute(
                    "INSERT INTO institution_parties(world_id,institution_key,actor_id,display_name,base_currency,tier,enabled) VALUES(?,?,?,'Corporate credit claims',?,'INTERNAL',1)",
                    List.of(
                        world.value().toString(),
                        borrower.key(),
                        actorKey,
                        acct.currency().code()));
              for (var a : accounts) FinancialAccounts.register(tx, a);
              posted.projections().apply(tx);
            });
    return record(
        intent,
        r,
        profile.identifier(),
        new Line(
            r.network(),
            line.limit(),
            principal,
            interest,
            line.carry(),
            line.lastDay(),
            Math.incrementExact(line.revision())),
        registered);
  }

  private static void leg(List<PostingBatch.Leg> legs, FinancialAccounts.Account a, long amount) {
    if (amount != 0)
      legs.add(
          new PostingBatch.Leg(
              a.id(),
              a.role().spendable()
                  ? PostingBatch.Role.INSTITUTION_CASH
                  : PostingBatch.Role.INSTITUTION_CLAIM,
              a.currency(),
              new Money(a.currency(), amount)));
  }

  private static JournalEngine.Commit record(
      Journal.Intent intent, Request r, String slug, Line next, JournalEngine.Commit financial) {
    String account = r.account().value().toString(), company = r.company().toString();
    var records = new ArrayList<>(financial.records());
    records.add(
        new Journal.Record(
            FACT,
            1,
            String.join(
                "|",
                r.action().name(),
                company,
                account,
                r.network().name(),
                Long.toString(next.limit()),
                Long.toString(next.principal()),
                Long.toString(next.interest()),
                Long.toString(next.carry()),
                Long.toString(next.lastDay()),
                Long.toString(next.revision()))));
    return new JournalEngine.Commit(
        records,
        new Journal.Result(1, "CORPORATE_CREDIT_" + r.action().name(), account),
        tx -> {
          financial.projections().apply(tx);
          if (r.action() == Action.APPLY) {
            tx.execute(
                "INSERT INTO corporate_credit_lines(account_id,company,network,limit_minor,principal,interest,carry,last_day,revision,active,opened_operation) VALUES(?,?,?,?,0,0,0,?,?,1,?) ON CONFLICT(account_id) DO UPDATE SET company=excluded.company,network=excluded.network,limit_minor=excluded.limit_minor,principal=0,interest=0,carry=0,last_day=excluded.last_day,revision=excluded.revision,active=1,opened_operation=excluded.opened_operation WHERE active=0",
                List.of(
                    account,
                    slug,
                    r.network().name(),
                    Long.toString(next.limit()),
                    Long.toString(next.lastDay()),
                    Long.toString(next.revision()),
                    intent.operationId().value().toString()));
            tx.execute(
                "INSERT INTO corporation_claims(company,domain,claim_id,kind) VALUES(?,'economy:corporate-credit',?,'HOLD')",
                List.of(company, account));
          } else {
            if (tx.execute(
                    "UPDATE corporate_credit_lines SET principal=?,interest=?,carry=?,last_day=?,revision=?,active=? WHERE account_id=? AND active=1 AND revision=?",
                    List.of(
                        Long.toString(next.principal()),
                        Long.toString(next.interest()),
                        Long.toString(next.carry()),
                        Long.toString(next.lastDay()),
                        Long.toString(next.revision()),
                        r.action() == Action.CLOSE ? "0" : "1",
                        account,
                        Long.toString(r.lineRevision())))
                != 1) throw new IllegalStateException("Corporate credit changed");
            if (r.action() == Action.CLOSE)
              tx.execute(
                  "DELETE FROM corporation_claims WHERE company=? AND domain='economy:corporate-credit' AND claim_id=?",
                  List.of(company, account));
          }
        });
  }

  public static void validateFact(String payload) {
    var fields = payload.split("\\|", -1);
    if (fields.length == 5 && fields[0].equals("PARTY")) {
      var world = new WorldId(UUID.fromString(fields[1]));
      var owner = new InstitutionId("economy:corporate-borrower/" + UUID.fromString(fields[2]));
      EconomyCurrency.valueOf(fields[4]);
      if (!new SystemActor(world, owner).actorId().value().toString().equals(fields[3]))
        throw new IllegalArgumentException("Corporate credit accounting party");
      return;
    }
    if (fields.length != 10) throw new IllegalArgumentException("Corporate credit fact");
    Action.valueOf(fields[0]);
    UUID.fromString(fields[1]);
    UUID.fromString(fields[2]);
    Network.valueOf(fields[3]);
    long limit = Long.parseLong(fields[4]),
        principal = Long.parseLong(fields[5]),
        interest = Long.parseLong(fields[6]),
        carry = Long.parseLong(fields[7]);
    if (limit < 1
        || principal < 0
        || principal > limit
        || interest < 0
        || carry < 0
        || carry >= DENOMINATOR
        || Long.parseLong(fields[8]) < 0
        || Long.parseLong(fields[9]) < 1)
      throw new IllegalArgumentException("Corporate credit balance");
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }
}
