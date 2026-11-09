package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.USD;
import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.JournalFixtures.ACTOR;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.account.AccountRebasePlanner;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.OperationId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.RequestId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.fx.DenominationChange;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;

class RebaseSettlementTest {
  private static final AccountRebasePlanner.ClaimsReader CLEAR =
      (reads, id) -> Optional.of(new AccountRebasePlanner.Claims(false, false, false));
  @TempDir Path directory;

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> rebaseCases() {
    return java.util.stream.Stream.of(
        org.junit.jupiter.params.provider.Arguments.of(USD, false),
        org.junit.jupiter.params.provider.Arguments.of(EconomyCurrency.CHF, false),
        org.junit.jupiter.params.provider.Arguments.of(USD, true));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("rebaseCases")
  void fundedCollisionsAtMaximumRosterRetireAndReplay(
      EconomyCurrency target, boolean fractionalSource) throws Exception {
    var store = new SqliteStorage();
    try {
      store.initialize(directory.toRealPath());
      var world = store.snapshot().worldId();
      var handlers =
          new HashMap<>(SameCurrency.registrations(world, (intent, instruction, reads) -> true));
      handlers.put(FxService.OPEN, FxService.opening(Clock.systemUTC()));
      var setup = new JournalEngine(store, (actor, kind) -> actor.equals(ACTOR), handlers);
      var opening = intent(FxService.OPEN, new CanonicalRequest(1, Map.of("currency", "CAD")));
      assertEquals(Status.COMMITTED, setup.submit(opening).status());
      for (int index = 0; index < RebaseSettlement.MAX_PRIMARIES; index++) {
        var owner = new PlayerId(UUID.randomUUID());
        var primary = new AccountId(UUID.randomUUID());
        var collision = new AccountId(UUID.randomUUID());
        store.transaction(
            tx -> {
              tx.execute(
                  "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                  List.of(primary.value().toString(), owner.value().toString()));
              tx.execute(
                  "INSERT INTO account_catalog VALUES(?,?,'BANK',?,'OPEN',1,0)",
                  List.of(collision.value().toString(), owner.value().toString(), target.code()));
              return null;
            });
        assertEquals(
            Status.COMMITTED,
            setup
                .submit(
                    intent(
                        SameCurrency.CREDIT,
                        new SameCurrency.Adjustment(
                                owner, new SameCurrency.Revision(primary, 1), new Money(CAD, 1_000))
                            .request()))
                .status());
        assertEquals(
            Status.COMMITTED,
            setup
                .submit(
                    intent(
                        SameCurrency.CREDIT,
                        new SameCurrency.Adjustment(
                                owner,
                                new SameCurrency.Revision(collision, 1),
                                new Money(target, 500))
                            .request()))
                .status());
        if (fractionalSource) {
          var legs =
              List.of(
                  new PostingBatch.Leg(
                      primary.value(), PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, 8)),
                  new PostingBatch.Leg(
                      Settlement.issuanceAccount(CAD),
                      PostingBatch.Role.ISSUANCE,
                      CAD,
                      new Money(CAD, -8)));
          var minorKind = new Kind("economy:chf-fixture", "economy:minor-fund");
          var minor =
              new JournalEngine(
                  store,
                  (actor, kind) -> actor.equals(ACTOR),
                  Map.of(
                      minorKind,
                      Settlement.registrationMinor((i, l, r) -> PostingBatch.Authority.ISSUE)));
          assertEquals(
              Status.COMMITTED,
              minor.submit(intent(minorKind, Settlement.minorRequest(legs))).status());
        }
      }
      var session = UUID.randomUUID();
      var confirmation = new DenominationChange(world, session);
      var proof =
          confirmation.confirm(confirmation.first(ACTOR, target, 0, true, 0), ACTOR, 0, true, 1);
      var plan =
          store
              .transaction(
                  tx ->
                      AccountRebasePlanner.plan(
                          tx::query,
                          world,
                          session,
                          ACTOR,
                          proof,
                          RebaseSettlement.denomination(tx::query).orElseThrow(),
                          CLEAR))
              .plan();
      assertEquals(RebaseSettlement.MAX_PRIMARIES, plan.mappings().size());

      var publication = opening.operationId().value();
      var registration =
          RebaseSettlement.registration(
              world, session, proof, plan, publication, (actor, reads) -> true, CLEAR);
      var engine =
          new JournalEngine(
              store,
              (actor, kind) -> ACTOR.equals(actor),
              Map.of(RebaseSettlement.KIND, registration));
      var request = intent(RebaseSettlement.KIND, RebaseSettlement.request(plan, publication));
      var outcome = engine.submit(request);
      assertEquals(Status.COMMITTED, outcome.status(), outcome.result().code());
      assertEquals(
          outcome,
          engine.submit(
              new Intent(
                  request.operationId(),
                  new RequestId(UUID.randomUUID()),
                  ACTOR,
                  request.kind(),
                  request.request(),
                  null,
                  null)));
      var envelope = engine.readAfter(outcome.sequence().value() - 1, 1).getFirst();
      assertEquals(1 + 6 * RebaseSettlement.MAX_PRIMARIES, envelope.records().size());
      assertEquals(
          new AccountRebasePlanner.Denomination(target, 1),
          store.transaction(tx -> RebaseSettlement.denomination(tx::query).orElseThrow()));
      for (var mapping : plan.mappings()) {
        var balance =
            store.transaction(
                tx -> Settlement.posted(tx::query, mapping.primary().id().value(), target));
        assertEquals(target == EconomyCurrency.CHF ? 1_100 : 1_200, balance);
        var collision =
            store.transaction(
                tx ->
                    tx.query(
                            "SELECT status,revision FROM account_catalog WHERE account_id=?",
                            List.of(mapping.collision().id().value().toString()),
                            1)
                        .getFirst());
        assertEquals(Map.of("status", "CLOSED", "revision", "2"), collision);
      }
    } finally {
      store.close();
    }
  }

  private static Intent intent(Kind kind, CanonicalRequest request) {
    return new Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        ACTOR,
        kind,
        request,
        null,
        null);
  }
}
