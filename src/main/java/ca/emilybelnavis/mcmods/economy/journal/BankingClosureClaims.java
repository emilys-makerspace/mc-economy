package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.AccountIdentity;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import java.util.List;
import java.util.Optional;

/** Built-in obligations checked in the same worker transaction as account closure. */
public final class BankingClosureClaims {
  private BankingClosureClaims() {}

  public static Optional<FundedClosure.Claims> read(
      JournalEngine.ReadView reads, AccountIdentity account) {
    if (!(account.owner().owner() instanceof PartyRef.Player player)) return Optional.empty();
    String id = account.id().value().toString(), owner = Journal.actorKey(player.id());
    boolean pending = ca.emilybelnavis.mcmods.economy.autopay.Autopay.linked(reads, account.id());
    pending |=
        !reads
            .query(
                """
                SELECT id FROM payment_invoices
                WHERE (source=? OR target=?) AND state IN ('OPEN','AUTHORIZED','CAPTURED') LIMIT 1
                """,
                List.of(id, id),
                1)
            .isEmpty();
    // Cash previews encode their account binding. Owner-wide checks conservatively protect all
    // outstanding deliveries and returns, without parsing an unbounded set of encoded previews.
    pending |=
        !reads
            .query(
                "SELECT id FROM deposits WHERE owner=? AND state IN ('RESERVED','SAVED','QUARANTINED') LIMIT 1",
                List.of(owner),
                1)
            .isEmpty();
    pending |=
        !reads
            .query(
                "SELECT id FROM withdrawals WHERE owner=? AND state IN ('RESERVED','QUARANTINED') LIMIT 1",
                List.of(owner),
                1)
            .isEmpty();
    pending |=
        !reads
            .query(
                "SELECT id FROM custody_cases WHERE owner=? AND state NOT IN ('DELIVERED','COMPENSATED') LIMIT 1",
                List.of(owner),
                1)
            .isEmpty();
    pending |=
        !reads
            .query(
                "SELECT id FROM register_sales WHERE owner=? AND state IN ('RESERVED','SAVED','QUARANTINED') LIMIT 1",
                List.of(owner),
                1)
            .isEmpty();
    pending |=
        !reads
            .query(
                """
                SELECT r.deposit_id FROM deposit_returns r JOIN deposits d ON d.id=r.deposit_id
                WHERE d.owner=? AND r.state NOT IN ('DELIVERED','CLAIMED') LIMIT 1
                """,
                List.of(owner),
                1)
            .isEmpty();
    // Schema 17 has no separate restricted-funds projection (see LedgerService.bankFunds).
    return Optional.of(new FundedClosure.Claims(false, pending));
  }
}
