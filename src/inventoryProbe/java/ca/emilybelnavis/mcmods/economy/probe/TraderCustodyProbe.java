package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.cash.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import ca.emilybelnavis.mcmods.economy.trade.cash.CashDenominations;
import ca.emilybelnavis.mcmods.economy.trade.custody.TraderInventory;
import ca.emilybelnavis.mcmods.economy.trade.entity.NativeCatalog;
import ca.emilybelnavis.mcmods.economy.trade.settlement.NpcTrades;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.trading.*;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Actual inventory and independent player-file witness; financial economics use the SQLite test.
 */
public final class TraderCustodyProbe {
  private TraderCustodyProbe() {}

  public static volatile String result, failure;

  public static boolean tick(MinecraftServer server) throws Exception {
    if (result != null || failure != null) return true;
    if (server.getPlayerList().getPlayers().isEmpty()) return false;
    require(
        server
            .getWorldPath(LevelResource.ROOT)
            .toAbsolutePath()
            .normalize()
            .getFileName()
            .toString()
            .matches("economy-trader-custody-[0-9a-f-]{36}"),
        "disposable world");
    var player = server.getPlayerList().getPlayers().getFirst();
    for (var denomination : CashCatalog.all()) {
      var frozen =
          CashDenominations.forCurrency(denomination.currency()).stream()
              .filter(d -> d.id().equals("economy:" + denomination.id()))
              .findFirst()
              .orElseThrow();
      require(
          frozen.minorUnits() == denomination.minorUnits() && frozen.bill() == denomination.bill(),
          "frozen cash catalogue matches registered items");
    }
    var store = new SqliteStorage();
    try {
      var directory = Files.createTempDirectory("economy-npc-custody-sqlite-");
      store.initialize(directory.toRealPath());
      var world = store.snapshot().worldId();
      var actor = new PlayerId(player.getUUID());
      for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
      player.getInventory().setItem(0, new ItemStack(Items.WHEAT, 10));
      var coin = CashCatalog.byId("economy:cad_coin_100").orElseThrow();
      player.getInventory().setItem(1, new ItemStack(coin.item().get(), 2));
      UUID original = UUID.randomUUID(),
          input = UUID.randomUUID(),
          goods = UUID.randomUUID(),
          cash = UUID.randomUUID();
      var kind = new Journal.Kind("economy:fixture", "economy:npc-custody");
      var financial =
          new Journal.Intent(
              new OperationId(original),
              new RequestId(original),
              actor,
              kind,
              new Journal.CanonicalRequest(1, Map.of("fixture", "physical")),
              null,
              null);
      var snapshot = TraderInventory.snapshot(player, world, original, Map.of(0, 3, 1, 2));
      var terms =
          new Custody.Terms(
              actor,
              snapshot.encode(),
              "fixture-input",
              "player:" + player.getUUID(),
              Custody.financialIntent(financial));
      submit(
          store,
          new Journal.Intent(
              new OperationId(input),
              new RequestId(input),
              actor,
              Custody.Action.RESERVE.kind(),
              Custody.reserveRequest(terms),
              null,
              null),
          Custody.registration(
              Custody.Action.RESERVE, (a, i, c, r) -> true, (c, e, r) -> false, null));
      var reserved = find(store, actor, input);
      var combined =
          List.of(
              new ItemStack(Items.BREAD),
              CashStackIdentity.marked(new ItemStack(coin.item().get()), world, cash));
      require(
          !TraderInventory.fitsCombined(player, snapshot, combined),
          "combined capacity rejects before consumption");
      require(
          TraderInventory.reserve(player, reserved, snapshot, combined)
              .equals("CAPACITY_OR_INPUT_CHANGED"),
          "capacity rejection");
      require(
          player.getInventory().getItem(0).getCount() == 10
              && player.getInventory().getItem(1).getCount() == 2,
          "capacity failure consumes nothing");
      player.getInventory().setItem(2, ItemStack.EMPTY);
      require(
          TraderInventory.fitsCombined(player, snapshot, combined),
          "combined post-removal capacity");
      require(
          TraderInventory.cashSlots(player, snapshot).size() == 1
              && TraderInventory.cashSlots(player, snapshot).getFirst().unmarked(),
          "operator cash detached explicitly");
      require(
          TraderInventory.reserve(player, reserved, snapshot, combined)
              .equals("HELD_REQUIRES_SAVE"),
          "exact originals held");
      require(
          player.getInventory().getItem(0).getCount() == 7
              && player.getInventory().getItem(1).isEmpty(),
          "selected counts removed exactly");
      var proof = TraderInventory.saveProof(player, reserved, world, true);
      change(
          store,
          actor,
          input,
          Custody.Action.RESERVATION_SAVED,
          null,
          proof.evidence(),
          TraderInventory.verifier(player));
      var lot =
          new CashIssuance.Lot(
              world,
              cash,
              "economy:cad_coin_100",
              1,
              1,
              CashIssuance.State.ACTIVE,
              1,
              "fixture-original-output");
      var offer =
          NativeCatalog.detachOffer(
              0,
              new MerchantOffer(
                  new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD), 12, 2, 0),
              server.registryAccess());
      var reg =
          new JournalEngine.Registration(
              1,
              (intent, reads) -> {
                var a =
                    Custody.committedDelivery(
                        intent,
                        goods,
                        actor,
                        NpcTrades.outputItems(offer, 1),
                        "fixture-goods",
                        "player:" + player.getUUID(),
                        reads);
                var b =
                    Custody.committedDelivery(
                        intent,
                        cash,
                        actor,
                        WithdrawalDelivery.items(List.of(lot)),
                        "fixture-cash",
                        "player:" + player.getUUID(),
                        reads);
                var records = new ArrayList<Journal.Record>(a.records());
                records.addAll(b.records());
                var outputs =
                    new JournalEngine.Commit(
                        records,
                        new Journal.Result(1, "FIXTURE_OUTPUTS", ""),
                        tx -> {
                          a.projections().apply(tx);
                          b.projections().apply(tx);
                          tx.execute(
                              "INSERT INTO custody_cash_reservations VALUES(?,?,1,'ACTIVE')",
                              List.of(cash.toString(), cash.toString()));
                        });
                var issued = CashIssuance.composeIssuance(world, List.of(lot), outputs, reads);
                if (!(issued instanceof JournalEngine.Commit committed)) return issued;
                var child =
                    new Journal.Intent(
                        intent.operationId(),
                        intent.requestId(),
                        actor,
                        Custody.Action.COMMIT.kind(),
                        Custody.request(input, null, Custody.Evidence.unknown(), "fixture commit"),
                        null,
                        null);
                return Custody.registration(
                        Custody.Action.COMMIT,
                        (ac, i, c, r) -> true,
                        (c, e, r) -> false,
                        (i, c, r) -> committed,
                        (i, c, r) -> true)
                    .handler()
                    .prepare(child, reads);
              });
      submit(store, financial, reg);
      TraderInventory.consumeCommitted(player, find(store, actor, input));
      deliver(store, player, actor, world, goods);
      deliver(store, player, actor, world, cash);
      require(player.getInventory().countItem(Items.BREAD) == 1, "goods exactly once");
      require(
          player.getInventory().countItem(coin.item().get()) == 1,
          "original marked cash exactly once");
      require(
          store.transaction(
                  tx -> CashIssuance.find(tx::query, world, cash).orElseThrow().remaining())
              == 1,
          "delivery does not issue duplicate lot quantity");
      result =
          "PASS combined capacity rejected before consumption; exact partial goods and unmarked cash reservation; actual independently read player/integrated-owner saves; saved STARTED delivery capability; single goods/cash delivery and replay blocked; frozen denominations match registered catalogue";
    } finally {
      store.close();
    }
    return true;
  }

  private static void deliver(
      SqliteStorage store, ServerPlayer player, PlayerId actor, WorldId world, UUID id) {
    UUID attempt = UUID.randomUUID();
    var intent =
        new Journal.Intent(
            new OperationId(attempt),
            new RequestId(attempt),
            actor,
            Custody.Action.BEGIN_DELIVERY.kind(),
            Custody.request(id, attempt, Custody.Evidence.unknown(), "fixture delivery"),
            null,
            null);
    var fresh =
        TraderInventory.beginDelivery(store, intent, (a, i, c, r) -> true, (i, c, r) -> true);
    require(fresh != null, "fresh authoritative transition");
    var delivery = find(store, actor, id);
    var start = TraderInventory.markDeliveryStart(player, fresh, world);
    require(start.attempt() != null, "fresh start");
    var saved = TraderInventory.saveProof(player, delivery, world, false);
    require(
        TraderInventory.deliverOnce(start.attempt(), saved, find(store, actor, id))
            .equals("DELIVERED_REQUIRES_SAVE"),
        "delivery with independent STARTED save");
    require(
        TraderInventory.deliverOnce(start.attempt(), saved, find(store, actor, id))
            .equals("ORIGINAL_NOT_REEXECUTED"),
        "attempt cannot run twice");
    require(
        TraderInventory.beginDelivery(store, intent, (a, i, c, r) -> true, (i, c, r) -> true)
            == null,
        "replayed transition grants no capability");
    var delivered = TraderInventory.saveProof(player, delivery, world, false);
    change(
        store,
        actor,
        id,
        Custody.Action.DELIVERY_SAVED,
        attempt,
        delivered.evidence(),
        TraderInventory.verifier(player));
    require(
        find(store, actor, id).state() == Custody.State.DELIVERED, "verified terminal delivery");
  }

  private static Custody.Case find(SqliteStorage store, PlayerId actor, UUID id) {
    return store.transaction(tx -> Custody.find(tx::query, actor, id, false).orElseThrow());
  }

  private static void change(
      SqliteStorage store,
      PlayerId actor,
      UUID id,
      Custody.Action action,
      UUID attempt,
      Custody.Evidence evidence,
      Custody.EvidenceVerifier verifier) {
    UUID op = UUID.randomUUID();
    submit(
        store,
        new Journal.Intent(
            new OperationId(op),
            new RequestId(op),
            actor,
            action.kind(),
            Custody.request(id, attempt, evidence, "fixture evidence"),
            null,
            null),
        Custody.registration(action, (a, i, c, r) -> true, verifier, null));
  }

  private static void submit(
      SqliteStorage store, Journal.Intent intent, JournalEngine.Registration registration) {
    var result =
        new JournalEngine(store, (a, k) -> true, Map.of(intent.kind(), registration))
            .submit(intent);
    require(result.status() == Journal.Status.COMMITTED, result.result().code());
  }

  private static void require(boolean value, String message) {
    if (!value) throw new IllegalStateException(message);
  }
}
