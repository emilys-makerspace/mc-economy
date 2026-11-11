package ca.emilybelnavis.mcmods.economy.journal;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CustodySaveWitnessTest {
  @TempDir Path world;

  @Test
  void pairedSnapshotMatchesWhileEitherSkewFails() throws Exception {
    var id = UUID.randomUUID();
    var worldId = new WorldId(UUID.randomUUID());
    var generation = UUID.randomUUID();
    var original =
        ("world="
                + worldId.value()
                + ";custody="
                + id
                + ";generation="
                + generation
                + ";items=minecraft:stone,count=4")
            .getBytes(StandardCharsets.UTF_8);
    var expected =
        new CustodySaveWitness.Expected(
            worldId, id, generation, "player.dat", CustodySaveWitness.digest(original));
    Files.write(world.resolve("player.dat"), original);
    assertTrue(CustodySaveWitness.matches(world, expected));
    Files.writeString(world.resolve("player.dat"), "older inventory snapshot");
    assertFalse(CustodySaveWitness.matches(world, expected));
    Files.write(world.resolve("player.dat"), original);
    var newerLedger =
        new CustodySaveWitness.Expected(
            worldId,
            id,
            UUID.randomUUID(),
            "player.dat",
            CustodySaveWitness.digest("newer committed delivery".getBytes(StandardCharsets.UTF_8)));
    assertFalse(CustodySaveWitness.matches(world, newerLedger));
  }

  @Test
  void unobservableAndUnboundedLocationsCannotProveSave() throws Exception {
    var expected =
        new CustodySaveWitness.Expected(
            new WorldId(UUID.randomUUID()),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "player.dat",
            CustodySaveWitness.digest(new byte[0]));
    assertFalse(CustodySaveWitness.matches(world, expected));
    Files.write(world.resolve("player.dat"), new byte[CustodySaveWitness.MAX_BYTES + 1]);
    assertFalse(CustodySaveWitness.matches(world, expected));
    Files.delete(world.resolve("player.dat"));
    Files.createSymbolicLink(world.resolve("player.dat"), world.resolve("absent.dat"));
    assertFalse(CustodySaveWitness.matches(world, expected));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CustodySaveWitness.Expected(
                expected.world(),
                expected.custody(),
                expected.generation(),
                "../outside.dat",
                expected.sha256()));
  }
}
