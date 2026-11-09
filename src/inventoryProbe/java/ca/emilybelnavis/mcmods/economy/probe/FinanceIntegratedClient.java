package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.client.BankingScreen;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.terminal.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Opt-in real integrated-client payload and rendered finance-screen acceptance. */
@Mod(value = "economy_inventory_probe", dist = Dist.CLIENT)
public final class FinanceIntegratedClient {
  private static final BlockPos KIOSK = new BlockPos(520, 110, 520);
  private static final String BANK = "economy:bank/crownstone";
  private boolean worldOpened, switched, verificationRequested, completed;
  private volatile boolean setupRequested, adminRequested;
  private int ticks, step, stepStarted;
  private volatile int screenshots;
  private final ArrayDeque<String> pendingCaptures = new ArrayDeque<>();
  private long sentRevision = -1;
  private String networkLabel;
  private volatile boolean prepared, verified;
  private volatile Path worldRoot;
  private volatile String failure;

  public FinanceIntegratedClient() {
    NeoForge.EVENT_BUS.addListener(this::tick);
    NeoForge.EVENT_BUS.addListener(this::captureFrame);
  }

  private void tick(ClientTickEvent.Post event) {
    var name = System.getProperty("economy.financeWorld");
    if (name == null || completed) return;
    var minecraft = Minecraft.getInstance();
    try {
      if (!worldOpened
          && minecraft.getOverlay() == null
          && minecraft.screen != null
          && minecraft.level == null) {
        worldOpened = true;
        minecraft.options.guiScale().set(2);
        minecraft.resizeDisplay();
        minecraft
            .createWorldOpenFlows()
            .openWorld(name, () -> failure = "Disposable finance world opening was cancelled");
        return;
      }
      if (failure != null) {
        finish(minecraft, false);
        return;
      }
      if (minecraft.player == null || minecraft.getSingleplayerServer() == null) return;
      ticks++;
      if (ticks - stepStarted > 1200)
        throw new IllegalStateException("Client step deadline " + diagnostic(minecraft));
      var server = minecraft.getSingleplayerServer();
      if (minecraft.player.containerMenu instanceof BankingMenu setup
          && setup.worldSetup()
          && !setup.busy()) {
        if (ticks % 20 == 0) BankingNetwork.action(setup, "setup", "CAD");
        return;
      }
      if (!setupRequested && ticks > 30) {
        setupRequested = true;
        var uuid = minecraft.player.getUUID();
        server.execute(() -> prepare(server, uuid));
      }
      if (prepared && !adminRequested && ticks % 20 == 0) {
        var uuid = minecraft.player.getUUID();
        server.execute(
            () -> {
              var player = server.getPlayerList().getPlayer(uuid);
              if (player != null && BankingController.openAdmin(player, KIOSK))
                adminRequested = true;
            });
      }
      if (ticks % 100 == 0) System.out.println("FINANCE_CLIENT_WAIT " + diagnostic(minecraft));
      if (!(minecraft.screen instanceof BankingScreen screen)
          || !(minecraft.player.containerMenu instanceof BankingMenu menu)
          || menu.worldSetup()
          || menu.busy()
          || ticks % 10 != 0) return;
      switch (step) {
        case 0 -> {
          if (menu.access() == BankingMenu.Access.ADMIN && offered(menu, "brand_preview", "")) {
            send(menu, "brand_preview", "");
            next(1);
          }
        }
        case 1 -> {
          if (fresh(menu) && offered(menu, "brand_preview", BANK)) {
            send(menu, "brand_preview", BANK);
            next(2);
          }
        }
        case 2 -> {
          if (fresh(menu) && offered(menu, "brand_confirm", BANK)) {
            send(menu, "brand_confirm", BANK);
            next(3);
          }
        }
        case 3 -> {
          if (fresh(menu)
              && menu.page().equals("settings")
              && menu.lines().stream().anyMatch(s -> s.startsWith("Crownstone Bank"))) {
            openKiosk(server, minecraft.player.getUUID());
            next(4);
          }
        }
        case 4 -> {
          if (offered(menu, "branch_open", "")) {
            send(menu, "branch_open", "");
            next(5);
          }
        }
        case 5 -> {
          if (fresh(menu)
              && menu.choices().stream().anyMatch(c -> c.action().equals("branch_assign"))) {
            send(menu, "finance_credit_apply", "");
            next(6);
          }
        }
        case 6 -> {
          var choice = first(menu, "finance_credit_network");
          if (fresh(menu) && choice != null && press(screen, "Actions")) {
            networkLabel = choice.label();
            next(7);
          }
        }
        case 7 -> {
          if (press(screen, networkLabel)) {
            sentRevision = menu.revision();
            next(8);
          }
        }
        case 8 -> {
          if (fresh(menu) && offered(menu, "finance_credit_preview", "")) {
            capture(minecraft, "finance-credit-input.png");
            if (amount(screen, "2500") && press(screen, "Review limit")) {
              sentRevision = menu.revision();
              next(9);
            }
          }
        }
        case 9 -> {
          if (fresh(menu) && offered(menu, "finance_credit_confirm", "")) {
            require(
                menu.lines().stream().anyMatch(s -> s.contains("APR")),
                "Credit review contains APRs");
            capture(minecraft, "finance-credit-review.png");
            next(90);
          }
        }
        case 90 -> {
          if (press(screen, "Actions")) next(10);
        }
        case 10 -> {
          var choice = first(menu, "finance_credit_confirm");
          if (choice != null && press(screen, choice.label())) {
            sentRevision = menu.revision();
            next(11);
          }
        }
        case 11 -> {
          if (fresh(menu) && menu.lines().stream().anyMatch(s -> s.equals("Done."))) {
            send(menu, "claims", "");
            next(12);
          }
        }
        case 12 -> {
          var choice = first(menu, "collect");
          if (fresh(menu) && choice != null) {
            send(menu, choice.action(), choice.value());
            next(13);
          }
        }
        case 13 -> {
          if (fresh(menu) && menu.status().equals("Delivery complete")) {
            if (!verificationRequested) {
              verificationRequested = true;
              verifyStarter(server, minecraft.player.getUUID());
            }
            if (verified && !switched) {
              switched = true;
              var uuid = minecraft.player.getUUID();
              server.execute(
                  () -> {
                    var player = server.getPlayerList().getPlayer(uuid);
                    if (player != null) {
                      player.closeContainer();
                      var world = Economy.serverServices(server).worldId();
                      boolean held = false;
                      for (int slot = 0; slot < 36; slot++) {
                        var stack = player.getInventory().getItem(slot);
                        if (ca.emilybelnavis.mcmods.economy.card.CardStackIdentity.mark(
                                stack, world)
                            .isPresent()) {
                          var previous = player.getOffhandItem();
                          player.setItemInHand(InteractionHand.OFF_HAND, stack);
                          player.getInventory().setItem(slot, previous);
                          held = true;
                          break;
                        }
                      }
                      if (!held) {
                        failure = "Collected issued card missing before remote access";
                        return;
                      }
                      BankingController.openCard(player);
                    }
                  });
              next(14);
            }
          }
        }
        case 14 -> {
          if (menu.remote() && menu.page().equals("home")) {
            send(menu, "finance_home", "");
            next(15);
          }
        }
        case 15 -> {
          if (fresh(menu) && menu.page().equals("finance_home")) {
            capture(minecraft, "finance-services.png");
            send(menu, "finance_wealth", "");
            next(16);
          }
        }
        case 16 -> {
          if (fresh(menu) && menu.page().equals("finance_wealth")) {
            require(
                menu.lines().stream().anyMatch(s -> s.contains("100,000,000")),
                "Exact tier threshold reaches client");
            capture(minecraft, "finance-wealth.png");
            send(menu, "finance_report", "");
            next(17);
          }
        }
        case 17 -> {
          if (fresh(menu) && menu.page().equals("finance_report")) {
            send(menu, "finance_loan_home", "");
            next(18);
          }
        }
        case 18 -> {
          if (fresh(menu) && menu.page().equals("finance_loan_home")) {
            openKiosk(server, minecraft.player.getUUID());
            next(19);
          }
        }
        case 19 -> {
          if (menu.access() == BankingMenu.Access.KIOSK
              && menu.page().equals("branch")
              && !menu.busy()) {
            send(menu, "finance_loan_apply", "");
            next(20);
          }
        }
        case 20 -> {
          if (fresh(menu) && offered(menu, "finance_loan_preview", "")) {
            capture(minecraft, "finance-loan-input.png");
            var choice = first(menu, "finance_loan_preview");
            if (amount(screen, "1000 1") && press(screen, choice.label())) {
              sentRevision = menu.revision();
              next(21);
            }
          }
        }
        case 21 -> {
          if (fresh(menu) && offered(menu, "finance_loan_confirm", "")) {
            require(
                menu.lines().stream().anyMatch(s -> s.contains("APR")),
                "Loan offer APR reaches client");
            capture(minecraft, "finance-loan-offer.png");
            next(22);
          }
        }
        case 22 -> {
          if (screenshots >= 6) {
            send(menu, "auto_home", "");
            next(23);
          }
        }
        case 23 -> {
          if (fresh(menu) && offered(menu, "auto_cards", "")) {
            send(menu, "auto_cards", "");
            next(24);
          }
        }
        case 24 -> {
          if (fresh(menu) && !menu.busy()) {
            var choice = first(menu, "auto_select");
            send(menu, choice.action(), choice.value());
            next(25);
          }
        }
        case 25 -> {
          if (fresh(menu) && !menu.busy()) {
            require(
                menu.lines().stream().anyMatch(s -> s.equals("Automatic payments OFF")),
                "Autopay starts OFF");
            var choice = first(menu, "auto_funding");
            send(menu, choice.action(), choice.value());
            next(26);
          }
        }
        case 26 -> {
          if (fresh(menu) && offered(menu, "auto_mode", "MINIMUM")) {
            send(menu, "auto_mode", "MINIMUM");
            next(27);
          }
        }
        case 27 -> {
          if (fresh(menu) && offered(menu, "auto_confirm", "")) {
            require(
                menu.lines().stream().anyMatch(s -> s.contains("two days after")),
                "Retry consent reaches client");
            capture(minecraft, "finance-autopay-consent.png");
            send(menu, "auto_confirm", "");
            next(28);
          }
        }
        case 28 -> {
          if (fresh(menu) && !menu.busy()) {
            require(
                menu.status().equals("Banking operation resolved"),
                "Autopay enable committed " + menu.status());
            send(menu, "auto_cards", "");
            next(29);
          }
        }
        case 29 -> {
          if (fresh(menu) && !menu.busy()) {
            var choice = first(menu, "auto_select");
            send(menu, choice.action(), choice.value());
            next(30);
          }
        }
        case 30 -> {
          if (fresh(menu) && offered(menu, "auto_disable", "")) {
            require(
                menu.lines().stream().anyMatch(s -> s.equals("Automatic payments ON")),
                "Persisted autopay ON reaches client");
            capture(minecraft, "finance-autopay-enabled.png");
            send(menu, "auto_disable", "");
            next(31);
          }
        }
        case 31 -> {
          if (fresh(menu) && offered(menu, "auto_confirm", "")) {
            send(menu, "auto_confirm", "");
            next(32);
          }
        }
        case 32 -> {
          if (fresh(menu) && !menu.busy()) {
            require(
                menu.status().equals("Banking operation resolved"),
                "Autopay disable committed " + menu.status());
            send(menu, "auto_cards", "");
            next(33);
          }
        }
        case 33 -> {
          if (fresh(menu) && !menu.busy()) {
            var choice = first(menu, "auto_select");
            send(menu, choice.action(), choice.value());
            next(34);
          }
        }
        case 34 -> {
          if (fresh(menu) && !menu.busy() && screenshots >= 8) {
            require(
                menu.lines().stream().anyMatch(s -> s.equals("Automatic payments OFF")),
                "Persisted opt-out reaches client");
            finish(minecraft, true);
          }
        }
        default -> throw new IllegalStateException("Client fixture step " + step);
      }
    } catch (Exception error) {
      failure = error.toString();
      finish(minecraft, false);
    }
  }

