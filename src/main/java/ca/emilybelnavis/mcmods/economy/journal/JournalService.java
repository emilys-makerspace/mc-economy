package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.foundation.OperationDiagnostics.Event;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Server-thread admission/publication around the one storage worker. No networking or external
 * delivery.
 */
public final class JournalService {
  public interface Publisher {
    void invalidate(Outcome committed);

    void publish(Outcome terminal);
  }

  /**
   * Session and menu instance IDs are assigned by the server. A null menu denotes player-only
   * delivery.
   */
  public record LiveTarget(UUID session, PlayerId player, UUID menu) {
    public LiveTarget {
      Objects.requireNonNull(session);
      Objects.requireNonNull(player);
    }
  }

  public enum DeliveryStatus {
    PUBLISHED,
    FAILED,
    SKIPPED
  }

  public record Delivery(Outcome outcome, DeliveryStatus publication) {}

  public record Submission(
      ca.emilybelnavis.mcmods.economy.api.Identifiers.RequestId attemptRequestId,
      Receipt receipt,
      CompletionStage<Delivery> completion) {}

  private final Thread owner = Thread.currentThread();
  private final ServerServices services;
  private final JournalEngine engine;
  private final Publisher publisher;

  private record LookupKey(JournalService service, Intent intent) {}

  public JournalService(ServerServices services, JournalEngine engine, Publisher publisher) {
    this.services = java.util.Objects.requireNonNull(services);
    this.engine = java.util.Objects.requireNonNull(engine);
    this.publisher = java.util.Objects.requireNonNull(publisher);
  }

  public Submission submit(Intent intent) {
    return submit(
        intent,
        outcome -> {
          publisher.publish(outcome);
          return DeliveryStatus.PUBLISHED;
        });
  }

  /**
   * Resolve on publication. Return null if the player disconnected or the menu instance changed.
   */
  public Submission submit(
      Intent intent, LiveTarget target, Function<LiveTarget, Consumer<Outcome>> resolveLive) {
    checkOwner();
    Objects.requireNonNull(resolveLive);
    if (!services.sessionId().equals(target.session()) || !intent.actor().equals(target.player()))
      throw new IllegalArgumentException("Live target does not match the authenticated request");
    return submit(
        intent,
        outcome -> {
          var deliver = resolveLive.apply(target);
          if (deliver == null) return DeliveryStatus.SKIPPED;
          deliver.accept(outcome);
          return DeliveryStatus.PUBLISHED;
        });
  }

