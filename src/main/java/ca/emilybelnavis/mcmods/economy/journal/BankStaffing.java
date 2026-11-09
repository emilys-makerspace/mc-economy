package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banker.StaffingRecords.*;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Detached worker-owned employee projection. Authority is a refreshed internal server lease. */
public final class BankStaffing {
  public static final String FACT = "economy:bank_staffing";
  private static final Gson JSON =
      new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
  private static final Set<String> PHASES =
      Set.of("RESERVED", "SPAWNING", "LIVE", "VACANT", "UNKNOWN");

  private BankStaffing() {}

  public static String encodeCommand(Command command) {
    validateCommand(command);
    return JSON.toJson(command);
  }

  public static Command decodeCommand(String encoded) {
    bounded(encoded, 4096);
    var value = JSON.fromJson(encoded, Command.class);
    if (!encodeCommand(value).equals(encoded))
      throw new IllegalArgumentException("Noncanonical staffing command");
    return value;
  }

  public static String encodeRow(Row row) {
    validateRow(row);
    return JSON.toJson(row);
  }

  public static Row decodeRow(String encoded) {
    bounded(encoded, 2048);
    var value = JSON.fromJson(encoded, Row.class);
    if (!encodeRow(value).equals(encoded))
      throw new IllegalArgumentException("Noncanonical staffing row");
    return value;
  }

  private static void bounded(String text, int max) {
    if (text == null || text.getBytes(StandardCharsets.UTF_8).length > max)
      throw new IllegalArgumentException("Staffing payload limit");
  }

  private static void validateCommand(Command c) {
    Objects.requireNonNull(c);
    Objects.requireNonNull(c.action());
    Objects.requireNonNull(c.branch());
    if (c.branchRevision() < 1
        || (c.action() == Action.PAUSE ? c.slot() != -1 : c.slot() < 0 || c.slot() > 1)
        || c.revision() < 0
        || c.delta() < 0
        || c.delta() > 200
        || c.progress().size() > 2) throw new IllegalArgumentException("Staffing command bounds");
    if (c.action() != Action.PAUSE) {
      Objects.requireNonNull(c.assignment());
      Objects.requireNonNull(c.employee());
      if (!c.progress().isEmpty())
        throw new IllegalArgumentException("Unexpected staffing progress");
    }
    if (c.action() != Action.CHECKPOINT && c.delta() != 0)
      throw new IllegalArgumentException("Unexpected staffing delta");
    var slots = new HashSet<Integer>();
    for (var p : c.progress())
      if (p.slot() < 0
          || p.slot() > 1
          || p.revision() < 1
          || p.delta() < 0
          || p.delta() > 200
          || !slots.add(p.slot())) throw new IllegalArgumentException("Staffing progress bounds");
  }

  private static void validateRow(Row r) {
    Objects.requireNonNull(r);
    Objects.requireNonNull(r.branch());
    Objects.requireNonNull(r.deskGeneration());
    Objects.requireNonNull(r.assignment());
    Objects.requireNonNull(r.employee());
    Objects.requireNonNull(r.operation());
    if (r.slot() < 0
        || r.slot() > 1
        || r.dimension() == null
        || r.dimension().length() > 128
        || !r.dimension().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
        || Math.abs((long) r.x()) > 30000000
        || Math.abs((long) r.z()) > 30000000
        || r.y() < -2048
        || r.y() > 2047
        || !Set.of("ACTIVE", "REMOVED").contains(r.deskState())
        || !PHASES.contains(r.phase())
        || r.elapsed() < 0
        || r.elapsed() > 24000
        || r.revision() < 1
        || (!r.phase().equals("VACANT") && r.elapsed() != 0))
      throw new IllegalArgumentException("Staffing row bounds");
  }

  public static Journal.CanonicalRequest request(Command command) {
    return new Journal.CanonicalRequest(1, Map.of("command", encodeCommand(command)));
  }

