package ca.emilybelnavis.mcmods.economy.terminal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Resource-level compatibility checks require no Minecraft bootstrap or disposable world. */
class BrandedHardwareResourcesTest {
    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Path ASSETS = RESOURCES.resolve("assets/economy");
    private static final List<String> DYES = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime",
            "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final List<String> FACING = List.of("north", "east", "south", "west");

    @Test void everyLegacyIdentityHasEveryApprovedBrandFacingAndCell() throws IOException {
        verifyStates("atm", "atm", 4);
        verifyStates("teller", "teller", 4);
        for (String dye : DYES) verifyStates(dye + "_atm", "atm", 4);
        verifyStates("branch_kiosk", "kiosk", 4);
    }

    private static void verifyStates(String id, String kind, int cells) throws IOException {
        assertTrue(Files.isRegularFile(ASSETS.resolve("models/item/" + id + ".json")), id);
        var variants = json(ASSETS.resolve("blockstates/" + id + ".json")).getAsJsonObject("variants");
        boolean kiosk = kind.equals("kiosk");
        var widths = kiosk ? List.of(false, true) : List.of(false);
        assertEquals(24 * cells * FACING.size() * widths.size(), variants.size(), id);
        for (int bank = 0; bank <= 23; bank++) {
            for (int cell = 0; cell < cells; cell++) {
                for (int facing = 0; facing < FACING.size(); facing++) {
                    for (boolean wide : widths) {
                        String key = "bank=" + bank + ",cell=" + cell + ",facing=" + FACING.get(facing);
                        if (kiosk) key += ",wide=" + wide;
                        assertTrue(variants.has(key), id + " " + key);
                        var value = variants.getAsJsonObject(key);
                        assertEquals(facing * 90, value.get("y").getAsInt(), id + " " + key);
                        String model = value.get("model").getAsString();
                        // Narrow kiosks only place cells 0 and 1. The other legal states use cell 1 as a fallback.
                        int modelCell = kiosk && !wide ? Math.min(cell, 1) : cell;
                        String suffix = kind + (wide ? "_wide_" : "_") + modelCell;
                        if (bank > 0) {
                            String issuer = InstitutionCatalog.banks().get(bank - 1).id().key().substring("economy:bank/".length());
                            assertEquals("economy:block/banking/" + issuer + "_" + suffix, model, key);
                        } else if (kiosk) {
                            assertEquals("economy:block/branch_" + suffix, model, key);
                        }
                        verifyModel(model);
                    }
                }
            }
        }
    }

    private static void verifyModel(String id) throws IOException {
        assertTrue(id.startsWith("economy:"));
        var model = json(ASSETS.resolve("models/" + id.substring("economy:".length()) + ".json"));
        if (model.has("parent") && model.get("parent").getAsString().startsWith("economy:"))
            verifyModel(model.get("parent").getAsString());
        if (model.has("textures")) for (var entry : model.getAsJsonObject("textures").entrySet()) {
            String texture = entry.getValue().getAsString();
            if (texture.startsWith("economy:"))
                assertTrue(Files.isRegularFile(ASSETS.resolve("textures/" + texture.substring("economy:".length()) + ".png")), texture);
        }
    }

    @Test void brandedAtmAndTellerKeepTheirExactLegacyGeometry() throws IOException {
        for (var bank : InstitutionCatalog.banks()) {
            String key = bank.id().key().substring("economy:bank/".length());
            for (String kind : List.of("atm", "teller")) {
                String base = kind.equals("atm") ? "atm" : "bank_teller_station";
                List<String> originals = List.of(base + "_right", base, base + "_upper_right", base + "_upper");
                for (int cell = 0; cell < 4; cell++) {
                    var original = json(ASSETS.resolve("models/block/" + originals.get(cell) + ".json"));
                    var branded = json(ASSETS.resolve("models/block/banking/" + key + "_" + kind + "_" + cell + ".json"));
                    assertEquals(original.get("elements"), branded.get("elements"), key + " " + kind + " " + cell);
                }
            }
        }
    }

