package ca.emilybelnavis.mcmods.economy.pricing;

import ca.emilybelnavis.mcmods.economy.api.CurrencyConversion;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** CAD-based fees, converted exactly before rounding to the nearest target-currency increment. */
public final class BankingFees {
    private BankingFees() {}

    public static Money advanceFee(EconomyCurrency currency, CurrencyConversion.Rates rates) {
        return convert(new BigDecimal("3.00"), currency, rates);
    }

    public static Money atmFee(EconomyCurrency currency, String issuerBankKey, String terminalBankKey,
                               CurrencyConversion.Rates rates) {
        if (terminalBankKey == null || terminalBankKey.isBlank() || terminalBankKey.equals(issuerBankKey))
            return new Money(currency, 0);
        return convert(new BigDecimal("2.50"), currency, rates);
    }

    public static Money convert(BigDecimal cadMajor, EconomyCurrency currency, CurrencyConversion.Rates rates) {
        Objects.requireNonNull(rates);
        return convert(cadMajor, currency, rates.perMajorUnit().get(EconomyCurrency.CAD), rates.perMajorUnit().get(currency));
    }

    /** Rates share any reference currency; their ratio converts CAD without intermediate rounding. */
    public static Money convert(BigDecimal cadMajor, EconomyCurrency currency, BigDecimal cadRate, BigDecimal targetRate) {
        Objects.requireNonNull(cadMajor); Objects.requireNonNull(currency);
        if (cadMajor.signum() < 0 || cadRate == null || targetRate == null || cadRate.signum() <= 0 || targetRate.signum() <= 0)
            throw new IllegalArgumentException("Banking fee FX rates unavailable");
        long incrementMinor = 25; // 0.25 for currencies with cents; 25 for JPY.
        var numerator = cadMajor.multiply(cadRate).multiply(BigDecimal.valueOf(currency.minorUnitsPerMajor()));
        var denominator = targetRate.multiply(BigDecimal.valueOf(incrementMinor));
        long increments = numerator.divide(denominator, 0, RoundingMode.HALF_UP).longValueExact();
        return new Money(currency, Math.multiplyExact(increments, incrementMinor));
    }
}
