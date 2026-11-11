package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Worker-confined register pricing, original stock reservation and paid delivery composition. */
public final class RegisterSales {
  public static final String FACT = "economy:register_sale";
  public static final String CURRENCY_GUIDANCE =
      "Checkout needs matching buyer and seller primary currencies. Use a compatible register or ask an operator to resolve the primary-currency mismatch.";
  private static final SystemActorId RECOVERY =
      new SystemActorId(UUID.fromString("bbec567a-4847-473d-8709-b6c68dc3200e"));

  public enum Action {
    CONFIGURE,
    RESERVE,
    SAVED,
    COMMIT,
    CANCEL,
    QUARANTINE;

    public Kind kind() {
      return new Kind("economy:register_sales", "economy:" + name().toLowerCase(Locale.ROOT));
    }
  }

  public enum State {
    RESERVED,
    SAVED,
    COMMITTED,
    CANCELLED,
    QUARANTINED
  }

  public record Price(
      WorldId world,
      TerminalPolicy.Anchor anchor,
      UUID generation,
      long terminalRevision,
      PlayerId owner,
      UUID sellerAccount,
      long sellerAccountRevision,
      EconomyCurrency currency,
      long revision,
      String template,
      int batch,
      Money amount) {
    public Price {
      Objects.requireNonNull(world);
      Objects.requireNonNull(anchor);
      Objects.requireNonNull(generation);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(sellerAccount);
      Objects.requireNonNull(currency);
      Objects.requireNonNull(amount);
      text(template, 2048);
      if (terminalRevision < 1
          || sellerAccountRevision < 1
          || revision < 1
          || batch < 1
          || batch > 64
          || amount.currency() != currency
          || amount.minorUnits() <= 0
          || amount.minorUnits() % currency.minorUnitsPerMajor() != 0) throw invalid("SALE_PRICE");
    }
  }

  public record Source(
      UUID generation,
      long revision,
      int quantity,
      String template,
      boolean available,
      String fingerprint) {
    public Source {
      Objects.requireNonNull(generation);
      text(template, 2048);
      text(fingerprint, 128);
      if (revision < 1 || quantity < 0 || quantity > 4096) throw invalid("SALE_SOURCE");
    }

    public Source(
        UUID generation, long revision, int quantity, String template, boolean available) {
      this(
          generation,
          revision,
          quantity,
          template,
          available,
          RegisterSales.fingerprint(
              generation + "|" + revision + "|" + quantity + "|" + template + "|" + available));
    }
  }

  public record Quote(
      Price price, PlayerId buyer, UUID buyerAccount, long buyerAccountRevision, Source source) {
    public Quote {
      Objects.requireNonNull(price);
      Objects.requireNonNull(buyer);
      Objects.requireNonNull(buyerAccount);
      Objects.requireNonNull(source);
      if (buyerAccountRevision < 1 || buyer.equals(price.owner())) throw invalid("SALE_SELF");
    }
  }

  public record Reservation(
      UUID id,
      PlayerId buyer,
      State state,
      Quote quote,
      Source savedSource,
      String evidence,
      String reason,
      long revision) {}

  @FunctionalInterface
  public interface Authority {
    boolean allows(Action action, Intent intent, Reservation current, ReadView reads);
  }

  @FunctionalInterface
  public interface Operators {
    boolean allows(ActorId actor, ReadView reads);
  }

  @FunctionalInterface
  public interface Capacity {
    boolean fits(Action action, Intent intent, Reservation current, ReadView reads);
  }

  @FunctionalInterface
  public interface SourceReader {
    Source current(TerminalPolicy.Anchor below, String template, ReadView reads);
  }

  @FunctionalInterface
  public interface EvidenceVerifier {
    boolean verifies(
        Action action, Intent intent, Reservation current, String evidence, ReadView reads);
  }

  @FunctionalInterface
  public interface SettlementComposer {
    Decision prepare(Intent intent, Reservation current, ReadView reads) throws Exception;
  }

  @FunctionalInterface
  public interface ReturnComposer {
    Commit prepare(Intent intent, Reservation current, boolean uncertain, ReadView reads)
        throws Exception;
  }

  private RegisterSales() {}

  public static TerminalPolicy.Anchor below(TerminalPolicy.Anchor anchor) {
    return new TerminalPolicy.Anchor(
        anchor.dimension(), anchor.x(), Math.subtractExact(anchor.y(), 1), anchor.z());
  }

