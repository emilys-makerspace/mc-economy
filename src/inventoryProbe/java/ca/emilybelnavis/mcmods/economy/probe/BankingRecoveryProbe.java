package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Disposable process termination and restored normal-menu quarantine checks. Never included in
 * release jars.
 */
public final class BankingRecoveryProbe {
  private static final int FILE_LIMIT = 1_048_576;

  private record CaseRow(UUID id, UUID owner) {}

  private static int phase, actorIndex;
  private static long deadline, waitUntil, claimsRevision;
  private static Path root;
  private static Map<String, Long> expected;
  private static List<CaseRow> cases = List.of();
  private static final LinkedHashMap<UUID, CompoundTag> playerFiles = new LinkedHashMap<>();
  private static final List<ServerPlayer> actors = new ArrayList<>();
  private static final Map<UUID, Tag> baselines = new HashMap<>();
  private static CompoundTag pendingBefore;
  private static Journal.Intent captured;
  private static Journal.Outcome capturedOutcome;
  private static boolean lookupStarted, lookupFinished;
  private static String asynchronousFailure;

  private BankingRecoveryProbe() {}

  /**
   * Explicit opt-in, only in the disposable fixture process. Captures a normal unresolved COMMIT
   * then halts.
   */
  public static void maybeCrash(MinecraftServer server) {
    String selected = System.getProperty("economy.bankingCrash", "");
    if (!Set.of("withdrawals", "deposits", "register_sales").contains(selected)) return;
    for (var player : server.getPlayerList().getPlayers()) {
      var pending = player.getPersistentData().getCompound("economy_banking_cash_pending");
      if (!pending.hasUUID("operation")
          || !pending.getString("service").equals("economy:" + selected)
          || !pending.getString("type").equals("economy:commit")) continue;
      var services = Economy.serverServices(server);
      if (services == null || services.worldId() == null)
        throw new IllegalStateException("Crash capture requires READY fixture storage");
      var capture = new CompoundTag();
      capture.putUUID("world", services.worldId().value());
      capture.putUUID("player", player.getUUID());
      capture.put("pending", pending.copy());
      var actual = player.saveWithoutId(new CompoundTag());
      capture.put("Inventory", actual.getList("Inventory", Tag.TAG_COMPOUND).copy());
      capture.put("NeoForgeData", actual.getCompound("NeoForgeData").copy());
      var directory = server.getWorldPath(LevelResource.ROOT);
      try {
        var output = directory.resolve("banking-crash-capture.dat");
        NbtIo.writeCompressed(capture, output);
        if (Files.size(output) > FILE_LIMIT)
          throw new IllegalStateException("Crash capture exceeds fixture bound");
        server.overworld().save(null, true, false);
      } catch (IOException failure) {
        throw new IllegalStateException("Could not save disposable crash capture", failure);
      }
      System.out.println("BANKING_CRASH_CAPTURE " + selected + " " + pending.getUUID("operation"));
      System.out.flush();
      Runtime.getRuntime().halt(77);
    }
  }

