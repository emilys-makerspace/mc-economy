package ca.emilybelnavis.mcmods.economy.fx;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Additive v1 intraday family. FxFacts v1/v2 remain frozen and separately indexed by daily date.
 */
public final class EffectiveFxFacts {
  private EffectiveFxFacts() {}

  public static final String FACT = "economy:a8/effective-fx", PROVIDER = "economy:a8/macro-rates";
  private static final Gson JSON =
      new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

  public record Source(
      EffectiveFxSnapshot snapshot, long sequence, boolean legacy, int factVersion) {
    public Source {
      Objects.requireNonNull(snapshot);
      if (sequence < 1 || factVersion < 1) throw new IllegalArgumentException("FX source sequence");
    }
  }

  public static UUID id(WorldId world, String provider, UUID event, UUID policy, UUID window) {
    return UUID.nameUUIDFromBytes(
        ("economy:a8:effective-fx:v1|"
                + world.value()
                + "|"
                + provider
                + "|"
                + event
                + "|"
                + policy
                + "|"
                + window)
            .getBytes(StandardCharsets.UTF_8));
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog
        .with(new RecordCatalog.Key(FACT, 1), EffectiveFxFacts::decode)
        .with(new RecordCatalog.Key(FACT, 2), EffectiveFxFacts::decode);
  }

  public static String encode(EffectiveFxSnapshot value) {
    if (value.mode() == EffectiveFxSnapshot.Mode.LEGACY_DAILY)
      throw new IllegalArgumentException("Legacy FX is adapted, never re-encoded as intraday");
    String text = JSON.toJson(value);
    if (text.getBytes(StandardCharsets.UTF_8).length > 16384)
      throw new IllegalArgumentException("FX fact size");
    return text;
  }

  public static EffectiveFxSnapshot decode(String text) {
    if (text == null || text.getBytes(StandardCharsets.UTF_8).length > 16384)
      throw new IllegalArgumentException("FX fact size");
    try {
      var value = JSON.fromJson(text, EffectiveFxSnapshot.class);
      if (value == null || !encode(value).equals(text))
        throw new IllegalArgumentException("Noncanonical effective FX");
      return value;
    } catch (JsonParseException bad) {
      throw new IllegalArgumentException("Malformed effective FX", bad);
    }
  }
}
