package ca.emilybelnavis.mcmods.economy.autopay;

import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.debt.DebtRecords.Kind;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.loan.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;

/** Owner consent and exact original-window targets. All reads and effects are worker-confined. */
public final class Autopay {
  public static final Journal.Kind CONFIGURE =
      new Journal.Kind("economy:autopay", "economy:configure");
  public static final String SETTING = "economy:a6/autopay-setting",
      WINDOW = "economy:a6/autopay-window",
      BOOTSTRAP = "economy:a6/autopay-bootstrap";

  public enum Mode {
    MINIMUM,
    STATEMENT,
    FULL_PAYOFF,
    LOAN_DUE
  }

  public enum State {
    WAITING,
    SATISFIED,
    EXHAUSTED,
    CANCELLED
  }

  public record Settings(
      WorldId world,
      PlayerId owner,
      Kind kind,
      UUID facility,
      boolean enabled,
      AccountId funding,
      Mode mode,
      long effectiveAt,
      long revision) {
    public Settings {
      Objects.requireNonNull(world);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(facility);
      Objects.requireNonNull(mode);
      if (effectiveAt < 0
          || revision < 1
          || enabled && funding == null
          || (kind == Kind.LOAN) != (mode == Mode.LOAN_DUE))
        throw new IllegalArgumentException("Autopay settings");
    }

    public PartyRef party() {
      return new PartyRef(world, new PartyRef.Player(owner));
    }
  }

  public record Configure(
      WorldId world,
      PlayerId owner,
      Kind kind,
      UUID facility,
      long expectedRevision,
      long facilityRevision,
      boolean enabled,
      AccountId funding,
      long fundingRevision,
      Mode mode) {
    public Configure {
      Objects.requireNonNull(world);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(facility);
      Objects.requireNonNull(mode);
      if (expectedRevision < 0
          || facilityRevision < 1
          || fundingRevision < 0
          || enabled && (funding == null || fundingRevision < 1)
          || (kind == Kind.LOAN) != (mode == Mode.LOAN_DUE))
        throw new IllegalArgumentException("Autopay consent");
    }

    public Journal.CanonicalRequest request() {
      var fields = new TreeMap<String, String>();
      fields.put("world", world.value().toString());
      fields.put("economy:owner", Journal.actorKey(owner));
      fields.put("kind", kind.name());
      fields.put("facility", facility.toString());
      fields.put("revision", Long.toString(expectedRevision));
      fields.put("facility_revision", Long.toString(facilityRevision));
      fields.put("enabled", Boolean.toString(enabled));
      fields.put("funding", funding == null ? "" : funding.value().toString());
      fields.put("funding_revision", Long.toString(fundingRevision));
      fields.put("mode", mode.name());
      return new Journal.CanonicalRequest(1, fields);
    }
  }

  record Window(
      Settings consent,
      long dueAt,
      String obligation,
      int attempts,
      State state,
      long targetMinor,
      long remainingMinor,
      Long nextAt,
      long effectiveAt,
      String reason) {
    Window {
      Objects.requireNonNull(consent);
      Objects.requireNonNull(obligation);
      Objects.requireNonNull(state);
      Objects.requireNonNull(reason);
      if (dueAt < 0
          || effectiveAt < dueAt
          || attempts < 0
          || attempts > 3
          || targetMinor < 0
          || remainingMinor < 0
          || obligation.isBlank()
          || obligation.length() > 128
          || !reason.matches("[A-Z_]{1,96}")) throw new IllegalArgumentException("Autopay window");
    }
  }

  private Autopay() {}

  public static Optional<Settings> find(ReadView reads, PartyRef party, Kind kind, UUID facility) {
    if (!(party.owner() instanceof PartyRef.Player owner)) return Optional.empty();
    return reads
        .query(
            "SELECT body FROM autopay_settings WHERE world_id=? AND owner_id=? AND kind=? AND facility_id=?",
            List.of(
                party.world().value().toString(),
                owner.id().value().toString(),
                kind.name(),
                facility.toString()),
            1)
        .stream()
        .map(r -> decodeSettings(r.get("body")))
        .findFirst();
  }

  static Optional<Window> waiting(ReadView reads, Settings settings) {
    return reads
        .query(
            "SELECT body FROM autopay_windows WHERE world_id=? AND kind=? AND facility_id=? AND state='WAITING' ORDER BY due_at LIMIT 1",
            List.of(
                settings.world().value().toString(),
                settings.kind().name(),
                settings.facility().toString()),
            1)
        .stream()
        .map(r -> decodeWindow(r.get("body")))
        .findFirst();
  }

