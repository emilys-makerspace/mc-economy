package ca.emilybelnavis.mcmods.economy.credit;

import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.clock.FinancialCalendar;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.util.*;

/** Versioned score settlement shared by the accepted reporting provider and detached adapters. */
public final class CreditScoring {
  public static final Journal.Kind RECALCULATE =
      new Journal.Kind("economy:credit-profile", "economy:score-cycle");

  /** Fallback when the accepted reporting dependency is absent. */
  public static final String PRODUCTION_STATUS = "DORMANT_REPORTING_UNAVAILABLE";

  private CreditScoring() {}

  public record Request(
      long expectedProfileRevision,
      long origin,
      long boundary,
      int policyVersion,
      long reportingFrontier,
      long sourceRevision,
      String inputFingerprint) {
    public Request {
      CreditProfiles.fingerprint(inputFingerprint);
      if (expectedProfileRevision < 1
          || origin < 0
          || boundary <= origin
          || policyVersion < 1
          || reportingFrontier < 0
          || sourceRevision < 0) throw new IllegalArgumentException("Score cycle request");
    }

    public Journal.CanonicalRequest request(PartyRef owner) {
      if (!(owner.owner() instanceof PartyRef.Player player))
        throw new IllegalArgumentException("Player profile required");
      return new Journal.CanonicalRequest(
          1,
          Map.of(
              "world",
              owner.world().value().toString(),
              "economy:owner",
              Journal.actorKey(player.id()),
              "profile_revision",
              Long.toString(expectedProfileRevision),
              "origin",
              Long.toString(origin),
              "boundary",
              Long.toString(boundary),
              "policy_version",
              Integer.toString(policyVersion),
              "reporting_frontier",
              Long.toString(reportingFrontier),
              "source_revision",
              Long.toString(sourceRevision),
              "input_fingerprint",
              inputFingerprint));
    }
  }

  public record ReportedScore(
      boolean installed,
      FinancialCalendar.Stage stage,
      long reportingFrontier,
      long sourceRevision,
      long firstIssuedAt,
      ScorePolicy.Result result) {
    public ReportedScore {
      Objects.requireNonNull(stage);
      Objects.requireNonNull(result);
      if (reportingFrontier < 0 || sourceRevision < 0 || firstIssuedAt < 0)
        throw new IllegalArgumentException("Reporting witness");
    }
  }

  @FunctionalInterface
  public interface ReportSource {
    /**
     * Supplied only by A6-10's actual completed due+2 report snapshot; never a missing-data-as-zero
     * source.
     */
    Optional<ReportedScore> capture(ReadView reads, PartyRef owner, long origin, long boundary);
  }

  /** Detached adapter that deliberately supplies no reports. */
  public static Registration dormant(PartyRef owner, Request request) {
    return futureRegistration(
        owner, request, (reads, target, origin, boundary) -> Optional.empty());
  }

  @FunctionalInterface
  public interface ScoreAuthority {
    boolean allows(
        ca.emilybelnavis.mcmods.economy.api.Identifiers.ActorId actor,
        PartyRef target,
        ReadView reads);
  }

  public static Registration futureRegistration(
      PartyRef owner, Request request, ReportSource source) {
    return futureRegistration(
        owner,
        request,
        source,
        (actor, target, reads) ->
            target.owner() instanceof PartyRef.Player p && p.id().equals(actor));
  }