  public static CanonicalRequest configureRequest(
      TerminalPolicy.Anchor anchor,
      UUID generation,
      long terminalRevision,
      long expectedPriceRevision,
      String template,
      int batch,
      Money price) {
    Objects.requireNonNull(generation);
    Objects.requireNonNull(price);
    text(template, 2048);
    if (terminalRevision < 1
        || expectedPriceRevision < 0
        || batch < 1
        || batch > 64
        || price.minorUnits() <= 0
        || price.minorUnits() % price.currency().minorUnitsPerMajor() != 0)
      throw invalid("SALE_PRICE");
    return new CanonicalRequest(
        1,
        Map.of(
            "anchor",
            encodeAnchor(anchor),
            "generation",
            generation.toString(),
            "terminal_revision",
            Long.toString(terminalRevision),
            "price_revision",
            Long.toString(expectedPriceRevision),
            "template",
            template,
            "batch",
            Integer.toString(batch),
            "currency",
            price.currency().name(),
            "units",
            Long.toString(price.minorUnits())));
  }

  public static Quote quote(
      ReadView reads,
      WorldId world,
      PlayerId buyer,
      TerminalPolicy.Anchor anchor,
      UUID generation,
      long terminalRevision,
      SourceReader sources) {
    return quote(reads, world, buyer, anchor, generation, terminalRevision, sources, false);
  }

  public static Quote quote(
      ReadView reads,
      WorldId world,
      PlayerId buyer,
      TerminalPolicy.Anchor anchor,
      UUID generation,
      long terminalRevision,
      SourceReader sources,
      boolean creditFunding) {
    var terminal = terminal(reads, world, anchor, generation, terminalRevision);
    var price = rawPrice(reads, world, anchor).orElseThrow(() -> invalid("SALE_UNCONFIGURED"));
    if (!price.generation().equals(generation)
        || price.terminalRevision() != terminalRevision
        || !price.owner().equals(terminal.owner())) throw invalid("SALE_PRICE_STALE");
    var seller = primary(reads, price.owner());
    if (!seller.id().equals(price.sellerAccount())
        || seller.revision() != price.sellerAccountRevision()
        || seller.currency() != price.currency()) throw invalid("SALE_SELLER_STALE");
    var account = primary(reads, buyer);
    if (!creditFunding && account.currency() != price.currency())
      throw invalid("SALE_CURRENCY_MISMATCH");
    var source = source(reads, world, below(anchor), price.template(), sources);
    long pending =
        Long.parseLong(
            reads
                .query(
                    """
                SELECT COALESCE(SUM(count),0) AS total FROM register_stock_locks
                WHERE world_id=? AND source_generation=? AND state IN ('RESERVED','QUARANTINED','RETURN_HELD')
                """,
                    List.of(world.value().toString(), source.generation().toString()),
                    1)
                .getFirst()
                .get("total"));
    if (source.quantity() - pending < price.batch()) throw invalid("SALE_STOCK");
    var result = new Quote(price, buyer, account.id(), account.revision(), source);
    var validation = new UUID(0, 0);
    new Intent(
        new OperationId(validation),
        new RequestId(validation),
        buyer,
        Action.RESERVE.kind(),
        reserveRequest(result),
        null,
        null);
    return result;
  }

  public static CanonicalRequest reserveRequest(Quote quote) {
    return new CanonicalRequest(1, Map.of("quote", encodeQuote(quote)));
  }

  public static CanonicalRequest reserveRequest(Quote quote, boolean creditFunding) {
    return creditFunding
        ? new CanonicalRequest(1, Map.of("quote", encodeQuote(quote), "credit_funding", "true"))
        : reserveRequest(quote);
  }

  /** Optional trusted funding binding is part of the original COMMIT identity. */
  public static CanonicalRequest creditRequest(
      UUID sale, String evidence, String reason, String credit) {
    if (credit == null || credit.isEmpty() || credit.length() > 2048)
      throw new IllegalArgumentException("SALE_CREDIT_BINDING");
    var fields = new TreeMap<>(request(sale, evidence, reason).fields());
    fields.put("credit_request", credit);
    return new CanonicalRequest(1, fields);
  }

