package ca.emilybelnavis.mcmods.economy.banking;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.cash.WithdrawalInventory;
import ca.emilybelnavis.mcmods.economy.foundation.BankingAccounts;
import ca.emilybelnavis.mcmods.economy.foundation.LedgerService;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/** Live menu orchestration around the exact, journaled withdrawal service. */
public final class BankingWithdrawals {
  private static final Set<String> ACTIONS =
      Set.of(
          "withdraw",
          "withdraw_preview",
          "withdraw_confirm",
          "withdraw_mix",
          "withdraw_mix_change",
          "withdraw_mix_review",
          "withdraw_mix_reset");
  private static final Map<BankingMenu, State> STATES = new WeakHashMap<>();

  private static final class State {
    Withdrawals.Preview preview;
    WorldId world;
    List<BankingNetwork.Choice> accountChoices = List.of();
    final Map<String, Integer> mix = new TreeMap<>();
    List<WithdrawalPlanner.Denomination> mixDenominations = List.of();
    long previewRevision;
    UUID reservation;
    boolean committing;
    boolean cancelling;
    boolean complete;
  }

  record Parsed(Money cash, Map<String, Integer> edits) {}

  private BankingWithdrawals() {}

  public static boolean handles(String action) {
    return ACTIONS.contains(action);
  }

  public static boolean canChangeAccount(BankingMenu menu) {
    var state = STATES.get(menu);
    return state == null || (state.reservation == null || state.complete) && !state.cancelling;
  }

  public static void action(ServerPlayer player, BankingMenu menu, String action, String value) {
    if (!handles(action) || !menu.valid(player) || menu.busy()) return;
    if (value == null || value.getBytes(StandardCharsets.UTF_8).length > 2048) return;
    var state = STATES.computeIfAbsent(menu, ignored -> new State());
    try {
      switch (action) {
        case "withdraw" -> {
          if (state.reservation != null && !state.complete)
            throw new IllegalStateException("Pending withdrawal");
          state.preview = null;
          if (state.complete) {
            state.reservation = null;
            state.complete = false;
            state.committing = false;
          }
          BankingAccounts.cashAccounts(
              player,
              menu,
              "withdraw",
              List.of(
                  UiText.t("ui.bank.select_the_account_to_withdraw_from_then_enter.f4b7eb"),
                  UiText.t("ui.bank.choose_review_to_see_the_cash_and_account_tota.f7884e"),
                  UiText.t("ui.bank.atms_dispense_bills_tellers_can_also_dispense_.72d12d")),
              UiText.t("ui.bank.enter_an_amount_to_continue.b9c1c0"));
        }
        case "withdraw_preview" -> preview(player, menu, state, value);
        case "withdraw_confirm" -> confirm(player, menu, state);
        case "withdraw_mix" -> {
          if (state.preview == null || state.reservation != null || !menu.page().equals("withdraw"))
            return;
          showMix(player, menu, state);
        }
        case "withdraw_mix_change" -> {
          if (!menu.page().equals("withdraw_mix")
              || state.preview == null
              || state.reservation != null) return;
          var fields = value.split(";", -1);
          if (fields.length != 2
              || state.mixDenominations.stream().noneMatch(d -> d.id().equals(fields[0]))) return;
          int delta = Integer.parseInt(fields[1]);
          if (delta != -1 && delta != 1) return;
          int count =
              Math.clamp(
                  state.mix.getOrDefault(fields[0], 0) + delta, 0, WithdrawalPlanner.MAX_ITEMS);
          state.mix.put(fields[0], count);
          showMix(player, menu, state);
        }
        case "withdraw_mix_review", "withdraw_mix_reset" -> {
          if (!menu.page().equals("withdraw_mix")
              || state.preview == null
              || state.reservation != null) return;
          String amount = state.preview.cash().majorUnits().toPlainString();
          if (action.equals("withdraw_mix_review")) {
            amount +=
                ";"
                    + state.mix.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(java.util.stream.Collectors.joining(","));
          }
          preview(player, menu, state, amount);
        }
        default -> {}
      }
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException invalid) {
      show(
          player,
          menu,
          List.of(
              UiText.t("ui.bank.enter_a_positive_amount_for_the_selected_accou.c4850b"),
              UiText.t("ui.bank.nothing_was_taken_from_your_account.a5f2e2")),
          UiText.t("ui.bank.check_the_amount_and_try_again.7a6dde"));
    }
  }

