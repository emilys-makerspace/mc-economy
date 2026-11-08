package ca.emilybelnavis.mcmods.economy.trade.settlement;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.BankProviders;
import ca.emilybelnavis.mcmods.economy.journal.NpcAccountFx;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.trade.pricing.*;
import com.google.gson.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Immutable account-NPC admission and semantic source facts; cash adapters are not installed. */
public final class NpcFacts {
    private NpcFacts() {}
    public static final String ADAPTER="economy:a8/npc-adapter",QUOTE="economy:a8/npc-quote",RECEIPT="economy:a8/npc-trade";
    public static final String SOURCES="npc-account-v1:goods,creation,retirement,customer-fx;cash=absent";
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls()
            .registerTypeAdapter(Instant.class,new InstantCodec()).registerTypeAdapter(PartyRef.Owner.class,new OwnerCodec()).create();
    public record Adapter(WorldId world,String sources){public Adapter{Objects.requireNonNull(world);if(!SOURCES.equals(sources))throw new IllegalArgumentException("NPC adapter manifest");}}
    /** Constructed by the authenticated server service after detached entity/save admission. */
    public record Terms(UUID operation,PlayerId actor,UUID session,UUID trader,long catalogRevision,int offerOrdinal,int executions,
                        AccountId account,UUID epoch,TradePriceSource.EmeraldV1 source,Rational reputation,long reputationRevision,
                        String reputationWitness,List<Pricing.Comparison> comparisons,UUID inputCustody,String exactInputItems,UUID outputCustody,
                        String capacityReservation,long capacityRevision) {
        public Terms {Objects.requireNonNull(operation);Objects.requireNonNull(actor);Objects.requireNonNull(session);Objects.requireNonNull(trader);Objects.requireNonNull(account);Objects.requireNonNull(epoch);Objects.requireNonNull(source);Objects.requireNonNull(reputation);
            comparisons=List.copyOf(comparisons);if(exactInputItems==null||exactInputItems.length()>4096||(inputCustody==null)!=exactInputItems.isEmpty())throw new IllegalArgumentException("Exact NPC input reservation");if(catalogRevision<1||offerOrdinal<0||offerOrdinal>=32||executions<1||executions>1024||reputationRevision<1||capacityRevision<1||reputationWitness==null||reputationWitness.isEmpty()||reputationWitness.length()>256||capacityReservation==null||capacityReservation.isEmpty()||capacityReservation.length()>256||comparisons.isEmpty()||comparisons.size()>16)throw new IllegalArgumentException("NPC admission terms");}
    }
    public record Quote(UUID id,WorldId world,Terms terms,EconomyCurrency accountCurrency,long accountRevision,BankProviders.Binding issuer,
                        UUID market,long bindingRevision,long stockRevision,long priceRevision,long marketTickRevision,long eligibleTick,
                        Pricing.Request priceRequest,Pricing.Quote price,NpcAccountFx.Quote fx,long issuedMillis,long expiresMillis,String financialIntent) {
        public Quote {Objects.requireNonNull(id);Objects.requireNonNull(world);Objects.requireNonNull(terms);Objects.requireNonNull(accountCurrency);Objects.requireNonNull(issuer);Objects.requireNonNull(market);Objects.requireNonNull(priceRequest);Objects.requireNonNull(price);
            if(!id.equals(quoteId(world,terms.operation()))||accountRevision<1||bindingRevision<1||stockRevision<1||priceRevision<0||marketTickRevision<0||eligibleTick<0||issuedMillis<0||expiresMillis<=issuedMillis||expiresMillis-issuedMillis>30_000||financialIntent==null||financialIntent.length()>2048||!priceRequest.key().market().equals(market)||!priceRequest.key().actor().equals(terms.actor().value())||priceRequest.eligibleTick()!=eligibleTick||!priceRequest.key().epoch().equals(terms.epoch()))throw new IllegalArgumentException("NPC quote identity");
            if((fx==null)!=(accountCurrency==priceRequest.basis().currency()))throw new IllegalArgumentException("Explicit NPC FX required");}
    }
    public record Receipt(WorldId world,UUID operation,UUID quote,PlayerId actor,UUID account,UUID trader,UUID market,Pricing.Side side,
                          EconomyCurrency currency,long goodsPrincipal,long effectiveMillis,EconomyCurrency fxSourceCurrency,long fxSourcePrincipal,
                          EconomyCurrency fxTargetCurrency,long fxTargetPrincipal,UUID publication,long feeMinor,UUID inputCustody,UUID outputCustody) {
        public Receipt {Objects.requireNonNull(world);Objects.requireNonNull(operation);Objects.requireNonNull(quote);Objects.requireNonNull(actor);Objects.requireNonNull(account);Objects.requireNonNull(trader);Objects.requireNonNull(market);Objects.requireNonNull(side);Objects.requireNonNull(currency);
            if(goodsPrincipal<=0||effectiveMillis<0||feeMinor<0||!quoteId(world,operation).equals(quote))throw new IllegalArgumentException("NPC source receipt");
            if(publication==null){if(fxSourceCurrency!=null||fxTargetCurrency!=null||fxSourcePrincipal!=0||fxTargetPrincipal!=0||feeMinor!=0)throw new IllegalArgumentException("Unexpected NPC FX source");}
            else if(fxSourceCurrency==null||fxTargetCurrency==null||fxSourceCurrency==fxTargetCurrency||fxSourcePrincipal<=0||fxTargetPrincipal<=0)throw new IllegalArgumentException("NPC FX principal source");}
    }
    public static UUID quoteId(WorldId world,UUID operation){return stable("quote|"+world.value()+"|"+operation);}
    public static UUID stable(String value){return UUID.nameUUIDFromBytes(("economy:a8:npc:v1|"+value).getBytes(StandardCharsets.UTF_8));}
    public static RecordCatalog register(RecordCatalog catalog){return catalog.with(new RecordCatalog.Key(ADAPTER,1),NpcFacts::decodeAdapter).with(new RecordCatalog.Key(QUOTE,1),NpcFacts::decodeQuote).with(new RecordCatalog.Key(RECEIPT,1),NpcFacts::decodeReceipt);}
    public static String encode(Adapter value){return encodeObject(value);}public static String encode(Quote value){return encodeObject(value);}public static String encode(Receipt value){return encodeObject(value);}
    public static Adapter decodeAdapter(String value){return decode(value,Adapter.class);}public static Quote decodeQuote(String value){return decode(value,Quote.class);}public static Receipt decodeReceipt(String value){return decode(value,Receipt.class);}
    private static String encodeObject(Object value){String payload=JSON.toJson(value);if(payload.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("NPC fact bound");return payload;}
    private static <T>T decode(String payload,Class<T> type){if(payload==null||payload.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("NPC fact bound");try{T value=JSON.fromJson(payload,type);if(value==null||!encodeObject(value).equals(payload))throw new IllegalArgumentException("Noncanonical NPC fact");return value;}catch(JsonParseException malformed){throw new IllegalArgumentException("Malformed NPC fact",malformed);}}
    private static final class InstantCodec implements JsonSerializer<Instant>,JsonDeserializer<Instant>{public JsonElement serialize(Instant value,Type type,JsonSerializationContext c){return new JsonPrimitive(value.toString());}public Instant deserialize(JsonElement value,Type type,JsonDeserializationContext c){return Instant.parse(value.getAsString());}}
    private static final class OwnerCodec implements JsonSerializer<PartyRef.Owner>,JsonDeserializer<PartyRef.Owner>{public JsonElement serialize(PartyRef.Owner owner,Type type,JsonSerializationContext c){if(!(owner instanceof PartyRef.Player p))throw new IllegalArgumentException("Player account required");return new JsonPrimitive(p.id().value().toString());}public PartyRef.Owner deserialize(JsonElement value,Type type,JsonDeserializationContext c){return new PartyRef.Player(new PlayerId(UUID.fromString(value.getAsString())));}}
}
