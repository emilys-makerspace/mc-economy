package ca.emilybelnavis.mcmods.economy.banking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Removes internal identifiers and protocol details before normal GUI snapshots reach clients. */
final class BankingPresentation {
  private static final Pattern UUID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,}");

  private BankingPresentation() {}

  static List<String> lines(List<String> source) {
    var result = new ArrayList<String>();
    boolean hideOutcomeCode = false;
    for (String line : source) {
      if (line == null) continue;
      String english = UiText.render(line, "en_gb");
      if (english.startsWith("Operation ") || english.startsWith("Original operation:")) {
        hideOutcomeCode = true;
        continue;
      }
      if (hideOutcomeCode
          && (CODE.matcher(english).matches() || english.matches("[a-z0-9 ]{3,}"))) {
        hideOutcomeCode = false;
        continue;
      }
      hideOutcomeCode = false;
      if (english.startsWith("Transaction ")
          && UUID.matcher(english.substring("Transaction ".length())).matches()) continue;
      if (english.startsWith("Terminal revision ") || UUID.matcher(english).matches()) continue;
      String clean = line;
      if (english.startsWith("History for "))
        clean = UiText.mapText(clean, text -> UUID.matcher(text).replaceFirst("").stripTrailing());
      if (english.matches("#[0-9]+ .+"))
        clean = UiText.mapText(clean, text -> text.replaceFirst("^#[0-9]+ ", ""));
      if (english.matches("[1-8]\\. .* — .*") && UUID.matcher(english).find()) {
        String number = english.substring(0, english.indexOf('.') + 1);
        String kind = english.substring(english.lastIndexOf('—') + 1).strip();
        clean =
            number
                + " "
                + switch (kind) {
                  case "register-goods-v1" -> UiText.t("ui.bank.register_purchase");
                  case "withdrawal-cash-v1" -> UiText.t("ui.bank.cash_withdrawal");
                  case "deposit-original-v1" -> UiText.t("ui.bank.deposit_return");
                  default -> UiText.t("ui.bank.protected_items");
                };
      }
      clean =
          UiText.mapText(
              clean,
              text ->
                  UUID.matcher(text)
                      .replaceAll(
                          java.util.regex.Matcher.quoteReplacement(
                              UiText.t("ui.bank.redacted_account"))));
      if (!clean.isBlank()) result.add(clean);
    }
    return List.copyOf(result);
  }

  static String status(String source) {
    if (source == null || source.isBlank()) return UiText.t("ui.bank.ready");
    String english = UiText.render(source, "en_gb");
    if (english.contains("retry preserves operation")) return UiText.t("ui.bank.retry_outcome");
    if (UUID.matcher(english).find()) return UiText.t("ui.bank.debug_details");
    if (CODE.matcher(english).matches()) {
      return UiText.t("ui.bank.request_processed");
    }
    return source;
  }
}
