package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.CreditFacilities;
import ca.emilybelnavis.mcmods.economy.debt.DebtRecords;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.loan.LoanDisbursementFx;
import ca.emilybelnavis.mcmods.economy.loan.Loans;
import ca.emilybelnavis.mcmods.economy.pricing.ServicePricing;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.trade.settlement.NpcFacts;
import ca.emilybelnavis.mcmods.economy.trade.settlement.ReferenceNpcFacts;
import java.time.Instant;
import java.util.*;

/**
 * Exact, owner-visible original facts. No current rate, pricing policy or opposing posting supplies
 * missing terms.
 */
public final class HistoryDetails {
  private HistoryDetails() {}

  private static final class Decoders {
    static final RecordCatalog CATALOG = RecordCatalog.core();
  }

  public enum Availability {
    KNOWN,
    NOT_RECORDED,
    UNAVAILABLE
  }

  public enum Visibility {
    CAPTURED,
    PRIVATE,
    NOT_RECORDED
  }

  public record Movement(
      UUID account, int position, Money amount, long denominationRevision, int postingVersion) {
    public Movement {
      Objects.requireNonNull(account);
      Objects.requireNonNull(amount);
      if (position < 0
          || position > 63
          || denominationRevision < 0
          || postingVersion < 1
          || postingVersion > 4) throw new IllegalArgumentException("History movement bounds");
    }
  }

  public record Fee(Availability availability, Money amount) {
    public Fee {
      Objects.requireNonNull(availability);
      if ((availability == Availability.KNOWN) != (amount != null)
          || amount != null && amount.minorUnits() < 0)
        throw new IllegalArgumentException("History fee state");
    }
  }

  public record Counterparty(Visibility visibility, String label) {
    public Counterparty {
      Objects.requireNonNull(visibility);
      text(label);
      if ((visibility == Visibility.CAPTURED) != !label.isEmpty())
        throw new IllegalArgumentException("History counterparty state");
    }
  }

  /**
   * numerator/denominator are target units per source unit under the retained publication's
   * original direction.
   */
  public record CapturedFx(
      UUID publication,
      String family,
      long effectiveAt,
      String source,
      String target,
      String numerator,
      String denominator,
      int version,
      Availability availability) {
    public CapturedFx {
      Objects.requireNonNull(publication);
      Objects.requireNonNull(availability);
      for (var value : List.of(family, source, target, numerator, denominator)) text(value);
      EconomyCurrency.fromCode(source);
      EconomyCurrency.fromCode(target);
      if (effectiveAt < 0
          || version < 0
          || availability == Availability.KNOWN
              && (version < 1 || numerator.isEmpty() || denominator.isEmpty()))
        throw new IllegalArgumentException("History captured FX state");
    }
  }

  public record Provenance(String type, int version, String reference) {
    public Provenance {
      text(type);
      text(reference);
      if (version < 1) throw new IllegalArgumentException("History provenance version");
    }
  }

  public record Detail(
      UUID transaction,
      UUID operation,
      long sequence,
      long recordedAt,
      String service,
      String operationType,
      String status,
      List<Movement> movements,
      boolean movementsMore,
      Fee fee,
      Counterparty counterparty,
      List<CapturedFx> fx,
      List<Provenance> provenance,
      boolean additionalDetail) {
    public Detail {
      Objects.requireNonNull(transaction);
      Objects.requireNonNull(operation);
      Objects.requireNonNull(fee);
      Objects.requireNonNull(counterparty);
      text(service);
      text(operationType);
      text(status);
      movements = List.copyOf(movements);
      fx = List.copyOf(fx);
      provenance = List.copyOf(provenance);
      if (sequence < 1
          || recordedAt < 0
          || !status.equals("COMMITTED")
          || movements.isEmpty()
          || movements.size() > 16
          || fx.size() > 4
          || provenance.size() > 8) throw new IllegalArgumentException("History detail bounds");
    }
  }

