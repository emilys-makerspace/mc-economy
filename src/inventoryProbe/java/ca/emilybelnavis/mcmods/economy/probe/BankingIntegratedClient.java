package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.cash.CashCatalog;
import ca.emilybelnavis.mcmods.economy.cash.CashStackIdentity;
import ca.emilybelnavis.mcmods.economy.client.BankingScreen;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.terminal.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Actual local client payload/click exchange and host-save proof, opt-in disposable world only. */
@Mod(value = "economy_inventory_probe", dist = Dist.CLIENT)
public final class BankingIntegratedClient {
  private static final BlockPos TELLER = new BlockPos(136, 110, 136);
  private boolean opened,
      cardRequested,
      captured,
      seedRequested,
      proofRequested,
      cashCaptured,
      failureWritten;
  private int ticks, step, transferState;
  private long revision = -1;
  private volatile boolean funded,
      terminalOpened,
      terminalRequested,
      inputsConfirmed,
      proofComplete;
  private volatile Path worldRoot;
  private volatile String failure;
  private volatile String proofOperation;

  public BankingIntegratedClient() {
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  private void tick(ClientTickEvent.Post event) {
    var name = System.getProperty("economy.bankingWorld");
    if (name == null) return;
    var minecraft = Minecraft.getInstance();
    if (!opened
        && minecraft.getOverlay() == null
        && minecraft.screen != null
        && minecraft.level == null) {
      opened = true;
      minecraft
          .createWorldOpenFlows()
          .openWorld(name, () -> System.out.println("BANKING_INTEGRATED_OPEN_CANCELLED"));
      return;
    }
    if (minecraft.player == null || minecraft.getSingleplayerServer() == null) return;
    ticks++;
    if (failure != null) {
      if (!failureWritten) {
        failureWritten = true;
        System.out.println("BANKING_CLIENT_FAILED " + failure);
        try {
          if (worldRoot != null)
            Files.writeString(worldRoot.resolve("banking-client-failed.txt"), failure);
        } catch (Exception ignored) {
        }
      }
      return;
    }
    if (minecraft.player.containerMenu instanceof BankingMenu menu
        && menu.worldSetup()
        && !menu.busy()) {
      if (ticks % 20 == 0) BankingNetwork.action(menu, "setup", "CAD");
      return;
    }
    var server = minecraft.getSingleplayerServer();
    if (!cardRequested && ticks > 25) {
      cardRequested = true;
      var uuid = minecraft.player.getUUID();
      server.execute(
          () -> {
            var player = server.getPlayerList().getPlayer(uuid);
            if (player == null || Economy.serverServices(server) == null) {
              failure = "Actual host unavailable for teller setup";
              return;
            }
            worldRoot = server.getWorldPath(LevelResource.ROOT);
            if (!FixtureBankingTerminal.open(player, TELLER)) cardRequested = false;
          });
    }
    if (ticks % 100 == 0)
      System.out.println(
          "BANKING_CLIENT_WAIT step="
              + step
              + " funded="
              + funded
              + " terminal="
              + terminalOpened
              + " proof="
              + proofComplete
              + " screen="
              + (minecraft.screen == null ? "none" : minecraft.screen.getClass().getSimpleName()));
    if (!(minecraft.screen instanceof BankingScreen)
        || !(minecraft.player.containerMenu instanceof BankingMenu menu)
        || menu.worldSetup()
        || menu.busy()
        || ticks % 8 != 0) return;
    if (step == 0 && menu.revision() > 0 && menu.page().equals("home")) {
      send(menu, "accounts", "");
      step++;
    } else if (step == 1
        && menu.page().equals("accounts")
        && menu.revision() > revision
        && menu.choices().stream().anyMatch(choice -> choice.action().equals("setup"))) {
      send(menu, "setup", "");
      step++;
    } else if (step == 2
        && menu.page().equals("accounts")
        && menu.revision() > revision
        && menu.choices().stream().anyMatch(choice -> choice.action().equals("account_select"))) {
      send(menu, "history", "");
      step++;
    } else if (step == 3 && menu.page().equals("history") && menu.revision() > revision) {
      send(menu, "fx", "");
      step++;
    } else if (step == 4 && menu.page().equals("fx") && menu.revision() > revision && !captured) {
      captured = true;
      Screenshot.grab(
          minecraft.gameDirectory,
          "banking-menu.png",
          minecraft.getMainRenderTarget(),
          message ->
              System.out.println(
                  "BANKING_CLIENT_PACKET_PASS actual setup/accounts/history/FX payload roundtrips and screen "
                      + message.getString()));
      step = 5;
    } else if (step == 5) {
      var uuid = minecraft.player.getUUID();
      if (!seedRequested) {
        seedRequested = true;
        var anchor = menu.anchor();
        server.execute(() -> seedHost(server, uuid, anchor));
      }
      if (funded && !terminalRequested) {
        terminalRequested = true;
        server.execute(() -> openHostTeller(server, uuid));
      }
      if (terminalOpened
          && !menu.remote()
          && menu.anchor().x() == TELLER.getX()
          && menu.anchor().z() == TELLER.getZ()) {
        send(menu, "withdraw", "");
        step = 6;
      }
    } else if (step == 6 && menu.page().equals("withdraw") && menu.revision() > revision) {
      send(menu, "withdraw_preview", "CAD 20");
      step = 7;
    } else if (step == 7
        && menu.status().equals("Withdrawal preview")
        && menu.revision() > revision) {
      send(menu, "withdraw_confirm", "");
      step = 8;
    } else if (step == 8
        && menu.status().equals("Delivery complete")
        && clientCash(minecraft) == 2000) {
      send(menu, "deposit", "");
      step = 9;
    } else if (step == 9 && menu.page().equals("deposit") && menu.revision() > revision) {
      if (minecraft.gameMode == null) return;
      for (int index = 24; index < menu.slots.size(); index++) {
        var stack = menu.slots.get(index).getItem();
        if (CashCatalog.find(stack).isPresent() && stack.has(CashStackIdentity.CASH_LOT.get())) {
          transferState = menu.getStateId();
          minecraft.gameMode.handleInventoryMouseClick(
              menu.containerId, index, 0, ClickType.QUICK_MOVE, minecraft.player);
          step = 10;
          break;
        }
      }
    } else if (step == 10) {
      if (!inputsConfirmed) {
        var uuid = minecraft.player.getUUID();
        var session = menu.session();
        server.execute(
            () -> {
              var player = server.getPlayerList().getPlayer(uuid);
              if (player != null
                  && player.containerMenu instanceof BankingMenu current
                  && current.session().equals(session)
                  && inputCash(current) == 2000
                  && serverCash(player) == 0) inputsConfirmed = true;
            });
      }
      if (clientCash(minecraft) == 0 && inputCash(menu) == 2000 && inputsConfirmed) {
        send(menu, "deposit_preview", "");
        step = 11;
      }
    } else if (step == 11
        && menu.status().startsWith("Preview ready")
        && menu.revision() > revision) {
      send(menu, "deposit_confirm", "");
      step = 12;
    } else if (step == 12
        && menu.status().equals("Thank you for using our banking service.")
        && menu.revision() > revision) {
      if (!proofRequested) {
        proofRequested = true;
        var uuid = minecraft.player.getUUID();
        server.execute(() -> verifyHostRoundtrip(server, uuid));
      }
      if (proofComplete && !cashCaptured) {
        cashCaptured = true;
        Path screenshot =
            minecraft.gameDirectory.toPath().resolve("screenshots/banking-cash-saved.png");
        Screenshot.grab(
            minecraft.gameDirectory,
            "banking-cash-saved.png",
            minecraft.getMainRenderTarget(),
            message -> {
              try {
                if (!Files.isRegularFile(screenshot))
                  throw new IllegalStateException("Cash screen screenshot not observed");
                Files.writeString(
                    worldRoot.resolve("banking-client-passed.txt"),
                    "PASS actual host C2S setup/accounts/history/FX, CAD20 withdrawal, vanilla QUICK_MOVE issued-cash deposit, restored CAD100 balance, UUID.dat+level.dat Player save proof operation "
                        + proofOperation
                        + "\n");
                System.out.println(
                    "BANKING_HOST_CASH_PASS actual network payloads and vanilla slot click; CAD10000 restored; UUID.dat and level.dat Player agree; "
                        + message.getString());
              } catch (Exception error) {
                failure = "Could not publish actual host cash proof: " + error;
              }
            });
      }
    }
  }

  private void send(BankingMenu menu, String action, String value) {
    revision = menu.revision();
    BankingNetwork.action(menu, action, value);
  }

  private void seedHost(MinecraftServer server, UUID uuid, TerminalPolicy.Anchor anchor) {
    var player = server.getPlayerList().getPlayer(uuid);
    var services = Economy.serverServices(server);
    if (player == null || services == null) {
      failure = "Host seed session missing";
      return;
    }
    var owner = new PlayerId(uuid);
    services
        .ledger()
        .bankingContext(owner, anchor, null)
        .whenComplete(
            (context, error) -> {
              if (error != null || context.account() == null) {
                failure = "Host primary missing before cash test: " + error;
                return;
              }
              var account = context.account();
              var adjustment =
                  new SameCurrency.Adjustment(
                      owner,
                      new SameCurrency.Revision(account.id(), account.revision()),
                      new Money(account.currency(), 10_000));
              var intent =
                  new Journal.Intent(
                      new OperationId(UUID.randomUUID()),
                      new RequestId(UUID.randomUUID()),
                      owner,
                      SameCurrency.CREDIT,
                      adjustment.request(),
                      null,
                      null);
              services
                  .ledger()
                  .adjustments(
                      SameCurrency.CREDIT,
                      (who, kind) -> who.equals(owner),
                      (actual, instruction, reads) -> true,
                      null)
                  .submit(intent)
                  .completion()
                  .whenComplete(
                      (delivery, seedError) -> {
                        if (seedError != null
                            || delivery.outcome().status() != Journal.Status.COMMITTED)
                          failure = "Host seed did not commit: " + seedError;
                        else funded = true;
                      });
            });
  }

  private void openHostTeller(MinecraftServer server, UUID uuid) {
    var player = server.getPlayerList().getPlayer(uuid);
    var services = Economy.serverServices(server);
    if (player == null || services == null) {
      failure = "Host terminal session missing";
      return;
    }
    var level = server.overworld();
    if (!(level.getBlockEntity(TELLER) instanceof TerminalBlockEntity entity)
        || !TerminalLifecycle.matches(level, TELLER, entity.generation())) {
      terminalRequested = false;
      return;
    }
    var anchor =
        new TerminalPolicy.Anchor(
            level.dimension().location().toString(), TELLER.getX(), TELLER.getY(), TELLER.getZ());
    services
        .ledger()
        .bankingContext(new PlayerId(uuid), anchor, null)
        .whenComplete(
            (context, error) -> {
              if (error != null) {
                failure = "Host teller read failed: " + error;
                return;
              }
              if (context.terminal() == null
                  || !context.terminal().settings().mode().deposits()
                  || !context.terminal().settings().mode().withdrawals()) {
                terminalRequested = false;
                return;
              }
              player.teleportTo(TELLER.getX() + 3.0, TELLER.getY(), TELLER.getZ() - 2.0);
              BankingController.open(player, TELLER);
              terminalOpened = player.containerMenu instanceof BankingMenu;
            });
  }

  private void verifyHostRoundtrip(MinecraftServer server, UUID uuid) {
    var player = server.getPlayerList().getPlayer(uuid);
    var services = Economy.serverServices(server);
    if (player == null || services == null) {
      failure = "Host final session missing";
      return;
    }
    var world = services.worldId();
    var owner = new PlayerId(uuid);
    var grant =
        PartyAuthorization.player(
                player, Economy::serverServices, new PartyRef(world, new PartyRef.Player(owner)))
            .orElseThrow();
    services
        .ledger()
        .primaryFunds(grant)
        .whenComplete(
            (found, error) -> {
              if (error != null
                  || found.isEmpty()
                  || found.orElseThrow().funds().availableMinorUnits() != 10_000) {
                failure = "Actual host cash roundtrip balance is not CAD100: " + error;
                return;
              }
              try {
                var operation = UUID.randomUUID();
                var proof = BankingSaves.player(player, world, operation);
                if (!proof.reference().equals("level.dat"))
                  throw new IllegalStateException("Host save did not use integrated reload source");
                var root = server.getWorldPath(LevelResource.ROOT);
                var actual =
                    NbtIo.readCompressed(
                        root.resolve("playerdata/" + uuid + ".dat"),
                        NbtAccounter.create(1_048_576));
                var host =
                    NbtIo.readCompressed(root.resolve("level.dat"), NbtAccounter.create(1_048_576))
                        .getCompound("Data")
                        .getCompound("Player");
                if (!Objects.equals(actual.get("Inventory"), host.get("Inventory"))
                    || !Objects.equals(actual.get("NeoForgeData"), host.get("NeoForgeData")))
                  throw new IllegalStateException(
                      "Host reload inventory differs from UUID inventory");
                var marker = host.getCompound("NeoForgeData").getCompound("economy_banking_save");
                if (!marker.hasUUID("operation")
                    || !marker.getUUID("operation").equals(operation)
                    || !marker.getUUID("world").equals(world.value()))
                  throw new IllegalStateException("Host reload save marker differs");
                proofOperation = operation.toString();
                proofComplete = true;
              } catch (Exception failed) {
                failure = "Host cash save witness failed: " + failed;
              }
            });
  }

  private static long clientCash(Minecraft minecraft) {
    long value = 0;
    for (int index = 0; index < 36; index++) {
      var stack = minecraft.player.getInventory().getItem(index);
      var denomination = CashCatalog.find(stack).orElse(null);
      if (denomination != null) value += denomination.minorUnits() * stack.getCount();
    }
    return value;
  }

  private static long serverCash(ServerPlayer player) {
    long value = 0;
    for (int index = 0; index < 36; index++) {
      var stack = player.getInventory().getItem(index);
      var denomination = CashCatalog.find(stack).orElse(null);
      if (denomination != null) value += denomination.minorUnits() * stack.getCount();
    }
    return value;
  }

  private static long inputCash(BankingMenu menu) {
    long value = 0;
    for (int index = 0; index < 24; index++) {
      var stack = menu.depositInputs().getItem(index);
      var denomination = CashCatalog.find(stack).orElse(null);
      if (denomination != null) value += denomination.minorUnits() * stack.getCount();
    }
    return value;
  }
}
