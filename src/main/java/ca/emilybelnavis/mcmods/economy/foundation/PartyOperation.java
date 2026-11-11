package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** An operation constructed from server authority and a canonical, owner-bound request. */
record PartyOperation(
    PartyAuthorization.Grant grant,
    Journal.Intent intent,
    JournalEngine.Registration registration) {
  static PartyOperation owned(
      PartyAuthorization.Grant grant,
      Journal.Kind kind,
      OperationId operationId,
      RequestId requestId,
      Journal.CanonicalRequest request,
      JournalEngine.Registration registration) {
    if (kind.equals(InspectionAudit.KIND))
      throw new IllegalArgumentException("Reserved inspection operation");
    return bind(grant, kind, operationId, requestId, request, registration);
  }

  static PartyOperation inspection(
      PartyAuthorization.Grant grant, OperationId operationId, RequestId requestId) {
    return inspection(grant, operationId, requestId, Map.of());
  }

  static PartyOperation inspection(
      PartyAuthorization.Grant grant,
      OperationId operationId,
      RequestId requestId,
      Map<String, String> query) {
    return bind(
        grant,
        InspectionAudit.KIND,
        operationId,
        requestId,
        new Journal.CanonicalRequest(1, query),
        InspectionAudit.registration(PartyAuthorization.ownerKey(grant.target().owner())));
  }

  JournalEngine engine(FoundationStore store, UUID session) {
    var kind = intent.kind();
    return new JournalEngine(
        store,
        (actor, requestedKind) ->
            kind.equals(requestedKind)
                && grant.allows(store.snapshot().worldId(), session, actor, requestedKind),
        Map.of(kind, registration));
  }

  private static PartyOperation bind(
      PartyAuthorization.Grant grant,
      Journal.Kind kind,
      OperationId operationId,
      RequestId requestId,
      Journal.CanonicalRequest request,
      JournalEngine.Registration registration) {
    var fields = new HashMap<>(request.fields());
    if (fields.putIfAbsent("economy:owner", PartyAuthorization.ownerKey(grant.target().owner()))
        != null) throw new IllegalArgumentException("Reserved canonical owner field");
    var canonical = new Journal.CanonicalRequest(request.version(), fields);
    var intent =
        new Journal.Intent(operationId, requestId, grant.actor(), kind, canonical, null, null);
    return new PartyOperation(grant, intent, Objects.requireNonNull(registration));
  }
}