  /**
   * Call on the authorized worker transaction. Ownership is checked before any specialized detail
   * disclosure.
   */
  public static Optional<Detail> read(
      JournalEngine.ReadView reads, PartyRef owner, UUID transaction, long highWater) {
    Objects.requireNonNull(transaction);
    if (!(owner.owner() instanceof PartyRef.Player player) || highWater < 0)
      return Optional.empty();
    var headers =
        reads.query(
            """
                SELECT t.sequence,t.operation_id,t.audit_utc,o.service,o.operation_type,o.status
                FROM journal_transactions t JOIN operation_outcomes o ON o.transaction_id=t.transaction_id
                WHERE t.world_id=? AND t.transaction_id=? AND t.sequence<=? AND o.status='COMMITTED'
                """,
            List.of(
                owner.world().value().toString(), transaction.toString(), Long.toString(highWater)),
            1);
    if (headers.isEmpty()) return Optional.empty();
    var header = headers.getFirst();
    long sequence = Long.parseLong(header.get("sequence"));
    var records =
        reads.query(
            "SELECT position,record_type,record_version,payload FROM journal_records WHERE sequence=? ORDER BY position LIMIT 64",
            List.of(Long.toString(sequence)),
            64);
    var owned = new HashSet<UUID>();
    var movements = new ArrayList<Movement>();
    long denomination = denomination(reads, sequence);
    for (var record : records)
      if (record.get("record_type").equals("economy:posting")) {
        int version = Integer.parseInt(record.get("record_version"));
        if (version < 1 || version > 4) continue;
        var fields = record.get("payload").split("\\|", -1);
        var account = new AccountId(UUID.fromString(fields[0]));
        if (AccountCatalog.find(reads, owner, account).isPresent()) {
          owned.add(account.value());
          movements.add(
              new Movement(
                  account.value(),
                  Integer.parseInt(record.get("position")),
                  new Money(EconomyCurrency.fromCode(fields[2]), Long.parseLong(fields[3])),
                  denomination,
                  version));
        }
      }
    if (movements.isEmpty()) return Optional.empty();
    var operation = UUID.fromString(header.get("operation_id"));
    var captured =
        new Capture(
            reads,
            owner,
            player.id(),
            operation,
            sequence,
            owned,
            header.get("service"),
            header.get("operation_type"));
    for (var record : records) captured.record(record);
    return Optional.of(
        new Detail(
            transaction,
            operation,
            sequence,
            Instant.parse(header.get("audit_utc")).toEpochMilli(),
            header.get("service"),
            header.get("operation_type"),
            header.get("status"),
            movements.stream().limit(16).toList(),
            movements.size() > 16,
            captured.fee(),
            captured.counterparty,
            captured.fx,
            captured.provenance,
            captured.additional));
  }

  /**
   * Exact source identity; never falls back to the newest FX publication when the original is
   * absent.
   */
  public static CapturedFx capturedFx(
      JournalEngine.ReadView reads,
      UUID publication,
      EconomyCurrency from,
      EconomyCurrency to,
      long originalSequence) {
    var source = FxService.source(reads, publication, originalSequence);
    if (source == null
        || !source.snapshot().rates().containsKey(from)
        || !source.snapshot().rates().containsKey(to))
      return new CapturedFx(
          publication,
          "UNAVAILABLE",
          0,
          from.code(),
          to.code(),
          "",
          "",
          0,
          Availability.UNAVAILABLE);
    var snapshot = source.snapshot();
    return new CapturedFx(
        publication,
        source.legacy() ? "LEGACY_DAILY" : "EFFECTIVE_TIME",
        snapshot.effectiveMillis(),
        from.code(),
        to.code(),
        snapshot.rates().get(from).toPlainString(),
        snapshot.rates().get(to).toPlainString(),
        source.factVersion(),
        Availability.KNOWN);
  }

  private static final class Capture {
    final JournalEngine.ReadView reads;
    final PartyRef owner;
    final PlayerId player;
    final UUID operation;
    final long sequence;
    final Set<UUID> accounts;
    final String service, operationType;
    final List<CapturedFx> fx = new ArrayList<>();
    final List<Provenance> provenance = new ArrayList<>();
    final List<Money> settlementFees = new ArrayList<>(),
        pricingFees = new ArrayList<>(),
        cashFees = new ArrayList<>();
    Counterparty counterparty = new Counterparty(Visibility.NOT_RECORDED, "");
    boolean additional, feeUnavailable, cashFeeInspected;

