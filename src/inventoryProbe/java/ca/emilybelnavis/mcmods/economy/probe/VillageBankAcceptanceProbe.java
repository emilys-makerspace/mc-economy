package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banker.*;
import ca.emilybelnavis.mcmods.economy.banker.desk.*;
import ca.emilybelnavis.mcmods.economy.banker.dialogue.*;
import ca.emilybelnavis.mcmods.economy.banker.entity.BankerEntity;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.terminal.*;
import ca.emilybelnavis.mcmods.economy.village.*;
import ca.emilybelnavis.mcmods.economy.village.generation.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.storage.LevelResource;

/**
 * One opt-in two-process witness. SQL reads are detached/query-only; financial writes use the real
 * journal.
 */
public final class VillageBankAcceptanceProbe {
  public static volatile String result;
  public static volatile String failure;
  public static volatile boolean phase1Ready;
  public static volatile boolean clientOpeningSent;
  public static volatile BlockPos kioskTarget;
  private static final Gson JSON = new Gson();
  private static int stage;
  private static long deadlineTick;
  private static long waitUntilTick;
  private static Path worldRoot;
  private static Path runRoot;
  private static UUID branch;
  private static UUID deathTarget;
  private static BranchPlans.VillagePlanV1 plan;
  private static BranchPlans.BranchPlanV1 choice;
  private static List<StaffingRecords.Row> employees;
  private static CompletableFuture<?> pending;
  private static Map<String, String> expected;
  private static BankingMenu staleMenu;
  private static boolean branchPaused;

  private VillageBankAcceptanceProbe() {}

  public static boolean tick(MinecraftServer server) {
    try {
      return step(server);
    } catch (Throwable error) {
      failure = "stage=" + stage + " " + error;
      error.printStackTrace();
      return true;
    }
  }

