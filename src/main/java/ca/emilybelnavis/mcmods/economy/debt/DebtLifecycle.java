package ca.emilybelnavis.mcmods.economy.debt;

import static ca.emilybelnavis.mcmods.economy.debt.DebtRecords.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.loan.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared worker-owned obligations, reporting revisions and independent collection clocks. */
public final class DebtLifecycle {
  public static final String STATE = "economy:a6/debt-state",
      ATTEMPT = "economy:a6/debt-sale-attempt";

  private DebtLifecycle() {}

  public static UUID stable(String key) {
    return UUID.nameUUIDFromBytes(("economy:debt:v1|" + key).getBytes(StandardCharsets.UTF_8));
  }

  public static Optional<DebtRecords.Case> findCase(JournalEngine.ReadView reads, UUID id) {
    return reads
        .query("SELECT * FROM debt_cases WHERE case_id=?", List.of(id.toString()), 1)
        .stream()
        .map(DebtLifecycle::caseRow)
        .findFirst();
  }

  public static Optional<DebtRecords.Case> facilityCase(
      JournalEngine.ReadView reads, Kind kind, UUID facility) {
    return reads
        .query(
            "SELECT * FROM debt_cases WHERE kind=? AND facility_id=?",
            List.of(kind.name(), facility.toString()),
            1)
        .stream()
        .map(DebtLifecycle::caseRow)
        .findFirst();
  }

  private static DebtRecords.Case caseRow(Map<String, String> row) {
    var c = decodeCase(row.get("body"));
    if (!row.get("world_id").equals(c.world().value().toString())
        || !row.get("case_id").equals(c.id().toString())
        || !row.get("kind").equals(c.kind().name())
        || !row.get("facility_id").equals(c.facility().toString())
        || !row.get("owner_id").equals(c.owner().value().toString())
        || !row.get("creditor").equals(c.creditor().key())
        || !row.get("currency").equals(c.currency().name())
        || Long.parseLong(row.get("principal_minor")) != c.principalMinor()
        || Long.parseLong(row.get("interest_minor")) != c.interestMinor()
        || Long.parseLong(row.get("default_at")) != c.defaultAt()
        || Long.parseLong(row.get("acknowledged_at")) != c.acknowledgedAt()
        || Long.parseLong(row.get("collection_expires")) != c.collectionExpires()
        || Long.parseLong(row.get("report_expires")) != c.reportExpires()
        || Long.parseLong(row.get("discount_minor")) != c.discountRemainingMinor()
        || Long.parseLong(row.get("revision")) != c.revision()
        || !row.get("sale").equals(c.sale().name())
        || !row.get("collectability").equals(c.collectability().name())
        || !row.get("payment").equals(c.payment().name())) {
      throw new IllegalArgumentException("Debt case projection binding");
    }
    return c;
  }

  public static String bureauStatus(DebtRecords.Case c, long now) {
    return !reportVisible(c, now)
        ? "EXPIRED"
        : c.payment() == Payment.SETTLED ? "PAID_DEFAULT" : "DEFAULT";
  }

  public static Optional<DebtRecords.Case> ownedCase(
      JournalEngine.ReadView reads, PartyRef owner, UUID id) {
    return findCase(reads, id)
        .filter(
            c ->
                owner.world().equals(c.world())
                    && owner.owner() instanceof PartyRef.Player p
                    && p.id().equals(c.owner()));
  }

  public static List<DebtRecords.Case> cases(
      JournalEngine.ReadView reads, PartyRef owner, UUID after, int limit) {
    bounds(limit);
    if (!(owner.owner() instanceof PartyRef.Player player)) return List.of();
    return reads
        .query(
            "SELECT * FROM debt_cases WHERE world_id=? AND owner_id=? AND case_id>? ORDER BY case_id LIMIT ?",
            List.of(
                owner.world().value().toString(),
                player.id().value().toString(),
                after == null ? "" : after.toString(),
                Integer.toString(limit)),
            limit)
        .stream()
        .map(DebtLifecycle::caseRow)
        .toList();
  }

  public static boolean demands(DebtRecords.Case c, long now) {
    return c.collectability() == Collectability.COLLECTIBLE
        && c.payment() != Payment.SETTLED
        && now >= c.acknowledgedAt()
        && now < c.collectionExpires();
  }

  public static boolean reportVisible(DebtRecords.Case c, long now) {
    return now >= c.defaultAt() && now < c.reportExpires();
  }

