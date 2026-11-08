package ca.emilybelnavis.mcmods.economy.simulation.model;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityFacts.Kind;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static ca.emilybelnavis.mcmods.economy.simulation.model.Rational.*;

/** Detached deterministic v1 calculation. No clock, storage or random inputs. */
public final class MacroModel {
    private MacroModel() {}
    private static final Rational STEP = of("0.001"), FX_STEP = of("0.005");
    private static final Rational LOW_STEP = of("0.995"), HIGH_STEP = of("1.005");
    private static final Rational LOW_ANCHOR = of("0.5"), HIGH_ANCHOR = of(2);
    private static final Rational MIN_RATE = of("0.000000000001"), MAX_RATE = of(1_000_000);
    private static final Rational MAX_PRIME = of("0.25");

    public record Window(int days, int baselineDays, boolean available, Map<Kind,BigInteger> totals,
                         Map<Kind,BigInteger> baselineTotals, BigInteger capacityMinorSum) {
        public Window {
            totals = amounts(totals); baselineTotals = amounts(baselineTotals);
            Objects.requireNonNull(capacityMinorSum);
            if (days < 0 || days > 7 || baselineDays < 0 || baselineDays > 7 || capacityMinorSum.signum() < 0
                    || (days < 7 && baselineDays != 0) || (available && days == 0)
                    || (days == 0 && (!totals.isEmpty() || capacityMinorSum.signum()!=0))
                    || (baselineDays == 0 && !baselineTotals.isEmpty())) throw new IllegalArgumentException("Window coverage");
        }
        public boolean empty() { return totals.values().stream().allMatch(v -> v.signum()==0); }
    }
    public record Config(UUID id, int revision, Rational dailyFloor, Rational capacityFactor,
                         List<Rational> primeWeights, List<Rational> fxWeights) {
        public Config {
            Objects.requireNonNull(id); Objects.requireNonNull(dailyFloor); Objects.requireNonNull(capacityFactor);
            primeWeights = weights(primeWeights,3); fxWeights = weights(fxWeights,2);
            if (revision < 1 || dailyFloor.compareTo(ZERO)<=0 || capacityFactor.compareTo(ZERO)<0)
                throw new IllegalArgumentException("Model configuration");
        }
        public static Config defaults(UUID id,int revision) {
            Rational third = ONE.divide(of(3)), half = ONE.divide(of(2));
            return new Config(id,revision,of(1000),of("0.01"),List.of(third,third,third),List.of(half,half));
        }
    }
    public record Input(UUID windowId, String inputHash, EconomyCurrency reference,
                        Map<EconomyCurrency,Window> windows, Map<EconomyCurrency,BigDecimal> anchors,
                        Map<EconomyCurrency,BigDecimal> priorFx, Map<EconomyCurrency,BigDecimal> priorPrime, Config config) {
        public Input {
            Objects.requireNonNull(windowId); Objects.requireNonNull(reference); Objects.requireNonNull(config);
            if (inputHash == null || !inputHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Window input hash");
            windows=Map.copyOf(windows); anchors=decimals(anchors); priorFx=decimals(priorFx); priorPrime=decimals(priorPrime);
            if (windows.isEmpty() || windows.size()>11 || !windows.containsKey(EconomyCurrency.CAD)
                    || !windows.containsKey(reference) || !windows.keySet().equals(anchors.keySet())
                    || !windows.keySet().equals(priorFx.keySet()) || !windows.keySet().equals(priorPrime.keySet()))
                throw new IllegalArgumentException("Model currency manifest");
            int days=-1, baseline=-1;
            for (Window w:windows.values()) {
                if (days!=-1 && (days!=w.days() || baseline!=w.baselineDays())) throw new IllegalArgumentException("Incoherent window coverage");
                days=w.days(); baseline=w.baselineDays();
            }
            validatePrior(reference,anchors,priorFx,priorPrime);
        }
    }
    public record Signals(Rational spending, Rational credit, Rational money, Rational exchange,
                          Rational primePressure, Rational fxPressure) {
        public Signals {
            for (var signal:List.of(spending,credit,money,exchange,primePressure,fxPressure))
                if(signal.abs().compareTo(ONE)>0)throw new IllegalArgumentException("Signal range");
        }
    }
    public record Output(boolean available, String reason, Map<EconomyCurrency,Signals> signals,
                         Map<EconomyCurrency,BigDecimal> primes, Map<EconomyCurrency,BigDecimal> fx,
                         Rational alpha, List<String> explanations, String hash) {
        public Output {
            Objects.requireNonNull(reason); Objects.requireNonNull(alpha); Objects.requireNonNull(hash);
            signals=Map.copyOf(signals); primes=decimals(primes); fx=decimals(fx); explanations=List.copyOf(explanations);
            if (!Set.of("AVAILABLE","EMPTY_WINDOW_HOLD","MISSING_COVERAGE").contains(reason)
                    || !hash.matches("[0-9a-f]{64}") || alpha.compareTo(ZERO)<0 || alpha.compareTo(ONE)>0
                    || primes.isEmpty() || primes.size()>11 || !primes.keySet().equals(fx.keySet())
                    || (available && !signals.keySet().equals(primes.keySet()))
                    || (!available && !signals.isEmpty()) || available==reason.equals("MISSING_COVERAGE")
                    || explanations.size()>32)throw new IllegalArgumentException("Model output structure");
            for(var c:primes.keySet())if(of(primes.get(c)).compareTo(ZERO)<0 || of(primes.get(c)).compareTo(MAX_PRIME)>0
                    || primes.get(c).stripTrailingZeros().scale()>6 || of(fx.get(c)).compareTo(MIN_RATE)<0
                    || of(fx.get(c)).compareTo(MAX_RATE)>0 || fx.get(c).stripTrailingZeros().scale()>12)
                throw new IllegalArgumentException("Model output rates");
        }
    }

    public static Output compute(Input input) {
        var signals = new EnumMap<EconomyCurrency,Signals>(EconomyCurrency.class);
        if (input.windows().values().stream().anyMatch(w -> !w.available()))
            return output(input,false,"MISSING_COVERAGE",signals,input.priorPrime(),input.priorFx(),ZERO,List.of("Required covered days unavailable"));
        boolean empty=input.windows().values().stream().allMatch(Window::empty);
        var primes=new EnumMap<EconomyCurrency,BigDecimal>(EconomyCurrency.class);
        var h=new EnumMap<EconomyCurrency,Rational>(EconomyCurrency.class);
        var explanations=new ArrayList<String>();
        for (var c:ordered(input.windows())) {
            Window w=input.windows().get(c);
            Rational unit=of(input.anchors().get(c)).divide(of(input.anchors().get(EconomyCurrency.CAD)))
                    .divide(of(c.minorUnitsPerMajor()));
            Rational s=ZERO, credit=ZERO, money=ZERO, exchange=ZERO;
            if (!w.empty()) {
                Rational n=of(w.days()), q=input.config().dailyFloor();
                if (w.baselineDays()>0) {
                    Rational a=net(w.totals(),Kind.GOODS_SALE,Kind.GOODS_REFUND).multiply(unit).divide(n);
                    Rational b=net(w.baselineTotals(),Kind.GOODS_SALE,Kind.GOODS_REFUND).multiply(unit).divide(of(w.baselineDays()));
                    s=clamp(a.subtract(b).divide(a.abs().add(b.abs()).add(q)),ONE);
                }
                Rational draw=value(w.totals(),Kind.CREDIT_DRAW).multiply(unit), repay=value(w.totals(),Kind.CREDIT_REPAYMENT).multiply(unit);
                Rational capacity=of(w.capacityMinorSum()).multiply(unit).divide(n);
                credit=draw.subtract(repay).divide(draw.add(repay).add(input.config().capacityFactor().multiply(capacity)).add(n.multiply(q)));
                Rational created=sum(w.totals(),Kind.OPERATOR_CREATE,Kind.OPERATOR_CASH_CREATE,Kind.NPC_CREATE).multiply(unit);
                Rational retired=sum(w.totals(),Kind.OPERATOR_RETIRE,Kind.NPC_RETIRE).multiply(unit);
                money=created.subtract(retired).divide(created.add(retired).add(n.multiply(q)));
                Rational bought=value(w.totals(),Kind.FX_BUY).multiply(unit), sold=value(w.totals(),Kind.FX_SELL).multiply(unit);
                exchange=bought.subtract(sold).divide(bought.add(sold).add(n.multiply(q)));
            }
            var pw=input.config().primeWeights(); var fw=input.config().fxWeights();
            Rational pressure=s.multiply(pw.get(0)).add(credit.multiply(pw.get(1))).add(money.multiply(pw.get(2)));
            Rational fxPressure=exchange.multiply(fw.get(0)).subtract(money.multiply(fw.get(1)));
            signals.put(c,new Signals(s,credit,money,exchange,pressure,fxPressure)); h.put(c,fxPressure);
            Rational old=of(input.priorPrime().get(c)), raw=old.add(STEP.multiply(pressure));
            BigDecimal prime=raw.max(ZERO).min(MAX_PRIME).decimal(6);
            if (of(prime).subtract(old).abs().compareTo(STEP)>0) throw new IllegalArgumentException("Rounded prime step");
            primes.put(c,empty?input.priorPrime().get(c):prime);
            if (raw.compareTo(ZERO)<0 || raw.compareTo(MAX_PRIME)>0) explanations.add(c+": prime range clamp");
        }
        if(empty) return output(input,true,"EMPTY_WINDOW_HOLD",signals,input.priorPrime(),input.priorFx(),ZERO,List.of("All covered W activity is empty"));
        Rational alpha=ONE;
        var currencies=ordered(input.windows());
        for(var a:currencies) for(var b:currencies) {
            Rational ka=FX_STEP.multiply(h.get(a)), kb=FX_STEP.multiply(h.get(b));
            alpha=capRatio(alpha,ONE,ka,kb,LOW_STEP,HIGH_STEP);
            Rational relative=of(input.priorFx().get(a)).divide(of(input.priorFx().get(b)))
                    .divide(of(input.anchors().get(a)).divide(of(input.anchors().get(b))));
            alpha=capRatio(alpha,relative,ka,kb,LOW_ANCHOR,HIGH_ANCHOR);
        }
        for(var c:currencies) alpha=capRatio(alpha,of(input.priorFx().get(c)),FX_STEP.multiply(h.get(c)),
                FX_STEP.multiply(h.get(input.reference())),MIN_RATE,MAX_RATE);
        if(alpha.compareTo(ONE)<0) explanations.add("Common alpha limited by ordered-pair/rate bounds");
        Map<EconomyCurrency,BigDecimal> fx=vector(input,h,alpha);
        if(!validRounded(input,fx)) {
            Rational low=ZERO, high=alpha; Map<EconomyCurrency,BigDecimal> retained=input.priorFx();
            for(int i=0;i<64;i++) {
                Rational middle=low.add(high).divide(of(2)); var candidate=vector(input,h,middle);
                if(validRounded(input,candidate)) { low=middle; retained=candidate; } else high=middle;
            }
            alpha=low; fx=retained; explanations.add("64-step rounded-vector bisection");
        }
        if(!validRounded(input,fx)) throw new IllegalArgumentException("Invalid FX fallback");
        return output(input,true,"AVAILABLE",signals,primes,fx,alpha,explanations);
    }

    private static Rational capRatio(Rational alpha,Rational base,Rational ka,Rational kb,Rational lower,Rational upper) {
        // base*(1+a*ka) >= lower*(1+a*kb), and <= upper*(1+a*kb).
        alpha=linear(alpha,base.subtract(lower),base.multiply(ka).subtract(lower.multiply(kb)));
        return linear(alpha,upper.subtract(base),upper.multiply(kb).subtract(base.multiply(ka)));
    }
    private static Rational linear(Rational alpha,Rational constant,Rational coefficient) {
        if(constant.compareTo(ZERO)<0) throw new IllegalArgumentException("Prior FX violates bound");
        return coefficient.compareTo(ZERO)<0 ? alpha.min(constant.divide(coefficient.negate())) : alpha;
    }
    private static Map<EconomyCurrency,BigDecimal> vector(Input in,Map<EconomyCurrency,Rational> h,Rational alpha) {
        var result=new EnumMap<EconomyCurrency,BigDecimal>(EconomyCurrency.class);
        Rational denominator=ONE.add(alpha.multiply(FX_STEP).multiply(h.get(in.reference())));
        for(var c:ordered(in.windows())) result.put(c,c==in.reference()?BigDecimal.ONE:
                of(in.priorFx().get(c)).multiply(ONE.add(alpha.multiply(FX_STEP).multiply(h.get(c)))).divide(denominator).decimal(12));
        return result;
    }
    public static boolean validRounded(Input in,Map<EconomyCurrency,BigDecimal> fx) {
        if(!fx.keySet().equals(in.windows().keySet()) || fx.get(in.reference()).compareTo(BigDecimal.ONE)!=0) return false;
        for(var c:ordered(fx)) { Rational rate=of(fx.get(c)); if(rate.compareTo(MIN_RATE)<0 || rate.compareTo(MAX_RATE)>0 || fx.get(c).stripTrailingZeros().scale()>12) return false; }
        for(var a:ordered(fx)) for(var b:ordered(fx)) {
            Rational pair=of(fx.get(a)).divide(of(fx.get(b)));
            Rational step=pair.divide(of(in.priorFx().get(a)).divide(of(in.priorFx().get(b))));
            Rational anchor=pair.divide(of(in.anchors().get(a)).divide(of(in.anchors().get(b))));
            if(step.compareTo(LOW_STEP)<0 || step.compareTo(HIGH_STEP)>0 || anchor.compareTo(LOW_ANCHOR)<0 || anchor.compareTo(HIGH_ANCHOR)>0) return false;
        }
        return true;
    }
    private static void validatePrior(EconomyCurrency reference,Map<EconomyCurrency,BigDecimal> anchors,
                                      Map<EconomyCurrency,BigDecimal> rates,Map<EconomyCurrency,BigDecimal> primes) {
        if(rates.get(reference).compareTo(BigDecimal.ONE)!=0 || anchors.get(reference).compareTo(BigDecimal.ONE)!=0)
            throw new IllegalArgumentException("Reference must equal one");
        for(var c:rates.keySet()) {
            Rational rate=of(rates.get(c));
            if(rate.compareTo(MIN_RATE)<0 || rate.compareTo(MAX_RATE)>0 || rates.get(c).stripTrailingZeros().scale()>12
                    || anchors.get(c).signum()<=0 || of(primes.get(c)).compareTo(ZERO)<0
                    || of(primes.get(c)).compareTo(MAX_PRIME)>0 || primes.get(c).stripTrailingZeros().scale()>6)
                throw new IllegalArgumentException("Invalid prior rates/prime");
        }
        for(var a:rates.keySet()) for(var b:rates.keySet()) {
            Rational relative=of(rates.get(a)).divide(of(rates.get(b))).divide(of(anchors.get(a)).divide(of(anchors.get(b))));
            if(relative.compareTo(LOW_ANCHOR)<0 || relative.compareTo(HIGH_ANCHOR)>0) throw new IllegalArgumentException("Prior anchor bound");
        }
    }
    private static Output output(Input in,boolean available,String reason,Map<EconomyCurrency,Signals> signals,
                                 Map<EconomyCurrency,BigDecimal> primes,Map<EconomyCurrency,BigDecimal> fx,Rational alpha,List<String> explanations) {
        String hash=sha256(ModelFacts.encode(in)+"|"+available+"|"+reason+"|"+canonical(signals)+"|"+canonical(primes)+"|"+canonical(fx)+"|"+alpha+"|"+String.join(";",explanations));
        return new Output(available,reason,signals,primes,fx,alpha,explanations,hash);
    }
    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String canonical(Map<EconomyCurrency,?> values) { var b=new StringBuilder(); for(var c:ordered(values))b.append(c).append('=').append(values.get(c)).append('|'); return b.toString(); }
    private static <T> List<EconomyCurrency> ordered(Map<EconomyCurrency,T> map) { return map.keySet().stream().sorted().toList(); }
    private static Rational value(Map<Kind,BigInteger> map,Kind kind) { return of(map.getOrDefault(kind,BigInteger.ZERO)); }
    private static Rational net(Map<Kind,BigInteger> map,Kind positive,Kind negative) { return value(map,positive).subtract(value(map,negative)); }
    private static Rational sum(Map<Kind,BigInteger> map,Kind... kinds) { Rational result=ZERO; for(var k:kinds)result=result.add(value(map,k)); return result; }
    private static Rational clamp(Rational value,Rational bound) { return value.min(bound).max(bound.negate()); }
    private static Map<Kind,BigInteger> amounts(Map<Kind,BigInteger> input) {
        var result=new EnumMap<Kind,BigInteger>(Kind.class);
        input.forEach((k,v)->{ Objects.requireNonNull(k); Objects.requireNonNull(v); if(v.signum()<0)throw new IllegalArgumentException("Negative gross amount"); if(v.signum()>0)result.put(k,v); });
        return Collections.unmodifiableMap(result);
    }
    private static Map<EconomyCurrency,BigDecimal> decimals(Map<EconomyCurrency,BigDecimal> input) { return Map.copyOf(input); }
    private static List<Rational> weights(List<Rational> values,int size) {
        values=List.copyOf(values); Rational sum=ZERO;
        if(values.size()!=size)throw new IllegalArgumentException("Weight count");
        for(var v:values) { if(v.compareTo(ZERO)<0)throw new IllegalArgumentException("Negative weight"); sum=sum.add(v); }
        if(!sum.equals(ONE))throw new IllegalArgumentException("Weight sum"); return values;
    }
}
