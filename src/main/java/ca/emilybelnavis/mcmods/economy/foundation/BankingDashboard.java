package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.banking.BankingDashboardView;
import ca.emilybelnavis.mcmods.economy.banking.BankingDashboardView.*;
import ca.emilybelnavis.mcmods.economy.credit.CreditFacilities;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.journal.RebaseSettlement;
import ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy;
import ca.emilybelnavis.mcmods.economy.loan.Loans;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

/** Bounded worker-side projection. The caller supplies one authorized transaction's read view. */
public final class BankingDashboard {
  private static final int DOMAIN_SAMPLE = 3;
  static final String RECENT_QUERY =
      """
            SELECT r.sequence,r.position,r.payload,t.transaction_id,t.operation_id,t.audit_utc,(
                SELECT d.payload FROM journal_records d INDEXED BY ledger_rebase_latest
                WHERE d.record_type='economy:rebase' AND d.record_version=1 AND d.sequence<=r.sequence
                ORDER BY d.sequence DESC LIMIT 1
            ) AS denomination
            FROM journal_records r INDEXED BY ledger_posting_account
            JOIN journal_transactions t ON t.sequence=r.sequence
            WHERE r.record_type='economy:posting' AND r.record_version IN (1,2,3,4)
              AND substr(r.payload,1,36)=? AND t.world_id=?
            ORDER BY r.sequence DESC,r.position DESC LIMIT 5
            """;

  private BankingDashboard() {}