  public static CanonicalRequest request(UUID sale, String evidence, String reason) {
    Objects.requireNonNull(sale);
    text(evidence, 256);
    text(reason, 256);
    return new CanonicalRequest(
        1, Map.of("sale", sale.toString(), "evidence", evidence, "reason", reason));
  }

  public static Registration registration(
      Action action,
      WorldId world,
      Authority authority,
      Operators operators,
      Capacity capacity,
      SourceReader sources,
      EvidenceVerifier verifier,
      SettlementComposer settlement,
      ReturnComposer returns) {
    Objects.requireNonNull(action);
    Objects.requireNonNull(world);
    Objects.requireNonNull(authority);
    Objects.requireNonNull(operators);
    Objects.requireNonNull(capacity);
    Objects.requireNonNull(sources);
    Objects.requireNonNull(verifier);
    Objects.requireNonNull(settlement);
    Objects.requireNonNull(returns);
    return new Registration(
        1,
        (intent, reads) ->
            prepare(
                action,
                world,
                authority,
                operators,
                capacity,
                sources,
                verifier,
                settlement,
                returns,
                intent,
                reads));
  }

  private static Decision prepare(
      Action action,
      WorldId world,
      Authority authority,
      Operators operators,
      Capacity capacity,
      SourceReader sources,
      EvidenceVerifier verifier,
      SettlementComposer settlement,
      ReturnComposer returns,
      Intent intent,
      ReadView reads)
      throws Exception {
    if (!intent.kind().equals(action.kind())) return reject("SALE_REQUEST");
    if (action == Action.CONFIGURE) return configure(world, authority, operators, intent, reads);
    if (action == Action.RESERVE) {
      Quote supplied;
      Quote actual;
      try {
        supplied = decodeQuote(intent.request().fields().get("quote"));
        if (!(intent.actor() instanceof PlayerId buyer) || !buyer.equals(supplied.buyer()))
          return decline("SALE_ACCESS");
        boolean creditFunding = "true".equals(intent.request().fields().get("credit_funding"));
        actual =
            quote(
                reads,
                world,
                buyer,
                supplied.price().anchor(),
                supplied.price().generation(),
                supplied.price().terminalRevision(),
                sources,
                creditFunding);
        if (!reserveRequest(actual, creditFunding).equals(intent.request()))
          return reject("SALE_QUOTE_STALE");
      } catch (IllegalArgumentException | ArithmeticException | NullPointerException malformed) {
        return rejected(malformed);
      }
      var current =
          new Reservation(
              intent.operationId().value(),
              actual.buyer(),
              State.RESERVED,
              actual,
              null,
              "pending",
              "stock reserved",
              1);
      if (!authority.allows(action, intent, current, reads)) return decline("SALE_ACCESS");
      if (!capacity.fits(action, intent, current, reads)) return decline("SALE_CAPACITY");
      return commit(action, current, null, true);
    }
    UUID id;
    String evidence;
    String reason;
    try {
      var fields = intent.request().fields();
      id = UUID.fromString(fields.get("sale"));
      evidence = fields.get("evidence");
      reason = fields.get("reason");
      var canonical =
          action == Action.COMMIT && fields.containsKey("credit_request")
              ? creditRequest(id, evidence, reason, fields.get("credit_request"))
              : request(id, evidence, reason);
      if (!canonical.equals(intent.request())) return reject("SALE_REQUEST");
    } catch (IllegalArgumentException | NullPointerException malformed) {
      return reject("SALE_REQUEST");
    }
    var current = raw(reads, id).orElse(null);
    if (current == null
        || (action != Action.QUARANTINE && !intent.actor().equals(current.buyer()))
        || !authority.allows(action, intent, current, reads)) return decline("SALE_ACCESS");
    if (current.state() != State.RESERVED && current.state() != State.SAVED)
      return reject("SALE_STATE");
    State next;
    Source saved = current.savedSource();
    Commit composed = null;
    if (action == Action.SAVED) {
      if (current.state() != State.RESERVED) return reject("SALE_STATE");
      if (!verifier.verifies(action, intent, current, evidence, reads))
        return decline("SALE_EVIDENCE");
      try {
        saved =
            source(
                reads,
                world,
                below(current.quote().price().anchor()),
                current.quote().price().template(),
                sources);
        var prior = current.quote().source();
        if (!saved.generation().equals(prior.generation())
            || saved.revision() != Math.addExact(prior.revision(), 1)
            || saved.quantity() != prior.quantity() - current.quote().price().batch())
          return reject("SALE_SOURCE_CHANGED");
      } catch (IllegalArgumentException | ArithmeticException invalid) {
        return rejected(invalid);
      }
      next = State.SAVED;
    } else if (action == Action.COMMIT) {
      if (current.state() != State.SAVED) return reject("SALE_STATE");
      try {
        validateCommit(
            reads,
            world,
            current,
            sources,
            intent.request().fields().containsKey("credit_request"));
      } catch (IllegalArgumentException | ArithmeticException stale) {
        return rejected(stale);
      }
      if (!verifier.verifies(action, intent, current, evidence, reads))
        return decline("SALE_EVIDENCE");
      if (!capacity.fits(action, intent, current, reads)) return decline("SALE_CAPACITY");
      var payment = settlement.prepare(intent, current, reads);
      if (payment == null) return decline("SALE_PAYMENT");
      if (!(payment instanceof Commit accepted)) {
        if (payment instanceof Reject rejection
            && rejection.result().code().equals("INSUFFICIENT_FUNDS"))
          return new Retry(rejection.result());
        return payment;
      }
      composed = accepted;
      next = State.COMMITTED;
    } else if (action == Action.CANCEL) {
      // For RESERVED, proof must establish that physical removal never occurred.
      if (!verifier.verifies(action, intent, current, evidence, reads))
        return decline("SALE_EVIDENCE");
      if (current.state() == State.SAVED) {
        composed = returns.prepare(intent, current, false, reads);
        if (composed == null) return decline("SALE_RETURN");
      }
      next = State.CANCELLED;
    } else if (action == Action.QUARANTINE) {
      composed = returns.prepare(intent, current, true, reads);
      if (composed == null) return decline("SALE_RETURN");
      next = State.QUARANTINED;
    } else return reject("SALE_REQUEST");
    var changed =
        new Reservation(
            id,
            current.buyer(),
            next,
            current.quote(),
            saved,
            evidence,
            reason,
            Math.addExact(current.revision(), 1));
    return commit(action, changed, composed, false);
  }

