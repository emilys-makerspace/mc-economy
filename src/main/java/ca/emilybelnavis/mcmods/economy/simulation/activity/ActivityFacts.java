package ca.emilybelnavis.mcmods.economy.simulation.activity;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Frozen v1 activity facts. Only trusted semantic classifiers construct drafts. */
public final class ActivityFacts {
    private ActivityFacts() {}
    public static final String MANIFEST = "economy:a8/activity-manifest";
    public static final String BATCH = "economy:a8/activity-batch";
    public static final long DAY_MILLIS = 1_200_000L;
    public static final int MAX_OBSERVATIONS = 32, MAX_CAPACITIES = 275;
    public static final String SOURCE_MANIFEST = "v1:merchant,register,loan,credit,debt,fx,operator,cash;npc=declared";

    public enum Kind {
        GOODS_SALE, GOODS_REFUND, CREDIT_DRAW, CREDIT_REPAYMENT, FX_BUY, FX_SELL,
        OPERATOR_CREATE, OPERATOR_RETIRE, OPERATOR_CASH_CREATE, NPC_CREATE, NPC_RETIRE
    }

    public record Draft(String source, int sourceVersion, Kind kind, EconomyCurrency currency,
                        long minor, UUID subject, long effectiveMillis, UUID reversal, UUID publication) {
        public Draft {
            if (source == null || !source.matches("[a-z0-9_.:/-]{1,96}") || sourceVersion < 1
                    || minor <= 0 || effectiveMillis < 0) throw new IllegalArgumentException("Activity draft");
            Objects.requireNonNull(kind); Objects.requireNonNull(currency); Objects.requireNonNull(subject);
        }
    }

    public record Observation(UUID id, WorldId world, UUID operation, int ordinal, Draft draft,
                              long accountingMillis, long day) {
        public Observation {
            Objects.requireNonNull(world); Objects.requireNonNull(operation); Objects.requireNonNull(draft);
            if (ordinal < 0 || ordinal >= MAX_OBSERVATIONS || accountingMillis < 0 || day < 0
                    || !stable("observation|" + world.value() + "|" + operation + "|1|" + ordinal).equals(id))
                throw new IllegalArgumentException("Activity observation identity");
        }
    }

    public record Capacity(UUID account, String bank, EconomyCurrency currency, long freeMinor) {
        public Capacity {
            Objects.requireNonNull(account); Objects.requireNonNull(currency);
            if (bank == null || !bank.matches("[a-z0-9_.:/-]{1,128}") || freeMinor < 0)
                throw new IllegalArgumentException("Activity capacity");
        }
    }

    public record Manifest(WorldId world, long originMillis, long activatedMillis, String sources) {
        public Manifest {
            Objects.requireNonNull(world);
            if (originMillis < 0 || activatedMillis < originMillis || !SOURCE_MANIFEST.equals(sources))
                throw new IllegalArgumentException("Activity source manifest");
        }
    }

    public record Batch(WorldId world, UUID operation, long accountingMillis, long day,
                        List<Observation> observations, List<Capacity> capacities,
                        String capacityCursor, boolean seed, boolean ready) {
        public Batch {
            Objects.requireNonNull(world); Objects.requireNonNull(operation);
            observations = List.copyOf(observations); capacities = List.copyOf(capacities);
            if (accountingMillis < 0 || day < 0 || observations.size() > MAX_OBSERVATIONS
                    || capacities.size() > MAX_CAPACITIES || capacityCursor == null
                    || (!capacityCursor.isEmpty() && !canonicalUuid(capacityCursor).toString().equals(capacityCursor))
                    || (seed && !observations.isEmpty())) throw new IllegalArgumentException("Activity batch bounds");
            for (int i = 0; i < observations.size(); i++) {
                var o = observations.get(i);
                if (!world.equals(o.world()) || !operation.equals(o.operation()) || o.ordinal() != i
                        || accountingMillis != o.accountingMillis() || day != o.day())
                    throw new IllegalArgumentException("Activity batch observation");
            }
            if (capacities.stream().map(Capacity::bank).distinct().count() > 25)
                throw new IllegalArgumentException("Activity bank bound");
            var accounts = new HashSet<UUID>();
            for (var capacity : capacities) if (!accounts.add(capacity.account()))
                throw new IllegalArgumentException("Duplicate capacity account");
        }
    }

    public static Observation observation(WorldId world, UUID operation, int ordinal, Draft draft, long accountingMillis, long day) {
        return new Observation(stable("observation|" + world.value() + "|" + operation + "|1|" + ordinal),
                world, operation, ordinal, draft, accountingMillis, day);
    }

    public static RecordCatalog register(RecordCatalog catalog) {
        return catalog.with(new RecordCatalog.Key(MANIFEST, 1), ActivityFacts::decodeManifest)
                .with(new RecordCatalog.Key(BATCH, 1), ActivityFacts::decodeBatch);
    }

    public static String encode(Manifest manifest) {
        return write(out -> { out.writeInt(1); uuid(out, manifest.world().value()); out.writeLong(manifest.originMillis());
            out.writeLong(manifest.activatedMillis()); out.writeUTF(manifest.sources()); });
    }

    public static Manifest decodeManifest(String payload) {
        var value = read(payload, in -> {
            version(in); return new Manifest(new WorldId(uuid(in)), in.readLong(), in.readLong(), in.readUTF());
        });
        if (!encode(value).equals(payload)) throw new IllegalArgumentException("Noncanonical activity manifest");
        return value;
    }

