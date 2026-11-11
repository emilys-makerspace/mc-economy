package ca.emilybelnavis.mcmods.economy.recovery;

import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small generic rebuild primitive; generations are derived state, never a second authority. */
public final class ProjectionManager {
  public interface Cells {
    String get(String key);

    void put(String key, String value);
  }

  @FunctionalInterface
  public interface Reducer {
    void apply(Journal.Envelope envelope, Cells cells);
  }

  public record Snapshot(
      String name,
      int version,
      String generation,
      long sequence,
      String transactionId,
      Map<String, String> values) {
    public Snapshot {
      values = Map.copyOf(values);
    }
  }

  public record Definition(
      String name,
      int version,
      Set<RecordCatalog.Key> consumes,
      Reducer reducer,
      java.util.function.Predicate<Snapshot> validator) {
    public Definition {
      new Journal.Kind(name, "foundation:projection");
      if (version < 1 || consumes.size() > 64)
        throw new IllegalArgumentException("Projection bounds");
      consumes = Set.copyOf(consumes);
      Objects.requireNonNull(reducer);
      Objects.requireNonNull(validator);
    }
  }

  public record Progress(String name, long applied, long target, boolean ready) {}

  private record Build(Definition definition, String generation, long sequence, long target) {}

  private final FoundationStore store;
  private final JournalEngine journal;
  private final List<Definition> definitions;

  public ProjectionManager(FoundationStore store, Collection<Definition> definitions) {
    this.store = store;
    this.journal = new JournalEngine(store, (actor, kind) -> false, Map.of());
    if (definitions.size() > 32) throw new IllegalArgumentException("Projection registry bound");
    this.definitions = definitions.stream().sorted(Comparator.comparing(Definition::name)).toList();
    if (this.definitions.stream().map(Definition::name).distinct().count()
        != this.definitions.size()) throw new IllegalArgumentException("Duplicate projection");
  }

  public Progress step(int maximumTransactions) {
    if (maximumTransactions < 1 || maximumTransactions > 16)
      throw new IllegalArgumentException("Projection batch bound");
    store.transaction(
        tx -> {
          var names =
              definitions.stream()
                  .map(Definition::name)
                  .collect(java.util.stream.Collectors.toSet());
          for (var row : tx.query("SELECT name FROM projection_heads", List.of(), 32))
            if (!names.contains(row.get("name")))
              throw new StorageException(
                  StorageException.Kind.SCHEMA,
                  "Persisted projection has no registered definition: " + row.get("name"));
          return null;
        });
    for (var definition : definitions) {
      Build build = store.transaction(tx -> ensure(tx, definition));
      if (build == null) continue;
      var page =
          journal.readAfter(build.sequence(), maximumTransactions).stream()
              .filter(e -> e.sequence().value() <= build.target())
              .toList();
      if (page.isEmpty() && build.sequence() < build.target())
        throw failure("Missing journal transaction during rebuild");
      long applied =
          store.transaction(
              tx -> {
                long sequence = build.sequence();
                String transaction = null;
                var cells = new GenerationCells(tx, definition.name(), build.generation());
                for (var envelope : page) {
                  if (envelope.records().stream()
                      .anyMatch(
                          r ->
                              definition
                                  .consumes()
                                  .contains(new RecordCatalog.Key(r.type(), r.version()))))
                    definition.reducer().apply(envelope, cells);
                  sequence = envelope.sequence().value();
                  transaction = envelope.transactionId().value().toString();
                }
                if (page.isEmpty())
                  transaction =
                      tx.query(
                              "SELECT last_transaction FROM projection_generations WHERE name=? AND generation=?",
                              List.of(definition.name(), build.generation()),
                              1)
                          .getFirst()
                          .get("last_transaction");
                var values = values(tx, definition.name(), build.generation());
                String checksum = digest(definition.version(), sequence, transaction, values);
                tx.execute(
                    "UPDATE projection_generations SET last_sequence=?,last_transaction=?,checksum=? WHERE name=? AND generation=? AND state='BUILDING'",
                    Arrays.asList(
                        str(sequence),
                        transaction,
                        checksum,
                        definition.name(),
                        build.generation()));
                if (sequence == build.target()) {
                  var snapshot =
                      new Snapshot(
                          definition.name(),
                          definition.version(),
                          build.generation(),
                          sequence,
                          transaction,
                          values);
                  if (!definition.validator().test(snapshot))
                    throw failure("Completed projection failed validation: " + definition.name());
                  tx.execute(
                      "UPDATE projection_generations SET state='RETIRED' WHERE name=? AND state='ACTIVE'",
                      List.of(definition.name()));
                  tx.execute(
                      "UPDATE projection_generations SET state='ACTIVE' WHERE name=? AND generation=?",
                      List.of(definition.name(), build.generation()));
                  tx.execute(
                      "UPDATE projection_heads SET active_generation=? WHERE name=?",
                      List.of(build.generation(), definition.name()));
                }
                return sequence;
              });
      return new Progress(definition.name(), applied, build.target(), false);
    }
    return new Progress("projections-ready", 0, 0, true);
  }

