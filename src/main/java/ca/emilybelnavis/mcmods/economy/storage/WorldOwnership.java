package ca.emilybelnavis.mcmods.economy.storage;

import static java.nio.file.StandardOpenOption.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;

/** Persistent lock filename, OS lock lifetime. Never unlink a lock file on release. */
final class WorldOwnership implements AutoCloseable {
  private final FileChannel channel;
  private final FileLock lock;

  private WorldOwnership(FileChannel channel, FileLock lock) {
    this.channel = channel;
    this.lock = lock;
  }

  static WorldOwnership acquire(Path path) throws IOException {
    FileChannel channel = FileChannel.open(path, CREATE, WRITE);
    try {
      FileLock lock = channel.tryLock();
      if (lock == null)
        throw new StorageException(
            StorageException.Kind.LOCK_TIMEOUT, "World already owned: " + path);
      return new WorldOwnership(channel, lock);
    } catch (OverlappingFileLockException e) {
      channel.close();
      throw new StorageException(
          StorageException.Kind.LOCK_TIMEOUT, "World already owned: " + path, e);
    } catch (IOException | RuntimeException e) {
      channel.close();
      throw e;
    }
  }

  public void close() throws IOException {
    try {
      lock.release();
    } finally {
      channel.close();
    }
  }
}
