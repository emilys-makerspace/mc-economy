package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.banker.dialogue.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.client.*;
import ca.emilybelnavis.mcmods.economy.collection.*;
import ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy;
import ca.emilybelnavis.mcmods.economy.trade.menu.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * Release-excluded native layout fixture. Presentation snapshots confer no server financial
 * authority.
 */
public final class LayoutClient {
  private record Case(
      String name,
      String screen,
      String language,
      int framebufferWidth,
      int framebufferHeight,
      int scale) {}

  private final List<Case> cases =
      Boolean.getBoolean("economy.layoutTerminalCurrent")
          ? List.of(
              new Case("normal-current-home", "current_home", "en_gb", 1280, 720, 2),
              new Case("small-current-home", "current_home", "fr_ca", 960, 720, 3),
              new Case("small-current-accounts", "current_accounts", "fr_ca", 960, 720, 3),
              new Case("small-current-bank-history", "current_bank", "fr_ca", 960, 720, 3),
              new Case("small-current-credit-history", "current_credit", "fr_fr", 960, 720, 3),
              new Case("small-current-withdrawal-sources", "current_sources", "fr_fr", 960, 720, 3),
              new Case("small-current-cash-mix", "current_mix", "fr_ca", 960, 720, 3),
              new Case("small-current-final-review", "current_review", "fr_fr", 960, 720, 3))
          : Boolean.getBoolean("economy.layoutSupplement")
              ? List.of(
                  new Case("supplement-home", "home", "en_gb", 1280, 720, 2),
                  new Case("supplement-accounts", "accounts", "en_gb", 1280, 720, 2),
                  new Case("supplement-tooltip", "collection_tooltip", "fr_ca", 960, 720, 3))
              : List.of(
                  new Case("normal-banking", "banking", "en_gb", 1280, 720, 2),
                  new Case("normal-banker", "banker", "en_gb", 1280, 720, 2),
                  new Case("normal-collection", "collection", "en_gb", 1280, 720, 2),
                  new Case("normal-trader", "trader", "en_gb", 1280, 720, 2),
                  new Case("small-fr-ca-review", "banking", "fr_ca", 960, 720, 3),
                  new Case("small-fr-ca-banker", "banker", "fr_ca", 960, 720, 3),
                  new Case("small-fr-ca-collection", "collection", "fr_ca", 960, 720, 3),
                  new Case("small-fr-ca-trader", "trader", "fr_ca", 960, 720, 3),
                  new Case("small-fr-ca-history", "history", "fr_ca", 960, 720, 3),
                  new Case("small-fr-ca-history-detail", "history_detail", "fr_ca", 960, 720, 3),
                  new Case("small-fr-fr-review", "banking", "fr_fr", 960, 720, 3),
                  new Case("stale-review", "stale", "fr_fr", 960, 720, 3),
                  new Case("draft-normal", "draft", "fr_fr", 1280, 720, 2),
                  new Case("draft-small", "resize", "fr_fr", 960, 720, 3),
                  new Case("draft-large", "resize", "fr_fr", 1920, 1080, 3));
  private final long started = System.nanoTime();
  private final StringBuilder evidence =
      new StringBuilder(
          "Native layout-only presentation fixture; no service origination, accounting, socket multiplayer or financial confirmation.\n");
  private Path root;
  private boolean opened, finished, requested, interacted;
  private int worldTicks,
      index = Integer.getInteger("economy.layoutStartCase", 0),
      wait,
      captures,
      caseStartCaptures;
  private String capture;
  private CompletableFuture<?> reload;
  private Screen current;
  private BankingMenu draftMenu, reviewMenu;
  private Button originalConfirm;
  private BankingOperation.View originalReview;
  private String failure;
  private Button hoveredChoice;
  private double pointerX, pointerY;

  public LayoutClient() {
    if (!Boolean.getBoolean("economy.layoutProbe"))
      throw new IllegalStateException("Explicit layout fixture required");
    require(index >= 0 && index < cases.size(), "Layout start index in finite matrix");
    evidence
        .append("Start case index=")
        .append(index)
        .append(
            "; earlier cases, if skipped, require separately recorded prior native evidence.\n");
    NeoForge.EVENT_BUS.addListener(this::tick);
    NeoForge.EVENT_BUS.addListener(this::frame);
  }

