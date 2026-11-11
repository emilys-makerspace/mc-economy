package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans.BranchPlanV1;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Versioned later-site attempts. Initial captures and their selected providers are never rewritten.
 */
public final class BranchPlacement {
  public static final String FACT = "economy:branch_placement";
  public static final int MAX_CHOICE_BYTES = 12 * 1024;
  private static final Gson JSON =
      new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

  public enum Action {
    RESERVE,
    START,
    FINISH,
    FAIL,
    PENDING;

    public Journal.Kind kind() {
      return new Journal.Kind(
          "economy:branch_placement", "economy:" + name().toLowerCase(Locale.ROOT));
    }
  }

  public record Attempt(
      long number, UUID operation, BranchPlanV1 choice, String phase, String reason) {}

  private BranchPlacement() {}

  public static String encodeChoice(BranchPlanV1 choice) {
    var root = new JsonObject();
    root.addProperty("version", 1);
    root.add("choice", JSON.toJsonTree(choice));
    var result = JSON.toJson(sorted(root));
    if (result.getBytes(StandardCharsets.UTF_8).length > MAX_CHOICE_BYTES)
      throw new IllegalArgumentException("Placement choice limit");
    return result;
  }

  public static BranchPlanV1 decodeChoice(String body) {
    if (body == null || body.getBytes(StandardCharsets.UTF_8).length > MAX_CHOICE_BYTES)
      throw new IllegalArgumentException("Placement choice limit");
    int depth = 0;
    boolean quoted = false, escaped = false;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (quoted) {
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == '"') quoted = false;
      } else if (c == '"') quoted = true;
      else if (c == '{' || c == '[') {
        if (++depth > 24) throw new IllegalArgumentException("Placement nesting");
      } else if (c == '}' || c == ']') {
        if (--depth < 0) throw new IllegalArgumentException("Placement nesting");
      }
    }
    if (quoted || depth != 0) throw new IllegalArgumentException("Placement nesting");
    var root = JsonParser.parseString(body).getAsJsonObject();
    if (root.get("version").getAsInt() != 1)
      throw new IllegalArgumentException("Placement version");
    var choice = JSON.fromJson(root.get("choice"), BranchPlanV1.class);
    if (!body.equals(encodeChoice(choice)))
      throw new IllegalArgumentException("Noncanonical placement choice");
    return choice;
  }

  private static JsonElement sorted(JsonElement value) {
    if (value.isJsonObject()) {
      var fields = new TreeMap<String, JsonElement>();
      value.getAsJsonObject().entrySet().forEach(e -> fields.put(e.getKey(), e.getValue()));
      var result = new JsonObject();
      fields.forEach((k, v) -> result.add(k, sorted(v)));
      return result;
    }
    if (value.isJsonArray()) {
      var result = new JsonArray();
      value.getAsJsonArray().forEach(v -> result.add(sorted(v)));
      return result;
    }
    return value;
  }

  public static Optional<Attempt> latest(ReadView reads, WorldId world, UUID branch) {
    return reads
        .query(
            "SELECT * FROM village_placement_attempts WHERE world_id=? AND branch_id=? ORDER BY attempt DESC LIMIT 1",
            List.of(world.value().toString(), branch.toString()),
            1)
        .stream()
        .map(
            r ->
                new Attempt(
                    Long.parseLong(r.get("attempt")),
                    UUID.fromString(r.get("operation_id")),
                    decodeChoice(r.get("choice")),
                    r.get("phase"),
                    r.get("reason")))
        .findFirst();
  }

  public static Optional<BranchPlanV1> latestChoice(ReadView reads, WorldId world, UUID branch) {
    return latest(reads, world, branch).map(Attempt::choice);
  }

  public static Journal.CanonicalRequest request(
      UUID branch,
      long revision,
      long attempt,
      UUID operation,
      String reason,
      BranchPlanV1 choice) {
    if (revision < 1
        || attempt < 0
        || operation == null
        || reason == null
        || !reason.matches("[A-Z][A-Z0-9_]{0,127}"))
      throw new IllegalArgumentException("Placement request");
    var values = new TreeMap<String, String>();
    values.put("branch", branch.toString());
    values.put("revision", "" + revision);
    values.put("attempt", "" + attempt);
    values.put("operation", operation.toString());
    values.put("reason", reason);
    values.put(
        "choice",
        choice == null
            ? ""
            : Base64.getUrlEncoder().withoutPadding().encodeToString(hash(encodeChoice(choice))));
    return new Journal.CanonicalRequest(1, values);
  }

  private static byte[] hash(String text) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  public static Registration registration(
      Action action, WorldId world, Branches.Authority authority, BranchPlanV1 proposed) {
    return new Registration(
        1,
        (intent, reads) -> {
          if (!intent.kind().equals(action.kind()) || !authority.allows(intent))
            return new Decline(new Journal.Result(1, "PLACEMENT_ACCESS", ""));
          var fields = intent.request().fields();
          UUID branch, operation;
          long revision, attempt;
          String reason;
          try {
            branch = UUID.fromString(fields.get("branch"));
            operation = UUID.fromString(fields.get("operation"));
            revision = Long.parseLong(fields.get("revision"));
            attempt = Long.parseLong(fields.get("attempt"));
            reason = fields.get("reason");
            if (!request(branch, revision, attempt, operation, reason, proposed)
                .equals(intent.request())) return reject("PLACEMENT_REQUEST");
          } catch (IllegalArgumentException | NullPointerException bad) {
            return reject("PLACEMENT_REQUEST");
          }
          var view = Branches.find(reads, world, branch).orElse(null);
          if (view == null) return reject("BRANCH_ABSENT");
          if (view.revision() != revision) return reject("BRANCH_STALE");
          if (Set.of("ACTIVE", "REMOVED", "CONFLICT", "OMITTED").contains(view.state()))
            return reject("PLACEMENT_STATE");
          var prior = latest(reads, world, branch).orElse(null);
          String state, phase;
          BranchPlanV1 choice;
          if (action == Action.PENDING) {
            if (!view.state().equals("SITE_PENDING") || attempt != 0 || proposed != null)
              return reject("PLACEMENT_STATE");
            return commit(world, intent, view, "SITE_PENDING", reason, tx -> {});
          }
          if (action == Action.RESERVE) {
            if (!view.state().equals("SITE_PENDING")
                || proposed == null
                || attempt != (prior == null ? 1 : prior.number() + 1))
              return reject("PLACEMENT_STATE");
            var original = view.choice();
            choice = proposed;
            if (choice.state() != BranchPlans.State.RESERVED
                || choice.slot() != view.slot()
                || choice.required() != original.required()
                || choice.size() != original.size()
                    && (choice.size() != BranchPlans.Size.STANDARD || original.bounds() != null)
                || !Objects.equals(choice.institution(), original.institution())
                || choice.nativeCurrency() != original.nativeCurrency()
                || choice.optionalRoll() != original.optionalRoll())
              return reject("PLACEMENT_CAPTURE_CONFLICT");
            if (view.anchors().stream().anyMatch(a -> !a.get("state").equals("PENDING")))
              return reject("PLACEMENT_ANCHOR_USED");
            for (var a : choice.services()) {
              if (!a.generation().equals(BranchPlans.generation(branch, a.slot())))
                return reject("PLACEMENT_GENERATION");
              if (!reads
                  .query(
                      "SELECT generation FROM terminal_generations WHERE world_id=? AND generation=?",
                      List.of(world.value().toString(), a.generation().toString()),
                      1)
                  .isEmpty()) return reject("PLACEMENT_GENERATION_USED");
            }
            for (var a : choice.desks())
              if (!a.generation()
                  .equals(BranchPlans.generation(branch, choice.deskGenerationSlot(a.slot()))))
                return reject("PLACEMENT_GENERATION");
            state = "RESERVED";
            phase = "RESERVED";
          } else {
            if (prior == null
                || prior.number() != attempt
                || !prior.operation().equals(operation)
                || proposed != null) return reject("PLACEMENT_ATTEMPT_STALE");
            choice = prior.choice();
            switch (action) {
              case START -> {
                if (!prior.phase().equals("RESERVED") || !view.state().equals("RESERVED"))
                  return reject("PLACEMENT_STATE");
                state = "PLACING";
                phase = "PLACING";
              }
              case FINISH -> {
                if (!prior.phase().equals("PLACING") || !view.state().equals("PLACING"))
                  return reject("PLACEMENT_STATE");
                state = "PHYSICAL_COMPLETE";
                phase = "PHYSICAL_COMPLETE";
              }
              case FAIL -> {
                if (!Set.of("RESERVED", "PLACING").contains(prior.phase()))
                  return reject("PLACEMENT_STATE");
                state = "CONFLICT";
                phase = "CONFLICT";
              }
              default -> throw new IllegalStateException("Placement action");
            }
          }
          var records = new ArrayList<Journal.Record>();
          records.add(
              new Journal.Record(
                  FACT, 1, fact(world, branch, attempt, operation, phase, reason, choice)));
          var changed =
              (Commit)
                  commit(
                      world,
                      intent,
                      view,
                      state,
                      reason,
                      tx -> {
                        if (action == Action.RESERVE) {
                          tx.execute(
                              "INSERT INTO village_placement_attempts VALUES(?,?,?,?,?,?,?,?)",
                              List.of(
                                  world.value().toString(),
                                  branch.toString(),
                                  "" + attempt,
                                  operation.toString(),
                                  encodeChoice(choice),
                                  phase,
                                  reason,
                                  "" + revision));
                          for (var a : choice.services())
                            tx.execute(
                                """
                            INSERT INTO village_branch_anchors VALUES(?,?,?,?,?,?,?,?,?,?)
                            ON CONFLICT(world_id,branch_id,slot) DO UPDATE SET dimension=excluded.dimension,x=excluded.x,y=excluded.y,z=excluded.z
                            """,
                                List.of(
                                    world.value().toString(),
                                    branch.toString(),
                                    "" + a.slot(),
                                    view.plan().source().dimension(),
                                    "" + a.position().x(),
                                    "" + a.position().y(),
                                    "" + a.position().z(),
                                    a.generation().toString(),
                                    "0",
                                    "PENDING"));
                        } else
                          tx.execute(
                              "UPDATE village_placement_attempts SET phase=?,reason=? WHERE world_id=? AND branch_id=? AND attempt=?",
                              List.of(
                                  phase,
                                  reason,
                                  world.value().toString(),
                                  branch.toString(),
                                  "" + attempt));
                      });
          records.addAll(changed.records());
          return new Commit(records, changed.result(), changed.projections());
        });
  }

  private static Decision commit(
      WorldId world,
      Journal.Intent intent,
      Branches.View view,
      String state,
      String reason,
      Projection effect) {
    if (view.revision() == Long.MAX_VALUE) return reject("BRANCH_REVISION_LIMIT");
    long next = view.revision() + 1;
    var payload =
        String.join(
            "|",
            world.value().toString(),
            view.id().toString(),
            "" + next,
            state,
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(reason.getBytes(StandardCharsets.UTF_8)),
            intent.operationId().value().toString());
    return new Commit(
        List.of(new Journal.Record(Branches.FACT, 1, payload)),
        new Journal.Result(1, "BRANCH_" + state, view.id().toString()),
        tx -> {
          effect.apply(tx);
          int changed =
              tx.execute(
                  "UPDATE village_branches SET state=?,reason=?,revision=? WHERE world_id=? AND branch_id=? AND revision=?",
                  List.of(
                      state,
                      reason,
                      "" + next,
                      world.value().toString(),
                      view.id().toString(),
                      "" + view.revision()));
          if (changed != 1) throw new IllegalStateException("Placement revision changed");
          tx.execute(
              "INSERT INTO village_branch_events VALUES(?,?,?,?,?,?)",
              List.of(
                  world.value().toString(),
                  view.id().toString(),
                  "" + next,
                  state,
                  reason,
                  intent.operationId().value().toString()));
        });
  }

  private static String fact(
      WorldId world,
      UUID branch,
      long attempt,
      UUID operation,
      String phase,
      String reason,
      BranchPlanV1 choice) {
    return String.join(
        "|",
        world.value().toString(),
        branch.toString(),
        "" + attempt,
        operation.toString(),
        phase,
        reason,
        encodeChoice(choice));
  }

  public static void validateFact(String payload) {
    var values = payload.split("\\|", 7);
    if (values.length != 7) throw new IllegalArgumentException("Placement fact");
    var world = new WorldId(UUID.fromString(values[0]));
    var branch = UUID.fromString(values[1]);
    long attempt = Long.parseLong(values[2]);
    var operation = UUID.fromString(values[3]);
    if (attempt < 1
        || !Set.of("RESERVED", "PLACING", "PHYSICAL_COMPLETE", "CONFLICT").contains(values[4])
        || !values[5].matches("[A-Z][A-Z0-9_]{0,127}"))
      throw new IllegalArgumentException("Placement phase/reason");
    var choice = decodeChoice(values[6]);
    if (!fact(world, branch, attempt, operation, values[4], values[5], choice).equals(payload))
      throw new IllegalArgumentException("Placement canonical fact");
  }

  private static Reject reject(String code) {
    return new Reject(new Journal.Result(1, code, ""));
  }
}
