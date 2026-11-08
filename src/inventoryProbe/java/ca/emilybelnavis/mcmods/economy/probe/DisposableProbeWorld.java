package ca.emilybelnavis.mcmods.economy.probe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Limits opt-in client fixtures to named disposable directories directly under a temporary root. */
final class DisposableProbeWorld {
    private DisposableProbeWorld() {}

    static Path requireClientDirectory(Path gameDirectory, String prefix) throws IOException {
        Path client = gameDirectory.toAbsolutePath().normalize();
        Path run = client.getParent();
        if (run == null || run.getFileName() == null || client.getFileName() == null || !client.getFileName().toString().equals("client")
                || !run.getFileName().toString().startsWith(prefix)
                || run.getFileName().toString().length() == prefix.length()
                || !Files.isDirectory(client, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(run, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Guarded disposable " + prefix + " client root required");
        }
        Path realClient = client.toRealPath();
        Path realRun = run.toRealPath();
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        boolean allowed = realRun.getParent().equals(temporary);
        // Preserve existing macOS commands that explicitly allocate under /private/tmp.
        Path macTemporary = Path.of("/private/tmp");
        if (!allowed && System.getProperty("os.name", "").startsWith("Mac") && Files.isDirectory(macTemporary)) {
            allowed = realRun.getParent().equals(macTemporary.toRealPath());
        }
        if (!allowed || !realClient.equals(realRun.resolve("client"))) {
            throw new IllegalStateException("Disposable client escaped its temporary root");
        }
        return realClient;
    }

    static void requireFreshWorld(Path client, String name) {
        Path saves = client.resolve("saves");
        if (Files.isSymbolicLink(saves) || Files.exists(saves.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Fresh UUID world required");
        }
    }
}
