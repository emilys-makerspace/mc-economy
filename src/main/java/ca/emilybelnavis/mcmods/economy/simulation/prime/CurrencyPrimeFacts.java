package ca.emilybelnavis.mcmods.economy.simulation.prime;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable currency-prime v1 facts; no legacy policy bytes or meanings are changed. */
public final class CurrencyPrimeFacts {
    private CurrencyPrimeFacts(){}
    public static final String ACTIVATION="economy:a8/currency-prime-activation",POLICY="economy:a8/currency-prime",COMPLETE="economy:a8/currency-prime-complete";
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    public record Completion(UUID id,WorldId world,UUID activation,UUID window,long effectiveMillis,String modelHash,List<UUID> policies){
        public Completion{Objects.requireNonNull(id);Objects.requireNonNull(world);Objects.requireNonNull(activation);Objects.requireNonNull(window);policies=List.copyOf(policies);if(effectiveMillis<0||modelHash==null||!modelHash.matches("[0-9a-f]{64}")||policies.size()!=EconomyCurrency.all().size()||policies.stream().distinct().count()!=policies.size())throw new IllegalArgumentException("Prime basket completion");}}
    public static RecordCatalog register(RecordCatalog catalog){return catalog.with(new RecordCatalog.Key(ACTIVATION,1),CurrencyPrimeFacts::decodeActivation).with(new RecordCatalog.Key(POLICY,1),CurrencyPrimeFacts::decodePolicy).with(new RecordCatalog.Key(COMPLETE,1),CurrencyPrimeFacts::decodeCompletion);}
    public static String encode(CurrencyPrimes.Activation value){return write(value);}public static String encode(CurrencyPrimes.Policy value){return write(value);}public static String encode(Completion value){return write(value);}
    public static CurrencyPrimes.Activation decodeActivation(String value){return read(value,CurrencyPrimes.Activation.class);}public static CurrencyPrimes.Policy decodePolicy(String value){return read(value,CurrencyPrimes.Policy.class);}public static Completion decodeCompletion(String value){return read(value,Completion.class);}
    public static UUID stable(String source){return UUID.nameUUIDFromBytes(("economy:a8:currency-prime:v1|"+source).getBytes(StandardCharsets.UTF_8));}
    private static String write(Object value){String payload=JSON.toJson(value);if(payload.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Prime fact size");return payload;}
    private static <T>T read(String payload,Class<T> type){if(payload==null||payload.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Prime fact size");try{T value=JSON.fromJson(payload,type);if(value==null||!write(value).equals(payload))throw new IllegalArgumentException("Noncanonical prime fact");return value;}catch(JsonParseException malformed){throw new IllegalArgumentException("Malformed prime fact",malformed);}}
}