  private void prepare(MinecraftServer server, UUID uuid) {
    try {
      var player = server.getPlayerList().getPlayer(uuid);
      var services = Economy.serverServices(server);
      if (player == null
          || services == null
          || services.state() != ServerServices.State.READY
          || !services.metrics().economy().ready()) {
        setupRequested = false;
        return;
      }
      worldRoot = server.getWorldPath(LevelResource.ROOT);
      player.closeContainer();
      player.setInvulnerable(true);
      server.overworld().setChunkForced(KIOSK.getX() >> 4, KIOSK.getZ() >> 4, true);
      server.overworld().getChunkAt(KIOSK);
      for (var pos : BlockPos.betweenClosed(KIOSK.offset(-3, -1, -3), KIOSK.offset(3, 3, 3)))
        server
            .overworld()
            .setBlockAndUpdate(
                pos,
                pos.getY() < KIOSK.getY()
                    ? Blocks.STONE.defaultBlockState()
                    : Blocks.AIR.defaultBlockState());
      player.teleportTo(KIOSK.getX() + 2, KIOSK.getY(), KIOSK.getZ() - 2);
      player.setYRot(Direction.SOUTH.toYRot());
      var stack = new ItemStack(TerminalCatalog.get("branch_kiosk").item().get());
      player.setItemInHand(InteractionHand.MAIN_HAND, stack);
      var hit =
          new BlockHitResult(
              Vec3.atCenterOf(KIOSK.below()).add(0, .5, 0), Direction.UP, KIOSK.below(), false);
      require(
          ((BlockItem) stack.getItem())
              .place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit))
              .consumesAction(),
          "Place host kiosk");
      player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
      prepared = true;
    } catch (Exception error) {
      failure = "Host setup " + error;
    }
  }

  private void verifyStarter(MinecraftServer server, UUID uuid) {
    server.execute(
        () -> {
          try {
            var player = server.getPlayerList().getPlayer(uuid);
            var services = Economy.serverServices(server);
            var target = new PartyRef(services.worldId(), new PartyRef.Player(new PlayerId(uuid)));
            var grant =
                PartyAuthorization.player(player, Economy::serverServices, target).orElseThrow();
            services
                .ledger()
                .creditProfile(grant)
                .thenCombine(services.ledger().creditFacilities(grant, null, 4), Map::entry)
                .whenComplete(
                    (result, error) -> {
                      if (error != null) {
                        failure = "Starter owner verification " + error;
                        return;
                      }
                      try {
                        require(
                            result.getKey().orElseThrow().profile().orElseThrow().score() == 650,
                            "Actual first issuance score 650");
                        var cards = result.getValue().orElseThrow();
                        require(
                            cards.size() == 1
                                && cards.getFirst().limitMinor() == 250000
                                && cards.getFirst().owedMinor() == 0,
                            "Actual starter limit 2500 and no debt");
                        verified = true;
                      } catch (Exception invalid) {
                        failure = invalid.toString();
                      }
                    });
          } catch (Exception error) {
            failure = "Starter owner grant " + error;
          }
        });
  }

  private void openKiosk(MinecraftServer server, UUID uuid) {
    server.execute(
        () -> {
          var player = server.getPlayerList().getPlayer(uuid);
          if (player == null) {
            failure = "Host disappeared";
            return;
          }
          player.closeContainer();
          BankingController.open(player, KIOSK);
        });
  }

  private void send(BankingMenu menu, String action, String value) {
    sentRevision = menu.revision();
    BankingNetwork.action(menu, action, value);
  }

  private boolean fresh(BankingMenu menu) {
    return menu.revision() > sentRevision;
  }

  private static boolean offered(BankingMenu menu, String action, String value) {
    return menu.choices().stream()
        .anyMatch(c -> c.action().equals(action) && c.value().equals(value));
  }

  private static BankingNetwork.Choice first(BankingMenu menu, String action) {
    return menu.choices().stream().filter(c -> c.action().equals(action)).findFirst().orElse(null);
  }

  private static boolean amount(BankingScreen screen, String value) {
    var input =
        screen.children().stream()
            .filter(EditBox.class::isInstance)
            .map(EditBox.class::cast)
            .findFirst();
    input.ifPresent(box -> box.setValue(value));
    return input.isPresent();
  }

  private static boolean press(BankingScreen screen, String label) {
    var button =
        screen.children().stream()
            .filter(Button.class::isInstance)
            .map(Button.class::cast)
            .filter(b -> b.active && b.visible && b.getMessage().getString().equals(label))
            .findFirst();
    button.ifPresent(Button::onPress);
    return button.isPresent();
  }

  private void capture(Minecraft minecraft, String name) {
    pendingCaptures.addLast(name);
  }

  private void captureFrame(RenderFrameEvent.Post event) {
    if (pendingCaptures.isEmpty()) return;
    var minecraft = Minecraft.getInstance();
    String name = pendingCaptures.removeFirst();
    Screenshot.grab(
        minecraft.gameDirectory,
        name,
        minecraft.getMainRenderTarget(),
        message -> {
          synchronized (this) {
            screenshots++;
          }
          System.out.println("FINANCE_CLIENT_SCREEN " + name + " " + message.getString());
        });
  }

  private void next(int value) {
    step = value;
    stepStarted = ticks;
    System.out.println("FINANCE_CLIENT_STAGE " + step);
  }

  private void finish(Minecraft minecraft, boolean success) {
    if (completed) return;
    completed = true;
    try {
      Path root = worldRoot == null ? minecraft.gameDirectory.toPath() : worldRoot;
      Files.writeString(
          root.resolve(success ? "finance-client-passed.txt" : "finance-client-failed.txt"),
          success
              ? "PASS actual integrated host C2S/S2C kiosk branding, onboarding, credit network chooser, editable limit review button, credit terms/confirmation, protected token collection, first650/2500, remote finance/wealth/report/loan navigation and editable loan-offer review. Automatic-payment named-source MINIMUM consent, persisted ON and confirmed OFF. Eight rendered finance screenshots at GUI scale2. Loan offer was previewed only. External multiplayer transport, FX and collection payments are outside this fixture.\n"
              : failure + "\n");
      System.out.println(
          success
              ? "FINANCE_CLIENT_PASS actual payloads, widgets, screenshots and first issuance"
              : "FINANCE_CLIENT_FAILED " + failure);
    } catch (Exception error) {
      System.out.println("FINANCE_CLIENT_FAILED marker " + error);
    }
    var server = minecraft.getSingleplayerServer();
    if (server != null)
      server.execute(
          () -> server.overworld().setChunkForced(KIOSK.getX() >> 4, KIOSK.getZ() >> 4, false));
    minecraft.stop();
  }

  private String diagnostic(Minecraft minecraft) {
    return "step="
        + step
        + " screen="
        + (minecraft.screen == null ? "none" : minecraft.screen.getClass().getSimpleName())
        + " menu="
        + (minecraft.player != null && minecraft.player.containerMenu instanceof BankingMenu menu
            ? menu.page() + "/" + menu.revision() + "/" + menu.status()
            : "none");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