  public static JournalEngine.Decision observe(
      Journal.Intent intent, JournalEngine.ReadView reads, JournalEngine.Commit base) {
    var snapshots = new ArrayList<Snapshot>();
    var cases = new LinkedHashMap<String, DebtRecords.Case>();
    long effective = effective(intent, base);
    for (var record : base.records()) {
      if (record.type().equals(CASE_FACT)) {
        var c = decodeCase(record.payload());
        cases.put(c.kind() + "/" + c.facility(), c);
      }
      if (record.type().equals(CreditFacilities.FACT) && record.version() == 1)
        snapshots.add(snapshot(CreditFacilities.decodeSnapshot(record.payload())));
      if (record.type().equals(Loans.FACT) && (record.version() == 1 || record.version() == 2))
        snapshots.add(snapshot(Loans.decode(record.payload())));
    }
    if (snapshots.isEmpty() && cases.isEmpty()) return base;
    var records = new ArrayList<>(base.records());
    var updates = new ArrayList<Obligation>();
    var reports = new ArrayList<Report>();
    for (var snapshot : snapshots) {
      synchronize(reads, snapshot, effective, records, updates, reports);
      var c = cases.get(snapshot.kind() + "/" + snapshot.facility());
      if (c == null) c = facilityCase(reads, snapshot.kind(), snapshot.facility()).orElse(null);
      if (c == null) records.add(stateFact(snapshot, effective));
      else cases.putIfAbsent(snapshot.kind() + "/" + snapshot.facility(), c);
    }
    for (var c : cases.values()) records.add(caseStateFact(c, effective));
    if (records.size() > 64)
      return new JournalEngine.Decline(new Journal.Result(1, "DEBT_BATCH_LIMIT", ""));
    return new JournalEngine.Commit(
        records,
        base.result(),
        tx -> {
          base.projections().apply(tx);
          for (var obligation : updates) {
            DebtRecords.write(tx, obligation);
            if (obligation.revision() == 1)
              tx.execute(
                  "INSERT OR IGNORE INTO debt_due_queue VALUES(?,?,?,?)",
                  List.of(
                      obligation.world().value().toString(),
                      obligation.kind().name(),
                      obligation.id().toString(),
                      Long.toString(Math.addExact(obligation.dueAt(), 1))));
          }
          for (var report : reports) DebtRecords.write(tx, report);
          for (var c : cases.values()) writeCaseState(tx, c, effective);
          for (var snapshot : snapshots) {
            if (!cases.containsKey(snapshot.kind() + "/" + snapshot.facility()))
              writeState(tx, snapshot, effective);
            var oldest =
                snapshot.obligations().stream()
                    .filter(o -> o.unmet() > 0)
                    .mapToLong(Original::due)
                    .min();
            if (snapshot.status().equals("ACTIVE") && oldest.isPresent())
              tx.execute(
                  "INSERT INTO debt_default_heads VALUES(?,?,?,?) ON CONFLICT(world_id,kind,facility_id) DO UPDATE SET next_at=excluded.next_at",
                  List.of(
                      snapshot.world().value().toString(),
                      snapshot.kind().name(),
                      snapshot.facility().toString(),
                      Long.toString(Math.addExact(oldest.getAsLong(), DEFAULT_DELAY))));
            else
              tx.execute(
                  "DELETE FROM debt_default_heads WHERE world_id=? AND kind=? AND facility_id=?",
                  List.of(
                      snapshot.world().value().toString(),
                      snapshot.kind().name(),
                      snapshot.facility().toString()));
          }
        });
  }

  private record Original(UUID id, long due, long original, long unmet) {}

  private record Snapshot(
      WorldId world,
      Kind kind,
      UUID facility,
      PlayerId owner,
      EconomyCurrency currency,
      String status,
      long revision,
      long savedAt,
      List<Original> obligations) {}

  private static Snapshot snapshot(CreditFacilities.Facility f) {
    return new Snapshot(
        f.world(),
        Kind.CARD,
        f.id(),
        f.owner(),
        f.currency(),
        f.status().name(),
        f.revision(),
        f.lastDay(),
        f.requirements().stream()
            .map(
                r ->
                    new Original(
                        UUID.fromString(r.id()), r.dueAt(), r.originalMinor(), r.unmetMinor()))
            .toList());
  }

  private static Snapshot snapshot(Loans.Contract loan) {
    return new Snapshot(
        loan.world(),
        Kind.LOAN,
        loan.id(),
        loan.owner(),
        loan.currency(),
        loan.status().name(),
        loan.revision(),
        loan.servicing().lastAccrued(),
        loan.servicing().requirements().stream()
            .map(
                r ->
                    new Original(
                        UUID.fromString(r.id()), r.dueAt(), r.originalMinor(), r.unmetMinor()))
            .toList());
  }

