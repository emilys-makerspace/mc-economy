package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.card.*;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
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

/** Explicit disposable-world acceptance of branch, card and account-cap entry points. */
public final class BranchReleaseProbe {
  private static final UUID OWNER = UUID.fromString("ebd90d7b-44e2-46d3-a178-60378858d133");
  private static final BlockPos KIOSK = new BlockPos(328, 110, 328);
  private static final BlockPos TELLER = new BlockPos(334, 110, 328);
  private static final String PREFIX = "economy:bank/";
  private static ServerPlayer player;
  private static int stage, brandReturn;
  private static long deadline, nextAction, issuedRevision;
  private static boolean reading, funded;
  private static String failure, brand;
  private static List<AccountIdentity> accounts;
  private static AccountIdentity primary, usd, chf;
  private static IssuedCards.Token originalToken, replacementToken;
  private static DebitView debit;
  private static Long balance;

  private record DebitView(IssuedCards.Token token, AccountFunds funds, boolean oldUsable) {}

  private BranchReleaseProbe() {}

  public static boolean tick(MinecraftServer server) throws Exception {
    if (failure != null) throw new IllegalStateException(failure);
    if (stage == 0) {
      if (Files.exists(
          server.getWorldPath(LevelResource.ROOT).resolve("branch-release-passed.txt")))
        return true;
      server.overworld().setChunkForced(KIOSK.getX() >> 4, KIOSK.getZ() >> 4, true);
      player = BankingAcceptanceProbe.fixture(server, OWNER, "BranchRelease");
      player.setInvulnerable(true);
      player.setPos(KIOSK.getX() + 2, KIOSK.getY(), KIOSK.getZ() - 2);
      next(server, 1);
      return false;
    }
    require(server.getTickCount() < deadline, "deadline " + diagnostics());
    if (server.getTickCount() % 100 == 0)
      System.out.println("BRANCH_RELEASE_WAIT " + diagnostics());
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
        beginBrand(server, "crownstone", 10);
      }
      case 10 -> {
        if (offered("branch_open", "") && act(server, "branch_open", "")) next(server, 11);
      }
      case 11 -> {
        if (!fresh() || !offeredAction("branch_assign")) break;
        readAccounts();
        if (accounts != null) {
          require(accounts.size() == 1, "one primary account");
          primary = byCurrency(EconomyCurrency.CAD);
          require(primary.primary(), "CAD is protected primary");
          fund(primary, 10_000);
          next(server, 12);
        }
      }
      case 12 -> {
        if (funded && ready() && act(server, "cards", "")) next(server, 13);
      }
      case 13 -> {
        if (offered("card_preview", id(primary)) && act(server, "card_preview", id(primary)))
          next(server, 14);
      }
      case 14 -> {
        if (!offered("card_confirm", "")) break;
        require(
            menu().lines().stream().anyMatch(line -> line.contains("free")),
            "first debit issuance free");
        if (act(server, "card_confirm", "")) next(server, 15);
      }
      case 15 -> {
        if (!fresh()) break;
        readDebit(null);
        if (debit != null) {
          originalToken = debit.token();
          require(debit.funds().postedMinorUnits() == 10_000, "first card has no debit");
          if (act(server, "claims", "")) next(server, 16);
        }
      }
      case 16 -> {
        if (firstAction(server, "collect")) next(server, 17);
      }
      case 17 -> {
        if (!fresh()) break;
        require(
            menu().status().equals("Delivery complete"),
            "first debit physical delivery " + diagnostics());
        require(tokenCopies(originalToken.id()) == 1, "one original physical token");
        if (act(server, "cards", "")) next(server, 18);
      }
      case 18 -> {
        if (offered("card_preview", id(primary)) && act(server, "card_preview", id(primary)))
          next(server, 19);
      }
      case 19 -> {
        if (offered("card_preview", id(primary) + "|" + id(primary))
            && act(server, "card_preview", id(primary) + "|" + id(primary))) next(server, 20);
      }
      case 20 -> {
        if (!offered("card_confirm", "")) break;
        require(
            menu().lines().stream().anyMatch(line -> line.contains("25.00 CAD")),
            "reviewed exact early replacement fee");
        if (act(server, "card_confirm", "")) next(server, 21);
      }
      case 21 -> {
        if (!fresh()) break;
        readDebit(originalToken.id());
        if (debit != null) {
          replacementToken = debit.token();
          require(
              !replacementToken.id().equals(originalToken.id()) && !debit.oldUsable(),
              "replacement revokes old token");
          require(debit.funds().postedMinorUnits() == 7_500, "one 25 CAD replacement fee");
          if (act(server, "claims", "")) next(server, 22);
        }
      }
      case 22 -> {
        if (firstAction(server, "collect")) next(server, 23);
      }
      case 23 -> {
        if (!fresh()) break;
        require(menu().status().equals("Delivery complete"), "replacement physical delivery");
        require(
            tokenCopies(originalToken.id()) == 1 && tokenCopies(replacementToken.id()) == 1,
            "copies share token authority without item deletion");
        beginBrand(server, "orchard_direct", 24);
      }
      case 24 -> {
        if (ready() && act(server, "cards", "")) next(server, 25);
      }
      case 25 -> {
        if (!fresh()) break;
        require(
            !offeredAction("card_preview"), "same-currency wrong-bank replacement is not offered");
        if (act(server, "card_preview", id(primary))) next(server, 26);
      }
      case 26 -> {
        if (!fresh()) break;
        require(
            !menu().page().equals("card_review"),
            "forged wrong-bank replacement has no confirmation");
        readDebit(originalToken.id());
        if (debit != null) {
          require(
              debit.token().id().equals(replacementToken.id())
                  && debit.funds().postedMinorUnits() == 7_500,
              "wrong bank leaves token and funds intact");
          beginBrand(server, "crosswind", 30);
        }
      }
      case 30 -> {
        if (offered("branch_open", "") && act(server, "branch_open", "")) next(server, 31);
      }
      case 31 -> {
        if (!fresh() || !offeredAction("branch_assign")) break;
        readAccounts();
        if (accounts != null) {
          require(accounts.size() == 2, "second account");
          usd = byCurrency(EconomyCurrency.USD);
          beginBrand(server, "greenhaven", 32);
        }
      }
      case 32 -> {
        if (offered("branch_open", "") && act(server, "branch_open", "")) next(server, 33);
      }
      case 33 -> {
        if (!fresh() || !offeredAction("branch_assign")) break;
        readAccounts();
        if (accounts != null) {
          require(accounts.size() == 3, "third account");
          byCurrency(EconomyCurrency.GBP);
          beginBrand(server, "lakeward_cantonal", 34);
        }
      }
      case 34 -> {
        if (offered("branch_open", "") && act(server, "branch_open", "")) next(server, 35);
      }
      case 35 -> {
        if (!fresh() || !offeredAction("branch_assign")) break;
        readAccounts();
        if (accounts != null) {
          require(accounts.size() == 4, "fourth account succeeds");
          chf = byCurrency(EconomyCurrency.CHF);
          beginBrand(server, "slate_harbor", 36);
        }
      }
      case 36 -> {
        if (offered("branch_open", "") && act(server, "branch_open", "")) next(server, 37);
      }
      case 37 -> {
        if (!fresh()) break;
        require(
            menu().status().contains("4 open accounts")
                || menu().lines().stream().anyMatch(line -> line.contains("4 open accounts")),
            "fifth account has explicit cap rejection " + diagnostics());
        readAccounts();
        if (accounts != null) {
          require(
              accounts.size() == 4
                  && accounts.stream().noneMatch(a -> a.currency() == EconomyCurrency.EUR),
              "fifth account never opened");
          beginBrand(server, "mesa_trail", 38);
        }
      }
      case 38 -> {
        if (offered("branch_close", id(usd)) && act(server, "branch_close", id(usd)))
          next(server, 39);
      }
      case 39 -> {
        if (!fresh()) break;
        require(
            !offeredAction("account_close_confirm"),
            "same-currency wrong bank cannot confirm closure");
        readAccounts();
        if (accounts != null) {
          require(
              accounts.size() == 4 && byCurrency(EconomyCurrency.USD).id().equals(usd.id()),
              "wrong bank leaves USD account open");
          beginBrand(server, "crosswind", 40);
        }
      }
      case 40 -> {
        if (offered("branch_close", id(usd)) && act(server, "branch_close", id(usd)))
          next(server, 41);
      }
      case 41 -> {
        if (offered("account_close_confirm", "") && act(server, "account_close_confirm", ""))
          next(server, 42);
      }
      case 42 -> {
        if (!fresh()) break;
        readAccounts();
        if (accounts != null) {
          require(
              accounts.size() == 3 && accounts.stream().noneMatch(a -> a.id().equals(usd.id())),
              "correct bank closes account and frees slot");
          fund(chf, 200);
          next(server, 43);
        }
      }
      case 43 -> {
        if (!funded || !ready()) break;
        player.closeContainer();
        player.setPos(TELLER.getX() + 2, TELLER.getY(), TELLER.getZ() - 2);
        place(server, "teller", TELLER);
        brand = "lakeward_cantonal";
        next(server, 110);
      }
      case 110 -> {
        if (physical(TELLER) && BankingController.openAdmin(player, TELLER)) next(server, 111);
      }
      case 111 -> {
        if (offered("brand_preview", "") && act(server, "brand_preview", "")) next(server, 112);
      }
      case 112 -> {
        if (offered("brand_preview", PREFIX + brand)
            && act(server, "brand_preview", PREFIX + brand)) next(server, 113);
      }
      case 113 -> {
        if (offered("brand_confirm", PREFIX + brand)
            && act(server, "brand_confirm", PREFIX + brand)) next(server, 114);
      }
      case 114 -> {
        if (fresh() && offeredAction("configure") && act(server, "configure", "BOTH CHF"))
          next(server, 115);
      }
      case 115 -> {
        if (!fresh() || menu().lines().stream().noneMatch(line -> line.contains("Cash: BOTH")))
          break;
        player.closeContainer();
        BankingController.open(player, TELLER);
        next(server, 44);
      }
      case 44 -> {
        if (ready() && act(server, "accounts", "")) next(server, 45);
      }
      case 45 -> {
        if (!ready()) break;
        var choice =
            menu().choices().stream()
                .filter(
                    c -> c.action().equals("account_select") && c.value().startsWith(id(chf) + "|"))
                .findFirst()
                .orElse(null);
        if (choice != null && act(server, choice.action(), choice.value())) next(server, 46);
      }
      case 46 -> {
        if (fresh() && act(server, "withdraw", "")) next(server, 47);
      }
      case 47 -> {
        if (ready() && act(server, "withdraw_preview", "1.02")) next(server, 48);
      }
      case 48 -> {
        if (!fresh()) break;
        require(!menu().status().equals("Withdrawal preview"), "CHF 1.02 exact change is rejected");
        readBalance(chf);
        if (balance != null) {
          require(balance == 200, "CHF exact-change failure keeps full account funds");
          require(
              !player
                  .getPersistentData()
                  .getCompound("economy_banking_cash_pending")
                  .hasUUID("operation"),
              "no pending cash mutation from failed preview");
          player.closeContainer();
          BankingAcceptanceProbe.unregister(server, player);
          server.overworld().setChunkForced(KIOSK.getX() >> 4, KIOSK.getZ() >> 4, false);
          Files.writeString(
              server.getWorldPath(LevelResource.ROOT).resolve("branch-release-passed.txt"),
              "PASS native kiosk primary onboarding, debit issuance and protected claim, 25 CAD reviewed early replacement with old-token revocation, same-currency wrong-bank replacement denial, four open accounts including CHF, fifth-account rejection, wrong-bank USD closure denial and correct-bank closure, CHF teller 1.02 exact-change failure with no debit. Fixture funding uses audited 100 CAD and 2 CHF adjustments.\n");
          System.out.println(
              "BRANCH_RELEASE_PASS lifecycle, same-bank authority, four-account cap and CHF exact change");
          return true;
        }
      }
      case 100 -> {
        if (physical(KIOSK) && BankingController.openAdmin(player, KIOSK)) next(server, 101);
      }
      case 101 -> {
        if (offered("brand_preview", "") && act(server, "brand_preview", "")) next(server, 102);
      }
      case 102 -> {
        if (offered("brand_preview", PREFIX + brand)
            && act(server, "brand_preview", PREFIX + brand)) next(server, 103);
      }
      case 103 -> {
        if (offered("brand_confirm", PREFIX + brand)
            && act(server, "brand_confirm", PREFIX + brand)) next(server, 104);
      }
      case 104 -> {
        String name =
            InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(PREFIX + brand)).name();
        if (!fresh() || menu().lines().stream().noneMatch(line -> line.startsWith(name))) break;
        player.closeContainer();
        BankingController.open(player, KIOSK);
        next(server, brandReturn);
      }
      default -> throw new IllegalStateException("Unknown branch release stage " + stage);
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
    accounts = null;
    debit = null;
    balance = null;
    reading = false;
    System.out.println("BRANCH_RELEASE_STAGE " + stage);
  }

  private static void beginBrand(MinecraftServer server, String bank, int afterwards) {
    player.closeContainer();
    player.setPos(KIOSK.getX() + 2, KIOSK.getY(), KIOSK.getZ() - 2);
    brand = bank;
    brandReturn = afterwards;
    next(server, 100);
  }

  private static PartyAuthorization.Grant grant() {
    var owner =
        new PartyRef(services().worldId(), new PartyRef.Player(new PlayerId(player.getUUID())));
    return PartyAuthorization.player(player, Economy::serverServices, owner).orElseThrow();
  }

  private static void readAccounts() {
    if (reading || accounts != null) return;
    reading = true;
    int expected = stage;
    services()
        .listOwnedAccounts(grant(), null, 8)
        .whenComplete(
            (found, error) -> {
              if (stage != expected) return;
              reading = false;
              if (error != null || found.isEmpty()) failure = "Branch account read " + error;
              else
                accounts =
                    found.orElseThrow().accounts().stream()
                        .filter(a -> a.status() == AccountIdentity.Status.OPEN)
                        .toList();
            });
  }

  private static AccountIdentity byCurrency(EconomyCurrency currency) {
    return accounts.stream().filter(a -> a.currency() == currency).findFirst().orElseThrow();
  }

  private static String id(AccountIdentity account) {
    return account.id().value().toString();
  }

  private static void readDebit(UUID old) {
    if (reading || debit != null) return;
    reading = true;
    int expected = stage;
    var grant = grant();
    var token = services().ledger().accountCard(grant, primary.id());
    var funds = services().ledger().funds(grant, primary.id());
    var oldCard =
        old == null
            ? java.util.concurrent.CompletableFuture.completedStage(
                Optional.<IssuedCards.Token>empty())
            : services()
                .ledger()
                .usableCard(new PlayerId(player.getUUID()), services().worldId(), old);
    token
        .thenCombine(funds, (t, f) -> new DebitView(t.orElseThrow(), f.orElseThrow(), false))
        .thenCombine(
            oldCard,
            (view, previous) -> new DebitView(view.token(), view.funds(), previous.isPresent()))
        .whenComplete(
            (found, error) -> {
              if (stage != expected) return;
              reading = false;
              if (error != null) failure = "Branch debit read " + error;
              else debit = found;
            });
  }

  private static void readBalance(AccountIdentity account) {
    if (reading || balance != null) return;
    reading = true;
    int expected = stage;
    services()
        .ledger()
        .funds(grant(), account.id())
        .whenComplete(
            (found, error) -> {
              if (stage != expected) return;
              reading = false;
              if (error != null || found.isEmpty())
                failure = "Branch funding balance read " + error;
              else balance = found.orElseThrow().postedMinorUnits();
            });
  }

  private static void fund(AccountIdentity account, long amount) {
    funded = false;
    var actor = new PlayerId(player.getUUID());
    var adjustment =
        new SameCurrency.Adjustment(
            actor,
            new SameCurrency.Revision(account.id(), account.revision()),
            new Money(account.currency(), amount));
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
                    "Branch audited funding "
                        + error
                        + " "
                        + (delivery == null ? "" : delivery.outcome());
              else funded = true;
            });
  }

  private static int tokenCopies(UUID token) {
    int count = 0;
    for (int slot = 0; slot < 36; slot++) {
      var stack = player.getInventory().getItem(slot);
      if (CardStackIdentity.mark(stack, services().worldId())
          .filter(mark -> mark.token().equals(token))
          .isPresent()) count += stack.getCount();
    }
    return count;
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
    if (!condition) throw new IllegalStateException("Branch release " + message);
  }
}
