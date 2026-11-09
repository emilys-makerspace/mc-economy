package ca.emilybelnavis.mcmods.economy.village;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.SystemActorId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy.Kind;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Detached version-one generation contracts. No world access, JDBC or financial authority. */
public final class BranchPlans {
  public static final int VERSION = 1;
  public static final int MAX_PLAN_BYTES = 256 * 1024;
  private static final Gson JSON =
      new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
  private static final Set<String> STYLES = Set.of("plains", "desert", "savanna", "snowy", "taiga");

  public enum State {
    CAPTURE_PENDING,
    PROVIDER_PENDING,
    SITE_PENDING,
    RESERVED,
    PLACING,
    PHYSICAL_COMPLETE,
    ACTIVATION_PENDING,
    ACTIVE,
    REMOVED,
    CONFLICT,
    OMITTED
  }

  public enum Size {
    COMPACT,
    LARGER,
    STANDARD;

    public int deskGenerationSlot(int deskSlot) {
      return this == STANDARD ? 4 : deskSlot + 2;
    }
  }

  public enum CaptureStatus {
    CAPTURE_PENDING,
    CAPTURED,
    EXCLUDED
  }

  public enum ManifestState {
    PLANNED,
    COMPLETE,
    CONFLICT
  }

  public record Source(String dimension, String structure, int startX, int startZ) {
    public Source {
      BranchPlans.key(dimension);
      BranchPlans.key(structure);
      if (Math.abs((long) startX) > 1_875_000 || Math.abs((long) startZ) > 1_875_000)
        throw new IllegalArgumentException("Village source coordinates");
    }

    public String key() {
      return dimension + "|" + structure + "|" + startX + "|" + startZ;
    }
  }

  public record Point(int x, int y, int z) {
    public Point {
      if (Math.abs((long) x) > 30_000_000
          || Math.abs((long) z) > 30_000_000
          || y < -2048
          || y > 2047) throw new IllegalArgumentException("Branch point bounds");
    }
  }

  public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public Bounds {
      new Point(minX, minY, minZ);
      new Point(maxX, maxY, maxZ);
      if (minX > maxX || minY > maxY || minZ > maxZ)
        throw new IllegalArgumentException("Branch box order");
    }

