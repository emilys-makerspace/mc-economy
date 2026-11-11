package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.banking.BankingSaves;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.sqlite.SQLiteConnection;

/**
 * Disposable fixture checkpoint for intentionally skewed restore tests, not a world-backup feature.
 * The source ledger is opened read-only; SQLite backup includes its consistent WAL-visible
 * snapshot.
 */
public final class BankingSkewCapture {
  private BankingSkewCapture() {}

  public static void capture(MinecraftServer server, ServerPlayer player, UUID claim)
      throws IOException, SQLException {
    require(server.isSameThread(), "capture requires the fixture server thread");
    require(
        server.getPlayerList().getPlayer(player.getUUID()) == player,
        "capture requires the current fixture player");
    var services = Economy.serverServices(server);
    require(
        services != null && services.worldId() != null, "capture requires ready fixture storage");
    var world = server.getWorldPath(LevelResource.ROOT).toRealPath();
    var playerSnapshot = world.resolve("banking-claim-player.dat");
    var ledgerSnapshot = world.resolve("banking-claim-ledger.sqlite");
    var propertiesFile = world.resolve("banking-claim-capture.properties");
    if (exists(propertiesFile)) {
      require(
          regular(propertiesFile) && regular(playerSnapshot) && regular(ledgerSnapshot),
          "existing capture must be complete");
      var existing = new Properties();
      try (var input = Files.newInputStream(propertiesFile)) {
        existing.load(input);
      }
      require(
          player.getUUID().toString().equals(existing.getProperty("player"))
              && claim.toString().equals(existing.getProperty("claim")),
          "existing capture identity differs");
      return;
    }
    require(
        !exists(playerSnapshot) && !exists(ledgerSnapshot),
        "capture refuses to overwrite incomplete checkpoint files");
    var sourceLedger = world.resolve("economy/economy.sqlite");
    var sourcePlayer = world.resolve("playerdata/" + player.getUUID() + ".dat");
    require(
        !Files.isSymbolicLink(sourceLedger.getParent()) && regular(sourceLedger),
        "source ledger must be a real fixture database");
    require(
        !Files.isSymbolicLink(sourcePlayer.getParent()),
        "source player directory must not be linked");

    BankingSaves.player(player, services.worldId(), claim);
    require(
        regular(sourcePlayer) && Files.size(sourcePlayer) <= 1_048_576,
        "saved fixture player is bounded and observable");
    try (var connection = readOnly(sourceLedger)) {
      connection.setAutoCommit(false);
      try {
        boundedSnapshot(connection, player.getUUID(), claim);
        Files.copy(
            sourcePlayer,
            playerSnapshot); // No REPLACE_EXISTING: original checkpoints are immutable.
        Files.createFile(
            ledgerSnapshot); // Reserve a fresh destination before invoking SQLite's backup API.
        int result =
            ((SQLiteConnection) connection)
                .getDatabase()
                .backup("main", ledgerSnapshot.toString(), null, 1, 100, 64);
        require(result == 0, "read-only source backup failed with SQLite code " + result);
      } finally {
        connection.rollback();
      }
    }
    require(
        regular(ledgerSnapshot) && Files.size(ledgerSnapshot) > 0,
        "ledger checkpoint was not written");
    try (var snapshot = readOnly(ledgerSnapshot)) {
      boundedSnapshot(snapshot, player.getUUID(), claim);
      try (var statement = snapshot.createStatement();
          var rows = statement.executeQuery("PRAGMA quick_check")) {
        require(
            rows.next() && rows.getString(1).equals("ok") && !rows.next(),
            "ledger checkpoint integrity");
      }
    }
    var properties = new Properties();
    properties.setProperty("player", player.getUUID().toString());
    properties.setProperty("claim", claim.toString());
    try (var output =
        Files.newOutputStream(
            propertiesFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      properties.store(output, "Disposable older-claim ledger and matching player checkpoint");
    }
    System.out.println("BANKING_SKEW_CAPTURE player=" + player.getUUID() + " claim=" + claim);
  }

  private static Connection readOnly(Path database) throws SQLException {
    var connection =
        DriverManager.getConnection(
            "jdbc:sqlite:" + database.toAbsolutePath().toUri() + "?mode=ro");
    try (var statement = connection.createStatement()) {
      statement.execute("PRAGMA query_only=ON");
      statement.execute("PRAGMA busy_timeout=5000");
    }
    return connection;
  }

  private static void boundedSnapshot(Connection connection, UUID player, UUID claim)
      throws SQLException {
    int balanceRows = 0;
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT account_id FROM ledger_balances LIMIT 513")) {
      while (rows.next()) require(++balanceRows <= 512, "fixture ledger row bound exceeded");
    }
    int caseRows = 0;
    boolean found = false;
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT id,owner,state FROM custody_cases ORDER BY id LIMIT 65")) {
      while (rows.next()) {
        require(++caseRows <= 64, "fixture custody case bound exceeded");
        if (rows.getString("id").equals(claim.toString())) {
          require(
              rows.getString("state").equals("CLAIM")
                  && rows.getString("owner").equals("player:" + player),
              "checkpoint requires the selected owner's stable CLAIM before collection");
          found = true;
        }
      }
    }
    require(found, "selected claim was absent from the bounded fixture snapshot");
  }

  private static boolean exists(Path path) {
    return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
  }

  private static boolean regular(Path path) {
    return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException("Banking skew fixture: " + message);
  }
}
