package ca.emilybelnavis.mcmods.economy.card;

import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.sql.*;
import java.util.List;

/**
 * V23: immutable token identities, authoritative lifecycle status and one active debit token per
 * account.
 */
public final class CardSchema {
  private CardSchema() {}

  private static final String TABLE =
      "CREATE TABLE issued_cards(world_id TEXT NOT NULL,token_id TEXT NOT NULL,owner_id TEXT NOT NULL,issuer TEXT NOT NULL,account_id TEXT NOT NULL,product TEXT NOT NULL CHECK(product IN('DEBIT','CREDIT')),network TEXT NOT NULL,tier INTEGER NOT NULL CHECK(tier IN(1,2)),provider_epoch INTEGER NOT NULL CHECK(provider_epoch>=0),status TEXT NOT NULL CHECK(status IN('ACTIVE','REVOKED','EXPIRED')),issued_at INTEGER NOT NULL CHECK(issued_at>=0),expires_at INTEGER NOT NULL CHECK(expires_at>issued_at),revision INTEGER NOT NULL CHECK(revision>0),previous_token TEXT NOT NULL,custody_id TEXT NOT NULL,PRIMARY KEY(world_id,token_id))";
  private static final String ACTIVE =
      "CREATE UNIQUE INDEX issued_cards_active ON issued_cards(world_id,account_id,product) WHERE status='ACTIVE'";
  private static final String ACCOUNT =
      "CREATE INDEX issued_cards_account ON issued_cards(world_id,owner_id,account_id)";
  private static final String FEES =
      "CREATE TABLE issued_card_fee_quotes(quote_id TEXT PRIMARY KEY,operation_id TEXT NOT NULL)";

  public static List<String> statements() {
    return List.of(TABLE, ACTIVE, ACCOUNT, FEES);
  }

  public static void validate(Connection connection) throws SQLException {
    for (var expected : statements()) {
      String name =
          expected.equals(TABLE)
              ? "issued_cards"
              : expected.equals(FEES)
                  ? "issued_card_fee_quotes"
                  : expected.equals(ACTIVE) ? "issued_cards_active" : "issued_cards_account";
      try (var query = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
        query.setString(1, name);
        try (var row = query.executeQuery()) {
          if (!row.next() || !expected.equals(row.getString(1)))
            throw new StorageException(
                StorageException.Kind.SCHEMA, "Missing or changed issued-card schema");
        }
      }
    }
  }
}
