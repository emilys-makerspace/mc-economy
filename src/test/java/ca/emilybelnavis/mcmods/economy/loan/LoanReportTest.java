package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.foundation.LoanReport;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LoanReportTest {
    @TempDir Path path;
    @Test void reportsPreserveCapturedScheduleAndLabelBacklogAndPreviewWithoutFinancialEffects()throws Exception {
        try(var f=new LoanTestFixture(path)){
            var loan=f.originate();long before=f.store.lastCommittedSequence();
            var view=f.store.transaction(tx->LoanDiagnostics.contract(tx::query,f.owner,loan.id(),f.clock.get()).orElseThrow());
            var lines=LoanReport.owner(view).stream().map(s -> UiText.render(s, "en_gb")).toList();assertTrue(lines.stream().anyMatch(s->s.contains("Fixed unsecured")));assertTrue(lines.stream().anyMatch(s->s.contains("Captured score 650")));
            var page=f.store.transaction(tx->LoanDiagnostics.schedule(tx::query,f.owner,loan.id(),0,4));
            var schedule=LoanReport.schedule(page).stream().map(s -> UiText.render(s, "en_gb")).toList();assertEquals(6,schedule.size());assertTrue(schedule.getFirst().contains(loan.scheduleHash()));assertTrue(schedule.getLast().contains("after 4"));
            var quote=f.store.transaction(tx->Loans.quote(tx::query,f.owner,loan.id(),f.account,0,true,f.clock.get()));
            var payoff=LoanReport.payoff(quote).stream().map(s -> UiText.render(s, "en_gb")).toList();assertTrue(payoff.stream().anyMatch(s->s.contains("No debit or interest waiver")));assertTrue(payoff.stream().anyMatch(s->s.contains("loan revision "+loan.revision())));
            var late=f.store.transaction(tx->LoanDiagnostics.contract(tx::query,f.owner,loan.id(),loan.nextMonth()).orElseThrow());
            assertFalse(late.current());assertTrue(LoanReport.owner(late).stream().map(s -> UiText.render(s, "en_gb")).anyMatch(s->s.contains("saved amounts only")));
            assertEquals(before,f.store.lastCommittedSequence());
        }
    }
}
