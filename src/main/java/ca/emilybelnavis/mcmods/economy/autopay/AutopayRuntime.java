package ca.emilybelnavis.mcmods.economy.autopay;

import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.debt.DebtRecords.Kind;
import ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.loan.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Stage3 payments precede stage4 delinquency/default and stage5 reporting. */
public final class AutopayRuntime {
  public static final String PROVIDER = "economy:a6/autopay";
  private static final long DAY = CardServicing.DAY_MILLIS;

  private AutopayRuntime() {}

  public static DeadlineDispatcher.Provider provider() {
    return new DeadlineDispatcher.Provider() {
      public String id() {
        return PROVIDER;
      }

      public Map<Integer, String> versions() {
        return Map.of(1, "autopay-original-window-v1");
      }

      public DeadlineDispatcher.Event next(
          EconomicClock.Queries reads, long origin, DeadlineDispatcher.Event completed) {
        var active =
            reads.query(
                "SELECT event_id,effective_ms,stage,version FROM financial_deadline_events WHERE provider=? AND state='APPLYING' ORDER BY effective_ms LIMIT 1",
                List.of(id()),
                1);
        if (!active.isEmpty()) {
          var row = active.getFirst();
          return new DeadlineDispatcher.Event(
              UUID.fromString(row.get("event_id")),
              id(),
              Long.parseLong(row.get("effective_ms")),
              3,
              Integer.parseInt(row.get("version")));
        }
        var head =
            reads.query(
                "SELECT next_at FROM autopay_settings WHERE enabled=1 AND next_at IS NOT NULL ORDER BY next_at,kind,facility_id LIMIT 1",
                List.of(),
                1);
        if (head.isEmpty()) return null;
        long at = Long.parseLong(head.getFirst().get("next_at"));
        if (completed != null && at <= completed.effectiveMillis())
          throw new IllegalStateException("Autopay head failed to advance");
        var world =
            reads
                .query("SELECT world_id FROM foundation_metadata", List.of(), 1)
                .getFirst()
                .get("world_id");
        return new DeadlineDispatcher.Event(
            UUID.nameUUIDFromBytes(
                ("economy:autopay:v1|" + world + "|" + at).getBytes(StandardCharsets.UTF_8)),
            id(),
            at,
            3,
            1);
      }

      public List<String> targets(
          DeadlineDispatcher.Event event, String after, int limit, EconomicClock.Queries reads) {
        return reads
            .query(
                "SELECT kind||'/'||facility_id AS target FROM autopay_settings WHERE enabled=1 AND next_at=? AND kind||'/'||facility_id>? ORDER BY kind,facility_id LIMIT ?",
                List.of(Long.toString(event.effectiveMillis()), after, Integer.toString(limit)),
                limit)
            .stream()
            .map(r -> r.get("target"))
            .toList();
      }

      public EconomicClock.Output prepare(
          DeadlineDispatcher.Event event, String target, EconomicClock.Queries queries) {
        try {
          var parts = target.split("/", 2);
          var kind = Kind.valueOf(parts[0]);
          var id = UUID.fromString(parts[1]);
          ReadView reads = queries::query;
          var rows =
              reads.query(
                  "SELECT body,next_at FROM autopay_settings WHERE kind=? AND facility_id=? AND enabled=1",
                  List.of(kind.name(), id.toString()),
                  1);
          if (rows.isEmpty()
              || Long.parseLong(rows.getFirst().get("next_at")) != event.effectiveMillis())
            throw new IllegalStateException("Autopay membership changed");
          var settings = Autopay.decodeSettings(rows.getFirst().get("body"));
          var commit = attempt(reads, settings, event.effectiveMillis());
          return new EconomicClock.Output(target, commit.projections(), commit.records());
        } catch (RuntimeException failure) {
          throw failure;
        } catch (Exception failure) {
          throw new IllegalStateException("Automatic payment unavailable", failure);
        }
      }
    };
  }

