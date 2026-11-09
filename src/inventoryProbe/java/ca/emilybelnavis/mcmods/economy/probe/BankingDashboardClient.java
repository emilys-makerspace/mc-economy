package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.cash.CashCatalog;
import ca.emilybelnavis.mcmods.economy.client.BankingScreen;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.terminal.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Opt-in, release-excluded native protocol/dashboard witness in a UUID-named fresh world only. */
public final class BankingDashboardClient {
  private static final BlockPos KIOSK = new BlockPos(520, 110, 520),
      TELLER = new BlockPos(528, 110, 520);
  private static final String BANK = "economy:bank/crownstone";
  private final boolean historyMode = Boolean.getBoolean("economy.bankingHistoryProbe");
  private final long started = System.nanoTime();
  private final ArrayDeque<String> captures = new ArrayDeque<>();
  private boolean opened,
      stopped,
      setupRequested,
      adminRequested,
      seedRequested,
      tellerRequested,
      absenceRequested,
      verificationRequested;
  private volatile boolean prepared, funded, tellerOpened, absenceInstalled, verified;
  private volatile String failure;
  private volatile int screenshots;
  private int ticks, step, stepStarted;
  private long sentRevision = -1;
  private volatile UUID fundingOperation, withdrawalOperation, withdrawalReceipt, absentOperation;
  private volatile Journal.Intent absentIntent;
  private boolean historyHeadRequested;
  private volatile Long historyHead;
  private Path fixtureRoot;
  private UUID historyGeneration;
  private long historyHighWater;

  public BankingDashboardClient() {
    if (!Boolean.getBoolean("economy.bankingDashboardProbe") && !historyMode)
      throw new IllegalStateException("Explicit banking fixture required");
    NeoForge.EVENT_BUS.addListener(this::tick);
    NeoForge.EVENT_BUS.addListener(this::captureFrame);
  }