  public static boolean tick(MinecraftServer server) throws Exception {
    if (asynchronousFailure != null) throw new IllegalStateException(asynchronousFailure);
    if (phase == 0) {
      root = server.getWorldPath(LevelResource.ROOT);
      expected = expected(root.resolve("banking-recovery-expected.properties"));
      checkDatabase();
      readPlayers();
      var capture = root.resolve("banking-crash-capture.dat");
      if (Files.exists(capture)) {
        var tag = read(capture);
        require(
            tag.getUUID("world").equals(Economy.serverServices(server).worldId().value()),
            "captured world identity");
        captured = intent(tag.getUUID("player"), tag.getCompound("pending"));
      }
      next(server, 1, 10);
      return false;
    }
    if (server.getTickCount() > deadline)
      throw new IllegalStateException(
          "Banking recovery fixture timed out phase " + phase + " actor " + actorIndex);
    switch (phase) {
      case 1 -> {
        if (server.getTickCount() < waitUntil) break;
        // Mere startup/reconciliation must never rewrite saved physical inventories.
        for (var entry : playerFiles.entrySet())
          require(
              Objects.equals(
                  entry.getValue().get("Inventory"),
                  read(root.resolve("playerdata/" + entry.getKey() + ".dat")).get("Inventory")),
              "startup keeps saved inventory unchanged");
        var owners = new LinkedHashSet<UUID>();
        cases.forEach(value -> owners.add(value.owner()));
        if (captured != null) owners.add(((PlayerId) captured.actor()).value());
        if (owners.isEmpty() && !playerFiles.isEmpty())
          owners.add(playerFiles.keySet().iterator().next());
        require(owners.size() <= 16, "recovery fixture actor bound");
        for (var owner : owners) {
          require(playerFiles.containsKey(owner), "saved owner inventory exists");
          var player =
              BankingAcceptanceProbe.fixture(
                  server, owner, "Recovery" + owner.toString().substring(0, 8));
          player.load(playerFiles.get(owner).copy());
          actors.add(player);
        }
        if (actors.isEmpty()) next(server, 5, 0);
        else {
          FixtureBankingTerminal.open(
              actors.getFirst(), new net.minecraft.core.BlockPos(136, 110, 136));
          next(server, 2, 5);
        }
      }
      case 2 -> {
        if (server.getTickCount() < waitUntil) break;
        var player = actors.get(actorIndex);
        var menu = menu(player);
        if (menu == null) {
          FixtureBankingTerminal.open(player, new net.minecraft.core.BlockPos(136, 110, 136));
          break;
        }
        if (menu.busy() || !menu.valid(player)) break;
        require(
            !menu.acceptAction(player, UUID.randomUUID(), menu.revision(), "claims"),
            "restored menu rejects stale session");
        require(
            !menu.acceptAction(player, menu.session(), menu.revision() + 1, "claims"),
            "restored menu rejects stale revision");
        claimsRevision = menu.revision();
        if (!menu.acceptAction(player, menu.session(), claimsRevision, "claims")) break;
        BankingController.action(player, menu, "claims", "");
        next(server, 3, 3);
      }
      case 3 -> {
        if (server.getTickCount() < waitUntil) break;
        var player = actors.get(actorIndex);
        var menu = menu(player);
        if (menu == null || menu.busy() || menu.revision() <= claimsRevision) break;
        require(menu.page().equals("claims"), "normal restored claims view");
        for (var value : cases)
          if (value.owner().equals(player.getUUID()))
            require(
                menu.lines().stream().noneMatch(line -> line.contains(value.id().toString())),
                "quarantine is not offered as a collectible claim");
        baselines.put(player.getUUID(), inventory(player));
        pendingBefore =
            player.getPersistentData().getCompound("economy_banking_cash_pending").copy();
        var quarantined =
            cases.stream()
                .filter(value -> value.owner().equals(player.getUUID()))
                .findFirst()
                .orElse(null);
        if (quarantined != null)
          BankingController.deliver(
              player, menu, Economy.serverServices(server).worldId(), quarantined.id());
        next(server, 4, 10);
      }
      case 4 -> {
        if (server.getTickCount() < waitUntil) break;
        var player = actors.get(actorIndex);
        require(
            Objects.equals(baselines.get(player.getUUID()), inventory(player)),
            "quarantined normal delivery cannot insert items");
        require(
            pendingBefore.equals(
                player.getPersistentData().getCompound("economy_banking_cash_pending")),
            "quarantined delivery creates no operation");
        checkDatabase();
        actorIndex++;
        if (actorIndex < actors.size()) {
          FixtureBankingTerminal.open(
              actors.get(actorIndex), new net.minecraft.core.BlockPos(136, 110, 136));
          next(server, 2, 5);
        } else next(server, 5, 0);
      }
      case 5 -> {
        if (captured == null) {
          next(server, 6, 0);
          break;
        }
        if (!lookupStarted) {
          lookupStarted = true;
          var owner = captured.actor();
          Economy.serverServices(server)
              .journal((who, kind) -> who.equals(owner), Map.of(), null)
              .lookup(captured)
              .whenComplete(
                  (found, error) -> {
                    if (error != null)
                      asynchronousFailure = "Captured original lookup failed: " + error;
                    else {
                      capturedOutcome = found.orElse(null);
                      if (capturedOutcome != null
                          && (capturedOutcome.status() != Journal.Status.COMMITTED
                              || !capturedOutcome.operationId().equals(captured.operationId())
                              || !capturedOutcome.originalRequestId().equals(captured.requestId())))
                        asynchronousFailure =
                            "Captured original lookup conflicts or is not committed";
                    }
                    lookupFinished = true;
                  });
        }
        if (lookupFinished) next(server, 6, 0);
      }
      case 6 -> {
        checkDatabase();
        for (var player : actors) {
          require(
              Objects.equals(baselines.get(player.getUUID()), inventory(player)),
              "read-only original lookup cannot duplicate physical items");
          BankingAcceptanceProbe.unregister(server, player);
        }
        String result =
            "PASS world="
                + Economy.serverServices(server).worldId().value()
                + " inventories="
                + playerFiles.size()
                + " quarantined="
                + cases.size()
                + " original="
                + (captured == null ? "none" : capturedOutcome == null ? "absent" : "committed")
                + "\n";
        Files.writeString(root.resolve("banking-recovery-passed.txt"), result);
        System.out.println("BANKING_RECOVERY_PASS " + result.strip());
        return true;
      }
      default -> throw new IllegalStateException("Unknown recovery fixture phase");
    }
    return false;
  }

  private static void next(MinecraftServer server, int value, int delay) {
    phase = value;
    deadline = server.getTickCount() + 900;
    waitUntil = server.getTickCount() + delay;
    System.out.println("BANKING_RECOVERY_PHASE " + phase + " actor=" + actorIndex);
  }

  private static BankingMenu menu(ServerPlayer player) {
    return player.containerMenu instanceof BankingMenu value ? value : null;
  }

  private static Tag inventory(ServerPlayer player) {
    return player.getInventory().save(new ListTag()).copy();
  }

