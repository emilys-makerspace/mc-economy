package ca.emilybelnavis.mcmods.economy.valuation;

import com.google.gson.*;
import java.util.TreeSet;

/** Shared strict bounded JSON normalization; arrays preserve their semantic ordering. */
public final class ValuationIdentity {
  private ValuationIdentity() {}

  public static void itemId(String value) {
    ValuationData.itemId(value);
  }

  public static String canonical(String value) {
    return canonical(ValuationConfig.object(ValuationConfig.parseIdentity(value))).toString();
  }

  private static JsonElement canonical(JsonElement value) {
    if (value.isJsonObject()) {
      var out = new JsonObject();
      for (var key : new TreeSet<>(value.getAsJsonObject().keySet()))
        out.add(key, canonical(value.getAsJsonObject().get(key)));
      return out;
    }
    if (value.isJsonArray()) {
      var out = new JsonArray();
      for (var entry : value.getAsJsonArray()) out.add(canonical(entry));
      return out;
    }
    return value.deepCopy();
  }
}