  private static void synchronize(
      JournalEngine.ReadView reads,
      Snapshot snapshot,
      long effective,
      List<Journal.Record> facts,
      List<Obligation> updates,
      List<Report> corrections) {
    var oldRows =
        reads.query(
            "SELECT body FROM debt_obligations WHERE kind=? AND facility_id=? AND unmet_minor>0 ORDER BY due_at,obligation_id LIMIT 17",
            List.of(snapshot.kind().name(), snapshot.facility().toString()),
            17);
    if (oldRows.size() > 16) throw new IllegalStateException("Debt obligation bound");
    var incoming = new LinkedHashMap<UUID, Original>();
    for (var original : snapshot.obligations()) incoming.put(original.id(), original);
    for (var row : oldRows) {
      var old = decodeObligation(row.get("body"));
      incoming.putIfAbsent(old.id(), new Original(old.id(), old.dueAt(), old.originalMinor(), 0));
    }
    for (var original : incoming.values()) {
      if (original.original() == 0) continue;
      var existing =
          reads.query(
              "SELECT body FROM debt_obligations WHERE world_id=? AND kind=? AND obligation_id=?",
              List.of(
                  snapshot.world().value().toString(),
                  snapshot.kind().name(),
                  original.id().toString()),
              1);
      var old = existing.isEmpty() ? null : decodeObligation(existing.getFirst().get("body"));
      if (old != null && old.unmetMinor() == original.unmet()) continue;
      var next =
          new Obligation(
              snapshot.world(),
              original.id(),
              snapshot.kind(),
              snapshot.facility(),
              snapshot.owner(),
              snapshot.currency(),
              original.original(),
              original.unmet(),
              original.due(),
              old != null && old.reported(),
              old != null && old.metAtReport(),
              old == null ? 1 : Math.addExact(old.revision(), 1),
              Math.max(snapshot.savedAt(), effective));
      updates.add(next);
      facts.add(DebtRecords.fact(next));
      if (old != null
          && old.reported()
          && !old.metAtReport()
          && old.unmetMinor() > 0
          && next.unmetMinor() == 0
          && !snapshot.status().equals("DEFAULTED")) {
        var correction = report(reads, next, true, next.effectiveAt());
        corrections.add(correction);
        facts.add(DebtRecords.fact(correction));
      }
    }
  }

  private static Journal.Record caseStateFact(DebtRecords.Case c, long effective) {
    return new Journal.Record(
        STATE,
        1,
        c.world().value()
            + "|"
            + c.kind()
            + "|"
            + c.facility()
            + "|"
            + c.owner().value()
            + "|"
            + caseState(c)
            + "|"
            + Math.max(effective, c.defaultAt())
            + "|"
            + c.revision());
  }

  private static void writeCaseState(
      FoundationStore.Transaction tx, DebtRecords.Case c, long effective) {
    var p = caseStateFact(c, effective).payload().split("\\|", -1);
    tx.execute(
        "INSERT INTO debt_facility_states VALUES(?,?,?,?,?,?,?) ON CONFLICT(world_id,kind,facility_id) DO UPDATE SET state=excluded.state,effective_at=excluded.effective_at,revision=excluded.revision",
        List.of(p));
  }

  private static Journal.Record stateFact(Snapshot s, long effective) {
    String status = s.status();
    if (status.equals("ACTIVE"))
      status =
          s.obligations().stream().anyMatch(o -> o.unmet() > 0 && o.due() < effective)
              ? "DELINQUENT"
              : "CURRENT";
    return new Journal.Record(
        STATE,
        1,
        s.world().value()
            + "|"
            + s.kind()
            + "|"
            + s.facility()
            + "|"
            + s.owner().value()
            + "|"
            + status
            + "|"
            + Math.max(effective, s.savedAt())
            + "|"
            + s.revision());
  }

  private static void writeState(
      FoundationStore.Transaction tx, Snapshot snapshot, long effective) {
    var p = stateFact(snapshot, effective).payload().split("\\|", -1);
    tx.execute(
        "INSERT INTO debt_facility_states VALUES(?,?,?,?,?,?,?) ON CONFLICT(world_id,kind,facility_id) DO UPDATE SET state=excluded.state,effective_at=excluded.effective_at,revision=excluded.revision",
        List.of(p[0], p[1], p[2], p[3], p[4], p[5], p[6]));
  }

