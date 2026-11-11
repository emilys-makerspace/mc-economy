package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import java.util.*;

/** Composes frozen cash delivery and quantity protection with the withdrawal debit. */
public final class WithdrawalDelivery {
  private WithdrawalDelivery() {}

  public static JournalEngine.Commit prepare(
      Journal.Intent intent,
      Withdrawals.Reservation reservation,
      List<CashIssuance.Lot> lots,
      JournalEngine.ReadView reads) {
    var owner = reservation.owner();
    var base =
        Custody.committedDelivery(
            intent,
            reservation.id(),
            owner,
            items(lots),
            "withdrawal:" + reservation.id(),
            Journal.actorKey(owner),
            reads);
    return new JournalEngine.Commit(
        base.records(),
        base.result(),
        tx -> {
          base.projections().apply(tx);
          for (var lot : lots)
            tx.execute(
                "INSERT INTO custody_cash_reservations VALUES(?,?,?,'ACTIVE')",
                List.of(
                    reservation.id().toString(),
                    lot.id().toString(),
                    Integer.toString(lot.issued())));
        });
  }

  public static String items(List<CashIssuance.Lot> lots) {
    if (lots.isEmpty() || lots.size() > 24)
      throw new IllegalArgumentException("WITHDRAW_DELIVERY_COUNT");
    var lines = new ArrayList<String>();
    lines.add("withdrawal-cash-v1|" + lots.getFirst().world().value());
    lots.stream()
        .sorted(Comparator.comparing(CashIssuance.Lot::item))
        .forEach(lot -> lines.add(lot.item() + "|" + lot.id() + "|" + lot.issued()));
    return String.join("\n", lines);
  }

  /** Decode only the server's committed custody payload, with bounded stack quantities. */
  public static List<CashIssuance.Claim> claims(Custody.Case delivery) {
    var lines = delivery.terms().items().split("\n", -1);
    if (lines.length < 2 || lines.length > 25)
      throw new IllegalArgumentException("WITHDRAW_DELIVERY_COUNT");
    var header = lines[0].split("\\|", -1);
    if (header.length != 2 || !header[0].equals("withdrawal-cash-v1"))
      throw new IllegalArgumentException("WITHDRAW_DELIVERY_FORMAT");
    var world = new WorldId(UUID.fromString(header[1]));
    var result = new ArrayList<CashIssuance.Claim>();
    var ids = new HashSet<UUID>();
    for (int index = 1; index < lines.length; index++) {
      var fields = lines[index].split("\\|", -1);
      if (fields.length != 3) throw new IllegalArgumentException("WITHDRAW_DELIVERY_FORMAT");
      var lot = UUID.fromString(fields[1]);
      if (!ids.add(lot)) throw new IllegalArgumentException("WITHDRAW_DELIVERY_DUPLICATE");
      int count = Integer.parseInt(fields[2]);
      if (count < 1 || count > 2304)
        throw new IllegalArgumentException("WITHDRAW_DELIVERY_QUANTITY");
      while (count > 0) {
        int stack = Math.min(count, 64);
        result.add(new CashIssuance.Claim(world, lot, fields[0], stack));
        count -= stack;
      }
    }
    if (result.size() > 36) throw new IllegalArgumentException("WITHDRAW_DELIVERY_STACKS");
    return List.copyOf(result);
  }
}