    Capture(
        JournalEngine.ReadView reads,
        PartyRef owner,
        PlayerId player,
        UUID operation,
        long sequence,
        Set<UUID> accounts,
        String service,
        String operationType) {
      this.reads = reads;
      this.owner = owner;
      this.player = player;
      this.operation = operation;
      this.sequence = sequence;
      this.accounts = accounts;
      this.service = service;
      this.operationType = operationType;
    }

    void record(Map<String, String> record) {
      String type = record.get("record_type"), payload = record.get("payload");
      int version = Integer.parseInt(record.get("record_version"));
      try {
        if (type.equals(Withdrawals.FACT) && version >= 1 && version <= 5) {
          var fact = Withdrawals.decodeFact(payload, version);
          var value = fact.reservation();
          var preview = value.preview();
          if (fact.action() == Withdrawals.Action.COMMIT
              && value.state() == Withdrawals.State.COMMITTED
              && value.owner().equals(player)
              && accounts.contains(preview.binding().account())
              && service.equals(Withdrawals.Action.COMMIT.kind().service())
              && operationType.equals(Withdrawals.Action.COMMIT.kind().operationType())) {
            cashFeeInspected = true;
            addProvenance(type, version, value.id().toString());
            var atmFee = Withdrawals.atmFee(preview);
            if (preview.pricing() != null)
              cashPrice(
                  preview.pricing(),
                  preview.binding().currency(),
                  preview.binding().account(),
                  Math.subtractExact(preview.debit().minorUnits(), atmFee.minorUnits()));
            if (atmFee.minorUnits() > 0) {
              var fxFee = preview.pricing() == null ? 0 : cashFees.removeLast().minorUnits();
              cashFees.add(new Money(atmFee.currency(), Math.addExact(fxFee, atmFee.minorUnits())));
            }
            if (preview.binding().currency() != preview.cash().currency())
              cashFx(
                  preview.binding().fxRevision(),
                  preview.binding().currency(),
                  preview.cash().currency(),
                  version);
          } else additional = true;
        } else if (type.equals(Deposits.FACT) && version >= 1 && version <= 3) {
          var fact = Deposits.decodeFact(payload, version);
          var value = fact.reservation();
          var preview = value.preview();
          if (fact.action() == Deposits.Action.COMMIT
              && value.state() == Deposits.State.COMMITTED
              && value.owner().equals(player)
              && accounts.contains(preview.binding().account())
              && service.equals(Deposits.Action.COMMIT.kind().service())
              && operationType.equals(Deposits.Action.COMMIT.kind().operationType())) {
            cashFeeInspected = true;
            addProvenance(type, version, value.id().toString());
            for (var group : preview.groups())
              if (!group.skipped()) {
                if (group.pricing() != null)
                  cashPrice(group.pricing(), group.currency(), null, group.subtotal().minorUnits());
                if (group.currency() != preview.binding().currency())
                  cashFx(
                      preview.binding().fxRevision(),
                      group.currency(),
                      preview.binding().currency(),
                      version);
              }
          } else additional = true;
        } else if (type.equals(FxSettlement.FACT) && version >= 1 && version <= 3) {
          if (version == 1) FxSettlement.validateFact(payload);
          else if (version == 2) FxSettlement.validateFactV2(payload);
          else FxSettlement.validateFactV3(payload);
          var p = payload.split("\\|", -1);
          var from = EconomyCurrency.fromCode(p[3]);
          var to = EconomyCurrency.fromCode(p[4]);
          settlementFees.add(new Money(from, Long.parseLong(p[7])));
          addFx(capturedFx(reads, UUID.fromString(p[1]), from, to, sequence));
          addProvenance(type, version, p[0]);
        } else if (type.equals(ServicePricing.FACT) && (version == 1 || version == 2)) {
          Decoders.CATALOG.validate(type, version, payload);
          var p = payload.split("\\|", -1);
          if (p[1].equals(owner.world().value().toString())
              && p[2].equals(player.value().toString())) {
            pricingFees.add(new Money(EconomyCurrency.fromCode(p[5]), Long.parseLong(p[7])));
            addProvenance(type, version, p[0]);
          }
        } else if (type.equals(NpcFacts.RECEIPT) && version == 1) {
          var receipt = NpcFacts.decodeReceipt(payload);
          if (receipt.world().equals(owner.world())
              && receipt.operation().equals(operation)
              && receipt.actor().equals(player)
              && accounts.contains(receipt.account())) {
            settlementFees.add(
                new Money(
                    receipt.publication() == null ? receipt.currency() : receipt.fxSourceCurrency(),
                    receipt.feeMinor()));
            counterparty("Trader " + receipt.trader());
            if (receipt.publication() != null)
              addFx(
                  capturedFx(
                      reads,
                      receipt.publication(),
                      receipt.fxSourceCurrency(),
                      receipt.fxTargetCurrency(),
                      sequence));
            addProvenance(type, version, receipt.quote().toString());
          } else additional = true;
        } else if (type.equals(ReferenceNpcFacts.RECEIPT) && version == 1) {
          var receipt = ReferenceNpcFacts.decodeReceipt(payload);
          if (receipt.world().equals(owner.world())
              && receipt.operation().equals(operation)
              && receipt.actor().equals(player)
              && accounts.contains(receipt.account())) {
            settlementFees.add(
                new Money(
                    receipt.publication() == null ? receipt.currency() : receipt.fxSourceCurrency(),
                    receipt.feeMinor()));
            counterparty("Trader " + receipt.trader());
            if (receipt.publication() != null)
              addFx(
                  capturedFx(
                      reads,
                      receipt.publication(),
                      receipt.fxSourceCurrency(),
                      receipt.fxTargetCurrency(),
                      sequence));
            addProvenance(type, version, receipt.quote().toString());
            referenceQuote(receipt.quote());
          } else additional = true;
        } else if (type.equals(ReferenceNpcFacts.QUOTE) && version == 1) {
          var quote = ReferenceNpcFacts.decodeQuote(payload);
          if (quote.world().equals(owner.world())
              && quote.terms().operation().equals(operation)
              && quote.terms().actor().equals(player)
              && accounts.contains(quote.terms().account().value())) {
            var ref = quote.terms().source().ref();
            addProvenance(type, version, quote.id().toString());
            addProvenance(
                "economy:valuation-manifest", ref.revision(), ref.valuationManifest().toString());
            addProvenance("economy:source-epoch", ref.revision(), ref.epoch().toString());
          }
        } else if (type.equals(LoanDisbursementFx.FACT) && version == 1) {
          Decoders.CATALOG.validate(type, version, payload);
          var p = payload.split("\\|", -1);
          if (p[1].equals(owner.world().value().toString())
              && accounts.contains(UUID.fromString(p[3]))) {
            var from = EconomyCurrency.fromCode(p[5]);
            var to = EconomyCurrency.fromCode(p[7]);
            settlementFees.add(new Money(from, Long.parseLong(p[9])));
            addFx(capturedFx(reads, UUID.fromString(p[4]), from, to, sequence));
            addProvenance(type, version, p[0]);
          }
        } else if (type.equals(MerchantPayments.FACT) && version == 1)
          merchant(payload, type, version);
        else if (type.equals(CreditFacilities.FACT) && version == CreditFacilities.VERSION) {
          var facility = CreditFacilities.decodeSnapshot(payload);
          if (facility.party().equals(owner)) {
            counterparty(facility.issuer().key());
            addProvenance(type, version, facility.id().toString());
          }
        } else if (type.equals(CreditFacilities.EVENT_FACT) && version == 1) {
          CreditFacilities.validateEventFact(payload);
          var event =
              Arrays.stream(payload.split("\\|", -1))
                  .map(
                      part ->
                          new String(
                              Base64.getUrlDecoder().decode(part),
                              java.nio.charset.StandardCharsets.UTF_8))
                  .toList();
          if (event.get(0).equals("ADVANCE_FEES")
              && service.equals(CreditFacilities.DRAW.service())
              && operationType.equals(CreditFacilities.DRAW.operationType())
              && CreditFacilities.find(reads, UUID.fromString(event.get(1)))
                  .filter(f -> f.party().equals(owner))
                  .isPresent()) {
            var parts = event.get(3).split("\\|", -1);
            if (parts.length != 4) throw new IllegalArgumentException("Advance fee receipt");
            pricingFees.add(
                new Money(
                    EconomyCurrency.fromCode(parts[0]),
                    Math.addExact(Long.parseLong(parts[1]), Long.parseLong(parts[2]))));
            addProvenance(type, version, event.get(1));
          }
        } else if (type.equals(Loans.FACT) && (version == 1 || version == 2)) {
          var loan = Loans.decode(payload, version);
          if (loan.party().equals(owner)) {
            counterparty(loan.lender().key());
            addProvenance(type, version, loan.id().toString());
          }
        } else if (type.equals(DebtRecords.CASE_FACT) && version == DebtRecords.VERSION) {
          var value = DebtRecords.decodeCase(payload);
          if (value.world().equals(owner.world()) && value.owner().equals(player)) {
            counterparty(value.creditor().key());
            addProvenance(type, version, value.id().toString());
          }
        } else if (!type.equals("economy:posting")) additional = true;
      } catch (IllegalArgumentException | IllegalStateException | ArithmeticException malformed) {
        additional = true;
        if (type.equals(Deposits.FACT)
            || type.equals(Withdrawals.FACT)
            || type.equals(FxSettlement.FACT)
            || type.equals(ServicePricing.FACT)
            || type.equals(NpcFacts.RECEIPT)
            || type.equals(ReferenceNpcFacts.RECEIPT)) feeUnavailable = true;
      }
    }

