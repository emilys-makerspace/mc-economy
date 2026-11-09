package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.ACCOUNT;
import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.CLEARING;

import ca.emilybelnavis.mcmods.economy.account.AccountRebasePlanner;
import ca.emilybelnavis.mcmods.economy.account.AccountRebasePlanner.Denomination;
import ca.emilybelnavis.mcmods.economy.account.AccountRebasePlanner.Mapping;
import ca.emilybelnavis.mcmods.economy.account.AccountRebasePlanner.Plan;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.fx.DenominationChange;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Atomically changes the denomination, balance projection, and account catalog. */
public final class RebaseSettlement {
  public static final Journal.Kind KIND = new Journal.Kind("economy:ledger", "economy:rebase");
  public static final String FACT = "economy:rebase";
  public static final String MAPPING = "economy:rebase-account";
  // One denomination fact plus at most five legs and one mapping fact per primary.
  public static final int MAX_PRIMARIES = (64 - 1) / 6;

  private RebaseSettlement() {}

  @FunctionalInterface
  public interface Authority {
    boolean allows(PlayerId actor, JournalEngine.ReadView reads);
  }

  public static Optional<Denomination> denomination(JournalEngine.ReadView reads) {
    var rows =
        reads.query(
            """
                SELECT payload FROM journal_records WHERE record_type='economy:rebase'
                ORDER BY sequence DESC LIMIT 1
                """,
            List.of(),
            1);
    if (!rows.isEmpty()) {
      var fields = rows.getFirst().get("payload").split("\\|", -1);
      return Optional.of(
          new Denomination(EconomyCurrency.valueOf(fields[0]), Long.parseLong(fields[1])));
    }
    return Optional.ofNullable(FxService.latest(reads))
        .map(publication -> new Denomination(publication.reference(), 0));
  }

  public static Journal.CanonicalRequest request(Plan plan, UUID publication) {
    var intent = plan.intent();
    return new Journal.CanonicalRequest(
        1,
        Map.of(
            "confirmation",
            intent.id().toString(),
            "source",
            plan.current().currency().name(),
            "revision",
            Long.toString(plan.current().revision()),
            "target",
            intent.target().name(),
            "publication",
            publication.toString(),
            "roster-sha256",
            rosterDigest(plan)));
  }

