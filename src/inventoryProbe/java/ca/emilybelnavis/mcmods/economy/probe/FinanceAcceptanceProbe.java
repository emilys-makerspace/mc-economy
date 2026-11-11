package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.card.CreditCardDesigns;
import ca.emilybelnavis.mcmods.economy.cash.*;
import ca.emilybelnavis.mcmods.economy.credit.CreditFacilities;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.loan.Loans;
import ca.emilybelnavis.mcmods.economy.terminal.*;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;

/**
 * Opt-in disposable native controller/menu fixture. No finance is seeded except one audited funding
 * adjustment.
 */
public final class FinanceAcceptanceProbe {
  private static final UUID OWNER = UUID.fromString("d8c26244-5042-4ff4-a3fb-915e0d06a821");
  private static final BlockPos KIOSK = new BlockPos(264, 110, 264);
  private static final BlockPos ATM = new BlockPos(270, 110, 264);
  private static final BlockPos REGISTER = new BlockPos(264, 110, 270);
  private static final String BANK = "economy:bank/crownstone";
  private static ServerPlayer player, customer, seller;
  private static long purchasePreviewRevision;
  private static int stage;
  private static long deadline, nextAction, issuedRevision;
  private static boolean reading, funded;
  private static String failure;
  private static AccountIdentity account;
  private static List<CreditFacilities.Facility> cards;
  private static List<Loans.Contract> loans;
  private static BankingMenu brokenMenu;

  private FinanceAcceptanceProbe() {}

