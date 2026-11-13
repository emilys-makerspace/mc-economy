package ca.emilybelnavis.mcmods.economy.storage;

import java.sql.SQLException;

/** Typed, non-retrying persistence failure. Commit uncertainty is resolved by A1-03. */
public final class StorageException extends RuntimeException {
  public enum Kind {
    CONFIGURATION_DRIVER,
    IDENTITY,
    SCHEMA,
    UPGRADE_REQUIRED,
    CORRUPTION_IO,
    LOCK_TIMEOUT,
    OVERLOAD,
    ECONOMIC_CATCH_UP,
    MIGRATION,
    UNKNOWN_COMMIT,
    UNSAFE_CONNECTION
  }

  private final Kind kind;

  public StorageException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public StorageException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  public boolean fatal() {
    return kind != Kind.LOCK_TIMEOUT && kind != Kind.OVERLOAD && kind != Kind.ECONOMIC_CATCH_UP;
  }

  static StorageException sql(SQLException error) {
    int code = error.getErrorCode() & 255;
    return new StorageException(
        code == 5 || code == 6 ? Kind.LOCK_TIMEOUT : Kind.CORRUPTION_IO,
        "SQLite operation failed; no automatic retry",
        error);
  }
}
