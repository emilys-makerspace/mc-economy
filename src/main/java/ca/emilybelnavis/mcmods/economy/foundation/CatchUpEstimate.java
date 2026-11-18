package ca.emilybelnavis.mcmods.economy.foundation;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/** Samples on the storage worker. Commands and logs read the same detached result. */
final class CatchUpEstimate {
  private record Sample(long time, long lagMillis) {}

  record Estimate(long sampledAt, long days, Long seconds) {
    Long recentSeconds(long now) {
      return now - sampledAt < TimeUnit.SECONDS.toNanos(5) ? seconds : null;
    }
  }

  private final ArrayDeque<Sample> samples = new ArrayDeque<>();
  private volatile Estimate latest;

  Estimate latest() {
    return latest;
  }

  void reset() {
    samples.clear();
    latest = null;
  }

  void observe(StorageLifecycle.CatchUpCalendar calendar, long now) {
    if (calendar == null || calendar.targetMillis() <= calendar.processedThroughMillis()) {
      reset();
      return;
    }
    // A long pause breaks the observed rate. A moving wall-clock target is included in the lag.
    if (!samples.isEmpty() && now - samples.getLast().time() >= TimeUnit.SECONDS.toNanos(5))
      samples.clear();
    while (!samples.isEmpty() && now - samples.getFirst().time() > TimeUnit.SECONDS.toNanos(30))
      samples.removeFirst();
    long remaining = calendar.targetMillis() - calendar.processedThroughMillis();
    samples.addLast(new Sample(now, remaining));
    Sample first = samples.getFirst();
    long elapsed = now - first.time();
    long gained = first.lagMillis() - remaining;
    Long seconds = null;
    if (gained > 0 && elapsed >= TimeUnit.SECONDS.toNanos(5)) {
      seconds = (long) Math.ceil((remaining / (double) gained) * (elapsed / 1_000_000_000.0));
    }
    latest = new Estimate(now, Math.ceilDiv(remaining, calendar.dayMillis()), seconds);
  }
}