  private static Decision configure(
      WorldId world, Authority authority, Operators operators, Intent intent, ReadView reads) {
    try {
      var f = intent.request().fields();
      var anchor = decodeAnchor(f.get("anchor"));
      var generation = UUID.fromString(f.get("generation"));
      long terminalRevision = Long.parseLong(f.get("terminal_revision"));
      long expected = Long.parseLong(f.get("price_revision"));
      var template = f.get("template");
      int batch = Integer.parseInt(f.get("batch"));
      var amount =
          new Money(EconomyCurrency.valueOf(f.get("currency")), Long.parseLong(f.get("units")));
      if (!configureRequest(anchor, generation, terminalRevision, expected, template, batch, amount)
          .equals(intent.request())) return reject("SALE_REQUEST");
      var terminal = terminal(reads, world, anchor, generation, terminalRevision);
      if (!(terminal.owner() instanceof PlayerId owner)) return reject("SALE_OWNER");
      if (!owner.equals(intent.actor()) && !operators.allows(intent.actor(), reads))
        return decline("SALE_ACCESS");
      if (!authority.allows(Action.CONFIGURE, intent, null, reads)) return decline("SALE_ACCESS");
      var previous = rawPrice(reads, world, anchor).orElse(null);
      long activeRevision =
          previous != null && previous.generation().equals(generation) ? previous.revision() : 0;
      if (expected != activeRevision) return reject("SALE_PRICE_STALE");
      var primary = primary(reads, owner);
      if (primary.currency() != amount.currency()) return reject("SALE_CURRENCY_MISMATCH");
      long revision = previous == null ? 1 : Math.addExact(previous.revision(), 1);
      var price =
          new Price(
              world,
              anchor,
              generation,
              terminalRevision,
              owner,
              primary.id(),
              primary.revision(),
              amount.currency(),
              revision,
              template,
              batch,
              amount);
      return new Commit(
          List.of(new Journal.Record(FACT, 1, "CONFIGURE|" + pack(encodePrice(price)))),
          new Result(1, "SALE_CONFIGURE", Long.toString(revision)),
          tx ->
              tx.execute(
                  """
                    INSERT INTO register_prices VALUES(?,?,?,?,?,?,?,?)
                    ON CONFLICT(world_id,dimension,x,y,z) DO UPDATE SET
                    generation=excluded.generation,revision=excluded.revision,price=excluded.price
                    """,
                  List.of(
                      world.value().toString(),
                      anchor.dimension(),
                      Integer.toString(anchor.x()),
                      Integer.toString(anchor.y()),
                      Integer.toString(anchor.z()),
                      generation.toString(),
                      Long.toString(revision),
                      encodePrice(price))));
    } catch (IllegalArgumentException | ArithmeticException | NullPointerException malformed) {
      return rejected(malformed);
    }
  }

