package ca.emilybelnavis.mcmods.economy.foundation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreditSafetyAdmissionTest {
  @TempDir Path path;

  @Test
  void productionIssuanceAndIncreaseAreGatedWithoutBlockingProtectedReadsAndDebtActions()
      throws Exception {
    var store = new SqliteStorage();
    var services = new ServerServices(path.toRealPath(), store);
    await(services, services.readiness());
    try {
      var player = new PlayerId(UUID.randomUUID());
      var owner = new PartyRef(services.worldId(), new PartyRef.Player(player));
      var grant =
          PartyAuthorization.admit(
                  services.worldId(), services.sessionId(), player, false, owner, false)
              .orElseThrow();
      var bank = new InstitutionCatalog.InstitutionId("economy:bank/crownstone");
      var facility = UUID.randomUUID();
      var proof =
          BranchFixtures.proof(
              new BranchAdmission.Context(
                  owner.world(),
                  UUID.randomUUID(),
                  player,
                  BranchAdmission.Action.CREDIT_ISSUE,
                  bank,
                  null,
                  null,
                  0,
                  0,
                  0));
      var request =
          new CreditFacilities.IssueRequest(
              proof,
              facility,
              InstitutionCatalog.Network.ORBIT,
              EconomyCurrency.CAD,
              250000,
              0,
              0,
              0);
      var issue =
          services
              .ledger()
              .issueCreditFacility(
                  grant,
                  new OperationId(UUID.randomUUID()),
                  new RequestId(UUID.randomUUID()),
                  request);
      assertEquals(
          CreditSafety.STATUS, await(services, issue.completion()).outcome().result().code());
      var increaseProof =
          BranchFixtures.proof(
              new BranchAdmission.Context(
                  owner.world(),
                  UUID.randomUUID(),
                  player,
                  BranchAdmission.Action.CREDIT_INCREASE,
                  bank,
                  null,
                  null,
                  0,
                  0,
                  0));
      var increase =
          new CreditFacilities.IncreaseRequest(increaseProof, facility, 300000, 1, 1, 0, 0);
      assertEquals(
          CreditSafety.STATUS,
          await(
                  services,
                  services
                      .ledger()
                      .increaseCreditFacility(
                          grant,
                          new OperationId(UUID.randomUUID()),
                          new RequestId(UUID.randomUUID()),
                          increase)
                      .completion())
              .outcome()
              .result()
              .code());
      var loanProof =
          BranchFixtures.proof(
              new BranchAdmission.Context(
                  owner.world(),
                  UUID.randomUUID(),
                  player,
                  BranchAdmission.Action.LOAN_APPLY,
                  bank,
                  null,
                  null,
                  0,
                  0,
                  0));
      var loanRequest =
          new ca.emilybelnavis.mcmods.economy.loan.Loans.Application(
              loanProof,
              UUID.randomUUID(),
              UUID.randomUUID(),
              CreditUnderwriting.Product.BANK_LOAN,
              EconomyCurrency.CAD,
              100000,
              1,
              new AccountId(UUID.randomUUID()),
              1,
              0,
              0,
              1,
              UUID.randomUUID(),
              0,
              ca.emilybelnavis.mcmods.economy.loan.Loans.RateMode.FIXED,
              false);
      assertEquals(
          CreditSafety.STATUS,
          await(
                  services,
                  services
                      .ledger()
                      .applyLoan(
                          grant,
                          new OperationId(UUID.randomUUID()),
                          new RequestId(UUID.randomUUID()),
                          loanRequest)
                      .completion())
              .outcome()
              .result()
              .code());
      assertTrue(await(services, services.ledger().loans(grant, null, 8)).orElseThrow().isEmpty());
      assertTrue(
          await(services, services.ledger().creditFacilityPage(grant, null, 8))
              .orElseThrow()
              .entries()
              .isEmpty());
      var revoke =
          await(
                  services,
                  services
                      .ledger()
                      .revokeCreditCard(
                          grant,
                          new OperationId(UUID.randomUUID()),
                          new RequestId(UUID.randomUUID()),
                          UUID.randomUUID(),
                          1)
                      .completion())
              .outcome();
      assertNotEquals(CreditSafety.STATUS, revoke.result().code());
      var repay =
          new CreditFacilities.RepaymentRequest(
              owner.world(),
              player,
              facility,
              new AccountId(UUID.randomUUID()),
              100,
              1,
              1,
              0,
              UUID.randomUUID(),
              System.currentTimeMillis() + 30000);
      assertNotEquals(
          CreditSafety.STATUS,
          await(
                  services,
                  services
                      .ledger()
                      .repayCredit(
                          grant,
                          new OperationId(UUID.randomUUID()),
                          new RequestId(UUID.randomUUID()),
                          repay)
                      .completion())
              .outcome()
              .result()
              .code());
      var counts =
          await(
              services,
              services.storage(
                  () ->
                      store.transaction(
                          tx ->
                              List.of(
                                  tx.query(
                                          "SELECT count(*) AS n FROM credit_profiles", List.of(), 1)
                                      .getFirst()
                                      .get("n"),
                                  tx.query(
                                          "SELECT count(*) AS n FROM credit_facilities",
                                          List.of(),
                                          1)
                                      .getFirst()
                                      .get("n"),
                                  tx.query(
                                          "SELECT count(*) AS n FROM journal_records", List.of(), 1)
                                      .getFirst()
                                      .get("n")))));
      assertEquals(List.of("0", "0", "0"), counts);
    } finally {
      assertTrue(services.stop(Duration.ofSeconds(5)));
    }
  }

  private static <T> T await(ServerServices services, CompletionStage<T> stage) throws Exception {
    var future = stage.toCompletableFuture();
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!future.isDone() && System.nanoTime() < until) {
      services.tick();
      Thread.sleep(1);
    }
    return future.get(1, TimeUnit.SECONDS);
  }
}
