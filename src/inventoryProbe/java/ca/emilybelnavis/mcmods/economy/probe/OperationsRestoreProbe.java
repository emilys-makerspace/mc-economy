package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import java.nio.file.*;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** One stopped-backup restored-copy readiness witness; never runs workload setup again. */
public final class OperationsRestoreProbe {
  private static Path root;
  private static long started;
  private static boolean checking, ready, failed;
  private static ServerServices services;

  private OperationsRestoreProbe() {}

  public static void register() {
    NeoForge.EVENT_BUS.register(OperationsRestoreProbe.class);
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void tick(ServerTickEvent.Post event) {
    if (ready || failed) return;
    var server = event.getServer();
    try {
      if (root == null) {
        root = Path.of(System.getProperty("economy.operationsRoot")).toRealPath();
        if (!root.getParent().equals(Path.of("/private/tmp").toRealPath())
            || !root.getFileName().toString().startsWith("economy-a10-08-")
            || !server.isDedicatedServer()
            || !server
                .getWorldPath(LevelResource.ROOT)
                .toRealPath()
                .equals(root.resolve("server/restored-world")))
          throw new IllegalStateException("Guarded separate restored world required");
        started = System.nanoTime();
      }
      if (System.nanoTime() - started > 120_000_000_000L)
        throw new IllegalStateException("Restored readiness deadline");
      services = Economy.serverServices(server);
      if (services == null) return;
      if (services.state() == ServerServices.State.UNAVAILABLE)
        throw new IllegalStateException("Restored foundation unavailable", services.failure());
      if (checking
          || services.state() != ServerServices.State.READY
          || !services.metrics().economy().ready()) return;
      checking = true;
      services
          .tradeStorage(
              store -> {
                try {
                  store.requireEconomicCurrent();
                } catch (ca.emilybelnavis.mcmods.economy.storage.StorageException e) {
                  if (e.kind()
                      == ca.emilybelnavis.mcmods.economy.storage.StorageException.Kind
                          .ECONOMIC_CATCH_UP) return null;
                  throw e;
                }
                return store.transaction(
                    tx ->
                        "world="
                            + store.snapshot().worldId().value()
                            + " journal="
                            + tx.query(
                                    "SELECT COALESCE(max(sequence),0) AS n FROM journal_transactions",
                                    List.of(),
                                    1)
                                .getFirst()
                                .get("n"));
              })
          .whenComplete(
              (snapshot, error) -> {
                checking = false;
                if (error != null) fail(server, error);
                else if (snapshot != null) {
                  try {
                    Files.writeString(
                        root.resolve("restore-readiness.txt"),
                        "READY "
                            + snapshot
                            + "\n"
                            + String.join("\n", services.diagnostics(true, true))
                            + "\n");
                    ready = true;
                    System.out.println("A10_RESTORE READY " + snapshot + "; normal shutdown");
                    server.halt(false);
                  } catch (Exception e) {
                    fail(server, e);
                  }
                }
              });
    } catch (Throwable e) {
      fail(server, e);
    }
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void stopped(ServerStoppedEvent event) {
    if (ready
        && !failed
        && services.state() == ServerServices.State.STOPPED
        && services.failure() == null
        && services.metrics().unresolvedAtShutdown() == 0) {
      try {
        Files.writeString(
            root.resolve("operations-restore-passed.txt"),
            "PASS separate restored copy reached worker readiness and normal drained stop\n");
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private static void fail(MinecraftServer server, Throwable error) {
    failed = true;
    error.printStackTrace();
    try {
      if (root != null)
        Files.writeString(root.resolve("operations-restore-failed.txt"), error.toString());
    } catch (Exception ignored) {
    }
    server.halt(false);
  }
}