  private static void validateCommit(
      ReadView reads,
      WorldId world,
      Reservation sale,
      SourceReader sources,
      boolean creditFunding) {
    var quote = sale.quote();
    var price = quote.price();
    var terminal =
        terminal(reads, world, price.anchor(), price.generation(), price.terminalRevision());
    var current =
        rawPrice(reads, world, price.anchor()).orElseThrow(() -> invalid("SALE_PRICE_STALE"));
    if (!current.equals(price) || !terminal.owner().equals(price.owner()))
      throw invalid("SALE_PRICE_STALE");
    var seller = primary(reads, price.owner());
    var buyer = primary(reads, quote.buyer());
    if (!seller.id().equals(price.sellerAccount())
        || seller.revision() != price.sellerAccountRevision()) throw invalid("SALE_SELLER_STALE");
    if (!buyer.id().equals(quote.buyerAccount())
        || buyer.revision() != quote.buyerAccountRevision()) throw invalid("SALE_BUYER_STALE");
    if ((!creditFunding && buyer.currency() != seller.currency())
        || seller.currency() != price.currency()) throw invalid("SALE_CURRENCY_MISMATCH");
    var source = source(reads, world, below(price.anchor()), price.template(), sources);
    if (!source.equals(sale.savedSource())) throw invalid("SALE_SOURCE_CHANGED");
  }

  private static Commit commit(Action action, Reservation value, Commit base, boolean insert) {
    var records = new ArrayList<Journal.Record>();
    if (base != null) records.addAll(base.records());
    records.add(new Journal.Record(FACT, 1, action.name() + "|" + pack(encodeReservation(value))));
    return new Commit(
        records,
        new Result(1, "SALE_" + action.name(), value.id().toString()),
        tx -> {
          if (base != null) base.projections().apply(tx);
          if (insert) {
            tx.execute("INSERT INTO register_sales VALUES(?,?,?,?,?,?,?,?)", values(value));
            tx.execute(
                "INSERT INTO register_stock_locks(sale_id,world_id,source_generation,count,state) VALUES(?,?,?,?,'RESERVED')",
                List.of(
                    value.id().toString(), value.quote().price().world().value().toString(),
                    value.quote().source().generation().toString(),
                        Integer.toString(value.quote().price().batch())));
            observe(
                tx,
                value.quote().price().world(),
                below(value.quote().price().anchor()),
                value.quote().source());
          } else {
            int changed =
                tx.execute(
                    """
                        UPDATE register_sales SET state=?,quote=?,saved_source=?,evidence=?,reason=?,revision=?
                        WHERE id=? AND revision=?
                        """,
                    Arrays.asList(
                        value.state().name(),
                        encodeQuote(value.quote()),
                        value.savedSource() == null ? null : encodeSource(value.savedSource()),
                        value.evidence(),
                        value.reason(),
                        Long.toString(value.revision()),
                        value.id().toString(),
                        Long.toString(value.revision() - 1)));
            if (changed != 1) throw new IllegalStateException("Sale revision changed");
            tx.execute(
                """
                        UPDATE register_stock_locks SET state=CASE
                        WHEN ?='CANCELLED' AND return_custody_id IS NOT NULL THEN 'RETURN_HELD' ELSE ? END WHERE sale_id=?
                        """,
                List.of(
                    value.state().name(),
                    value.state() == State.SAVED
                        ? "SAVED"
                        : value.state() == State.QUARANTINED ? "QUARANTINED" : "RELEASED",
                    value.id().toString()));
            if (action == Action.SAVED)
              observe(
                  tx,
                  value.quote().price().world(),
                  below(value.quote().price().anchor()),
                  value.savedSource());
          }
        });
  }

