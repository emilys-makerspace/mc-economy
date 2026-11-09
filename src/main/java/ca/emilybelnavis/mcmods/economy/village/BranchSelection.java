package ca.emilybelnavis.mcmods.economy.village;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Deterministic detached capture. Construction and financial activation remain separate adapters. */
public final class BranchSelection {
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private BranchSelection() {}

    public static BranchPlans.VillagePlanV1 capture(BranchPlans.VillagePlanV1 uncaptured,
                                                    BranchPlans.PlanningSnapshotV1 snapshot, long worldSeed) {
        Objects.requireNonNull(uncaptured); Objects.requireNonNull(snapshot);
        if (uncaptured.captureStatus() != BranchPlans.CaptureStatus.CAPTURE_PENDING)
            throw new IllegalArgumentException("Village is not awaiting capture");
        int count = uncaptured.buildingCount();
        int slots = BranchPlans.standardSlotCount(count);
        var branches = new ArrayList<BranchPlans.BranchPlanV1>();
        var used = new HashSet<String>();
        for (int slot = 0; slot < slots; slot++) {
            boolean required = slot < Math.min(2, slots);
            int roll = required ? -1 : draw(worldSeed, uncaptured.source(), "optional-roll", slot, 100);
            boolean selected = required || roll < (count <= 16 ? 25 : 50);
            var provider = selected ? selectProvider(snapshot, slot, used, worldSeed, uncaptured.source()) : Optional.<BranchPlans.Provider>empty();
            BranchPlans.State state;
            String reason;
            if (!selected) { state = BranchPlans.State.OMITTED; reason = "NOT_SELECTED"; }
            else if (provider.isPresent()) { state = BranchPlans.State.SITE_PENDING; reason = "PLACEMENT_ADAPTER_UNAVAILABLE"; }
            else if (required) {
                state = BranchPlans.State.PROVIDER_PENDING;
                reason = slot == 0 ? "NO_LOCAL_PROVIDER" : "NO_DISTINCT_PROVIDER";
            } else { state = BranchPlans.State.OMITTED; reason = "NO_DISTINCT_PROVIDER"; }
            provider.ifPresent(value -> used.add(value.institution()));
            branches.add(new BranchPlans.BranchPlanV1(slot, required, BranchPlans.Size.STANDARD,
                    provider.map(BranchPlans.Provider::institution).orElse(null),
                    provider.map(BranchPlans.Provider::nativeCurrency).orElse(null),
                    null, null, null, null, List.of(), List.of(), List.of(), state, reason, 0, roll));
        }
        return new BranchPlans.VillagePlanV1(uncaptured.source(), uncaptured.style(), count, uncaptured.abandoned(),
                uncaptured.originalBounds(), uncaptured.junctions(), snapshot, BranchPlans.CaptureStatus.CAPTURED,
                branches, uncaptured.diagnostics());
    }

    /** Missing required providers may be filled from a later snapshot without recapturing prior choices. */
    public static Optional<BranchPlans.Provider> selectProvider(BranchPlans.PlanningSnapshotV1 snapshot, int slot,
                                                                Set<String> used, long worldSeed, BranchPlans.Source source) {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(used); Objects.requireNonNull(source);
        if (slot < 0 || slot > 2) throw new IllegalArgumentException("Branch slot");
        var eligible = snapshot.providers().stream().filter(provider -> !used.contains(provider.institution()))
                .filter(provider -> slot != 0 || provider.primaryEligible() && provider.nativeCurrency() == snapshot.denomination())
                .sorted(Comparator.comparing(BranchPlans.Provider::institution)).toList();
        if (slot > 0) {
            var foreign = eligible.stream().filter(provider -> provider.nativeCurrency() != snapshot.denomination()).toList();
            if (!foreign.isEmpty()) eligible = foreign;
        }
        if (eligible.isEmpty()) return Optional.empty();
        return Optional.of(eligible.get(draw(worldSeed, source, "institution", slot, eligible.size())));
    }

    public static String encodeSnapshot(BranchPlans.PlanningSnapshotV1 snapshot) {
        Objects.requireNonNull(snapshot);
        var envelope = new JsonObject(); envelope.addProperty("version", 1); envelope.add("snapshot", JSON.toJsonTree(snapshot));
        String encoded = JSON.toJson(canonical(envelope));
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 16_384) throw new IllegalArgumentException("Provider snapshot bytes");
        return encoded;
    }

    public static BranchPlans.PlanningSnapshotV1 decodeSnapshot(String encoded) {
        Objects.requireNonNull(encoded);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 16_384) throw new IllegalArgumentException("Provider snapshot bytes");
        int depth = 0; boolean quoted = false; boolean escaped = false;
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (quoted) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') quoted = false; }
            else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') { if (++depth > 12) throw new IllegalArgumentException("Snapshot nesting"); }
            else if (c == '}' || c == ']') { if (--depth < 0) throw new IllegalArgumentException("Snapshot nesting"); }
        }
        if (quoted || depth != 0) throw new IllegalArgumentException("Snapshot nesting");
        try {
            var envelope = JsonParser.parseString(encoded).getAsJsonObject();
            if (envelope.get("version").getAsInt() != 1) throw new IllegalArgumentException("Provider snapshot version");
            var snapshot = JSON.fromJson(envelope.get("snapshot"), BranchPlans.PlanningSnapshotV1.class);
            if (!encodeSnapshot(snapshot).equals(encoded)) throw new IllegalArgumentException("Noncanonical provider snapshot");
            return snapshot;
        } catch (RuntimeException malformed) { throw new IllegalArgumentException("Invalid provider snapshot", malformed); }
    }

    private static int draw(long worldSeed, BranchPlans.Source source, String purpose, int slot, int bound) {
        byte[] seed = bytes(out -> {
            string(out, "economy:a7:selection:v1"); out.writeLong(worldSeed); string(out, source.dimension());
            string(out, source.structure()); out.writeInt(source.startX()); out.writeInt(source.startZ());
        });
        byte[] selection = hash(seed);
        long threshold = Long.remainderUnsigned(-((long) bound), bound);
        for (long counter = 0; counter < 64; counter++) {
            long capturedCounter = counter;
            byte[] word = hash(bytes(out -> {
                string(out, "economy:a7:stream:v1"); out.write(selection); string(out, purpose);
                out.writeByte(slot); out.writeLong(capturedCounter);
            }));
            long value = ByteBuffer.wrap(word).getLong();
            if (Long.compareUnsigned(value, threshold) >= 0) return (int) Long.remainderUnsigned(value, bound);
        }
        throw new IllegalStateException("Selection rejection bound");
    }

    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            var fields = new TreeMap<String, JsonElement>(); value.getAsJsonObject().entrySet().forEach(e -> fields.put(e.getKey(), e.getValue()));
            var result = new JsonObject(); fields.forEach((key, child) -> result.add(key, canonical(child))); return result;
        }
        if (value.isJsonArray()) {
            var result = new com.google.gson.JsonArray(); value.getAsJsonArray().forEach(child -> result.add(canonical(child))); return result;
        }
        return value;
    }
    @FunctionalInterface private interface Writer { void write(DataOutputStream out) throws IOException; }
    private static byte[] bytes(Writer writer) {
        try {
            var buffer = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(buffer)) { writer.write(out); }
            return buffer.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static byte[] hash(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
