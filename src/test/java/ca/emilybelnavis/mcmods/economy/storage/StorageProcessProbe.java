package ca.emilybelnavis.mcmods.economy.storage;

import java.nio.file.Path;

/** Disposable child process used only to prove OS ownership; never packaged. */
public final class StorageProcessProbe {
  public static void main(String[] args) throws Exception {
    var store = new SqliteStorage();
    store.initialize(Path.of(args[0]));
    System.out.println("READY");
    System.out.flush();
    try {
      System.in.read();
    } finally {
      store.close();
    }
  }
}
