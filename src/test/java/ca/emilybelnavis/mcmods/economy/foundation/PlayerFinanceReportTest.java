package ca.emilybelnavis.mcmods.economy.foundation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.credit.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerFinanceReportTest {
  @Test
  void statementShowsTheBalanceMinimumAndDueDateWithoutInternalReferences() {
    var facility = UUID.randomUUID();
    var statementId = facility + "/1";
    var statement =
        new CreditFacilities.StatementSnapshot(
            new WorldId(UUID.randomUUID()),
            facility,
            new PlayerId(UUID.randomUUID()),
            new CardServicing.Statement(
                statementId,
                0,
                14 * CreditFacilities.DAY,
                1299,
                1200,
                new BigDecimal("0.99"),
                200,
                200));
    String rendered =
        String.join(
            "\n",
            FinanceReports.statement(statement, EconomyCurrency.CAD).stream()
                .map(line -> UiText.render(line, "en_gb"))
                .toList());
    assertTrue(rendered.contains("12.99"));
    assertTrue(rendered.contains("2.00"));
    assertTrue(rendered.contains("1970-01-01 04:40 UTC"));
    assertFalse(rendered.contains(facility.toString()));
  }
}