  static long futureDue(CreditFacilities.Facility card, Loans.Contract loan) {
    return card == null ? loan.nextMonth() : Math.addExact(card.nextStatement(), 14 * DAY);
  }

  static long remaining(
      Autopay.Window window,
      CreditFacilities.Facility card,
      Loans.Contract loan,
      Autopay.Tracker tracker) {
    return switch (window.consent().mode()) {
      case FULL_PAYOFF -> card.owedMinor();
      case STATEMENT -> {
        if (tracker == null || !tracker.statement().equals(window.obligation()))
          throw new IllegalStateException("Original statement target unavailable");
        yield Math.min(tracker.remaining(), card.owedMinor());
      }
      case MINIMUM -> Math.min(card.owedMinor(), requiredCard(card, window.dueAt()));
      case LOAN_DUE -> Math.min(loan.servicing().debtMinor(), requiredLoan(loan, window.dueAt()));
    };
  }

  private static long requiredCard(CreditFacilities.Facility card, long due) {
    long amount = 0;
    for (var requirement : card.requirements())
      if (requirement.dueAt() <= due) amount = Math.addExact(amount, requirement.unmetMinor());
    return amount;
  }

  private static long requiredLoan(Loans.Contract loan, long due) {
    long amount = 0;
    for (var requirement : loan.servicing().requirements())
      if (requirement.dueAt() <= due) amount = Math.addExact(amount, requirement.unmetMinor());
    return amount;
  }

