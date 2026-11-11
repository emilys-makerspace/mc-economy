package ca.emilybelnavis.mcmods.economy.autopay;

import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.debt.DebtRecords.Kind;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.loan.Loans;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A bounded source fold retains literal statement balance less every later payment, including
 * legacy facts.
 */
final class AutopayHistory {
  private AutopayHistory() {}

  static boolean bootstrap(FoundationStore store) {
    var checkpoint =
        store.transaction(
            tx ->
                tx.query("SELECT * FROM autopay_bootstrap WHERE singleton=1", List.of(), 1)
                    .getFirst());
    if (checkpoint.get("ready").equals("1")) return true;
    long frontier = Autopay.number(checkpoint, "frontier");
    if (frontier == 0) frontier = store.lastCommittedSequence();
    long sequence = Autopay.number(checkpoint, "sequence");
    int position = Integer.parseInt(checkpoint.get("position"));
    long end = frontier;
    var world = store.snapshot().worldId();
    var kind = new Journal.Kind("economy:autopay", "economy:bootstrap");
    String identity =
        world.value() + "|autopay-bootstrap-v1|" + end + "|" + sequence + "|" + position;
    var id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    var actor =
        new SystemActorId(
            UUID.nameUUIDFromBytes("economy:autopay-bootstrap".getBytes(StandardCharsets.UTF_8)));
    var registration =
        new Registration(
            1,
            (intent, reads) -> {
              var rows =
                  reads.query(
                      "SELECT sequence,position,record_type,record_version,payload FROM journal_records WHERE "
                          + AutopaySchema.FEED
                          + " AND sequence<=? AND (sequence>? OR (sequence=? AND position>?)) ORDER BY sequence,position LIMIT 17",
                      List.of(
                          Long.toString(end),
                          Long.toString(sequence),
                          Long.toString(sequence),
                          Integer.toString(position)),
                      17);
              var trackers = new LinkedHashMap<UUID, Autopay.Tracker>();
              long nextSequence = sequence;
              int nextPosition = position;
              for (var row : rows.stream().limit(16).toList()) {
                if (!row.get("record_version").equals("1")
                    && !(row.get("record_type").equals(Loans.FACT)
                        && row.get("record_version").equals("2")))
                  throw new IllegalStateException("Autopay historical source version");
                fold(
                    reads,
                    world,
                    new Journal.Record(
                        row.get("record_type"),
                        Integer.parseInt(row.get("record_version")),
                        row.get("payload")),
                    trackers);
                nextSequence = Autopay.number(row, "sequence");
                nextPosition = Integer.parseInt(row.get("position"));
              }
              boolean ready = rows.size() <= 16;
              long savedSequence = nextSequence;
              int savedPosition = nextPosition;
              String body =
                  world.value()
                      + "|"
                      + end
                      + "|"
                      + savedSequence
                      + "|"
                      + savedPosition
                      + "|"
                      + (ready ? "1" : "0");
              return new Commit(
                  List.of(new Journal.Record(Autopay.BOOTSTRAP, 1, body)),
                  new Journal.Result(1, "AUTOPAY_HISTORY_PROGRESS", body),
                  tx -> {
                    trackers.values().forEach(t -> Autopay.writeTracker(tx, t));
                    tx.execute(
                        "UPDATE autopay_bootstrap SET frontier=?,sequence=?,position=?,ready=? WHERE singleton=1",
                        List.of(
                            Long.toString(end),
                            Long.toString(savedSequence),
                            Integer.toString(savedPosition),
                            ready ? "1" : "0"));
                  });
            });
    var engine =
        new JournalEngine(
            store, (who, k) -> who.equals(actor) && k.equals(kind), Map.of(kind, registration));
    var outcome =
        engine.submit(
            new Journal.Intent(
                new OperationId(id),
                new RequestId(id),
                actor,
                kind,
                new Journal.CanonicalRequest(
                    1,
                    Map.of(
                        "frontier",
                        Long.toString(end),
                        "sequence",
                        Long.toString(sequence),
                        "position",
                        Integer.toString(position))),
                null,
                null));
    if (outcome.status() != Journal.Status.COMMITTED)
      throw new IllegalStateException("Autopay history bootstrap unavailable");
    return false;
  }

