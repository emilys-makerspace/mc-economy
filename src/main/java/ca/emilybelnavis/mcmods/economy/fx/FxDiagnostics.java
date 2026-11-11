package ca.emilybelnavis.mcmods.economy.fx;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Detached operational state from one storage-worker read transaction. */
public record FxDiagnostics(
    Instant sampledAt,
    EconomyCurrency reference,
    LocalDate lastDay,
    UUID effectivePolicy,
    LocalDate nextDay,
    long backlog,
    boolean waitingForWall,
    PendingPolicy pending,
    boolean chfAvailable,
    LocalDate chfScheduledDay,
    UUID snapshotId,
    long effectiveMillis,
    boolean retired) {
  public FxDiagnostics(
      Instant sampledAt,
      EconomyCurrency reference,
      LocalDate lastDay,
      UUID effectivePolicy,
      LocalDate nextDay,
      long backlog,
      boolean waitingForWall,
      PendingPolicy pending,
      boolean chfAvailable,
      LocalDate chfScheduledDay) {
    this(
        sampledAt,
        reference,
        lastDay,
        effectivePolicy,
        nextDay,
        backlog,
        waitingForWall,
        pending,
        chfAvailable,
        chfScheduledDay,
        null,
        -1,
        false);
  }

  public FxDiagnostics(
      Instant sampledAt,
      EconomyCurrency reference,
      LocalDate lastDay,
      UUID effectivePolicy,
      LocalDate nextDay,
      long backlog,
      boolean waitingForWall,
      PendingPolicy pending) {
    this(
        sampledAt,
        reference,
        lastDay,
        effectivePolicy,
        nextDay,
        backlog,
        waitingForWall,
        pending,
        false,
        null);
  }

  public record PendingPolicy(LocalDate day, UUID id) {}

  public static FxDiagnostics read(FoundationStore.Transaction tx, Instant now) {
    var snapshot = FxService.snapshot(tx::query);
    var publication = FxService.latestDaily(tx::query);
    if (publication == null || snapshot == null)
      return new FxDiagnostics(now, null, null, null, null, 0, false, null);

    var rows =
        tx.query(
            "SELECT frontier_ms,active_observed_ms FROM economic_systems WHERE system_id=?",
            List.of(FxService.SYSTEM),
            1);
    var lastDay = publication.day();
    long frontier = lastDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    long observed = frontier;
    if (!rows.isEmpty()) {
      var row = rows.getFirst();
      if (Long.parseLong(row.get("frontier_ms")) != frontier
          || row.get("active_observed_ms") == null) {
        throw new StorageException(
            StorageException.Kind.SCHEMA, "FX diagnostic checkpoint mismatch");
      }
      observed = Long.parseLong(row.get("active_observed_ms"));
    }

    long wall = now.toEpochMilli();
    long target =
        ca.emilybelnavis.mcmods.economy.clock.FixedRetirements.eligibleTarget(
            tx::query, FxService.SYSTEM, Math.max(wall, observed));
    long backlog = Math.max(0, target - frontier) / FxService.DAY_MILLIS;
    boolean waiting = wall < frontier || (wall < observed && backlog > 0);
    var pending = FxService.nextPolicy(tx::query, lastDay);
    var retirement =
        ca.emilybelnavis.mcmods.economy.clock.FixedRetirements.read(tx::query, FxService.SYSTEM);
    LocalDate next =
        retirement != null && frontier + FxService.DAY_MILLIS >= retirement.firstExcludedMillis()
            ? null
            : lastDay.plusDays(1);
    return new FxDiagnostics(
        now,
        snapshot.reference(),
        lastDay,
        snapshot.policy(),
        next,
        backlog,
        waiting,
        pending == null ? null : new PendingPolicy(pending.effectiveDay(), pending.id()),
        snapshot.rates().containsKey(EconomyCurrency.CHF),
        FxService.chfActivationDay(tx::query),
        snapshot.id(),
        snapshot.effectiveMillis(),
        retirement != null);
  }
}