  public static boolean tick(MinecraftServer server) throws Exception {
    if (failure != null) throw new IllegalStateException(failure);
    if (stage == 0) {
      if (Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("finance-probe-passed.txt")))
        return true;
      if (Economy.serverServices(server).state() != ServerServices.State.READY) return false;
      server.overworld().setChunkForced(KIOSK.getX() >> 4, KIOSK.getZ() >> 4, true);
      player = BankingAcceptanceProbe.fixture(server, OWNER, "FinanceCustomer");
      player.setInvulnerable(true);
      player.setPos(KIOSK.getX() + 2, KIOSK.getY(), KIOSK.getZ() - 2);
      next(server, 1);
      return false;
    }
    require(server.getTickCount() < deadline, "deadline " + diagnostics());
    if (server.getTickCount() % 100 == 0)
      System.out.println("FINANCE_ACCEPTANCE_WAIT " + diagnostics());
    switch (stage) {
      case 1 -> {
        if (menu() != null && menu().worldSetup()) {
          if (act(server, "setup", "CAD")) next(server, 2);
        } else if (services().metrics().economy().ready()) next(server, 2);
      }
      case 2 -> {
        if (!services().metrics().economy().ready()) break;
        player.closeContainer();
        place(server, "branch_kiosk", KIOSK);
        next(server, 3);
      }
      case 3 -> {
        if (physical(KIOSK) && BankingController.openAdmin(player, KIOSK)) next(server, 4);
      }
      case 4 -> {
        if (offered("brand_preview", "") && act(server, "brand_preview", "")) next(server, 5);
      }
      case 5 -> {
        if (offered("brand_preview", BANK) && act(server, "brand_preview", BANK)) next(server, 6);
      }
      case 6 -> {
        if (offered("brand_confirm", BANK) && act(server, "brand_confirm", BANK)) next(server, 7);
      }
      case 7 -> {
        if (fresh() && boundSettings("DISABLED")) {
          player.closeContainer();
          BankingController.open(player, KIOSK);
          next(server, 8);
        }
      }
      case 8 -> {
        if (offered("branch_open", "") && act(server, "branch_open", "")) next(server, 9);
      }
      case 9 -> {
        if (!fresh() || !offeredAction("branch_assign")) break;
        readAccount();
        if (account != null) {
          seed();
          next(server, 10);
        }
      }
      case 10 -> {
        if (funded && ready() && act(server, "finance_credit_apply", "")) next(server, 11);
      }
      case 11 -> {
        if (firstAction(server, "finance_credit_network")) next(server, 12);
      }
      case 12 -> {
        if (offered("finance_credit_preview", "") && act(server, "finance_credit_preview", "2500"))
          next(server, 13);
      }
      case 13 -> {
        if (offered("finance_credit_confirm", "") && act(server, "finance_credit_confirm", ""))
          next(server, 14);
      }
      case 14 -> {
        if (!fresh()) break;
        requireDone();
        readCards();
        if (cards != null) {
          require(
              cards.size() == 1
                  && cards.getFirst().limitMinor() == 250_000
                  && cards.getFirst().owedMinor() == 0,
              "normal starter facility");
          if (act(server, "claims", "")) next(server, 15);
        }
      }
      case 15 -> {
        if (firstAction(server, "collect")) next(server, 16);
      }
      case 16 -> {
        if (!fresh()) break;
        require(
            menu().status().equals("Delivery complete"),
            "protected credit token delivered " + menu().lines());
        holdCreditCard();
        player.closeContainer();
        player.setPos(ATM.getX() + 2, ATM.getY(), ATM.getZ() - 2);
        place(server, "atm", ATM);
        next(server, 17);
      }
      case 17 -> {
        if (physical(ATM) && BankingController.openAdmin(player, ATM)) next(server, 18);
      }
      case 18 -> {
        if (offered("brand_preview", "") && act(server, "brand_preview", "")) next(server, 19);
      }
      case 19 -> {
        if (offered("brand_preview", BANK) && act(server, "brand_preview", BANK)) next(server, 20);
      }
      case 20 -> {
        if (offered("brand_confirm", BANK) && act(server, "brand_confirm", BANK)) next(server, 21);
      }
      case 21 -> {
        if (fresh() && boundSettings("DISABLED")) next(server, 22);
      }
      case 22 -> {
        if (ready() && offeredAction("configure") && act(server, "configure", "BOTH CAD"))
          next(server, 23);
      }
      case 23 -> {
        if (fresh() && boundSettings("BOTH")) {
          player.closeContainer();
          BankingController.open(player, ATM);
          next(server, 24);
        }
      }
      case 24 -> {
        if (ready() && act(server, "accounts", "")) next(server, 25);
      }
      case 25 -> {
        if (ready()
            && offeredAction("account_select")
            && act(server, "account_select", account.id().value().toString())) next(server, 26);
      }
      case 26 -> {
        if (fresh() && act(server, "credit_advance", "")) next(server, 27);
      }
      case 27 -> {
        if (offered("credit_adv_preview", "") && act(server, "credit_adv_preview", "10.00"))
          next(server, 28);
      }
      case 28 -> {
        if (offered("credit_adv_confirm", "") && act(server, "credit_adv_confirm", ""))
          next(server, 29);
      }
      case 29 -> {
        if (!fresh()) break;
        requireDone();
        readCards();
        if (cards != null) {
          require(
              cards.getFirst().advance().principalMinor() == 1000
                  && cards.getFirst().owedMinor() == 1000,
              "advance creates exact native debt");
          if (act(server, "withdraw", "")) next(server, 30);
        }
      }
      case 30 -> {
        if (ready() && act(server, "withdraw_preview", "10.00")) next(server, 31);
      }
      case 31 -> {
        if (fresh() && act(server, "withdraw_confirm", "")) next(server, 32);
      }
      case 32 -> {
        if (!fresh()) break;
        require(
            menu().status().equals("Delivery complete"),
            "advance cash withdrawal delivered " + menu().lines());
        require(cashValue() == 1000, "protected physical advance cash");
        if (act(server, "finance_credit_pay", "")) next(server, 33);
      }
      case 33 -> {
        if (firstAction(server, "finance_credit_pay")) next(server, 34);
      }
      case 34 -> {
        if (firstAction(server, "finance_credit_fund")) next(server, 35);
      }
      case 35 -> {
        if (offered("finance_credit_amount", "") && act(server, "finance_credit_amount", "10.00"))
          next(server, 36);
      }
      case 36 -> {
        if (offered("finance_credit_pay_ok", "") && act(server, "finance_credit_pay_ok", ""))
          next(server, 37);
      }
      case 37 -> {
        if (!fresh()) break;
        requireDone();
        readCards();
        if (cards != null) {
          require(cards.getFirst().owedMinor() == 0, "native credit repayment settles advance");
          player.closeContainer();
          player.setPos(KIOSK.getX() + 2, KIOSK.getY(), KIOSK.getZ() - 2);
          BankingController.open(player, KIOSK);
          next(server, 50);
        }
      }
      case 50 -> {
        if (!ready()) break;
        customer = player;
        customer.closeContainer();
        seller =
            BankingAcceptanceProbe.fixture(
                server, UUID.fromString("11114841-66c8-4cc3-8ad0-f20c75c24c93"), "FinanceMerchant");
        player = seller;
        player.setInvulnerable(true);
        player.setPos(KIOSK.getX() + 2, KIOSK.getY(), KIOSK.getZ() - 2);
        BankingController.open(player, KIOSK);
        next(server, 51);
      }
      case 51 -> {
        if (offered("branch_open", "") && act(server, "branch_open", "")) next(server, 52);
      }
      case 52 -> {
        if (!fresh() || !offeredAction("branch_assign")) break;
        player.closeContainer();
        player.setPos(REGISTER.getX() + 2, REGISTER.getY(), REGISTER.getZ() - 2);
        place(server, "register", REGISTER);
        ((net.minecraft.world.level.block.entity.ChestBlockEntity)
                server.overworld().getBlockEntity(REGISTER.below()))
            .setItem(0, new ItemStack(Items.DIAMOND, 4));
        next(server, 53);
      }
      case 53 -> {
        if (!physical(REGISTER)) break;
        player.getInventory().selected = 8;
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND, 4));
        BankingController.open(player, REGISTER);
        next(server, 54);
      }
      case 54 -> {
        if (ready() && act(server, "price", "1")) next(server, 55);
      }
      case 55 -> {
        if (!fresh()) break;
        require(
            (menu().status().equals("SALE_CONFIGURE") || menu().status().equals("Sale configure.")),
            "merchant register price " + menu().lines());
        seller.closeContainer();
        player = customer;
        player.setPos(REGISTER.getX() + 2, REGISTER.getY(), REGISTER.getZ() - 2);
        holdCreditCard();
        BankingController.open(player, REGISTER);
        next(server, 56);
      }
      case 56 -> {
        if (ready() && act(server, "buy_preview", "")) next(server, 57);
      }
      case 57 -> {
        if (!fresh()) break;
        require(
            menu().status().equals("Review credit purchase, then Confirm"),
            "held credit path in register preview " + menu().status() + " " + menu().lines());
        purchasePreviewRevision = menu().revision();
        if (act(server, "buy_confirm", "")) next(server, 58);
      }
      case 58 -> {
        if (!fresh()) break;
        require(
            menu().status().equals("Delivery complete"),
            "credit purchase protected goods delivery " + menu().lines());
        require(
            count(Items.DIAMOND) == 4, "credit purchase delivers exactly original four diamonds");
        require(
            ((net.minecraft.world.level.block.entity.ChestBlockEntity)
                    server.overworld().getBlockEntity(REGISTER.below()))
                .getItem(0)
                .isEmpty(),
            "credit purchase consumes original source stock");
        require(
            !menu().acceptAction(player, menu().session(), purchasePreviewRevision, "buy_confirm"),
            "duplicate old confirmation is rejected");
        readCards();
        if (cards != null) {
          require(
              cards.getFirst().purchase().principalMinor() == 100
                  && cards.getFirst().owedMinor() == 100,
              "one purchase charge despite duplicate confirmation");
          player.closeContainer();
          player.setPos(KIOSK.getX() + 2, KIOSK.getY(), KIOSK.getZ() - 2);
          BankingController.open(player, KIOSK);
          next(server, 38);
        }
      }
      case 38 -> {
        if (ready() && act(server, "finance_loan_apply", "")) next(server, 39);
      }
      case 39 -> {
        if (offered("finance_loan_preview", "") && act(server, "finance_loan_preview", "1000 1"))
          next(server, 40);
      }
      case 40 -> {
        if (offered("finance_loan_confirm", "") && act(server, "finance_loan_confirm", ""))
          next(server, 41);
      }
      case 41 -> {
        if (!fresh()) break;
        requireDone();
        readLoans();
        if (loans != null) {
          require(
              loans.size() == 1 && loans.getFirst().originalPrincipalMinor() == 100_000,
              "normal native bank loan origination");
          if (act(server, "finance_loan_home", "")) next(server, 42);
        }
      }
      case 42 -> {
        if (firstAction(server, "finance_loan_view")) next(server, 43);
      }
      case 43 -> {
        if (offered("finance_loan_payoff", "") && act(server, "finance_loan_payoff", ""))
          next(server, 44);
      }
      case 44 -> {
        if (offered("finance_loan_pay_confirm", "") && act(server, "finance_loan_pay_confirm", ""))
          next(server, 45);
      }
      case 45 -> {
        if (!fresh()) break;
        requireDone();
        readLoans();
        if (loans != null) {
          require(
              loans.getFirst().status() == Loans.Status.SETTLED
                  && loans.getFirst().owedMinor() == 0,
              "native full payoff settles loan");
          if (act(server, "finance_credit_apply", "")) next(server, 46);
        }
      }
      case 46 -> {
        if (firstAction(server, "finance_credit_network")) next(server, 47);
      }
      case 47 -> {
        if (offered("finance_credit_preview", "") && act(server, "finance_credit_preview", "1000"))
          next(server, 48);
      }
      case 48 -> {
        if (!offered("finance_credit_confirm", "")) break;
        brokenMenu = menu();
        server.overworld().destroyBlock(KIOSK, false);
        require(!brokenMenu.valid(player), "broken kiosk invalidates the financial menu");
        require(
            !brokenMenu.acceptAction(
                player, brokenMenu.session(), brokenMenu.revision(), "finance_credit_confirm"),
            "normal action gate denies broken kiosk confirmation");
        BankingController.action(player, brokenMenu, "finance_credit_confirm", "");
        player.closeContainer();
        next(server, 49);
      }
      case 49 -> {
        readCards();
        if (cards == null) break;
        require(cards.size() == 1, "breaking preview kiosk cannot originate a second facility");
        BankingAcceptanceProbe.unregister(server, player);
        BankingAcceptanceProbe.unregister(server, seller);
        server.overworld().setChunkForced(KIOSK.getX() >> 4, KIOSK.getZ() >> 4, false);
        Files.writeString(
            server.getWorldPath(LevelResource.ROOT).resolve("finance-probe-passed.txt"),
            "PASS normal kiosk branding, primary account/provider, audited fixture funding, starter credit application, protected token claim, native ATM advance and protected cash withdrawal, owner credit repayment, separate seller onboarding and held-credit register purchase with protected original goods and duplicate confirmation denial, native bank loan and full payoff, broken kiosk after preview denial. Socket transport, GUI pixels, FX loan paths and accelerated delinquency are outside this fixture.\n");
        System.out.println(
            "FINANCE_ACCEPTANCE_PASS native menus, protected credit token/cash, loans and stale physical admission");
        return true;
      }
      default -> throw new IllegalStateException("Unknown finance fixture stage " + stage);
    }
    return false;
  }

  private static ServerServices services() {
    return Economy.serverServices(player.server);
  }

  private static BankingMenu menu() {
    return player != null && player.containerMenu instanceof BankingMenu found ? found : null;
  }

  private static boolean ready() {
    return menu() != null
        && menu().valid(player)
        && !menu().busy()
        && !player.getPersistentData().getCompound("economy_banking_pending").hasUUID("operation")
        && !player
            .getPersistentData()
            .getCompound("economy_banking_cash_pending")
            .hasUUID("operation");
  }

  private static boolean fresh() {
    return ready() && menu().revision() > issuedRevision;
  }

  private static boolean offeredAction(String action) {
    return ready() && menu().choices().stream().anyMatch(c -> c.action().equals(action));
  }

  private static boolean offered(String action, String value) {
    return ready()
        && menu().choices().stream()
            .anyMatch(c -> c.action().equals(action) && c.value().equals(value));
  }

  private static boolean act(MinecraftServer server, String action, String value) {
    if (!ready() || server.getTickCount() < nextAction) return false;
    var current = menu();
    issuedRevision = current.revision();
    require(
        current.acceptAction(player, current.session(), current.revision(), action),
        "normal action gate " + action);
    BankingController.action(player, current, action, value);
    nextAction = server.getTickCount() + 3;
    return true;
  }

  private static boolean firstAction(MinecraftServer server, String action) {
    if (!ready()) return false;
    var choice = menu().choices().stream().filter(c -> c.action().equals(action)).findFirst();
    return choice.isPresent() && act(server, action, choice.orElseThrow().value());
  }

  private static void next(MinecraftServer server, int value) {
    stage = value;
    deadline = server.getTickCount() + 600;
    nextAction = server.getTickCount() + 3;
    cards = null;
    loans = null;
    reading = false;
    System.out.println("FINANCE_ACCEPTANCE_STAGE " + stage);
  }

  private static PartyAuthorization.Grant grant() {
    var owner =
        new PartyRef(services().worldId(), new PartyRef.Player(new PlayerId(player.getUUID())));
    return PartyAuthorization.player(player, Economy::serverServices, owner).orElseThrow();
  }

  private static void readAccount() {
    if (reading) return;
    reading = true;
    services()
        .listOwnedAccounts(grant(), null, 4)
        .whenComplete(
            (found, error) -> {
              reading = false;
              if (error != null || found.isEmpty() || found.orElseThrow().accounts().isEmpty())
                failure = "Finance fixture primary account read " + error;
              else account = found.orElseThrow().accounts().getFirst();
            });
  }

  private static void readCards() {
    if (reading || cards != null) return;
    reading = true;
    int expected = stage;
    services()
        .ledger()
        .creditFacilities(grant(), null, 16)
        .whenComplete(
            (found, error) -> {
              if (stage != expected) return;
              reading = false;
              if (error != null || found.isEmpty())
                failure = "Finance fixture credit read " + error;
              else cards = found.orElseThrow();
            });
  }

  private static void readLoans() {
    if (reading || loans != null) return;
    reading = true;
    int expected = stage;
    services()
        .ledger()
        .loans(grant(), null, 16)
        .whenComplete(
            (found, error) -> {
              if (stage != expected) return;
              reading = false;
              if (error != null || found.isEmpty()) failure = "Finance fixture loan read " + error;
              else loans = found.orElseThrow();
            });
  }

  private static void seed() {
    var actor = new PlayerId(player.getUUID());
    var adjustment =
        new SameCurrency.Adjustment(
            actor,
            new SameCurrency.Revision(account.id(), account.revision()),
            new Money(account.currency(), 10_000));
    var journal =
        services()
            .ledger()
            .adjustments(
                SameCurrency.CREDIT,
                (who, kind) -> who.equals(actor),
                (intent, instruction, reads) -> true,
                null);
    var intent =
        new Journal.Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            actor,
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
                failure =
                    "Finance audited funding adjustment "
                        + error
                        + " "
                        + (delivery == null ? "" : delivery.outcome());
              else funded = true;
            });
  }

  private static boolean boundSettings(String mode) {
    return menu().status().equals("Owner/operator configuration")
        && menu().lines().stream().anyMatch(line -> line.startsWith("Crownstone Bank"))
        && menu().lines().stream()
            .anyMatch(
                line -> line.contains("Migration: BOUND") || line.contains("Migration: ENABLED"))
        && menu().lines().stream().anyMatch(line -> line.contains("Cash: " + mode));
  }

  private static void requireDone() {
    require(
        menu().lines().stream()
            .anyMatch(
                s -> s.equals("Done.") || s.contains("account is ready") || s.contains("saved")),
        "committed financial result " + menu().status() + " " + menu().lines());
  }

  private static void holdCreditCard() {
    for (int slot = 0; slot < 36; slot++) {
      var stack = player.getInventory().getItem(slot);
      if (CreditCardDesigns.byId(
              net.minecraft.core.registries.BuiltInRegistries.ITEM
                  .getKey(stack.getItem())
                  .toString())
          .isEmpty()) continue;
      var previous = player.getMainHandItem();
      player.getInventory().setItem(slot, previous);
      player.setItemInHand(InteractionHand.MAIN_HAND, stack);
      return;
    }
    throw new IllegalStateException("No protected credit token was delivered");
  }

  private static int count(Item item) {
    int count = 0;
    for (int slot = 0; slot < 36; slot++)
      if (player.getInventory().getItem(slot).is(item))
        count += player.getInventory().getItem(slot).getCount();
    return count;
  }

  private static long cashValue() {
    long value = 0;
    for (int slot = 0; slot < 36; slot++) {
      var stack = player.getInventory().getItem(slot);
      var cash = CashCatalog.find(stack).orElse(null);
      if (cash == null) continue;
      require(stack.has(CashStackIdentity.CASH_LOT.get()), "cash has an issuance identity");
      value = Math.addExact(value, Math.multiplyExact(cash.minorUnits(), stack.getCount()));
    }
    return value;
  }

  private static boolean physical(BlockPos anchor) {
    return player.serverLevel().getBlockEntity(anchor) instanceof TerminalBlockEntity entity
        && TerminalLifecycle.matches(player.serverLevel(), anchor, entity.generation());
  }

  private static void place(MinecraftServer server, String id, BlockPos anchor) {
    var level = server.overworld();
    level.getChunkAt(anchor);
    for (var pos : BlockPos.betweenClosed(anchor.offset(-2, -1, -2), anchor.offset(2, -1, 2)))
      level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
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
          "normal terminal placement " + id);
    } finally {
      player.setItemInHand(InteractionHand.MAIN_HAND, previous);
    }
  }

  private static String diagnostics() {
    return "stage="
        + stage
        + " page="
        + (menu() == null ? "closed" : menu().page())
        + " busy="
        + (menu() != null && menu().busy())
        + " status="
        + (menu() == null ? "" : menu().status())
        + " lines="
        + (menu() == null ? "" : menu().lines());
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException("Finance acceptance " + message);
  }
}