    public static String encode(Batch batch) {
        return write(out -> {
            out.writeInt(1); uuid(out, batch.world().value()); uuid(out, batch.operation());
            out.writeLong(batch.accountingMillis()); out.writeLong(batch.day());
            out.writeUTF(batch.capacityCursor()); out.writeBoolean(batch.seed()); out.writeBoolean(batch.ready());
            out.writeInt(batch.observations().size());
            for (var observation : batch.observations()) {
                var d = observation.draft();
                out.writeUTF(d.source()); out.writeInt(d.sourceVersion()); out.writeUTF(d.kind().name());
                out.writeUTF(d.currency().name()); out.writeLong(d.minor()); uuid(out, d.subject());
                out.writeLong(d.effectiveMillis()); nullableUuid(out, d.reversal()); nullableUuid(out, d.publication());
            }
            var banks = batch.capacities().stream().map(Capacity::bank).distinct().sorted().toList();
            var currencies = batch.capacities().stream().map(c -> c.currency().name()).distinct().sorted().toList();
            out.writeInt(banks.size()); for (var bank : banks) out.writeUTF(bank);
            out.writeInt(currencies.size()); for (var currency : currencies) out.writeUTF(currency);
            out.writeInt(batch.capacities().size());
            for (var c : batch.capacities()) { uuid(out, c.account()); out.writeByte(banks.indexOf(c.bank()));
                out.writeByte(currencies.indexOf(c.currency().name())); out.writeLong(c.freeMinor()); }
        });
    }

    public static Batch decodeBatch(String payload) {
        var value = read(payload, in -> {
            version(in); var world = new WorldId(uuid(in)); var operation = uuid(in);
            long accounting = in.readLong(), day = in.readLong(); String cursor = in.readUTF();
            boolean seed = in.readBoolean(), ready = in.readBoolean();
            int count = count(in, MAX_OBSERVATIONS); var observations = new ArrayList<Observation>();
            for (int i = 0; i < count; i++) {
                var draft = new Draft(in.readUTF(), in.readInt(), Kind.valueOf(in.readUTF()),
                        EconomyCurrency.valueOf(in.readUTF()), in.readLong(), uuid(in), in.readLong(),
                        nullableUuid(in), nullableUuid(in));
                observations.add(observation(world, operation, i, draft, accounting, day));
            }
            int bankCount = count(in, 25); var banks = new ArrayList<String>();
            for (int i = 0; i < bankCount; i++) banks.add(in.readUTF());
            int currencyCount = count(in, 11); var currencies = new ArrayList<EconomyCurrency>();
            for (int i = 0; i < currencyCount; i++) currencies.add(EconomyCurrency.valueOf(in.readUTF()));
            int capacityCount = count(in, MAX_CAPACITIES); var capacities = new ArrayList<Capacity>();
            for (int i = 0; i < capacityCount; i++) {
                var account = uuid(in); int bank = in.readUnsignedByte(), currency = in.readUnsignedByte();
                if (bank >= banks.size() || currency >= currencies.size()) throw new IllegalArgumentException("Capacity dictionary");
                capacities.add(new Capacity(account, banks.get(bank), currencies.get(currency), in.readLong()));
            }
            return new Batch(world, operation, accounting, day, observations, capacities, cursor, seed, ready);
        });
        if (!encode(value).equals(payload)) throw new IllegalArgumentException("Noncanonical activity batch");
        return value;
    }

    public static UUID stable(String value) {
        return UUID.nameUUIDFromBytes(("economy:a8:activity:v1|" + value).getBytes(StandardCharsets.UTF_8));
    }
    public static UUID canonicalUuid(String value) {
        var id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Noncanonical activity UUID");
        return id;
    }

    @FunctionalInterface private interface Writer { void write(DataOutputStream out) throws IOException; }
    @FunctionalInterface private interface Reader<T> { T read(DataInputStream in) throws IOException; }
    private static String write(Writer writer) {
        try {
            var bytes = new ByteArrayOutputStream(); try (var out = new DataOutputStream(bytes)) { writer.write(out); }
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
            if (encoded.length() > 16_384) throw new IllegalArgumentException("Activity fact size");
            return encoded;
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }
    private static <T> T read(String payload, Reader<T> reader) {
        if (payload == null || payload.length() > 16_384) throw new IllegalArgumentException("Activity fact size");
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(payload);
            try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                T result = reader.read(in); if (in.available() != 0) throw new IllegalArgumentException("Trailing activity bytes");
                return result;
            }
        } catch (IOException | IllegalArgumentException malformed) {
            throw new IllegalArgumentException("Malformed activity fact", malformed);
        }
    }
    private static void version(DataInputStream in) throws IOException { if (in.readInt() != 1) throw new IllegalArgumentException("Activity fact version"); }
    private static int count(DataInputStream in, int maximum) throws IOException {
        int value = in.readInt(); if (value < 0 || value > maximum) throw new IllegalArgumentException("Activity count"); return value;
    }
    private static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void nullableUuid(DataOutputStream out, UUID id) throws IOException { out.writeBoolean(id != null); if (id != null) uuid(out, id); }
    private static UUID nullableUuid(DataInputStream in) throws IOException { return in.readBoolean() ? uuid(in) : null; }
}
