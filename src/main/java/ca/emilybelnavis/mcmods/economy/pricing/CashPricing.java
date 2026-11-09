package ca.emilybelnavis.mcmods.economy.pricing;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import java.math.*;
import java.time.Instant;
import java.util.*;

/** Worker-owned pricing context for cash previews. No client field can supply this context. */
public final class CashPricing {
  private CashPricing() {}

  public record Context(
      ServicePricing pricing, WorldId world, PlayerId owner, UUID account, Instant now) {}

  public record Quote(ServicePricing.Quote pricing, long principalMinor, Money fee, UUID payer) {
    public String encoded() {
      return pricing.id()
          + "|"
          + principalMinor
          + "|"
          + fee.currency()
          + "|"
          + fee.minorUnits()
          + "|"
          + (payer == null ? "" : payer);
    }
  }

  public static Money principalForBudget(Money budget, ServicePricing.Tier tier) {
    if (tier == ServicePricing.Tier.TIER2) return budget;
    long principal =
        BigDecimal.valueOf(budget.minorUnits())
            .divide(new BigDecimal("1.005"), 0, RoundingMode.FLOOR)
            .longValueExact();
    if (principal < 1
        || Math.addExact(
                principal,
                ServicePricing.fee(
                        new Money(budget.currency(), principal), ServicePricing.Service.FX, tier)
                    .minorUnits())
            != budget.minorUnits())
      throw new IllegalArgumentException("FX_CASH_BUDGET_UNREPRESENTABLE");
    return new Money(budget.currency(), principal);
  }

  public static Quote deposit(Context context, JournalEngine.ReadView reads, Money gross) {
    var bank = bank(context, reads);
    if (bank == null) return null;
    var tentative =
        context
            .pricing()
            .quote(
                reads,
                context.world(),
                context.owner(),
                bank,
                ServicePricing.Service.FX,
                gross,
                context.now(),
                context.now().plusSeconds(60));
    long principal = gross.minorUnits();
    if (!tentative.tierTwo()) {
      principal =
          BigDecimal.valueOf(gross.minorUnits())
              .divide(new BigDecimal("1.005"), 0, RoundingMode.FLOOR)
              .longValueExact();
      if (principal < 1
          || Math.addExact(
                  principal,
                  ServicePricing.fee(
                          new Money(gross.currency(), principal),
                          ServicePricing.Service.FX,
                          tentative.tier())
                      .minorUnits())
              != gross.minorUnits())
        throw new IllegalArgumentException("FX_CASH_BUDGET_UNREPRESENTABLE");
    }
    var q =
        principal == gross.minorUnits()
            ? tentative
            : context
                .pricing()
                .quote(
                    reads,
                    context.world(),
                    context.owner(),
                    bank,
                    ServicePricing.Service.FX,
                    new Money(gross.currency(), principal),
                    context.now(),
                    tentative.expires());
    return new Quote(q, principal, q.fee(), null);
  }

  public static Quote withdrawal(Context context, JournalEngine.ReadView reads, Money principal) {
    var bank = bank(context, reads);
    if (bank == null) return null;
    var q =
        context
            .pricing()
            .quote(
                reads,
                context.world(),
                context.owner(),
                bank,
                ServicePricing.Service.PROTECTED_WITHDRAWAL_FX,
                principal,
                context.now(),
                context.now().plusSeconds(60));
    return new Quote(q, principal.minorUnits(), q.fee(), context.account());
  }

  public static Quote requireFresh(Context context, JournalEngine.ReadView reads, String encoded) {
    if (context == null) throw new IllegalArgumentException("PRICING_CONTEXT_UNAVAILABLE");
    var p = encoded.split("\\|", -1);
    if (p.length != 5) throw new IllegalArgumentException("CASH_PRICING_FIELDS");
    var q =
        context
            .pricing()
            .requireFresh(
                UUID.fromString(p[0]), reads, context.world(), context.owner(), context.now());
    var result =
        new Quote(
            q,
            Long.parseLong(p[1]),
            new Money(EconomyCurrency.valueOf(p[2]), Long.parseLong(p[3])),
            p[4].isEmpty() ? null : UUID.fromString(p[4]));
    if (result.payer() == null && q.service() != ServicePricing.Service.FX
        || result.payer() != null && q.service() != ServicePricing.Service.PROTECTED_WITHDRAWAL_FX)
      throw new IllegalArgumentException("CASH_PRICING_ACTION");
    if (!result.encoded().equals(encoded)
        || q.principal().minorUnits() != result.principalMinor()
        || !q.fee().equals(result.fee())
        || !q.institution().equals(bank(context, reads))
        || result.payer() != null && !result.payer().equals(context.account()))
      throw new IllegalArgumentException("CASH_PRICING_STALE");
    return result;
  }

  public static InstitutionCatalog.InstitutionId bank(
      Context context, JournalEngine.ReadView reads) {
    if (context == null) return null;
    var binding = BankProviders.find(reads, new AccountId(context.account())).orElse(null);
    if (binding == null || binding.bankKey().equals(BankProviders.UNASSIGNED_PROVIDER)) return null;
    return new InstitutionCatalog.InstitutionId(binding.bankKey());
  }
}
