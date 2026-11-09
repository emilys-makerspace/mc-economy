package ca.emilybelnavis.mcmods.economy.collection;

import java.util.UUID;

/** Deterministic, finite candidate order independent of mutable random generators. */
public final class CollectionPlacement {
  public static final int CANDIDATES = 16, GLOBAL_BUDGET = 4, RETRY_TICKS = 200;

  public record Offset(int x, int y, int z) {}

  private CollectionPlacement() {}

  public static Offset candidate(UUID world, UUID owner, UUID debt, long attempt, int index) {
    if (attempt < 0 || index < 0 || index >= CANDIDATES)
      throw new IllegalArgumentException("Collection candidate cursor");
    long seed =
        world.getMostSignificantBits()
            ^ owner.getLeastSignificantBits()
            ^ debt.getMostSignificantBits()
            ^ attempt * 0x9E3779B97F4A7C15L;
    long mixed = mix(seed);
    double start = (mixed >>> 11) * 0x1.0p-53 * Math.PI * 2;
    double angle = start + index * Math.PI * (3 - Math.sqrt(5));
    int y = (int) Math.floorMod(mix(seed + index), 7) - 3;
    return new Offset(
        (int) Math.round(Math.cos(angle) * 10), y, (int) Math.round(Math.sin(angle) * 10));
  }

  private static long mix(long value) {
    value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
    value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
    return value ^ (value >>> 31);
  }
}