  private static long effective(Journal.Intent intent, JournalEngine.Commit commit) {
    long result = 0;
    String requested = intent.request().fields().get("effective");
    if (requested != null) result = Long.parseLong(requested);
    for (var fact : commit.records()) {
      if (fact.type().equals(DebtFinance.ACCOUNTING_FACT)
          || fact.type().equals(DebtFinance.FREEZE_FACT))
        result = Math.max(result, Long.parseLong(fact.payload().split("\\|", -1)[3]));
      if (fact.type().equals(CreditAuditFacts.PAYMENT))
        result = Math.max(result, CreditAuditFacts.decodePayment(fact.payload()).effective());
      if (fact.type().equals(Loans.EVENT_FACT)) {
        var fields = fact.payload().split("\\|", -1);
        result =
            Math.max(
                result,
                Long.parseLong(
                    new String(Base64.getUrlDecoder().decode(fields[2]), StandardCharsets.UTF_8)));
      }
      if (fact.type().equals(Loans.FACT))
        result = Math.max(result, Loans.decode(fact.payload()).servicing().lastAccrued());
      if (fact.type().equals(CreditFacilities.FACT))
        result = Math.max(result, CreditFacilities.decodeSnapshot(fact.payload()).lastDay());
    }
    return result;
  }

  private static Report report(
      JournalEngine.ReadView reads, Obligation obligation, boolean cured, long now) {
    var id =
        stable(
            "report|"
                + obligation.world().value()
                + "|"
                + obligation.kind()
                + "|"
                + obligation.id());
    var old =
        reads.query(
            "SELECT body FROM debt_reports WHERE world_id=? AND report_id=? ORDER BY revision DESC LIMIT 1",
            List.of(obligation.world().value().toString(), id.toString()),
            1);
    var previous = old.isEmpty() ? null : decodeReport(old.getFirst().get("body"));
    long at = Math.addExact(obligation.dueAt(), 2 * DAY);
    return new Report(
        obligation.world(),
        id,
        obligation.kind(),
        obligation.facility(),
        obligation.owner(),
        obligation.id(),
        obligation.dueAt(),
        at,
        previous == null ? obligation.unmetMinor() == 0 : previous.metAtSnapshot(),
        cured,
        obligation.originalMinor(),
        obligation.unmetMinor(),
        previous == null ? 1 : Math.addExact(previous.revision(), 1),
        Math.max(at, now));
  }

  public static JournalEngine.Decision reportDue(
      JournalEngine.ReadView reads, Kind kind, UUID id, long effective) {
    var obligation = obligation(reads, kind, id).orElseThrow();
    if (obligation.reported() || effective != Math.addExact(obligation.dueAt(), 2 * DAY))
      return decline("DEBT_REPORT_STALE");
    var report = report(reads, obligation, false, effective);
    var next =
        new Obligation(
            obligation.world(),
            obligation.id(),
            kind,
            obligation.facility(),
            obligation.owner(),
            obligation.currency(),
            obligation.originalMinor(),
            obligation.unmetMinor(),
            obligation.dueAt(),
            true,
            report.metAtSnapshot(),
            obligation.revision() + 1,
            effective);
    return new JournalEngine.Commit(
        List.of(DebtRecords.fact(next), DebtRecords.fact(report)),
        new Journal.Result(1, "DEBT_REPORTED", id.toString()),
        tx -> {
          DebtRecords.write(tx, next);
          DebtRecords.write(tx, report);
        });
  }

  public static Optional<Obligation> obligation(JournalEngine.ReadView reads, Kind kind, UUID id) {
    return reads
        .query(
            "SELECT body FROM debt_obligations WHERE kind=? AND obligation_id=?",
            List.of(kind.name(), id.toString()),
            1)
        .stream()
        .map(r -> decodeObligation(r.get("body")))
        .findFirst();
  }

  public static JournalEngine.Decision defaultDue(
      JournalEngine.ReadView reads, Kind kind, UUID facility, long effective) {
    if (facilityCase(reads, kind, facility).isPresent()) return decline("DEBT_ALREADY_DEFAULTED");
    var rows =
        reads.query(
            "SELECT body FROM debt_obligations WHERE kind=? AND facility_id=? AND unmet_minor>0 ORDER BY due_at,obligation_id LIMIT 1",
            List.of(kind.name(), facility.toString()),
            1);
    if (rows.isEmpty()
        || Math.addExact(decodeObligation(rows.getFirst().get("body")).dueAt(), DEFAULT_DELAY)
            != effective) return decline("DEBT_DEFAULT_STALE");
    DebtFinance.Frozen frozen;
    try {
      frozen = DebtFinance.freeze(reads, kind, facility, effective);
    } catch (DebtFinance.FinancialRejection rejected) {
      return rejected.decision();
    }
    if (!accountingCurrent(
        reads, frozen.world(), kind, frozen.owner(), frozen.origin(), frozen.currency()))
      return decline("DEBT_ACCOUNTING_UNAVAILABLE");
    var id =
        stable("case|" + frozen.world().value() + "|" + kind + "|" + facility + "|" + effective);
    var next =
        new DebtRecords.Case(
            frozen.world(),
            id,
            kind,
            facility,
            frozen.owner(),
            frozen.origin(),
            frozen.origin(),
            frozen.currency(),
            frozen.principalMinor(),
            frozen.interestMinor(),
            effective,
            effective,
            Math.addExact(effective, COLLECTION_WINDOW),
            Math.addExact(effective, REPORT_WINDOW),
            Sale.PENDING,
            Collectability.COLLECTIBLE,
            Payment.UNPAID,
            0,
            1);
    var records = new ArrayList<>(frozen.commit().records());
    records.add(DebtRecords.fact(next));
    return new JournalEngine.Commit(
        records,
        new Journal.Result(1, "DEBT_DEFAULTED", id.toString()),
        tx -> {
          frozen.commit().projections().apply(tx);
          DebtRecords.write(tx, next);
          tx.execute(
              "INSERT INTO debt_sale_attempts VALUES(?,?,?)",
              List.of(next.world().value().toString(), id.toString(), Long.toString(effective)));
        });
  }