  public static BankingDashboardView read(
      JournalEngine.ReadView reads,
      PartyRef owner,
      AccountId selected,
      TerminalPolicy.Anchor anchor,
      long capturedAt) {
    if (!(owner.owner() instanceof PartyRef.Player player))
      throw new IllegalArgumentException("Dashboard player owner");
    String institution = "", currency = "";
    if (anchor != null) {
      var terminal =
          TerminalPolicy.find(reads, owner.world(), anchor, player.id(), true).orElse(null);
      if (terminal != null && terminal.binding() != null && terminal.binding().bound()) {
        institution = terminal.binding().institution().key();
        currency = terminal.binding().nativeCurrency().code();
      }
    }
    if (currency.isEmpty())
      currency = RebaseSettlement.denomination(reads).map(d -> d.currency().code()).orElse("");

    Section<AccountRow> accounts;
    try {
      var rows =
          AccountCatalog.openBanks(reads, owner).stream()
              .map(
                  account -> {
                    var funds = LedgerService.bankFunds(reads, account).orElseThrow();
                    return new AccountRow(
                        account.id().value(),
                        new Money(funds.currency(), funds.availableMinorUnits()),
                        new Money(funds.currency(), funds.reservedMinorUnits()),
                        account.primary());
                  })
              .toList();
      accounts = Section.available(rows, false);
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException unavailable) {
      accounts = Section.unavailable("screen.economy.dashboard.accounts_unavailable");
    }

    var obligations = new ArrayList<Obligation>();
    Section<DebtRow> credit;
    boolean creditMore = false;
    try {
      var facilities = CreditFacilities.history(reads, owner, null, DOMAIN_SAMPLE + 1);
      creditMore = facilities.size() > DOMAIN_SAMPLE;
      var sample = facilities.stream().limit(DOMAIN_SAMPLE).toList();
      credit =
          Section.available(
              sample.stream()
                  .map(
                      f ->
                          new DebtRow(
                              f.id(),
                              new Money(f.currency(), f.owedMinor()),
                              capturedAt >= f.lastDay()
                                  && (f.status() != CreditFacilities.Status.ACTIVE
                                      || capturedAt < f.nextDay()
                                          && capturedAt < f.nextStatement())))
                  .toList(),
              creditMore);
      for (var f : sample)
        for (var requirement : f.requirements())
          if (requirement.unmetMinor() > 0)
            obligations.add(
                new Obligation(
                    requirement.id(),
                    f.id(),
                    "credit",
                    requirement.dueAt(),
                    new Money(f.currency(), requirement.unmetMinor())));
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException unavailable) {
      credit = Section.unavailable("screen.economy.dashboard.credit_unavailable");
    }

    Section<DebtRow> loans;
    boolean loanMore = false;
    try {
      var contracts = Loans.history(reads, owner, null, DOMAIN_SAMPLE + 1);
      loanMore = contracts.size() > DOMAIN_SAMPLE;
      var sample = contracts.stream().limit(DOMAIN_SAMPLE).toList();
      loans =
          Section.available(
              sample.stream()
                  .map(
                      l ->
                          new DebtRow(
                              l.id(),
                              new Money(l.currency(), l.servicing().debtMinor()),
                              capturedAt >= l.servicing().lastAccrued()
                                  && (l.status() != Loans.Status.ACTIVE
                                      || capturedAt < l.nextMonth())))
                  .toList(),
              loanMore);
      for (var loan : sample) {
        for (var requirement : loan.servicing().requirements())
          if (requirement.unmetMinor() > 0)
            obligations.add(
                new Obligation(
                    requirement.id(),
                    loan.id(),
                    "loan",
                    requirement.dueAt(),
                    new Money(loan.currency(), requirement.unmetMinor())));
        // The retained original schedule supplies the next contractual installment even before it
        // becomes due.
        if (loan.status() == Loans.Status.ACTIVE) {
          var next =
              Loans.schedule(reads, owner, loan.id(), loan.servicing().completedPeriods(), 1);
          if (!next.isEmpty()) {
            var row = next.getFirst();
            obligations.add(
                new Obligation(
                    "schedule/" + row.ordinal(),
                    loan.id(),
                    "loan_schedule",
                    row.dueAt(),
                    new Money(loan.currency(), row.paymentMinor())));
          }
        }
      }
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException unavailable) {
      loans = Section.unavailable("screen.economy.dashboard.loans_unavailable");
    }

    Section<Obligation> upcoming;
    if (credit.availability() != Availability.AVAILABLE
        || loans.availability() != Availability.AVAILABLE)
      upcoming = Section.unavailable("screen.economy.dashboard.upcoming_unavailable");
    else {
      obligations.sort(
          Comparator.comparingLong(Obligation::dueAt)
              .thenComparing(o -> o.facility().toString())
              .thenComparing(Obligation::id));
      upcoming =
          Section.available(
              obligations.stream().limit(BankingDashboardView.MAX_ROWS).toList(),
              creditMore || loanMore || obligations.size() > BankingDashboardView.MAX_ROWS);
    }

    var account =
        selected == null
            ? AccountCatalog.primary(reads, owner).orElse(null)
            : AccountCatalog.find(reads, owner, selected).orElse(null);
    UUID recentAccount = account == null ? null : account.id().value();
    Section<Recent> recent;
    if (account == null || account.purpose() != AccountIdentity.Purpose.BANK)
      recent = Section.unavailable("screen.economy.dashboard.recent_account_required");
    else {
      try {
        var page =
            ca.emilybelnavis.mcmods.economy.journal.OwnerHistory.page(
                reads,
                owner,
                new ca.emilybelnavis.mcmods.economy.journal.OwnerHistory.Query(
                    account.id().value(),
                    null,
                    null,
                    null,
                    ca.emilybelnavis.mcmods.economy.journal.OwnerHistory.Type.ALL,
                    4),
                ca.emilybelnavis.mcmods.economy.journal.OwnerHistory.highWater(reads),
                null);
        recent =
            page.availability()
                    == ca.emilybelnavis.mcmods.economy.journal.OwnerHistory.Availability.AVAILABLE
                ? Section.available(
                    page.rows().stream()
                        .map(
                            r ->
                                new Recent(
                                    r.transaction(),
                                    r.operation(),
                                    r.sequence(),
                                    r.position(),
                                    r.amount(),
                                    r.denominationRevision(),
                                    r.recordedAt()))
                        .toList(),
                    page.next() != null)
                : Section.unavailable("screen.economy.dashboard.recent_unavailable");
      } catch (IllegalArgumentException | IllegalStateException | ArithmeticException unavailable) {
        recent = Section.unavailable("screen.economy.dashboard.recent_unavailable");
      }
    }
    return new BankingDashboardView(
        owner.world().value(),
        capturedAt,
        institution,
        currency,
        recentAccount,
        accounts,
        credit,
        loans,
        upcoming,
        recent);
  }

  private static Recent recent(Map<String, String> row) {
    var fields = row.get("payload").split("\\|", -1);
    String denomination = row.get("denomination");
    long revision = denomination == null ? 0 : Long.parseLong(denomination.split("\\|", -1)[1]);
    return new Recent(
        UUID.fromString(row.get("transaction_id")),
        UUID.fromString(row.get("operation_id")),
        Long.parseLong(row.get("sequence")),
        Integer.parseInt(row.get("position")),
        new Money(EconomyCurrency.fromCode(fields[2]), Long.parseLong(fields[3])),
        revision,
        Instant.parse(row.get("audit_utc")).toEpochMilli());
  }
}
