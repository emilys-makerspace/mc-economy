package ca.emilybelnavis.mcmods.economy.foundation;

import java.util.EnumMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Per-server diagnostic state with one log allowance per event per interval. */
public final class OperationDiagnostics {
  public enum Event {
    OPERATION_REPLAYED,
    OPERATION_PAYLOAD_CONFLICT,
    OPERATION_LOOKUP_UNAVAILABLE,
    OPERATION_ADMISSION_REJECTED,
    OPERATION_BUSINESS_REJECTED,
    OPERATION_STORAGE_FAILED,
    OPERATION_OUTCOME_UNKNOWN,
    OPERATION_PUBLICATION_FAILED
  }

  public record Failure(String code, UUID correlation) {}

  private static final System.Logger LOGGER =
      System.getLogger(OperationDiagnostics.class.getName());
  private static final long INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

  private final EnumMap<Event, Long> lastLogged = new EnumMap<>(Event.class);
  private final LongSupplier nanoTime;
  private final Consumer<String> log;
  private volatile Failure lastFailure;

  public OperationDiagnostics() {
    this(System::nanoTime, message -> LOGGER.log(System.Logger.Level.WARNING, message));
  }

  OperationDiagnostics(LongSupplier nanoTime, Consumer<String> log) {
    this.nanoTime = nanoTime;
    this.log = log;
  }

  public Failure lastFailure() {
    return lastFailure;
  }

  public synchronized void record(Event event, UUID operationId, UUID requestId) {
    if (event != Event.OPERATION_REPLAYED) lastFailure = new Failure(event.name(), operationId);
    long now = nanoTime.getAsLong();
    Long previous = lastLogged.get(event);
    if (previous != null && now - previous < INTERVAL_NANOS) return;
    lastLogged.put(event, now);
    log.accept(
        "event=" + event.name() + " operation_id=" + operationId + " request_id=" + requestId);
  }
}