  public static Registration configuration(PartyRef party, Configure request, LongSupplier clock) {
    return new Registration(
        1,
        (intent, reads) -> {
          if (!party.equals(new PartyRef(request.world(), new PartyRef.Player(request.owner())))
              || !intent.actor().equals(request.owner())
              || !intent.kind().equals(CONFIGURE)
              || !intent.request().equals(request.request())) return reject("AUTOPAY_ACCESS");
          var old = find(reads, party, request.kind(), request.facility()).orElse(null);
          if ((old == null ? 0 : old.revision()) != request.expectedRevision())
            return reject("AUTOPAY_STALE");
          long now = clock.getAsLong();
          EconomyCurrency currency;
          long revision;
          boolean active;
          long next;
          if (request.kind() == Kind.CARD) {
            var card =
                CreditFacilities.find(reads, request.facility())
                    .filter(c -> c.party().equals(party))
                    .orElse(null);
            if (card == null) return reject("AUTOPAY_ACCESS");
            currency = card.currency();
            revision = card.revision();
            active =
                card.status() == CreditFacilities.Status.ACTIVE
                    && card.access() != CreditFacilities.Access.CLOSED;
            var statement = tracker(reads, request.world(), request.facility());
            next =
                statement != null && statement.dueAt() > now
                    ? statement.dueAt()
                    : Math.addExact(card.nextStatement(), 14 * CardServicing.DAY_MILLIS);
          } else {
            var loan =
                Loans.find(reads, request.facility())
                    .filter(l -> l.party().equals(party))
                    .orElse(null);
            if (loan == null) return reject("AUTOPAY_ACCESS");
            currency = loan.currency();
            revision = loan.revision();
            active = loan.status() == Loans.Status.ACTIVE;
            next = loan.nextMonth();
          }
          if (revision != request.facilityRevision()) return reject("AUTOPAY_FACILITY_STALE");
          if (request.enabled()) {
            if (!active || next <= now) return reject("AUTOPAY_FACILITY_CLOSED");
            var account = AccountCatalog.find(reads, party, request.funding()).orElse(null);
            if (account == null
                || account.purpose() != AccountIdentity.Purpose.BANK
                || account.status() != AccountIdentity.Status.OPEN
                || account.currency() != currency
                || account.revision() != request.fundingRevision())
              return reject("AUTOPAY_FUNDING_STALE");
            if (!ready(reads))
              return new Decline(new Journal.Result(1, "AUTOPAY_HISTORY_PENDING", ""));
          }
          var settings =
              new Settings(
                  request.world(),
                  request.owner(),
                  request.kind(),
                  request.facility(),
                  request.enabled(),
                  request.funding(),
                  request.mode(),
                  now,
                  Math.addExact(request.expectedRevision(), 1));
          var records = new ArrayList<Journal.Record>();
          records.add(settingFact(settings));
          var pending = old == null ? null : waiting(reads, old).orElse(null);
          var cancelled =
              pending == null
                  ? null
                  : new Window(
                      pending.consent(),
                      pending.dueAt(),
                      pending.obligation(),
                      pending.attempts(),
                      State.CANCELLED,
                      pending.targetMinor(),
                      pending.remainingMinor(),
                      null,
                      Math.max(now, pending.dueAt()),
                      "CONSENT_CHANGED");
          if (cancelled != null) records.add(windowFact(cancelled));
          long due = next;
          return new Commit(
              records,
              new Journal.Result(
                  1,
                  request.enabled() ? "AUTOPAY_ENABLED" : "AUTOPAY_DISABLED",
                  request.facility().toString()),
              tx -> {
                if (cancelled != null) writeWindow(tx, cancelled);
                writeSettings(tx, settings, settings.enabled() ? due : null);
              });
        });
  }

  public static boolean linked(ReadView reads, AccountId funding) {
    return !reads
        .query(
            "SELECT facility_id FROM autopay_settings WHERE funding_id=? AND enabled=1 LIMIT 1",
            List.of(funding.value().toString()),
            1)
        .isEmpty();
  }

  record Tracker(
      WorldId world,
      UUID facility,
      String statement,
      long closedAt,
      long dueAt,
      long balance,
      long remaining) {}

  static Tracker tracker(ReadView reads, WorldId world, UUID facility) {
    return reads
        .query(
            "SELECT * FROM autopay_statements WHERE world_id=? AND facility_id=?",
            List.of(world.value().toString(), facility.toString()),
            1)
        .stream()
        .map(
            r ->
                new Tracker(
                    world,
                    facility,
                    r.get("statement_id"),
                    number(r, "closed_at"),
                    number(r, "due_at"),
                    number(r, "balance_minor"),
                    number(r, "remaining_minor")))
        .findFirst()
        .orElse(null);
  }

  static void writeTracker(FoundationStore.Transaction tx, Tracker t) {
    tx.execute(
        "INSERT INTO autopay_statements VALUES(?,?,?,?,?,?,?) ON CONFLICT(world_id,facility_id) DO UPDATE SET statement_id=excluded.statement_id,closed_at=excluded.closed_at,due_at=excluded.due_at,balance_minor=excluded.balance_minor,remaining_minor=excluded.remaining_minor",
        List.of(
            t.world().value().toString(),
            t.facility().toString(),
            t.statement(),
            Long.toString(t.closedAt()),
            Long.toString(t.dueAt()),
            Long.toString(t.balance()),
            Long.toString(t.remaining())));
  }