  public static JournalEngine.Decision sell(JournalEngine.ReadView reads, UUID id, long effective) {
    var c = findCase(reads, id).orElseThrow();
    if (c.sale() != Sale.PENDING || !demands(c, effective)) return decline("DEBT_SALE_STALE");
    if (!accountingCurrent(reads, c.world(), c.kind(), c.owner(), c.creditor(), c.currency()))
      return decline("DEBT_ACCOUNTING_UNAVAILABLE");
    JournalEngine.Decision decision;
    try {
      decision = DebtFinance.sale(reads, c, effective);
    } catch (DebtFinance.FinancialRejection rejected) {
      decision = rejected.decision();
    }
    if (!(decision instanceof JournalEngine.Commit finance)) {
      String reason =
          decision instanceof JournalEngine.Reject reject
              ? reject.result().code()
              : "FUNDING_UNAVAILABLE";
      return new JournalEngine.Commit(
          List.of(new Journal.Record(ATTEMPT, 1, id + "|" + effective + "|" + reason)),
          new Journal.Result(1, "COLLECTIONS_PENDING_SALE", reason),
          tx ->
              tx.execute(
                  "UPDATE debt_sale_attempts SET next_at=? WHERE case_id=?",
                  List.of(Long.toString(Math.addExact(effective, DAY)), id.toString())));
    }
    long price = DebtFinance.purchasePrice(c.principalMinor());
    var next =
        new DebtRecords.Case(
            c.world(),
            c.id(),
            c.kind(),
            c.facility(),
            c.owner(),
            c.origin(),
            InstitutionCatalog.WAYSTONE,
            c.currency(),
            c.principalMinor(),
            c.interestMinor(),
            c.defaultAt(),
            c.acknowledgedAt(),
            c.collectionExpires(),
            c.reportExpires(),
            Sale.SOLD,
            c.collectability(),
            c.payment(),
            Math.subtractExact(c.debtMinor(), price),
            c.revision() + 1);
    var facts = new ArrayList<>(finance.records());
    facts.add(DebtRecords.fact(next));
    return new JournalEngine.Commit(
        facts,
        new Journal.Result(1, "DEBT_SOLD", id.toString()),
        tx -> {
          finance.projections().apply(tx);
          DebtRecords.write(tx, next);
          tx.execute("DELETE FROM debt_sale_attempts WHERE case_id=?", List.of(id.toString()));
        });
  }

  public static JournalEngine.Decision expire(
      JournalEngine.ReadView reads, UUID id, long effective) {
    var c = findCase(reads, id).orElseThrow();
    if (c.collectability() != Collectability.COLLECTIBLE
        || c.payment() == Payment.SETTLED
        || effective != c.collectionExpires()) return decline("DEBT_EXPIRY_STALE");
    if (!accountingCurrent(reads, c.world(), c.kind(), c.owner(), c.creditor(), c.currency()))
      return decline("DEBT_ACCOUNTING_UNAVAILABLE");
    JournalEngine.Commit finance;
    try {
      finance = DebtFinance.writeOff(reads, c);
    } catch (DebtFinance.FinancialRejection rejected) {
      return rejected.decision();
    }
    var next =
        new DebtRecords.Case(
            c.world(),
            c.id(),
            c.kind(),
            c.facility(),
            c.owner(),
            c.origin(),
            c.creditor(),
            c.currency(),
            c.principalMinor(),
            c.interestMinor(),
            c.defaultAt(),
            c.acknowledgedAt(),
            c.collectionExpires(),
            c.reportExpires(),
            c.sale(),
            Collectability.UNCOLLECTABLE,
            c.payment(),
            c.discountRemainingMinor(),
            c.revision() + 1);
    var records = new ArrayList<>(finance.records());
    records.add(DebtRecords.fact(next));
    return new JournalEngine.Commit(
        records,
        new Journal.Result(1, "DEBT_UNCOLLECTABLE", id.toString()),
        tx -> {
          finance.projections().apply(tx);
          DebtRecords.write(tx, next);
          tx.execute("DELETE FROM debt_sale_attempts WHERE case_id=?", List.of(id.toString()));
        });
  }