  public static Registration registration(
      WorldId world, Branches.Authority authority, Command command) {
    var expected = request(command);
    return new Registration(
        1,
        (intent, reads) -> {
          if (!intent.kind().equals(command.action().kind()) || !intent.request().equals(expected))
            return reject("STAFF_REQUEST");
          if (!authority.allows(intent))
            return new Decline(new Journal.Result(1, "STAFF_ACCESS", ""));
          if (command.action() != Action.PAUSE
              && !intent.actor().equals(BranchPlans.owner(command.branch())))
            return new Decline(new Journal.Result(1, "STAFF_ACCESS", ""));
          var branch = Branches.find(reads, world, command.branch()).orElse(null);
          if (branch == null) return reject("STAFF_BRANCH_ABSENT");
          if (branch.revision() != command.branchRevision()) return reject("STAFF_BRANCH_STALE");
          if (command.action() == Action.PAUSE) return pause(world, intent, reads, branch, command);
          var prior = find(reads, world, command.branch(), command.slot()).orElse(null);
          if (prior == null) {
            if ((command.action() != Action.ATTACH && command.action() != Action.DESK_REMOVED)
                || command.revision() != 0) return reject("STAFF_ABSENT");
            if (command.action() == Action.ATTACH && !eligible(branch))
              return reject("STAFF_BRANCH_INACTIVE");
            var anchor =
                branch.choice().desks().stream()
                    .filter(a -> a.slot() == command.slot())
                    .findFirst()
                    .orElse(null);
            if (anchor == null) return reject("STAFF_DESK_UNCAPTURED");
            if (!fresh(reads, world, command.assignment(), command.employee()))
              return reject("STAFF_EMPLOYEE_REUSED");
            var p = anchor.position();
            var row =
                new Row(
                    branch.id(),
                    command.slot(),
                    anchor.generation(),
                    branch.plan().source().dimension(),
                    p.x(),
                    p.y(),
                    p.z(),
                    command.action() == Action.DESK_REMOVED ? "REMOVED" : "ACTIVE",
                    command.assignment(),
                    command.employee(),
                    command.action() == Action.DESK_REMOVED ? "UNKNOWN" : "RESERVED",
                    0,
                    1,
                    intent.operationId().value());
            return commit(world, intent, command.action(), null, row);
          }
          if (prior.revision() != command.revision() || prior.revision() == Long.MAX_VALUE)
            return reject("STAFF_STALE");
          if (command.action() == Action.ATTACH) return reject("STAFF_ALREADY_ATTACHED");
          if (command.action() != Action.REPLACE
              && (!prior.assignment().equals(command.assignment())
                  || !prior.employee().equals(command.employee())))
            return reject("STAFF_IDENTITY_STALE");
          String phase = prior.phase(), desk = prior.deskState();
          int elapsed = prior.elapsed();
          UUID assignment = prior.assignment(), employee = prior.employee();
          boolean active = eligible(branch) && desk.equals("ACTIVE");
          switch (command.action()) {
            case BEGIN -> {
              if (!active || !phase.equals("RESERVED")) return reject("STAFF_BEGIN_STATE");
              phase = "SPAWNING";
            }
            case LIVE -> {
              if (!branch.state().equals("ACTIVE")
                  || !desk.equals("ACTIVE")
                  || !Set.of("SPAWNING", "UNKNOWN").contains(phase))
                return reject("STAFF_LIVE_STATE");
              phase = "LIVE";
            }
            case DEATH -> {
              if (!Set.of("LIVE", "SPAWNING", "RESERVED", "UNKNOWN").contains(phase))
                return reject("STAFF_DEATH_STATE");
              phase = "VACANT";
              elapsed = 0;
            }
            case DESK_REMOVED -> {
              if (!desk.equals("ACTIVE")) return reject("STAFF_DESK_REMOVED");
              desk = "REMOVED";
            }
            case CHECKPOINT -> {
              if (!active || !phase.equals("VACANT") || command.delta() < 1)
                return reject("STAFF_PROGRESS_STATE");
              elapsed = Math.min(24000, elapsed + command.delta());
            }
            case REPLACE -> {
              if (!active || !phase.equals("VACANT") || elapsed != 24000)
                return reject("STAFF_REPLACEMENT_NOT_DUE");
              if (!fresh(reads, world, command.assignment(), command.employee()))
                return reject("STAFF_EMPLOYEE_REUSED");
              phase = "RESERVED";
              elapsed = 0;
              assignment = command.assignment();
              employee = command.employee();
            }
            case UNKNOWN -> {
              if (!phase.equals("SPAWNING")) return reject("STAFF_UNKNOWN_STATE");
              phase = "UNKNOWN";
            }
            case DEFER -> {
              if (!phase.equals("SPAWNING")) return reject("STAFF_DEFER_STATE");
              phase = "RESERVED";
            }
            default -> {
              return reject("STAFF_REQUEST");
            }
          }
          var row =
              new Row(
                  prior.branch(),
                  prior.slot(),
                  prior.deskGeneration(),
                  prior.dimension(),
                  prior.x(),
                  prior.y(),
                  prior.z(),
                  desk,
                  assignment,
                  employee,
                  phase,
                  elapsed,
                  prior.revision() + 1,
                  intent.operationId().value());
          return commit(world, intent, command.action(), prior, row);
        });
  }