    @Test void atmDisplaysLeaveTheKeypadClearInBlockAndInventoryModels() throws IOException {
        var models = new java.util.ArrayList<Path>();
        models.add(ASSETS.resolve("models/block/atm_upper.json"));
        models.add(ASSETS.resolve("models/block/atm_upper_right.json"));
        models.add(ASSETS.resolve("models/item/atm.json"));
        for (var bank : InstitutionCatalog.banks()) {
            String key = bank.id().key().substring("economy:bank/".length());
            for (String suffix : List.of("2", "3", "inventory"))
                models.add(ASSETS.resolve("models/block/banking/" + key + "_atm_" + suffix + ".json"));
        }
        for (var path : models) {
            double keypadBack = 0;
            var displays = new java.util.ArrayList<JsonObject>();
            for (var value : json(path).getAsJsonArray("elements")) {
                var element = value.getAsJsonObject();
                var faces = element.getAsJsonObject("faces");
                if (faces.getAsJsonObject("up").get("texture").getAsString().equals("#keypad"))
                    keypadBack = Math.max(keypadBack, element.getAsJsonArray("to").get(2).getAsDouble());
                if (faces.getAsJsonObject("north").get("texture").getAsString().equals("#screen"))
                    displays.add(element);
            }
            assertTrue(keypadBack > 0 && !displays.isEmpty(), path.toString());
            for (var display : displays)
                assertTrue(display.getAsJsonArray("from").get(2).getAsDouble() > keypadBack,
                        path + " screen must sit behind the entire keypad");
        }
    }

