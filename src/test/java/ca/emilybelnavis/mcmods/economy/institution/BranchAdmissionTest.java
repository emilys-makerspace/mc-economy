package ca.emilybelnavis.mcmods.economy.institution;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class BranchAdmissionTest {
  @Test
  void proofRequiresFreshLiveBranchAndExpiresAndRevokes() {
    var time = new AtomicLong(100);
    var live = new AtomicBoolean(true);
    var checks = new AtomicInteger();
    var issuer =
        new BranchAdmission.Issuer(
            time::get,
            (context, reads) -> {
              checks.incrementAndGet();
              return live.get();
            });
    var actor = new PlayerId(UUID.randomUUID());
    var context =
        new BranchAdmission.Context(
            new WorldId(UUID.randomUUID()),
            UUID.randomUUID(),
            actor,
            BranchAdmission.Action.PRIMARY,
            new InstitutionCatalog.InstitutionId("economy:bank/crownstone"),
            null,
            null,
            0,
            0,
            0);
    var proof = issuer.issue(context, 30_000);
    var request = new BankProviders.Request(proof, 0);
    var intent =
        new Journal.Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            actor,
            BankProviders.PRIMARY,
            request.request(),
            null,
            null);
    assertTrue(proof.valid(intent, (sql, p, max) -> List.of()));
    live.set(false);
    assertFalse(proof.valid(intent, (sql, p, max) -> List.of()));
    assertEquals(2, checks.get());
    live.set(true);
    time.set(30_100);
    assertFalse(proof.valid(intent, (sql, p, max) -> List.of()));
    time.set(100);
    issuer.disable();
    assertFalse(proof.valid(intent, (sql, p, max) -> List.of()));
    assertThrows(IllegalArgumentException.class, () -> issuer.issue(context, 1));
  }

  @Test
  void sameCurrencyBankCannotSubstituteForServerObservedBranch() {
    var actor = new PlayerId(UUID.randomUUID());
    var observed = new InstitutionCatalog.InstitutionId("economy:bank/crownstone");
    var issuer =
        new BranchAdmission.Issuer(() -> 0, (context, reads) -> context.bank().equals(observed));
    var context =
        new BranchAdmission.Context(
            new WorldId(UUID.randomUUID()),
            UUID.randomUUID(),
            actor,
            BranchAdmission.Action.PRIMARY,
            new InstitutionCatalog.InstitutionId("economy:bank/orchard_direct"),
            null,
            null,
            0,
            0,
            0);
    var proof = issuer.issue(context, 1_000);
    var request = new BankProviders.Request(proof, 0);
    var intent =
        new Journal.Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            actor,
            BankProviders.PRIMARY,
            request.request(),
            null,
            null);
    assertFalse(proof.valid(intent, (sql, p, max) -> List.of()));
    assertThrows(NullPointerException.class, () -> new BankProviders.Request(null, 0));
  }

  @Test
  void excessiveLeaseLifetimeRejected() {
    var issuer = new BranchAdmission.Issuer(() -> 0, (context, reads) -> true);
    var context =
        new BranchAdmission.Context(
            new WorldId(UUID.randomUUID()),
            UUID.randomUUID(),
            new PlayerId(UUID.randomUUID()),
            BranchAdmission.Action.PRIMARY,
            new InstitutionCatalog.InstitutionId("economy:bank/crownstone"),
            null,
            null,
            0,
            0,
            0);
    assertThrows(IllegalArgumentException.class, () -> issuer.issue(context, 30_001));
  }
}