  private Submission submit(Intent intent, Function<Outcome, DeliveryStatus> publish) {
    checkOwner();
    CompletionStage<JournalEngine.Attempt> work;
    try {
      work = services.economicWrite(intent.actor(), () -> engine.submitAttempt(intent));
    } catch (IllegalStateException unavailable) {
      record(intent, Event.OPERATION_ADMISSION_REJECTED);
      return new Submission(
          intent.requestId(),
          Receipt.NOT_ACCEPTED,
          CompletableFuture.completedStage(
              new Delivery(
                  nonterminal(intent, Status.NOT_ACCEPTED, "UNAVAILABLE"),
                  DeliveryStatus.SKIPPED)));
    }
    Receipt receipt =
        work.toCompletableFuture().isCompletedExceptionally()
            ? Receipt.NOT_ACCEPTED
            : Receipt.ACCEPTED_PENDING;
    // This independent stage means cancellation/disconnect cannot cancel accepted storage work.
    CompletionStage<Delivery> completion =
        work.handle(
            (attempt, error) -> {
              if (error != null) {
                Throwable cause = unwrap(error);
                if (cause instanceof StorageException storage) {
                  if (storage.kind() == StorageException.Kind.OVERLOAD) {
                    record(intent, Event.OPERATION_ADMISSION_REJECTED);
                    return new Delivery(
                        nonterminal(intent, Status.NOT_ACCEPTED, "OVERLOADED"),
                        DeliveryStatus.SKIPPED);
                  }
                  if (storage.kind() == StorageException.Kind.ECONOMIC_CATCH_UP) {
                    record(intent, Event.OPERATION_ADMISSION_REJECTED);
                    return new Delivery(
                        nonterminal(intent, Status.NOT_ACCEPTED, "ECONOMIC_CATCH_UP"),
                        DeliveryStatus.SKIPPED);
                  }
                  record(
                      intent,
                      storage.kind() == StorageException.Kind.UNKNOWN_COMMIT
                          ? Event.OPERATION_OUTCOME_UNKNOWN
                          : Event.OPERATION_STORAGE_FAILED);
                } else {
                  record(intent, Event.OPERATION_OUTCOME_UNKNOWN);
                }
                return new Delivery(
                    nonterminal(intent, Status.UNKNOWN, "LOOKUP_OR_RETRY_SAME_ID"),
                    DeliveryStatus.SKIPPED);
              }
              var outcome = attempt.outcome();
              if (attempt.replay()) record(intent, Event.OPERATION_REPLAYED);
              switch (outcome.status()) {
                case NOT_ACCEPTED ->
                    record(
                        intent,
                        "OPERATION_CONFLICT".equals(outcome.result().code())
                            ? Event.OPERATION_PAYLOAD_CONFLICT
                            : Event.OPERATION_ADMISSION_REJECTED);
                case DURABLY_REJECTED -> record(intent, Event.OPERATION_BUSINESS_REJECTED);
                case UNKNOWN -> record(intent, Event.OPERATION_OUTCOME_UNKNOWN);
                case COMMITTED -> {}
              }
              if (outcome.status() != Status.COMMITTED
                  && outcome.status() != Status.DURABLY_REJECTED)
                return new Delivery(outcome, DeliveryStatus.SKIPPED);
              try {
                if (outcome.status() == Status.COMMITTED) {
                  services.denominationCommitted(intent.kind());
                  publisher.invalidate(outcome);
                }
                return new Delivery(outcome, publish.apply(outcome));
              } catch (Throwable deliveryFailure) {
                // Invalidation is best-effort even if a cache refresh or the first invalidation
                // failed.
                if (outcome.status() == Status.COMMITTED) {
                  try {
                    publisher.invalidate(outcome);
                  } catch (Throwable ignored) {
                  }
                }
                record(intent, Event.OPERATION_PUBLICATION_FAILED);
                return new Delivery(outcome, DeliveryStatus.FAILED);
              }
            });
    return new Submission(intent.requestId(), receipt, completion);
  }

  /** No outcome is disclosed unless current policy, bound actor, service and type match. */
  public CompletionStage<Optional<Outcome>> lookup(Intent intent) {
    checkOwner();
    try {
      var lookup =
          services
              .shareStorageRead(
                  intent.actor(), new LookupKey(this, intent), () -> engine.lookup(intent))
              .thenApply(
                  outcome -> {
                    if (outcome.isPresent() && outcome.get().status() == Status.COMMITTED)
                      services.denominationCommitted(intent.kind());
                    return outcome;
                  });
      lookup.whenComplete(
          (outcome, error) -> {
            if (error != null) record(intent, Event.OPERATION_LOOKUP_UNAVAILABLE);
          });
      return lookup;
    } catch (IllegalStateException unavailable) {
      record(intent, Event.OPERATION_LOOKUP_UNAVAILABLE);
      throw unavailable;
    }
  }

  private void record(Intent intent, Event event) {
    services
        .operationDiagnostics()
        .record(event, intent.operationId().value(), intent.requestId().value());
  }

  private static Throwable unwrap(Throwable error) {
    while ((error instanceof CompletionException || error instanceof ExecutionException)
        && error.getCause() != null) error = error.getCause();
    return error;
  }

  private void checkOwner() {
    if (Thread.currentThread() != owner) throw new IllegalStateException("Requires server thread");
  }
}