  private static boolean eligible(Branches.View branch) {
    return branch.state().equals("ACTIVE") && !branch.paused();
  }

  private static boolean fresh(ReadView reads, WorldId world, UUID assignment, UUID employee) {
    return reads
        .query(
            "SELECT employee_id FROM banker_employee_history WHERE world_id=? AND (employee_id=? OR assignment_id=?)",
            List.of(world.value().toString(), employee.toString(), assignment.toString()),
            2)
        .isEmpty();
  }

  private static Decision pause(
      WorldId world, Journal.Intent intent, ReadView reads, Branches.View branch, Command command)
      throws Exception {
    var changes = new ArrayList<Commit>();
    for (var progress : command.progress()) {
      var prior = find(reads, world, branch.id(), progress.slot()).orElse(null);
      if (prior == null
          || prior.revision() != progress.revision()
          || prior.revision() == Long.MAX_VALUE) return reject("STAFF_STALE");
      if (!prior.phase().equals("VACANT")
          || !prior.deskState().equals("ACTIVE")
          || !eligible(branch)) return reject("STAFF_PROGRESS_STATE");
      var row =
          new Row(
              prior.branch(),
              prior.slot(),
              prior.deskGeneration(),
              prior.dimension(),
              prior.x(),
              prior.y(),
              prior.z(),
              prior.deskState(),
              prior.assignment(),
              prior.employee(),
              prior.phase(),
              Math.min(24000, prior.elapsed() + progress.delta()),
              prior.revision() + 1,
              intent.operationId().value());
      changes.add(commit(world, intent, Action.PAUSE, prior, row));
    }
    var decision =
        Branches.staffingPause(
            world,
            intent,
            branch,
            command.paused(),
            tx -> {
              for (var change : changes) change.projections().apply(tx);
            });
    if (!(decision instanceof Commit branchChange)) return decision;
    var facts = new ArrayList<Journal.Record>();
    for (var change : changes) facts.addAll(change.records());
    facts.addAll(branchChange.records());
    return new Commit(
        facts,
        new Journal.Result(
            1, command.paused() ? "STAFF_PAUSED" : "STAFF_RESUMED", branch.id().toString()),
        branchChange.projections());
  }

