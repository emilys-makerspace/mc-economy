package ca.emilybelnavis.mcmods.economy.foundation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.cash.CashCatalog;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BankingSettingsTest {
  @TempDir Path directory;
  private static final List<CashCatalog.Denomination> CATALOG =
      List.of(
          new CashCatalog.Denomination("cad_bill_20", EconomyCurrency.CAD, 2_000, true, null),
          new CashCatalog.Denomination("cad_coin_25", EconomyCurrency.CAD, 25, false, null),
          new CashCatalog.Denomination("usd_bill_20", EconomyCurrency.USD, 2_000, true, null));

  private static CashCatalog.Denomination denomination(String id) {
    return CATALOG.stream()
        .filter(value -> ("economy:" + value.id()).equals(id))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void reviewedSettingsDoNotRebaseOntoConcurrentChangesAndStrangersCannotConfirm()
      throws Exception {
    var store = new SqliteStorage();
    store.initialize(directory.toRealPath());
    try {
      var owner = new PlayerId(UUID.randomUUID());
      var stranger = new PlayerId(UUID.randomUUID());
      var at = new TerminalPolicy.Anchor("minecraft:overworld", 10, 64, 20);
      var generation = UUID.randomUUID();
      var catalog =
          CATALOG.stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      d -> "economy:" + d.id(),
                      d -> new TerminalPolicy.Denomination(d.currency(), d.bill())));
      var place =
          TerminalPolicy.registration(
              TerminalPolicy.Action.PLACE,
              store.snapshot().worldId(),
              catalog,
              (action, intent, current, reads) -> true,
              (actor, reads) -> false);
      assertEquals(
          Journal.Status.COMMITTED,
          terminalSubmit(
                  store,
                  owner,
                  TerminalPolicy.Action.PLACE.kind(),
                  TerminalPolicy.placeRequest(
                      at, generation, owner, TerminalPolicy.Kind.ATM, null, 0),
                  place)
              .status());
      var bind =
          TerminalBanking.registration(
              store.snapshot().worldId(),
              catalog,
              (action, intent, current, reads) -> true,
              (actor, reads) -> false);
      assertEquals(
          Journal.Status.COMMITTED,
          terminalSubmit(
                  store,
                  owner,
                  TerminalBanking.BIND,
                  TerminalBanking.bindRequest(
                      at,
                      generation,
                      1,
                      new InstitutionCatalog.InstitutionId("economy:bank/crownstone"),
                      true),
                  bind)
              .status());
      var shown =
          store
              .transaction(
                  tx ->
                      TerminalPolicy.find(tx::query, store.snapshot().worldId(), at, owner, false))
              .orElseThrow();
      var proposed = BankingAccounts.parseSettings("BOTH CAD", TerminalPolicy.Kind.ATM, CATALOG);
      var review = TerminalSettingsReview.capture(shown, shown, proposed);
      var frozen = review.request(shown);
      assertEquals("2", frozen.fields().get("expected_revision"));
      for (var language : List.of("en_gb", "fr_ca", "fr_fr")) {
        var rendered =
            review.lines(CATALOG).stream().map(line -> UiText.render(line, language)).toList();
        assertTrue(
            rendered.stream()
                .anyMatch(
                    line -> line.contains("10") && line.contains("64") && line.contains("20")));
        assertTrue(rendered.stream().anyMatch(line -> line.contains("CAD")));
        assertTrue(rendered.stream().noneMatch(line -> line.contains("ui.admin.")));
        var stale = UiText.render(BankingAccounts.failureMessage("TERMINAL_STALE"), language);
        assertFalse(stale.isBlank());
        assertFalse(stale.contains("ui.admin."));
      }
      var registration =
          TerminalPolicy.registration(
              TerminalPolicy.Action.CONFIGURE,
              store.snapshot().worldId(),
              catalog,
              (action, intent, current, reads) -> true,
              (actor, reads) -> false);
      var concurrent =
          BankingAccounts.parseSettings("WITHDRAW CAD", TerminalPolicy.Kind.ATM, CATALOG);
      assertEquals(
          Journal.Status.COMMITTED,
          terminalSubmit(
                  store,
                  owner,
                  TerminalPolicy.Action.CONFIGURE.kind(),
                  TerminalPolicy.configureRequest(at, generation, shown.revision(), concurrent),
                  registration)
              .status());
      var changed =
          store
              .transaction(
                  tx ->
                      TerminalPolicy.find(tx::query, store.snapshot().worldId(), at, owner, false))
              .orElseThrow();
      long before = store.lastCommittedSequence();
      assertThrows(
          TerminalSettingsReview.Stale.class,
          () -> TerminalSettingsReview.capture(shown, changed, proposed));
      assertThrows(TerminalSettingsReview.Stale.class, () -> review.request(changed));
      assertEquals(
          "TERMINAL_STALE",
          terminalSubmit(store, owner, TerminalPolicy.Action.CONFIGURE.kind(), frozen, registration)
              .result()
              .code());
      assertEquals(
          before,
          store.lastCommittedSequence(),
          "Stale settings cannot produce a configuration fact");
      assertEquals(concurrent, changed.settings());
      var refreshed = TerminalSettingsReview.capture(changed, changed, proposed);
      assertEquals(
          "TERMINAL_ACCESS",
          terminalSubmit(
                  store,
                  stranger,
                  TerminalPolicy.Action.CONFIGURE.kind(),
                  refreshed.request(changed),
                  registration)
              .result()
              .code());
      assertEquals(
          before, store.lastCommittedSequence(), "A customer cannot edit another owner's terminal");
      assertEquals(
          Journal.Status.COMMITTED,
          terminalSubmit(
                  store,
                  owner,
                  TerminalPolicy.Action.CONFIGURE.kind(),
                  refreshed.request(changed),
                  registration)
              .status());
      var saved =
          store
              .transaction(
                  tx ->
                      TerminalPolicy.find(tx::query, store.snapshot().worldId(), at, owner, false))
              .orElseThrow();
      assertEquals(proposed, saved.settings());
      assertEquals(changed.revision() + 1, saved.revision());
    } finally {
      store.close();
    }
  }

  private static Journal.Outcome terminalSubmit(
      SqliteStorage store,
      PlayerId actor,
      Journal.Kind kind,
      Journal.CanonicalRequest request,
      JournalEngine.Registration handler) {
    return new JournalEngine(store, (who, action) -> action.equals(kind), Map.of(kind, handler))
        .submit(
            new Journal.Intent(
                new OperationId(UUID.randomUUID()),
                new RequestId(UUID.randomUUID()),
                actor,
                kind,
                request,
                null,
                null));
  }

  @Test
  void lifecycleRejectionsExplainTheAccountLimitAndResolution() {
    assertTrue(
        UiText.render(BankingAccounts.failureMessage("BANK_CAP_REACHED"), "en_gb")
            .contains("4 open accounts"));
    assertTrue(
        UiText.render(BankingAccounts.failureMessage("BANK_CAP_REACHED"), "en_gb")
            .contains("bank branch"));
    assertTrue(
        UiText.render(BankingAccounts.failureMessage("BANK_CURRENCY_EXISTS"), "en_gb")
            .contains("already have"));
    assertTrue(
        UiText.render(BankingAccounts.failureMessage("ACCOUNT_PROTECTED"), "en_gb")
            .contains("cannot be closed"));
    assertTrue(
        UiText.render(BankingAccounts.failureMessage("ACTIVE_HOLD"), "en_gb").contains("reserved"));
    assertTrue(
        UiText.render(BankingAccounts.failureMessage("UNRESOLVED_OBLIGATION"), "en_gb")
            .contains("deliveries"));
  }

  @Test
  void allCashMeansOnlyAcceptedCurrenciesAndAtmBills() {
    var atm = BankingAccounts.parseSettings("BOTH;CAD,USD;all", TerminalPolicy.Kind.ATM, CATALOG);
    assertEquals(Set.of(EconomyCurrency.CAD, EconomyCurrency.USD), atm.currencies());
    assertFalse(atm.denominations().isEmpty());
    for (var id : atm.denominations()) {
      var denomination = denomination(id);
      assertTrue(denomination.bill(), "ATM may not enable a coin through all");
      assertTrue(atm.currencies().contains(denomination.currency()));
    }
    var teller =
        BankingAccounts.parseSettings("DEPOSIT;CAD;all", TerminalPolicy.Kind.TELLER, CATALOG);
    assertTrue(
        teller.denominations().stream().map(id -> denomination(id)).anyMatch(item -> !item.bill()));
    assertTrue(
        teller.denominations().stream()
            .map(id -> denomination(id))
            .allMatch(item -> item.currency() == EconomyCurrency.CAD));
  }

  @Test
  void simplePlayerFacingSettingsMapToSafeFullAllowlists() {
    var both = BankingAccounts.parseSettings("BOTH CAD", TerminalPolicy.Kind.ATM, CATALOG);
    assertEquals(TerminalPolicy.Mode.BOTH, both.mode());
    assertEquals(Set.of(EconomyCurrency.CAD), both.currencies());
    assertEquals(Set.of("economy:cad_bill_20"), both.denominations());
    assertEquals(
        TerminalPolicy.Settings.disabled(),
        BankingAccounts.parseSettings("OFF", TerminalPolicy.Kind.TELLER, CATALOG));
  }

  @Test
  void explicitCashSelectionCannotBypassKindCurrencyOrCatalog() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BankingAccounts.parseSettings(
                "BOTH;CAD;economy:cad_coin_25", TerminalPolicy.Kind.ATM, CATALOG));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BankingAccounts.parseSettings(
                "BOTH;CAD;economy:usd_bill_20", TerminalPolicy.Kind.TELLER, CATALOG));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BankingAccounts.parseSettings(
                "BOTH;CAD;economy:missing_cash_item", TerminalPolicy.Kind.TELLER, CATALOG));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BankingAccounts.parseSettings(
                "BOTH;CAD;minecraft:diamond", TerminalPolicy.Kind.TELLER, CATALOG));
    var explicit =
        BankingAccounts.parseSettings("WITHDRAW;CAD;cad_bill_20", TerminalPolicy.Kind.ATM, CATALOG);
    assertEquals(Set.of("economy:cad_bill_20"), explicit.denominations());
  }

  @Test
  void registerCashModesStayDisabledAndClearAllowlists() {
    assertEquals(
        TerminalPolicy.Settings.disabled(),
        BankingAccounts.parseSettings("DISABLED;CAD;all", TerminalPolicy.Kind.REGISTER, CATALOG));
    assertEquals(
        TerminalPolicy.Settings.disabled(),
        BankingAccounts.parseSettings("DISABLED;;", TerminalPolicy.Kind.ATM, CATALOG));
    assertThrows(
        IllegalArgumentException.class,
        () -> BankingAccounts.parseSettings("BOTH;CAD;all", TerminalPolicy.Kind.REGISTER, CATALOG));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BankingAccounts.parseSettings(
                "DEPOSIT;CAD;all", TerminalPolicy.Kind.REGISTER, CATALOG));
  }

  @Test
  void malformedSettingsCannotCreateAnEnabledEmptyAllowlist() {
    assertThrows(
        IllegalArgumentException.class,
        () -> BankingAccounts.parseSettings("BOTH;;all", TerminalPolicy.Kind.ATM, CATALOG));
    assertThrows(
        IllegalArgumentException.class,
        () -> BankingAccounts.parseSettings("BOTH;CAD;", TerminalPolicy.Kind.ATM, CATALOG));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BankingAccounts.parseSettings("BOTH;CAD;all;extra", TerminalPolicy.Kind.ATM, CATALOG));
    assertThrows(
        IllegalArgumentException.class,
        () -> BankingAccounts.parseSettings("BOTH;NOT;all", TerminalPolicy.Kind.TELLER, CATALOG));
  }
}
