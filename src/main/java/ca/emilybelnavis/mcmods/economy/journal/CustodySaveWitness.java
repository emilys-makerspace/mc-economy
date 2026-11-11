package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded observation of a known persisted inventory file. Trusted adapters must include the world,
 * custody and generation marker in the expected serialized state, together with exact item
 * components and counts.
 */
public final class CustodySaveWitness {
  public static final int MAX_BYTES = 1_048_576;

  public record Expected(
      WorldId world, UUID custody, UUID generation, String location, String sha256) {
    public Expected {
      Objects.requireNonNull(world);
      Objects.requireNonNull(custody);
      Objects.requireNonNull(generation);
      Objects.requireNonNull(location);
      if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Witness digest");
      var relative = Path.of(location);
      if (relative.isAbsolute()
          || relative.getNameCount() == 0
          || !relative.normalize().equals(relative)
          || location.contains("..")
          || location.length() > 512) throw new IllegalArgumentException("Witness location");
    }

    public String token() {
      return "v1:" + world.value() + ":" + custody + ":" + generation + ":" + sha256;
    }
  }

  public enum Status {
    MATCH,
    MISMATCH,
    MISSING,
    SCAN_LIMIT,
    UNSAFE_LOCATION,
    UNAVAILABLE
  }

  private CustodySaveWitness() {}

  /** Missing, changed, oversized or inaccessible files cannot establish a save witness. */
  public static boolean matches(Path worldDirectory, Expected expected) {
    return observe(worldDirectory, expected) == Status.MATCH;
  }

  /** Read-only reason for the bounded observation, without changing custody state. */
  public static Status observe(Path worldDirectory, Expected expected) {
    try {
      var root = worldDirectory.toRealPath();
      var file = root.resolve(expected.location());
      var current = root;
      for (var component : Path.of(expected.location())) {
        current = current.resolve(component);
        if (Files.isSymbolicLink(current)) return Status.UNSAFE_LOCATION;
      }
      if (!file.toRealPath().startsWith(root)
          || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Status.UNSAFE_LOCATION;
      if (Files.size(file) > MAX_BYTES) return Status.SCAN_LIMIT;
      var digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
          total = Math.addExact(total, read);
          if (total > MAX_BYTES) return Status.SCAN_LIMIT;
          digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest()).equals(expected.sha256())
          ? Status.MATCH
          : Status.MISMATCH;
    } catch (NoSuchFileException missing) {
      return Status.MISSING;
    } catch (IOException | SecurityException error) {
      return Status.UNAVAILABLE;
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  public static String digest(byte[] expectedSerializedState) {
    if (expectedSerializedState.length > MAX_BYTES)
      throw new IllegalArgumentException("Witness size");
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(expectedSerializedState));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
