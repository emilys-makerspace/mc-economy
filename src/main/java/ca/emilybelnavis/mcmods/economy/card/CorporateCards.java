package ca.emilybelnavis.mcmods.economy.card;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.InstitutionId;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.util.*;

/**
 * Corporate debit credentials refer to the live account; holding an item confers no office or
 * payment rights.
 */
public final class CorporateCards {
  private CorporateCards() {}

  public static List<String> schema() {
    return List.of(
        "CREATE TABLE corporate_cards(token TEXT PRIMARY KEY NOT NULL,world TEXT NOT NULL,company TEXT NOT NULL REFERENCES corporation_registry(identifier) ON UPDATE CASCADE,account TEXT NOT NULL REFERENCES account_catalog(account_id),bank TEXT NOT NULL,network TEXT NOT NULL)",
        "CREATE UNIQUE INDEX corporate_card_debit ON corporate_cards(account) WHERE network=''");
  }

  public record Account(UUID company, AccountId account, InstitutionId bank, String network) {
    public String design() {
      return item(bank, network);
    }
  }

  private static String item(InstitutionId bank, String network) {
    return network.isEmpty()
        ? CardDesigns.corporateDebitId(bank)
        : CardDesigns.corporateId(
            CreditCardDesigns.id(
                bank,
                network,
                ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.require(bank).tier1()
                    ? 1
                    : 2));
  }

  public static boolean isDesign(String id) {
    if (!id.startsWith("economy:issued_corporate_")) return false;
    String personal = id.replace("economy:issued_corporate_", "economy:issued_");
    return CardDesigns.byId(personal).isPresent() || CreditCardDesigns.byId(personal).isPresent();
  }

  public static Optional<Account> resolve(
      JournalEngine.ReadView reads, WorldId world, UUID token, PlayerId actor) {
    var rows =
        reads.query(
            """
        SELECT r.party AS company,c.account,c.bank,c.network FROM corporate_cards c
        JOIN corporation_registry r ON r.identifier=c.company
        JOIN account_catalog a ON a.account_id=c.account AND a.status='OPEN'
        JOIN bank_account_providers p ON p.account_id=c.account AND p.bank_key=c.bank
        JOIN corporations o ON o.id=r.party AND o.state<>'DISSOLVED'
        WHERE c.world=? AND c.token=? AND (c.network='' OR EXISTS(
          SELECT 1 FROM corporate_credit_lines l WHERE l.account_id=c.account AND l.active=1 AND l.opened_operation=c.token))
        AND (o.founder=? OR EXISTS(
          SELECT 1 FROM corporation_managers m WHERE m.company=r.party AND m.manager=? AND m.active=1))
        """,
            List.of(
                world.value().toString(),
                token.toString(),
                actor.value().toString(),
                actor.value().toString()),
            1);
    if (rows.isEmpty()) return Optional.empty();
    var row = rows.getFirst();
    return Optional.of(
        new Account(
            UUID.fromString(row.get("company")),
            new AccountId(UUID.fromString(row.get("account"))),
            new InstitutionId(row.get("bank")),
            row.get("network")));
  }

  public static final String BINDING = "economy:corporate-card-binding";

  private static Journal.Record binding(
      UUID token,
      WorldId world,
      UUID company,
      AccountId account,
      InstitutionId bank,
      String network) {
    return new Journal.Record(
        BINDING,
        1,
        String.join(
            "|",
            token.toString(),
            world.value().toString(),
            company.toString(),
            account.value().toString(),
            bank.key(),
            network));
  }

  public static void validateBinding(String payload) {
    var fields = payload.split("\\|", -1);
    if (fields.length != 6) throw new IllegalArgumentException("Corporate card binding");
    var bank = new InstitutionId(fields[4]);
    item(bank, fields[5]);
    if (!binding(
            UUID.fromString(fields[0]),
            new WorldId(UUID.fromString(fields[1])),
            UUID.fromString(fields[2]),
            new AccountId(UUID.fromString(fields[3])),
            bank,
            fields[5])
        .payload()
        .equals(payload)) throw new IllegalArgumentException("Corporate card identity");
  }

  public static JournalEngine.Decision issueExisting(
      Journal.Intent intent,
      JournalEngine.ReadView reads,
      WorldId world,
      UUID company,
      AccountId account,
      InstitutionId bank,
      PlayerId recipient) {
    if (hasDebit(reads, account))
      return new JournalEngine.Reject(new Journal.Result(1, "CORPORATE_DEBIT_ALREADY_ISSUED", ""));
    return issueCard(intent, reads, world, company, account, bank, "", recipient, null);
  }

  public static boolean hasDebit(JournalEngine.ReadView reads, AccountId account) {
    return !reads
        .query(
            "SELECT 1 FROM corporate_cards WHERE account=? AND network=''",
            List.of(account.value().toString()),
            1)
        .isEmpty();
  }

  public static JournalEngine.Commit issue(
      Journal.Intent intent,
      JournalEngine.ReadView reads,
      WorldId world,
      UUID company,
      AccountId account,
      InstitutionId bank,
      PlayerId recipient,
      JournalEngine.Commit opening) {
    return issueCard(intent, reads, world, company, account, bank, "", recipient, opening);
  }

  public static JournalEngine.Commit issueCredit(
      Journal.Intent intent,
      JournalEngine.ReadView reads,
      WorldId world,
      UUID company,
      AccountId account,
      InstitutionId bank,
      String network,
      PlayerId recipient,
      JournalEngine.Commit opened) {
    if (network.isBlank()) throw new IllegalArgumentException("Credit network required");
    return issueCard(intent, reads, world, company, account, bank, network, recipient, opened);
  }

  private static JournalEngine.Commit issueCard(
      Journal.Intent intent,
      JournalEngine.ReadView reads,
      WorldId world,
      UUID company,
      AccountId account,
      InstitutionId bank,
      String network,
      PlayerId recipient,
      JournalEngine.Commit opening) {
    UUID token = intent.operationId().value();
    String items = "issued-card-v1\n" + world.value() + "|" + token + "|" + item(bank, network);
    var delivery =
        Custody.committedDelivery(
            intent,
            token,
            recipient,
            items,
            "issued-card:" + token,
            Journal.actorKey(recipient),
            reads);
    var facts = new ArrayList<Journal.Record>();
    if (opening != null) facts.addAll(opening.records());
    facts.add(binding(token, world, company, account, bank, network));
    facts.addAll(delivery.records());
    var result =
        opening == null
            ? new Journal.Result(1, "CORPORATE_DEBIT_ISSUED", account.value().toString())
            : opening.result();
    return new JournalEngine.Commit(
        facts,
        result,
        tx -> {
          if (opening != null) opening.projections().apply(tx);
          tx.execute(
              "INSERT INTO corporate_cards(token,world,company,account,bank,network) VALUES(?,?,(SELECT identifier FROM corporation_registry WHERE party=?),?,?,?)",
              List.of(
                  token.toString(),
                  world.value().toString(),
                  company.toString(),
                  account.value().toString(),
                  bank.key(),
                  network));
          delivery.projections().apply(tx);
        });
  }
}