  private static String rosterDigest(Plan plan) {
    var roster =
        plan.mappings().stream().map(RebaseSettlement::mapping).collect(Collectors.joining("\n"));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(roster.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static JournalEngine.Registration registration(
      WorldId world,
      UUID session,
      DenominationChange.Confirmed proof,
      Plan expected,
      UUID publication,
      Authority authority,
      AccountRebasePlanner.ClaimsReader claims) {
    Objects.requireNonNull(authority);
    Objects.requireNonNull(claims);
    if (!proof.intent().equals(expected.intent()))
      throw new IllegalArgumentException("Rebase proof mismatch");
    var canonical = request(expected, publication);
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!KIND.equals(intent.kind()) || !canonical.equals(intent.request()))
            return reject("REBASE_REQUEST");
          if (!(intent.actor() instanceof PlayerId actor) || !authority.allows(actor, reads)) {
            return reject("REBASE_AUTHORITY");
          }
          var current = denomination(reads).orElse(null);
          var result =
              AccountRebasePlanner.plan(
                  reads,
                  world,
                  session,
                  actor,
                  proof,
                  current,
                  (view, id) ->
                      claims
                          .read(view, id)
                          .map(
                              value ->
                                  new AccountRebasePlanner.Claims(
                                      value.activeHold() || Holds.hasActiveClaim(view, id.value()),
                                      value.restricted(),
                                      value.unresolvedObligation())));
          if (result.status() != AccountRebasePlanner.Status.READY)
            return reject("REBASE_" + result.status().name());
          if (!expected.equals(result.plan())) return reject("REBASE_ROSTER_CHANGED");
          if (expected.mappings().size() > MAX_PRIMARIES) return reject("REBASE_WRITE_BOUND");
          var rates = FxService.latest(reads);
          if (rates == null || !rates.updateId().equals(publication))
            return reject("REBASE_STALE_RATES");
          return prepare(reads, world, expected, rates);
        });
  }

  private static JournalEngine.Decision prepare(
      JournalEngine.ReadView reads,
      WorldId world,
      Plan plan,
      ca.emilybelnavis.mcmods.economy.fx.FxFacts.Publication rates) {
    var target = plan.intent().target();
    var source = plan.current().currency();
    if (!rates.rates().containsKey(source) || !rates.rates().containsKey(target))
      return reject("REBASE_MISSING_RATE");
    var legs = new ArrayList<PostingBatch.Leg>();
    var future = new HashMap<UUID, EconomyCurrency>();
    var facts = new ArrayList<Journal.Record>();
    try {
      long revision = Math.incrementExact(plan.current().revision());
      facts.add(
          new Journal.Record(FACT, 1, target.name() + "|" + revision + "|" + rates.updateId()));
      for (var entry : plan.mappings()) {
        UUID primary = entry.primary().id().value();
        long balance = Settlement.posted(reads, primary, source);
        long collision =
            entry.collision() == null
                ? 0
                : Settlement.posted(reads, entry.collision().id().value(), target);
        if (balance < 0 || collision < 0 || Settlement.posted(reads, primary, target) != 0) {
          return reject("REBASE_RECONCILIATION");
        }
        long payout =
            Math.multiplyExact(
                new Money(source, balance)
                    .majorUnits()
                    .multiply(rates.rates().get(source))
                    .divide(rates.rates().get(target), 0, RoundingMode.FLOOR)
                    .longValueExact(),
                target.minorUnitsPerMajor());
        add(legs, primary, ACCOUNT, source, -balance);
        add(legs, Settlement.clearingAccount(source), CLEARING, source, balance);
        if (entry.collision() != null)
          add(legs, entry.collision().id().value(), ACCOUNT, target, -collision);
        add(legs, Settlement.clearingAccount(target), CLEARING, target, -payout);
        add(legs, primary, ACCOUNT, target, Math.addExact(payout, collision));
        future.put(primary, target);
        facts.add(new Journal.Record(MAPPING, 1, mapping(entry)));
      }
    } catch (ArithmeticException overflow) {
      return reject("REBASE_RECONCILIATION");
    }
    JournalEngine.Projection postings = tx -> {};
    if (!legs.isEmpty()) {
      var decision = Settlement.prepareRebase(reads, legs, future);
      if (!(decision instanceof JournalEngine.Commit accepted)) return decision;
      facts.addAll(accepted.records());
      postings = accepted.projections();
    }
    var cardRevocations = new java.util.ArrayList<JournalEngine.Commit>();
    for (var entry : plan.mappings())
      if (entry.collision() != null) {
        var revoked =
            ca.emilybelnavis.mcmods.economy.card.IssuedCards.invalidateAccount(
                    reads, world, entry.collision().id())
                .orElse(null);
        if (revoked != null) {
          facts.addAll(revoked.records());
          cardRevocations.add(revoked);
        }
      }
    if (facts.size() > 64) return reject("REBASE_WRITE_BOUND");
    var projection = postings;
    return new JournalEngine.Commit(
        facts,
        new Journal.Result(1, "REBASED", ""),
        tx -> {
          projection.apply(tx);
          for (var revoke : cardRevocations) revoke.projections().apply(tx);
          for (var entry : plan.mappings()) {
            if (entry.collision() != null) retire(tx, entry.collision());
            change(tx, entry.primary(), target);
          }
        });
  }

  private static void retire(
      FoundationStore.Transaction tx, AccountRebasePlanner.AccountVersion account) {
    ensureOne(
        tx.execute(
            """
                UPDATE account_catalog SET status='CLOSED',revision=revision+1
                WHERE account_id=? AND revision=? AND status='OPEN' AND is_primary=0
                """,
            List.of(account.id().value().toString(), Long.toString(account.revision()))));
  }

  private static void change(
      FoundationStore.Transaction tx,
      AccountRebasePlanner.AccountVersion account,
      EconomyCurrency target) {
    ensureOne(
        tx.execute(
            """
                UPDATE account_catalog SET currency=?,revision=revision+1
                WHERE account_id=? AND revision=? AND status='OPEN' AND is_primary=1
                """,
            List.of(
                target.code(),
                account.id().value().toString(),
                Long.toString(account.revision()))));
  }

  private static void ensureOne(int count) {
    if (count != 1) throw new IllegalStateException("Rebase catalog reconciliation failed");
  }

  private static void add(
      List<PostingBatch.Leg> legs,
      UUID account,
      PostingBatch.Role role,
      EconomyCurrency currency,
      long amount) {
    if (amount != 0)
      legs.add(new PostingBatch.Leg(account, role, currency, new Money(currency, amount)));
  }

  private static String mapping(Mapping entry) {
    var primary = entry.primary();
    var collision = entry.collision();
    return entry.owner().value()
        + "|"
        + primary.id().value()
        + "|"
        + primary.revision()
        + "|"
        + (collision == null ? "|" : collision.id().value() + "|" + collision.revision());
  }

  public static void validateMapping(String payload) {
    var fields = payload.split("\\|", -1);
    if (fields.length != 5) throw new IllegalArgumentException("Rebase mapping fields");
    UUID.fromString(fields[0]);
    UUID.fromString(fields[1]);
    positiveRevision(fields[2]);
    if (fields[3].isEmpty() && fields[4].isEmpty()) return;
    UUID.fromString(fields[3]);
    positiveRevision(fields[4]);
  }

  public static void validateFact(String payload) {
    var fields = payload.split("\\|", -1);
    if (fields.length != 3) throw new IllegalArgumentException("Rebase fact fields");
    EconomyCurrency.valueOf(fields[0]);
    positiveRevision(fields[1]);
    UUID.fromString(fields[2]);
  }

  private static void positiveRevision(String value) {
    if (Long.parseLong(value) < 1) throw new IllegalArgumentException("Rebase revision");
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }
}