  public static Optional<Reservation> find(ReadView reads, ActorId actor, UUID id) {
    return raw(reads, id)
        .filter(
            value -> value.buyer().equals(actor) || value.quote().price().owner().equals(actor));
  }

  public static Optional<Price> price(ReadView reads, WorldId world, TerminalPolicy.Anchor anchor) {
    return rawPrice(reads, world, anchor);
  }

  public static boolean reconcileBatch(FoundationStore store, ReturnComposer returns) {
    var rows =
        store.transaction(
            tx ->
                tx.query(
                    "SELECT id,revision FROM register_sales WHERE state IN ('RESERVED','SAVED') ORDER BY id LIMIT 16",
                    List.of(),
                    16));
    var action = Action.QUARANTINE;
    var engine =
        new JournalEngine(
            store,
            (actor, kind) -> actor.equals(RECOVERY) && kind.equals(action.kind()),
            Map.of(
                action.kind(),
                registration(
                    action,
                    store.snapshot().worldId(),
                    (a, intent, current, reads) -> intent.actor().equals(RECOVERY),
                    (actor, reads) -> false,
                    (a, intent, current, reads) -> false,
                    (anchor, template, reads) -> null,
                    (a, intent, current, evidence, reads) -> false,
                    (intent, current, reads) -> null,
                    returns)));
    for (var row : rows) {
      var id = UUID.fromString(row.get("id"));
      var operation =
          UUID.nameUUIDFromBytes(
              ("economy:sale-recovery:" + id + ":" + row.get("revision"))
                  .getBytes(StandardCharsets.UTF_8));
      var result =
          engine.submit(
              new Intent(
                  new OperationId(operation),
                  new RequestId(UUID.randomUUID()),
                  RECOVERY,
                  action.kind(),
                  request(
                      id, "startup-unknown", "reserved goods require independent reconciliation"),
                  null,
                  null));
      if (result.status() != Status.COMMITTED)
        throw new IllegalStateException("Sale reconciliation failed");
    }
    return store.transaction(
        tx ->
            tx.query(
                    "SELECT id FROM register_sales WHERE state IN ('RESERVED','SAVED') LIMIT 1",
                    List.of(),
                    1)
                .isEmpty());
  }

  private record Primary(UUID id, long revision, EconomyCurrency currency) {}

  private static Primary primary(ReadView reads, PlayerId owner) {
    var row =
        reads
            .query(
                """
                SELECT account_id,revision,currency FROM account_catalog
                WHERE owner_id=? AND purpose='BANK' AND status='OPEN' AND is_primary=1
                """,
                List.of(owner.value().toString()),
                1)
            .stream()
            .findFirst()
            .orElseThrow(() -> invalid("SALE_PRIMARY_MISSING"));
    return new Primary(
        UUID.fromString(row.get("account_id")),
        Long.parseLong(row.get("revision")),
        EconomyCurrency.valueOf(row.get("currency")));
  }

  private static TerminalPolicy.Snapshot terminal(
      ReadView reads, WorldId world, TerminalPolicy.Anchor anchor, UUID generation, long revision) {
    var terminal =
        TerminalPolicy.find(reads, world, anchor, RECOVERY, true)
            .orElseThrow(() -> invalid("SALE_TERMINAL"));
    if (!terminal.active()
        || terminal.kind() != TerminalPolicy.Kind.REGISTER
        || !terminal.generation().equals(generation)
        || terminal.revision() != revision) throw invalid("SALE_TERMINAL_STALE");
    return terminal;
  }

  static Source source(
      ReadView reads,
      WorldId world,
      TerminalPolicy.Anchor anchor,
      String template,
      SourceReader reader) {
    var value = reader.current(anchor, template, reads);
    if (value == null || !value.available()) throw invalid("SALE_SOURCE_UNAVAILABLE");
    if (!value.template().equals(template)) throw invalid("SALE_COMPONENT_MISMATCH");
    var rows =
        reads.query(
            "SELECT * FROM register_stock_sources WHERE world_id=? AND generation=?",
            List.of(world.value().toString(), value.generation().toString()),
            1);
    if (!rows.isEmpty()) {
      var row = rows.getFirst();
      if (!row.get("dimension").equals(anchor.dimension())
          || Integer.parseInt(row.get("x")) != anchor.x()
          || Integer.parseInt(row.get("y")) != anchor.y()
          || Integer.parseInt(row.get("z")) != anchor.z()
          || Long.parseLong(row.get("revision")) > value.revision()
          || (Long.parseLong(row.get("revision")) == value.revision()
              && ((row.get("template").equals(value.template())
                      && Integer.parseInt(row.get("quantity")) != value.quantity())
                  || !row.get("fingerprint").equals(value.fingerprint()))))
        throw invalid("SALE_SOURCE_CHANGED");
    }
    return value;
  }

