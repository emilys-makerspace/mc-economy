package ca.emilybelnavis.mcmods.economy.trade.cash;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.trade.pricing.*;
import com.google.gson.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Immutable cash-only NPC source. Exact physical stacks are separate from catalog predicates. */
public final class ReferenceCashFacts {
    private ReferenceCashFacts(){}
    public static final String ADAPTER="economy:a9/reference-cash-adapter",QUOTE="economy:a9/reference-cash-quote",RECEIPT="economy:a9/reference-cash-trade",RETURN="economy:a9/reference-cash-return";
    public static final String SOURCES="reference-cash-v1:goods,creation,retirement,customer-fx,operator-unmarked";
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().registerTypeAdapter(ReferenceSource.class,new ReferenceSource.Codec()).registerTypeAdapter(Instant.class,new TimeCodec()).create();
    public record Adapter(WorldId world,String sources){public Adapter{Objects.requireNonNull(world);if(!SOURCES.equals(sources))throw new IllegalArgumentException("Cash adapter manifest");}}
    public record Terms(UUID operation,PlayerId actor,UUID session,UUID trader,long catalogRevision,int offerOrdinal,int executions,
                        EconomyCurrency cashCurrency,List<Deposits.Slot> cash,UUID epoch,ReferenceSource source,Rational reputation,
                        long reputationRevision,String reputationWitness,List<Pricing.Comparison> comparisons,UUID inputCustody,String exactInputItems,
                        UUID outputCustody,UUID cashCustody,String capacityReservation,long capacityRevision) {
        public Terms{Objects.requireNonNull(operation);Objects.requireNonNull(actor);Objects.requireNonNull(session);Objects.requireNonNull(trader);Objects.requireNonNull(cashCurrency);Objects.requireNonNull(epoch);Objects.requireNonNull(source);Objects.requireNonNull(reputation);Objects.requireNonNull(inputCustody);Objects.requireNonNull(cashCustody);if(inputCustody.equals(cashCustody)||inputCustody.equals(outputCustody)||cashCustody.equals(outputCustody))throw new IllegalArgumentException("Distinct cash custody IDs required");if(!epoch.equals(source.ref().epoch()))throw new IllegalArgumentException("Reference cash epoch mismatch");cash=List.copyOf(cash);comparisons=List.copyOf(comparisons);
            if(catalogRevision<1||offerOrdinal<0||offerOrdinal>=32||executions<1||executions>1024||cash.size()>24||comparisons.isEmpty()||comparisons.size()>16||reputationRevision<1||reputationWitness==null||reputationWitness.isEmpty()||reputationWitness.length()>256||exactInputItems==null||exactInputItems.isEmpty()||exactInputItems.length()>4096||capacityReservation==null||capacityReservation.isEmpty()||capacityReservation.length()>256||capacityRevision<1)throw new IllegalArgumentException("Cash trade terms");var slots=new HashSet<Integer>();for(var slot:cash)if(!slots.add(slot.index()))throw new IllegalArgumentException("Duplicate cash slot");}
    }
    public record PricingSnapshot(UUID market,long bindingRevision,long stockRevision,long priceRevision,long marketTickRevision,long eligibleTick,
                                  ReferencePricing.Request request,ReferencePricing.Quote price) {
        public PricingSnapshot{Objects.requireNonNull(market);Objects.requireNonNull(request);Objects.requireNonNull(price);if(price.minor()!=Math.multiplyExact(price.unitMinor(),request.goods()))throw new IllegalArgumentException("Published unit quantity mismatch");if(bindingRevision<1||stockRevision<1||priceRevision<0||marketTickRevision<0||eligibleTick<0||!request.key().market().equals(market)||request.eligibleTick()!=eligibleTick)throw new IllegalArgumentException("Cash pricing snapshot");}
    }
    public record Quote(UUID id,WorldId world,Terms terms,PricingSnapshot pricing,InstitutionCatalog.InstitutionId supplier,NpcCashFx.Quote fx,
                        long inputCashMinor,long operatorCashMinor,long requiredCashMinor,long cashOutputMinor,List<CashIssuance.Lot> inputLots,
                        WithdrawalPlanner.Plan plan,List<CashIssuance.Lot> outputLots,String denominationManifest,long issuedMillis,long expiresMillis,String financialIntent) {
        public Quote{Objects.requireNonNull(world);Objects.requireNonNull(terms);Objects.requireNonNull(pricing);Objects.requireNonNull(supplier);inputLots=List.copyOf(inputLots);outputLots=List.copyOf(outputLots);
            if(!world.equals(terms.source().ref().world())||!pricing.market().equals(terms.source().ref().market())||!pricing.request().key().item().equals(terms.source().ref().item())||!pricing.request().key().epoch().equals(terms.epoch())||!pricing.request().key().actor().equals(terms.actor().value())||!CashDenominations.SHA256.equals(denominationManifest)||!quoteId(world,terms.operation()).equals(id)||inputCashMinor<0||operatorCashMinor<0||operatorCashMinor>inputCashMinor||requiredCashMinor<0||cashOutputMinor<0||inputLots.size()>24||outputLots.size()>24||issuedMillis<0||expiresMillis<=issuedMillis||expiresMillis-issuedMillis>30_000||financialIntent==null||financialIntent.length()>2048||(cashOutputMinor==0)!=(plan==null)||(cashOutputMinor==0)!=outputLots.isEmpty()||plan!=null&&(plan.minorUnits()!=cashOutputMinor||plan.currency()!=terms.cashCurrency()))throw new IllegalArgumentException("Cash quote arithmetic/bounds");}
    }
    public record Receipt(WorldId world,UUID operation,UUID quote,PlayerId actor,UUID trader,UUID market,Pricing.Side side,EconomyCurrency currency,
                          long goodsPrincipal,long effectiveMillis,EconomyCurrency fxSourceCurrency,long fxSourcePrincipal,EconomyCurrency fxTargetCurrency,
                          long fxTargetPrincipal,UUID publication,long feeMinor,UUID inputCustody,UUID outputCustody,UUID cashCustody,EconomyCurrency cashCurrency,
                          long inputCashMinor,long cashOutputMinor,long operatorCashMinor) {
        public Receipt{Objects.requireNonNull(world);Objects.requireNonNull(operation);Objects.requireNonNull(actor);Objects.requireNonNull(trader);Objects.requireNonNull(market);Objects.requireNonNull(side);Objects.requireNonNull(currency);Objects.requireNonNull(cashCurrency);Objects.requireNonNull(inputCustody);if(!quoteId(world,operation).equals(quote)||goodsPrincipal<=0||effectiveMillis<0||feeMinor<0||inputCashMinor<0||cashOutputMinor<0||operatorCashMinor<0||operatorCashMinor>inputCashMinor||(cashOutputMinor>0)!=(cashCustody!=null))throw new IllegalArgumentException("Cash receipt");if(publication==null){if(fxSourceCurrency!=null||fxTargetCurrency!=null||fxSourcePrincipal!=0||fxTargetPrincipal!=0||feeMinor!=0)throw new IllegalArgumentException("Unexpected cash FX");}else if(fxSourceCurrency==null||fxTargetCurrency==null||fxSourceCurrency==fxTargetCurrency||fxSourcePrincipal<=0||fxTargetPrincipal<=0)throw new IllegalArgumentException("Cash FX principal");}
    }
    public record Return(WorldId world,UUID operation,UUID quote,UUID original,UUID inputCustody,UUID returnCustody,boolean knownHeld,Custody.Evidence evidence){public Return{Objects.requireNonNull(world);Objects.requireNonNull(operation);Objects.requireNonNull(quote);Objects.requireNonNull(original);Objects.requireNonNull(inputCustody);Objects.requireNonNull(returnCustody);Objects.requireNonNull(evidence);if(knownHeld&&evidence.kind()!=Custody.EvidenceKind.UNDELIVERED)throw new IllegalArgumentException("Known held return proof required");}}
    public static UUID quoteId(WorldId world,UUID operation){return stable("quote|"+world.value()+"|"+operation);}
    public static UUID stable(String value){return UUID.nameUUIDFromBytes(("economy:a9:reference-cash:v1|"+value).getBytes(StandardCharsets.UTF_8));}
    public static RecordCatalog register(RecordCatalog catalog){return catalog.with(new RecordCatalog.Key(ADAPTER,1),ReferenceCashFacts::decodeAdapter).with(new RecordCatalog.Key(QUOTE,1),ReferenceCashFacts::decodeQuote).with(new RecordCatalog.Key(RECEIPT,1),ReferenceCashFacts::decodeReceipt).with(new RecordCatalog.Key(RETURN,1),ReferenceCashFacts::decodeReturn);}
    public static String encode(Adapter value){return encodeObject(value);}public static String encode(Quote value){return encodeObject(value);}public static String encode(Receipt value){return encodeObject(value);}public static String encode(Return value){return encodeObject(value);}
    public static Adapter decodeAdapter(String value){return decode(value,Adapter.class);}public static Quote decodeQuote(String value){return decode(value,Quote.class);}public static Receipt decodeReceipt(String value){return decode(value,Receipt.class);}public static Return decodeReturn(String value){return decode(value,Return.class);}
    private static String encodeObject(Object value){String payload=JSON.toJson(value);if(payload.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Cash fact bound");return payload;}
    private static <T>T decode(String payload,Class<T> type){if(payload==null||payload.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Cash fact bound");try{T value=JSON.fromJson(payload,type);if(value==null||!encodeObject(value).equals(payload))throw new IllegalArgumentException("Noncanonical cash fact");return value;}catch(JsonParseException malformed){throw new IllegalArgumentException("Malformed cash fact",malformed);}}
    private static final class TimeCodec implements JsonSerializer<Instant>,JsonDeserializer<Instant>{public JsonElement serialize(Instant v,Type t,JsonSerializationContext c){return new JsonPrimitive(v.toString());}public Instant deserialize(JsonElement v,Type t,JsonDeserializationContext c){return Instant.parse(v.getAsString());}}
}
