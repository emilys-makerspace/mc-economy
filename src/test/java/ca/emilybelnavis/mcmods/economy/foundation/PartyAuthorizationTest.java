package ca.emilybelnavis.mcmods.economy.foundation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PartyAuthorizationTest {
  @TempDir Path worldPath;
  private final UUID session = UUID.randomUUID();
  private final PlayerId player = new PlayerId(UUID.randomUUID());
  private final PlayerId other = new PlayerId(UUID.randomUUID());

  @Test
  void ownershipAndOperatorAuthorityAreSeparate() {
    var world = new WorldId(UUID.randomUUID());
    var self = new PartyRef(world, new PartyRef.Player(player));
    var system = new SystemActorId(player.value());
    var systemParty = new PartyRef(world, new PartyRef.System(system));
    var org = new PartyRef(world, new PartyRef.Organization(new OrganizationId(player.value())));
    var kind = new Journal.Kind("fixture:accounts", "fixture:open");
    var grant = PartyAuthorization.admit(world, session, player, false, self, false).orElseThrow();
    assertTrue(grant.allows(world, session, player, kind));
    assertFalse(grant.allows(world, session, other, kind));
    assertFalse(grant.allows(world, UUID.randomUUID(), player, kind));
    assertFalse(grant.allows(world, session, player, InspectionAudit.KIND));
    assertTrue(PartyAuthorization.admit(world, session, other, true, self, false).isEmpty());
    assertTrue(PartyAuthorization.admit(world, session, other, false, self, true).isEmpty());
    assertTrue(PartyAuthorization.admit(world, session, player, true, org, true).isEmpty());
    assertTrue(
        PartyAuthorization.admit(world, session, player, false, systemParty, false).isEmpty());
    assertTrue(
        PartyAuthorization.admit(world, session, system, false, systemParty, false).isPresent());
    assertTrue(PartyAuthorization.admit(world, session, system, false, self, false).isEmpty());
    assertTrue(
        PartyAuthorization.admit(
                new WorldId(UUID.randomUUID()), session, player, false, self, false)
            .isEmpty());
    var inspection =
        PartyAuthorization.admit(world, session, other, true, self, true).orElseThrow();
    assertFalse(inspection.allows(world, session, other, kind));
    assertTrue(inspection.allows(world, session, other, InspectionAudit.KIND));

    var automation =
        new CommandSourceStack(
            CommandSource.NULL,
            Vec3.ZERO,
            Vec2.ZERO,
            null,
            4,
            "fixture",
            Component.empty(),
            null,
            null);
    assertTrue(
        PartyAuthorization.inspection(automation, server -> fail("Unauthenticated source"), self)
            .isEmpty());
  }

  @Test
  void auditAndOriginalOutcomeSurviveRestartWithCurrentAuthorization() throws Exception {
    var operationId = new OperationId(UUID.randomUUID());
    PartyRef target;
    PartyAuthorization.Grant originalGrant;
    Journal.Outcome original;
    var store = open();
    try {
      var world = store.snapshot().worldId();
      target = new PartyRef(world, new PartyRef.Player(player));
      originalGrant =
          PartyAuthorization.admit(world, session, other, true, target, true).orElseThrow();
      var inspection = PartyOperation.inspection(originalGrant, operationId, requestId());
      var engine = inspection.engine(store, session);
      var intent = inspection.intent();
      var forged =
          new Journal.Intent(
              operationId, requestId(), player, intent.kind(), intent.request(), null, null);
      assertEquals("ACCESS_DENIED", engine.submit(forged).result().code());
      assertTrue(engine.lookup(forged).isEmpty());
      original = engine.submit(intent);
      assertEquals(Journal.Status.COMMITTED, original.status());
      assertTrue(engine.lookup(forged).isEmpty());
    } finally {
      store.close();
    }
    var reopened = open();
    try {
      var world = reopened.snapshot().worldId();
      var newSession = UUID.randomUUID();
      var stale = PartyOperation.inspection(originalGrant, operationId, requestId());
      assertTrue(stale.engine(reopened, newSession).lookup(stale.intent()).isEmpty());

      var ownerGrant =
          PartyAuthorization.admit(world, newSession, player, false, target, false).orElseThrow();
      var unprivileged = PartyOperation.inspection(ownerGrant, operationId, requestId());
      assertEquals(
          "ACCESS_DENIED",
          unprivileged.engine(reopened, newSession).submit(unprivileged.intent()).result().code());
      assertTrue(unprivileged.engine(reopened, newSession).lookup(unprivileged.intent()).isEmpty());

      var current =
          PartyAuthorization.admit(world, newSession, other, true, target, true).orElseThrow();
      var retry = PartyOperation.inspection(current, operationId, requestId());
      var engine = retry.engine(reopened, newSession);
      assertEquals(original, engine.lookup(retry.intent()).orElseThrow());
      assertEquals(original, engine.submit(retry.intent()));
      var changed =
          PartyAuthorization.admit(
                  world,
                  newSession,
                  other,
                  true,
                  new PartyRef(world, new PartyRef.Player(other)),
                  true)
              .orElseThrow();
      var changedRequest = PartyOperation.inspection(changed, operationId, requestId());
      assertEquals(
          "OPERATION_CONFLICT",
          changedRequest
              .engine(reopened, newSession)
              .submit(changedRequest.intent())
              .result()
              .code());

      var page = engine.readAfter(0, 2);
      assertEquals(1, page.size());
      var audit = page.getFirst();
      assertEquals(other, audit.actor());
      assertEquals(world, audit.worldId());
      assertEquals(original.originalRequestId(), audit.originalRequestId());
      assertEquals(original.sequence(), audit.sequence());
      assertNotNull(audit.auditInstant().value());
      assertEquals("player:" + player.value(), audit.records().getFirst().payload());
    } finally {
      reopened.close();
    }
  }

  @Test
  void forgedOrCrossOwnerMutationCannotConsumeAnOperationId() throws Exception {
    var store = open();
    try {
      var world = store.snapshot().worldId();
      var owner = new PartyRef(world, new PartyRef.Player(player));
      var kind = new Journal.Kind("fixture:accounts", "fixture:open");
      var grant =
          PartyAuthorization.admit(world, session, player, false, owner, false).orElseThrow();
      var handler =
          new JournalEngine.Registration(
              1,
              (intent, reads) ->
                  new JournalEngine.Reject(new Journal.Result(1, "FIXTURE_REJECTED", "")));
      var operation =
          PartyOperation.owned(
              grant,
              kind,
              new OperationId(UUID.randomUUID()),
              requestId(),
              new Journal.CanonicalRequest(1, Map.of()),
              handler);
      var intent = operation.intent();
      var engine = operation.engine(store, session);
      var forged =
          new Journal.Intent(
              intent.operationId(), requestId(), other, kind, intent.request(), null, null);
      assertEquals("ACCESS_DENIED", engine.submit(forged).result().code());
      assertEquals("FIXTURE_REJECTED", engine.submit(intent).result().code());
      assertTrue(engine.lookup(forged).isEmpty());
      assertEquals("FIXTURE_REJECTED", engine.lookup(intent).orElseThrow().result().code());
    } finally {
      store.close();
    }
  }

  private SqliteStorage open() throws java.io.IOException {
    var store = new SqliteStorage();
    store.initialize(worldPath.toRealPath());
    return store;
  }

  private static RequestId requestId() {
    return new RequestId(UUID.randomUUID());
  }
}
