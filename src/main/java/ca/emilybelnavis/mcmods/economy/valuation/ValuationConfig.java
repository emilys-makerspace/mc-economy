package ca.emilybelnavis.mcmods.economy.valuation;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Detached operator configuration. Reading a bad file never edits or replaces it. */
public final class ValuationConfig {
    public static final int MAX_BYTES = 1_048_576;
    public static final int MAX_ENTRIES = 4_096;
    private ValuationConfig() {}

    public record Entry(String item, EconomyCurrency currency, String amount, long referenceCount) {
        public Entry {
            ValuationData.itemId(item);
            Objects.requireNonNull(currency, "currency");
            if (amount == null || amount.length() > 64 || !amount.matches("[0-9]+(?:\\.[0-9]+)?"))
                throw new IllegalArgumentException("Positive decimal amount required");
            BigDecimal decimal = new BigDecimal(amount);
            if (decimal.signum() <= 0 || decimal.scale() > 12 || referenceCount < 1 || referenceCount > 1_000_000)
                throw new IllegalArgumentException("Source precision, price or reference count");
            amount = decimal.stripTrailingZeros().toPlainString();
            Rational source = Rational.of(decimal).multiply(Rational.of(currency.minorUnitsPerMajor()))
                    .divide(Rational.of(referenceCount));
            if (source.numerator().bitLength() > 128 || source.denominator().bitLength() > 128)
                throw new IllegalArgumentException("Source rational exceeds 128 bits");
        }
        public Rational exactMinor() {
            return Rational.of(amount).multiply(Rational.of(currency.minorUnitsPerMajor()))
                    .divide(Rational.of(referenceCount));
        }
    }

    public record Config(List<Entry> bases, List<Entry> overrides) {
        public Config {
            bases = entries(bases); overrides = entries(overrides);
            if ((long) bases.size() + overrides.size() > MAX_ENTRIES)
                throw new IllegalArgumentException("Configuration entry limit");
        }
        public static Config empty() { return new Config(List.of(), List.of()); }
    }

    private static List<Entry> entries(List<Entry> entries) {
        Objects.requireNonNull(entries, "entries");
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Entry limit");
        var sorted = new TreeMap<String, Entry>();
        for (Entry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            if (sorted.putIfAbsent(entry.item(), entry) != null)
                throw new IllegalArgumentException("Duplicate source identity: " + entry.item());
        }
        return List.copyOf(sorted.values());
    }

    public static Config load(Path path) throws IOException {
        if (!Files.exists(path)) return Config.empty();
        if (Files.size(path) > MAX_BYTES) throw new IllegalArgumentException("Configuration byte limit");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Configuration byte limit");
            return decode(utf8(bytes));
        }
    }

    public static Config decode(String text) {
        JsonObject object = object(parse(text, MAX_BYTES));
        keys(object, Set.of("bases", "overrides"));
        return new Config(readEntries(object.get("bases")), readEntries(object.get("overrides")));
    }

    private static List<Entry> readEntries(JsonElement value) {
        if (value == null || !value.isJsonArray()) throw new IllegalArgumentException("Source array required");
        if (value.getAsJsonArray().size() > MAX_ENTRIES) throw new IllegalArgumentException("Entry limit");
        var entries = new ArrayList<Entry>();
        for (JsonElement element : value.getAsJsonArray()) {
            var object = object(element);
            keys(object, Set.of("item", "currency", "amount", "referenceCount"));
            JsonElement count = object.get("referenceCount");
            if (!count.isJsonPrimitive() || !count.getAsJsonPrimitive().isNumber()
                    || !count.getAsString().matches("[1-9][0-9]{0,6}"))
                throw new IllegalArgumentException("Positive integer reference count required");
            entries.add(new Entry(string(object, "item"), EconomyCurrency.fromCode(string(object, "currency")),
                    string(object, "amount"), Long.parseLong(count.getAsString())));
        }
        return entries;
    }

    public static String encode(Config config) {
        var object = new JsonObject();
        object.add("bases", entryArray(config.bases()));
        object.add("overrides", entryArray(config.overrides()));
        String text = object.toString();
        if (bytes(text) > MAX_BYTES) throw new IllegalArgumentException("Configuration byte limit");
        return text;
    }

    static JsonArray entryArray(List<Entry> entries) {
        var result = new JsonArray();
        for (Entry entry : entries) {
            var object = new JsonObject();
            object.addProperty("item", entry.item()); object.addProperty("currency", entry.currency().code());
            object.addProperty("amount", entry.amount()); object.addProperty("referenceCount", entry.referenceCount());
            result.add(object);
        }
        return result;
    }

    /** Strict duplicate-aware JSON, bounded before parsing. */
    static JsonElement parse(String text, int limit) {
        if (text == null || bytes(text) > limit) throw new IllegalArgumentException("JSON byte limit");
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement element = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing JSON");
            return element;
        } catch (IOException | IllegalStateException e) { throw new IllegalArgumentException("Invalid JSON", e); }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 32) throw new IllegalArgumentException("JSON depth limit");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IllegalArgumentException("Duplicate JSON key: " + key);
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                var array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1));
                reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> {
                String number = reader.nextString();
                if (number.length() > 64) throw new IllegalArgumentException("JSON numeric token limit");
                BigDecimal decimal = new BigDecimal(number);
                if (decimal.scale() < -128 || decimal.scale() > 128)
                    throw new IllegalArgumentException("JSON numeric exponent limit");
                yield new JsonPrimitive(decimal);
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("Unexpected JSON token");
        };
    }

    static JsonObject object(JsonElement element) {
        if (element == null || !element.isJsonObject()) throw new IllegalArgumentException("JSON object required");
        return element.getAsJsonObject();
    }
    static void keys(JsonObject object, Set<String> required) {
        if (!object.keySet().equals(required)) throw new IllegalArgumentException("Unexpected or missing JSON fields");
    }
    static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("String required: " + key);
        return value.getAsString();
    }
    static int bytes(String text) {
        try { return StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(text)).remaining(); }
        catch (CharacterCodingException e) { throw new IllegalArgumentException("Malformed UTF-16", e); }
    }
    static String utf8(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new IllegalArgumentException("Malformed UTF-8", e); }
    }
}