  static void validate(String body) {
    var p = body.split("\\|", -1);
    if (p.length != 5) throw new IllegalArgumentException("Autopay bootstrap fact");
    UUID.fromString(p[0]);
    long frontier = Long.parseLong(p[1]), sequence = Long.parseLong(p[2]);
    int position = Integer.parseInt(p[3]);
    if (frontier < 0
        || sequence < 0
        || sequence > frontier
        || position < -1
        || position > 63
        || !Set.of("0", "1").contains(p[4]))
      throw new IllegalArgumentException("Autopay bootstrap cursor");
  }

  private static List<String> event(String payload) {
    var fields = payload.split("\\|", -1);
    if (fields.length != 4) throw new IllegalArgumentException("Credit payment event");
    return Arrays.stream(fields)
        .map(v -> new String(Base64.getUrlDecoder().decode(v), StandardCharsets.UTF_8))
        .toList();
  }

  private static void fold(
      ReadView reads, WorldId world, Journal.Record record, Map<UUID, Autopay.Tracker> trackers) {
    if (record.type().equals(CreditFacilities.STATEMENT_FACT)) {
      var snapshot = CreditFacilities.decodeStatementSnapshot(record.payload());
      if (!snapshot.world().equals(world))
        throw new IllegalArgumentException("Autopay statement world");
      var s = snapshot.statement();
      trackers.put(
          snapshot.facility(),
          new Autopay.Tracker(
              world,
              snapshot.facility(),
              s.id(),
              s.closedAt(),
              s.dueAt(),
              s.debtMinor(),
              s.debtMinor()));
    } else if (record.type().equals(CreditFacilities.EVENT_FACT)) {
      var fields = event(record.payload());
      if (!fields.getFirst().equals("REPAYMENT")) return;
      var facility = UUID.fromString(fields.get(1));
      var old =
          trackers.containsKey(facility)
              ? trackers.get(facility)
              : Autopay.tracker(reads, world, facility);
      if (old == null) return;
      long amount = Long.parseLong(fields.get(3).split("\\|", -1)[0]);
      if (amount <= 0) throw new IllegalArgumentException("Historical payment amount");
      trackers.put(
          facility,
          new Autopay.Tracker(
              world,
              facility,
              old.statement(),
              old.closedAt(),
              old.dueAt(),
              old.balance(),
              old.remaining() - Math.min(old.remaining(), amount)));
    }
  }

