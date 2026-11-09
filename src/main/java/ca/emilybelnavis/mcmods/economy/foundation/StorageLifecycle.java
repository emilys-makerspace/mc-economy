package ca.emilybelnavis.mcmods.economy.foundation;

import java.nio.file.Path;

/** All methods run on the sole storage worker. Implementations must honor interruption. */
public interface StorageLifecycle {
  void initialize(Path worldDirectory) throws Exception;

  void close() throws Exception;

  record Progress(String phase, long completed, long target, boolean ready) {}

  /** One bounded startup unit; no Minecraft objects, no server-thread storage waits. */
  default Progress startupStep() {
    return new Progress("ready", 1, 1, true);
  }

  record EconomicProgress(
      long completedSequence, long outstandingIntervals, boolean waitingForWall, boolean ready) {}

  /**
   * The supplier captures detached inputs only. The commit callback stays off the calculation
   * worker.
   */
  record EconomicStep<T>(
      EconomicProgress progress,
      java.util.function.Supplier<T> calculation,
      java.util.function.Function<T, EconomicProgress> commit) {}

  default boolean schedulesEconomy() {
    return false;
  }

  default EconomicStep<?> prepareEconomicStep() {
    throw new IllegalStateException("No economic scheduler");
  }

  default void requireEconomicCurrent() {}

  /** A1-01 has no persistence. This adapter must be replaced when storage is introduced. */
  static StorageLifecycle foundationOnly() {
    return new StorageLifecycle() {
      public void initialize(Path worldDirectory) {}

      public void close() {}
    };
  }
}
