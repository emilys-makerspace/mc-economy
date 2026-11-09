package ca.emilybelnavis.mcmods.economy.recovery;

import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/** Bounded shutdown-only evidence: any logged save/close error prevents a clean-world witness. */
public final class ShutdownErrors extends AbstractAppender implements AutoCloseable {
  private final AtomicBoolean failed = new AtomicBoolean();
  private final org.apache.logging.log4j.core.Logger root;

  public ShutdownErrors() {
    super(
        "economy-shutdown-" + java.util.UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY);
    root = LoggerContext.getContext(false).getRootLogger();
    start();
    root.addAppender(this);
  }

  public void append(LogEvent event) {
    if (event.getLevel().isMoreSpecificThan(Level.ERROR)) failed.set(true);
  }

  public boolean failed() {
    return failed.get();
  }

  public void close() {
    root.removeAppender(this);
    stop();
  }
}