  static void writeSettings(FoundationStore.Transaction tx, Settings s, Long next) {
    tx.execute(
        "INSERT INTO autopay_settings VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(world_id,kind,facility_id) DO UPDATE SET enabled=excluded.enabled,funding_id=excluded.funding_id,mode=excluded.mode,effective_at=excluded.effective_at,revision=excluded.revision,next_at=excluded.next_at,body=excluded.body",
        Arrays.asList(
            s.world().value().toString(),
            s.kind().name(),
            s.facility().toString(),
            s.owner().value().toString(),
            s.enabled() ? "1" : "0",
            s.funding() == null ? null : s.funding().value().toString(),
            s.mode().name(),
            Long.toString(s.effectiveAt()),
            Long.toString(s.revision()),
            next == null ? null : next.toString(),
            encodeSettings(s)));
  }

  static void writeWindow(FoundationStore.Transaction tx, Window w) {
    var s = w.consent();
    tx.execute(
        "INSERT INTO autopay_windows VALUES(?,?,?,?,?,?) ON CONFLICT(world_id,kind,facility_id,due_at) DO UPDATE SET state=excluded.state,body=excluded.body",
        List.of(
            s.world().value().toString(),
            s.kind().name(),
            s.facility().toString(),
            Long.toString(w.dueAt()),
            w.state().name(),
            encodeWindow(w)));
  }

  static Journal.Record settingFact(Settings s) {
    return new Journal.Record(SETTING, 1, encodeSettings(s));
  }

  static Journal.Record windowFact(Window w) {
    return new Journal.Record(WINDOW, 1, encodeWindow(w));
  }

  static String encodeSettings(Settings s) {
    return String.join(
        "|",
        s.world().value().toString(),
        s.owner().value().toString(),
        s.kind().name(),
        s.facility().toString(),
        Boolean.toString(s.enabled()),
        s.funding() == null ? "" : s.funding().value().toString(),
        s.mode().name(),
        Long.toString(s.effectiveAt()),
        Long.toString(s.revision()));
  }

  static Settings decodeSettings(String body) {
    var p = body.split("\\|", -1);
    if (p.length != 9) throw new IllegalArgumentException("Autopay setting fact");
    if (!Set.of("true", "false").contains(p[4]))
      throw new IllegalArgumentException("Autopay consent flag");
    return new Settings(
        new WorldId(UUID.fromString(p[0])),
        new PlayerId(UUID.fromString(p[1])),
        Kind.valueOf(p[2]),
        UUID.fromString(p[3]),
        Boolean.parseBoolean(p[4]),
        p[5].isEmpty() ? null : new AccountId(UUID.fromString(p[5])),
        Mode.valueOf(p[6]),
        Long.parseLong(p[7]),
        Long.parseLong(p[8]));
  }

  static String encodeWindow(Window w) {
    return String.join(
        "|",
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(encodeSettings(w.consent()).getBytes(StandardCharsets.UTF_8)),
        Long.toString(w.dueAt()),
        w.obligation(),
        Integer.toString(w.attempts()),
        w.state().name(),
        Long.toString(w.targetMinor()),
        Long.toString(w.remainingMinor()),
        w.nextAt() == null ? "" : w.nextAt().toString(),
        Long.toString(w.effectiveAt()),
        w.reason());
  }

  static Window decodeWindow(String body) {
    var p = body.split("\\|", -1);
    if (p.length != 10) throw new IllegalArgumentException("Autopay window fact");
    return new Window(
        decodeSettings(new String(Base64.getUrlDecoder().decode(p[0]), StandardCharsets.UTF_8)),
        Long.parseLong(p[1]),
        p[2],
        Integer.parseInt(p[3]),
        State.valueOf(p[4]),
        Long.parseLong(p[5]),
        Long.parseLong(p[6]),
        p[7].isEmpty() ? null : Long.parseLong(p[7]),
        Long.parseLong(p[8]),
        p[9]);
  }

  static long number(Map<String, String> row, String key) {
    return Long.parseLong(row.get(key));
  }

  private static Reject reject(String code) {
    return new Reject(new Journal.Result(1, code, ""));
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog
        .with(
            new RecordCatalog.Key(SETTING, 1),
            body -> {
              var s = decodeSettings(body);
              if (!encodeSettings(s).equals(body))
                throw new IllegalArgumentException("Autopay settings encoding");
            })
        .with(
            new RecordCatalog.Key(WINDOW, 1),
            body -> {
              var w = decodeWindow(body);
              if (!encodeWindow(w).equals(body))
                throw new IllegalArgumentException("Autopay window encoding");
            })
        .with(new RecordCatalog.Key(BOOTSTRAP, 1), AutopayHistory::validate);
  }

  public static boolean ready(ReadView reads) {
    return reads
        .query("SELECT ready FROM autopay_bootstrap WHERE singleton=1", List.of(), 1)
        .getFirst()
        .get("ready")
        .equals("1");
  }

  public static boolean bootstrap(FoundationStore store) {
    return AutopayHistory.bootstrap(store);
  }

  public static Commit decorate(ReadView reads, Commit commit) {
    return AutopayHistory.decorate(reads, commit);
  }
}
