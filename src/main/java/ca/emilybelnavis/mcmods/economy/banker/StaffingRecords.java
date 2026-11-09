package ca.emilybelnavis.mcmods.economy.banker;

import ca.emilybelnavis.mcmods.economy.journal.Journal;
import java.util.List;
import java.util.UUID;

/**
 * Detached presentation records. Entity existence and banking authority are separate observations.
 */
public final class StaffingRecords {
  private StaffingRecords() {}

  public enum Action {
    ATTACH,
    BEGIN,
    LIVE,
    DEATH,
    DESK_REMOVED,
    CHECKPOINT,
    REPLACE,
    UNKNOWN,
    DEFER,
    PAUSE;

    public Journal.Kind kind() {
      return new Journal.Kind(
          "economy:bank_staffing", "economy:" + name().toLowerCase(java.util.Locale.ROOT));
    }
  }

  public record Row(
      UUID branch,
      int slot,
      UUID deskGeneration,
      String dimension,
      int x,
      int y,
      int z,
      String deskState,
      UUID assignment,
      UUID employee,
      String phase,
      int elapsed,
      long revision,
      UUID operation) {}

  public record Progress(int slot, long revision, int delta) {}

  public record Command(
      Action action,
      UUID branch,
      long branchRevision,
      int slot,
      long revision,
      UUID assignment,
      UUID employee,
      int delta,
      boolean paused,
      List<Progress> progress) {
    public Command {
      progress = List.copyOf(progress);
    }
  }
}