  static Parsed parse(String value) {
    var parts = value.trim().split(";", -1);
    if (parts.length > 2) throw new IllegalArgumentException("Withdrawal fields");
    var amountFields = parts[0].trim().split("\\s+", -1);
    if (amountFields.length != 2 || amountFields[1].length() > 32)
      throw new IllegalArgumentException("Withdrawal amount");
    var currency = EconomyCurrency.fromCode(amountFields[0].toUpperCase(Locale.ROOT));
    long minor =
        new BigDecimal(amountFields[1]).movePointRight(currency.fractionDigits()).longValueExact();
    if (minor <= 0) throw new IllegalArgumentException("Positive cash amount required");
    Map<String, Integer> edits = null;
    if (parts.length == 2) {
      var values = new TreeMap<String, Integer>();
      if (!parts[1].isBlank()) {
        var lines = parts[1].split(",", -1);
        if (lines.length > WithdrawalPlanner.MAX_DENOMINATIONS)
          throw new IllegalArgumentException("Bill edit bound");
        for (var line : lines) {
          var fields = line.trim().split("=", -1);
          if (fields.length != 2
              || fields[0].length() > 96
              || !fields[0].matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("Bill edit identity");
          int count = Integer.parseInt(fields[1]);
          if (count < 0
              || count > WithdrawalPlanner.MAX_ITEMS
              || values.putIfAbsent(fields[0], count) != null)
            throw new IllegalArgumentException("Bill edit quantity");
        }
      }
      edits = Collections.unmodifiableMap(values);
    }
    return new Parsed(new Money(currency, minor), edits);
  }

  private static void preview(ServerPlayer player, BankingMenu menu, State state, String value) {
    var services = BankingController.services(player);
    var actor = BankingController.actor(player);
    long revision = menu.revision();
    var session = menu.session();
    var offeredAccounts =
        menu.choices().stream().filter(c -> c.action().equals("account_select")).toList();
    if (!offeredAccounts.isEmpty()) state.accountChoices = offeredAccounts;
    var previousPreview = state.preview;
    state.preview = null;
    menu.setBusy(true);
    services
        .ledger()
        .bankingContext(actor, menu.anchor(), BankingAccounts.selected(menu))
        .whenComplete(
            (context, error) -> {
              if (!live(player, menu, session, revision)) return;
              if (error != null || !usable(context, menu)) {
                unavailable(player, menu);
                return;
              }
              final Parsed parsed;
              try {
                parsed = parse(cashCurrency(context.terminal()).code() + " " + value.trim());
              } catch (IllegalArgumentException | ArithmeticException invalid) {
                show(
                    player,
                    menu,
                    List.of(
                        UiText.t("ui.bank.enter_a_positive_cash_amount_in.55ffcf")
                            + cashCurrency(context.terminal()).code()
                            + ".",
                        UiText.t("ui.bank.nothing_was_taken_from_your_account.a5f2e2")),
                    UiText.t("ui.bank.check_the_amount_and_try_again.7a6dde"));
                return;
              }
              state.world = context.world();
              var binding =
                  new Withdrawals.Binding(
                      context.account().id().value(),
                      context.account().revision(),
                      context.account().currency(),
                      menu.anchor(),
                      menu.generation(),
                      context.terminal().revision(),
                      context.fx().revision());
              int free = freeSlots(player);
              boolean markerCapacity = WithdrawalInventory.fits(player, 1);
              services
                  .ledger()
                  .previewWithdrawal(
                      actor,
                      binding,
                      parsed.cash(),
                      parsed.edits(),
                      (candidate, reads) -> candidate.equals(binding),
                      (candidate, reads) -> markerCapacity && stacks(candidate) <= free)
                  .whenComplete(
                      (quote, quoteError) -> {
                        if (!live(player, menu, session, revision)) return;
                        if (quoteError != null
                            || !WithdrawalInventory.fits(player, stacks(quote))) {
                          if (menu.page().equals("withdraw_mix")) {
                            state.preview = previousPreview;
                            showMix(player, menu, state);
                            menu.publish(
                                "withdraw_mix",
                                menu.lines(),
                                UiText.t(
                                    "ui.bank.mix_must_match_the_amount_and_fit_your_invento.39ad06"),
                                menu.choices());
                          } else unavailable(player, menu);
                          return;
                        }
                        state.preview = quote;
                        var catalog = ca.emilybelnavis.mcmods.economy.cash.CashCatalog.all();
                        var terminalCatalog =
                            catalog.stream()
                                .collect(
                                    java.util.stream.Collectors.toMap(
                                        d -> "economy:" + d.id(),
                                        d ->
                                            new TerminalPolicy.Denomination(
                                                d.currency(), d.bill())));
                        var enabled =
                            TerminalPolicy.effective(context.terminal(), terminalCatalog)
                                .settings()
                                .denominations();
                        state.mixDenominations =
                            catalog.stream()
                                .filter(
                                    d ->
                                        d.bill()
                                            && d.currency() == quote.cash().currency()
                                            && d.minorUnits() <= quote.cash().minorUnits()
                                            && enabled.contains("economy:" + d.id()))
                                .filter(d -> !enabled.contains("economy:" + d.id() + "_horizontal"))
                                .map(
                                    d ->
                                        new WithdrawalPlanner.Denomination(
                                            "economy:" + d.id(),
                                            d.currency(),
                                            d.minorUnits(),
                                            true))
                                .sorted(
                                    Comparator.comparingLong(
                                            WithdrawalPlanner.Denomination::minorUnits)
                                        .reversed())
                                .toList();
                        state.mix.clear();
                        for (var denomination : state.mixDenominations) {
                          int count =
                              quote.plan().lines().stream()
                                  .filter(
                                      l ->
                                          l.denomination().bill()
                                              && l.denomination().minorUnits()
                                                  == denomination.minorUnits())
                                  .mapToInt(WithdrawalPlanner.Line::count)
                                  .sum();
                          state.mix.put(denomination.id(), count);
                        }
                        var lines = new ArrayList<String>();
                        lines.add(UiText.t("ui.bank.cash.f016dc") + money(quote.cash()));
                        lines.add(UiText.t("ui.bank.bank_debit.c90280") + money(quote.debit()));
                        if (quote.atmFee() != null)
                          lines.add(UiText.t("ui.bank.atm_fee", money(Withdrawals.atmFee(quote))));
                        if (quote.pricing() != null) {
                          var price = quote.pricing().split("\\|", -1);
                          lines.add(
                              UiText.t("ui.bank.fx_principal.f945c9")
                                  + money(
                                      new Money(quote.debit().currency(), Long.parseLong(price[1])))
                                  + "; fee: "
                                  + money(
                                      new Money(
                                          quote.debit().currency(), Long.parseLong(price[3]))));
                        }
                        for (var line : quote.plan().lines())
                          lines.add(
                              line.count()
                                  + " × "
                                  + money(
                                      new Money(
                                          quote.cash().currency(),
                                          line.denomination().minorUnits())));
                        lines.add(
                            UiText.t("ui.bank.the_entire_batch_needs.37a744")
                                + stacks(quote)
                                + UiText.t("ui.bank.free_inventory_slots.e38c98"));
                        lines.add(
                            UiText.t(
                                "ui.bank.choose_confirm_withdrawal_to_debit_and_receive.7bc402"));
                        menu.setBusy(false);
                        var choices = new ArrayList<>(state.accountChoices);
                        choices.add(
                            new BankingNetwork.Choice(
                                "withdraw_mix", "", UiText.t("ui.bank.cash_select.8c80b8"), false));
                        choices.add(
                            new BankingNetwork.Choice(
                                "withdraw_confirm",
                                quote.cash().majorUnits().toPlainString(),
                                UiText.t("ui.bank.confirm_withdrawal.854c34"),
                                false));
                        menu.publish(
                            "withdraw",
                            lines,
                            UiText.t("ui.bank.withdrawal_preview.14d0fa"),
                            choices);
                        state.previewRevision = menu.revision();
                      });
            });
  }

  private static void showMix(ServerPlayer player, BankingMenu menu, State state) {
    long selected = 0;
    var choices = new ArrayList<BankingNetwork.Choice>();
    for (var denomination : state.mixDenominations) {
      int count = state.mix.getOrDefault(denomination.id(), 0);
      selected = Math.addExact(selected, Math.multiplyExact(denomination.minorUnits(), count));
      choices.add(
          new BankingNetwork.Choice(
              "withdraw_mix_change",
              denomination.id(),
              money(new Money(denomination.currency(), denomination.minorUnits())) + " × " + count,
              false));
    }
    long remaining = state.preview.cash().minorUnits() - selected;
    String total =
        UiText.t("ui.bank.selected.ae3c35")
            + money(new Money(state.preview.cash().currency(), selected));
    String rest =
        remaining < 0
            ? UiText.t("ui.bank.over_by.a559ab")
                + money(new Money(state.preview.cash().currency(), -remaining))
            : UiText.t("ui.bank.remaining.5c9d2e")
                + money(new Money(state.preview.cash().currency(), remaining));
    menu.setBusy(false);
    menu.publish(
        "withdraw_mix",
        List.of(total, rest),
        UiText.t("ui.bank.choose_bill_counts_then_review.4bc7d1"),
        choices);
  }

  static EconomyCurrency cashCurrency(TerminalPolicy.Snapshot terminal) {
    if (terminal == null || terminal.binding() == null || !terminal.binding().bound())
      throw new IllegalArgumentException("UNBOUND_BANK");
    return terminal.binding().nativeCurrency();
  }

  private static boolean usable(LedgerService.BankingContext context, BankingMenu menu) {
    return context != null
        && context.account() != null
        && context.account().purpose() == AccountIdentity.Purpose.BANK
        && context.account().status() == AccountIdentity.Status.OPEN
        && context.terminal() != null
        && context.fx() != null
        && context.terminal().active()
        && context.terminal().generation().equals(menu.generation())
        && context.terminal().binding() != null
        && context.terminal().binding().bound()
        && context.terminal().kind() != TerminalPolicy.Kind.REGISTER
        && (context.terminal().kind() == TerminalPolicy.Kind.KIOSK
            || context.terminal().settings().mode().withdrawals());
  }

  private static void confirm(ServerPlayer player, BankingMenu menu, State state) {
    if (state.preview == null
        || !menu.page().equals("withdraw")
        || menu.revision() != state.previewRevision
        || state.reservation != null)
      throw new IllegalArgumentException("Current preview required");
    var quote = state.preview;
    if (!WithdrawalInventory.fits(player, stacks(quote))) {
      unavailable(player, menu);
      return;
    }
    var operation = new OperationId(UUID.randomUUID());
    var actor = BankingController.actor(player);
    var intent =
        new Journal.Intent(
            operation,
            new RequestId(UUID.randomUUID()),
            actor,
            Withdrawals.Action.RESERVE.kind(),
            Withdrawals.reserveRequest(quote),
            null,
            null);
    BankingController.submit(
        player,
        menu,
        intent,
        () -> journal(player, menu, quote, Withdrawals.Action.RESERVE, false),
        outcome -> {
          if (outcome.status() != Journal.Status.COMMITTED) {
            if (outcome.status() == Journal.Status.DURABLY_REJECTED) {
              state.preview = null;
              show(
                  player,
                  menu,
                  List.of(
                      UiText.t("ui.bank.the_withdrawal_was_rejected_before_debit.15ce73"),
                      outcome.result().code(),
                      UiText.t("ui.bank.request_a_new_preview_after_checking_the_termi.9b1faa")),
                  UiText.t("ui.bank.withdrawal_not_reserved.06acf5"));
            }
            return;
          }
          state.reservation = operation.value();
          if (!menu.valid(player)) {
            cancel(player, menu, state);
            return;
          }
          commit(player, menu, state);
        });
  }

  private static void commit(ServerPlayer player, BankingMenu menu, State state) {
    var services = BankingController.services(player);
    var actor = BankingController.actor(player);
    services
        .ledger()
        .bankingContext(actor, menu.anchor(), BankingAccounts.selected(menu))
        .whenComplete(
            (context, error) -> {
              if (!menu.valid(player)
                  || error != null
                  || !sameBinding(context, menu, state.preview)
                  || !WithdrawalInventory.fits(player, stacks(state.preview))) {
                cancel(player, menu, state);
                return;
              }
              state.committing = true;
              var intent =
                  new Journal.Intent(
                      new OperationId(UUID.randomUUID()),
                      new RequestId(UUID.randomUUID()),
                      actor,
                      Withdrawals.Action.COMMIT.kind(),
                      Withdrawals.request(state.reservation, "menu", "confirmed"),
                      null,
                      null);
              BankingController.submit(
                  player,
                  menu,
                  intent,
                  () -> journal(player, menu, state.preview, Withdrawals.Action.COMMIT, false),
                  outcome -> {
                    if (outcome.status() == Journal.Status.COMMITTED) {
                      state.complete = true;
                      BankingController.deliver(player, menu, state.world, state.reservation);
                    } else if (outcome.status() == Journal.Status.DURABLY_REJECTED) {
                      state.committing = false;
                      cancel(player, menu, state);
                    }
                  });
            });
  }

  private static boolean sameBinding(
      LedgerService.BankingContext context, BankingMenu menu, Withdrawals.Preview preview) {
    if (!usable(context, menu)) return false;
    var binding = preview.binding();
    return context.account().id().value().equals(binding.account())
        && context.account().revision() == binding.accountRevision()
        && context.account().currency() == binding.currency()
        && context.terminal().revision() == binding.terminalRevision()
        && context.fx().revision().equals(binding.fxRevision());
  }

  private static JournalService journal(
      ServerPlayer player,
      BankingMenu menu,
      Withdrawals.Preview preview,
      Withdrawals.Action action,
      boolean cleanup) {
    var services = BankingController.services(player);
    var actor = BankingController.actor(player);
    boolean live = !cleanup && menu.valid(player) && menu.page().equals("withdraw");
    boolean capacity = !cleanup && live && WithdrawalInventory.fits(player, stacks(preview));
    return services
        .ledger()
        .withdrawals(
            action,
            (candidate, kind) -> candidate.equals(actor),
            (requested, intent, current, reads) ->
                intent.actor().equals(actor)
                    && (cleanup || live)
                    && (current == null || current.owner().equals(actor))
                    && (current == null || current.preview().binding().equals(preview.binding())),
            (requested, intent, current, reads) -> capacity,
            null);
  }

  private static void cancel(ServerPlayer player, BankingMenu menu, State state) {
    if (state.reservation == null || state.cancelling || state.complete || state.committing) return;
    state.cancelling = true;
    var actor = BankingController.actor(player);
    var intent =
        new Journal.Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            actor,
            Withdrawals.Action.CANCEL.kind(),
            Withdrawals.request(state.reservation, "no-debit", "menu closed or preview changed"),
            null,
            null);
    BankingController.submit(
        player,
        menu,
        intent,
        () -> journal(player, menu, state.preview, Withdrawals.Action.CANCEL, true),
        outcome -> {
          if (outcome.status() == Journal.Status.COMMITTED) {
            state.complete = true;
            show(
                player,
                menu,
                List.of(UiText.t("ui.bank.the_withdrawal_was_cancelled_before_debit_or_c.fcd82f")),
                UiText.t("ui.bank.withdrawal_cancelled.d0c930"));
          }
        });
  }