  private static Journal.Intent intent(UUID owner, CompoundTag pending) {
    require(
        pending.hasUUID("operation") && pending.hasUUID("request"), "captured canonical identity");
    require(
        pending.hasUUID("actor") && pending.getUUID("actor").equals(owner),
        "captured actor identity");
    var fields = new TreeMap<String, String>();
    var stored = pending.getCompound("fields");
    require(stored.size() <= 64, "captured canonical fields bound");
    stored.getAllKeys().forEach(key -> fields.put(key, stored.getString(key)));
    var kind = new Journal.Kind(pending.getString("service"), pending.getString("type"));
    require(
        Set.of("economy:withdrawals", "economy:deposits", "economy:register_sales")
                .contains(kind.service())
            && kind.operationType().equals("economy:commit"),
        "captured commit kind");
    return new Journal.Intent(
        new OperationId(pending.getUUID("operation")),
        new RequestId(pending.getUUID("request")),
        new PlayerId(owner),
        kind,
        new Journal.CanonicalRequest(
            pending.contains("version") ? pending.getInt("version") : 1, fields),
        null,
        null);
  }

  private static Map<String, Long> expected(Path file) throws IOException {
    require(
        Files.isRegularFile(file) && !Files.isSymbolicLink(file) && Files.size(file) <= 65_536,
        "bounded recovery balance expectation");
    var properties = new Properties();
    try (var input = Files.newInputStream(file)) {
      properties.load(input);
    }
    require(properties.size() <= 512, "expected balance count");
    var result = new TreeMap<String, Long>();
    for (var key : properties.stringPropertyNames()) {
      var fields = key.split("[|]", -1);
      require(
          fields.length == 2 && fields[1].matches("[A-Z][A-Z0-9]{2,11}"),
          "expected balance identity");
      UUID.fromString(fields[0]);
      result.put(key, Long.parseLong(properties.getProperty(key)));
    }
    return Map.copyOf(result);
  }

  private static void checkDatabase() throws SQLException {
    var database = root.resolve("economy/economy.sqlite");
    require(
        Files.isRegularFile(database) && !Files.isSymbolicLink(database),
        "restored database exists");
    try (var connection =
            DriverManager.getConnection(
                "jdbc:sqlite:" + database.toAbsolutePath().toUri() + "?mode=ro");
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA query_only=ON");
      statement.execute("PRAGMA busy_timeout=5000");
      var balances = new TreeMap<String, Long>();
      try (var rows =
          statement.executeQuery(
              "SELECT account_id,currency,minor_units FROM ledger_balances ORDER BY account_id,currency LIMIT 513")) {
        while (rows.next()) {
          require(balances.size() < 512, "bounded restored ledger snapshot");
          balances.put(rows.getString(1) + "|" + rows.getString(2), rows.getLong(3));
        }
      }
      require(balances.equals(expected), "restored financial balances remain exactly unchanged");
      var found = new ArrayList<CaseRow>();
      try (var rows =
          statement.executeQuery("SELECT id,owner,state FROM custody_cases ORDER BY id LIMIT 65")) {
        while (rows.next()) {
          require(
              found.size() < 64 && rows.getString(3).equals("QUARANTINED"),
              "all restored custody is quarantined");
          String owner = rows.getString(2);
          require(owner.startsWith("player:"), "fixture custody owner type");
          found.add(
              new CaseRow(
                  UUID.fromString(rows.getString(1)),
                  UUID.fromString(owner.substring("player:".length()))));
        }
      }
      cases = List.copyOf(found);
      for (String table : List.of("deposits", "withdrawals", "register_sales"))
        try (var rows =
            statement.executeQuery(
                "SELECT id FROM " + table + " WHERE state IN ('RESERVED','SAVED') LIMIT 1")) {
          require(!rows.next(), "interrupted " + table + " no longer admits precommit effects");
        }
    }
  }

  private static void readPlayers() throws IOException {
    var folder = root.resolve("playerdata");
    require(
        Files.isDirectory(folder) && !Files.isSymbolicLink(folder), "restored player directory");
    var files = new ArrayList<Path>();
    try (var entries = Files.newDirectoryStream(folder, "*.dat")) {
      for (var file : entries) {
        require(files.size() < 16, "bounded restored player count");
        files.add(file);
      }
    }
    files.sort(Comparator.comparing(value -> value.getFileName().toString()));
    for (var file : files) {
      String name = file.getFileName().toString();
      var id = UUID.fromString(name.substring(0, name.length() - 4));
      playerFiles.put(id, read(file));
    }
  }

  private static CompoundTag read(Path file) throws IOException {
    require(
        Files.isRegularFile(file) && !Files.isSymbolicLink(file) && Files.size(file) <= FILE_LIMIT,
        "bounded fixture player/capture file");
    return NbtIo.readCompressed(file, NbtAccounter.create(FILE_LIMIT));
  }

  private static void require(boolean value, String message) {
    if (!value) throw new IllegalStateException("Banking recovery fixture: " + message);
  }
}
