package ca.emilybelnavis.mcmods.economy.probe;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static ca.emilybelnavis.mcmods.economy.journal.Custody.Action.*;
import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Real chest and ItemStack serialization, with disk reread and world-local custody facts. */
public final class CustodyMinecraftInventoryProbe {
  private Path temporary;
  private static RegistryAccess registries;
  private final PlayerId owner = new PlayerId(UUID.randomUUID());
  private final UUID source = UUID.randomUUID();
  private final UUID target = UUID.randomUUID();
  private static final String LOCATION = "custody-fixture/chest.nbt";

  public static void run(Path temporary, RegistryAccess registryAccess) throws Exception {
    var fixture = new CustodyMinecraftInventoryProbe();
    fixture.temporary = temporary;
    registries = registryAccess;
    fixture.realChestSerializationPairsWithLedgerAndRejectsBothRestoreSkews();
  }

  private void realChestSerializationPairsWithLedgerAndRejectsBothRestoreSkews() throws Exception {
    Path world = Files.createDirectory(temporary.resolve("world"));
    Files.createDirectory(world.resolve("custody-fixture"));
    var store = new SqliteStorage();
    store.initialize(world);
    UUID custody = UUID.randomUUID();
    var worldId = store.snapshot().worldId();
    var original = financialIntent();
    var terms =
        new Custody.Terms(
            owner,
            "minecraft:stone[count=4,custom_name=Custody parcel]",
            LOCATION,
            LOCATION,
            Custody.financialIntent(original));
    CustodySaveWitness.Expected reservation;
    CustodySaveWitness.Expected delivered;
    byte[] oldInventory;
    byte[] newInventory;
    Path oldDatabase = temporary.resolve("old.sqlite");
    Path newDatabase = temporary.resolve("new.sqlite");
    try {
      seed(store);
      var reserve =
          new Intent(
              new OperationId(custody),
              new RequestId(UUID.randomUUID()),
              owner,
              RESERVE.kind(),
              Custody.reserveRequest(terms),
              null,
              null);
      assertEquals(Status.COMMITTED, engine(store, RESERVE, world, null).submit(reserve).status());
      reservation = writeChest(world, worldId, custody, UUID.randomUUID(), 0);
      oldInventory = Files.readAllBytes(world.resolve(LOCATION));
      assertTrue(CustodySaveWitness.matches(world, reservation));
      assertEquals(
          Status.COMMITTED,
          transition(
                  store,
                  world,
                  RESERVATION_SAVED,
                  custody,
                  null,
                  evidence(Custody.EvidenceKind.RESERVATION_SAVED, reservation),
                  null,
                  null)
              .status());
      assertEquals(
          Status.COMMITTED,
          transition(
                  store,
                  world,
                  COMMIT,
                  custody,
                  null,
                  Custody.Evidence.unknown(),
                  original.operationId().value(),
                  Custody.financial(
                      original,
                      Settlement.registration(
                          (intent, legs, reads) -> PostingBatch.Authority.TRANSFER)))
              .status());
      assertEquals(
          Status.COMMITTED,
          transition(
                  store,
                  world,
                  CLAIM,
                  custody,
                  null,
                  evidence(Custody.EvidenceKind.UNDELIVERED, reservation),
                  null,
                  null)
              .status());
    } finally {
      store.close();
    }
    Files.copy(world.resolve("economy/economy.sqlite"), oldDatabase);

    var resumed = new SqliteStorage();
    resumed.initialize(world);
    try {
      var attempt = UUID.randomUUID();
      assertEquals(
          Status.COMMITTED,
          transition(
                  resumed,
                  world,
                  COLLECT,
                  custody,
                  attempt,
                  Custody.Evidence.unknown(),
                  attempt,
                  null)
              .status());
      delivered = writeChest(world, worldId, custody, attempt, 4);
      newInventory = Files.readAllBytes(world.resolve(LOCATION));
      assertEquals(
          Status.COMMITTED,
          transition(
                  resumed,
                  world,
                  ACKNOWLEDGE,
                  custody,
                  attempt,
                  Custody.Evidence.unknown(),
                  null,
                  null)
              .status());
      assertEquals(
          Status.COMMITTED,
          transition(
                  resumed,
                  world,
                  DELIVERY_SAVED,
                  custody,
                  attempt,
                  evidence(Custody.EvidenceKind.DELIVERY_SAVED, delivered),
                  null,
                  null)
              .status());
    } finally {
      resumed.close();
    }
    Files.copy(world.resolve("economy/economy.sqlite"), newDatabase);

    // Each branch starts from a stopped database and a separately restored persisted inventory.
    assertRestored(world, oldDatabase, oldInventory, custody, reservation, true, 0);
    assertRestored(world, newDatabase, newInventory, custody, delivered, true, 4);
    assertRestored(world, oldDatabase, newInventory, custody, reservation, false, 4);
    assertRestored(world, newDatabase, oldInventory, custody, delivered, false, 0);
  }