  private Build ensure(FoundationStore.Transaction tx, Definition definition) {
    long target =
        Long.parseLong(
            tx.query(
                    "SELECT COALESCE(max(sequence),0) AS sequence FROM journal_transactions",
                    List.of(),
                    1)
                .getFirst()
                .get("sequence"));
    tx.execute(
        "INSERT INTO projection_heads(name,active_generation) VALUES(?,NULL) ON CONFLICT(name) DO NOTHING",
        List.of(definition.name()));
    var active =
        tx.query(
            "SELECT g.* FROM projection_heads h JOIN projection_generations g ON g.name=h.name AND g.generation=h.active_generation WHERE h.name=?",
            List.of(definition.name()),
            1);
    if (!active.isEmpty()
        && valid(tx, definition, active.getFirst())
        && active.getFirst().get("state").equals("ACTIVE")
        && Long.parseLong(active.getFirst().get("last_sequence")) == target) return null;
    var builds =
        tx.query(
            "SELECT * FROM projection_generations WHERE name=? AND state='BUILDING' ORDER BY generation",
            List.of(definition.name()),
            32);
    for (var build : builds) {
      if (valid(tx, definition, build) && Long.parseLong(build.get("last_sequence")) <= target) {
        tx.execute(
            "UPDATE projection_generations SET target_sequence=? WHERE name=? AND generation=?",
            List.of(str(target), definition.name(), build.get("generation")));
        return new Build(
            definition,
            build.get("generation"),
            Long.parseLong(build.get("last_sequence")),
            target);
      }
      tx.execute(
          "UPDATE projection_generations SET state='FAILED' WHERE name=? AND generation=?",
          List.of(definition.name(), build.get("generation")));
    }
    String generation = UUID.randomUUID().toString();
    tx.execute(
        "INSERT INTO projection_generations VALUES(?,?,?,'BUILDING',0,NULL,?,?)",
        List.of(
            definition.name(),
            generation,
            str(definition.version()),
            str(target),
            digest(definition.version(), 0, null, Map.of())));
    return new Build(definition, generation, 0, target);
  }

  private boolean valid(
      FoundationStore.Transaction tx, Definition definition, Map<String, String> row) {
    if (Integer.parseInt(row.get("version")) != definition.version()) return false;
    long sequence = Long.parseLong(row.get("last_sequence"));
    String transaction = row.get("last_transaction");
    if (sequence == 0 ? transaction != null : transaction == null) return false;
    if (sequence > 0) {
      var checkpoint =
          tx.query(
              "SELECT transaction_id FROM journal_transactions WHERE sequence=?",
              List.of(str(sequence)),
              1);
      if (checkpoint.isEmpty() || !transaction.equals(checkpoint.getFirst().get("transaction_id")))
        return false;
    }
    var values = values(tx, definition.name(), row.get("generation"));
    return row.get("checksum").equals(digest(definition.version(), sequence, transaction, values))
        && (!row.get("state").equals("ACTIVE")
            || definition
                .validator()
                .test(
                    new Snapshot(
                        definition.name(),
                        definition.version(),
                        row.get("generation"),
                        sequence,
                        transaction,
                        values)));
  }

  public Snapshot snapshot(String name) {
    var definition =
        definitions.stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
    return store.transaction(
        tx -> {
          var rows =
              tx.query(
                  "SELECT g.* FROM projection_heads h JOIN projection_generations g ON g.name=h.name AND g.generation=h.active_generation WHERE h.name=?",
                  List.of(name),
                  1);
          if (rows.isEmpty()
              || !rows.getFirst().get("state").equals("ACTIVE")
              || !valid(tx, definition, rows.getFirst()))
            throw new IllegalStateException("Projection not available: " + name);
          var row = rows.getFirst();
          return new Snapshot(
              name,
              definition.version(),
              row.get("generation"),
              Long.parseLong(row.get("last_sequence")),
              row.get("last_transaction"),
              values(tx, name, row.get("generation")));
        });
  }

  static Map<String, String> values(
      FoundationStore.Transaction tx, String name, String generation) {
    Map<String, String> values = new TreeMap<>();
    for (var row :
        tx.query(
            "SELECT key,value FROM projection_cells WHERE name=? AND generation=? ORDER BY key",
            List.of(name, generation),
            1024)) values.put(row.get("key"), row.get("value"));
    return Collections.unmodifiableMap(values);
  }

  public static String digest(
      int version, long sequence, String transaction, Map<String, String> values) {
    try {
      var hash = java.security.MessageDigest.getInstance("SHA-256");
      for (String value :
          List.of(str(version), str(sequence), transaction == null ? "" : transaction))
        add(hash, value);
      for (var entry : new TreeMap<>(values).entrySet()) {
        add(hash, entry.getKey());
        add(hash, entry.getValue());
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private static void add(java.security.MessageDigest hash, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
    hash.update(bytes);
  }

  private final class GenerationCells implements Cells {
    private final FoundationStore.Transaction tx;
    private final String name, generation;

    GenerationCells(FoundationStore.Transaction tx, String name, String generation) {
      this.tx = tx;
      this.name = name;
      this.generation = generation;
    }

    public String get(String key) {
      var rows =
          tx.query(
              "SELECT value FROM projection_cells WHERE name=? AND generation=? AND key=?",
              List.of(name, generation, key),
              1);
      return rows.isEmpty() ? null : rows.getFirst().get("value");
    }

    public void put(String key, String value) {
      if (key == null
          || key.getBytes(StandardCharsets.UTF_8).length > 128
          || value == null
          || value.getBytes(StandardCharsets.UTF_8).length > 16384)
        throw new IllegalArgumentException("Projection cell bounds");
      if (!tx.query(
              "SELECT state FROM projection_generations WHERE name=? AND generation=?",
              List.of(name, generation),
              1)
          .getFirst()
          .get("state")
          .equals("BUILDING")) throw failure("Cannot mutate published projection");
      tx.execute(
          "INSERT INTO projection_cells VALUES(?,?,?,?) ON CONFLICT(name,generation,key) DO UPDATE SET value=excluded.value",
          List.of(name, generation, key, value));
    }
  }

  private static String str(long value) {
    return Long.toString(value);
  }

  private static StorageException failure(String message) {
    return new StorageException(StorageException.Kind.CORRUPTION_IO, message);
  }
}