  /** The registered reporting scheduler supplies its narrow system authority. */
  public static Registration futureRegistration(
      PartyRef owner, Request request, ReportSource source, ScoreAuthority authority) {
    Objects.requireNonNull(source);
    Objects.requireNonNull(authority);
    return new Registration(
        1,
        (intent, reads) -> {
          if (!(owner.owner() instanceof PartyRef.Player)
              || !CreditProfiles.worldMatches(reads, owner.world())
              || !authority.allows(intent.actor(), owner, reads)
              || !intent.kind().equals(RECALCULATE)
              || !request.request(owner).equals(intent.request()))
            return CreditProfiles.reject("CREDIT_SCORE_REQUEST");
          if (request.policyVersion() != ScorePolicy.VERSION)
            return CreditProfiles.unavailable("SCORE_POLICY_UNAVAILABLE");
          var profile = CreditProfiles.find(reads, owner).orElse(null);
          if (profile == null) return CreditProfiles.unavailable("CREDIT_UNESTABLISHED");
          if (!CreditProfiles.origin(reads).filter(o -> o == request.origin()).isPresent())
            return CreditProfiles.unavailable("CREDIT_CALENDAR_UNAVAILABLE");
          long offset = request.boundary() - request.origin();
          if (offset < ScorePolicy.CYCLE_MILLIS
              || offset % ScorePolicy.CYCLE_MILLIS != 0
              || request.boundary() <= profile.firstIssuedAt())
            return CreditProfiles.reject("SCORE_BOUNDARY");
          if (profile.revision() != request.expectedProfileRevision())
            return CreditProfiles.reject("CREDIT_PROFILE_STALE");
          if (profile.lastBoundary() >= request.boundary())
            return CreditProfiles.reject("SCORE_PERIOD_ALREADY_APPLIED");
          long expected =
              profile.lastBoundary() >= 0
                  ? Math.addExact(profile.lastBoundary(), ScorePolicy.CYCLE_MILLIS)
                  : Math.addExact(
                      request.origin(),
                      Math.multiplyExact(
                          (profile.firstIssuedAt() - request.origin()) / ScorePolicy.CYCLE_MILLIS
                              + 1,
                          ScorePolicy.CYCLE_MILLIS));
          if (expected != request.boundary())
            return CreditProfiles.unavailable("SCORE_PRIOR_PERIOD_INCOMPLETE");
          var captured =
              source.capture(reads, owner, request.origin(), request.boundary()).orElse(null);
          if (captured == null
              || !captured.installed()
              || captured.stage() != FinancialCalendar.Stage.REPORTING
              || captured.reportingFrontier() < request.boundary()
              || captured.reportingFrontier() != request.reportingFrontier()
              || captured.sourceRevision() != request.sourceRevision())
            return CreditProfiles.unavailable("REPORTING_UNAVAILABLE");
          if (captured.reportingFrontier() < profile.reportingFrontier())
            return CreditProfiles.unavailable("REPORTING_FRONTIER_STALE");
          var result = captured.result();
          if (!result.available()) return CreditProfiles.unavailable(result.reason());
          if (captured.firstIssuedAt() != profile.firstIssuedAt()
              || result.boundary() != request.boundary()
              || result.policyVersion() != request.policyVersion()
              || !result.fingerprint().equals(request.inputFingerprint())
              || !CreditProfiles.hash(result.snapshot()).equals(result.fingerprint()))
            return CreditProfiles.reject("SCORE_INPUT_STALE");
          if (profile.revision() == Long.MAX_VALUE)
            return CreditProfiles.reject("CREDIT_PROFILE_REVISION_LIMIT");
          long index = offset / ScorePolicy.CYCLE_MILLIS;
          var period =
              FinancialCalendar.boundary(
                  owner.world().value(),
                  owner.world().value(),
                  request.origin(),
                  FinancialCalendar.Period.MONTH,
                  index);
          var changed =
              new CreditProfiles.Profile(
                  profile.world(),
                  profile.owner(),
                  result.score(),
                  profile.firstIssuedAt(),
                  profile.firstFacility(),
                  profile.firstIssuer(),
                  profile.starterCurrency(),
                  profile.starterMinor(),
                  result.policyVersion(),
                  profile.revision() + 1,
                  request.boundary(),
                  captured.reportingFrontier(),
                  result.fingerprint());
          var history =
              new CreditProfiles.History(
                  0,
                  profile.world(),
                  profile.owner(),
                  request.boundary(),
                  "SCORE",
                  result.policyVersion(),
                  changed.revision(),
                  result.score(),
                  result.components(),
                  result.fingerprint(),
                  result.snapshot(),
                  result.reasons(),
                  period.id(),
                  captured.reportingFrontier());
          return new Commit(
              List.of(
                  new Journal.Record(
                      CreditProfiles.PROFILE_FACT, 1, CreditProfiles.encode(changed)),
                  new Journal.Record(
                      CreditProfiles.HISTORY_FACT, 1, CreditProfiles.encode(history))),
              new Journal.Result(1, "CREDIT_SCORE_RECALCULATED", Integer.toString(result.score())),
              tx -> {
                int count =
                    tx.execute(
                        "UPDATE credit_profiles SET score=?,policy_version=?,revision=?,last_boundary=?,reporting_frontier=?,input_fingerprint=? WHERE world_id=? AND owner_id=? AND revision=?",
                        List.of(
                            Integer.toString(changed.score()),
                            Integer.toString(changed.policyVersion()),
                            Long.toString(changed.revision()),
                            Long.toString(changed.lastBoundary()),
                            Long.toString(changed.reportingFrontier()),
                            changed.inputFingerprint(),
                            changed.world().value().toString(),
                            changed.owner().value().toString(),
                            Long.toString(profile.revision())));
                if (count != 1) throw new IllegalStateException("Credit profile changed");
                CreditProfiles.writeHistory(tx, history);
              });
        });
  }
}