  public static JournalEngine.Decision pay(
      JournalEngine.ReadView reads,
      DebtRecords.Case c,
      long amount,
      long effective,
      List<PostingBatch.Leg> funding,
      List<FinancialAccounts.Account> planned) {
    if (!findCase(reads, c.id()).filter(c::equals).isPresent()
        || !demands(c, effective)
        || amount <= 0
        || amount > c.debtMinor()) return decline("DEBT_PAYMENT_STALE");
    if (!accountingCurrent(reads, c.world(), c.kind(), c.owner(), c.creditor(), c.currency()))
      return decline("DEBT_ACCOUNTING_UNAVAILABLE");
    DebtFinance.PaymentPlan plan;
    try {
      plan = DebtFinance.payment(reads, c, amount, effective, funding, planned);
    } catch (DebtFinance.FinancialRejection rejected) {
      return rejected.decision();
    }
    boolean settled = Math.addExact(plan.principalMinor(), plan.interestMinor()) == 0;
    long acknowledged = settled ? c.acknowledgedAt() : effective;
    var next =
        new DebtRecords.Case(
            c.world(),
            c.id(),
            c.kind(),
            c.facility(),
            c.owner(),
            c.origin(),
            c.creditor(),
            c.currency(),
            plan.principalMinor(),
            plan.interestMinor(),
            c.defaultAt(),
            acknowledged,
            Math.addExact(acknowledged, COLLECTION_WINDOW),
            c.reportExpires(),
            c.sale(),
            c.collectability(),
            settled ? Payment.SETTLED : Payment.PARTIAL,
            Math.subtractExact(c.discountRemainingMinor(), plan.discountReleasedMinor()),
            c.revision() + 1);
    var records = new ArrayList<>(plan.commit().records());
    records.add(DebtRecords.fact(next));
    return new JournalEngine.Commit(
        records,
        new Journal.Result(
            1, settled ? "COLLECTION_SETTLED" : "COLLECTION_REPAID", c.id().toString()),
        tx -> {
          plan.commit().projections().apply(tx);
          DebtRecords.write(tx, next);
          if (settled)
            tx.execute(
                "DELETE FROM debt_sale_attempts WHERE case_id=?", List.of(c.id().toString()));
        });
  }

  public static JournalEngine.Decision delinquent(
      JournalEngine.ReadView reads, Kind kind, UUID id, long effective) {
    var o = obligation(reads, kind, id).orElseThrow();
    var existing = facilityCase(reads, kind, o.facility());
    String state =
        existing.isPresent()
            ? caseState(existing.orElseThrow())
            : o.unmetMinor() > 0 ? "DELINQUENT" : "CURRENT";
    var fact =
        new Journal.Record(
            STATE,
            1,
            o.world().value()
                + "|"
                + kind
                + "|"
                + o.facility()
                + "|"
                + o.owner().value()
                + "|"
                + state
                + "|"
                + effective
                + "|"
                + o.revision());
    return new JournalEngine.Commit(
        List.of(fact),
        new Journal.Result(1, "DEBT_DUE_CHECKED", id.toString()),
        tx -> {
          tx.execute(
              "DELETE FROM debt_due_queue WHERE world_id=? AND kind=? AND obligation_id=?",
              List.of(o.world().value().toString(), kind.name(), id.toString()));
          tx.execute(
              "INSERT INTO debt_facility_states VALUES(?,?,?,?,?,?,?) ON CONFLICT(world_id,kind,facility_id) DO UPDATE SET state=excluded.state,effective_at=excluded.effective_at,revision=excluded.revision",
              List.of(
                  o.world().value().toString(),
                  kind.name(),
                  o.facility().toString(),
                  o.owner().value().toString(),
                  state,
                  Long.toString(effective),
                  Long.toString(o.revision())));
        });
  }

  public static String caseState(DebtRecords.Case c) {
    if (c.payment() == Payment.SETTLED) return "SETTLED";
    if (c.collectability() == Collectability.UNCOLLECTABLE) return "UNCOLLECTABLE";
    return "COLLECTIONS";
  }

