package ca.emilybelnavis.mcmods.economy.foundation;

/**
 * Detached snapshot taken on the server thread. Ages measure time waiting, using a monotonic clock.
 */
public record SchedulingMetrics(
    Queue storage,
    Queue calculation,
    Queue publication,
    StorageLifecycle.Progress recovery,
    StorageLifecycle.EconomicProgress economy,
    boolean economicStepInFlight,
    int unresolvedAtShutdown) {
  public record Queue(int capacity, int depth, long oldestAgeNanos, long oldestActiveAgeNanos) {
    public Queue(int capacity, int depth, long oldestAgeNanos) {
      this(capacity, depth, oldestAgeNanos, 0);
    }
  }
}
