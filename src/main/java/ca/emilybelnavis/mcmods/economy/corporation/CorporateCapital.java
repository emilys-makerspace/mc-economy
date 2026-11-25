package ca.emilybelnavis.mcmods.economy.corporation;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.util.*;

/** Registration and paid-in capital are independent of the immutable accounting party. */
public final class CorporateCapital {
  public static final String FACT = "economy:corporations/capital";

  private CorporateCapital() {}

  public record ShareClass(String code, long quantity, Money price, Money total) {}

  public record Profile(
      String identifier, List<ShareClass> shares, Money required, boolean funded) {
    public Profile {
      shares = List.copyOf(shares);
    }
  }

  public static Optional<Profile> profile(JournalEngine.ReadView reads, UUID party) {
    var rows =
        reads.query(
            """
        SELECT r.identifier,r.funded,s.class,s.quantity,s.price_minor,s.currency
        FROM corporation_registry r LEFT JOIN corporation_shares s ON s.company=r.identifier
        WHERE r.party=? ORDER BY s.class
        """,
            List.of(party.toString()),
            32);
    if (rows.isEmpty()) return Optional.empty();
    var classes = new ArrayList<ShareClass>();
    Money required = null;
    for (var row : rows) {
      if (row.get("class") == null) continue;
      var price =
          new Money(
              EconomyCurrency.fromCode(row.get("currency")),
              Long.parseLong(row.get("price_minor")));
      long quantity = Long.parseLong(row.get("quantity"));
      var total = new Corporations.ShareIssue(quantity, price).total();
      classes.add(new ShareClass(row.get("class"), quantity, price, total));
      if (row.get("class").equals("A")) required = total;
    }
    var first = rows.getFirst();
    return Optional.of(
        new Profile(first.get("identifier"), classes, required, "1".equals(first.get("funded"))));
  }

  public static Journal.Record issueFact(UUID party, Corporations.ShareIssue issue) {
    return new Journal.Record(
        FACT,
        1,
        "ISSUE|"
            + party
            + "|A|"
            + issue.quantity()
            + "|"
            + issue.price().currency().code()
            + "|"
            + issue.price().minorUnits());
  }

  static void writeIssue(
      FoundationStore.Transaction tx, String identifier, Corporations.ShareIssue issue)
      throws Exception {
    tx.execute(
        "INSERT INTO corporation_shares(company,class,quantity,price_minor,currency) VALUES(?,'A',?,?,?)",
        List.of(
            identifier,
            Long.toString(issue.quantity()),
            Long.toString(issue.price().minorUnits()),
            issue.price().currency().code()));
  }

  static JournalEngine.Decision issue(
      JournalEngine.ReadView reads,
      Identifiers.WorldId world,
      PlayerId actor,
      Corporations.Issue command) {
    var company = Corporations.find(reads, actor, command.id()).orElse(null);
    if (company == null || company.state() != Corporations.State.ACTIVE)
      return reject("CORPORATION_UNAVAILABLE");
    if (company.revision() != command.revision()) return reject("CORPORATION_STALE");
    var profile = profile(reads, company.id()).orElseThrow();
    if (profile.required() != null) return reject("CORPORATION_SHARES_ALREADY_ISSUED");
    var accounts = CorporateAccounts.list(reads, world, company.partyId());
    if (accounts.size() >= AccountIdentity.MAX_OPEN_BANK_ACCOUNTS
        && accounts.stream().noneMatch(a -> a.currency() == command.issue().price().currency()))
      return reject("CORPORATION_CHOOSE_EXISTING_ACCOUNT_CURRENCY");
    return new JournalEngine.Commit(
        List.of(issueFact(company.id(), command.issue())),
        new Journal.Result(1, "CORPORATION_SHARES_ISSUED", profile.identifier()),
        tx -> writeIssue(tx, profile.identifier(), command.issue()));
  }

  /** Called during preparation of the actual contribution, with its final destination credit. */
  public static JournalEngine.Decision fund(
      JournalEngine.ReadView reads, UUID party, Money credited, JournalEngine.Commit transfer) {
    var capital = profile(reads, party).orElse(null);
    if (capital == null || capital.required() == null)
      return reject("CORPORATION_ISSUE_SHARES_FIRST");
    if (capital.funded()) return transfer;
    if (!capital.required().equals(credited)) return reject("CORPORATION_CAPITAL_AMOUNT");
    var records = new ArrayList<>(transfer.records());
    records.add(
        new Journal.Record(
            FACT,
            1,
            "FUND|" + party + "|" + credited.currency().code() + "|" + credited.minorUnits()));
    return new JournalEngine.Commit(
        records,
        transfer.result(),
        tx -> {
          transfer.projections().apply(tx);
          if (tx.execute(
                  "UPDATE corporation_registry SET funded=1 WHERE party=? AND funded=0",
                  List.of(party.toString()))
              != 1) throw new IllegalStateException("Corporate capitalization changed");
        });
  }

  public static void validateFact(String value) {
    var f = value.split("\\|", -1);
    if (f.length == 6 && f[0].equals("ISSUE") && f[2].equals("A")) {
      var issue =
          new Corporations.ShareIssue(
              Long.parseLong(f[3]),
              new Money(EconomyCurrency.fromCode(f[4]), Long.parseLong(f[5])));
      if (issueFact(UUID.fromString(f[1]), issue).payload().equals(value)) return;
    } else if (f.length == 4 && f[0].equals("FUND")) {
      var party = UUID.fromString(f[1]);
      var amount = new Money(EconomyCurrency.fromCode(f[2]), Long.parseLong(f[3]));
      if (amount.minorUnits() > 0
          && ("FUND|" + party + "|" + amount.currency().code() + "|" + amount.minorUnits())
              .equals(value)) return;
    }
    throw new IllegalArgumentException("Invalid corporate capital record");
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }
}
