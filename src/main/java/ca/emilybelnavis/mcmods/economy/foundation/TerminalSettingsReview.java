package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.cash.CashCatalog;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy;
import java.util.*;

/**
 * Captured owner settings scope. Confirmation never silently rebases a draft onto a newer terminal.
 */
record TerminalSettingsReview(TerminalPolicy.Snapshot shown, TerminalPolicy.Settings proposed) {
  static final class Stale extends IllegalStateException {
    Stale() {
      super("TERMINAL_STALE");
    }
  }

  TerminalSettingsReview {
    Objects.requireNonNull(shown);
    Objects.requireNonNull(proposed);
  }

  static TerminalSettingsReview capture(
      TerminalPolicy.Snapshot shown,
      TerminalPolicy.Snapshot current,
      TerminalPolicy.Settings proposed) {
    requireCurrent(shown, current);
    return new TerminalSettingsReview(shown, proposed);
  }

  Journal.CanonicalRequest request(TerminalPolicy.Snapshot current) {
    requireCurrent(shown, current);
    return TerminalPolicy.configureRequest(
        shown.anchor(), shown.generation(), shown.revision(), proposed);
  }

  private static void requireCurrent(
      TerminalPolicy.Snapshot shown, TerminalPolicy.Snapshot current) {
    if (shown == null || !shown.active() || !shown.equals(current)) throw new Stale();
  }

  static List<String> scope(TerminalPolicy.Snapshot terminal) {
    var at = terminal.anchor();
    return List.of(
        UiText.t(
            "ui.admin.terminal.scope",
            UiText.t("ui.admin.kind." + terminal.kind().name().toLowerCase(Locale.ROOT)),
            at.x(),
            at.y(),
            at.z(),
            at.dimension()),
        UiText.t("ui.admin.terminal.version", terminal.revision()));
  }

  List<String> lines(List<CashCatalog.Denomination> catalog) {
    var lines = new ArrayList<>(scope(shown));
    lines.add(UiText.t("ui.admin.settings.mode", BankingAccounts.deliveryLabel(proposed.mode())));
    if (proposed.mode() == TerminalPolicy.Mode.DISABLED)
      lines.add(UiText.t("ui.admin.settings.off"));
    else {
      lines.add(
          UiText.t(
              "ui.admin.settings.currencies",
              String.join(", ", proposed.currencies().stream().map(Enum::name).sorted().toList())));
      for (var id : proposed.denominations()) {
        var cash =
            catalog.stream()
                .filter(d -> ("economy:" + d.id()).equals(id))
                .findFirst()
                .orElseThrow();
        lines.add(
            UiText.t(
                "ui.admin.settings.cash_type",
                UiText.money(new Money(cash.currency(), cash.minorUnits())),
                UiText.t(
                    cash.bill()
                        ? cash.id().endsWith("_horizontal")
                            ? "ui.admin.cash.horizontal_bill"
                            : "ui.admin.cash.bill"
                        : "ui.admin.cash.coin")));
      }
    }
    lines.add(UiText.t("ui.admin.settings.effect"));
    if (lines.size() > 32)
      throw new IllegalArgumentException("Settings review exceeds display bound");
    return List.copyOf(lines);
  }
}
