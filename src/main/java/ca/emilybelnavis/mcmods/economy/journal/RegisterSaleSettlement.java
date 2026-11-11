package ca.emilybelnavis.mcmods.economy.journal;

import java.time.Clock;
import java.util.*;

/**
 * Register checkout and original-stock recovery compose with the trusted payment/custody
 * mechanisms.
 */
public final class RegisterSaleSettlement {
  public static final String RETURN = "economy:register_restock";

  private RegisterSaleSettlement() {}

  public static JournalEngine.Decision checkout(
      Journal.Intent intent, RegisterSales.Reservation sale, JournalEngine.ReadView reads)
      throws Exception {
    if (intent.request().fields().containsKey("credit_request"))
      return new JournalEngine.Reject(new Journal.Result(1, "SALE_CREDIT_COMPOSER_REQUIRED", ""));
    var quote = sale.quote();
    var payment =
        MerchantPayments.checkout(
            intent,
            sale.id(),
            quote.price().owner(),
            quote.buyerAccount(),
            quote.price().sellerAccount(),
            quote.price().amount(),
            reads,
            Clock.systemUTC());
    if (!(payment instanceof JournalEngine.Commit captured)) return payment;
    var custody =
        Custody.committedDelivery(
            intent,
            sale.id(),
            sale.buyer(),
            items(sale),
            location(sale),
            Journal.actorKey(sale.buyer()),
            reads);
    var records = new ArrayList<>(captured.records());
    records.addAll(custody.records());
    return new JournalEngine.Commit(
        records,
        captured.result(),
        tx -> {
          captured.projections().apply(tx);
          custody.projections().apply(tx);
        });
  }

  public static JournalEngine.Commit returnOriginals(
      Journal.Intent intent,
      RegisterSales.Reservation sale,
      boolean uncertain,
      JournalEngine.ReadView reads,
      RegisterSales.SourceReader sources) {
    if (!uncertain && intent.request().fields().get("evidence").equals("restocked")) {
      var current =
          RegisterSales.source(
              reads,
              sale.quote().price().world(),
              RegisterSales.below(sale.quote().price().anchor()),
              sale.quote().price().template(),
              sources);
      if (sale.savedSource() == null
          || !current.generation().equals(sale.savedSource().generation())
          || current.revision() <= sale.savedSource().revision()) return null;
      return new JournalEngine.Commit(
          List.of(
              new Journal.Record(
                  RETURN,
                  1,
                  sale.id()
                      + "|"
                      + current.generation()
                      + "|"
                      + current.revision()
                      + "|"
                      + current.fingerprint())),
          new Journal.Result(1, "SALE_RESTOCKED", sale.id().toString()),
          tx ->
              RegisterSales.observe(
                  tx,
                  sale.quote().price().world(),
                  RegisterSales.below(sale.quote().price().anchor()),
                  current));
    }
    return returnOriginals(intent, sale, uncertain, reads);
  }

  public static void validateReturn(String payload) {
    var fields = payload.split("[|]", -1);
    if (fields.length != 4 || fields[3].isBlank() || fields[3].length() > 128)
      throw new IllegalArgumentException("Register restock fact");
    UUID.fromString(fields[0]);
    UUID.fromString(fields[1]);
    if (Long.parseLong(fields[2]) < 1)
      throw new IllegalArgumentException("Register source revision");
  }

  public static JournalEngine.Commit returnOriginals(
      Journal.Intent intent,
      RegisterSales.Reservation sale,
      boolean uncertain,
      JournalEngine.ReadView reads) {
    var id =
        UUID.nameUUIDFromBytes(
            ("economy:register-return:" + sale.id())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var evidence =
        new Custody.Evidence(
            uncertain ? Custody.EvidenceKind.UNKNOWN : Custody.EvidenceKind.UNDELIVERED,
            intent.request().fields().get("evidence"),
            location(sale),
            intent.request().fields().get("evidence"));
    var custody =
        Custody.returnCase(
            intent,
            id,
            sale.id(),
            sale.quote().price().owner(),
            items(sale),
            location(sale),
            evidence,
            intent.request().fields().get("reason"),
            uncertain ? Custody.State.QUARANTINED : Custody.State.CLAIM,
            reads);
    if (custody == null) return null;
    return new JournalEngine.Commit(
        custody.records(),
        custody.result(),
        tx -> {
          custody.projections().apply(tx);
          tx.execute(
              "UPDATE register_stock_locks SET return_custody_id=? WHERE sale_id=?",
              List.of(id.toString(), sale.id().toString()));
        });
  }

  public static String items(RegisterSales.Reservation sale) {
    return "register-goods-v1|"
        + sale.quote().price().batch()
        + "\n"
        + sale.quote().price().template();
  }

  private static String location(RegisterSales.Reservation sale) {
    return "register-stock:" + sale.quote().source().generation() + ":" + sale.id();
  }
}
