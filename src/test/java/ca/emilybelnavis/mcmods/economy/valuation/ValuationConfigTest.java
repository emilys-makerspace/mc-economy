package ca.emilybelnavis.mcmods.economy.valuation;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ValuationConfigTest {
    @TempDir Path directory;

    @Test void fileReloadIsStrictAndNeverRewritesRejectedOperatorInput() throws Exception {
        var file = directory.resolve("valuation.json");
        assertEquals(ValuationConfig.Config.empty(), ValuationConfig.load(file));
        assertFalse(Files.exists(file));
        String valid = """
                {"bases":[{"item":"minecraft:diamond","currency":"USD","amount":"2.500","referenceCount":3}],
                 "overrides":[],"enchantmentPremiums":{"minecraft:sharpness":["20.00","45"]}}
                """;
        Files.writeString(file, valid);
        var loaded = ValuationConfig.load(file);
        assertEquals("2.5", loaded.bases().getFirst().amount());
        assertEquals(List.of("20", "45"), loaded.enchantmentPremiums().get("minecraft:sharpness"));
        assertEquals(loaded, ValuationConfig.decode(ValuationConfig.encode(loaded)));
        assertEquals(valid, Files.readString(file));
        for (String invalid : List.of(
                valid.replace("\"USD\"", "\"usd\""),
                valid.replace("\"2.500\"", "2.5"),
                valid.replace("\"2.500\"", "\"0\""),
                valid.replace("\"2.500\"", "\"1e2\""),
                valid.replace("\"2.500\"", "\"0.0000000000001\""),
                valid.replace("\"referenceCount\":3", "\"referenceCount\":3.0"),
                valid.replace("\"referenceCount\":3", "\"referenceCount\":\"3\""),
                valid.replace("\"referenceCount\":3", "\"referenceCount\":0"),
                valid.replace("\"referenceCount\":3", "\"referenceCount\":1000001"),
                valid.replace("\"overrides\":[]", "\"overrides\":[],\"extra\":true"),
                valid.replace("[\"20.00\",\"45\"]", "[]"),
                valid.replace("[\"20.00\",\"45\"]", "[20]"),
                valid.replace("[\"20.00\",\"45\"]", "{}"),
                "{\"bases\":[],\"overrides\":[],\"bases\":[]}",
                "{\"bases\":{},\"overrides\":[]}", "{\"bases\":[]}", "[]", valid + " true")) {
            Files.writeString(file, invalid);
            assertThrows(IllegalArgumentException.class, () -> ValuationConfig.load(file), invalid);
            assertEquals(invalid, Files.readString(file));
        }
        byte[] malformed = {(byte)0xc3, (byte)0x28};
        Files.write(file, malformed);
        assertThrows(IllegalArgumentException.class, () -> ValuationConfig.load(file));
        assertArrayEquals(malformed, Files.readAllBytes(file));
        Files.writeString(file, " ".repeat(ValuationConfig.MAX_BYTES+1));
        assertThrows(IllegalArgumentException.class, () -> ValuationConfig.load(file));
    }

    @Test void componentIdentityIsCanonicalButPreservesArrayOrderAndRejectsAmbiguousOrUnboundedJson() {
        assertEquals("{\"a\":[{\"a\":null,\"z\":true},2],\"b\":\"text\"}",
                ValuationIdentity.canonical("{\"b\":\"text\",\"a\":[{\"z\":true,\"a\":null},2]}"));
        assertNotEquals(ValuationIdentity.canonical("{\"a\":[1,2]}"), ValuationIdentity.canonical("{\"a\":[2,1]}"));
        for (String invalid : List.of("{\"a\":{\"x\":1,\"x\":2}}", "{} {}", "[1]", "{a:1}",
                "{\"x\":1e129}", "{\"x\":1e-129}", "{\"x\":"+"1".repeat(65)+"}",
                "{\"x\":\""+"a".repeat(65_536)+"\"}", "{\"x\":\"\ud800\"}",
                "{\"x\":"+"[".repeat(65)+"0"+"]".repeat(65)+"}"))
            assertThrows(IllegalArgumentException.class, () -> ValuationIdentity.canonical(invalid));
        var entry = new ValuationConfig.Entry("minecraft:diamond", EconomyCurrency.CAD, "1", 1);
        assertThrows(IllegalArgumentException.class, () -> new ValuationConfig.Config(List.of(entry, entry), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ValuationConfig.Entry("minecraft:diamond", EconomyCurrency.CAD, "9".repeat(64), 1));
    }
}
