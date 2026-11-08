package ca.emilybelnavis.mcmods.economy.simulation.macro;

import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Immutable configuration/mode rows; a manual row denotes one explicitly named future event. */
public final class MacroFacts {
    private MacroFacts(){}
    public static final String POLICY="economy:a8/macro-policy",SCHEDULE="economy:a8/macro-schedule";
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    public record Schedule(ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId world,UUID policy,UUID previous,long scheduledMillis,long schedulingFrontier){public Schedule{Objects.requireNonNull(world);Objects.requireNonNull(policy);Objects.requireNonNull(previous);if(scheduledMillis<0||schedulingFrontier<scheduledMillis)throw new IllegalArgumentException("Captured future schedule frontier");}}
    public static RecordCatalog register(RecordCatalog catalog){return catalog.with(new RecordCatalog.Key(POLICY,1),MacroFacts::decodePolicy).with(new RecordCatalog.Key(SCHEDULE,1),MacroFacts::decodeSchedule);}
    public static String encode(MacroControls.Policy policy){String text=JSON.toJson(policy);if(text.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Macro policy fact size");return text;}
    public static MacroControls.Policy decodePolicy(String text){if(text==null||text.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Macro policy fact size");try{var value=JSON.fromJson(text,MacroControls.Policy.class);if(value==null||!encode(value).equals(text))throw new IllegalArgumentException("Noncanonical macro policy");return value;}catch(JsonParseException bad){throw new IllegalArgumentException("Malformed macro policy",bad);}}
    public static String encode(Schedule value){String text=JSON.toJson(value);if(text.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Macro schedule size");return text;}
    public static Schedule decodeSchedule(String text){if(text==null||text.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Macro schedule size");try{var value=JSON.fromJson(text,Schedule.class);if(value==null||!encode(value).equals(text))throw new IllegalArgumentException("Noncanonical macro schedule");return value;}catch(JsonParseException bad){throw new IllegalArgumentException("Malformed macro schedule",bad);}}
    public static UUID stable(String value){return UUID.nameUUIDFromBytes(("economy:a8:macro-controls:v1|"+value).getBytes(StandardCharsets.UTF_8));}
    public static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