  static void observe(
      FoundationStore.Transaction tx, WorldId world, TerminalPolicy.Anchor anchor, Source source) {
    tx.execute(
        """
                INSERT INTO register_stock_sources VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(world_id,generation) DO UPDATE SET revision=excluded.revision,
                quantity=excluded.quantity,template=excluded.template,fingerprint=excluded.fingerprint
                """,
        List.of(
            world.value().toString(),
            source.generation().toString(),
            anchor.dimension(),
            Integer.toString(anchor.x()),
            Integer.toString(anchor.y()),
            Integer.toString(anchor.z()),
            Long.toString(source.revision()),
            Integer.toString(source.quantity()),
            source.template(),
            source.fingerprint()));
  }

  private static Optional<Price> rawPrice(
      ReadView reads, WorldId world, TerminalPolicy.Anchor anchor) {
    var parameters = anchorParameters(world, anchor);
    return reads
        .query(
            "SELECT price FROM register_prices WHERE world_id=? AND dimension=? AND x=? AND y=? AND z=?",
            parameters,
            1)
        .stream()
        .map(row -> decodePrice(row.get("price")))
        .findFirst();
  }

  private static Optional<Reservation> raw(ReadView reads, UUID id) {
    return reads
        .query("SELECT * FROM register_sales WHERE id=?", List.of(id.toString()), 1)
        .stream()
        .map(RegisterSales::read)
        .findFirst();
  }

  private static Reservation read(Map<String, String> row) {
    return new Reservation(
        UUID.fromString(row.get("id")),
        player(row.get("owner")),
        State.valueOf(row.get("state")),
        decodeQuote(row.get("quote")),
        row.get("saved_source") == null ? null : decodeSource(row.get("saved_source")),
        row.get("evidence"),
        row.get("reason"),
        Long.parseLong(row.get("revision")));
  }

  private static List<String> values(Reservation value) {
    return Arrays.asList(
        value.id().toString(),
        actorKey(value.buyer()),
        value.state().name(),
        encodeQuote(value.quote()),
        value.savedSource() == null ? null : encodeSource(value.savedSource()),
        value.evidence(),
        value.reason(),
        Long.toString(value.revision()));
  }

  private static List<String> anchorParameters(WorldId world, TerminalPolicy.Anchor anchor) {
    return List.of(
        world.value().toString(),
        anchor.dimension(),
        Integer.toString(anchor.x()),
        Integer.toString(anchor.y()),
        Integer.toString(anchor.z()));
  }

  private static String encodeAnchor(TerminalPolicy.Anchor a) {
    return a.dimension() + "," + a.x() + "," + a.y() + "," + a.z();
  }

