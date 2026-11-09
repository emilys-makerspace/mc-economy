package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.cash.*;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.terminal.*;
import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * Normal server controller/menu/identity gate acceptance with real ServerPlayers and dummy fixture
 * connections.
 */
public final class BankingAcceptanceProbe {
  private static final BlockPos TELLER = new BlockPos(136, 110, 136);
  private static final BlockPos REGISTER = new BlockPos(140, 110, 136);
  private static final UUID SELLER = UUID.fromString("bbf7d584-bfc0-41d2-bf9f-52988ed4dca5");
  private static final UUID BUYER = UUID.fromString("83cafb3e-4b64-4f7b-a0bd-7dc2a2b712ac");
  private static ServerPlayer seller, buyer, player;
  private static int stage;
  private static long deadline, nextAction, issuedRevision, lastRead;
  private static boolean reading, funded;
  private static LedgerService.BankingContext context;
  private static LedgerService.PrimaryFunds funds;
  private static UUID claim;
  private static AccountId euroAccount;
  private static AccountFunds euroFunds;
  private static Journal.Intent exchangeIntent;
  private static Journal.Outcome exchangeOutcome;
  private static String asynchronousFailure;

  private BankingAcceptanceProbe() {}

  public static boolean tick(MinecraftServer server) throws Exception {
    if (asynchronousFailure != null) throw new IllegalStateException(asynchronousFailure);
    if (stage == 0) {
      // Real players are not spawned/tracked as world entities in this fixture. Hold only its own
      // test chunk.
      server.overworld().setChunkForced(TELLER.getX() >> 4, TELLER.getZ() >> 4, true);
      seller = fixture(server, SELLER, "BankingSeller");
      player = seller;
      player.setPos(TELLER.getX() + 3, TELLER.getY(), TELLER.getZ() - 2);
      next(server, 1);
      return false;
    }
    if (server.getTickCount() > deadline)
      throw new IllegalStateException("Banking fixture timed out " + diagnostics());
    if (server.getTickCount() % 100 == 0)
      System.out.println("BANKING_ACCEPTANCE_WAIT " + diagnostics());
    switch (stage) {
      case 1 -> {
        if (menu() != null && menu().worldSetup() && act(server, "setup", "CAD")) next(server, 2);
        else if (menu() == null) readContext(server);
        if (context != null && context.fx() != null && (menu() == null || !menu().worldSetup()))
          next(server, 2);
      }
      case 2 -> {
        readContext(server);
        if (context == null || context.fx() == null) break;
        place(server, "teller", TELLER);
        next(server, 3);
      }
      case 3 -> {
        if (!physicalReady(TELLER)) break;
        BankingController.open(player, TELLER);
        require(
            menu() != null && menu().valid(player) && menu().page().equals("home"),
            "normal physical menu opens Home");
        next(server, 4);
      }
      case 4 -> {
        if (act(server, "accounts", "")) next(server, 70);
      }
      case 70 -> {
        if (!snapshotReady() || !menu().page().equals("accounts")) break;
        require(
            menu().choices().size() == 1 && menu().choices().getFirst().action().equals("setup"),
            "first account view offers only Open account");
        if (act(server, "setup", "")) next(server, 5);
      }
      case 5 -> {
        if (!ready()) break;
        readContext(server);
        if (context == null || context.account() == null) break;
        seed(server, 10_000);
        next(server, 6);
      }
      case 6 -> {
        if (funded && ready()) next(server, 7);
      }
      case 7 -> {
        if (!snapshotReady()) break;
        readContext(server);
        if (context == null
            || context.terminal() == null
            || context.terminal().settings().mode() != TerminalPolicy.Mode.BOTH) break;
        require(context.account().currency() == EconomyCurrency.CAD, "primary uses world currency");
        if (act(server, "accounts", "")) next(server, 8);
      }
      case 8 -> {
        if (!snapshotReady()) break;
        require(
            menu().page().equals("accounts")
                && menu().choices().stream()
                    .anyMatch(choice -> choice.action().equals("account_select"))
                && menu().choices().stream()
                    .anyMatch(choice -> choice.action().equals("manage_accounts")),
            "normal accounts snapshot offers history and management");
        if (act(server, "history", "")) next(server, 9);
      }
      case 9 -> {
        if (snapshotReady() && act(server, "fx", "")) next(server, 10);
      }
      case 10 -> {
        if (!snapshotReady()) break;
        require(menu().page().equals("fx") && !menu().lines().isEmpty(), "normal FX snapshot");
        fillInventory(player);
        if (act(server, "withdraw", "")) next(server, 11);
      }
      case 11 -> {
        if (snapshotReady() && act(server, "withdraw_preview", "CAD 60")) next(server, 12);
      }
      case 12 -> {
        if (!snapshotReady()) break;
        readFunds(server);
        if (funds == null) break;
        require(
            cashValue(player) == 0 && funds.funds().availableMinorUnits() == 10_000,
            "full inventory rejects before withdrawal debit");
        clearInventory(player);
        if (act(server, "withdraw_preview", "CAD 60")) next(server, 13);
      }
      case 13 -> {
        if (!snapshotReady() || !menu().status().equals("Withdrawal preview")) break;
        if (act(server, "withdraw_confirm", "")) next(server, 14);
      }
      case 14 -> {
        if (!deliveryComplete()) break;
        readFunds(server);
        if (funds == null) break;
        require(
            cashValue(player) == 6000 && funds.funds().availableMinorUnits() == 4000,
            "withdrawal delivers authentic cash after one debit");
        if (act(server, "deposit", "")) next(server, 15);
      }
      case 15 -> {
        if (!snapshotReady()) break;
        stageCashInputs();
        if (act(server, "deposit_preview", "")) next(server, 16);
      }
      case 16 -> {
        if (!snapshotReady() || !menu().status().startsWith("Preview ready")) break;
        if (act(server, "deposit_confirm", "")) next(server, 17);
      }
      case 17 -> {
        if (!ready() || !menu().status().equals("Thank you for using our banking service.")) break;
        readFunds(server);
        if (funds == null) break;
        require(
            cashValue(player) == 0 && funds.funds().availableMinorUnits() == 10_000,
            "normal deposit restores original balance");
        for (int index = 0; index < 24; index++)
          require(menu().depositInputs().getItem(index).isEmpty(), "consumed deposit inputs empty");
        player.closeContainer();
        BankingController.open(player, TELLER);
        next(server, 18);
      }
      case 18 -> {
        if (act(server, "withdraw", "")) next(server, 19);
      }
      case 19 -> {
        if (snapshotReady() && act(server, "withdraw_preview", "CAD 20")) next(server, 20);
      }
      case 20 -> {
        if (snapshotReady()
            && menu().status().equals("Withdrawal preview")
            && act(server, "withdraw_confirm", "")) next(server, 21);
      }
      case 21 -> {
        if (!deliveryComplete()) break;
        require(cashValue(player) == 2000, "second cash delivery");
        if (act(server, "deposit", "")) next(server, 22);
      }
      case 22 -> {
        if (!snapshotReady()) break;
        stageCashInputs();
        if (act(server, "deposit_preview", "")) next(server, 23);
      }
      case 23 -> {
        if (!snapshotReady()) break;
        require(menu().status().startsWith("Preview ready"), "uncommitted deposit preview");
        if (act(server, "accounts", "")) next(server, 50);
      }
      case 50 -> {
        if (!snapshotReady()) break;
        for (int index = 0; index < 24; index++)
          require(
              menu().depositInputs().getItem(index).isEmpty(),
              "page change releases unreserved inputs");
        require(cashValue(player) == 2000, "page change returns exact preview originals");
        readFunds(server);
        if (funds == null) break;
        require(
            funds.funds().availableMinorUnits() == 8000, "page change creates no deposit credit");
        if (act(server, "deposit", "")) next(server, 51);
      }
      case 51 -> {
        if (!snapshotReady()) break;
        stageCashInputs();
        if (act(server, "deposit_preview", "")) next(server, 52);
      }
      case 52 -> {
        if (!snapshotReady()) break;
        require(
            menu().status().startsWith("Preview ready"),
            "close claim begins from a normal preview");
        fillInventory(player);
        player.closeContainer();
        next(server, 24);
      }
      case 24 -> {
        findClaim(server);
        if (claim == null) break;
        BankingSkewCapture.capture(server, player, claim);
        player.getInventory().setItem(0, ItemStack.EMPTY);
        if (FixtureBankingTerminal.open(player, TELLER)) next(server, 25);
      }
      case 25 -> {
        if (!ready()) break;
        require(!menu().remote() && menu().valid(player), "physical teller banking access");
        require(
            !menu().acceptAction(player, UUID.randomUUID(), menu().revision(), "accounts"),
            "wrong session rejected");
        require(
            !menu().acceptAction(player, menu().session(), menu().revision() + 1, "accounts"),
            "wrong revision rejected");
        if (act(server, "claims", "")) next(server, 27);
      }
      case 27 -> {
        if (snapshotReady() && act(server, "collect", claim.toString())) next(server, 28);
      }
      case 28 -> {
        if (!deliveryComplete()) break;
        readFunds(server);
        if (funds == null) break;
        require(
            cashValue(player) == 2000 && funds.funds().availableMinorUnits() == 8000,
            "closed preview claim returns exact originals without credit or second debit");
        require(BankingInventory.stashed(player, claim).isEmpty(), "completed stash retired");
        player.closeContainer();
        clearNonCash(player);
        BankingController.open(player, TELLER);
        next(server, 29);
      }
      case 29 -> {
        if (act(server, "deposit", "")) next(server, 30);
      }
      case 30 -> {
        if (!snapshotReady()) break;
        stageCashInputs();
        BankingSaves.player(player, services().worldId(), UUID.randomUUID());
        require(
            player
                    .getPersistentData()
                    .getList("economy_banking_menu_inputs", Tag.TAG_COMPOUND)
                    .size()
                > 0,
            "actual saved transient input mirror");
        // Simulate a lost transient menu object without running removed(); this is not a process
        // crash.
        player.containerMenu = player.inventoryMenu;
        BankingController.open(player, TELLER);
        next(server, 31);
      }
      case 31 -> {
        if (!ready()) break;
        require(
            cashValue(player) == 2000
                && player
                    .getPersistentData()
                    .getList("economy_banking_menu_inputs", Tag.TAG_COMPOUND)
                    .isEmpty(),
            "saved input mirror recovers one original batch");
        BankingSaves.player(player, services().worldId(), UUID.randomUUID());
        player.closeContainer();
        player.setPos(REGISTER.getX() + 2, REGISTER.getY(), REGISTER.getZ() - 2);
        place(server, "register", REGISTER);
        ((ChestBlockEntity) player.serverLevel().getBlockEntity(REGISTER.below()))
            .setItem(0, new ItemStack(Items.DIAMOND, 4));
        next(server, 32);
      }
      case 32 -> {
        if (!physicalReady(REGISTER)) break;
        player.getInventory().selected = 8;
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND, 4));
        BankingController.open(player, REGISTER);
        next(server, 33);
      }
      case 33 -> {
        if (act(server, "price", "1")) next(server, 34);
      }
      case 34 -> {
        if (!snapshotReady()) break;
        readContext(server);
        if (context == null || context.price() == null) break;
        require(
            context.price().batch() == 4 && context.price().amount().minorUnits() == 100,
            "owner menu price exact batch");
        buyer = fixture(server, BUYER, "BankingBuyer");
        player = buyer;
        player.setPos(REGISTER.getX() + 2, REGISTER.getY(), REGISTER.getZ() - 2);
        if (FixtureBankingTerminal.open(player, TELLER)) next(server, 35);
      }
      case 35 -> {
        if (act(server, "accounts", "")) next(server, 71);
      }
      case 71 -> {
        if (snapshotReady()
            && menu().page().equals("accounts")
            && menu().choices().stream().anyMatch(choice -> choice.action().equals("setup"))
            && act(server, "setup", "")) next(server, 36);
      }
      case 36 -> {
        if (!ready()) break;
        readContext(server);
        if (context == null || context.account() == null) break;
        seed(server, 10_000);
        next(server, 37);
      }
      case 37 -> {
        if (funded) {
          BankingController.open(player, REGISTER);
          next(server, 38);
        }
      }
      case 38 -> {
        if (act(server, "buy_preview", "")) next(server, 39);
      }
      case 39 -> {
        if (!snapshotReady() || !menu().page().equals("shop")) break;
        if (act(server, "buy_confirm", "")) next(server, 40);
      }
      case 40 -> {
        if (!deliveryComplete()) break;
        readFunds(server);
        if (funds == null) break;
        require(
            count(player, Items.DIAMOND) == 4 && funds.funds().availableMinorUnits() == 9900,
            "normal buyer receives full paid batch once");
        require(
            ((ChestBlockEntity) player.serverLevel().getBlockEntity(REGISTER.below()))
                .getItem(0)
                .isEmpty(),
            "paid stock removed from source");
        player.closeContainer();
        player = seller;
        next(server, 41);
      }
      case 41 -> {
        readFunds(server);
        if (funds == null) break;
        require(
            funds.funds().availableMinorUnits() == 8100, "seller receives one register payment");
        player.closeContainer();
        player.setPos(TELLER.getX() + 3, TELLER.getY(), TELLER.getZ() - 2);
        BankingController.open(player, TELLER);
        next(server, 60);
      }
      case 60 -> {
        if (act(server, "manage_accounts", "")) next(server, 72);
      }
      case 72 -> {
        if (!snapshotReady() || !menu().page().equals("manage")) break;
        require(
            menu().choices().stream()
                .anyMatch(
                    choice ->
                        choice.action().equals("account_open") && choice.value().equals("EUR")),
            "Manage account offers unopened EUR currency");
        if (act(server, "account_open", "EUR")) next(server, 61);
      }
      case 61 -> {
        if (snapshotReady() && act(server, "accounts", "")) next(server, 62);
      }
      case 62 -> {
        if (!snapshotReady()) break;
        readEuroAccount(server);
        if (euroAccount != null && act(server, "fx", "")) next(server, 63);
      }
      case 63 -> {
        if (snapshotReady() && act(server, "exchange_preview", euroAccount.value() + " 10"))
          next(server, 64);
      }
      case 64 -> {
        if (!snapshotReady() || !menu().status().equals("Exchange preview")) break;
        if (act(server, "exchange_confirm", "")) {
          exchangeIntent = pendingBankIntent();
          next(server, 65);
        }
      }
      case 65 -> {
        if (!snapshotReady() || !menu().status().equals("Banking operation resolved")) break;
        if (funds == null) {
          readFunds(server);
          break;
        }
        require(
            funds.funds().availableMinorUnits() == 7100,
            "normal exchange debits exactly ten CAD after register receipt");
        if (euroFunds == null) {
          readEuroFunds(server);
          break;
        }
        require(
            euroFunds.availableMinorUnits() > 0, "normal exchange credits floored positive EUR");
        if (exchangeOutcome == null) {
          lookupExchange(server);
          break;
        }
        require(
            exchangeOutcome.status() == Journal.Status.COMMITTED
                && exchangeOutcome.operationId().equals(exchangeIntent.operationId())
                && exchangeOutcome.originalRequestId().equals(exchangeIntent.requestId()),
            "original canonical exchange outcome resolves");
        if (act(server, "bank_retry", "")) next(server, 66);
      }
      case 66 -> {
        if (!snapshotReady()) break;
        readFunds(server);
        if (funds == null) break;
        require(
            funds.funds().availableMinorUnits() == 7100,
            "resolved banking retry cannot post exchange again");
        if (!server.isDedicatedServer()
            && System.getProperty("economy.bankingWorld") != null
            && !Files.exists(
                server.getWorldPath(LevelResource.ROOT).resolve("banking-client-passed.txt")))
          break;
        unregister(server, seller);
        unregister(server, buyer);
        server.overworld().setChunkForced(TELLER.getX() >> 4, TELLER.getZ() >> 4, false);
        Files.writeString(
            server.getWorldPath(LevelResource.ROOT).resolve("banking-probe-passed.txt"),
            "PASS normal world setup, primary+EUR bank accounts, settings, accounts/history/FX, full-capacity rejection, withdrawal/deposit roundtrip, page-change/close claim/teller collection, stale session/revision, saved transient-input recovery, owner pricing and buyer register checkout; FX exchange original operation "
                + exchangeIntent.operationId().value()
                + " CAD7100 EUR"
                + euroFunds.availableMinorUnits()
                + "\n");
        System.out.println(
            "BANKING_ACCEPTANCE_PASS normal controllers and real menu/session gate; canonical exchange outcome; no network socket asserted");
        return true;
      }
      default -> throw new IllegalStateException("Unknown banking fixture stage");
    }
    return false;
  }

  private static String diagnostics() {
    var current = menu();
    return "stage="
        + stage
        + " funded="
        + funded
        + " page="
        + (current == null ? "closed" : current.page())
        + " status="
        + (current == null ? "" : current.status())
        + " busy="
        + (current != null && current.busy())
        + " valid="
        + (current != null && current.valid(player))
        + " ready="
        + ready()
        + " position="
        + player.position()
        + " health="
        + player.getHealth()
        + " cashPending="
        + player.getPersistentData().getCompound("economy_banking_cash_pending").getAllKeys()
        + " bankPending="
        + player.getPersistentData().getCompound("economy_banking_pending").getAllKeys();
  }

  private static BankingMenu menu() {
    return player != null && player.containerMenu instanceof BankingMenu value ? value : null;
  }

  private static ServerServices services() {
    return Economy.serverServices(player.server);
  }

  private static PlayerId actor() {
    return new PlayerId(player.getUUID());
  }

  private static boolean ready() {
    return menu() != null
        && menu().valid(player)
        && !menu().busy()
        && !player
            .getPersistentData()
            .getCompound("economy_banking_cash_pending")
            .hasUUID("operation")
        && !player.getPersistentData().getCompound("economy_banking_pending").hasUUID("operation");
  }

  private static boolean snapshotReady() {
    return ready() && menu().revision() > issuedRevision;
  }

  private static boolean deliveryComplete() {
    return ready() && menu().status().equals("Delivery complete");
  }

  private static void next(MinecraftServer server, int value) {
    stage = value;
    deadline = server.getTickCount() + 900;
    nextAction = server.getTickCount() + 3;
    context = null;
    funds = null;
    reading = false;
    System.out.println("BANKING_ACCEPTANCE_STAGE " + value);
  }

  private static boolean act(MinecraftServer server, String action, String value) {
    if (!ready() || server.getTickCount() < nextAction) return false;
    var current = menu();
    issuedRevision = current.revision();
    require(
        current.acceptAction(player, current.session(), issuedRevision, action),
        "normal menu action gate " + action);
    BankingController.action(player, current, action, value);
    nextAction = server.getTickCount() + 3;
    return true;
  }

  private static PartyAuthorization.Grant grant() {
    var owner = new PartyRef(services().worldId(), new PartyRef.Player(actor()));
    return PartyAuthorization.player(player, Economy::serverServices, owner).orElseThrow();
  }

  private static void readContext(MinecraftServer server) {
    if (reading || server.getTickCount() - lastRead < 4) return;
    reading = true;
    lastRead = server.getTickCount();
    int expected = stage;
    var observed = player;
    var anchor =
        menu() == null
            ? new TerminalPolicy.Anchor(
                player.serverLevel().dimension().location().toString(),
                TELLER.getX(),
                TELLER.getY(),
                TELLER.getZ())
            : menu().anchor();
    services()
        .ledger()
        .bankingContext(actor(), anchor, null)
        .whenComplete(
            (value, error) -> {
              if (stage != expected || player != observed) return;
              reading = false;
              if (error != null) asynchronousFailure = "Banking context read: " + error;
              else context = value;
            });
  }

  private static void readFunds(MinecraftServer server) {
    if (reading || server.getTickCount() - lastRead < 4) return;
    reading = true;
    lastRead = server.getTickCount();
    int expected = stage;
    var observed = player;
    services()
        .ledger()
        .primaryFunds(grant())
        .whenComplete(
            (value, error) -> {
              if (stage != expected || player != observed) return;
              reading = false;
              if (error != null || value.isEmpty())
                asynchronousFailure = "Primary funds unavailable: " + error;
              else funds = value.orElseThrow();
            });
  }

  private static void findClaim(MinecraftServer server) {
    if (reading || server.getTickCount() - lastRead < 4) return;
    reading = true;
    lastRead = server.getTickCount();
    int expected = stage;
    var observed = player;
    services()
        .ledger()
        .custodyClaims(actor(), null, 16)
        .whenComplete(
            (page, error) -> {
              if (stage != expected || player != observed) return;
              reading = false;
              if (error != null) asynchronousFailure = "Claim page read: " + error;
              else
                for (var value : page.cases())
                  if (value.terms().source().startsWith("menu-return:")) {
                    claim = value.id();
                    break;
                  }
            });
  }

  private static void readEuroAccount(MinecraftServer server) {
    if (reading || server.getTickCount() - lastRead < 4) return;
    reading = true;
    lastRead = server.getTickCount();
    int expected = stage;
    services()
        .listOwnedAccounts(grant(), null, 16)
        .whenComplete(
            (page, error) -> {
              if (stage != expected) return;
              reading = false;
              if (error != null || page.isEmpty())
                asynchronousFailure = "Secondary account page unavailable: " + error;
              else
                for (var account : page.orElseThrow().accounts())
                  if (account.currency() == EconomyCurrency.EUR) euroAccount = account.id();
            });
  }

  private static void readEuroFunds(MinecraftServer server) {
    if (reading || server.getTickCount() - lastRead < 4) return;
    reading = true;
    lastRead = server.getTickCount();
    int expected = stage;
    services()
        .accountFunds(grant(), euroAccount)
        .whenComplete(
            (value, error) -> {
              if (stage != expected) return;
              reading = false;
              if (error != null || value.isEmpty())
                asynchronousFailure = "EUR account funds unavailable: " + error;
              else euroFunds = value.orElseThrow();
            });
  }

  private static Journal.Intent pendingBankIntent() {
    var tag = player.getPersistentData().getCompound("economy_banking_pending");
    require(
        tag.hasUUID("operation") && tag.hasUUID("request"),
        "exchange preserves original intent before submission");
    var fields = new TreeMap<String, String>();
    var stored = tag.getCompound("fields");
    stored.getAllKeys().forEach(key -> fields.put(key, stored.getString(key)));
    return new Journal.Intent(
        new OperationId(tag.getUUID("operation")),
        new RequestId(tag.getUUID("request")),
        actor(),
        new Journal.Kind(tag.getString("service"), tag.getString("type")),
        new Journal.CanonicalRequest(tag.getInt("version"), fields),
        null,
        null);
  }

  private static void lookupExchange(MinecraftServer server) {
    if (reading || server.getTickCount() - lastRead < 4) return;
    reading = true;
    lastRead = server.getTickCount();
    int expected = stage;
    var owner = actor();
    services()
        .journal((who, kind) -> who.equals(owner), Map.of(), null)
        .lookup(exchangeIntent)
        .whenComplete(
            (value, error) -> {
              if (stage != expected) return;
              reading = false;
              if (error != null || value.isEmpty())
                asynchronousFailure = "Original exchange outcome unavailable: " + error;
              else exchangeOutcome = value.orElseThrow();
            });
  }

  private static void seed(MinecraftServer server, long minor) {
    funded = false;
    var owner = actor();
    var account = context.account();
    var adjustment =
        new SameCurrency.Adjustment(
            owner,
            new SameCurrency.Revision(account.id(), account.revision()),
            new Money(account.currency(), minor));
    var journal =
        services()
            .ledger()
            .adjustments(
                SameCurrency.CREDIT,
                (who, kind) -> who.equals(owner),
                (intent, instruction, reads) -> true,
                null);
    var intent =
        new Journal.Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            owner,
            SameCurrency.CREDIT,
            adjustment.request(),
            null,
            null);
    journal
        .submit(intent)
        .completion()
        .whenComplete(
            (delivery, error) -> {
              if (error != null || delivery.outcome().status() != Journal.Status.COMMITTED)
                asynchronousFailure = "Fixture seed did not commit: " + error;
              else funded = true;
            });
  }

  private static void stageCashInputs() {
    int input = 0;
    for (int index = 0; index < 36; index++) {
      var stack = player.getInventory().getItem(index);
      if (CashCatalog.find(stack).isEmpty()) continue;
      require(
          stack.has(CashStackIdentity.CASH_LOT.get()),
          "normal withdrawal carries an issuance marker");
      while (input < 24 && !menu().depositInputs().getItem(input).isEmpty()) input++;
      require(input < 24, "deposit fixture slot bound");
      player.getInventory().setItem(index, ItemStack.EMPTY);
      menu().depositInputs().setItem(input++, stack);
    }
    player.getInventory().setChanged();
  }

  private static long cashValue(ServerPlayer owner) {
    long value = 0;
    for (int index = 0; index < 36; index++) {
      var stack = owner.getInventory().getItem(index);
      var cash = CashCatalog.find(stack).orElse(null);
      if (cash != null) {
        require(
            stack.has(CashStackIdentity.CASH_LOT.get()),
            "all received cash has protected lot identity");
        value = Math.addExact(value, Math.multiplyExact(cash.minorUnits(), stack.getCount()));
      }
    }
    return value;
  }

  private static int count(ServerPlayer owner, Item item) {
    int count = 0;
    for (int index = 0; index < 36; index++)
      if (owner.getInventory().getItem(index).is(item))
        count += owner.getInventory().getItem(index).getCount();
    return count;
  }

  private static void fillInventory(ServerPlayer owner) {
    for (int index = 0; index < 36; index++)
      owner.getInventory().setItem(index, new ItemStack(Items.STONE, 64));
  }

  private static void clearInventory(ServerPlayer owner) {
    for (int index = 0; index < 36; index++) owner.getInventory().setItem(index, ItemStack.EMPTY);
  }

  private static void clearNonCash(ServerPlayer owner) {
    for (int index = 0; index < 36; index++)
      if (CashCatalog.find(owner.getInventory().getItem(index)).isEmpty())
        owner.getInventory().setItem(index, ItemStack.EMPTY);
  }

  private static boolean physicalReady(BlockPos position) {
    return player.serverLevel().getBlockEntity(position) instanceof TerminalBlockEntity entity
        && TerminalLifecycle.matches(player.serverLevel(), position, entity.generation());
  }

  private static void place(MinecraftServer server, String id, BlockPos anchor) {
    var level = server.overworld();
    level.getChunkAt(anchor);
    for (var position : BlockPos.betweenClosed(anchor.offset(-2, -1, -2), anchor.offset(2, -1, 2)))
      level.setBlockAndUpdate(position, Blocks.STONE.defaultBlockState());
    if (id.equals("register"))
      level.setBlockAndUpdate(anchor.below(), Blocks.CHEST.defaultBlockState());
    player.setYRot(Direction.SOUTH.toYRot());
    var previous = player.getMainHandItem().copy();
    var stack = new ItemStack(TerminalCatalog.get(id).item().get());
    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
    var hit =
        new BlockHitResult(
            Vec3.atCenterOf(anchor.below()).add(0, .5, 0), Direction.UP, anchor.below(), false);
    try {
      require(
          ((BlockItem) stack.getItem())
              .place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit))
              .consumesAction(),
          "normal " + id + " item placement");
    } finally {
      player.setItemInHand(InteractionHand.MAIN_HAND, previous);
    }
  }

  static ServerPlayer fixture(MinecraftServer server, UUID id, String name) throws Exception {
    var profile = new GameProfile(id, name);
    var level = server.overworld();
    var dummy = FakePlayerFactory.get(level, profile);
    var result = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
    result.connection = dummy.connection;
    var connection = result.connection.getConnection();
    Field channel = net.minecraft.network.Connection.class.getDeclaredField("channel");
    channel.setAccessible(true);
    if (channel.get(connection) == null)
      channel.set(connection, new io.netty.channel.embedded.EmbeddedChannel());
    var channels =
        net.neoforged.neoforge.network.registration.ChannelAttributes.getOrCreateAdHocChannels(
            connection);
    channels.add(BankingNetwork.Snapshot.TYPE.id());
    channels.add(net.neoforged.neoforge.network.payload.AdvancedOpenScreenPayload.TYPE.id());
    players(server).add(result);
    byId(server).put(id, result);
    return result;
  }

  static void unregister(MinecraftServer server, ServerPlayer value) throws Exception {
    if (value == null) return;
    if (value.containerMenu != value.inventoryMenu) value.closeContainer();
    players(server).remove(value);
    byId(server).remove(value.getUUID(), value);
  }

  @SuppressWarnings("unchecked")
  private static List<ServerPlayer> players(MinecraftServer server) throws Exception {
    Field field = PlayerList.class.getDeclaredField("players");
    field.setAccessible(true);
    return (List<ServerPlayer>) field.get(server.getPlayerList());
  }

  @SuppressWarnings("unchecked")
  private static Map<UUID, ServerPlayer> byId(MinecraftServer server) throws Exception {
    Field field = PlayerList.class.getDeclaredField("playersByUUID");
    field.setAccessible(true);
    return (Map<UUID, ServerPlayer>) field.get(server.getPlayerList());
  }

  private static void require(boolean value, String message) {
    if (!value) throw new IllegalStateException("Banking fixture: " + message);
  }
}
