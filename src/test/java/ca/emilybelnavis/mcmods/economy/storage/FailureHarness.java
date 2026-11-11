package ca.emilybelnavis.mcmods.economy.storage;

import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.atomic.*;

/** Test-only JDBC boundary faults, armed after initialization. */
public final class FailureHarness {
  public enum Fault {
    NONE,
    BEFORE_COMMIT,
    AFTER_COMMIT,
    RESET,
    ROLLBACK,
    ROLLBACK_AND_CLOSE,
    LOST_COMMIT_AND_CLOSE
  }

  public final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);
  public final AtomicInteger opens = new AtomicInteger();
  public final SqliteStorage store =
      new SqliteStorage(
          (path, create) -> {
            opens.incrementAndGet();
            Connection delegate =
                DriverManager.getConnection(
                    create ? "jdbc:sqlite:" + path : "jdbc:sqlite:" + path.toUri() + "?mode=rw");
            return (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                      Fault current = fault.get();
                      String name = method.getName();
                      if (name.equals("close")
                          && (current == Fault.ROLLBACK_AND_CLOSE
                              || current == Fault.LOST_COMMIT_AND_CLOSE))
                        throw new SQLException("fixture close failure");
                      if (name.equals("rollback")
                          && (current == Fault.ROLLBACK || current == Fault.ROLLBACK_AND_CLOSE)) {
                        if (current == Fault.ROLLBACK) fault.set(Fault.NONE);
                        throw new SQLException("fixture rollback failure");
                      }
                      if (name.equals("commit") && current == Fault.BEFORE_COMMIT) {
                        fault.set(Fault.NONE);
                        throw new SQLException("fixture before commit");
                      }
                      Object result;
                      try {
                        result = method.invoke(delegate, args);
                      } catch (InvocationTargetException e) {
                        throw e.getCause();
                      }
                      if (name.equals("commit")
                          && (current == Fault.AFTER_COMMIT
                              || current == Fault.LOST_COMMIT_AND_CLOSE)) {
                        if (current == Fault.AFTER_COMMIT) fault.set(Fault.NONE);
                        throw new SQLException("fixture lost commit response");
                      }
                      if (name.equals("setAutoCommit")
                          && Boolean.TRUE.equals(args[0])
                          && current == Fault.RESET) {
                        fault.set(Fault.NONE);
                        throw new SQLException("fixture postcommit cleanup failure");
                      }
                      return result;
                    });
          },
          FixtureRecords.catalog());
}