  public record ReportCursor(long effectiveAt, String reportId, long revision) {
    public ReportCursor {
      if (effectiveAt < 0
          || revision < 0
          || (!reportId.isEmpty() && !UUID.fromString(reportId).toString().equals(reportId)))
        throw new IllegalArgumentException("Report cursor");
    }

    public static ReportCursor start() {
      return new ReportCursor(0, "", 0);
    }
  }

  public record ReportPage(List<Report> entries, ReportCursor nextCursor) {
    public ReportPage {
      entries = List.copyOf(entries);
    }
  }

  public static ReportPage reports(
      JournalEngine.ReadView reads, PartyRef owner, ReportCursor after, int limit) {
    bounds(limit);
    if (!(owner.owner() instanceof PartyRef.Player p))
      throw new IllegalArgumentException("Debt owner");
    var rows =
        reads.query(
            "SELECT body FROM debt_reports WHERE world_id=? AND owner_id=? AND (effective_at,report_id,revision)>(?,?,?) ORDER BY effective_at,report_id,revision LIMIT ?",
            List.of(
                owner.world().value().toString(),
                p.id().value().toString(),
                Long.toString(after.effectiveAt()),
                after.reportId(),
                Long.toString(after.revision()),
                Integer.toString(limit + 1)),
            limit + 1);
    var entries = rows.stream().limit(limit).map(r -> decodeReport(r.get("body"))).toList();
    for (var report : entries) {
      if (!report.world().equals(owner.world()) || !report.owner().equals(p.id()))
        throw new IllegalArgumentException("Debt report owner binding");
    }
    var last = entries.isEmpty() ? null : entries.getLast();
    return new ReportPage(
        entries,
        rows.size() > limit
            ? new ReportCursor(last.effectiveAt(), last.id().toString(), last.revision())
            : null);
  }

  public static boolean blocks(JournalEngine.ReadView reads, Kind kind, UUID facility, long now) {
    return facilityCase(reads, kind, facility).map(c -> demands(c, now)).orElse(true);
  }

  /** One saved facility per startup turn. Historical deadlines cannot be silently backdated. */
  public static boolean bootstrap(FoundationStore store) {
    var row =
        store.transaction(
            tx ->
                tx.query("SELECT * FROM debt_bootstrap WHERE singleton=1", List.of(), 1)
                    .getFirst());
    if (row.get("ready").equals("1")) return true;
    var kind = Kind.valueOf(row.get("kind"));
    String cursor = row.get("cursor");
    String table = kind == Kind.CARD ? "credit_facilities" : "loans";
    String column = kind == Kind.CARD ? "facility_id" : "loan_id";
    var source =
        store.transaction(
            tx ->
                tx.query(
                    "SELECT body,"
                        + column
                        + " AS id FROM "
                        + table
                        + " WHERE "
                        + column
                        + ">? ORDER BY "
                        + column
                        + " LIMIT 1",
                    List.of(cursor),
                    1));
    Snapshot snapshot =
        source.isEmpty()
            ? null
            : kind == Kind.CARD
                ? snapshot(CreditFacilities.decodeSnapshot(source.getFirst().get("body")))
                : snapshot(Loans.decode(source.getFirst().get("body")));
    var world = store.snapshot().worldId();
    var op = stable("bootstrap|" + world.value() + "|" + kind + "|" + cursor);
    var journalKind = new Journal.Kind("economy:debt", "economy:bootstrap");
    var engine =
        new JournalEngine(
            store,
            (a, k) -> true,
            Map.of(
                journalKind,
                new JournalEngine.Registration(
                    1,
                    (intent, reads) -> {
                      var facts = new ArrayList<Journal.Record>();
                      var updates = new ArrayList<Obligation>();
                      var reports = new ArrayList<Report>();
                      if (snapshot != null) {
                        long frontier =
                            reads
                                .query(
                                    "SELECT last_effective_ms FROM financial_calendar",
                                    List.of(),
                                    1)
                                .stream()
                                .mapToLong(r -> Long.parseLong(r.get("last_effective_ms")))
                                .findFirst()
                                .orElse(0);
                        boolean tracked =
                            !reads
                                .query(
                                    "SELECT facility_id FROM debt_facility_states WHERE world_id=? AND kind=? AND facility_id=?",
                                    List.of(
                                        world.value().toString(),
                                        kind.name(),
                                        snapshot.facility().toString()),
                                    1)
                                .isEmpty();
                        if (!tracked
                            && snapshot.obligations().stream()
                                .anyMatch(o -> Math.addExact(o.due(), 1) < frontier))
                          throw new StorageException(
                              StorageException.Kind.MIGRATION,
                              "DEBT_LEGACY_TIMELINE_REQUIRES_RECONCILIATION");
                        synchronize(reads, snapshot, snapshot.savedAt(), facts, updates, reports);
                      }
                      String next = source.isEmpty() ? "" : source.getFirst().get("id");
                      facts.add(new Journal.Record(ATTEMPT, 1, op + "|0|BOOTSTRAP_" + kind));
                      return new JournalEngine.Commit(
                          facts,
                          new Journal.Result(1, "DEBT_BOOTSTRAP_PAGE", next),
                          tx -> {
                            for (var o : updates) {
                              DebtRecords.write(tx, o);
                              tx.execute(
                                  "INSERT OR IGNORE INTO debt_due_queue VALUES(?,?,?,?)",
                                  List.of(
                                      o.world().value().toString(),
                                      o.kind().name(),
                                      o.id().toString(),
                                      Long.toString(o.dueAt() + 1)));
                            }
                            if (snapshot != null) {
                              writeState(tx, snapshot, snapshot.savedAt());
                              var due =
                                  snapshot.obligations().stream()
                                      .filter(o -> o.unmet() > 0)
                                      .mapToLong(Original::due)
                                      .min();
                              if (snapshot.status().equals("ACTIVE") && due.isPresent())
                                tx.execute(
                                    "INSERT INTO debt_default_heads VALUES(?,?,?,?) ON CONFLICT(world_id,kind,facility_id) DO UPDATE SET next_at=excluded.next_at",
                                    List.of(
                                        world.value().toString(),
                                        kind.name(),
                                        snapshot.facility().toString(),
                                        Long.toString(due.getAsLong() + DEFAULT_DELAY)));
                            }
                            if (source.isEmpty())
                              tx.execute(
                                  "UPDATE debt_bootstrap SET kind=?,cursor='',ready=? WHERE singleton=1",
                                  List.of("LOAN", kind == Kind.LOAN ? "1" : "0"));
                            else
                              tx.execute(
                                  "UPDATE debt_bootstrap SET cursor=? WHERE singleton=1",
                                  List.of(next));
                          });
                    })));
    var actor = new SystemActorId(stable("debt-bootstrap-actor|" + world.value()));
    var outcome =
        engine.submit(
            new Journal.Intent(
                new OperationId(op),
                new RequestId(op),
                actor,
                journalKind,
                new Journal.CanonicalRequest(1, Map.of("kind", kind.name(), "after", cursor)),
                null,
                null));
    if (outcome.status() != Journal.Status.COMMITTED)
      throw new IllegalStateException("Debt bootstrap page unavailable");
    return false;
  }