  private static TerminalPolicy.Anchor decodeAnchor(String value) {
    var p = value.split(",", -1);
    if (p.length != 4) throw invalid("SALE_ANCHOR");
    return new TerminalPolicy.Anchor(
        p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
  }

  private static String encodePrice(Price p) {
    return String.join(
        "|",
        p.world().value().toString(),
        pack(encodeAnchor(p.anchor())),
        p.generation().toString(),
        Long.toString(p.terminalRevision()),
        actorKey(p.owner()),
        p.sellerAccount().toString(),
        Long.toString(p.sellerAccountRevision()),
        p.currency().name(),
        Long.toString(p.revision()),
        pack(p.template()),
        Integer.toString(p.batch()),
        Long.toString(p.amount().minorUnits()));
  }

  private static Price decodePrice(String value) {
    var p = value.split("\\|", -1);
    if (p.length != 12) throw invalid("SALE_PRICE");
    return new Price(
        new WorldId(UUID.fromString(p[0])),
        decodeAnchor(unpack(p[1])),
        UUID.fromString(p[2]),
        Long.parseLong(p[3]),
        player(p[4]),
        UUID.fromString(p[5]),
        Long.parseLong(p[6]),
        EconomyCurrency.valueOf(p[7]),
        Long.parseLong(p[8]),
        unpack(p[9]),
        Integer.parseInt(p[10]),
        new Money(EconomyCurrency.valueOf(p[7]), Long.parseLong(p[11])));
  }

  private static String encodeSource(Source s) {
    return s.generation()
        + "|"
        + s.revision()
        + "|"
        + s.quantity()
        + "|"
        + pack(s.template())
        + "|"
        + s.available()
        + "|"
        + pack(s.fingerprint());
  }

  private static Source decodeSource(String value) {
    var p = value.split("\\|", -1);
    if (p.length != 6) throw invalid("SALE_SOURCE");
    return new Source(
        UUID.fromString(p[0]),
        Long.parseLong(p[1]),
        Integer.parseInt(p[2]),
        unpack(p[3]),
        Boolean.parseBoolean(p[4]),
        unpack(p[5]));
  }

  private static String encodeQuote(Quote q) {
    return pack(encodePrice(q.price()))
        + "|"
        + actorKey(q.buyer())
        + "|"
        + q.buyerAccount()
        + "|"
        + q.buyerAccountRevision()
        + "|"
        + pack(encodeSource(q.source()));
  }

  private static Quote decodeQuote(String value) {
    var p = value.split("\\|", -1);
    if (p.length != 5) throw invalid("SALE_QUOTE");
    return new Quote(
        decodePrice(unpack(p[0])),
        player(p[1]),
        UUID.fromString(p[2]),
        Long.parseLong(p[3]),
        decodeSource(unpack(p[4])));
  }

  private static String encodeReservation(Reservation value) {
    return String.join(
        "|",
        value.id().toString(),
        actorKey(value.buyer()),
        value.state().name(),
        pack(encodeQuote(value.quote())),
        value.savedSource() == null ? "" : pack(encodeSource(value.savedSource())),
        pack(value.evidence()),
        pack(value.reason()),
        Long.toString(value.revision()));
  }

  private static Reservation decodeReservation(String value) {
    var p = value.split("\\|", -1);
    if (p.length != 8) throw invalid("SALE_FACT");
    var result =
        new Reservation(
            UUID.fromString(p[0]),
            player(p[1]),
            State.valueOf(p[2]),
            decodeQuote(unpack(p[3])),
            p[4].isEmpty() ? null : decodeSource(unpack(p[4])),
            unpack(p[5]),
            unpack(p[6]),
            Long.parseLong(p[7]));
    text(result.evidence(), 256);
    text(result.reason(), 256);
    if (result.revision() < 1) throw invalid("SALE_REVISION");
    return result;
  }

  public static void validateFact(String payload) {
    var parts = payload.split("\\|", -1);
    if (parts.length != 2) throw invalid("SALE_FACT");
    var action = Action.valueOf(parts[0]);
    if (action == Action.CONFIGURE) decodePrice(unpack(parts[1]));
    else decodeReservation(unpack(parts[1]));
  }

  private static PlayerId player(String value) {
    var actor = actor(value);
    if (!(actor instanceof PlayerId player)) throw invalid("SALE_OWNER");
    return player;
  }

  private static String fingerprint(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String pack(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String unpack(String value) {
    return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
  }

  private static void text(String value, int max) {
    if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > max)
      throw invalid("SALE_BOUNDS");
  }

  private static IllegalArgumentException invalid(String code) {
    return new IllegalArgumentException(code);
  }

  private static Reject rejected(Exception error) {
    var code = error.getMessage();
    return reject(code != null && code.startsWith("SALE_") ? code : "SALE_REQUEST");
  }

  public static String guidance(String code) {
    return switch (code) {
      case "SALE_CURRENCY_MISMATCH" -> CURRENCY_GUIDANCE;
      case "SALE_SELLER_STALE" ->
          "The seller primary changed. Ask the owner to refresh shop pricing.";
      case "SALE_BUYER_STALE" -> "The buyer primary changed. Refresh the checkout quote.";
      default -> "";
    };
  }

  private static Reject reject(String code) {
    return new Reject(new Result(1, code, guidance(code)));
  }

  private static Decline decline(String code) {
    return new Decline(new Result(1, code, guidance(code)));
  }
}