  public static void closed(BankingMenu menu) {
    var state = STATES.remove(menu);
    if (state != null && menu.player() instanceof ServerPlayer player && !state.committing)
      cancel(player, menu, state);
  }

  private static int stacks(Withdrawals.Preview preview) {
    return preview.plan().lines().stream()
        .mapToInt(line -> Math.addExact(line.count(), 63) / 64)
        .sum();
  }

  private static int freeSlots(ServerPlayer player) {
    int count = 0;
    for (int index = 0; index < 36; index++)
      if (player.getInventory().getItem(index).isEmpty()) count++;
    return count;
  }

  private static boolean live(ServerPlayer player, BankingMenu menu, UUID session, long revision) {
    return menu.valid(player) && menu.session().equals(session) && menu.revision() == revision;
  }

  private static void unavailable(ServerPlayer player, BankingMenu menu) {
    show(
        player,
        menu,
        List.of(
            UiText.t("ui.bank.the_terminal_account_cash_plan_or_full_invento.4378ff"),
            UiText.t("ui.bank.check_terminal_settings_make_room_for_the_enti.24fe44"),
            UiText.t("ui.bank.nothing_was_debited.e9dbfb")),
        UiText.t("ui.bank.withdrawal_unavailable.e8327c"));
  }

  private static void show(
      ServerPlayer player, BankingMenu menu, List<String> lines, String status) {
    if (!menu.valid(player)) return;
    menu.setBusy(false);
    menu.publish(
        "withdraw",
        lines,
        status,
        List.of(),
        BankingOperation.View.of(
            BankingOperation.State.DECLINED, "screen.economy.operation.declined"),
        null);
  }

  private static String money(Money value) {
    return UiText.money(value);
  }
}
