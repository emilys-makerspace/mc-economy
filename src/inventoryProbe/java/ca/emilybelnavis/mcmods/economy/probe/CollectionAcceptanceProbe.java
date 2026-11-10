package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.collection.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.LedgerProjection;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/** Explicitly opted-in disposable-world fixture, excluded from release jars. */
public final class CollectionAcceptanceProbe {
  private static final UUID OWNER = UUID.fromString("8bd5dbfb-c21f-48aa-bddb-2a1720b60eb7");
  private static final UUID OTHER = UUID.fromString("3c34f3a5-dd4b-4fd2-a9f4-0b1f7258a58b");
  private static final boolean BANKING = Boolean.getBoolean("economy.collectionBankingProbe");
  private static final AccountId CHF_FUNDING =
      new AccountId(UUID.fromString("d59e41ec-c892-4f52-a086-11ef3688b031"));
  private static long originalReportExpiry, originalCollectionExpiry;
  private static final AccountId FUNDING =
      new AccountId(UUID.fromString("90d37d94-c7ab-4091-a7be-c374bb87569b"));
  private static final InstitutionCatalog.InstitutionId BANK =
      new InstitutionCatalog.InstitutionId("economy:bank/crownstone");
  private static ServerPlayer player, stranger;
  private static CollectionAgent agent;
  private static UUID firstLease, paidCase, lastPayment;
  private static int stage;
  private static long deadline, nextAction;
  private static boolean seeded, reading;
  private static String failure;

  private CollectionAcceptanceProbe() {}

