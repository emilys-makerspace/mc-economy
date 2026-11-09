package ca.emilybelnavis.mcmods.economy.cash;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Resource wiring for the approved six notes and seven coins; financial values remain
 * catalog-owned.
 */
class ChfAssetsTest {
  private static final Path ASSETS = Path.of("src/main/resources/assets/economy");
  private static final List<String> IDS =
      List.of(
          "chf_bill_10",
          "chf_bill_20",
          "chf_bill_50",
          "chf_bill_100",
          "chf_bill_200",
          "chf_bill_1000",
          "chf_coin_5",
          "chf_coin_10",
          "chf_coin_20",
          "chf_coin_50",
          "chf_coin_100",
          "chf_coin_200",
          "chf_coin_500");

  @Test
  void everyApprovedDenominationHasDistinctTransparentArtAndALocalizedGeneratedModel()
      throws Exception {
    var language =
        JsonParser.parseString(Files.readString(ASSETS.resolve("lang/en_us.json")))
            .getAsJsonObject();
    assertEquals("Economy - CHF Cash", language.get("itemGroup.economy.chf").getAsString());
    var hashes = new HashSet<String>();
    for (String id : IDS) {
      var model =
          JsonParser.parseString(Files.readString(ASSETS.resolve("models/item/" + id + ".json")))
              .getAsJsonObject();
      assertEquals("minecraft:item/generated", model.get("parent").getAsString());
      assertEquals(
          "economy:item/" + id, model.getAsJsonObject("textures").get("layer0").getAsString());
      Path image = ASSETS.resolve("textures/item/" + id + ".png");
      var png = ImageIO.read(image.toFile());
      assertNotNull(png, id);
      assertEquals(64, png.getWidth(), id);
      assertEquals(64, png.getHeight(), id);
      assertTrue(png.getColorModel().hasAlpha(), id);
      assertEquals(0, png.getRGB(0, 0) >>> 24, id);
      assertTrue((png.getRGB(32, 32) >>> 24) > 0, id);
      assertTrue(
          hashes.add(
              HexFormat.of()
                  .formatHex(
                      MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image)))),
          id);
      String label = language.get("item.economy." + id).getAsString();
      assertTrue(label.contains("CHF"), id);
      assertTrue(label.endsWith(id.contains("_bill_") ? "Note" : "Coin"), id);
    }
    assertTrue(Files.readString(Path.of("tools/chf-assets/README.md")).contains("CC0-1.0"));
    assertTrue(Files.isRegularFile(Path.of("tools/chf-assets/generate.py")));
  }
}
