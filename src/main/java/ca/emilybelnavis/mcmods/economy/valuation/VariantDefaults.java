package ca.emilybelnavis.mcmods.economy.valuation;

import com.google.gson.*;
import java.util.*;

/** Original approved material/content tables, detached from runtime item callbacks. */
final class VariantDefaults {
    private VariantDefaults() {}
    static Map<String,VariantGraph.Rule> rules() {
        try(var input=VariantDefaults.class.getResourceAsStream("/data/economy/valuation/variants.json")) {
            if(input==null)throw new IllegalStateException("Missing variant table");
            var raw=ValuationConfig.object(ValuationConfig.parse(ValuationConfig.utf8(input.readNBytes(1_048_577)),1_048_576));
            var result=new TreeMap<String,VariantGraph.Rule>();var gson=new Gson();
            raw.entrySet().forEach(e->result.put(e.getKey(),gson.fromJson(e.getValue(),VariantGraph.Rule.class)));
            return Collections.unmodifiableSortedMap(result);
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
}