    @Test void acquisitionIsNeutralAndDyeCannotSelectABank() throws IOException {
        var recipe = json(RESOURCES.resolve("data/economy/recipe/branch_kiosk.json"));
        assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString());
        assertEquals(JsonParser.parseString("[\"IGI\",\"IRI\",\"ICI\"]"), recipe.get("pattern"));
        var ingredients = recipe.getAsJsonObject("key");
        assertEquals("minecraft:iron_ingot", ingredients.getAsJsonObject("I").get("item").getAsString());
        assertEquals("minecraft:glass", ingredients.getAsJsonObject("G").get("item").getAsString());
        assertEquals("minecraft:redstone", ingredients.getAsJsonObject("R").get("item").getAsString());
        assertEquals("minecraft:chest", ingredients.getAsJsonObject("C").get("item").getAsString());
        assertEquals(JsonParser.parseString("{\"id\":\"economy:branch_kiosk\",\"count\":1}"), recipe.get("result"));
        for (String dye : DYES) assertFalse(Files.exists(RESOURCES.resolve("data/economy/recipe/" + dye + "_atm.json")), dye);
        assertTrue(Files.isRegularFile(RESOURCES.resolve("data/economy/recipe/atm.json")));
        assertTrue(Files.isRegularFile(RESOURCES.resolve("data/economy/recipe/teller.json")));
        // Multiblock teardown owns the unique drop; vanilla loot must not create another.
        for (String id : List.of("atm", "teller", "branch_kiosk"))
            assertEquals(0, json(RESOURCES.resolve("data/economy/loot_table/blocks/" + id + ".json")).getAsJsonArray("pools").size());
    }

    @Test void wideConsoleSurfacesDoNotFlickerAndControlsFaceTheUser() throws IOException {
        var models = new java.util.ArrayList<Path>();
        for (int cell = 0; cell < 4; cell++)
            models.add(ASSETS.resolve("models/block/branch_kiosk_wide_shape_" + cell + ".json"));
        models.add(ASSETS.resolve("models/block/branch_kiosk_wide_inventory.json"));
        for (var bank : InstitutionCatalog.banks()) {
            String key = bank.id().key().substring("economy:bank/".length());
            models.add(ASSETS.resolve("models/block/banking/" + key + "_kiosk_wide_inventory.json"));
        }
        for (var path : models) {
            var tops = new java.util.ArrayList<JsonObject>();
            var keys = new java.util.HashMap<String, JsonObject>();
            var monitor = new java.util.ArrayList<JsonObject>();
            for (var value : json(path).getAsJsonArray("elements")) {
                var element = value.getAsJsonObject();
                var faces = element.getAsJsonObject("faces");
                if (faces.has("north") && List.of("#screen", "#bezel").contains(
                        faces.getAsJsonObject("north").get("texture").getAsString()))
                    monitor.add(element);
                if (faces.has("up")) {
                    var top = faces.getAsJsonObject("up");
                    for (var other : tops) {
                        boolean sameHeight = coordinate(element, "to", 1) == coordinate(other, "to", 1);
                        boolean overlapX = overlaps(element, other, 0);
                        boolean overlapZ = overlaps(element, other, 2);
                        assertFalse(sameHeight && overlapX && overlapZ, path + " overlapping top faces");
                    }
                    tops.add(element);
                    String texture = top.get("texture").getAsString();
                    if (texture.startsWith("#key_")) {
                        keys.put(texture, element);
                        assertEquals(180, top.get("rotation").getAsInt(), path + " upright key label");
                    }
                }
                if (faces.has("north") && faces.getAsJsonObject("north").get("texture").getAsString().equals("#reader"))
                    for (String side : List.of("up", "down", "south", "east", "west"))
                        assertEquals("#metal", faces.getAsJsonObject(side).get("texture").getAsString(), path.toString());
            }
            if (!keys.isEmpty()) {
                assertEquals(12, keys.size(), path.toString());
                double keypadBack = keys.values().stream().mapToDouble(key -> coordinate(key, "to", 2)).max().orElseThrow();
                for (var part : monitor)
                    assertTrue(coordinate(part, "from", 2) > keypadBack,
                            path + " screen and bezel must both sit behind the entire PIN pad");
                assertTrue(coordinate(keys.get("#key_1"), "from", 0) > coordinate(keys.get("#key_3"), "from", 0),
                        path + " digits increase left to right from the front");
                assertTrue(coordinate(keys.get("#key_1"), "from", 2) > coordinate(keys.get("#key_cancel"), "from", 2),
                        path + " function keys are nearest the user");
            }
        }
    }

    private static double coordinate(JsonObject element, String bound, int axis) {
        return element.getAsJsonArray(bound).get(axis).getAsDouble();
    }

    private static boolean overlaps(JsonObject a, JsonObject b, int axis) {
        return Math.max(coordinate(a, "from", axis), coordinate(b, "from", axis))
                < Math.min(coordinate(a, "to", axis), coordinate(b, "to", axis));
    }

    @Test void cosmeticKioskDropsResolveEveryBankWithoutEmbeddingAuthority() throws IOException {
        var overrides = json(ASSETS.resolve("models/item/branch_kiosk.json")).getAsJsonArray("overrides");
        assertEquals(23, overrides.size());
        for (int bank = 1; bank <= 23; bank++) {
            var override = overrides.get(bank - 1).getAsJsonObject();
            assertEquals(bank, override.getAsJsonObject("predicate").get("custom_model_data").getAsInt());
            String issuer = InstitutionCatalog.banks().get(bank - 1).id().key().substring("economy:bank/".length());
            assertEquals("economy:item/banking/" + issuer + "_kiosk", override.get("model").getAsString());
            verifyModel(override.get("model").getAsString());
        }
        for (String tag : List.of("data/economy/tags/item/terminals.json", "data/economy/tags/block/terminals.json",
                "data/minecraft/tags/block/mineable/pickaxe.json"))
            assertTrue(json(RESOURCES.resolve(tag)).getAsJsonArray("values").contains(JsonParser.parseString("\"economy:branch_kiosk\"")), tag);
        assertTrue(json(ASSETS.resolve("lang/en_us.json")).has("block.economy.branch_kiosk"));
    }

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
}
