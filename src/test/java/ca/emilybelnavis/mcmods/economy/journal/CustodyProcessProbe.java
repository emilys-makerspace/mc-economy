package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static ca.emilybelnavis.mcmods.economy.journal.Custody.Action.*;
import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Disposable process-death fixture. Its files model detached inventory effects, not Minecraft IO.
 */
public final class CustodyProcessProbe {
  static final PlayerId OWNER = new PlayerId(id("owner"));
  static final UUID CASE = id("case");
  static final UUID SOURCE = id("source");
  static final UUID TARGET = id("target");
  static final UUID ATTEMPT = id("delivery-attempt");
  static final String SOURCE_FILE = "reservation.snapshot";
  static final String DESTINATION_FILE = "inventory.snapshot";
  static final byte[] RESERVED_BYTES =
      "custody-fixture:reserved:4-stone".getBytes(StandardCharsets.UTF_8);
  static final byte[] DELIVERED_BYTES =
      "custody-fixture:delivered:4-stone".getBytes(StandardCharsets.UTF_8);

  private CustodyProcessProbe() {}

  public static void main(String[] args) throws Exception {
    Path world = Path.of(args[0]);
    String boundary = args[1];
    var store = new SqliteStorage();
    store.initialize(world);
    seed(store);
    require(engine(store, world, RESERVE).submit(reservation()).status() == Status.COMMITTED);
    pause(boundary, "reservation-save-before");
    Files.write(world.resolve(SOURCE_FILE), RESERVED_BYTES);
    pause(boundary, "reservation-save-after");
    require(
        engine(store, world, RESERVATION_SAVED).submit(reservationSaved(store)).status()
            == Status.COMMITTED);
    pause(boundary, "financial-before");
    require(engine(store, world, COMMIT).submit(commit()).status() == Status.COMMITTED);
    pause(boundary, "financial-after");
    require(
        engine(store, world, BEGIN_DELIVERY)
                .submit(transition(BEGIN_DELIVERY, ATTEMPT, Custody.Evidence.unknown(), ATTEMPT))
                .status()
            == Status.COMMITTED);
    pause(boundary, "physical-before");
    // Observable controlled side effect, separate from the later inventory-save witness.
    Files.writeString(world.resolve("physical-delivery.marker"), ATTEMPT.toString());
    pause(boundary, "physical-after");
    pause(boundary, "ack-before");
    require(
        engine(store, world, ACKNOWLEDGE)
                .submit(transition(ACKNOWLEDGE, ATTEMPT, Custody.Evidence.unknown(), id("ack")))
                .status()
            == Status.COMMITTED);
    pause(boundary, "ack-after");
    pause(boundary, "inventory-save-before");
    Files.write(world.resolve(DESTINATION_FILE), DELIVERED_BYTES);
    require(
        engine(store, world, DELIVERY_SAVED)
                .submit(
                    transition(
                        DELIVERY_SAVED,
                        ATTEMPT,
                        evidence(store, Custody.EvidenceKind.DELIVERY_SAVED),
                        id("delivery-saved")))
                .status()
            == Status.COMMITTED);
    pause(boundary, "inventory-save-after");
    throw new IllegalArgumentException("Unknown fixture boundary");
  }

  static Intent reservation() {
    var terms =
        new Custody.Terms(
            OWNER,
            "minecraft:stone[count=4]",
            SOURCE_FILE,
            DESTINATION_FILE,
            Custody.financialIntent(financial()));
    return new Intent(
        new OperationId(CASE),
        new RequestId(id("reserve-request")),
        OWNER,
        RESERVE.kind(),
        Custody.reserveRequest(terms),
        null,
        null);
  }

  static Intent reservationSaved(SqliteStorage store) {
    return transition(
        RESERVATION_SAVED,
        null,
        evidence(store, Custody.EvidenceKind.RESERVATION_SAVED),
        id("reservation-saved"));
  }

  static Intent commit() {
    return transition(COMMIT, null, Custody.Evidence.unknown(), financial().operationId().value());
  }

