package ca.emilybelnavis.mcmods.economy.collection;

import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Optional server integration seam. Physical suppression never changes financial state. */
public final class CollectionProtection {
  public enum Action {
    SPAWN,
    INTERACT
  }

  public record Context(ServerPlayer debtor, Vec3 position, Action action) {}

  private static volatile Predicate<Context> predicate = context -> true;

  private CollectionProtection() {}

  public static void setPredicate(Predicate<Context> replacement) {
    predicate = Objects.requireNonNull(replacement);
  }

  public static void reset() {
    predicate = context -> true;
  }

  public static boolean allows(ServerPlayer player, Vec3 position, Action action) {
    try {
      return predicate.test(new Context(player, position, action));
    } catch (RuntimeException unavailable) {
      return false;
    }
  }
}
