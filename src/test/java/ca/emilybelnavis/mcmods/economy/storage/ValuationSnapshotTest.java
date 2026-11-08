package ca.emilybelnavis.mcmods.economy.storage;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.valuation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ValuationSnapshotTest {
    @TempDir Path temporary;
    private static final long NOW = Instant.parse("2026-11-07T12:00:00Z").toEpochMilli();
    private static final String EMPTY = "{\"bases\":[],\"overrides\":[]}";
    private static ValuationData.Inputs inputs(String recipe) {
        var items = new TreeSet<>(ValuationData.catalog().entries().keySet());
        items.add("fixture:gem");
        return new ValuationData.Inputs(items, ValuationData.hash(recipe), ValuationData.hash("tags"), ValuationData.hash("features"));
    }
    private static ValuationData.Manifest manifest(String config) {
        return ValuationData.prepare(inputs("recipes1"), ValuationConfig.decode(config), List.of());
    }
    private static EffectiveFxSnapshot fx(SqliteStorage store) {
        UUID operation = UUID.randomUUID();
        var actor = new PlayerId(UUID.randomUUID());
        var opened = new JournalEngine(store, (a,k) -> true,
                Map.of(FxService.OPEN, FxService.opening(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC))))
                .submit(new Journal.Intent(new OperationId(operation), new RequestId(operation), actor, FxService.OPEN,
                        new Journal.CanonicalRequest(1, Map.of("currency", "CAD")), null, null));
        assertEquals(Journal.Status.COMMITTED, opened.status());
        return store.transaction(tx -> FxService.at(tx::query, NOW));
    }
    @Test void exactSourcesCapturedFxAndPublishedUnitsNeverGuessMissingValues() throws Exception {
        var store = new SqliteStorage(); store.initialize(temporary.toRealPath());
        try {
            var fx = fx(store);
            var config = new ValuationConfig.Config(List.of(new ValuationConfig.Entry("minecraft:oak_log", EconomyCurrency.CAD, "3", 1)),
                    List.of(new ValuationConfig.Entry("minecraft:oak_log", EconomyCurrency.CAD, "4", 1),
                            new ValuationConfig.Entry("minecraft:diamond", EconomyCurrency.USD, "1.50", 3),
                            new ValuationConfig.Entry("minecraft:dirt", EconomyCurrency.CAD, "0.01", 3),
                            new ValuationConfig.Entry("minecraft:cobblestone", EconomyCurrency.JPY, "1", 3)));
            var manifest = ValuationData.prepare(inputs("recipes1"), config, List.of());
            var foreign = BaseValues.evaluate(manifest, "minecraft:diamond", "{}", 3, EconomyCurrency.CAD, fx);
            assertEquals(BaseValues.Status.READY, foreign.status());
            assertEquals(Rational.of("69.8"), foreign.exactUnitMinor());
            assertEquals(70, foreign.unitMoney().minorUnits()); assertEquals(210, foreign.totalMoney().minorUnits());
            assertEquals(fx.id(), foreign.fxPublication());
            assertEquals(400, BaseValues.evaluate(manifest,"minecraft:oak_log","{}",1,EconomyCurrency.CAD,fx).unitMoney().minorUnits());
            var floor = BaseValues.evaluate(manifest,"minecraft:dirt","{}",3,EconomyCurrency.CAD,fx);
            assertEquals(Rational.of(1).divide(Rational.of(3)), floor.exactUnitMinor());
            assertEquals(1, floor.unitMoney().minorUnits()); assertEquals(3, floor.totalMoney().minorUnits());
            var yen = BaseValues.evaluate(manifest,"minecraft:cobblestone","{}",3,EconomyCurrency.JPY,fx);
            assertEquals(1, yen.unitMoney().minorUnits()); assertEquals(3, yen.totalMoney().minorUnits());
            assertEquals(Rational.ZERO, BaseValues.inputCost(BaseValues.Input.FREE_WATER));
            assertEquals(BaseValues.Status.MISSING_INPUT, BaseValues.evaluate(manifest,"minecraft:iron_ingot","{}",1,EconomyCurrency.CAD,fx).status());
            assertEquals(BaseValues.Status.UNPRICED_OPERATOR, BaseValues.evaluate(manifest,"minecraft:air","{}",1,EconomyCurrency.CAD,fx).status());
            assertEquals(BaseValues.Status.MOD_UNREGISTERED, BaseValues.evaluate(manifest,"fixture:gem","{}",1,EconomyCurrency.CAD,fx).status());
            assertEquals(BaseValues.Status.UNSUPPORTED_IDENTITY, BaseValues.evaluate(manifest,"minecraft:diamond","{\"damage\":1}",1,EconomyCurrency.CAD,fx).status());
            assertEquals(BaseValues.Status.FX_UNAVAILABLE, BaseValues.evaluate(manifest,"minecraft:diamond","{}",1,EconomyCurrency.CAD,null).status());
            var huge = ValuationData.prepare(inputs("recipes1"), new ValuationConfig.Config(List.of(), List.of(
                    new ValuationConfig.Entry("minecraft:diamond",EconomyCurrency.CAD,"99999999999999999999",1))), List.of());
            assertEquals(BaseValues.Status.OVERFLOW, BaseValues.evaluate(huge,"minecraft:diamond","{}",1,EconomyCurrency.CAD,fx).status());
        } finally { store.close(); }
    }
    @Test void publicationPreservesOriginalsRejectsBadReloadsAndReopensWholeManifests() throws Exception {
        Path world = temporary.toRealPath();
        var store = new SqliteStorage(); store.initialize(world);
        ValuationStore.Snapshot original, changed;
        try {
            var gate = new ValuationStore.Gate();
            String payload = ValuationData.encode(manifest(EMPTY));
            assertTrue(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 131072,
                    "real catalog manifest must exercise multi-transaction staging");
            original = ValuationStore.publish(store,payload,NOW,gate,0);
            long sequence = store.lastCommittedSequence();
            assertEquals(original, ValuationStore.publish(store,payload,NOW+1,gate,0));
            assertEquals(sequence, store.lastCommittedSequence());
            for (String bad : List.of("{\"bases\":[],\"bases\":[],\"overrides\":[]}",
                    "{\"bases\":[],\"overrides\":[{\"item\":\"minecraft:diamond\",\"currency\":\"CAD\",\"amount\":\"0\",\"referenceCount\":1}]}",
                    "{\"bases\":[],\"overrides\":[{\"item\":\"missing:item\",\"currency\":\"CAD\",\"amount\":\"1\",\"referenceCount\":1}]}")) {
                assertThrows(IllegalArgumentException.class, () -> manifest(bad));
                assertEquals(original, ValuationStore.active(store));
                assertEquals(sequence, store.lastCommittedSequence());
            }
            var entry = new ValuationConfig.Entry("minecraft:diamond",EconomyCurrency.CAD,"301",1);
            String candidate = ValuationData.encode(ValuationData.prepare(inputs("recipes2"),
                    new ValuationConfig.Config(List.of(),List.of(entry)),List.of()));
            gate.invalidate();
            assertNull(ValuationStore.publish(store,candidate,NOW+2,gate,0));
            assertEquals(original, ValuationStore.active(store));
            changed = ValuationStore.publish(store,candidate,NOW+3,gate,1);
            assertNotEquals(original.id(),changed.id()); assertEquals(2,changed.revision());
            assertEquals(original,ValuationStore.load(store,original.id()));
            assertEquals(List.of(original.id()),ValuationStore.history(store,changed.id(),16).stream().map(ValuationStore.Metadata::id).toList());
            var reverted = ValuationStore.publish(store,payload,NOW+4,gate,1);
            assertEquals(3,reverted.revision()); assertNotEquals(original.id(),reverted.id());
            assertEquals(original.hash(),reverted.hash());
            assertThrows(RuntimeException.class,()->store.transaction(tx->tx.execute("DELETE FROM valuation_chunks",List.of())));
        } finally { store.close(); }
        var reopened = new SqliteStorage(); reopened.initialize(world);
        try {
            assertEquals(original,ValuationStore.load(reopened,original.id()));
            assertEquals(changed,ValuationStore.load(reopened,changed.id()));
            assertEquals(3,ValuationStore.active(reopened).revision());
            long before = reopened.lastCommittedSequence();
            ValuationStore.history(reopened,null,16); ValuationStore.load(reopened,original.id());
            assertEquals(before,reopened.lastCommittedSequence());
        } finally { reopened.close(); }
    }
    @Test void contributionConflictsRejectAtomicallyAndRemovalChangesManifestIdentity() {
        var entry = new ValuationConfig.Entry("fixture:gem",EconomyCurrency.CAD,"2",1);
        var first = new ValuationData.Contribution("fixture:first","r1",Set.of("fixture:gem"),List.of(entry));
        var second = new ValuationData.Contribution("fixture:second","r1",Set.of("fixture:gem"),List.of(entry));
        var accepted = ValuationData.prepare(inputs("recipes1"),ValuationConfig.Config.empty(),List.of(first));
        assertEquals(accepted,ValuationData.decode(ValuationData.encode(accepted)));
        assertThrows(IllegalArgumentException.class,()->ValuationData.prepare(inputs("recipes1"),ValuationConfig.Config.empty(),List.of(first,second)));
        assertThrows(IllegalArgumentException.class,()->ValuationData.prepare(inputs("recipes1"),ValuationConfig.Config.empty(),List.of(second,first)));
        assertNotEquals(accepted.hash(),manifest(EMPTY).hash());
        assertThrows(IllegalArgumentException.class,()->new ValuationData.Contribution("other:provider","r1",Set.of("fixture:gem"),List.of(entry)));
    }
    @Test void v49UpgradeRequiresBackupAndPreservesWorldAndPriorJournal() throws Exception {
        Path world = temporary.toRealPath(); Path directory = Files.createDirectory(world.resolve("economy"));
        UUID identity = UUID.randomUUID();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("economy.sqlite"))) {
            SchemaMigrations.apply(connection,identity,0,49,SchemaMigrations.REGISTRY.subList(0,49));
        }
        StorageFiles.writeNew(directory.resolve("world-identity"),SqliteStorage.encodeIdentity(identity));
        Files.writeString(world.resolve("level.dat"),"disposable complete-world fixture");
        assertEquals(StorageException.Kind.SCHEMA,assertThrows(StorageException.class,()->new SqliteStorage().initialize(world)).kind());
        UpgradeBackup.prepare(world);
        var store = new SqliteStorage(); store.initialize(world);
        try {
            assertEquals(identity,store.snapshot().worldId().value()); assertEquals(SchemaMigrations.CURRENT,store.snapshot().schemaVersion());
            assertEquals(0,store.lastCommittedSequence()); assertNull(ValuationStore.active(store));
        } finally { store.close(); }
    }
}
