package ca.emilybelnavis.mcmods.economy.storage;

import static java.nio.file.StandardOpenOption.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class StorageFiles {
  private StorageFiles() {}

  static void rejectLinks(Path path) throws IOException {
    Path current = path.toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isSymbolicLink(current))
        throw new IOException("Symbolic link not allowed: " + current);
      current = current.getParent();
    }
  }

  static String hash(Path path) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(path)) {
        byte[] buffer = new byte[65536];
        for (int n; (n = input.read(buffer)) != -1; ) {
          if (Thread.currentThread().isInterrupted())
            throw new java.io.InterruptedIOException("Storage hashing interrupted");
          digest.update(buffer, 0, n);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  static void writeNew(Path path, String text) throws IOException {
    if (Files.exists(path)) throw new FileAlreadyExistsException(path.toString());
    publish(path, text);
  }

  static void replace(Path path, String text) throws IOException {
    publish(path, text);
  }

  private static void publish(Path path, String text) throws IOException {
    Path temporary =
        Files.createTempFile(path.getParent(), path.getFileName() + ".writing-", ".tmp");
    try {
      byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      try (FileChannel channel = FileChannel.open(temporary, WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      Files.move(
          temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      forceDirectory(path.getParent());
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  static void forceDirectory(Path directory) throws IOException {
    try (FileChannel channel = FileChannel.open(directory, READ)) {
      channel.force(true);
    }
  }
}
