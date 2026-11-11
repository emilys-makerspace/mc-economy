package ca.emilybelnavis.mcmods.economy.pricing;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.interest.DepositYield;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Production adapters are declared even before activation, so backups retain their implementations.
 */
public final class PricingRuntime {
  private PricingRuntime() {}

  public static WealthValuation wealth() {
    return wealth(List.of());
  }

  public static WealthValuation wealth(List<WealthValuation.Source> extensions) {
    var sources = new java.util.ArrayList<WealthValuation.Source>();
    sources.add(
        new WealthValuation.Source() {
          public String id() {
            return "economy:deposit-yield-receivables";
          }

          public WealthValuation.Kind kind() {
            return WealthValuation.Kind.ASSET;
          }

          public boolean requiresFreshMark() {
            return false;
          }

          public WealthValuation.SourceSnapshot read(
              JournalEngine.ReadView reads, WorldId world, PlayerId owner, Instant now) {
            var components =
                DepositYield.receivables(reads, world, owner).stream()
                    .map(
                        m ->
                            new WealthValuation.Component(
                                "yield-receivable:" + m.currency(),
                                m.currency(),
                                BigDecimal.valueOf(m.minorUnits())
                                    .divide(BigDecimal.valueOf(m.currency().minorUnitsPerMajor()))))
                    .toList();
            long revision = 0;
            var party =
                new ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.InstitutionId(
                    "economy:depositor/" + owner.value());
            for (var currency : ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.all())
              revision =
                  Math.max(
                      revision,
                      WealthValuation.ledgerRevision(
                          reads,
                          ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts.id(
                              world,
                              party,
                              ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts.Role
                                  .RECEIVABLE,
                              currency)));
            return new WealthValuation.SourceSnapshot(
                true, revision, "ledger-yield-v1", Instant.EPOCH, components);
          }
        });
    sources.add(new ca.emilybelnavis.mcmods.economy.credit.CreditLiabilities());
    sources.add(new ca.emilybelnavis.mcmods.economy.loan.LoanLiabilities());
    sources.addAll(extensions);
    return new WealthValuation(sources);
  }

  public static DepositYield yield(WorldId world) {
    return PricingRuntime.yield(world, wealth());
  }

  public static DepositYield yield(WorldId world, WealthValuation valuation) {
    return new DepositYield(
        world,
        (reads, owner, bank, effective) -> {
          var history =
              reads.query(
                  "SELECT owner_id FROM pricing_tier_history WHERE owner_id=? AND effective_ms<=? LIMIT 1",
                  List.of(owner.value().toString(), Long.toString(effective)),
                  1);
          if (history.isEmpty()) {
            var institution =
                ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.require(bank);
            if (institution.tier2()) {
              var snapshot =
                  valuation.snapshot(reads, world, owner, Instant.ofEpochMilli(effective));
              if (snapshot.available() && snapshot.eligible())
                return PricingPolicy.TIER2_DEPOSIT_APR;
            }
          }
          return ServicePricing.depositApr(reads, owner, bank, effective);
        });
  }

  private static WorldId world(EconomicClock.Queries reads) {
    return new WorldId(
        UUID.fromString(
            reads
                .query("SELECT world_id FROM foundation_metadata", List.of(), 1)
                .getFirst()
                .get("world_id")));
  }

  public static List<DeadlineDispatcher.Provider> providers() {
    return providers(wealth());
  }

  public static List<DeadlineDispatcher.Provider> providers(WealthValuation valuation) {
    return List.of(provider(0, valuation), provider(1, valuation), provider(2, valuation));
  }

  private static DeadlineDispatcher.Provider provider(int kind, WealthValuation valuation) {
    return new DeadlineDispatcher.Provider() {
      private DeadlineDispatcher.Provider delegate(WorldId world) {
        return kind == 0
            ? PricingRuntime.yield(world, valuation).provider()
            : kind == 1
                ? PricingRuntime.yield(world, valuation).payoutProvider()
                : TierReevaluation.provider(world, valuation);
      }

      private DeadlineDispatcher.Provider metadata() {
        return delegate(new WorldId(new UUID(0, 0)));
      }

      public String id() {
        return metadata().id();
      }

      public Map<Integer, String> versions() {
        return metadata().versions();
      }

      public DeadlineDispatcher.Event next(
          EconomicClock.Queries reads, long origin, DeadlineDispatcher.Event completed) {
        return delegate(world(reads)).next(reads, origin, completed);
      }

      public List<String> targets(
          DeadlineDispatcher.Event event, String cursor, int limit, EconomicClock.Queries reads) {
        return delegate(world(reads)).targets(event, cursor, limit, reads);
      }

      public EconomicClock.Output prepare(
          DeadlineDispatcher.Event event, String target, EconomicClock.Queries reads) {
        return delegate(world(reads)).prepare(event, target, reads);
      }
    };
  }
}