  private static boolean accountingCurrent(
      JournalEngine.ReadView reads,
      WorldId world,
      Kind kind,
      PlayerId owner,
      InstitutionCatalog.InstitutionId creditor,
      EconomyCurrency currency) {
    var borrower =
        new InstitutionCatalog.InstitutionId(
            (kind == Kind.CARD ? "economy:borrower/" : "economy:loan-borrower/") + owner.value());
    return DebtClaims.matches(reads, world, borrower, FinancialAccounts.Role.LIABILITY, currency)
        && DebtClaims.matches(reads, world, creditor, FinancialAccounts.Role.RECEIVABLE, currency);
  }

  private static JournalEngine.Decline decline(String code) {
    return new JournalEngine.Decline(new Journal.Result(1, code, ""));
  }

  private static void bounds(int limit) {
    if (limit < 1 || limit > 16) throw new IllegalArgumentException("Debt page bounds");
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return DebtRecords.register(DebtFinance.register(catalog))
        .with(
            new RecordCatalog.Key(STATE, 1),
            body -> {
              var p = body.split("\\|", -1);
              if (p.length != 7) throw new IllegalArgumentException("Debt state fields");
              UUID.fromString(p[0]);
              Kind.valueOf(p[1]);
              UUID.fromString(p[2]);
              UUID.fromString(p[3]);
              if (!Set.of(
                          "CURRENT",
                          "DELINQUENT",
                          "DEFAULTED",
                          "SETTLED",
                          "COLLECTIONS",
                          "UNCOLLECTABLE")
                      .contains(p[4])
                  || Long.parseLong(p[5]) < 0
                  || Long.parseLong(p[6]) < 1)
                throw new IllegalArgumentException("Debt state values");
            })
        .with(
            new RecordCatalog.Key(ATTEMPT, 1),
            body -> {
              var p = body.split("\\|", -1);
              if (p.length != 3
                  || Long.parseLong(p[1]) < 0
                  || !p[2].matches("[A-Z][A-Z0-9_]{0,95}"))
                throw new IllegalArgumentException("Debt sale receipt");
              UUID.fromString(p[0]);
            });
  }
}
