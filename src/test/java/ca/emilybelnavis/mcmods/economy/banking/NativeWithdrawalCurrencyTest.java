package ca.emilybelnavis.mcmods.economy.banking;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeWithdrawalCurrencyTest {
  @Test
  void cashSelectionComesFromVerifiedBankBindingForEveryIssuer() {
    var world = new WorldId(UUID.randomUUID());
    var anchor = new TerminalPolicy.Anchor("minecraft:overworld", 1, 70, 2);
    var generation = UUID.randomUUID();
    var owner = new PlayerId(UUID.randomUUID());
    for (var bank : InstitutionCatalog.banks()) {
      var binding =
          new TerminalBanking.Binding(
              world,
              anchor,
              generation,
              bank.id(),
              1,
              bank.baseCurrency(),
              TerminalBanking.MigrationState.BOUND,
              TerminalPolicy.Settings.disabled());
      var terminal =
          new TerminalPolicy.Snapshot(
              world,
              anchor,
              generation,
              owner,
              TerminalPolicy.Kind.ATM,
              2,
              true,
              TerminalPolicy.Settings.disabled(),
              binding);
      assertEquals(bank.baseCurrency(), BankingWithdrawals.cashCurrency(terminal));
    }
    var unbound =
        new TerminalPolicy.Snapshot(
            world,
            anchor,
            generation,
            owner,
            TerminalPolicy.Kind.ATM,
            1,
            true,
            TerminalPolicy.Settings.disabled());
    assertThrows(IllegalArgumentException.class, () -> BankingWithdrawals.cashCurrency(unbound));
  }
}