  public static boolean tick(MinecraftServer server) throws Exception {
    if (failure != null) throw new IllegalStateException(failure);
    var services = Economy.serverServices(server);
    if (stage == 0) {
      if (Files.exists(
          server.getWorldPath(LevelResource.ROOT).resolve("collection-probe-passed.txt"))) {
        player = BankingAcceptanceProbe.fixture(server, OWNER, "CollectionRestart");
        player.setPos(8.5, 201, 8.5);
        deadline = server.getTickCount() + 400;
        stage = 100;
        return false;
      }
      for (int x = -1; x <= 1; x++)
        for (int z = -1; z <= 1; z++) server.overworld().setChunkForced(x, z, true);
      for (int x = -8; x <= 24; x++)
        for (int z = -8; z <= 24; z++) {
          server.overworld().setBlock(new BlockPos(x, 200, z), Blocks.STONE.defaultBlockState(), 3);
          for (int y = 201; y <= 205; y++)
            server.overworld().setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
      player = BankingAcceptanceProbe.fixture(server, OWNER, "CollectionDebtor");
      stranger = BankingAcceptanceProbe.fixture(server, OTHER, "CollectionOther");
      for (var p : List.of(player, stranger)) {
        p.setPos(8.5, 201, 8.5);
        p.setInvulnerable(true);
        net.neoforged.neoforge.network.registration.ChannelAttributes.getOrCreateAdHocChannels(
                p.connection.getConnection())
            .add(CollectionNetwork.Snapshot.PAYLOAD_TYPE.id());
      }
      deadline = server.getTickCount() + 1800;
      stage = 1;
      return false;
    }
    require(server.getTickCount() < deadline, "fixture turn budget stage " + stage);
    switch (stage) {
      case 100 -> {
        if (!reading) {
          reading = true;
          services
              .ledger()
              .collectionAgentState(grant(server), null, 4)
              .whenComplete(
                  (found, error) -> {
                    reading = false;
                    if (error != null) return;
                    require(
                        found.isPresent()
                            && found.orElseThrow().state().phase()
                                == CollectionPresence.Phase.KILLED,
                        "saved death cooldown survives restart");
                    require(
                        found.orElseThrow().state().cooldownUntil() > System.currentTimeMillis(),
                        "saved cooldown remains active");
                    stage = 101;
                    nextAction = server.getTickCount() + 60;
                  });
        }
      }
      case 101 -> {
        if (server.getTickCount() >= nextAction) {
          require(find(server) == null, "restart cannot spawn a duplicate or bypass cooldown");
          BankingAcceptanceProbe.unregister(server, player);
          Files.writeString(
              server
                  .getWorldPath(LevelResource.ROOT)
                  .resolve("collection-probe-restart-passed.txt"),
              "saved cooldown and no duplicate after restart");
          return true;
        }
      }
      case 1 -> {
        if (player.containerMenu instanceof BankingMenu menu && menu.worldSetup()) {
          if (menu.acceptAction(player, menu.session(), menu.revision(), "setup"))
            BankingController.action(player, menu, "setup", "CAD");
        }
        if (!reading) {
          reading = true;
          services
              .ledger()
              .bankingContext(
                  new PlayerId(OWNER),
                  new TerminalPolicy.Anchor("minecraft:overworld", 8, 201, 8),
                  null)
              .whenComplete(
                  (found, error) -> {
                    reading = false;
                    if (error == null && found != null && found.fx() != null) stage = 2;
                  });
        }
      }
      case 2 -> {
        if (services.metrics().economy().ready()) {
          player.closeContainer();
          seed(server);
          stage = 3;
        }
      }
      case 3 -> {
        if (BANKING && seeded) {
          if (FixtureBankingTerminal.open(player, new BlockPos(12, 201, 8))) stage = 200;
          break;
        }
        if (seeded
            && (agent = find(server)) != null
            && CollectionAgents.canInteract(playerAt(agent), agent)) {
          firstLease = agent.presentation();
          require(agent.getMaxHealth() == 20, "twenty health");
          CollectionDialogue.open(stranger, agent);
          require(!(stranger.containerMenu instanceof CollectionMenu), "other player denied");
          CollectionDialogue.open(player, agent);
          stage = 4;
        }
      }
      case 200 -> {
        if (bankAct(server, "finance_debt_home", "")) stage = 201;
      }
      case 201 -> {
        if (bankReady()) {
          var choice =
              bankMenu().choices().stream()
                  .filter(c -> c.action().equals("finance_debt_case"))
                  .findFirst()
                  .orElse(null);
          if (choice != null) {
            paidCase = UUID.fromString(choice.value());
            if (bankAct(server, choice.action(), choice.value())) stage = 202;
          }
        }
      }
      case 202 -> {
        if (bankReady()
            && bankMenu().choices().stream()
                .anyMatch(c -> c.action().equals("finance_debt_funding"))) {
          if (!reading) {
            reading = true;
            services
                .ledger()
                .collectionCase(grant(server), paidCase)
                .whenComplete(
                    (found, error) -> {
                      reading = false;
                      if (error != null) {
                        failure = error.toString();
                        return;
                      }
                      var debt = found.orElseThrow();
                      originalReportExpiry = debt.reportExpires();
                      originalCollectionExpiry = debt.collectionExpires();
                      stage = 203;
                    });
          }
        }
      }
      case 203 -> {
        if (bankAct(server, "finance_debt_funding", CHF_FUNDING.value().toString())) stage = 204;
      }
      case 204 -> {
        if (bankReady()
            && bankMenu().choices().stream().anyMatch(c -> c.action().equals("finance_debt_amount"))
            && bankAct(server, "finance_debt_amount", "10.00")) stage = 205;
      }
      case 205, 210 -> {
        if (bankReady()
            && bankMenu().choices().stream()
                .anyMatch(c -> c.action().equals("finance_debt_confirm"))) {
          require(
              bankMenu().lines().stream().anyMatch(l -> l.contains("CHF")),
              "FX source shown in bank review");
          require(
              bankMenu().lines().stream()
                  .anyMatch(l -> l.toLowerCase(java.util.Locale.ROOT).contains("fee")),
              "explicit FX fee review");
          var current = bankMenu();
          long revision = current.revision();
          int next = stage == 205 ? 206 : 211;
          if (bankAct(server, "finance_debt_confirm", "")) {
            require(
                !current.acceptAction(player, current.session(), revision, "finance_debt_confirm"),
                "duplicate bank confirmation denied");
            stage = next;
          }
        }
      }
      case 206, 211 -> {
        if (bankReady()
            && !player.getPersistentData().contains("economy_banking_pending")
            && !reading) {
          reading = true;
          boolean full = stage == 211;
          services
              .ledger()
              .collectionCase(grant(server), paidCase)
              .whenComplete(
                  (found, error) -> {
                    reading = false;
                    if (error != null) {
                      failure = error.toString();
                      return;
                    }
                    var debt = found.orElseThrow();
                    require(
                        debt.reportExpires() == originalReportExpiry,
                        "bank payment preserves report expiry");
                    if (full) {
                      require(
                          debt.payment() == DebtRecords.Payment.SETTLED && debt.debtMinor() == 0,
                          "full FX bank collection settles once");
                      stage = 212;
                    } else {
                      require(
                          debt.debtMinor() == 9000
                              && debt.collectionExpires() > originalCollectionExpiry,
                          "partial FX bank payment acknowledges exact amount");
                      stage = 207;
                    }
                  });
        }
      }
      case 207 -> {
        if (bankAct(server, "finance_debt_home", "")) stage = 208;
      }
      case 208 -> {
        if (bankReady() && bankAct(server, "finance_debt_case", paidCase.toString())) stage = 209;
      }
      case 209 -> {
        if (bankReady()
            && bankMenu().choices().stream()
                .anyMatch(c -> c.action().equals("finance_debt_funding"))
            && bankAct(server, "finance_debt_funding", CHF_FUNDING.value().toString())) stage = 213;
      }
      case 213 -> {
        if (bankReady()
            && bankMenu().choices().stream().anyMatch(c -> c.action().equals("finance_debt_amount"))
            && bankAct(server, "finance_debt_amount", "90.00")) stage = 210;
      }
      case 212 -> {
        BankingAcceptanceProbe.unregister(server, player);
        BankingAcceptanceProbe.unregister(server, stranger);
        Files.writeString(
            server.getWorldPath(LevelResource.ROOT).resolve("collection-banking-passed.txt"),
            "physical teller owner banking CHF-to-CAD partial/full collection payments, explicit fee review, acknowledgement, original report date and duplicate confirmation denial");
        return true;
      }
      case 4 -> {
        if (ready()) {
          require(
              menu().choices().stream().filter(c -> c.action().equals("select_case")).count() == 2,
              "one agent aggregates two debts");
          require(
              !menu()
                  .accept(
                      player,
                      new CollectionNetwork.Action(
                          menu().containerId, UUID.randomUUID(), menu().revision(), "exit", "")),
              "stale session denied");
          if (act(server, "exit", "")) {
            agent.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            stage = 5;
          }
        }
      }
      case 5 -> {
        var next = find(server);
        if (next != null
            && !firstLease.equals(next.presentation())
            && CollectionAgents.canInteract(playerAt(next), next)) {
          agent = next;
          CollectionDialogue.open(player, agent);
          stage = 6;
        }
      }
      case 6 -> {
        if (ready()) {
          var offered =
              menu().choices().stream()
                  .filter(c -> c.action().equals("select_case"))
                  .findFirst()
                  .orElseThrow();
          paidCase = UUID.fromString(offered.value());
          if (act(server, offered.action(), offered.value())) stage = 7;
        }
      }
      case 7 -> {
        if (ready() && act(server, "funding", "")) stage = 8;
      }
      case 8 -> {
        if (ready() && menu().page().equals("funding")) {
          var offered =
              menu().choices().stream()
                  .filter(c -> c.action().equals("select_funding"))
                  .findFirst()
                  .orElseThrow();
          if (act(server, offered.action(), offered.value())) stage = 9;
        }
      }
      case 9 -> {
        if (ready() && act(server, "preview_partial", "0.01")) stage = 10;
      }
      case 10 -> {
        if (ready() && menu().page().equals("review")) {
          var duplicate =
              new CollectionNetwork.Action(
                  menu().containerId, menu().session(), menu().revision(), "confirm", "");
          var current = menu();
          if (act(server, "confirm", "")) {
            require(!current.accept(player, duplicate), "concurrent confirmation denied");
            stage = 11;
          }
        }
      }
      case 11 -> {
        if (!reading) {
          reading = true;
          services
              .ledger()
              .collectionCase(grant(server), paidCase)
              .whenComplete(
                  (found, error) -> {
                    reading = false;
                    if (error != null) {
                      failure = error.toString();
                      return;
                    }
                    if (found.isPresent() && found.orElseThrow().debtMinor() == 9999) stage = 30;
                  });
        }
      }
      case 30 -> {
        agent = find(server);
        if (agent != null && CollectionAgents.canInteract(playerAt(agent), agent)) {
          player.closeContainer();
          CollectionDialogue.open(player, agent);
          stage = 31;
        }
      }
      case 31 -> {
        if (ready() && act(server, "select_case", paidCase.toString())) stage = 32;
      }
      case 32 -> {
        if (ready() && act(server, "funding", "")) stage = 33;
      }
      case 33 -> {
        if (ready() && menu().page().equals("funding")) {
          var offered =
              menu().choices().stream()
                  .filter(c -> c.action().equals("select_funding"))
                  .findFirst()
                  .orElseThrow();
          if (act(server, offered.action(), offered.value())) stage = 34;
        }
      }
      case 34 -> {
        if (ready() && act(server, "preview_full", "")) stage = 35;
      }
      case 35 -> {
        if (ready() && menu().page().equals("review") && act(server, "confirm", "")) {
          lastPayment =
              player
                  .getPersistentData()
                  .getCompound("economy_collection_payment")
                  .getUUID("operation");
          stage = 36;
        }
      }
      case 36 -> {
        if (!reading) {
          reading = true;
          services
              .ledger()
              .collectionCase(grant(server), paidCase)
              .whenComplete(
                  (found, error) -> {
                    reading = false;
                    if (error != null) {
                      failure = error.toString();
                      return;
                    }
                    if (found.isPresent()
                        && found.orElseThrow().payment() == DebtRecords.Payment.SETTLED) stage = 12;
                  });
        }
      }
      case 12 -> {
        player.closeContainer();
        agent = find(server);
        if (agent != null && CollectionAgents.canInteract(playerAt(agent), agent)) {
          agent.hurt(server.overworld().damageSources().generic(), 100);
          stage = 13;
        }
      }
      case 13 -> {
        if (!reading) {
          reading = true;
          services
              .ledger()
              .collectionAgentState(grant(server), null, 4)
              .whenComplete(
                  (found, error) -> {
                    reading = false;
                    if (error != null) {
                      failure = error.toString();
                      return;
                    }
                    if (found.isPresent()
                        && found.orElseThrow().state().phase() == CollectionPresence.Phase.KILLED) {
                      require(found.orElseThrow().cases().size() == 1, "death retains unpaid debt");
                      require(
                          found.orElseThrow().state().cooldownUntil()
                              >= found.orElseThrow().state().observedAt()
                                  + CollectionPresence.COOLDOWN,
                          "seven-day cooldown");
                      var marker = new net.minecraft.nbt.CompoundTag();
                      marker.putUUID("world", services.worldId().value());
                      marker.putUUID("operation", lastPayment);
                      player.getPersistentData().put("economy_collection_payment", marker);
                      stage = 14;
                      nextAction = server.getTickCount() + 60;
                    }
                  });
        }
      }
      case 14 -> {
        if (server.getTickCount() >= nextAction) {
          require(find(server) == null, "no replacement during cooldown");
          require(
              !player.getPersistentData().contains("economy_collection_payment"),
              "receipt recovery without an agent");
          BankingAcceptanceProbe.unregister(server, player);
          player = BankingAcceptanceProbe.fixture(server, OWNER, "CollectionReconnect");
          player.setPos(8.5, 201, 8.5);
          nextAction = server.getTickCount() + 40;
          stage = 15;
        }
      }
      case 15 -> {
        if (server.getTickCount() >= nextAction) {
          require(find(server) == null, "reconnect preserves cooldown");
          require(
              server
                  .overworld()
                  .getEntitiesOfClass(
                      net.minecraft.world.entity.item.ItemEntity.class,
                      new AABB(-8, 198, -8, 24, 207, 24))
                  .isEmpty(),
              "no item drops");
          require(
              server
                  .overworld()
                  .getEntitiesOfClass(
                      net.minecraft.world.entity.ExperienceOrb.class,
                      new AABB(-8, 198, -8, 24, 207, 24))
                  .isEmpty(),
              "no experience");
          BankingAcceptanceProbe.unregister(server, player);
          BankingAcceptanceProbe.unregister(server, stranger);
          Files.writeString(
              server.getWorldPath(LevelResource.ROOT).resolve("collection-probe-passed.txt"),
              "owner menu, two debts, exit, unload, native partial/full once, death, reconnect, no loot/XP");
          return true;
        }
      }
    }
    return false;
  }

  private static ca.emilybelnavis.mcmods.economy.banking.BankingMenu bankMenu() {
    return player.containerMenu instanceof ca.emilybelnavis.mcmods.economy.banking.BankingMenu m
        ? m
        : null;
  }

  private static boolean bankReady() {
    return bankMenu() != null && bankMenu().valid(player) && !bankMenu().busy();
  }

  private static boolean bankAct(MinecraftServer server, String action, String value) {
    if (!bankReady() || server.getTickCount() < nextAction) return false;
    var menu = bankMenu();
    if (!menu.allows(action)) throw new IllegalStateException("Bank action unavailable " + action);
    require(
        menu.acceptAction(player, menu.session(), menu.revision(), action),
        "bank action gate " + action);
    ca.emilybelnavis.mcmods.economy.banking.BankingController.checkedAction(
        player, menu, action, value);
    nextAction = server.getTickCount() + 3;
    return true;
  }

  private static ServerPlayer playerAt(CollectionAgent entity) {
    player.setPos(entity.getX() + 2, entity.getY(), entity.getZ());
    return player;
  }

  private static CollectionAgent find(MinecraftServer server) {
    var agents =
        server
            .overworld()
            .getEntitiesOfClass(
                CollectionAgent.class,
                new AABB(-100, 100, -100, 100, 300, 100),
                e -> e.isAlive() && OWNER.equals(e.debtor()));
    require(agents.size() <= 1, "no duplicate live presentation");
    return agents.isEmpty() ? null : agents.getFirst();
  }

  private static CollectionMenu menu() {
    return player.containerMenu instanceof CollectionMenu menu ? menu : null;
  }

  private static boolean ready() {
    return menu() != null && !menu().busy();
  }

  private static boolean act(MinecraftServer server, String action, String value) {
    if (!ready() || server.getTickCount() < nextAction) return false;
    var menu = menu();
    require(
        menu.accept(
            player,
            new CollectionNetwork.Action(
                menu.containerId, menu.session(), menu.revision(), action, value)),
        "action gate " + action);
    CollectionDialogue.action(player, menu, action, value);
    nextAction = server.getTickCount() + 3;
    return true;
  }

  private static PartyAuthorization.Grant grant(MinecraftServer server) {
    var services = Economy.serverServices(server);
    return PartyAuthorization.player(
            player,
            Economy::serverServices,
            new PartyRef(services.worldId(), new PartyRef.Player(new PlayerId(OWNER))))
        .orElseThrow();
  }

  private static void seed(MinecraftServer server) {
    var services = Economy.serverServices(server);
    var owner = new PlayerId(OWNER);
    var world = services.worldId();
    long now = System.currentTimeMillis();
    var borrower = new InstitutionCatalog.InstitutionId("economy:borrower/" + OWNER);
    var receivable =
        FinancialAccounts.account(
            world, BANK, FinancialAccounts.Role.RECEIVABLE, EconomyCurrency.CAD);
    var liability =
        FinancialAccounts.account(
            world, borrower, FinancialAccounts.Role.LIABILITY, EconomyCurrency.CAD);
    var kind = new Journal.Kind("fixture:collections", "fixture:seed");
    var registration =
        new JournalEngine.Registration(
            1,
            (i, reads) -> {
              var records = new ArrayList<Journal.Record>();
              var cases = new ArrayList<DebtRecords.Case>();
              var cards = new ArrayList<CreditFacilities.Facility>();
              for (int number = 0; number < 2; number++) {
                var id = UUID.randomUUID();
                var debt =
                    new DebtRecords.Case(
                        world,
                        UUID.randomUUID(),
                        DebtRecords.Kind.CARD,
                        id,
                        owner,
                        BANK,
                        BANK,
                        EconomyCurrency.CAD,
                        10000,
                        0,
                        now,
                        now,
                        now + DebtRecords.COLLECTION_WINDOW,
                        now + DebtRecords.REPORT_WINDOW,
                        DebtRecords.Sale.PENDING,
                        DebtRecords.Collectability.COLLECTIBLE,
                        DebtRecords.Payment.UNPAID,
                        0,
                        1);
                var card =
                    new CreditFacilities.Facility(
                        world,
                        id,
                        owner,
                        BANK,
                        InstitutionCatalog.Network.ORBIT,
                        EconomyCurrency.CAD,
                        250000,
                        1,
                        now,
                        now,
                        now,
                        1,
                        CreditFacilities.Status.DEFAULTED,
                        CreditFacilities.Access.CLOSED,
                        new CardServicing.Bucket(
                            CardServicing.Kind.PURCHASE,
                            10000,
                            0,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            new BigDecimal("0.1999")),
                        CardServicing.empty(CardServicing.Kind.ADVANCE, new BigDecimal("0.2199")),
                        List.of(),
                        List.of());
                cards.add(card);
                cases.add(debt);
                records.add(CreditFacilities.fact(card));
                records.add(DebtRecords.fact(debt));
              }
              var funding =
                  (JournalEngine.Commit)
                      PostingBatch.prepareMinor(
                          List.of(
                              new PostingBatch.Leg(
                                  Settlement.issuanceAccount(EconomyCurrency.CAD),
                                  PostingBatch.Role.ISSUANCE,
                                  EconomyCurrency.CAD,
                                  new Money(EconomyCurrency.CAD, -100000)),
                              new PostingBatch.Leg(
                                  FUNDING.value(),
                                  PostingBatch.Role.ACCOUNT,
                                  EconomyCurrency.CAD,
                                  new Money(EconomyCurrency.CAD, 100000))),
                          PostingBatch.Authority.ISSUE);
              records.addAll(funding.records());
              if (BANKING) {
                var chf =
                    (JournalEngine.Commit)
                        PostingBatch.prepareMinor(
                            List.of(
                                new PostingBatch.Leg(
                                    Settlement.issuanceAccount(EconomyCurrency.CHF),
                                    PostingBatch.Role.ISSUANCE,
                                    EconomyCurrency.CHF,
                                    new Money(EconomyCurrency.CHF, -100000)),
                                new PostingBatch.Leg(
                                    CHF_FUNDING.value(),
                                    PostingBatch.Role.ACCOUNT,
                                    EconomyCurrency.CHF,
                                    new Money(EconomyCurrency.CHF, 100000))),
                            PostingBatch.Authority.ISSUE);
                records.addAll(chf.records());
                records.add(
                    new Journal.Record(
                        "economy:bank-provider",
                        1,
                        "opened|" + CHF_FUNDING.value() + "|economy:bank/lakeward_cantonal|1|0|0"));
              }
              records.add(
                  new Journal.Record(
                      "economy:posting",
                      3,
                      receivable.id() + "|INSTITUTION_CLAIM|CAD|20000|TRANSFER"));
              records.add(
                  new Journal.Record(
                      "economy:posting",
                      3,
                      liability.id() + "|INSTITUTION_CLAIM|CAD|-20000|TRANSFER"));
              long chfIssuance =
                  BANKING
                      ? Math.subtractExact(
                          Settlement.posted(
                              reads,
                              Settlement.issuanceAccount(EconomyCurrency.CHF),
                              EconomyCurrency.CHF),
                          100000)
                      : 0;
              long issuanceBalance =
                  Math.subtractExact(
                      Settlement.posted(
                          reads,
                          Settlement.issuanceAccount(EconomyCurrency.CAD),
                          EconomyCurrency.CAD),
                      100000);
              return new JournalEngine.Commit(
                  records,
                  new Journal.Result(1, "COLLECTION_FIXTURE_SEEDED", ""),
                  tx -> {
                    tx.execute(
                        "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                        List.of(FUNDING.value().toString(), OWNER.toString()));
                    tx.execute(
                        "INSERT OR IGNORE INTO institution_parties VALUES(?,?,?,?,?,'INTERNAL',1)",
                        List.of(
                            world.value().toString(),
                            borrower.key(),
                            new InstitutionCatalog.SystemActor(world, borrower)
                                .actorId()
                                .value()
                                .toString(),
                            "Fixture borrower",
                            "CAD"));
                    if (BANKING) {
                      tx.execute(
                          "INSERT INTO account_catalog VALUES(?,?,'BANK','CHF','OPEN',1,0)",
                          List.of(CHF_FUNDING.value().toString(), OWNER.toString()));
                      tx.execute(
                          "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
                          List.of(
                              CHF_FUNDING.value().toString(), "economy:bank/lakeward_cantonal"));
                      LedgerProjection.write(tx, CHF_FUNDING.value(), EconomyCurrency.CHF, 100000);
                      LedgerProjection.write(
                          tx,
                          Settlement.issuanceAccount(EconomyCurrency.CHF),
                          EconomyCurrency.CHF,
                          chfIssuance);
                    }
                    FinancialAccounts.register(tx, receivable);
                    FinancialAccounts.register(tx, liability);
                    LedgerProjection.write(tx, FUNDING.value(), EconomyCurrency.CAD, 100000);
                    LedgerProjection.write(
                        tx,
                        Settlement.issuanceAccount(EconomyCurrency.CAD),
                        EconomyCurrency.CAD,
                        issuanceBalance);
                    LedgerProjection.write(tx, receivable.id(), EconomyCurrency.CAD, 20000);
                    LedgerProjection.write(tx, liability.id(), EconomyCurrency.CAD, -20000);
                    for (var card : cards) CreditFacilities.write(tx, card);
                    for (var debt : cases) DebtRecords.write(tx, debt);
                  });
            });
    services
        .journal((a, k) -> a.equals(owner), Map.of(kind, registration), null)
        .submit(
            new Journal.Intent(
                new OperationId(UUID.randomUUID()),
                new RequestId(UUID.randomUUID()),
                owner,
                kind,
                new Journal.CanonicalRequest(1, Map.of()),
                null,
                null))
        .completion()
        .whenComplete(
            (result, error) -> {
              if (error == null && result.outcome().result().code().equals("ECONOMIC_CATCH_UP")) {
                stage = 2;
                return;
              }
              if (error != null || result.outcome().status() != Journal.Status.COMMITTED)
                failure =
                    "Seed failed " + error + (result == null ? "" : result.outcome().result());
              else seeded = true;
            });
  }

  private static void require(boolean value, String reason) {
    if (!value) throw new IllegalStateException("Collection fixture " + reason);
  }
}
