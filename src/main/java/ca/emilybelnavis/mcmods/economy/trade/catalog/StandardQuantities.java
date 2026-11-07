package ca.emilybelnavis.mcmods.economy.trade.catalog;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Static constructor-domain minima. Never discovers prices/counts by invoking a listing. */
public final class StandardQuantities {
    private StandardQuantities() {}
    public static final int VERSION=1;
    public static final String ARTIFACT_SHA256="140360abbb394e8e3f282cdd88281f7c7da8ec94bd9a3a38dec3a1b3db25190b";
    public static final String SOURCE_SHA256="1e279519a2a87c59b7d50a0a40889e3e9381e281ca74f87c3fd40c6400865c41";
    public static final String MANIFEST_SHA256="d1a3c904132562ce375627b2b2d72864d4989ca1c1902580e39f09831e7dcc5d";
    private static final Map<String,Integer> VALUES=load();
    public static int require(String item) {
        Integer value=VALUES.get(item);
        if(value==null)throw new IllegalArgumentException("Unsupported catalog family: "+item+"; an explicit adapter is required");
        return value;
    }
    public static Map<String,Integer> all(){return VALUES;}
    private static Map<String,Integer> load() {
        try(var stream=StandardQuantities.class.getResourceAsStream("/data/economy/trade/standard_quantities_v1.json")) {
            if(stream==null)throw new IllegalStateException("Missing pinned quantity manifest");
            byte[] bytes=stream.readNBytes(65537);
            if(bytes.length>65536 || !sha256(bytes).equals(MANIFEST_SHA256))throw new IllegalStateException("Changed quantity manifest");
            var root=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            if(root.get("version").getAsInt()!=VERSION || !root.get("artifact_sha256").getAsString().equals(ARTIFACT_SHA256)
                    || !root.get("source_sha256").getAsString().equals(SOURCE_SHA256))throw new IllegalStateException("Quantity source identity");
            var values=new TreeMap<String,Integer>();
            root.getAsJsonObject("quantities").entrySet().forEach(e->{
                int count=e.getValue().getAsInt();
                if(!e.getKey().matches("minecraft:[a-z0-9_]+") || e.getKey().equals("minecraft:emerald") || count<=0 || count>64)
                    throw new IllegalStateException("Quantity domain");
                values.put(e.getKey(),count);
            });
            if(values.size()!=308)throw new IllegalStateException("Quantity coverage");
            return Collections.unmodifiableMap(values);
        }catch(IOException error){throw new IllegalStateException("Quantity manifest read",error);}
    }
    static String sha256(byte[] value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
