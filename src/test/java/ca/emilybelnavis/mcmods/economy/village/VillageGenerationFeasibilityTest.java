package ca.emilybelnavis.mcmods.economy.village;

import ca.emilybelnavis.mcmods.economy.village.generation.BankedVillageStructure;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the production village wrapper and the vanilla piece materialization API. */
class VillageGenerationFeasibilityTest {
    @BeforeAll static void bootstrap() {
        // Standalone API fixture has no discovered mods; use the real empty loader list.
        if (net.neoforged.fml.loading.LoadingModList.get() == null)
            net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void pinnedFiveStyleJigsawParametersSurviveCodecRoundTrip() throws Exception {
        var vanilla = VanillaRegistries.createLookup();
        var ops = RegistryOps.create(JsonOps.INSTANCE, withStableBiomeTags(vanilla));
        var structures = vanilla.lookupOrThrow(Registries.STRUCTURE);
        var wrapperCodec = BankedVillageStructure.CODEC.codec();
        var jigsawCodec = JigsawStructure.CODEC.codec();
        for (var style : List.of("plains", "desert", "savanna", "snowy", "taiga")) {
            String name = "village_" + style;
            var path = Path.of("src/main/resources/data/minecraft/worldgen/structure", name + ".json");
            var resource = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            assertEquals("economy:banked_village", resource.get("type").getAsString(), style);
            var wrapper = wrapperCodec.parse(ops, resource).getOrThrow();
            var encoded = wrapperCodec.encodeStart(ops, wrapper).getOrThrow();
            assertEquals(style, encoded.getAsJsonObject().get("style").getAsString());
            var reloaded = wrapperCodec.parse(ops, encoded).getOrThrow();
            assertEquals(encoded, wrapperCodec.encodeStart(ops, reloaded).getOrThrow(), style);

            // The vanilla registry supplies an independent baseline even when the mod overrides the data resource.
            var key = ResourceKey.create(Registries.STRUCTURE, ResourceLocation.withDefaultNamespace(name));
            var original = assertInstanceOf(JigsawStructure.class, structures.getOrThrow(key).value(), style);
            assertEquals(jigsawCodec.encodeStart(ops, original).getOrThrow(), encoded.getAsJsonObject().get("delegate"), style);
        }
    }

    private static RegistryOps.RegistryInfoLookup withStableBiomeTags(HolderLookup.Provider vanilla) {
        var biomes = vanilla.lookupOrThrow(Registries.BIOME);
        var tags = new HashMap<TagKey<Biome>, Optional<HolderSet.Named<Biome>>>();
        var stableBiomes = new HolderLookup.RegistryLookup.Delegate<Biome>() {
            @Override public HolderLookup.RegistryLookup<Biome> parent() { return biomes; }
            @Override public Optional<HolderSet.Named<Biome>> get(TagKey<Biome> tag) {
                // Datagen creates a fresh placeholder per read. The runtime registry shares each named tag.
                return tags.computeIfAbsent(tag, biomes::get);
            }
        };
        var getters = HolderLookup.Provider.create(vanilla.listRegistries()
                .map(key -> key.equals(Registries.BIOME) ? stableBiomes : vanilla.lookupOrThrow(key)));
        var original = vanilla.createSerializationContext(JsonOps.INSTANCE).lookupProvider;
        return new RegistryOps.RegistryInfoLookup() {
            @Override public <T> Optional<RegistryOps.RegistryInfo<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
                return original.lookup(key).map(info -> new RegistryOps.RegistryInfo<>(
                        info.owner(), getters.lookupOrThrow(key), info.elementsLifecycle()));
            }
        };
    }

    @Test void deferredLayoutCanBeMaterializedOnceBeforeImmutableStartSnapshot() {
        var calls = new AtomicInteger();
        var original = new ProbePiece(new BoundingBox(0, 60, 0, 10, 67, 10));
        var appended = new ProbePiece(new BoundingBox(40, 60, 0, 50, 67, 10));
        var position = new BlockPos(0, 60, 0);
        var deferred = new Structure.GenerationStub(position, builder -> {
            calls.incrementAndGet();
            builder.addPiece(original);
        });
        var builder = deferred.getPiecesBuilder();
        var vanillaSnapshot = builder.build();
        builder.addPiece(appended);
        var wrapped = new Structure.GenerationStub(deferred.position(), Either.right(builder));
        var finalSnapshot = wrapped.getPiecesBuilder().build();
        assertSame(builder, wrapped.getPiecesBuilder());
        assertEquals(1, calls.get());
        assertEquals(List.of(original), vanillaSnapshot.pieces());
        assertEquals(List.of(original, appended), finalSnapshot.pieces());
        assertSame(original, finalSnapshot.pieces().getFirst());
        assertEquals(position, wrapped.position());
        assertEquals(50, finalSnapshot.calculateBoundingBox().maxX());
    }

    private static final class ProbePiece extends StructurePiece {
        ProbePiece(BoundingBox box) { super(null, 0, box); }
        @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {}
        @Override public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator,
                RandomSource random, BoundingBox clip, ChunkPos chunk, BlockPos origin) {
            throw new AssertionError("The API experiment must not place world blocks");
        }
    }
}