    void cashPrice(String encoded, EconomyCurrency expectedCurrency, UUID payer, long grossMinor) {
      var p = encoded.split("\\|", -1);
      if (p.length != 5
          || !UUID.fromString(p[0]).toString().equals(p[0])
          || !p[2].equals(expectedCurrency.code())
          || !(payer == null ? p[4].isEmpty() : p[4].equals(payer.toString())))
        throw new IllegalArgumentException("Cash history pricing association");
      long principal = Long.parseLong(p[1]), fee = Long.parseLong(p[3]);
      if (principal <= 0
          || fee < 0
          || !Long.toString(principal).equals(p[1])
          || !Long.toString(fee).equals(p[3])
          || Math.addExact(principal, fee) != grossMinor)
        throw new IllegalArgumentException("Cash history pricing amounts");
      cashFees.add(new Money(expectedCurrency, fee));
      addProvenance("economy:cash-pricing", 1, p[0]);
    }

    void cashFx(String revision, EconomyCurrency from, EconomyCurrency to, int version) {
      String[] p = revision.split("/", -1);
      UUID publication = null;
      try {
        if (p.length != 3) throw new IllegalArgumentException("Cash FX identity format");
        java.time.LocalDate day = java.time.LocalDate.parse(p[0]);
        publication = UUID.fromString(p[1]);
        UUID policy = UUID.fromString(p[2]);
        var original = FxService.source(reads, publication, sequence);
        if (original == null
            || !original.snapshot().policy().equals(policy)
            || !original.snapshot().publication().day().equals(day)) {
          addFx(
              new CapturedFx(
                  publication,
                  "UNAVAILABLE",
                  0,
                  from.code(),
                  to.code(),
                  "",
                  "",
                  0,
                  Availability.UNAVAILABLE));
          additional = true;
          return;
        }
        addFx(capturedFx(reads, publication, from, to, sequence));
      } catch (IllegalArgumentException | java.time.DateTimeException malformed) {
        additional = true;
        if (publication != null)
          addFx(
              new CapturedFx(
                  publication,
                  "UNAVAILABLE",
                  0,
                  from.code(),
                  to.code(),
                  "",
                  "",
                  0,
                  Availability.UNAVAILABLE));
        if (revision.length() <= 128)
          addProvenance("economy:cash-fx-unavailable", version, revision);
      }
    }