  private static Commit attempt(ReadView reads, Autopay.Settings settings, long at)
      throws Exception {
    var card =
        settings.kind() == Kind.CARD
            ? CreditFacilities.find(reads, settings.facility())
                .filter(c -> c.party().equals(settings.party()))
                .orElseThrow()
            : null;
    var loan =
        card == null
            ? Loans.find(reads, settings.facility())
                .filter(l -> l.party().equals(settings.party()))
                .orElseThrow()
            : null;
    var old = Autopay.waiting(reads, settings).orElse(null);
    var tracker = card == null ? null : Autopay.tracker(reads, settings.world(), card.id());
    boolean active =
        card != null
            ? card.status() == CreditFacilities.Status.ACTIVE
                && card.access() != CreditFacilities.Access.CLOSED
            : loan.status() == Loans.Status.ACTIVE;
    if (old != null && old.consent().revision() != settings.revision())
      throw new IllegalStateException("Autopay original consent conflict");
    if (!active && old == null)
      old =
          new Autopay.Window(
              settings,
              at,
              "closed:" + settings.facility(),
              0,
              Autopay.State.WAITING,
              0,
              0,
              at,
              at,
              "DUE");
    if (!active)
      return progress(
          null, settings, old, Autopay.State.CANCELLED, 0, null, at, "FACILITY_CLOSED", true);
    if (old == null) {
      String obligation;
      if (card != null) {
        if (tracker == null || tracker.dueAt() != at)
          throw new IllegalStateException("Due statement provenance unavailable");
        obligation = tracker.statement();
      } else {
        var source =
            reads.query(
                "SELECT installment_id FROM loan_installments WHERE world_id=? AND loan_id=? AND due_at=? LIMIT 1",
                List.of(
                    settings.world().value().toString(), loan.id().toString(), Long.toString(at)),
                1);
        if (source.isEmpty()) throw new IllegalStateException("Due loan installment unavailable");
        obligation = source.getFirst().get("installment_id");
      }
      old =
          new Autopay.Window(
              settings, at, obligation, 0, Autopay.State.WAITING, 0, 0, at, at, "DUE");
      long amount = remaining(old, card, loan, tracker);
      old =
          new Autopay.Window(
              settings, at, obligation, 0, Autopay.State.WAITING, amount, amount, at, at, "DUE");
    }
    if (at < old.dueAt() || at > Math.addExact(old.dueAt(), 2 * DAY) || old.attempts() >= 3)
      throw new IllegalStateException("Autopay retry window");
    long amount = remaining(old, card, loan, tracker);
    if (amount == 0)
      return progress(
          null,
          settings,
          old,
          Autopay.State.SATISFIED,
          0,
          futureDue(card, loan),
          at,
          "TARGET_SATISFIED",
          false);
    var account = AccountCatalog.find(reads, settings.party(), settings.funding()).orElse(null);
    var currency = card == null ? loan.currency() : card.currency();
    if (account == null
        || account.status() != AccountIdentity.Status.OPEN
        || account.purpose() != AccountIdentity.Purpose.BANK
        || account.currency() != currency)
      return progress(
          null, settings, old, Autopay.State.CANCELLED, amount, null, at, "FUNDING_CLOSED", true);
    if (Holds.available(reads, account.id().value(), currency) < amount) {
      boolean exhausted = old.attempts() == 2;
      return progress(
          null,
          settings,
          old,
          exhausted ? Autopay.State.EXHAUSTED : Autopay.State.WAITING,
          amount,
          exhausted ? futureDue(card, loan) : Math.addExact(at, DAY),
          at,
          "INSUFFICIENT_FUNDS",
          false);
    }
    var funding =
        List.of(
            new PostingBatch.Leg(
                account.id().value(),
                PostingBatch.Role.ACCOUNT,
                currency,
                new Money(currency, -amount)));
    Decision paid;
    if (card != null) paid = CreditFacilities.repay(reads, card, amount, at, funding, List.of());
    else {
      var request =
          new Loans.RepaymentRequest(
              settings.world(),
              settings.owner(),
              loan.id(),
              account.id(),
              amount,
              loan.revision(),
              account.revision(),
              ca.emilybelnavis.mcmods.economy.pricing.WealthValuation.ledgerRevision(
                  reads, account.id().value()),
              UUID.nameUUIDFromBytes(
                  (loan.id() + "|autopay|" + old.dueAt() + "|" + at)
                      .getBytes(StandardCharsets.UTF_8)),
              at,
              Math.min(Math.addExact(at, 30_000), loan.nextMonth()),
              false);
      paid =
          Loans.planRepayment(
              reads,
              loan,
              new Loans.PaymentQuote(request, null),
              at,
              funding,
              List.<FinancialAccounts.Account>of());
    }
    if (!(paid instanceof Commit captured))
      throw new IllegalStateException("Autopay financial preparation rejected " + paid);
    boolean settled =
        loan != null
            && captured.records().stream()
                .filter(r -> r.type().equals(Loans.FACT))
                .map(r -> Loans.decode(r.payload()))
                .anyMatch(l -> l.status() != Loans.Status.ACTIVE);
    return progress(
        captured,
        settings,
        old,
        Autopay.State.SATISFIED,
        0,
        settled ? null : futureDue(card, loan),
        at,
        "PAID",
        settled);
  }

  private static Commit progress(
      Commit payment,
      Autopay.Settings settings,
      Autopay.Window old,
      Autopay.State state,
      long remaining,
      Long next,
      long at,
      String reason,
      boolean disable) {
    var window =
        new Autopay.Window(
            old.consent(),
            old.dueAt(),
            old.obligation(),
            old.attempts() + 1,
            state,
            old.targetMinor(),
            remaining,
            next,
            at,
            reason);
    var current =
        disable
            ? new Autopay.Settings(
                settings.world(),
                settings.owner(),
                settings.kind(),
                settings.facility(),
                false,
                settings.funding(),
                settings.mode(),
                at,
                Math.addExact(settings.revision(), 1))
            : settings;
    var records = new ArrayList<Journal.Record>();
    if (payment != null) records.addAll(payment.records());
    records.add(Autopay.windowFact(window));
    if (disable) records.add(Autopay.settingFact(current));
    return new Commit(
        records,
        new Journal.Result(1, "AUTOPAY_" + reason, settings.facility().toString()),
        tx -> {
          if (payment != null) payment.projections().apply(tx);
          Autopay.writeWindow(tx, window);
          Autopay.writeSettings(tx, current, next);
        });
  }
}