  private void tick(ClientTickEvent.Post event) {
    if (finished) return;
    var client = Minecraft.getInstance();
    client.options.pauseOnLostFocus = false;
    try {
      if (System.nanoTime() - started > 360_000_000_000L)
        throw new IllegalStateException("Layout fixture deadline case=" + index);
      if (!opened && client.getOverlay() == null && client.screen != null && client.level == null) {
        opened = true;
        root =
            DisposableProbeWorld.requireClientDirectory(
                client.gameDirectory.toPath(), "economy-a10-03-");
        String name = "economy-layout-" + UUID.randomUUID();
        DisposableProbeWorld.requireFreshWorld(root, name);
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
                new WorldOptions(100300L, false, false),
                WorldPresets::createNormalWorldDimensions,
                client.screen);
      }
      if (client.player == null || client.getSingleplayerServer() == null) return;
      if (client.player.containerMenu instanceof BankingMenu setup && setup.worldSetup()) {
        if (!setup.busy() && worldTicks++ % 20 == 0) BankingNetwork.action(setup, "setup", "CAD");
        return;
      }
      if (++worldTicks < 60) return;
      if (index >= cases.size()) {
        finish(client, true);
        return;
      }
      var c = cases.get(index);
      if (!requested) {
        requested = true;
        wait = 0;
        interacted = false;
        caseStartCaptures = captures;
        if (!client.options.languageCode.equals(c.language())) {
          client.options.languageCode = c.language();
          client.getLanguageManager().setSelected(c.language());
          reload = client.reloadResourcePacks();
        }
        resize(client, c);
        return;
      }
      if (reload != null) {
        if (!reload.isDone() || client.getOverlay() != null) return;
        reload.join();
        reload = null;
      }
      if (++wait == 8) {
        require(
            client.getWindow().getGuiScaledWidth() >= 320
                && client.getWindow().getGuiScaledHeight() >= 240,
            "Minimum logical viewport available");
        if (c.name().startsWith("small")
            || c.name().equals("draft-small")
            || c.screen().equals("collection_tooltip"))
          require(
              client.getWindow().getGuiScaledWidth() == 320
                  && client.getWindow().getGuiScaledHeight() == 240,
              "Exact 320x240 boundary");
        if (!c.screen().equals("resize")) {
          current = screen(client, c);
          client.setScreen(current);
        } else require(client.screen == current, "Resize keeps screen instance");
      }
      if (wait == 18) {
        require(client.screen == current, "Fixture remains on selected screen");
        checkWidgets(current);
        focus(current);
        if (c.screen().equals("draft")) editBox(current).setValue("1234.50");
        if (c.screen().equals("resize"))
          require(editBox(current).getValue().equals("1234.50"), "Draft survives resize");
        String dimensions = dimensions(client, c);
        evidence.append(dimensions).append('\n');
        System.out.println("LAYOUT_CLIENT_CASE " + dimensions);
        capture = "layout-" + c.name() + ".png";
      }
      if (wait == 28 && !interacted) {
        interacted = true;
        if (c.screen().startsWith("current_")) reachCurrentEnd(current, c);
        else reachEnd(current);
        boolean scroll = current.mouseScrolled(current.width / 2.0, current.height / 2.0, 0, -1);
        current.keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0);
        if (c.screen().equals("stale")) invalidateReview(c);
        if (c.screen().equals("collection_tooltip")) hoverChoice(client, current);
        checkWidgets(current);
        evidence
            .append(c.name())
            .append(
                " Tab focus; keyboard scroll activation; repeated body scroll and final choice page reached; mouseHandled=")
            .append(scroll)
            .append('\n');
        if (!c.screen().equals("stale") && !c.screen().equals("collection_tooltip"))
          capture = "layout-" + c.name() + "-scroll.png";
      }
      if (wait == 50 && c.screen().equals("collection_tooltip")) {
        double mouseX =
            client.mouseHandler.xpos() * current.width / client.getWindow().getScreenWidth();
        double mouseY =
            client.mouseHandler.ypos() * current.height / client.getWindow().getScreenHeight();
        String pointer =
            "Tooltip desired logical="
                + pointerX
                + ","
                + pointerY
                + " actual="
                + mouseX
                + ","
                + mouseY
                + " hovered="
                + hoveredChoice.isHovered()
                + " windowActive="
                + client.isWindowActive();
        evidence.append(pointer).append('\n');
        System.out.println("LAYOUT_CLIENT_POINTER " + pointer);
        require(
            hoveredChoice.getTooltip() != null && hoveredChoice.isHovered(),
            "Actual widget hovered with full tooltip before capture");
        capture = "layout-" + c.name() + "-hover.png";
        evidence.append(
            "Minimum collection long-choice tooltip hovered for22ticks before completed-frame capture; visual bounds require screenshot inspection.\n");
      }
      if (wait == 34 && c.screen().equals("stale")) {
        require(!reviewed(current), "Expired revision invalidates the retained review capability");
        require(
            reviewMenu.operation().reviewId() == null,
            "Expired presentation has no review capability");
        require(
            control(current, text("screen.economy.action.withdraw_confirm", c.language()))
                .isEmpty(),
            "No active confirmation survives stale revision");
        capture = "layout-" + c.name() + "-scroll.png";
        evidence.append(
            "Retained original confirm from REVIEW; new EXPIRED_STALE revision received; after six ticks reviewed() is false, no financial action sent.\n");
        checkWidgets(current);
      }
      if (wait >= (c.screen().equals("collection_tooltip") ? 60 : 40)
          && capture == null
          && captures >= caseStartCaptures + 2) {
        index++;
        requested = false;
      }
    } catch (Throwable invalid) {
      invalid.printStackTrace();
      failure = invalid.toString();
      finish(client, false);
    }
  }

  private static void resize(Minecraft client, Case c) {
    var window = client.getWindow();
    int[] w = {0}, h = {0};
    GLFW.glfwGetWindowSize(window.getWindow(), w, h);
    double ratioX = (double) window.getWidth() / Math.max(1, w[0]),
        ratioY = (double) window.getHeight() / Math.max(1, h[0]);
    GLFW.glfwSetWindowSize(
        window.getWindow(),
        (int) Math.round(c.framebufferWidth() / ratioX),
        (int) Math.round(c.framebufferHeight() / ratioY));
    GLFW.glfwPollEvents();
    client.options.guiScale().set(c.scale());
    client.resizeDisplay();
  }

  private static String dimensions(Minecraft client, Case c) {
    var w = client.getWindow();
    int[] width = {0}, height = {0};
    GLFW.glfwGetWindowSize(w.getWindow(), width, height);
    return c.name()
        + " language="
        + c.language()
        + " window="
        + width[0]
        + "x"
        + height[0]
        + " framebuffer="
        + w.getWidth()
        + "x"
        + w.getHeight()
        + " scale="
        + w.getGuiScale()
        + " logical="
        + w.getGuiScaledWidth()
        + "x"
        + w.getGuiScaledHeight();
  }

  private Screen screen(Minecraft client, Case c) {
    if (c.screen().startsWith("current_")) return terminalCurrentScreen(client, c);
    var inventory = client.player.getInventory();
    UUID session = UUID.randomUUID();
    var lines = terms(c.language());
    String status = lines.get(4);
    String label = text("ui.finance.autopaymenus.36", c.language());
    switch (c.screen()) {
      case "banker" -> {
        var m = new BankerMenu(82, inventory, UUID.randomUUID(), UUID.randomUUID(), 1, session);
        var controls = new ArrayList<BankerNetwork.Choice>();
        for (int n = 0; n < 8; n++)
          controls.add(
              new BankerNetwork.Choice("ask", "question" + n, (n + 1) + ". " + label, n == 0));
        controls.add(
            new BankerNetwork.Choice("banking", "", text("ui.client.back", c.language()), false));
        m.apply(
            new BankerNetwork.Snapshot(82, session, 1, "answer", lines, status, false, controls));
        client.player.containerMenu = m;
        return new BankerScreen(m, inventory, Component.literal("Crownstone Bank"));
      }
      case "collection", "collection_tooltip" -> {
        var m = new CollectionMenu(83, inventory, UUID.randomUUID(), UUID.randomUUID(), session);
        var controls = new ArrayList<CollectionNetwork.Choice>();
        for (int n = 0; n < 8; n++)
          controls.add(
              new CollectionNetwork.Choice(
                  "select_funding", "source" + n, (n + 1) + ". " + label, n == 2));
        controls.add(
            new CollectionNetwork.Choice(
                "preview_partial", "", text("ui.client.contract_payment", c.language()), false));
        controls.add(
            new CollectionNetwork.Choice(
                "preview_full", "", text("ui.finance.autopaymenus.29", c.language()), false));
        m.apply(
            new CollectionNetwork.Snapshot(
                83, session, 1, "payment", lines, status, false, controls));
        client.player.containerMenu = m;
        return new CollectionScreen(m, inventory, Component.literal("Collection"));
      }
      case "trader" -> {
        var m = new TraderMenu(84, inventory, UUID.randomUUID(), session);
        var controls = new ArrayList<TraderNetwork.Choice>();
        for (int n = 0; n < 12; n++)
          controls.add(
              new TraderNetwork.Choice("account", "source" + n, (n + 1) + ". " + label, n == 1));
        controls.add(
            new TraderNetwork.Choice(
                "confirm", "", text("ui.client.review_mix", c.language()), false));
        controls.add(
            new TraderNetwork.Choice("cancel", "", text("ui.client.back", c.language()), false));
        m.apply(
            new TraderNetwork.Snapshot(84, session, 1, "review", lines, status, false, controls));
        client.player.containerMenu = m;
        return new TraderScreen(m, inventory, Component.literal("Trader"));
      }
      default -> {
        var m =
            new BankingMenu(
                81,
                inventory,
                new TerminalPolicy.Anchor("minecraft:overworld", 0, 80, 0),
                UUID.randomUUID(),
                session,
                BankingMenu.Access.TELLER);
        var controls = new ArrayList<BankingNetwork.Choice>();
        String page = "finance_loan_offer";
        if (c.screen().equals("home")) {
          page = "home";
          controls.add(
              new BankingNetwork.Choice(
                  "accounts", "", text("screen.economy.tab.accounts", c.language()), false));
          controls.add(
              new BankingNetwork.Choice(
                  "payments", "", text("screen.economy.tab.payments", c.language()), false));
          controls.add(
              new BankingNetwork.Choice(
                  "history", "", text("screen.economy.tab.history", c.language()), false));
          controls.add(
              new BankingNetwork.Choice(
                  "home", "", text("screen.economy.refresh", c.language()), false));
        } else if (c.screen().equals("accounts")) {
          page = "accounts";
          for (int n = 0; n < 8; n++)
            controls.add(
                new BankingNetwork.Choice(
                    "account_select",
                    UUID.randomUUID().toString(),
                    (n + 1) + ". CAD · Crownstone Bank · " + label,
                    n == 2));
        } else if (c.screen().equals("draft")) {
          page = "withdraw";
          draftMenu = m;
          controls.add(
              new BankingNetwork.Choice(
                  "account_select", UUID.randomUUID().toString(), "CAD · Crownstone Bank", true));
          controls.add(
              new BankingNetwork.Choice(
                  "withdraw_preview",
                  "",
                  text("screen.economy.action.withdraw_preview", c.language()),
                  false));
        } else if (c.screen().equals("history_detail")) {
          page = "history_detail";
          controls.add(
              new BankingNetwork.Choice(
                  "history_back", "", text("screen.economy.history_back", c.language()), false));
        } else if (c.screen().equals("history")) {
          page = "history_filters";
          for (int n = 0; n < 12; n++)
            controls.add(
                new BankingNetwork.Choice(
                    "history_type", "type" + n, (n + 1) + ". " + label, n == 1));
        } else {
          controls.add(
              new BankingNetwork.Choice(
                  "finance_loan_confirm",
                  "",
                  text("screen.economy.action.withdraw_confirm", c.language()),
                  false));
          controls.add(
              new BankingNetwork.Choice(
                  "finance_loan_home", "", text("ui.client.back", c.language()), false));
        }
        m.applyClientSnapshot(
            1,
            page,
            lines,
            status,
            false,
            controls,
            Set.of("draft", "history", "history_detail", "home", "accounts").contains(c.screen())
                ? BankingOperation.View.ready()
                : BankingOperation.View.of(
                    BankingOperation.State.REVIEW, "screen.economy.operation.review"),
            null);
        if (c.screen().equals("stale")) {
          reviewMenu = m;
          originalReview = m.operation();
        }
        client.player.containerMenu = m;
        return new BankingScreen(m, inventory, Component.literal("Crownstone Bank · CAD"));
      }
    }
  }

  private Screen terminalCurrentScreen(Minecraft client, Case c) {
    var inventory = client.player.getInventory();
    var menu =
        new BankingMenu(
            81,
            inventory,
            new TerminalPolicy.Anchor("minecraft:overworld", 0, 80, 0),
            UUID.randomUUID(),
            UUID.randomUUID(),
            BankingMenu.Access.TELLER);
    var lines = new ArrayList<String>();
    var choices = new ArrayList<BankingNetwork.Choice>();
    String page;
    var operation = BankingOperation.View.ready();
    switch (c.screen()) {
      case "current_home" -> {
        page = "home";
        lines.add("Crownstone Bank · CAD");
        for (String[] entry :
            List.of(
                new String[] {"accounts", "my_accounts"},
                new String[] {"deposit", "deposit"},
                new String[] {"withdraw", "withdrawal"},
                new String[] {"payments", "bill_payments"},
                new String[] {"manage_accounts", "branch_services"}))
          choices.add(
              new BankingNetwork.Choice(
                  entry[0], "", text("ui.terminal." + entry[1], c.language()), false));
      }
      case "current_accounts", "current_sources" -> {
        page = c.screen().equals("current_accounts") ? "terminal_accounts" : "withdraw_sources";
        lines.add(
            text(
                c.screen().equals("current_sources")
                    ? "ui.terminal.withdraw.choose"
                    : "ui.terminal.account.settings",
                c.language()));
        for (int n = 0; n < 8; n++) {
          String label =
              (n + 1)
                  + ". "
                  + text("ui.terminal.account.bank", c.language(), "CAD")
                  + " · Crownstone Bank · "
                  + text("ui.terminal.account.settings", c.language());
          choices.add(
              new BankingNetwork.Choice(
                  c.screen().equals("current_accounts")
                      ? "terminal_account"
                      : "terminal_withdraw_bank",
                  UUID.randomUUID().toString(),
                  label,
                  n == 2));
        }
      }
      case "current_bank", "current_credit" -> {
        page = "account_overview";
        boolean credit = c.screen().equals("current_credit");
        lines.add(UiText.money(new Money(EconomyCurrency.CAD, credit ? 54321 : 123456)));
        lines.add(UiText.money(new Money(EconomyCurrency.CAD, credit ? 195679 : 98765)));
        lines.add(
            UiText.t(
                credit ? "ui.terminal.account.pending" : "ui.terminal.account.holds",
                new Money(EconomyCurrency.CAD, credit ? 0 : 24691)));
        String[] activities = {"withdrawal", "fx", "payment", "advance_fee", "interest"};
        for (int n = 0; n < 5; n++)
          lines.add(
              UiText.t(
                  "ui.terminal.history.movement",
                  UiText.t(n % 2 == 0 ? "ui.terminal.history.debit" : "ui.terminal.history.credit"),
                  UiText.t("ui.terminal.activity." + activities[n]),
                  new Money(EconomyCurrency.CAD, 12345 + n * 100)));
        lines.add(UiText.t("ui.terminal.history.page", 12, 24));
        choices.add(new BankingNetwork.Choice("terminal_history_prev", "", "←", false));
        choices.add(new BankingNetwork.Choice("terminal_history_next", "", "→", false));
        for (String line : lines) {
          String rendered = UiTextClient.render(line);
          require(
              rendered.indexOf('\u001e') < 0
                  && rendered.indexOf('\u001f') < 0
                  && !rendered.contains("ui.terminal.")
                  && rendered.length() <= 240,
              "Typed account rows resolve within published bounds before rendering");
        }
        require(UiTextClient.render(lines.get(7)).contains("CAD"), "Fifth row retains currency");
      }
      case "current_mix" -> {
        page = "withdraw_mix";
        var denominations =
            ca.emilybelnavis.mcmods.economy.cash.CashCatalog.all().stream()
                .filter(d -> d.currency() == EconomyCurrency.CAD && !d.id().endsWith("_horizontal"))
                .sorted(
                    Comparator.comparingLong(
                            ca.emilybelnavis.mcmods.economy.cash.CashCatalog.Denomination
                                ::minorUnits)
                        .reversed())
                .limit(8)
                .toList();
        require(denominations.size() == 8, "Eight current cash denominations");
        lines.add(text("ui.bank.selected.ae3c35", c.language()) + textMoney(c.language(), 0));
        lines.add(text("ui.bank.remaining.5c9d2e", c.language()) + textMoney(c.language(), 6000));
        for (var denomination : denominations)
          choices.add(
              new BankingNetwork.Choice(
                  "withdraw_mix_change",
                  "economy:" + denomination.id(),
                  textMoney(c.language(), denomination.minorUnits()) + " × 0",
                  false));
      }
      case "current_review" -> {
        page = "withdraw";
        lines.add(text("ui.bank.cash.f016dc", c.language()) + textMoney(c.language(), 6000));
        lines.add(text("ui.bank.bank_debit.c90280", c.language()) + textMoney(c.language(), 6000));
        int[] counts = {1, 2, 2, 3, 3, 4};
        long[] denominations = {2000, 1000, 500, 200, 100, 25};
        for (int n = 0; n < counts.length; n++)
          lines.add(counts[n] + " × " + textMoney(c.language(), denominations[n]));
        lines.add(
            text("ui.bank.the_entire_batch_needs.37a744", c.language())
                + 6
                + text("ui.bank.free_inventory_slots.e38c98", c.language()));
        lines.add(
            text("ui.bank.choose_confirm_withdrawal_to_debit_and_receive.7bc402", c.language()));
        choices.add(
            new BankingNetwork.Choice(
                "account_select",
                UUID.randomUUID().toString(),
                text("ui.terminal.account.bank", c.language(), "CAD") + " · Crownstone Bank",
                true));
        choices.add(
            new BankingNetwork.Choice(
                "withdraw_confirm",
                "60.00",
                text("screen.economy.action.withdraw_confirm", c.language()),
                false));
        choices.add(
            new BankingNetwork.Choice(
                "withdraw_mix", "", text("ui.client.cash_select", c.language()), false));
        operation =
            BankingOperation.View.of(
                BankingOperation.State.REVIEW, "screen.economy.operation.review");
      }
      default -> throw new IllegalStateException("Finite current terminal case");
    }
    menu.applyClientSnapshot(1, page, lines, "", false, choices, operation, null);
    client.player.containerMenu = menu;
    return new BankingScreen(menu, inventory, Component.literal("Crownstone Bank · CAD"));
  }

  private static String textMoney(String language, long minor) {
    return UiText.render(UiText.money(new Money(EconomyCurrency.CAD, minor)), language);
  }

  private void reachCurrentEnd(Screen screen, Case c) {
    // Only scroll/focus widgets are activated. Offered financial and page actions are inspected,
    // never sent by this presentation fixture.
    var down = control(screen, "▼");
    if (down.isPresent()) {
      var target = down.orElseThrow();
      for (int n = 0; n < screen.children().size() * 2 && screen.getFocused() != target; n++)
        screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
      require(screen.getFocused() == target, "Current terminal scroll is keyboard reachable");
      screen.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0);
      for (int n = 0; n < 100; n++) control(screen, "▼").ifPresent(Button::onPress);
    }
    screen.keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0);
    if (c.screen().equals("current_mix")) {
      require(scrollValue(screen) == 3, "Final cash denomination row reached");
      require(
          control(screen, text("ui.client.review_mix", c.language())).isPresent(),
          "Final cash review reachable");
      require(
          control(screen, text("ui.client.default_mix", c.language())).isPresent(),
          "Default mix reachable");
    }
    if (c.screen().equals("current_accounts") || c.screen().equals("current_sources"))
      require(scrollValue(screen) == 2, "Last of eight current account choices reached");
    if (c.screen().equals("current_bank") || c.screen().equals("current_credit")) {
      require(
          control(screen, "←").isPresent() && control(screen, "→").isPresent(),
          "Account history footer controls reachable");
      try {
        var label = BankingScreen.class.getDeclaredMethod("terminalLabel", String.class);
        label.setAccessible(true);
        require(
            label.invoke(null, "Available").equals(text("ui.terminal.available", c.language())),
            "Generic localized Available heading");
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }
    if (c.screen().equals("current_review"))
      require(
          reviewed(screen)
              && control(screen, text("screen.economy.action.withdraw_confirm", c.language()))
                  .isPresent(),
          "Final reviewed withdrawal confirmation reachable without sending");
    evidence
        .append(c.name())
        .append(" current terminal scroll/final controls checked; no offered action sent.\n");
  }

  private static int scrollValue(Screen screen) {
    try {
      var field = BankingScreen.class.getDeclaredField("scroll");
      field.setAccessible(true);
      return field.getInt(screen);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String text(String key, String language, Object... args) {
    return UiText.render(UiText.t(key, args), language);
  }

  private static List<String> terms(String language) {
    var lines = new ArrayList<String>();
    lines.add(text("ui.finance.loanmenus.36", language, new Money(EconomyCurrency.CAD, 123456), 5));
    lines.add(text("ui.finance.loanmenus.37", language, new java.math.BigDecimal("5.75")));
    lines.add(text("ui.finance.loanmenus.38", language, new Money(EconomyCurrency.CAD, 3210)));
    lines.add(text("ui.finance.loanmenus.39", language, "2026-11-09T12:00:00Z"));
    for (String key :
        List.of(
            "ui.finance.loanmenus.40",
            "ui.finance.loanmenus.41",
            "ui.finance.autopaymenus.32",
            "ui.finance.autopaymenus.33",
            "ui.finance.autopaymenus.34",
            "ui.finance.autopaymenus.35",
            "ui.finance.autopaymenus.36",
            "ui.finance.autopaymenus.37",
            "ui.finance.autopaymenus.38")) {
      String message = text(key, language);
      for (int p = 0; p < message.length(); p += 240)
        lines.add(message.substring(p, Math.min(message.length(), p + 240)));
    }
    require(lines.size() <= 16, "Synthetic terms retain actual snapshot bounds");
    return List.copyOf(lines);
  }

  private static Optional<Button> control(Screen screen, String label) {
    return screen.children().stream()
        .filter(Button.class::isInstance)
        .map(Button.class::cast)
        .filter(b -> b.active && b.visible && b.getMessage().getString().equals(label))
        .findFirst();
  }

  private static void reachEnd(Screen screen) {
    var down = control(screen, "▼").or(() -> control(screen, "↓"));
    require(down.isPresent(), "Focusable body scrolling control present");
    Button target = down.orElseThrow();
    for (int n = 0; n < screen.children().size() * 2 && screen.getFocused() != target; n++)
      screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
    require(screen.getFocused() == target, "Tab reaches body scrolling control");
    require(screen.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0), "Keyboard activates scroll control");
    for (int n = 0; n < 100; n++) {
      var next = control(screen, "▼").or(() -> control(screen, "↓"));
      if (next.isEmpty()) break;
      next.orElseThrow().onPress();
    }
    if (!(screen instanceof BankingScreen))
      require(
          screen.children().stream()
              .filter(Button.class::isInstance)
              .map(Button.class::cast)
              .anyMatch(b -> b.visible && b.getMessage().getString().equals("↓") && !b.active),
          "Folio downward scroll disables at final content row");
    // Folios expose separate four-choice pages; reach the final page without invoking an offered
    // action.
    for (int n = 0; n < 8; n++) {
      var next = control(screen, "→");
      if (next.isEmpty()) break;
      next.orElseThrow().onPress();
    }
  }

  private void hoverChoice(Minecraft client, Screen screen) {
    for (int n = 0; n < 8; n++) {
      var previous = control(screen, "←");
      if (previous.isEmpty()) break;
      previous.orElseThrow().onPress();
    }
    var choice =
        screen.children().stream()
            .filter(Button.class::isInstance)
            .map(Button.class::cast)
            .filter(b -> b.visible && b.active && b.getMessage().getString().startsWith("1. "))
            .findFirst()
            .orElseThrow();
    int[] w = {0}, h = {0};
    GLFW.glfwGetWindowSize(client.getWindow().getWindow(), w, h);
    hoveredChoice = choice;
    pointerX = choice.getX() + choice.getWidth() / 2.0;
    pointerY = choice.getY() + choice.getHeight() / 2.0;
    double x = pointerX * client.getWindow().getScreenWidth() / screen.width;
    double y = pointerY * client.getWindow().getScreenHeight() / screen.height;
    GLFW.glfwFocusWindow(client.getWindow().getWindow());
    GLFW.glfwSetCursorPos(client.getWindow().getWindow(), x, y);
    GLFW.glfwPollEvents();
    // Route the fixture pointer through the mapped native mouse callback as well: a background
    // macOS GLFW window can omit a synthesized OS cursor callback. Rendering remains unmodified.
    try {
      var move =
          net.minecraft.client.MouseHandler.class.getDeclaredMethod(
              "onMove", long.class, double.class, double.class);
      move.setAccessible(true);
      move.invoke(client.mouseHandler, client.getWindow().getWindow(), x, y);
    } catch (ReflectiveOperationException invalid) {
      throw new IllegalStateException("Fixture native mouse routing", invalid);
    }
    client.setLastInputType(net.minecraft.client.InputType.MOUSE);
    client.mouseHandler.handleAccumulatedMovement();
    evidence.append(
        "Tooltip pointer: GLFWfocus/cursor plus mapped MouseHandler.onMove fixture routing; no manual tooltip render.\n");
  }

  private void invalidateReview(Case c) {
    require(reviewed(current), "Original REVIEW is usable before invalidation");
    var actions = control(current, text("ui.client.actions", c.language())).orElseThrow();
    actions.onPress();
    String confirm = text("screen.economy.action.withdraw_confirm", c.language());
    originalConfirm = control(current, confirm).orElseThrow();
    require(originalConfirm.active, "Original confirm reachable through Actions");
    require(
        control(current, text("ui.client.back", c.language())).isPresent(),
        "Back reachable alongside confirmation");
    reviewMenu.applyClientSnapshot(
        2,
        reviewMenu.page(),
        reviewMenu.lines(),
        text("ui.common.review_expired", c.language()),
        false,
        reviewMenu.choices(),
        BankingOperation.View.of(
            BankingOperation.State.EXPIRED_STALE, "screen.economy.operation.expired_stale"),
        null);
    require(!reviewed(current), "Original review refused immediately on revision/state change");
    require(
        originalReview.reviewId() != null, "Original review identity retained in fixture evidence");
  }

  private static boolean reviewed(Screen screen) {
    try {
      var method = BankingScreen.class.getDeclaredMethod("reviewed");
      method.setAccessible(true);
      return (boolean) method.invoke(screen);
    } catch (ReflectiveOperationException invalid) {
      throw new IllegalStateException("Layout fixture review inspection", invalid);
    }
  }

  private static EditBox editBox(Screen screen) {
    return screen.children().stream()
        .filter(EditBox.class::isInstance)
        .map(EditBox.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private static void focus(Screen screen) {
    screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
    require(screen.getFocused() != null, "Tab obtains focus");
  }

  private static void checkWidgets(Screen screen) {
    var widgets =
        screen.children().stream()
            .filter(AbstractWidget.class::isInstance)
            .map(AbstractWidget.class::cast)
            .filter(w -> w.visible)
            .toList();
    for (var w : widgets)
      require(
          w.getX() >= 0
              && w.getY() >= 0
              && w.getX() + w.getWidth() <= screen.width
              && w.getY() + w.getHeight() <= screen.height,
          "Widget in logical viewport: " + w.getMessage().getString());
    for (int a = 0; a < widgets.size(); a++)
      for (int b = a + 1; b < widgets.size(); b++) {
        var x = widgets.get(a);
        var y = widgets.get(b);
        require(
            x.getX() + x.getWidth() <= y.getX()
                || y.getX() + y.getWidth() <= x.getX()
                || x.getY() + x.getHeight() <= y.getY()
                || y.getY() + y.getHeight() <= x.getY(),
            "Visible widgets overlap: "
                + x.getMessage().getString()
                + " / "
                + y.getMessage().getString());
      }
  }

  private void frame(RenderFrameEvent.Post event) {
    if (capture == null || finished) return;
    var client = Minecraft.getInstance();
    String name = capture;
    capture = null;
    Screenshot.grab(
        client.gameDirectory,
        name,
        client.getMainRenderTarget(),
        message -> {
          captures++;
          System.out.println("LAYOUT_CLIENT_SCREEN " + name + " " + message.getString());
        });
  }

  private void finish(Minecraft client, boolean success) {
    if (finished) return;
    finished = true;
    try {
      if (root != null)
        Files.writeString(
            root.resolve(success ? "layout-passed.txt" : "layout-failed.txt"),
            (success ? "PASS\n" : "FAILED " + failure + "\n") + evidence);
    } catch (Exception invalid) {
      System.out.println("LAYOUT_CLIENT_MARKER_FAILED " + invalid);
    }
    System.out.println(
        success
            ? "LAYOUT_CLIENT_PASS " + captures + " completed render frames"
            : "LAYOUT_CLIENT_FAILED " + failure);
    client.execute(client::stop);
  }

  private static void require(boolean result, String detail) {
    if (!result) throw new IllegalStateException(detail);
  }
}