  private static Commit commit(
      WorldId world, Journal.Intent intent, Action action, Row prior, Row row) {
    String encoded = encodeRow(row);
    return new Commit(
        List.of(fact(world, action, row)),
        new Journal.Result(1, "STAFF_" + action.name(), encoded),
        tx -> {
          var values = values(world, row);
          if (prior == null) {
            tx.execute("INSERT INTO banker_slots VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", values);
          } else {
            int changed =
                tx.execute(
                    "UPDATE banker_slots SET desk_generation=?,dimension=?,x=?,y=?,z=?,desk_state=?,assignment_id=?,employee_id=?,phase=?,elapsed=?,revision=?,operation_id=? WHERE world_id=? AND branch_id=? AND slot=? AND revision=?",
                    List.of(
                        row.deskGeneration().toString(),
                        row.dimension(),
                        Integer.toString(row.x()),
                        Integer.toString(row.y()),
                        Integer.toString(row.z()),
                        row.deskState(),
                        row.assignment().toString(),
                        row.employee().toString(),
                        row.phase(),
                        Integer.toString(row.elapsed()),
                        Long.toString(row.revision()),
                        row.operation().toString(),
                        world.value().toString(),
                        row.branch().toString(),
                        Integer.toString(row.slot()),
                        Long.toString(prior.revision())));
            if (changed != 1) throw new IllegalStateException("Staff revision changed");
          }
          String historyPhase = row.phase().equals("VACANT") ? "DEAD" : row.phase();
          if (prior == null || action == Action.REPLACE)
            tx.execute(
                "INSERT INTO banker_employee_history VALUES(?,?,?,?,?,?,?)",
                List.of(
                    world.value().toString(),
                    row.employee().toString(),
                    row.assignment().toString(),
                    row.branch().toString(),
                    Integer.toString(row.slot()),
                    row.deskGeneration().toString(),
                    historyPhase));
          else if (tx.execute(
                  "UPDATE banker_employee_history SET phase=? WHERE world_id=? AND employee_id=? AND assignment_id=?",
                  List.of(
                      historyPhase,
                      world.value().toString(),
                      row.employee().toString(),
                      row.assignment().toString()))
              != 1) throw new IllegalStateException("Staff employee missing");
          tx.execute(
              "INSERT INTO banker_slot_events VALUES(?,?,?,?,?,?,?)",
              List.of(
                  world.value().toString(),
                  row.branch().toString(),
                  Integer.toString(row.slot()),
                  Long.toString(row.revision()),
                  action.name(),
                  encoded,
                  row.operation().toString()));
        });
  }

  private static List<String> values(WorldId world, Row r) {
    return List.of(
        world.value().toString(),
        r.branch().toString(),
        Integer.toString(r.slot()),
        r.deskGeneration().toString(),
        r.dimension(),
        Integer.toString(r.x()),
        Integer.toString(r.y()),
        Integer.toString(r.z()),
        r.deskState(),
        r.assignment().toString(),
        r.employee().toString(),
        r.phase(),
        Integer.toString(r.elapsed()),
        Long.toString(r.revision()),
        r.operation().toString());
  }

  public static Optional<Row> find(ReadView reads, WorldId world, UUID branch, int slot) {
    if (slot < 0 || slot > 1) throw new IllegalArgumentException("Desk slot");
    return reads
        .query(
            "SELECT * FROM banker_slots WHERE world_id=? AND branch_id=? AND slot=?",
            List.of(world.value().toString(), branch.toString(), Integer.toString(slot)),
            1)
        .stream()
        .map(BankStaffing::row)
        .findFirst();
  }

  public static List<Row> rows(ReadView reads, WorldId world, UUID branch) {
    return reads
        .query(
            "SELECT * FROM banker_slots WHERE world_id=? AND branch_id=? ORDER BY slot",
            List.of(world.value().toString(), branch.toString()),
            2)
        .stream()
        .map(BankStaffing::row)
        .toList();
  }

  public static Row row(Map<String, String> r) {
    var row =
        new Row(
            UUID.fromString(r.get("branch_id")),
            Integer.parseInt(r.get("slot")),
            UUID.fromString(r.get("desk_generation")),
            r.get("dimension"),
            Integer.parseInt(r.get("x")),
            Integer.parseInt(r.get("y")),
            Integer.parseInt(r.get("z")),
            r.get("desk_state"),
            UUID.fromString(r.get("assignment_id")),
            UUID.fromString(r.get("employee_id")),
            r.get("phase"),
            Integer.parseInt(r.get("elapsed")),
            Long.parseLong(r.get("revision")),
            UUID.fromString(r.get("operation_id")));
    validateRow(row);
    return row;
  }

  private static Journal.Record fact(WorldId world, Action action, Row row) {
    return new Journal.Record(FACT, 1, world.value() + "|" + action.name() + "|" + encodeRow(row));
  }

  public static void validateFact(String payload) {
    bounded(payload, 2304);
    var parts = payload.split("\\|", 3);
    if (parts.length != 3 || !UUID.fromString(parts[0]).toString().equals(parts[0]))
      throw new IllegalArgumentException("Staff fact world");
    Action.valueOf(parts[1]);
    decodeRow(parts[2]);
  }

  private static Reject reject(String code) {
    return new Reject(new Journal.Result(1, code, ""));
  }
}