    void merchant(String payload, String type, int version) {
      MerchantPayments.validateFact(payload);
      var p = payload.split("\\|", -1);
      var action = MerchantPayments.Action.valueOf(p[0]);
      if (action == MerchantPayments.Action.GRANT || action == MerchantPayments.Action.REVOKE)
        return;
      UUID invoice = UUID.fromString(p[1]);
      // A receipt projection is usable only when the exact committed fact associates this operation
      // with its invoice.
      final ActorId[] capturedOther = {null};
      var receipt =
          MerchantPayments.receipt(
              reads,
              player,
              operation,
              (actor, merchant, terms, view) -> {
                if (!terms.payer().equals(player) && !merchant.equals(player)) return false;
                if (!accounts.contains(terms.source()) && !accounts.contains(terms.target()))
                  return false;
                capturedOther[0] = terms.payer().equals(player) ? merchant : terms.payer();
                return true;
              });
      if (receipt.isPresent()
          && receipt.get().invoice().equals(invoice)
          && receipt.get().action() == action) {
        counterparty(Journal.actorKey(capturedOther[0]));
        addProvenance(type, version, invoice.toString());
      } else additional = true;
    }

    void referenceQuote(UUID quoteId) {
      var saved =
          reads.query(
              """
                    SELECT q.payload FROM reference_npc_trade_quotes q
                    JOIN journal_transactions t ON t.operation_id=q.operation_id AND t.world_id=q.world_id
                    JOIN journal_records r ON r.sequence=t.sequence AND r.record_type=? AND r.record_version=1 AND r.payload=q.payload
                    WHERE q.id=? AND q.world_id=? AND t.sequence<? LIMIT 1
                    """,
              List.of(
                  ReferenceNpcFacts.QUOTE,
                  quoteId.toString(),
                  owner.world().value().toString(),
                  Long.toString(sequence)),
              1);
      if (saved.isEmpty()) {
        additional = true;
        return;
      }
      var quote = ReferenceNpcFacts.decodeQuote(saved.getFirst().get("payload"));
      if (!quote.id().equals(quoteId)
          || !quote.terms().operation().equals(operation)
          || !quote.terms().actor().equals(player)
          || !accounts.contains(quote.terms().account().value())) {
        additional = true;
        return;
      }
      var ref = quote.terms().source().ref();
      addProvenance(ReferenceNpcFacts.QUOTE, 1, quote.id().toString());
      addProvenance(
          "economy:valuation-manifest", ref.revision(), ref.valuationManifest().toString());
      addProvenance("economy:source-epoch", ref.revision(), ref.epoch().toString());
    }

