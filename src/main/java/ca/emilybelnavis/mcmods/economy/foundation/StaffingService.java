package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banker.StaffingRecords.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Internal detached staffing queries and journal submissions; never touches Minecraft objects. */
public final class StaffingService {
  private final ServerServices services;
  private final FoundationStore store;

  StaffingService(ServerServices services, FoundationStore store) {
    this.services = services;
    this.store = store;
  }

  public CompletionStage<List<Row>> rows(UUID branch) {
    var world = services.worldId();
    return services.storage(
        () -> store.transaction(tx -> BankStaffing.rows(tx::query, world, branch)));
  }

  public CompletionStage<Optional<Row>> find(UUID branch, int slot) {
    var world = services.worldId();
    return services.storage(
        () -> store.transaction(tx -> BankStaffing.find(tx::query, world, branch, slot)));
  }

  public BranchService.Pending update(
      Command command, ActorId actor, UUID operation, AtomicBoolean live) {
    var intent =
        new Journal.Intent(
            new OperationId(operation),
            new RequestId(operation),
            actor,
            command.action().kind(),
            BankStaffing.request(command),
            null,
            null);
    var journal =
        services.journal(
            (who, kind) -> who.equals(actor) && kind.equals(command.action().kind()),
            Map.of(
                command.action().kind(),
                BankStaffing.registration(
                    services.worldId(),
                    candidate -> live.get() && candidate.equals(intent),
                    command)),
            null);
    return new BranchService.Pending(journal, intent);
  }
}