  private static boolean step(MinecraftServer server) throws Exception {
    if (result != null || failure != null || phase1Ready) return true;
    if (server.getPlayerList().getPlayers().isEmpty()) return false;
    var player = server.getPlayerList().getPlayers().getFirst();
    var level = server.overworld();
    var services = Economy.serverServices(server);
    if (stage == 0) {
      worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
      runRoot = worldRoot.getParent().getParent();
      require(
          worldRoot.getFileName().toString().matches("economy-a7-06-[0-9a-f-]{36}"),
          "disposable save guard");
      require(
          Files.readString(runRoot.resolve("village-bank-acceptance-world.txt"))
              .strip()
              .equals(worldRoot.getFileName().toString()),
          "run marker owns save");
      require(level.getSeed() == 704L, "seed704");
      deadlineTick = server.getTickCount() + 11000;
      if (restart()) {
        require(
            Files.isRegularFile(runRoot.resolve("village-bank-acceptance-phase1-passed.txt")),
            "first process pass marker");
        expected =
            JSON.fromJson(
                Files.readString(runRoot.resolve("village-bank-acceptance-expected.json")),
                new TypeToken<TreeMap<String, String>>() {}.getType());
        require(expected.get("worldPath").equals(worldRoot.toString()), "same guarded world only");
        require(
            expected.get("player").equals(player.getUUID().toString()),
            "same integrated host identity");
      }
      stage = 1;
    }
    require(server.getTickCount() < deadlineTick, "bounded tick deadline stage=" + stage);
    if (server.getTickCount() % 100 == 0)
      System.out.println("VILLAGE_BANK_ACCEPTANCE_STAGE " + stage + " restart=" + restart());
    if (services == null || services.state() != ServerServices.State.READY) return false;
    var publication = GenerationRuntime.publication(level.getChunkSource().getGenerator());
    if (publication == null || publication.snapshot() == null) return false;
    if (server.getTickCount() < waitUntilTick) return false;
    if (pending != null && !pending.isDone()) return false;
    switch (stage) {
      // Resolve the known natural village and load only this disposable fixture area.
      case 1 -> {
        // This fixture alone may force the known natural village's disposable chunks.
        var source = level.getChunk(1097, -10);
        for (var start : source.getAllStarts().values()) {
          for (var piece : start.getPieces()) {
            if (piece instanceof VillageGenerationPiece metadata && metadata.branchSlot() == -1) {
              plan = metadata.plan();
            }
          }
        }
        require(plan != null, "natural savanna source metadata");
        choice =
            plan.branches().stream()
                .filter(b -> b.state() == BranchPlans.State.RESERVED && b.slot() == 0)
                .findFirst()
                .orElseThrow(
                    () -> new IllegalStateException("natural primary bank reservation missing"));
        branch =
            BranchPlans.branchId(BranchPlans.id(services.worldId(), plan.source()), choice.slot());
        if (restart())
          require(
              expected.get("branch").equals(branch.toString())
                  && expected.get("planDigest").equals(plan.digest())
                  && expected.get("world").equals(services.worldId().value().toString()),
              "same captured branch/world");
        for (var manifest : choice.manifests())
          for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
              level.getChunk(manifest.chunkX() + dx, manifest.chunkZ() + dz);
              level.setChunkForced(manifest.chunkX() + dx, manifest.chunkZ() + dz, true);
            }
        var p = choice.entrance();
        player.teleportTo(level, p.x() + .5, p.y(), p.z() + .5, Set.of(), 0, 0);
        waitUntilTick = server.getTickCount() + 160;
        stage = 2;
      }
      case 2 -> {
        pending = services.branches().find(branch).toCompletableFuture();
        stage = 3;
      }
      case 3 -> {
        var view = branchView();
        require(
            !Set.of("CONFLICT", "REMOVED", "OMITTED").contains(view.state()),
            "branch remains eligible: " + view.state() + "/" + view.reason());
        if (!view.state().equals("ACTIVE")) {
          stage = 2;
          waitUntilTick = server.getTickCount() + 40;
          break;
        }
        branchPaused = view.paused();
        choice = view.choice();
        StaffingRuntime.observe(level, view);
        pending = services.staffing().rows(branch).toCompletableFuture();
        stage = 4;
      }
      // Require physical staffing before direct kiosk use; restart also checks saved identities.
      case 4 -> {
        employees = staffRows();
        if (employees.size() != 1
            || (!restart() && employees.stream().anyMatch(r -> !r.phase().equals("LIVE")))) {
          stage = 2;
          waitUntilTick = server.getTickCount() + 40;
          break;
        }
        checkEmployees(level);
        if (restart()) {
          require(
              expected.get("employees").equals(employeeIdentities()),
              "same employee/assignment/desk identities after process restart");
          require(
              expected.get("staffState").equals(staffStates()),
              "same saved paused vacancy progress after process restart");
          require(branchPaused, "pause retained after restart");
          require(
              expected.get("provider").equals(choice.institution())
                  && expected.get("currency").equals(choice.nativeCurrency().name())
                  && expected.get("serviceGenerations").equals(serviceIdentities()),
              "same provider/currency/service generations");
          pending = financial(services, player).toCompletableFuture();
          stage = 20;
        } else {
          pending =
              services
                  .ledger()
                  .bankingContext(new PlayerId(player.getUUID()), terminalAnchor(), null)
                  .toCompletableFuture();
          stage = 5;
        }
      }
      // The account must not exist before the generated kiosk is used.
      case 5 -> {
        var context = (LedgerService.BankingContext) pending.join();
        require(
            context.account() == null, "generated kiosk precedes any account or banker dialogue");
        approachKiosk(level, player);
        stage = 6;
      }
      case 6 -> {
        if (!clientOpeningSent
            || !(player.containerMenu instanceof BankingMenu menu)
            || menu.busy()) break;
        require(menu.valid(player), "real generated kiosk direct menu valid");
        pending =
            services
                .ledger()
                .bankingContext(new PlayerId(player.getUUID()), terminalAnchor(), null)
                .toCompletableFuture();
        stage = 7;
      }
      case 7 -> {
        var context = (LedgerService.BankingContext) pending.join();
        if (context.account() == null) {
          stage = 6;
          waitUntilTick = server.getTickCount() + 20;
          break;
        }
        var account = context.account();
        require(
            account.primary() && account.currency() == EconomyCurrency.CAD,
            "client branch_open created primary CAD account");
        var owner = new PlayerId(player.getUUID());
        var adjustment =
            new SameCurrency.Adjustment(
                owner,
                new SameCurrency.Revision(account.id(), account.revision()),
                new Money(account.currency(), 10000));
        var id = UUID.randomUUID();
        var intent =
            new Journal.Intent(
                new OperationId(id),
                new RequestId(id),
                owner,
                SameCurrency.CREDIT,
                adjustment.request(),
                null,
                null);
        pending =
            services
                .ledger()
                .adjustments(
                    SameCurrency.CREDIT,
                    (actor, kind) -> actor.equals(owner),
                    (actor, instruction, reads) -> true,
                    null)
                .submit(intent)
                .completion()
                .toCompletableFuture();
        stage = 8;
      }
      // Fund through a real journal, then exercise explicit banker dialogue.
      case 8 -> {
        require(
            ((JournalService.Delivery) pending.join()).outcome().status()
                == Journal.Status.COMMITTED,
            "real journal fixture funding committed");
        player.closeContainer();
        // Only now may a banker menu appear. Initial explanation is empty and questions are
        // explicit.
        var banker = (BankerEntity) level.getEntity(employees.getFirst().employee());
        player.teleportTo(level, banker.getX(), banker.getY(), banker.getZ() + 1, Set.of(), 0, 0);
        BankerDialogue.open(player, banker);
        require(player.containerMenu instanceof BankerMenu, "private banker dialogue");
        var menu = (BankerMenu) player.containerMenu;
        require(menu.lines().isEmpty(), "no unsolicited explanation");
        var question =
            new BankerNetwork.Action(
                menu.containerId, menu.session(), menu.revision(), "ask", "institution");
        require(menu.accept(player, question), "offered explicit institution question");
        BankerDialogue.action(player, menu, "ask", "institution");
        require(
            !menu.lines().isEmpty() && !menu.accept(player, question),
            "answer and stale-revision rejection");
        player.closeContainer();
        deathTarget = employees.getFirst().employee();
        banker.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
        waitUntilTick = server.getTickCount() + 260;
        stage = 90;
      }
      // Persist a positively observed vacancy and earned replacement progress.
      case 90 -> {
        pending = services.staffing().rows(branch).toCompletableFuture();
        stage = 91;
      }
      case 91 -> {
        employees = staffRows();
        var vacancy =
            employees.stream()
                .filter(r -> r.employee().equals(deathTarget))
                .findFirst()
                .orElseThrow();
        if (!vacancy.phase().equals("VACANT") || vacancy.elapsed() == 0) {
          waitUntilTick = server.getTickCount() + 40;
          stage = 90;
          break;
        }
        server
            .getCommands()
            .performPrefixedCommand(
                player.createCommandSourceStack(), "economy branch staffing " + branch + " pause");
        waitUntilTick = server.getTickCount() + 40;
        stage = 92;
      }
      case 92 -> {
        pending = services.branches().find(branch).toCompletableFuture();
        stage = 93;
      }
      case 93 -> {
        if (!branchView().paused()) {
          waitUntilTick = server.getTickCount() + 20;
          stage = 92;
          break;
        }
        pending = services.staffing().rows(branch).toCompletableFuture();
        stage = 94;
      }
      case 94 -> {
        employees = staffRows();
        require(
            employees.stream().anyMatch(r -> r.phase().equals("VACANT") && r.elapsed() > 0),
            "saved positive earned paused vacancy");
        pending = financial(services, player).toCompletableFuture();
        stage = 9;
      }
      // Publish expected state and first-process marker before requesting shutdown.
      case 9 -> {
        var evidence = financialResult();
        require(evidence.get("available").equals("10000"), "populated account witness");
        expected = new TreeMap<>(evidence);
        expected.put("worldPath", worldRoot.toString());
        expected.put("world", services.worldId().value().toString());
        expected.put("player", player.getUUID().toString());
        expected.put("branch", branch.toString());
        expected.put("planDigest", plan.digest());
        expected.put("provider", choice.institution());
        expected.put("currency", choice.nativeCurrency().name());
        expected.put("serviceGenerations", serviceIdentities());
        expected.put("employees", employeeIdentities());
        expected.put("staffState", staffStates());
        Files.writeString(
            runRoot.resolve("village-bank-acceptance-expected.json"),
            JSON.toJson(expected),
            StandardOpenOption.CREATE_NEW);
        Files.writeString(
            runRoot.resolve("village-bank-acceptance-phase1-passed.txt"),
            "PASS generated kiosk client payload opening, original receipt and populated financial state persisted; awaiting normal process restart\n",
            StandardOpenOption.CREATE_NEW);
        phase1Ready = true;
        System.out.println("VILLAGE_BANK_ACCEPTANCE_PHASE1_PASS");
      }
      // Compare the saved financial evidence before any second-process mutations.
      case 20 -> {
        requireFinancialUnchanged(financialResult());
        approachKiosk(level, player);
        stage = 21;
      }
      case 21 -> {
        if (!(player.containerMenu instanceof BankingMenu menu) || menu.busy()) break;
        require(menu.valid(player), "direct generated kiosk still usable after actual restart");
        staleMenu = menu;
        deathTarget =
            employees.stream()
                .filter(r -> r.phase().equals("LIVE"))
                .findFirst()
                .orElseThrow()
                .employee();
        var banker = (BankerEntity) level.getEntity(deathTarget);
        require(banker != null, "positive loaded death target");
        banker.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
        waitUntilTick = server.getTickCount() + 100;
        stage = 22;
      }
      case 22 -> {
        pending = services.staffing().rows(branch).toCompletableFuture();
        stage = 23;
      }
      // Remove job stations and real service hardware after a proven death.
      case 23 -> {
        require(
            staffRows().stream()
                .anyMatch(r -> r.employee().equals(deathTarget) && r.phase().equals("VACANT")),
            "positive death durably recorded");
        for (var anchor : choice.desks()) level.destroyBlock(position(anchor.position()), false);
        for (var anchor : choice.services()) level.destroyBlock(position(anchor.position()), false);
        waitUntilTick = server.getTickCount() + 100;
        stage = 24;
      }
      case 24 -> {
        pending = services.branches().find(branch).toCompletableFuture();
        stage = 25;
      }
      case 25 -> {
        var view = branchView();
        if (!view.state().equals("REMOVED")) {
          require(!view.state().equals("CONFLICT"), "removal must not become conflict");
          stage = 24;
          waitUntilTick = server.getTickCount() + 40;
          break;
        }
        require(
            !staleMenu.valid(player)
                && !staleMenu.acceptAction(
                    player, staleMenu.session(), staleMenu.revision(), "branch_open"),
            "stale direct kiosk admission denied after removal");
        pending = financial(services, player).toCompletableFuture();
        stage = 26;
      }
      // Accounts, funds, obligations and the original opening receipt must remain unchanged.
      case 26 -> {
        requireFinancialUnchanged(financialResult());
        result =
            "PASS two real integrated-client processes: generated kiosk client branch_open before dialogue; populated original account/provider/opening receipt/funds and obligations survive restart, banker death, desks and kiosk removal; same employee/service identities, no duplicate bankers, durable REMOVED and stale direct admission denied.";
        System.out.println("VILLAGE_BANK_ACCEPTANCE_PASS " + result);
        return true;
      }
      default -> throw new IllegalStateException("Unknown acceptance stage " + stage);
    }
    return phase1Ready;
  }

  private static boolean restart() {
    return Boolean.getBoolean("economy.villageBankAcceptanceRestart");
  }

  private static void approachKiosk(ServerLevel level, ServerPlayer player) {
    var anchor = choice.services().getFirst();
    var p = position(anchor.position());
    var facing = level.getBlockState(p).getValue(WideTerminalBlock.FACING);
    var front = p.relative(facing, 2);
    player.closeContainer();
    player.teleportTo(
        level,
        front.getX() + .5,
        p.getY(),
        front.getZ() + .5,
        Set.of(),
        facing.getOpposite().toYRot(),
        0);
    kioskTarget = p;
  }

  private static void checkEmployees(ServerLevel level) {
    int live = 0;
    for (var row : employees) {
      if (row.phase().equals("LIVE")) {
        live++;
        require(
            level.getEntity(row.employee()) instanceof BankerEntity banker
                && banker.assignmentId().equals(row.assignment())
                && banker.branchId().equals(branch),
            "persisted exact employee loaded");
      } else
        require(
            row.phase().equals("VACANT") && level.getEntity(row.employee()) == null,
            "vacant saved employee is not replaced while paused");
    }
    long count = 0;
    for (var entity : level.getAllEntities())
      if (entity instanceof BankerEntity banker && branch.equals(banker.branchId())) count++;
    require(
        count == live,
        "exact physical banker count, no duplicates: " + count + " expected=" + live);
  }

  private static String employeeIdentities() {
    return employees.stream()
        .sorted(Comparator.comparingInt(StaffingRecords.Row::slot))
        .map(r -> r.slot() + "|" + r.deskGeneration() + "|" + r.assignment() + "|" + r.employee())
        .toList()
        .toString();
  }

  private static String staffStates() {
    return employees.stream()
        .sorted(Comparator.comparingInt(StaffingRecords.Row::slot))
        .map(r -> r.slot() + "|" + r.phase() + "|" + r.elapsed())
        .toList()
        .toString();
  }

  private static String serviceIdentities() {
    return choice.services().stream()
        .map(a -> a.slot() + "|" + a.position() + "|" + a.generation())
        .toList()
        .toString();
  }

  private static TerminalPolicy.Anchor terminalAnchor() {
    var p = choice.services().getFirst().position();
    return new TerminalPolicy.Anchor(plan.source().dimension(), p.x(), p.y(), p.z());
  }

  private static BlockPos position(BranchPlans.Point point) {
    return new BlockPos(point.x(), point.y(), point.z());
  }

  @SuppressWarnings("unchecked")
  private static Branches.View branchView() {
    return ((Optional<Branches.View>) pending.join()).orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private static List<StaffingRecords.Row> staffRows() {
    return (List<StaffingRecords.Row>) pending.join();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> financialResult() {
    return (Map<String, String>) pending.join();
  }

  private static CompletionStage<Map<String, String>> financial(
      ServerServices services, ServerPlayer player) {
    var owner = new PlayerId(player.getUUID());
    var grant =
        PartyAuthorization.player(
                player,
                Economy::serverServices,
                new PartyRef(services.worldId(), new PartyRef.Player(owner)))
            .orElseThrow();
    return services
        .ledger()
        .primaryFunds(grant)
        .thenCompose(
            found -> {
              var primary = found.orElseThrow();
              require(primary.account().primary(), "owned primary still exists");
              return services.storage(
                  () -> {
                    var evidence = new TreeMap<String, String>();
                    evidence.put("account", primary.account().id().value().toString());
                    evidence.put("primaryIdentity", JSON.toJson(primary.account()));
                    evidence.put("funds", JSON.toJson(primary.funds()));
                    evidence.put("available", Long.toString(primary.funds().availableMinorUnits()));
                    Path database = worldRoot.resolve("economy/economy.sqlite");
                    try (var connection =
                        DriverManager.getConnection("jdbc:sqlite:file:" + database + "?mode=ro")) {
                      try (var statement = connection.createStatement()) {
                        statement.execute("PRAGMA query_only=ON");
                        statement.execute("BEGIN");
                      }
                      for (String table :
                          List.of(
                              "account_catalog",
                              "bank_account_providers",
                              "ledger_balances",
                              "debt_obligations",
                              "credit_facilities",
                              "loans"))
                        evidence.put(table, query(connection, "SELECT * FROM " + table, List.of()));
                      String opening =
                          query(
                              connection,
                              "SELECT * FROM operation_outcomes WHERE service='economy:providers' AND operation_type='economy:primary' AND actor=?",
                              List.of(Journal.actorKey(owner)));
                      require(
                          !opening.equals("[]") && opening.contains("COMMITTED"),
                          "original client opening receipt exists for actual player");
                      evidence.put("openingReceipt", opening);
                      return evidence;
                    } catch (Exception error) {
                      throw new CompletionException(error);
                    }
                  });
            });
  }

  private static String query(Connection connection, String sql, List<String> parameters)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.size(); i++) statement.setString(i + 1, parameters.get(i));
      var values = new ArrayList<String>();
      try (var rows = statement.executeQuery()) {
        var meta = rows.getMetaData();
        while (rows.next()) {
          require(values.size() < 2048, "bounded financial fixture read");
          var row = new TreeMap<String, String>();
          for (int i = 1; i <= meta.getColumnCount(); i++)
            row.put(meta.getColumnLabel(i), rows.getString(i));
          values.add(JSON.toJson(row));
        }
      }
      Collections.sort(values);
      return JSON.toJson(values);
    }
  }

  private static void requireFinancialUnchanged(Map<String, String> actual) {
    for (var entry : actual.entrySet())
      require(
          entry.getValue().equals(expected.get(entry.getKey())),
          "unchanged original financial projection/receipt: " + entry.getKey());
  }

  private static void require(boolean value, String message) {
    if (!value) throw new IllegalStateException(message);
  }
}