  static Intent transition(
      Custody.Action action, UUID attempt, Custody.Evidence evidence, UUID operation) {
    return new Intent(
        new OperationId(operation),
        new RequestId(id(action.name() + "-request")),
        OWNER,
        action.kind(),
        Custody.request(CASE, attempt, evidence, "process-boundary fixture"),
        null,
        null);
  }

  static JournalEngine engine(SqliteStorage store, Path world, Custody.Action action) {
    var finance =
        Custody.financial(
            financial(),
            Settlement.registration((intent, legs, reads) -> PostingBatch.Authority.TRANSFER));
    return new JournalEngine(
        store,
        (actor, kind) -> actor.equals(OWNER) && kind.equals(action.kind()),
        Map.of(
            action.kind(),
            Custody.registration(
                action,
                (a, intent, current, reads) -> intent.actor().equals(current.terms().owner()),
                (current, evidence, reads) -> {
                  var parts = evidence.saveToken().split(":", -1);
                  if (parts.length != 5) return false;
                  var expected =
                      new CustodySaveWitness.Expected(
                          new WorldId(UUID.fromString(parts[1])),
                          UUID.fromString(parts[2]),
                          UUID.fromString(parts[3]),
                          evidence.location(),
                          parts[4]);
                  return expected.world().equals(store.snapshot().worldId())
                      && expected.custody().equals(current.id())
                      && CustodySaveWitness.matches(world, expected);
                },
                finance,
                (intent, current, reads) -> true)));
  }

  static Custody.Evidence evidence(SqliteStorage store, Custody.EvidenceKind kind) {
    boolean reservation = kind == Custody.EvidenceKind.RESERVATION_SAVED;
    var expected =
        new CustodySaveWitness.Expected(
            store.snapshot().worldId(),
            CASE,
            reservation ? id("reservation-generation") : ATTEMPT,
            reservation ? SOURCE_FILE : DESTINATION_FILE,
            CustodySaveWitness.digest(reservation ? RESERVED_BYTES : DELIVERED_BYTES));
    return new Custody.Evidence(
        kind, "controlled-file-snapshot", expected.location(), expected.token());
  }

  static Intent financial() {
    return new Intent(
        new OperationId(id("financial")),
        new RequestId(id("financial-request")),
        OWNER,
        new Kind("economy:custody_process", "economy:transfer"),
        Settlement.request(
            List.of(
                new PostingBatch.Leg(SOURCE, ACCOUNT, CAD, new Money(CAD, -1_000)),
                new PostingBatch.Leg(TARGET, ACCOUNT, CAD, new Money(CAD, 1_000)))),
        null,
        null);
  }

  private static void seed(SqliteStorage store) {
    store.transaction(
        tx -> {
          for (var account : List.of(SOURCE, TARGET))
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,0)",
                List.of(account.toString(), id("account-owner-" + account).toString()));
          return null;
        });
    var kind = new Kind("economy:custody_process", "economy:issue");
    var engine =
        new JournalEngine(
            store,
            (actor, requested) -> actor.equals(OWNER),
            Map.of(
                kind,
                Settlement.registration((intent, legs, reads) -> PostingBatch.Authority.ISSUE)));
    require(
        engine
                .submit(
                    new Intent(
                        new OperationId(id("funding")),
                        new RequestId(id("funding-request")),
                        OWNER,
                        kind,
                        Settlement.request(
                            List.of(
                                new PostingBatch.Leg(SOURCE, ACCOUNT, CAD, new Money(CAD, 10_000)),
                                new PostingBatch.Leg(
                                    Settlement.issuanceAccount(CAD),
                                    ISSUANCE,
                                    CAD,
                                    new Money(CAD, -10_000)))),
                        null,
                        null))
                .status()
            == Status.COMMITTED);
  }

  static UUID id(String label) {
    return UUID.nameUUIDFromBytes(
        ("economy:custody-process:" + label).getBytes(StandardCharsets.UTF_8));
  }

  private static void pause(String selected, String boundary) throws Exception {
    if (!selected.equals(boundary)) return;
    System.out.println("BOUNDARY " + boundary);
    System.out.flush();
    // Parent forcibly terminates this process. No close/save hook runs.
    while (true) Thread.sleep(60_000);
  }

  private static void require(boolean condition) {
    if (!condition) throw new IllegalStateException("Custody process fixture setup failed");
  }
}