  private void tick(ClientTickEvent.Post event) {
    if (stopped) return;
    var client = Minecraft.getInstance();
    client.options.pauseOnLostFocus = false;
    try {
      if (!opened && client.getOverlay() == null && client.screen != null && client.level == null) {
        opened = true;
        Path root =
            DisposableProbeWorld.requireClientDirectory(
                client.gameDirectory.toPath(),
                historyMode ? "economy-a10-02-02-" : "economy-a10-02-01-");
        fixtureRoot = root;
        String name =
            (historyMode ? "economy-banking-history-" : "economy-banking-dashboard-")
                + UUID.randomUUID();
        DisposableProbeWorld.requireFreshWorld(root, name);
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
                new WorldOptions(100201L, false, false),
                WorldPresets::createNormalWorldDimensions,
                client.screen);
      }
      if (failure != null) {
        finish(client, false);
        return;
      }
      if (System.nanoTime() - started > 300_000_000_000L)
        throw new IllegalStateException("Dashboard fixture wall deadline step=" + step);
      if (client.player == null || client.getSingleplayerServer() == null) return;
      ticks++;
      if (ticks - stepStarted > 1_200)
        throw new IllegalStateException("Dashboard fixture step deadline " + diagnostic(client));
      var server = client.getSingleplayerServer();
      if (client.player.containerMenu instanceof BankingMenu setup
          && setup.worldSetup()
          && !setup.busy()) {
        if (ticks % 20 == 0) BankingNetwork.action(setup, "setup", "CAD");
        return;
      }
      if (!setupRequested && ticks > 30) {
        setupRequested = true;
        UUID player = client.player.getUUID();
        server.execute(() -> prepare(server, player));
      }
      if (prepared && !adminRequested && step == 0 && ticks % 10 == 0) {
        UUID uuid = client.player.getUUID();
        server.execute(
            () -> {
              var player = server.getPlayerList().getPlayer(uuid);
              if (player != null && BankingController.openAdmin(player, KIOSK))
                adminRequested = true;
            });
      }
      if (step == 12 && !tellerOpened && !tellerRequested && ticks % 10 == 0) {
        tellerRequested = true;
        UUID uuid = client.player.getUUID();
        server.execute(
            () -> {
              try {
                var player = server.getPlayerList().getPlayer(uuid);
                player.closeContainer();
                if (!(server.overworld().getBlockEntity(TELLER) instanceof TerminalBlockEntity)) {
                  for (var pos :
                      BlockPos.betweenClosed(TELLER.offset(-4, -1, -4), TELLER.offset(4, 3, 4)))
                    server
                        .overworld()
                        .setBlockAndUpdate(
                            pos,
                            pos.getY() < TELLER.getY()
                                ? Blocks.STONE.defaultBlockState()
                                : Blocks.AIR.defaultBlockState());
                }
                tellerOpened = FixtureBankingTerminal.open(player, TELLER);
                if (!tellerOpened) tellerRequested = false;
              } catch (Throwable invalid) {
                failure = "Fixture teller opening " + invalid;
              }
            });
      }
      if (ticks % 100 == 0) System.out.println("DASHBOARD_CLIENT_WAIT " + diagnostic(client));
      if (!(client.screen instanceof BankingScreen)
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
                menu.operation().state() == BankingOperation.State.REVIEW,
                "Brand review state reaches client");
            send(menu, "brand_confirm", BANK);
            next(3);
          }
        }
        case 3 -> {
          if (fresh(menu) && menu.page().equals("settings")) {
            UUID uuid = client.player.getUUID();
            server.execute(
                () -> {
                  var player = server.getPlayerList().getPlayer(uuid);
                  player.closeContainer();
                  BankingController.open(player, KIOSK);
                });
            next(4);
          }
        }
        case 4 -> {
          if (menu.access() == BankingMenu.Access.KIOSK && menu.dashboard() != null) {
            require(menu.dashboard().institution().equals(BANK), "Bound institution context");
            require(offered(menu, "accounts", ""), "Home offers Accounts navigation");
            send(menu, "accounts", "");
            next(5);
          }
        }
        case 5 -> {
          if (fresh(menu) && offered(menu, "branch_open", "")) {
            send(menu, "branch_open", "");
            next(6);
          } else if (fresh(menu) && offered(menu, "manage_accounts", ""))
            send(menu, "manage_accounts", "");
        }
        case 6 -> {
          if (fresh(menu) && !seedRequested) {
            seedRequested = true;
            UUID uuid = client.player.getUUID();
            server.execute(() -> fund(server, uuid, menu.anchor()));
            next(7);
          }
        }
        case 7 -> {
          if (funded) {
            send(menu, "home", "");
            next(8);
          }
        }
        case 8 -> {
          if (fresh(menu) && menu.dashboard() != null) {
            var view = menu.dashboard();
            require(
                view.accounts().rows().size() == 1
                    && view.accounts().rows().getFirst().available().minorUnits() == 10_000,
                "Owner CAD100 dashboard row");
            require(
                view.recent().rows().size() == 1
                    && view.recent().rows().getFirst().amount().minorUnits() == 10_000
                    && view.recent().rows().getFirst().operation().equals(fundingOperation),
                "Original newest funding activity identity");
            require(
                offered(menu, "accounts", "")
                    && offered(menu, "payments", "")
                    && offered(menu, "history", ""),
                "Server-offered core dashboard destinations");
            require(
                !menu.allows("fx") && !offered(menu, "fx", ""),
                "Kiosk does not acquire FX authority");
            captures.addLast("banking-dashboard-home.png");
            next(9);
          }
        }
        case 9 -> {
          if (screenshots >= 1) {
            send(menu, "accounts", "");
            next(10);
          }
        }
        case 10 -> {
          if (fresh(menu) && menu.page().equals("accounts")) {
            send(menu, "payments", "");
            next(11);
          }
        }
        case 11 -> {
          if (fresh(menu) && menu.page().equals("payments")) {
            require(
                !offered(menu, "withdraw", ""),
                "Kiosk Payments preserves physical cash restrictions");
            next(12);
          }
        }
        case 12 -> {
          if (menu.access() == BankingMenu.Access.TELLER && menu.dashboard() != null) {
            UUID uuid = client.player.getUUID();
            server.execute(
                () -> {
                  var player = server.getPlayerList().getPlayer(uuid);
                  player.closeContainer();
                  require(
                      BankingController.openAdmin(player, TELLER),
                      "Open placed teller administration");
                });
            next(20);
          }
        }
        case 13 -> {
          if (fresh(menu) && offered(menu, "withdraw", "")) {
            send(menu, "withdraw", "");
            next(14);
          }
        }
        case 14 -> {
          if (fresh(menu) && menu.page().equals("withdraw")) {
            var screen = (BankingScreen) client.screen;
            screen.children().stream()
                .filter(net.minecraft.client.gui.components.EditBox.class::isInstance)
                .map(net.minecraft.client.gui.components.EditBox.class::cast)
                .findFirst()
                .orElseThrow()
                .setValue("20");
            press(screen, "withdraw_preview");
            sentRevision = menu.revision();
            next(15);
          }
        }
        case 15 -> {
          if (fresh(menu) && menu.operation().state() == BankingOperation.State.REVIEW) {
            require(
                menu.operation().reviewId() != null
                    && menu.choices().stream().anyMatch(c -> c.action().equals("withdraw_confirm")),
                "Structured review has explicit offered confirm");
            captures.addLast("banking-dashboard-withdraw-review.png");
            next(16);
          }
        }
        case 16 -> {
          if (screenshots >= 2) {
            press((BankingScreen) client.screen, "withdraw_confirm");
            sentRevision = menu.revision();
            next(17);
          }
        }
        case 17 -> {
          if (fresh(menu)
              && menu.operation().state() == BankingOperation.State.COMPLETED
              && clientCash(client) == 2_000) {
            require(
                menu.operation().operationId() != null && menu.operation().receiptId() != null,
                "Financial receipt independent from observed cash delivery");
            withdrawalOperation = menu.operation().operationId();
            withdrawalReceipt = menu.operation().receiptId();
            next(historyMode ? 30 : 18);
          }
        }
        case 18 -> {
          if (!absenceRequested) {
            absenceRequested = true;
            UUID uuid = client.player.getUUID();
            server.execute(() -> installAbsentOriginal(server, uuid));
          }
          if (absenceInstalled
              && menu.operation().state() == BankingOperation.State.OUTCOME_UNCERTAIN) {
            require(
                menu.operation().operationId().equals(absentOperation),
                "Original uncertain identity reaches client");
            require(
                menu.choices().size() == 1 && offered(menu, "bank_retry", ""),
                "Uncertain state offers original lookup only");
            send(menu, "bank_retry", "");
            next(19);
          }
        }
        case 19 -> {
          if (fresh(menu) && menu.operation().state() == BankingOperation.State.EXPIRED_STALE) {
            require(
                menu.operation().operationId().equals(absentOperation)
                    && menu.operation().receiptId() == null,
                "Proven absence keeps original identity and requires fresh review");
            if (!verificationRequested) {
              verificationRequested = true;
              UUID uuid = client.player.getUUID();
              server.execute(() -> verify(server, uuid));
            }
            if (verified) finish(client, true);
          }
        }
        case 20 -> {
          if (menu.access() == BankingMenu.Access.ADMIN && offered(menu, "brand_preview", "")) {
            send(menu, "brand_preview", "");
            next(21);
          }
        }
        case 21 -> {
          if (fresh(menu) && offered(menu, "brand_preview", BANK)) {
            send(menu, "brand_preview", BANK);
            next(22);
          }
        }
        case 22 -> {
          if (fresh(menu) && offered(menu, "brand_confirm", BANK)) {
            send(menu, "brand_confirm", BANK);
            next(23);
          }
        }
        case 23 -> {
          if (fresh(menu) && menu.page().equals("settings")) {
            send(menu, "configure", "BOTH CAD");
            next(24);
          }
        }
        case 24 -> {
          if (fresh(menu)
              && menu.page().equals("settings_review")
              && offered(menu, "configure_confirm", "")) {
            send(menu, "configure_confirm", "");
            return;
          }
          if (fresh(menu) && menu.page().equals("settings")) {
            UUID uuid = client.player.getUUID();
            server.execute(
                () -> {
                  var player = server.getPlayerList().getPlayer(uuid);
                  player.closeContainer();
                  BankingController.open(player, TELLER);
                });
            next(25);
          }
        }
        case 25 -> {
          if (menu.access() == BankingMenu.Access.TELLER && menu.dashboard() != null) {
            require(
                menu.dashboard().institution().equals(BANK),
                "Teller explicitly bound before cash service");
            require(
                offered(menu, "fx", "") && offered(menu, "payments", ""),
                "Teller offers permitted FX and payments");
            send(menu, "payments", "");
            next(13);
          }
        }
        case 30 -> {
          if (!historyHeadRequested) {
            historyHeadRequested = true;
            server.execute(
                () ->
                    Economy.serverServices(server)
                        .tradeStorage(store -> store.lastCommittedSequence())
                        .whenComplete(
                            (head, error) -> {
                              if (error != null) failure = "History pre-head " + error;
                              else historyHead = head;
                            }));
          }
          if (historyHead != null) {
            send(menu, "home", "");
            next(31);
          }
        }
        case 31 -> {
          if (fresh(menu) && menu.dashboard() != null && offered(menu, "history", "")) {
            send(menu, "history", "");
            next(32);
          }
        }
        case 32 -> {
          if (fresh(menu)
              && menu.history() != null
              && menu.history().availability().equals("AVAILABLE")) {
            var view = menu.history();
            require(
                view.rows().size() >= 2 && view.rows().size() <= 8,
                "Bounded original native history rows");
            require(
                view.rows().getFirst().operation().equals(withdrawalOperation),
                "Newest financial COMMIT first");
            for (int i = 1; i < view.rows().size(); i++) {
              var before = view.rows().get(i - 1);
              var after = view.rows().get(i);
              require(
                  before.sequence() > after.sequence()
                      || before.sequence() == after.sequence()
                          && before.position() > after.position(),
                  "Stable descending sequence/position");
            }
            historyGeneration = view.generation();
            historyHighWater = view.highWater();
            captures.addLast("banking-history-page.png");
            next(33);
          }
        }
        case 33 -> {
          if (screenshots >= 3) {
            var choice =
                menu.choices().stream()
                    .filter(c -> c.action().equals("history_detail"))
                    .findFirst()
                    .orElseThrow();
            pressChoice(client, choice);
            sentRevision = menu.revision();
            next(34);
          }
        }
        case 34 -> {
          if (fresh(menu) && menu.history() != null && menu.history().detail() != null) {
            var view = menu.history();
            var detail = view.detail();
            require(
                view.generation().equals(historyGeneration) && view.highWater() == historyHighWater,
                "Detail retains frozen query audience");
            require(
                detail.transaction().equals(withdrawalReceipt)
                    && detail.operation().equals(withdrawalOperation),
                "Original financial identities in immutable detail");
            require(
                detail.status().equals("COMMITTED")
                    && detail.movements().stream()
                        .anyMatch(
                            m ->
                                m.amount().currency() == EconomyCurrency.CAD
                                    && m.amount().minorUnits() == -2_000),
                "Captured debit in detail");
            captures.addLast("banking-history-detail.png");
            next(35);
          }
        }
        case 35 -> {
          if (screenshots >= 4) {
            pressOffered(client, menu, "history_back", historyGeneration.toString());
            next(36);
          }
        }
        case 36 -> {
          if (fresh(menu) && menu.history() != null && menu.history().detail() == null) {
            require(
                menu.history().generation().equals(historyGeneration)
                    && menu.history().highWater() == historyHighWater,
                "Back retains page high-water and generation");
            pressOffered(client, menu, "history_filters", "");
            next(37);
          }
        }
        case 37 -> {
          if (fresh(menu) && offered(menu, "history_currency", "")) {
            pressOffered(client, menu, "history_currency", "");
            next(38);
          }
        }
        case 38 -> {
          if (fresh(menu) && offered(menu, "history_currency", "CAD")) {
            pressOffered(client, menu, "history_currency", "CAD");
            next(39);
          }
        }
        case 39 -> {
          if (fresh(menu)
              && menu.history() != null
              && menu.history().query().currency() == EconomyCurrency.CAD) {
            require(
                !menu.history().generation().equals(historyGeneration),
                "Currency filter resets query generation");
            historyGeneration = menu.history().generation();
            require(
                menu.history().rows().stream()
                    .allMatch(r -> r.amount().currency() == EconomyCurrency.CAD),
                "Server filtered currency rows");
            pressOffered(client, menu, "history_filters", "");
            next(40);
          }
        }
        case 40 -> {
          if (fresh(menu) && offered(menu, "history_type", "")) {
            pressOffered(client, menu, "history_type", "");
            next(41);
          }
        }
        case 41 -> {
          if (fresh(menu) && offered(menu, "history_type", "CASH")) {
            pressOffered(client, menu, "history_type", "CASH");
            next(42);
          }
        }
        case 42 -> {
          if (fresh(menu)
              && menu.history() != null
              && menu.history().query().type() == OwnerHistory.Type.CASH) {
            require(
                !menu.history().generation().equals(historyGeneration),
                "Type filter resets query generation");
            historyGeneration = menu.history().generation();
            require(
                menu.history().rows().size() == 1
                    && menu.history().rows().getFirst().operation().equals(withdrawalOperation),
                "Cash type keeps original withdrawal only");
            pressOffered(client, menu, "history_filters", "");
            next(43);
          }
        }
        case 43 -> {
          if (fresh(menu) && offered(menu, "history_dates", "")) {
            pressOffered(client, menu, "history_dates", "");
            next(44);
          }
        }
        case 44 -> {
          if (fresh(menu) && menu.page().equals("history_dates")) {
            var screen = (BankingScreen) client.screen;
            screen.children().stream()
                .filter(net.minecraft.client.gui.components.EditBox.class::isInstance)
                .map(net.minecraft.client.gui.components.EditBox.class::cast)
                .findFirst()
                .orElseThrow()
                .setValue("2020-01-01 2090-01-01");
            pressOffered(client, menu, "history_dates", "");
            next(45);
          }
        }
        case 45 -> {
          if (fresh(menu)
              && menu.history() != null
              && menu.history().query().fromInclusive() != null) {
            require(
                !menu.history().generation().equals(historyGeneration),
                "UTC filter resets query generation");
            historyGeneration = menu.history().generation();
            require(
                menu.history().query().fromInclusive()
                        == java.time.Instant.parse("2020-01-01T00:00:00Z").toEpochMilli()
                    && menu.history().query().toExclusive()
                        == java.time.Instant.parse("2090-01-01T00:00:00Z").toEpochMilli(),
                "Server normalized UTC range");
            pressOffered(client, menu, "history_rates", "");
            next(46);
          }
        }
        case 46 -> {
          if (fresh(menu) && menu.history() != null && menu.history().rates() != null) {
            var rates = menu.history().rates();
            require(
                rates.family() == BankingHistoryView.Family.EFFECTIVE
                    && rates.source() == EconomyCurrency.CAD
                    && rates.target() == EconomyCurrency.USD,
                "Separate exact-time directed FX history");
            captures.addLast("banking-history-rates.png");
            next(47);
          }
        }
        case 47 -> {
          if (screenshots >= 5) {
            pressOffered(client, menu, "rate_family", "DAILY");
            next(48);
          }
        }
        case 48 -> {
          if (fresh(menu)
              && menu.history() != null
              && menu.history().rates() != null
              && menu.history().rates().family() == BankingHistoryView.Family.DAILY) {
            require(
                menu.history().rates().source() == EconomyCurrency.CAD
                    && menu.history().rates().target() == EconomyCurrency.USD,
                "Daily family retains explicit pair direction");
            if (!verificationRequested) {
              verificationRequested = true;
              UUID uuid = client.player.getUUID();
              server.execute(() -> verifyHistory(server, uuid));
            }
            if (verified) finish(client, true);
          }
        }
        default -> throw new IllegalStateException("Dashboard fixture step " + step);
      }
    } catch (Throwable invalid) {
      invalid.printStackTrace();
      failure = invalid.toString();
      finish(client, false);
    }
  }

  private static void press(BankingScreen screen, String action) {
    var label =
        net.minecraft.network.chat.Component.translatable("screen.economy.action." + action)
            .getString();
    var button =
        screen.children().stream()
            .filter(net.minecraft.client.gui.components.Button.class::isInstance)
            .map(net.minecraft.client.gui.components.Button.class::cast)
            .filter(candidate -> candidate.getMessage().getString().equals(label))
            .findFirst()
            .orElseThrow();
    require(
        button.active && button.visible, "Actual financial button must be available: " + action);
    button.onPress();
  }

  private void pressOffered(Minecraft client, BankingMenu menu, String action, String value) {
    var choice =
        menu.choices().stream()
            .filter(c -> c.action().equals(action) && c.value().equals(value))
            .findFirst()
            .orElseThrow();
    pressChoice(client, choice);
    sentRevision = menu.revision();
  }

  private static void pressChoice(Minecraft client, BankingNetwork.Choice choice) {
    var screen = (BankingScreen) client.screen;
    String label = choice.label();
    if ((choice.action().equals("history_dates") || choice.action().equals("rate_at"))
        && client.player.containerMenu instanceof BankingMenu menu
        && Set.of("history_dates", "history_rate_date").contains(menu.page()))
      label =
          net.minecraft.network.chat.Component.translatable("screen.economy.history_apply")
              .getString();
    var button = button(screen, label);
    if (button.isEmpty()) {
      String chooserKey =
          client.player.containerMenu instanceof BankingMenu menu
                  && menu.page().equals("history_rates")
              ? "screen.economy.rate_controls"
              : "screen.economy.history_controls";
      var chooser =
          button(screen, net.minecraft.network.chat.Component.translatable(chooserKey).getString())
              .orElseThrow();
      require(chooser.active && chooser.visible, "Native History controls chooser reachable");
      chooser.onPress();
      button = button(screen, label);
    }
    var selected = button.orElseThrow();
    require(
        selected.active && selected.visible,
        "Offered native history control must be reachable: " + choice.action());
    selected.onPress();
  }

  private static Optional<net.minecraft.client.gui.components.Button> button(
      BankingScreen screen, String label) {
    return screen.children().stream()
        .filter(net.minecraft.client.gui.components.Button.class::isInstance)
        .map(net.minecraft.client.gui.components.Button.class::cast)
        .filter(candidate -> candidate.getMessage().getString().equals(label))
        .findFirst();
  }

  private void verifyHistory(MinecraftServer server, UUID uuid) {
    try {
      var player = server.getPlayerList().getPlayer(uuid);
      var services = Economy.serverServices(server);
      var actor = new PlayerId(uuid);
      var grant =
          PartyAuthorization.player(
                  player,
                  Economy::serverServices,
                  new PartyRef(services.worldId(), new PartyRef.Player(actor)))
              .orElseThrow();
      services
          .tradeStorage(store -> store.lastCommittedSequence())
          .whenComplete(
              (head, headError) -> {
                if (headError != null || !head.equals(historyHead)) {
                  failure =
                      "History read changed journal head: "
                          + historyHead
                          + " -> "
                          + head
                          + " error="
                          + headError;
                  return;
                }
                services
                    .ledger()
                    .primaryFunds(grant)
                    .whenComplete(
                        (funds, fundsError) -> {
                          if (fundsError != null
                              || funds.isEmpty()
                              || funds.orElseThrow().funds().availableMinorUnits() != 8_000)
                            failure = "History changed post-withdrawal CAD80 funds: " + fundsError;
                          else verified = true;
                        });
              });
    } catch (Throwable invalid) {
      failure = "Native history final verification " + invalid;
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
      player.closeContainer();
      player.setInvulnerable(true);
      var level = server.overworld();
      level.setChunkForced(KIOSK.getX() >> 4, KIOSK.getZ() >> 4, true);
      level.getChunkAt(KIOSK);
      for (var pos : BlockPos.betweenClosed(KIOSK.offset(-3, -1, -3), KIOSK.offset(3, 3, 3)))
        level.setBlockAndUpdate(
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
          "Place fixture branch kiosk");
      player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
      prepared = true;
    } catch (Throwable invalid) {
      failure = "Dashboard setup " + invalid;
    }
  }

  private void fund(MinecraftServer server, UUID uuid, TerminalPolicy.Anchor anchor) {
    var player = server.getPlayerList().getPlayer(uuid);
    var services = Economy.serverServices(server);
    var actor = new PlayerId(uuid);
    services
        .ledger()
        .bankingContext(actor, anchor, null)
        .whenComplete(
            (context, error) -> {
              if (error != null || context.account() == null) {
                failure =
                    "Native primary account missing before explicitly seeded funding: " + error;
                return;
              }
              var account = context.account();
              fundingOperation = UUID.randomUUID();
              var adjustment =
                  new SameCurrency.Adjustment(
                      actor,
                      new SameCurrency.Revision(account.id(), account.revision()),
                      new Money(account.currency(), 10_000));
              var intent =
                  new Journal.Intent(
                      new OperationId(fundingOperation),
                      new RequestId(UUID.randomUUID()),
                      actor,
                      SameCurrency.CREDIT,
                      adjustment.request(),
                      null,
                      null);
              services
                  .ledger()
                  .adjustments(
                      SameCurrency.CREDIT,
                      (who, kind) -> who.equals(actor),
                      (actual, instruction, reads) -> true,
                      null)
                  .submit(intent)
                  .completion()
                  .whenComplete(
                      (delivery, seedError) -> {
                        if (seedError != null
                            || delivery.outcome().status() != Journal.Status.COMMITTED)
                          failure = "Explicit fixture CAD100 journal funding failed: " + seedError;
                        else {
                          funded = true;
                          System.out.println("DASHBOARD_FIXTURE_FUNDING " + fundingOperation);
                        }
                      });
            });
  }

  private void installAbsentOriginal(MinecraftServer server, UUID uuid) {
    try {
      var player = server.getPlayerList().getPlayer(uuid);
      var services = Economy.serverServices(server);
      require(
          player.containerMenu instanceof BankingMenu,
          "Live menu for controlled absent-original fixture");
      var menu = (BankingMenu) player.containerMenu;
      // Explicitly injected saved intent: no financial action is submitted for this original
      // operation.
      absentOperation = UUID.randomUUID();
      UUID request = UUID.randomUUID();
      var fields = new TreeMap<String, String>();
      fields.put("fixture", "lookup-only-absent-original");
      var kind = TerminalPolicy.Action.CONFIGURE.kind();
      absentIntent =
          new Journal.Intent(
              new OperationId(absentOperation),
              new RequestId(request),
              new PlayerId(uuid),
              kind,
              new Journal.CanonicalRequest(1, fields),
              null,
              null);
      var tag = new CompoundTag();
      tag.putUUID("world", services.worldId().value());
      tag.putUUID("actor", uuid);
      tag.putUUID("operation", absentOperation);
      tag.putUUID("request", request);
      tag.putString("service", kind.service());
      tag.putString("type", kind.operationType());
      tag.putInt("version", 1);
      var fieldTag = new CompoundTag();
      fields.forEach(fieldTag::putString);
      tag.put("fields", fieldTag);
      require(
          !player.getPersistentData().contains("economy_banking_pending")
              && !player.getPersistentData().contains("economy_banking_cash_pending"),
          "Actual withdrawal originals already resolved before fixture injection");
      player.getPersistentData().put("economy_banking_pending", tag);
      BankingSaves.player(player, services.worldId(), absentOperation);
      menu.setBusy(false);
      menu.publish(
          "home",
          List.of("Controlled fixture: resolve a saved original before any new request."),
          "Fixture original outcome uncertain",
          List.of());
      absenceInstalled = true;
    } catch (Throwable invalid) {
      failure = "Absent-original injection " + invalid;
    }
  }

  private void verify(MinecraftServer server, UUID uuid) {
    try {
      var player = server.getPlayerList().getPlayer(uuid);
      var services = Economy.serverServices(server);
      var actor = new PlayerId(uuid);
      var grant =
          PartyAuthorization.player(
                  player,
                  Economy::serverServices,
                  new PartyRef(services.worldId(), new PartyRef.Player(actor)))
              .orElseThrow();
      require(
          !player.getPersistentData().contains("economy_banking_pending"),
          "Resolved absent original marker released");
      require(
          player
              .getPersistentData()
              .getCompound("economy_banking_last_absent")
              .getUUID("operation")
              .equals(absentOperation),
          "Absent original saved separately");
      services
          .journal((who, kind) -> who.equals(actor), Map.of(), null)
          .lookup(absentIntent)
          .whenComplete(
              (found, lookupError) -> {
                if (lookupError != null || found.isPresent()) {
                  failure = "Lookup-only path submitted or fabricated an outcome: " + lookupError;
                  return;
                }
                services
                    .ledger()
                    .primaryFunds(grant)
                    .whenComplete(
                        (funds, fundsError) -> {
                          if (fundsError != null
                              || funds.isEmpty()
                              || funds.orElseThrow().funds().availableMinorUnits() != 8_000)
                            failure =
                                "Original lookup changed CAD80 post-withdrawal balance: "
                                    + fundsError;
                          else
                            services
                                .ledger()
                                .dashboard(grant, null, null)
                                .whenComplete(
                                    (dashboard, dashboardError) -> {
                                      if (dashboardError != null
                                          || dashboard.isEmpty()
                                          || dashboard.orElseThrow().recent().rows().stream()
                                              .noneMatch(
                                                  row ->
                                                      row.operation().equals(withdrawalOperation)
                                                          && row.transaction()
                                                              .equals(withdrawalReceipt)
                                                          && row.amount().minorUnits() == -2_000))
                                        failure =
                                            "Captured financial withdrawal identity does not match original posting: "
                                                + dashboardError;
                                      else verified = true;
                                    });
                        });
              });
    } catch (Throwable invalid) {
      failure = "Final dashboard verification " + invalid;
    }
  }

  private void captureFrame(RenderFrameEvent.Post event) {
    if (captures.isEmpty()) return;
    var client = Minecraft.getInstance();
    String name = captures.removeFirst();
    Screenshot.grab(
        client.gameDirectory,
        name,
        client.getMainRenderTarget(),
        message -> {
          screenshots++;
          System.out.println("DASHBOARD_CLIENT_SCREEN " + name + " " + message.getString());
        });
  }

  private void finish(Minecraft client, boolean success) {
    if (stopped) return;
    stopped = true;
    try {
      String evidence =
          success
              ? "PASS fresh integrated client; protocol12 owner dashboard, bound Crownstone/CAD, native primary opening; explicitly seeded CAD100 funding operation "
                  + fundingOperation
                  + "; original recent posting; kiosk restrictions and teller FX/Payments; structured withdrawal REVIEW/explicit confirm/COMPLETED operation "
                  + withdrawalOperation
                  + " receipt "
                  + withdrawalReceipt
                  + "; separately observed CAD20 physical cash; controlled injected absent-original "
                  + absentOperation
                  + " lookup preserved CAD80 and created no outcome. Two post-render screenshots, GUI scale2. Local single-client evidence only; no latency/multiplayer/restart/debt claim.\n"
              : failure + "\n";
      if (success && historyMode)
        evidence =
            "PASS fresh integrated client; protocol12 native History row/detail/filter/date/family widgets; original withdrawal operation "
                + withdrawalOperation
                + " receipt "
                + withdrawalReceipt
                + "; detail/back stable generation/high-water; currency/type/UTC reset generation; EFFECTIVE and DAILY CAD/USD directed history; read-only journal head "
                + historyHead
                + " retained; CAD80 bank and independently observed CAD20 cash. Explicit fixture CAD100 funding "
                + fundingOperation
                + ". Three post-render History screenshots plus reused setup captures, GUI scale2. Local single-client evidence only; no latency/multiplayer/restart/debt claim.\n";
      String prefix = historyMode ? "banking-history" : "banking-dashboard";
      if (fixtureRoot != null)
        Files.writeString(
            fixtureRoot.resolve(prefix + (success ? "-passed.txt" : "-failed.txt")), evidence);
      System.out.println(
          success ? "DASHBOARD_CLIENT_PASS " + evidence : "DASHBOARD_CLIENT_FAILED " + failure);
    } catch (Exception invalid) {
      System.out.println("DASHBOARD_CLIENT_FAILED marker " + invalid);
    }
    var server = client.getSingleplayerServer();
    if (server != null)
      server.execute(
          () -> {
            server.overworld().setChunkForced(KIOSK.getX() >> 4, KIOSK.getZ() >> 4, false);
            server.overworld().setChunkForced(TELLER.getX() >> 4, TELLER.getZ() >> 4, false);
          });
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
    stepStarted = ticks;
    System.out.println("DASHBOARD_CLIENT_STAGE " + value);
  }

  private static boolean offered(BankingMenu menu, String action, String value) {
    return menu.choices().stream()
        .anyMatch(c -> c.action().equals(action) && c.value().equals(value));
  }

  private static long clientCash(Minecraft client) {
    long value = 0;
    for (int slot = 0; slot < 36; slot++) {
      var stack = client.player.getInventory().getItem(slot);
      var denomination = CashCatalog.find(stack).orElse(null);
      if (denomination != null)
        value =
            Math.addExact(value, Math.multiplyExact(denomination.minorUnits(), stack.getCount()));
    }
    return value;
  }

  private String diagnostic(Minecraft client) {
    return "step="
        + step
        + " menu="
        + (client.player != null && client.player.containerMenu instanceof BankingMenu menu
            ? menu.page() + "/" + menu.revision() + "/" + menu.operation().state()
            : "none");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