    Fee fee() {
      var values =
          !cashFees.isEmpty() ? cashFees : settlementFees.isEmpty() ? pricingFees : settlementFees;
      if (feeUnavailable || values.size() > 1) return new Fee(Availability.UNAVAILABLE, null);
      return values.isEmpty()
          ? new Fee(
              additional && !cashFeeInspected
                  ? Availability.UNAVAILABLE
                  : Availability.NOT_RECORDED,
              null)
          : new Fee(Availability.KNOWN, values.getFirst());
    }

    void counterparty(String label) {
      if (counterparty.visibility() == Visibility.PRIVATE) return;
      if (counterparty.visibility() == Visibility.CAPTURED && !counterparty.label().equals(label))
        counterparty = new Counterparty(Visibility.PRIVATE, "");
      else counterparty = new Counterparty(Visibility.CAPTURED, label);
    }

    void addFx(CapturedFx value) {
      if (fx.contains(value)) return;
      if (fx.size() == 4) additional = true;
      else fx.add(value);
    }

    void addProvenance(String type, int version, String reference) {
      var value = new Provenance(type, version, reference);
      if (provenance.contains(value)) return;
      if (provenance.size() == 8) additional = true;
      else provenance.add(value);
    }
  }

  private static long denomination(JournalEngine.ReadView reads, long sequence) {
    var rows =
        reads.query(
            "SELECT payload FROM journal_records INDEXED BY ledger_rebase_latest WHERE record_type='economy:rebase' AND record_version=1 AND sequence<=? ORDER BY sequence DESC LIMIT 1",
            List.of(Long.toString(sequence)),
            1);
    return rows.isEmpty() ? 0 : Long.parseLong(rows.getFirst().get("payload").split("\\|", -1)[1]);
  }

  private static void text(String text) {
    if (text == null || text.length() > 128)
      throw new IllegalArgumentException("History text bounds");
  }
}