  static Commit decorate(ReadView reads, Commit commit) {
    if (!Autopay.ready(reads)) return commit;
    var trackers = new LinkedHashMap<UUID, Autopay.Tracker>();
    var cards = new LinkedHashMap<UUID, CreditFacilities.Facility>();
    var loans = new LinkedHashMap<UUID, Loans.Contract>();
    var effective = new HashMap<UUID, Long>();
    boolean automatic = commit.records().stream().anyMatch(r -> r.type().equals(Autopay.WINDOW));
    for (var record : commit.records()) {
      if (record.type().equals(CreditFacilities.FACT)) {
        var card = CreditFacilities.decodeSnapshot(record.payload());
        cards.put(card.id(), card);
      } else if (record.type().equals(Loans.FACT)) {
        var loan = Loans.decode(record.payload());
        loans.put(loan.id(), loan);
      } else if (record.type().equals(CreditFacilities.EVENT_FACT)) {
        var fields = event(record.payload());
        effective.merge(UUID.fromString(fields.get(1)), Long.parseLong(fields.get(2)), Math::max);
      }
    }
    for (var card : cards.values())
      for (var record : commit.records()) {
        if (record.type().equals(CreditFacilities.STATEMENT_FACT)
            && CreditFacilities.decodeStatementSnapshot(record.payload())
                .facility()
                .equals(card.id())) fold(reads, card.world(), record, trackers);
        else if (record.type().equals(CreditFacilities.EVENT_FACT)
            && event(record.payload()).get(1).equals(card.id().toString()))
          fold(reads, card.world(), record, trackers);
      }
    var windows = new ArrayList<Autopay.Window>();
    var settings = new ArrayList<Autopay.Settings>();
    var heads = new ArrayList<Long>();
    if (!automatic) {
      for (var card : cards.values()) {
        var consent = Autopay.find(reads, card.party(), Kind.CARD, card.id()).orElse(null);
        if (consent == null || !consent.enabled()) continue;
        var tracker =
            trackers.containsKey(card.id())
                ? trackers.get(card.id())
                : Autopay.tracker(reads, card.world(), card.id());
        update(
            reads,
            consent,
            card,
            null,
            tracker,
            effective.getOrDefault(card.id(), card.lastDay()),
            windows,
            settings,
            heads);
      }
      for (var loan : loans.values()) {
        var consent = Autopay.find(reads, loan.party(), Kind.LOAN, loan.id()).orElse(null);
        if (consent == null || !consent.enabled()) continue;
        update(
            reads,
            consent,
            null,
            loan,
            null,
            loan.servicing().lastAccrued(),
            windows,
            settings,
            heads);
      }
    }
    if (trackers.isEmpty() && windows.isEmpty() && settings.isEmpty()) return commit;
    var records = new ArrayList<>(commit.records());
    windows.forEach(w -> records.add(Autopay.windowFact(w)));
    settings.forEach(s -> records.add(Autopay.settingFact(s)));
    return new Commit(
        records,
        commit.result(),
        tx -> {
          commit.projections().apply(tx);
          trackers.values().forEach(t -> Autopay.writeTracker(tx, t));
          windows.forEach(w -> Autopay.writeWindow(tx, w));
          for (int n = 0; n < settings.size(); n++)
            Autopay.writeSettings(tx, settings.get(n), heads.get(n));
        });
  }

  private static void update(
      ReadView reads,
      Autopay.Settings consent,
      CreditFacilities.Facility card,
      Loans.Contract loan,
      Autopay.Tracker tracker,
      long at,
      List<Autopay.Window> windows,
      List<Autopay.Settings> settings,
      List<Long> heads) {
    boolean active =
        card != null
            ? card.status() == CreditFacilities.Status.ACTIVE
                && card.access() != CreditFacilities.Access.CLOSED
            : loan.status() == Loans.Status.ACTIVE;
    var old = Autopay.waiting(reads, consent).orElse(null);
    if (!active) {
      var disabled =
          new Autopay.Settings(
              consent.world(),
              consent.owner(),
              consent.kind(),
              consent.facility(),
              false,
              consent.funding(),
              consent.mode(),
              at,
              Math.addExact(consent.revision(), 1));
      settings.add(disabled);
      heads.add(null);
      if (old != null)
        windows.add(
            new Autopay.Window(
                old.consent(),
                old.dueAt(),
                old.obligation(),
                old.attempts(),
                Autopay.State.CANCELLED,
                old.targetMinor(),
                old.remainingMinor(),
                null,
                Math.max(at, old.dueAt()),
                "FACILITY_CLOSED"));
      return;
    }
    if (old == null) return;
    long remaining = AutopayRuntime.remaining(old, card, loan, tracker);
    if (remaining == old.remainingMinor()) return;
    Long next = remaining == 0 ? AutopayRuntime.futureDue(card, loan) : old.nextAt();
    windows.add(
        new Autopay.Window(
            old.consent(),
            old.dueAt(),
            old.obligation(),
            old.attempts(),
            remaining == 0 ? Autopay.State.SATISFIED : Autopay.State.WAITING,
            old.targetMinor(),
            remaining,
            next,
            Math.max(at, old.dueAt()),
            remaining == 0 ? "MANUAL_TARGET_SATISFIED" : "MANUAL_PAYMENT"));
    settings.add(consent);
    heads.add(next);
  }
}
