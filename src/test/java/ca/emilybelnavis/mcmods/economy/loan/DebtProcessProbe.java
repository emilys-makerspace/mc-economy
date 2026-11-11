package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher;
import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** Disposable child, paused only after a real default or sale transaction commits. */
public final class DebtProcessProbe {
  public static void main(String[] args) throws Exception {
    var store = new SqliteStorage();
    store.initialize(Path.of(args[0]));
    var providers = new ArrayList<>(DebtRuntime.providers());
    providers.add(LoanRuntime.provider());
    var scheduler =
        new DeadlineDispatcher(
            store,
            Clock.fixed(Instant.ofEpochMilli(Long.parseLong(args[2])), ZoneOffset.UTC),
            providers,
            1);
    scheduler.prepare();
    for (int turn = 0; turn < 1024; turn++) {
      scheduler.advance();
      var debt =
          store.transaction(
              tx ->
                  DebtLifecycle.facilityCase(
                      tx::query, DebtRecords.Kind.LOAN, UUID.fromString(args[1])));
      if (debt.isPresent() && debt.orElseThrow().sale().name().equals(args[3])) {
        System.out.println(
            "BOUNDARY|" + debt.orElseThrow().id() + "|" + store.lastCommittedSequence());
        System.out.flush();
        if (System.in.read() < 0) throw new IllegalStateException("Parent closed fixture input");
      }
    }
    throw new IllegalStateException("Requested fixture boundary was not reached");
  }
}
