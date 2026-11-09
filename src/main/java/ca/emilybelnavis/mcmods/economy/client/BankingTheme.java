package ca.emilybelnavis.mcmods.economy.client;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Uses the same original issuer palette and emblem as the physical bank hardware. */
record BankingTheme(String name, int primary, int accent, ResourceLocation logo) {
    static final int CREAM = 0xfff5f0e4;
    static final BankingTheme DEFAULT = new BankingTheme("Banking", 0xff263b50, 0xffd4a84c, null);

    static BankingTheme load(String institution) {
        if (institution.isEmpty()) return DEFAULT;
        var location = ResourceLocation.fromNamespaceAndPath("economy", "banking/art_manifest.json");
        try (var reader = new InputStreamReader(Minecraft.getInstance().getResourceManager()
                .getResource(location).orElseThrow().open(), StandardCharsets.UTF_8)) {
            for (var element : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("issuers")) {
                var entry = element.getAsJsonObject();
                if (!entry.get("institution").getAsString().equals(institution)) continue;
                var palette = entry.getAsJsonObject("palette");
                return new BankingTheme(entry.get("name").getAsString(), color(palette.get("primary").getAsString()),
                        color(palette.get("accent").getAsString()), ResourceLocation.parse(entry.get("sprite").getAsString()));
            }
        } catch (java.io.IOException | RuntimeException unavailable) {
            return DEFAULT;
        }
        return DEFAULT;
    }
    private static int color(String hex) { return 0xff000000 | Integer.parseInt(hex.substring(1), 16); }
    int headerText() {
        int brightness = ((primary >> 16 & 255) * 299 + (primary >> 8 & 255) * 587 + (primary & 255) * 114) / 1000;
        return brightness > 150 ? 0xff202b31 : CREAM;
    }
}