  private void assertRestored(
      Path originalWorld,
      Path database,
      byte[] inventory,
      UUID custody,
      CustodySaveWitness.Expected witness,
      boolean matches,
      int count)
      throws Exception {
    Path world = Files.createDirectory(temporary.resolve("restore-" + UUID.randomUUID()));
    try (var paths = Files.walk(originalWorld)) {
      for (var path : paths.toList()) {
        var destination = world.resolve(originalWorld.relativize(path));
        if (Files.isDirectory(path)) Files.createDirectories(destination);
        else Files.copy(path, destination);
      }
    }
    Files.copy(
        database, world.resolve("economy/economy.sqlite"), StandardCopyOption.REPLACE_EXISTING);
    Files.write(world.resolve(LOCATION), inventory);
    var store = new SqliteStorage();
    store.initialize(world);
    try {
      assertEquals(matches, CustodySaveWitness.matches(world, witness));
      var loadedTag = NbtIo.readCompressed(world.resolve(LOCATION), NbtAccounter.create(1_048_576));
      var loaded = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
      loaded.loadWithComponents(loadedTag, registries);
      assertEquals(count, loaded.getItem(0).getCount());
      assertEquals(custody, loaded.getPersistentData().getUUID("custody"));
      assertEquals(witness.world().value(), loaded.getPersistentData().getUUID("world"));
      if (count > 0) {
        assertTrue(loaded.getItem(0).is(Items.STONE));
        assertEquals(
            "Custody parcel", loaded.getItem(0).get(DataComponents.CUSTOM_NAME).getString());
      }
      if (!matches) {
        assertTrue(Custody.reconcileBatch(store));
        assertEquals(
            Custody.State.QUARANTINED,
            store
                .transaction(tx -> Custody.find(tx::query, owner, custody, false))
                .orElseThrow()
                .state());
        assertTrue(
            store.transaction(tx -> Custody.claims(tx::query, owner, null, 63)).cases().isEmpty());
      }
    } finally {
      store.close();
    }
  }

  private CustodySaveWitness.Expected writeChest(
      Path world, WorldId worldId, UUID custody, UUID generation, int count) throws Exception {
    var chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
    if (count > 0) {
      var stack = new ItemStack(Items.STONE, count);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("Custody parcel"));
      chest.setItem(0, stack);
    }
    chest.getPersistentData().putUUID("world", worldId.value());
    chest.getPersistentData().putUUID("custody", custody);
    chest.getPersistentData().putUUID("generation", generation);
    var tag = chest.saveWithFullMetadata(registries);
    var serialized = new ByteArrayOutputStream();
    NbtIo.writeCompressed(tag, serialized);
    var bytes = serialized.toByteArray();
    Files.write(world.resolve(LOCATION), bytes);
    return new CustodySaveWitness.Expected(
        worldId, custody, generation, LOCATION, CustodySaveWitness.digest(bytes));
  }

  private Custody.Evidence evidence(
      Custody.EvidenceKind kind, CustodySaveWitness.Expected witness) {
    return new Custody.Evidence(kind, "persisted-chest-nbt", LOCATION, witness.token());
  }

  private Outcome transition(
      SqliteStorage store,
      Path world,
      Custody.Action action,
      UUID custody,
      UUID attempt,
      Custody.Evidence evidence,
      UUID operation,
      Custody.Financial financial) {
    return engine(store, action, world, financial)
        .submit(
            new Intent(
                new OperationId(operation == null ? UUID.randomUUID() : operation),
                new RequestId(UUID.randomUUID()),
                owner,
                action.kind(),
                Custody.request(custody, attempt, evidence, "inventory persistence fixture"),
                null,
                null));
  }

  private JournalEngine engine(
      SqliteStorage store, Custody.Action action, Path world, Custody.Financial financial) {
    return new JournalEngine(
        store,
        (actor, kind) -> actor.equals(owner),
        Map.of(
            action.kind(),
            Custody.registration(
                action,
                (a, intent, current, reads) -> intent.actor().equals(owner),
                (current, evidence, reads) -> {
                  var token = evidence.saveToken().split(":", -1);
                  if (token.length != 5 || !token[0].equals("v1")) return false;
                  var expected =
                      new CustodySaveWitness.Expected(
                          new WorldId(UUID.fromString(token[1])),
                          UUID.fromString(token[2]),
                          UUID.fromString(token[3]),
                          evidence.location(),
                          token[4]);
                  return expected.world().equals(store.snapshot().worldId())
                      && expected.custody().equals(current.id())
                      && CustodySaveWitness.matches(world, expected);
                },
                financial,
                (intent, current, reads) -> true)));
  }

  private Intent financialIntent() {
    return new Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        owner,
        new Kind("economy:inventory_fixture", "economy:transfer"),
        Settlement.request(
            List.of(
                new PostingBatch.Leg(source, ACCOUNT, CAD, new Money(CAD, -1_000)),
                new PostingBatch.Leg(target, ACCOUNT, CAD, new Money(CAD, 1_000)))),
        null,
        null);
  }

  private void seed(SqliteStorage store) {
    store.transaction(
        tx -> {
          for (var id : List.of(source, target))
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,0)",
                List.of(id.toString(), UUID.randomUUID().toString()));
          return null;
        });
    var kind = new Kind("economy:inventory_fixture", "economy:issue");
    var engine =
        new JournalEngine(
            store,
            (actor, requested) -> true,
            Map.of(
                kind,
                Settlement.registration((intent, legs, reads) -> PostingBatch.Authority.ISSUE)));
    assertEquals(
        Status.COMMITTED,
        engine
            .submit(
                new Intent(
                    new OperationId(UUID.randomUUID()),
                    new RequestId(UUID.randomUUID()),
                    owner,
                    kind,
                    Settlement.request(
                        List.of(
                            new PostingBatch.Leg(source, ACCOUNT, CAD, new Money(CAD, 10_000)),
                            new PostingBatch.Leg(
                                Settlement.issuanceAccount(CAD),
                                ISSUANCE,
                                CAD,
                                new Money(CAD, -10_000)))),
                    null,
                    null))
            .status());
  }

  private static void assertTrue(boolean value) {
    if (!value) throw new IllegalStateException("Container custody fixture assertion failed");
  }

  private static void assertEquals(Object expected, Object actual) {
    if (!Objects.equals(expected, actual))
      throw new IllegalStateException(
          "Container custody fixture expected " + expected + ", got " + actual);
  }
}