    public boolean contains(Point point) {
      return point.x() >= minX
          && point.x() <= maxX
          && point.y() >= minY
          && point.y() <= maxY
          && point.z() >= minZ
          && point.z() <= maxZ;
    }
  }

  public record Provider(
      String institution, EconomyCurrency nativeCurrency, boolean primaryEligible) {
    public Provider {
      var entry = InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(institution));
      if (!entry.enabled()
          || !entry.customerBank()
          || !entry.capabilities().contains(InstitutionCatalog.Capability.BANK_ACCOUNT)
          || entry.baseCurrency() != Objects.requireNonNull(nativeCurrency)
          || primaryEligible && !entry.tier1())
        throw new IllegalArgumentException("Branch provider capability/currency");
    }
  }

  public record PlanningSnapshotV1(
      EconomyCurrency denomination,
      long denominationRevision,
      long catalogRevision,
      long policyRevision,
      String resourceRevision,
      List<Provider> providers,
      List<String> readinessReasons) {
    public PlanningSnapshotV1 {
      Objects.requireNonNull(denomination);
      if (denominationRevision < 0 || catalogRevision < 1 || policyRevision < 1)
        throw new IllegalArgumentException("Planning revisions");
      text(resourceRevision, 128, false);
      providers = sorted(providers, 23, Comparator.comparing(Provider::institution));
      distinct(providers.stream().map(Provider::institution).toList(), "provider");
      readinessReasons = List.copyOf(readinessReasons);
      if (readinessReasons.size() > 32) throw new IllegalArgumentException("Readiness reasons");
      readinessReasons.forEach(reason -> text(reason, 128, false));
    }
  }

  public record Anchor(int slot, Point position, UUID generation, String geometry) {
    public Anchor {
      BranchPlans.slot(slot, 3);
      Objects.requireNonNull(position);
      Objects.requireNonNull(generation);
      text(geometry, 128, false);
    }

    public Kind serviceKind() {
      return switch (geometry) {
        case "atm" -> Kind.ATM;
        case "wide", "narrow" -> Kind.KIOSK;
        default -> throw new IllegalStateException("Unknown service geometry " + geometry);
      };
    }
  }

  public record Transform(Point origin, int quarterTurns) {
    public Transform {
      Objects.requireNonNull(origin);
      if (quarterTurns < 0 || quarterTurns > 3)
        throw new IllegalArgumentException("Branch rotation");
    }
  }

  public record Manifest(
      int chunkX,
      int chunkZ,
      String beforeDigest,
      String afterDigest,
      ManifestState state,
      UUID operationId) {
    public Manifest {
      if (Math.abs((long) chunkX) > 1_875_000 || Math.abs((long) chunkZ) > 1_875_000)
        throw new IllegalArgumentException("Manifest chunk");
      hash(beforeDigest);
      hash(afterDigest);
      Objects.requireNonNull(state);
      Objects.requireNonNull(operationId);
    }
  }

  public record BranchPlanV1(
      int slot,
      boolean required,
      Size size,
      String institution,
      EconomyCurrency nativeCurrency,
      String templateRevision,
      Transform transform,
      Bounds bounds,
      Point entrance,
      List<Anchor> services,
      List<Anchor> desks,
      List<Manifest> manifests,
      State state,
      String reason,
      long placementRevision,
      int optionalRoll) {
    public BranchPlanV1 {
      BranchPlans.slot(slot, 3);
      Objects.requireNonNull(size);
      Objects.requireNonNull(state);
      text(reason, 128, true);
      if (placementRevision < 0 || optionalRoll < -1 || optionalRoll > 99)
        throw new IllegalArgumentException("Branch placement revision/roll");
      if ((institution == null) != (nativeCurrency == null))
        throw new IllegalArgumentException("Partial provider");
      if (institution != null) {
        var bank = InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(institution));
        if (bank.baseCurrency() != nativeCurrency)
          throw new IllegalArgumentException("Branch native currency");
      }
      if (templateRevision != null) text(templateRevision, 128, false);
      services = sorted(services, 3, Comparator.comparingInt(Anchor::slot));
      desks = sorted(desks, 2, Comparator.comparingInt(Anchor::slot));
      manifests =
          sorted(
              manifests,
              1024,
              Comparator.comparingInt(Manifest::chunkX).thenComparingInt(Manifest::chunkZ));
      distinct(services.stream().map(Anchor::slot).toList(), "service slot");
      for (int i = 0; i < services.size(); i++)
        if (services.get(i).slot() != i) throw new IllegalArgumentException("Service slot gap");
      for (int i = 0; i < desks.size(); i++)
        if (desks.get(i).slot() != i) throw new IllegalArgumentException("Desk slot gap");
      distinct(desks.stream().map(Anchor::slot).toList(), "desk slot");
      distinct(manifests.stream().map(m -> m.chunkX() + "," + m.chunkZ()).toList(), "fragment");
      var allAnchors = new ArrayList<>(services);
      allAnchors.addAll(desks);
      distinct(allAnchors.stream().map(Anchor::position).toList(), "anchor position");
      distinct(allAnchors.stream().map(Anchor::generation).toList(), "anchor generation");
      if (required && state == State.OMITTED)
        throw new IllegalArgumentException("Required branch omitted");
      if (state == State.OMITTED
          && (!Set.of("NOT_SELECTED", "NO_DISTINCT_PROVIDER").contains(reason)
              || institution != null
              || transform != null
              || bounds != null
              || entrance != null
              || !allAnchors.isEmpty()
              || !manifests.isEmpty())) throw new IllegalArgumentException("Optional omission");
      if (state == State.PROVIDER_PENDING && institution != null)
        throw new IllegalArgumentException("Provider already captured");
      boolean physical =
          switch (state) {
            case RESERVED, PLACING, PHYSICAL_COMPLETE, ACTIVATION_PENDING, ACTIVE -> true;
            default -> false;
          };
      if (physical
          && (institution == null
              || templateRevision == null
              || transform == null
              || bounds == null
              || entrance == null))
        throw new IllegalArgumentException("Incomplete physical branch plan");
      int serviceCapacity =
          switch (size) {
            case COMPACT -> 1;
            case LARGER -> 2;
            case STANDARD -> 3;
          };
      int deskCapacity = size == Size.LARGER ? 2 : 1;
      if (services.size() > serviceCapacity
          || desks.size() > deskCapacity
          || physical && (services.size() != serviceCapacity || desks.size() != deskCapacity))
        throw new IllegalArgumentException("Branch anchor capacity");
      for (var anchor : services) {
        boolean atm = size == Size.STANDARD && anchor.slot() > 0;
        if (atm
            ? !anchor.geometry().equals("atm")
            : !Set.of("wide", "narrow").contains(anchor.geometry()))
          throw new IllegalArgumentException("Branch service geometry");
      }
      for (var anchor : allAnchors)
        if (bounds == null || !bounds.contains(anchor.position()))
          throw new IllegalArgumentException("Anchor outside reservation");
      if (entrance != null && (bounds == null || !bounds.contains(entrance)))
        throw new IllegalArgumentException("Entrance outside reservation");
    }

    public int deskGenerationSlot(int deskSlot) {
      return size.deskGenerationSlot(deskSlot);
    }
  }

  public record VillagePlanV1(
      Source source,
      String style,
      int buildingCount,
      boolean abandoned,
      List<Bounds> originalBounds,
      List<Point> junctions,
      PlanningSnapshotV1 snapshot,
      CaptureStatus captureStatus,
      List<BranchPlanV1> branches,
      String diagnostics) {
    public VillagePlanV1(
        Source source,
        String style,
        int buildingCount,
        List<Bounds> originalBounds,
        List<Point> junctions,
        PlanningSnapshotV1 snapshot,
        CaptureStatus captureStatus,
        List<BranchPlanV1> branches,
        String diagnostics) {
      this(
          source,
          style,
          buildingCount,
          false,
          originalBounds,
          junctions,
          snapshot,
          captureStatus,
          branches,
          diagnostics);
    }

    public VillagePlanV1 {
      Objects.requireNonNull(source);
      Objects.requireNonNull(captureStatus);
      if (!STYLES.contains(style) || !source.structure().equals("minecraft:village_" + style))
        throw new IllegalArgumentException("Village style/source");
      if (buildingCount < 0 || buildingCount > 1024)
        throw new IllegalArgumentException("Village building count");
      originalBounds = List.copyOf(originalBounds);
      junctions = List.copyOf(junctions);
      if (originalBounds.isEmpty() || originalBounds.size() > 1024 || junctions.size() > 4096)
        throw new IllegalArgumentException("Original layout bounds");
      branches = sorted(branches, 3, Comparator.comparingInt(BranchPlanV1::slot));
      distinct(branches.stream().map(BranchPlanV1::slot).toList(), "branch slot");
      text(diagnostics, 4096, true);
      if (captureStatus == CaptureStatus.CAPTURE_PENDING
          && (snapshot != null || !branches.isEmpty()))
        throw new IllegalArgumentException("Uncaptured village choices");
      if (captureStatus == CaptureStatus.EXCLUDED || abandoned || buildingCount == 0) {
        if (captureStatus != CaptureStatus.EXCLUDED || !branches.isEmpty())
          throw new IllegalArgumentException("Excluded village has branches");
      } else if (captureStatus == CaptureStatus.CAPTURED) {
        Objects.requireNonNull(snapshot, "Captured planning snapshot");
        validateChoices(buildingCount, snapshot, branches);
      }
    }

    public int algorithmRevision() {
      return VERSION;
    }

    public int classificationRevision() {
      return VERSION;
    }

    public String digest() {
      return BranchPlans.digest(this);
    }
  }

  private BranchPlans() {}

  /**
   * Exact canonical JSON. Its digest is stored separately and never recursively encoded in itself.
   */
  public static String encode(VillagePlanV1 plan) {
    Objects.requireNonNull(plan);
    var envelope = new JsonObject();
    envelope.addProperty("version", VERSION);
    envelope.add("plan", JSON.toJsonTree(plan));
    var encoded = JSON.toJson(canonical(envelope));
    if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_PLAN_BYTES)
      throw new IllegalArgumentException("Plan bytes");
    return encoded;
  }

  public static VillagePlanV1 decode(String encoded) {
    boundedJson(encoded);
    try {
      var envelope = JsonParser.parseString(encoded).getAsJsonObject();
      if (envelope.get("version").getAsInt() != VERSION)
        throw new IllegalArgumentException("Unsupported plan version");
      var plan = JSON.fromJson(envelope.get("plan"), VillagePlanV1.class);
      if (!encode(plan).equals(encoded)) throw new IllegalArgumentException("Noncanonical plan");
      return plan;
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException("Invalid village plan", malformed);
    }
  }

  public static String digest(VillagePlanV1 plan) {
    return HexFormat.of().formatHex(sha256(encode(plan).getBytes(StandardCharsets.UTF_8)));
  }

  public static UUID id(WorldId world, Source source) {
    Objects.requireNonNull(world);
    Objects.requireNonNull(source);
    return identity(
        "economy:a7:village:v1",
        out -> {
          uuid(out, world.value());
          string(out, source.dimension());
          string(out, source.structure());
          out.writeInt(source.startX());
          out.writeInt(source.startZ());
        });
  }

  public static UUID branchId(UUID villageId, int slot) {
    slot(slot, 3);
    return identity(
        "economy:a7:branch:v1",
        out -> {
          uuid(out, villageId);
          out.writeByte(slot);
        });
  }

  public static SystemActorId owner(UUID branchId) {
    return new SystemActorId(identity("economy:a7:owner:v1", out -> uuid(out, branchId)));
  }

  /** Legacy desks use indexes 2/3. Standard banks use service indexes 0–2 and desk index 4. */
  public static UUID generation(UUID branchId, int slot) {
    slot(slot, 5);
    return identity(
        "economy:a7:anchor:v1",
        out -> {
          uuid(out, branchId);
          out.writeByte(slot);
        });
  }

  public static UUID operation(UUID branchId, int slot, String action, int effectVersion) {
    slot(slot, 4);
    text(action, 128, false);
    if (effectVersion < 1) throw new IllegalArgumentException("Effect version");
    return identity(
        "economy:a7:effect:v1",
        out -> {
          uuid(out, branchId);
          out.writeByte(slot);
          string(out, action);
          out.writeInt(effectVersion);
        });
  }

  private static void validateChoices(
      int count, PlanningSnapshotV1 snapshot, List<BranchPlanV1> branches) {
    boolean standard = branches.stream().anyMatch(b -> b.size() == Size.STANDARD);
    int slots = standard ? standardSlotCount(count) : count <= 8 ? 1 : count <= 16 ? 2 : 3;
    if (branches.size() != slots) throw new IllegalArgumentException("Village slot count");
    var used = new HashSet<String>();
    for (int index = 0; index < slots; index++) {
      var branch = branches.get(index);
      boolean required =
          standard ? index < Math.min(2, slots) : index == 0 || count >= 17 && index == 1;
      Size size = standard ? Size.STANDARD : count <= 8 || !required ? Size.COMPACT : Size.LARGER;
      if (branch.slot() != index || branch.required() != required || branch.size() != size)
        throw new IllegalArgumentException("Village slot semantics");
      if (required && branch.optionalRoll() != -1 || !required && branch.optionalRoll() < 0)
        throw new IllegalArgumentException("Optional branch roll");
      int threshold = count <= 16 ? 25 : 50;
      boolean selected = required || branch.optionalRoll() < threshold;
      if (!selected && (branch.state() != State.OMITTED || !branch.reason().equals("NOT_SELECTED")))
        throw new IllegalArgumentException("Unselected optional branch");
      if (selected
          && branch.state() == State.OMITTED
          && !branch.reason().equals("NO_DISTINCT_PROVIDER"))
        throw new IllegalArgumentException("Selected optional branch omission");
      List<Provider> available =
          snapshot.providers().stream()
              .filter(p -> !used.contains(p.institution()))
              .filter(
                  p ->
                      indexNotPrimary(branch)
                          || p.primaryEligible() && p.nativeCurrency() == snapshot.denomination())
              .toList();
      if (branch.institution() == null) {
        if (selected && branch.state() != State.PROVIDER_PENDING && branch.state() != State.OMITTED)
          throw new IllegalArgumentException("Missing selected provider");
        if (selected && !available.isEmpty())
          throw new IllegalArgumentException("Eligible provider omitted");
      } else {
        var provider =
            available.stream()
                .filter(p -> p.institution().equals(branch.institution()))
                .findFirst()
                .orElseThrow(
                    () -> new IllegalArgumentException("Provider outside captured eligibility"));
        if (provider.nativeCurrency() != branch.nativeCurrency())
          throw new IllegalArgumentException("Captured provider currency");
        if (index > 0
            && provider.nativeCurrency() == snapshot.denomination()
            && available.stream().anyMatch(p -> p.nativeCurrency() != snapshot.denomination()))
          throw new IllegalArgumentException("Foreign provider preference");
        used.add(branch.institution());
      }
    }
  }

  static int standardSlotCount(int buildingCount) {
    return buildingCount <= 8 ? 1 : 3;
  }

  private static boolean indexNotPrimary(BranchPlanV1 branch) {
    return branch.slot() != 0;
  }

  private static JsonElement canonical(JsonElement value) {
    if (value.isJsonObject()) {
      var sorted = new TreeMap<String, JsonElement>();
      value.getAsJsonObject().entrySet().forEach(e -> sorted.put(e.getKey(), e.getValue()));
      var result = new JsonObject();
      sorted.forEach((key, child) -> result.add(key, canonical(child)));
      return result;
    }
    if (value.isJsonArray()) {
      var result = new com.google.gson.JsonArray();
      value.getAsJsonArray().forEach(child -> result.add(canonical(child)));
      return result;
    }
    return value;
  }

  private static void boundedJson(String value) {
    Objects.requireNonNull(value);
    if (value.getBytes(StandardCharsets.UTF_8).length > MAX_PLAN_BYTES)
      throw new IllegalArgumentException("Plan bytes");
    int depth = 0;
    boolean quoted = false;
    boolean escape = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (quoted) {
        if (escape) escape = false;
        else if (c == '\\') escape = true;
        else if (c == '"') quoted = false;
      } else if (c == '"') quoted = true;
      else if (c == '{' || c == '[') {
        if (++depth > 24) throw new IllegalArgumentException("Plan nesting");
      } else if (c == '}' || c == ']') {
        if (--depth < 0) throw new IllegalArgumentException("Plan nesting");
      }
    }
    if (quoted || depth != 0) throw new IllegalArgumentException("Plan nesting");
  }

  private static <T> List<T> sorted(List<T> list, int limit, Comparator<? super T> comparator) {
    Objects.requireNonNull(list);
    if (list.size() > limit) throw new IllegalArgumentException("Plan list bound");
    var copy = new ArrayList<>(List.copyOf(list));
    copy.sort(comparator);
    return List.copyOf(copy);
  }

  private static void distinct(List<?> values, String name) {
    if (new HashSet<>(values).size() != values.size())
      throw new IllegalArgumentException("Duplicate " + name);
  }

  private static void slot(int value, int count) {
    if (value < 0 || value >= count) throw new IllegalArgumentException("Branch slot");
  }

  private static void key(String value) {
    if (value == null
        || value.getBytes(StandardCharsets.UTF_8).length > 128
        || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
      throw new IllegalArgumentException("Plan resource key");
  }

  private static void text(String value, int max, boolean empty) {
    if (value == null
        || !empty && value.isEmpty()
        || value.getBytes(StandardCharsets.UTF_8).length > max)
      throw new IllegalArgumentException("Plan text bound");
  }

  private static void hash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Plan hash");
  }

  @FunctionalInterface
  private interface Writer {
    void write(DataOutputStream output) throws IOException;
  }

  private static UUID identity(String domain, Writer writer) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var output = new DataOutputStream(bytes)) {
        string(output, domain);
        writer.write(output);
      }
      var hash = sha256(bytes.toByteArray());
      hash[6] = (byte) ((hash[6] & 15) | 0x80);
      hash[8] = (byte) ((hash[8] & 63) | 0x80);
      var input = ByteBuffer.wrap(hash);
      return new UUID(input.getLong(), input.getLong());
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void uuid(DataOutputStream out, UUID value) throws IOException {
    Objects.requireNonNull(value);
    out.writeLong(value.getMostSignificantBits());
    out.writeLong(value.getLeastSignificantBits());
  }

  private static void string(DataOutputStream out, String value) throws IOException {
    var bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
