package ca.emilybelnavis.mcmods.economy.pricing;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Required installed-source contracts survive restart; removing an adapter cannot silently forgive
 * liabilities.
 */
public final class ValuationSources {
  private ValuationSources() {}

  public static final String FACT = "economy:a6/valuation-source-registration";
  public static final Journal.Kind REGISTER =
      new Journal.Kind("economy:a6", "economy:a6/valuation-sources/register");
  private static final int MAX_SOURCES = 8;

  public record Contract(String id, WealthValuation.Kind kind, boolean freshMark) {
    public Contract {
      if (id == null || !id.matches("[a-z0-9_.:/-]{1,96}"))
        throw new IllegalArgumentException("Valuation source identity");
      Objects.requireNonNull(kind);
    }

    String encoded() {
      return id + "|" + kind + "|" + (freshMark ? "1" : "0");
    }
  }

  /**
   * At most one new registration per call; true only after the whole configured manifest is
   * retained.
   */
  public static boolean prepare(FoundationStore store, WealthValuation valuation) {
    Objects.requireNonNull(store);
    Objects.requireNonNull(valuation);
    var world = store.snapshot().worldId();
    var desired = manifest(valuation);
    var missing = store.transaction(tx -> missing(tx::query, world, desired));
    if (missing == null) return true;
    String manifestHash =
        WealthValuation.digest(
            desired.values().stream()
                .map(Contract::encoded)
                .reduce((a, b) -> a + "\n" + b)
                .orElse(""));
    var actor = new SystemActorId(stable("actor|" + world.value()));
    var operation =
        new OperationId(
            stable("registration|" + world.value() + "|" + manifestHash + "|" + missing.id()));
    var request =
        new Journal.CanonicalRequest(
            1,
            Map.of(
                "source",
                missing.id(),
                "kind",
                missing.kind().name(),
                "fresh_mark",
                missing.freshMark() ? "1" : "0",
                "manifest",
                manifestHash));
    String payload = world.value() + "|" + missing.encoded() + "|" + manifestHash;
    var registration =
        new JournalEngine.Registration(
            1,
            (intent, reads) -> {
              if (!request.equals(intent.request()))
                throw new IllegalArgumentException("Valuation registration canonical");
              var next = missing(reads, world, desired);
              if (next == null || !next.equals(missing))
                return new JournalEngine.Reject(
                    new Journal.Result(1, "VALUATION_SOURCE_CHANGED", ""));
              return new JournalEngine.Commit(
                  List.of(new Journal.Record(FACT, 1, payload)),
                  new Journal.Result(1, "VALUATION_SOURCE_REGISTERED", missing.id()),
                  tx ->
                      tx.execute(
                          "INSERT INTO pricing_valuation_sources(source_id,kind,fresh_mark) VALUES(?,?,?)",
                          List.of(
                              missing.id(),
                              missing.kind().name(),
                              missing.freshMark() ? "1" : "0")));
            });
    var engine =
        new JournalEngine(
            store,
            (candidate, kind) -> candidate.equals(actor) && kind.equals(REGISTER),
            Map.of(REGISTER, registration));
    var outcome =
        engine.submit(
            new Journal.Intent(
                operation, new RequestId(operation.value()), actor, REGISTER, request, null, null));
    if (outcome.status() != Journal.Status.COMMITTED)
      throw failure("Could not register required valuation source");
    // Detect lost projections even if A1 resolved an old operation rather than replaying its
    // effects.
    store.transaction(
        tx -> {
          var retained = retained(tx::query, world);
          if (!missing.equals(retained.get(missing.id())))
            throw failure("Valuation source registration projection missing");
          return null;
        });
    return false;
  }

  /**
   * Read-only startup/backup check; new sources may be registered later, retained sources cannot
   * disappear.
   */
  public static void validate(
      JournalEngine.ReadView reads, WorldId world, WealthValuation valuation) {
    missing(reads, world, manifest(valuation));
  }

  private static SortedMap<String, Contract> manifest(WealthValuation valuation) {
    var result = new TreeMap<String, Contract>();
    for (var contract : valuation.sourceManifest())
      if (result.putIfAbsent(contract.id(), contract) != null)
        throw failure("Duplicate valuation source contract");
    if (result.size() > MAX_SOURCES) throw failure("Valuation source registry bound");
    return Collections.unmodifiableSortedMap(result);
  }

  private static Contract missing(
      JournalEngine.ReadView reads, WorldId world, SortedMap<String, Contract> desired) {
    var retained = retained(reads, world);
    for (var contract : retained.values())
      if (!contract.equals(desired.get(contract.id())))
        throw failure("Missing or changed required valuation source: " + contract.id());
    return desired.values().stream()
        .filter(c -> !retained.containsKey(c.id()))
        .findFirst()
        .orElse(null);
  }

  /**
   * Compare both directions against immutable evidence so deleting a projection cannot uninstall a
   * source.
   */
  private static Map<String, Contract> retained(JournalEngine.ReadView reads, WorldId world) {
    var rows =
        reads.query(
            "SELECT source_id,kind,fresh_mark FROM pricing_valuation_sources ORDER BY source_id LIMIT 9",
            List.of(),
            MAX_SOURCES + 1);
    if (rows.size() > MAX_SOURCES) throw failure("Valuation source registry bound");
    var projected = new TreeMap<String, Contract>();
    for (var row : rows) {
      var contract =
          new Contract(
              row.get("source_id"),
              WealthValuation.Kind.valueOf(row.get("kind")),
              flag(row.get("fresh_mark")));
      projected.put(contract.id(), contract);
    }
    var facts =
        reads.query(
            "SELECT payload FROM journal_records WHERE record_type='economy:a6/valuation-source-registration' AND record_version=1 ORDER BY sequence LIMIT 9",
            List.of(),
            MAX_SOURCES + 1);
    if (facts.size() > MAX_SOURCES) throw failure("Valuation source evidence bound");
    var evidenced = new TreeMap<String, Contract>();
    for (var fact : facts) {
      var decoded = decode(fact.get("payload"));
      if (!decoded.world().equals(world)
          || evidenced.putIfAbsent(decoded.contract().id(), decoded.contract()) != null)
        throw failure("Valuation source evidence identity");
    }
    if (!projected.equals(evidenced))
      throw failure("Valuation source registration projection differs from immutable evidence");
    return Collections.unmodifiableMap(projected);
  }

  private record Registration(WorldId world, Contract contract, String manifest) {}

  private static Registration decode(String payload) {
    var p = payload.split("\\|", -1);
    if (p.length != 5) throw new IllegalArgumentException("Valuation source registration fields");
    var world = new WorldId(UUID.fromString(p[0]));
    if (!world.value().toString().equals(p[0]) || !p[4].matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Valuation source registration identity");
    var contract = new Contract(p[1], WealthValuation.Kind.valueOf(p[2]), flag(p[3]));
    return new Registration(world, contract, p[4]);
  }

  private static boolean flag(String value) {
    if (!Set.of("0", "1").contains(value))
      throw new IllegalArgumentException("Valuation freshness flag");
    return value.equals("1");
  }

  private static UUID stable(String value) {
    return UUID.nameUUIDFromBytes(
        ("economy:valuation-sources:v1|" + value).getBytes(StandardCharsets.UTF_8));
  }

  private static StorageException failure(String detail) {
    return new StorageException(StorageException.Kind.SCHEMA, detail);
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog.with(new RecordCatalog.Key(FACT, 1), ValuationSources::decode);
  }
}
