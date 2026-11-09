package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.client.BankingScreen;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.institution.KioskAdmission;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Release-excluded, local native owner-settings witness. No funding or physical money. */
public final class AdminSettingsClient {
  private static final BlockPos TELLER = new BlockPos(528, 110, 520);
  private static final String BANK = "economy:bank/crownstone";
  private final long started = System.nanoTime();
  private final ArrayDeque<String> captures = new ArrayDeque<>();
  private final List<String> evidence = new ArrayList<>();
  private boolean opened, stopped, preparing;
  private volatile boolean prepared;
  private UUID playerId;
  private int ticks, step, screenshots;
  private long sentRevision = -1;
  private Path root;
  private volatile String failure;
  private volatile Observation observed;
  private Observation before, preview, confirmed, concurrent;
  private UUID confirmedOperation, concurrentOperation;

  private record Observation(TerminalPolicy.Snapshot terminal, long facts, String lastOperation) {}

  public AdminSettingsClient() {
    require(
        Boolean.getBoolean("economy.adminSettingsProbe"),
        "Explicit admin settings fixture required");
    NeoForge.EVENT_BUS.addListener(this::tick);
    NeoForge.EVENT_BUS.addListener(this::capture);
  }

  private void tick(ClientTickEvent.Post event) {
    if (stopped) return;
    var client = Minecraft.getInstance();
    client.options.pauseOnLostFocus = false;
    try {
      if (!opened && client.getOverlay() == null && client.screen != null && client.level == null) {
        opened = true;
        root =
            DisposableProbeWorld.requireClientDirectory(
                client.gameDirectory.toPath(), "economy-a10-07-");
        String name = "economy-admin-settings-" + UUID.randomUUID();
        DisposableProbeWorld.requireFreshWorld(root, name);
        require(client.options.languageCode.equals("en_gb"), "Launch fixture with en_gb locale");
        client.options.guiScale().set(2);
        client.resizeDisplay();
        var settings =
            new LevelSettings(
                name,
                GameType.CREATIVE,
                false,
                Difficulty.PEACEFUL,
                true,
                new GameRules(),
                WorldDataConfiguration.DEFAULT);
        client
            .createWorldOpenFlows()
            .createFreshLevel(
                name,
                settings,
                new WorldOptions(1007L, false, false),
                WorldPresets::createNormalWorldDimensions,
                client.screen);
      }
      if (failure != null) {
        finish(client, false);
        return;
      }
      if (System.nanoTime() - started > 180_000_000_000L)
        throw new IllegalStateException("180s watchdog step=" + step);
      if (root != null && Files.exists(root.resolve("admin-settings-stop.txt")))
        throw new IllegalStateException("Fixture stop file");
      if (client.player == null || client.getSingleplayerServer() == null) return;
      ticks++;
      playerId = client.player.getUUID();
      var server = client.getSingleplayerServer();
      if (client.player.containerMenu instanceof BankingMenu setup
          && setup.worldSetup()
          && !setup.busy()) {
        if (ticks % 20 == 0) BankingNetwork.action(setup, "setup", "CAD");
        return;
      }
      if (!prepared && !preparing && ticks > 30) {
        preparing = true;
        UUID id = client.player.getUUID();
        server.execute(
            () -> {
              try {
                var services = Economy.serverServices(server);
                var player = server.getPlayerList().getPlayer(id);
                if (services == null
                    || services.state() != ServerServices.State.READY
                    || !services.metrics().economy().ready()) {
                  preparing = false;
                  return;
                }
                player.setInvulnerable(true);
                var level = player.serverLevel();
                level.setChunkForced(TELLER.getX() >> 4, TELLER.getZ() >> 4, true);
                level.getChunkAt(TELLER);
                if (!(level.getBlockEntity(TELLER)
                    instanceof ca.emilybelnavis.mcmods.economy.terminal.TerminalBlockEntity))
                  for (var pos :
                      BlockPos.betweenClosed(TELLER.offset(-4, -1, -4), TELLER.offset(4, 3, 4)))
                    level.setBlockAndUpdate(
                        pos,
                        pos.getY() < TELLER.getY()
                            ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()
                            : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                if (!FixtureBankingTerminal.open(player, TELLER)) {
                  preparing = false;
                  return;
                }
                player.closeContainer();
                require(
                    BankingController.openAdmin(player, TELLER),
                    "Owner opens actual placed teller Admin menu");
                prepared = true;
              } catch (Throwable invalid) {
                failure = "Setup " + invalid;
              }
            });
      }
      if (!(client.screen instanceof BankingScreen screen)
          || !(client.player.containerMenu instanceof BankingMenu menu)
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
            require(
                menu.operation().state() == BankingOperation.State.REVIEW, "Native brand REVIEW");
            send(menu, "brand_confirm", BANK);
            next(3);
          }
        }
        case 3 -> {
          if (fresh(menu) && menu.page().equals("settings")) {
            read(server, menu);
            next(4);
          }
        }
        case 4 -> {
          if (observed != null) {
            before = take("before preview");
            send(menu, "configure", "BOTH CAD");
            next(5);
          }
        }
        case 5 -> {
          if (fresh(menu)
              && menu.page().equals("settings_review")
              && offered(menu, "configure_confirm", "")) {
            require(
                menu.operation().state() == BankingOperation.State.REVIEW,
                "Settings structured REVIEW");
            require(menu.operation().reviewId() != null, "Immutable native review identity");
            evidence.add(
                "review identity="
                    + menu.operation().reviewId()
                    + " lines="
                    + menu.lines()
                    + " status="
                    + menu.status());
            captures.add("admin-settings-review-top.png");
            read(server, menu);
            next(6);
          }
        }
        case 6 -> {
          if (observed != null && screenshots >= 1) {
            preview = take("after preview");
            unchanged(before, preview, "preview has no mutation");
            for (int n = 0; n < 32; n++)
              screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN, 0, 0);
            captures.add("admin-settings-review-bottom.png");
            next(7);
          }
        }
        case 7 -> {
          if (screenshots >= 2) {
            send(menu, "configure_confirm", "");
            next(8);
          }
        }
        case 8 -> {
          if (fresh(menu) && menu.page().equals("settings")) {
            read(server, menu);
            next(9);
          }
        }
        case 9 -> {
          if (observed != null) {
            confirmed = take("confirmed");
            require(
                confirmed.terminal().revision() == before.terminal().revision() + 1
                    && confirmed.facts() == before.facts() + 1,
                "Exactly one native configuration commit");
            require(
                confirmed.terminal().settings().mode() == TerminalPolicy.Mode.BOTH
                    && confirmed
                        .terminal()
                        .settings()
                        .currencies()
                        .equals(Set.of(EconomyCurrency.CAD)),
                "Committed BOTH CAD");
            confirmedOperation = UUID.fromString(confirmed.lastOperation());
            evidence.add("native confirm operation=" + confirmedOperation);
            concurrent(server, menu);
            next(10);
          }
        }
        case 10 -> {
          if (observed != null) {
            concurrent = take("detached legitimate concurrent fixture configuration");
            require(
                concurrent.terminal().revision() == confirmed.terminal().revision() + 1
                    && concurrent.facts() == confirmed.facts() + 1
                    && concurrent.terminal().settings().mode() == TerminalPolicy.Mode.DEPOSIT
                    && concurrent.lastOperation().equals(concurrentOperation.toString()),
                "Concurrent journal changed DEPOSIT once");
            send(menu, "configure", "WITHDRAW CAD");
            next(11);
          }
        }
        case 11 -> {
          if (fresh(menu)) {
            String localized = Component.translatable("ui.admin.settings.stale").getString();
            require(
                menu.status().equals(localized) || menu.lines().contains(localized),
                "Localized stale draft refusal: " + menu.status() + " " + menu.lines());
            require(
                offered(menu, "settings", ""),
                "Stale refusal offers explicit read-only Settings refresh");
            captures.add("admin-settings-stale.png");
            read(server, menu);
            next(12);
          }
        }
        case 12 -> {
          if (observed != null && screenshots >= 3) {
            var after = take("after stale draft");
            unchanged(concurrent, after, "Stale draft adds no configuration fact/revision");
            send(menu, "settings", "");
            next(13);
          }
        }
        case 13 -> {
          if (fresh(menu)
              && menu.page().equals("settings")
              && menu.choices().stream()
                  .anyMatch(
                      c ->
                          c.action().equals("configure") && c.value().startsWith("DEPOSIT;CAD;"))) {
            captures.add("admin-settings-refreshed.png");
            read(server, menu);
            next(14);
          }
        }
        case 14 -> {
          if (observed != null && screenshots >= 4) {
            var after = take("after read-only refresh");
            unchanged(concurrent, after, "Refresh displays current state without writing");
            finish(client, true);
          }
        }
      }
    } catch (Throwable invalid) {
      failure = "Native admin settings step=" + step + " " + invalid;
      finish(client, false);
    }
  }

  private void read(MinecraftServer server, BankingMenu clientMenu) {
    UUID id = playerId;
    var anchor = clientMenu.anchor();
    server.execute(
        () -> {
          var services = Economy.serverServices(server);
          var actor = new PlayerId(id);
          services
              .tradeStorage(
                  store ->
                      store.transaction(
                          tx ->
                              new Observation(
                                  TerminalPolicy.find(
                                          tx::query,
                                          store.snapshot().worldId(),
                                          anchor,
                                          actor,
                                          false)
                                      .orElseThrow(),
                                  Long.parseLong(
                                      tx.query(
                                              "SELECT count(*) AS n FROM journal_records WHERE record_type=? AND payload LIKE 'CONFIGURE|%'",
                                              List.of(TerminalPolicy.FACT), 1)
                                          .getFirst()
                                          .get("n")),
                                  tx
                                      .query(
                                          "SELECT t.operation_id FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE r.record_type=? AND r.payload LIKE 'CONFIGURE|%' ORDER BY r.sequence DESC LIMIT 1",
                                          List.of(TerminalPolicy.FACT), 1)
                                      .stream()
                                      .findFirst()
                                      .map(row -> row.get("operation_id"))
                                      .orElse("none"))))
              .whenComplete(
                  (value, error) -> {
                    if (error != null) failure = "Independent terminal read " + error;
                    else observed = value;
                  });
        });
  }

  private void concurrent(MinecraftServer server, BankingMenu clientMenu) {
    UUID id = playerId;
    server.execute(
        () -> {
          try {
            var player = server.getPlayerList().getPlayer(id);
            var menu = (BankingMenu) player.containerMenu;
            var services = Economy.serverServices(server);
            var actor = new PlayerId(id);
            var terminal = confirmed.terminal();
            var settings =
                new TerminalPolicy.Settings(
                    TerminalPolicy.Mode.DEPOSIT,
                    terminal.settings().currencies(),
                    terminal.settings().denominations());
            concurrentOperation = UUID.randomUUID();
            var intent =
                new Journal.Intent(
                    new OperationId(concurrentOperation),
                    new RequestId(UUID.randomUUID()),
                    actor,
                    TerminalPolicy.Action.CONFIGURE.kind(),
                    TerminalPolicy.configureRequest(
                        terminal.anchor(), terminal.generation(), terminal.revision(), settings),
                    null,
                    null);
            var authority = KioskAdmission.authority(player, menu, terminal, intent);
            services
                .ledger()
                .terminalPolicy(
                    TerminalPolicy.Action.CONFIGURE,
                    (who, kind) -> who.equals(actor),
                    authority,
                    (who, reads) -> false,
                    null)
                .submit(intent)
                .completion()
                .whenComplete(
                    (delivery, error) -> {
                      if (error != null || delivery.outcome().status() != Journal.Status.COMMITTED)
                        failure = "Concurrent real journal configuration failed " + error;
                      else {
                        evidence.add(
                            "detached legitimate concurrent fixture operator CONFIGURE operation="
                                + concurrentOperation);
                        read(server, clientMenu);
                      }
                    });
          } catch (Throwable invalid) {
            failure = "Concurrent fixture " + invalid;
          }
        });
  }

  private Observation take(String label) {
    var value = observed;
    observed = null;
    evidence.add(
        label
            + " terminal="
            + value.terminal()
            + " configurationFacts="
            + value.facts()
            + " lastConfigurationOperation="
            + value.lastOperation());
    return value;
  }

  private static void unchanged(Observation a, Observation b, String message) {
    require(
        a.terminal().equals(b.terminal())
            && a.facts() == b.facts()
            && a.lastOperation().equals(b.lastOperation()),
        message);
  }

  private void capture(RenderFrameEvent.Post event) {
    if (captures.isEmpty()) return;
    var client = Minecraft.getInstance();
    String name = captures.remove();
    Screenshot.grab(
        client.gameDirectory,
        name,
        client.getMainRenderTarget(),
        message -> {
          screenshots++;
          System.out.println("ADMIN_SETTINGS_SCREEN " + name + " " + message.getString());
        });
  }

  private void finish(Minecraft client, boolean passed) {
    if (stopped) return;
    stopped = true;
    try {
      Files.writeString(
          client
              .gameDirectory
              .toPath()
              .resolve("admin-settings-" + (passed ? "passed" : "failed") + ".txt"),
          (passed
                  ? "PASS local native owner Admin settings witness; en_gb scale2; no funding; no multiplayer claim"
                  : "FAIL " + failure)
              + "\n"
              + String.join("\n", evidence)
              + "\n");
    } catch (Exception invalid) {
      System.out.println("ADMIN_SETTINGS_MARKER_FAILED " + invalid);
    }
    var server = client.getSingleplayerServer();
    if (server != null)
      server.execute(
          () -> server.overworld().setChunkForced(TELLER.getX() >> 4, TELLER.getZ() >> 4, false));
    client.execute(client::stop);
  }

  private void send(BankingMenu menu, String action, String value) {
    sentRevision = menu.revision();
    BankingNetwork.action(menu, action, value);
  }

  private boolean fresh(BankingMenu menu) {
    return menu.revision() > sentRevision;
  }

  private void next(int value) {
    step = value;
    System.out.println("ADMIN_SETTINGS_STAGE " + step);
  }

  private static boolean offered(BankingMenu menu, String action, String value) {
    return menu.choices().stream()
        .anyMatch(c -> c.action().equals(action) && c.value().equals(value));
  }

  private static void require(boolean test, String message) {
    if (!test) throw new IllegalStateException(message);
  }
}
