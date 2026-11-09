package ca.emilybelnavis.mcmods.economy.valuation;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import com.google.gson.Gson;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * Worker-confined immutable manifests. Staging is durable; only publication changes the active
 * pointer.
 */
public final class ValuationStore {
  private ValuationStore() {}

  public static final int MAX_MANIFEST_BYTES = 32 * 1024 * 1024;
  public static final long MAX_RETAINED_BYTES = 256L * 1024 * 1024;
  private static final int CHUNK_BYTES = 8192;
  private static final int BATCH_CHUNKS = 8;
  private static final int MAX_CHUNKS = MAX_MANIFEST_BYTES / CHUNK_BYTES;
  private static final Gson JSON = new Gson();
  private static final String CHUNK = "economy:a9/valuation-chunk";
  private static final String PUBLICATION = "economy:a9/valuation-publication";
  private static final Journal.Kind STAGE =
      new Journal.Kind("economy:a9", "economy:a9/valuation-stage");
  private static final Journal.Kind PUBLISH =
      new Journal.Kind("economy:a9", "economy:a9/valuation-publish");

  /** Reload invalidation and the final small publication transaction share this gate. */
  public static final class Gate {
    private long generation;

    public synchronized long invalidate() {
      return generation = Math.incrementExact(generation);
    }

    public synchronized long generation() {
      return generation;
    }

    public synchronized boolean matches(long expected) {
      return generation == expected;
    }
  }

  public record Snapshot(
      UUID id, long revision, long publishedMillis, String hash, String payload) {}

  public record Metadata(UUID id, long revision, long publishedMillis, String hash, int bytes) {}

  private record Chunk(WorldId world, String hash, int ordinal, int bytes, String data) {}

  private record Head(
      WorldId world,
      UUID id,
      long revision,
      long publishedMillis,
      String hash,
      int bytes,
      int chunks,
      UUID previous) {}

  public static List<String> statements() {
    var sql =
        new ArrayList<String>(
            List.of(
                "CREATE TABLE valuation_chunks(content_hash TEXT NOT NULL,ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND ordinal BETWEEN 0 AND 4095),world_id TEXT NOT NULL,operation_id TEXT NOT NULL,payload TEXT NOT NULL,raw_bytes INTEGER NOT NULL CHECK(typeof(raw_bytes)='integer' AND raw_bytes BETWEEN 1 AND 8192),PRIMARY KEY(content_hash,ordinal))",
                "CREATE TABLE valuation_manifests(id TEXT PRIMARY KEY,revision INTEGER NOT NULL UNIQUE CHECK(typeof(revision)='integer' AND revision>0),world_id TEXT NOT NULL,operation_id TEXT NOT NULL UNIQUE,content_hash TEXT NOT NULL,raw_bytes INTEGER NOT NULL CHECK(typeof(raw_bytes)='integer' AND raw_bytes BETWEEN 1 AND 33554432),payload TEXT NOT NULL)",
                "CREATE TABLE valuation_active(singleton INTEGER PRIMARY KEY CHECK(singleton=1),manifest_id TEXT NOT NULL REFERENCES valuation_manifests(id))",
                "CREATE INDEX valuation_fact_lookup ON journal_records(record_type,record_version,sequence) WHERE record_type IN ('economy:a9/valuation-chunk','economy:a9/valuation-publication')"));
    for (String table : List.of("valuation_chunks", "valuation_manifests")) {
      for (String action : List.of("UPDATE", "DELETE"))
        sql.add(
            "CREATE TRIGGER "
                + table
                + "_"
                + action.toLowerCase(Locale.ROOT)
                + " BEFORE "
                + action
                + " ON "
                + table
                + " BEGIN SELECT RAISE(ABORT,'immutable valuation data'); END");
      String identity =
          table.equals("valuation_chunks")
              ? "content_hash=NEW.content_hash AND ordinal=NEW.ordinal"
              : "id=NEW.id OR revision=NEW.revision OR operation_id=NEW.operation_id";
      sql.add(
          "CREATE TRIGGER "
              + table
              + "_replace BEFORE INSERT ON "
              + table
              + " WHEN EXISTS(SELECT 1 FROM "
              + table
              + " WHERE "
              + identity
              + ") BEGIN SELECT RAISE(ABORT,'existing valuation data'); END");
    }
    return List.copyOf(sql);
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog
        .with(new RecordCatalog.Key(CHUNK, 1), ValuationStore::decodeChunk)
        .with(new RecordCatalog.Key(PUBLICATION, 1), ValuationStore::decodeHead);
  }

  public static Snapshot active(FoundationStore store) {
    return store.transaction(
        tx -> {
          Head head = activeHead(tx::query);
          return head == null ? null : snapshot(tx::query, head);
        });
  }

  public static Snapshot load(FoundationStore store, UUID id) {
    Objects.requireNonNull(id);
    return store.transaction(
        tx -> {
          Head head = head(tx::query, id);
          return head == null ? null : snapshot(tx::query, head);
        });
  }

  /** Trusted detached settlement/recovery lookup; no current-FX substitution. */
  public static Snapshot load(JournalEngine.ReadView reads, UUID id) {
    Head value = head(reads, id);
    return value == null ? null : snapshot(reads, value);
  }

  public static List<Metadata> history(FoundationStore store, UUID exclusiveBefore, int limit) {
    if (limit < 1 || limit > 16) throw new IllegalArgumentException("Valuation history limit");
    return store.transaction(
        tx -> {
          long before = Long.MAX_VALUE;
          if (exclusiveBefore != null) {
            Head cursor = head(tx::query, exclusiveBefore);
            if (cursor == null)
              throw new IllegalArgumentException("Unknown valuation history cursor");
            before = cursor.revision();
          }
          var result = new ArrayList<Metadata>();
          for (var row :
              tx.query(
                  "SELECT payload FROM valuation_manifests WHERE revision<? ORDER BY revision DESC LIMIT ?",
                  List.of(Long.toString(before), Integer.toString(limit)),
                  limit)) {
            Head value = decodeHead(row.get("payload"));
            result.add(
                new Metadata(
                    value.id(),
                    value.revision(),
                    value.publishedMillis(),
                    value.hash(),
                    value.bytes()));
          }
          return List.copyOf(result);
        });
  }

  /**
   * No caller callbacks enter storage. A stale generation may leave harmless retained staged
   * chunks.
   */
  public static Snapshot publish(
      FoundationStore store,
      String canonicalPayload,
      long time,
      Gate gate,
      long expectedGeneration) {
    Objects.requireNonNull(store);
    Objects.requireNonNull(gate);
    if (time < 0) throw new IllegalArgumentException("Valuation publication time");
    byte[] bytes = canonicalPayload.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 1 || bytes.length > MAX_MANIFEST_BYTES)
      throw new IllegalArgumentException("Valuation manifest size");
    requireCanonical(canonicalPayload);
    String hash = ValuationData.hash(canonicalPayload);
    if (!gate.matches(expectedGeneration)) return null;
    Head current = store.transaction(tx -> activeHead(tx::query));
    if (current != null && current.hash().equals(hash)) {
      if (!gate.matches(expectedGeneration)) return null;
      return new Snapshot(
          current.id(),
          current.revision(),
          current.publishedMillis(),
          current.hash(),
          canonicalPayload);
    }
    WorldId world = store.snapshot().worldId();
    List<Chunk> chunks = chunks(world, hash, bytes);
    for (int start = 0; start < chunks.size(); start += BATCH_CHUNKS) {
      if (!gate.matches(expectedGeneration)) return null;
      stage(store, chunks.subList(start, Math.min(start + BATCH_CHUNKS, chunks.size())));
    }
    // Verify the complete immutable staged content before entering the short publication gate.
    String staged =
        store.transaction(tx -> content(tx::query, hash, bytes.length, chunks.size(), world));
    if (!staged.equals(canonicalPayload))
      throw new IllegalStateException("Staged valuation content mismatch");
    Snapshot accepted;
    synchronized (gate) {
      if (!gate.matches(expectedGeneration)) return null;
      Head prior = store.transaction(tx -> activeHead(tx::query));
      if (prior != null && prior.hash().equals(hash)) {
        accepted =
            new Snapshot(
                prior.id(),
                prior.revision(),
                prior.publishedMillis(),
                prior.hash(),
                canonicalPayload);
      } else {
        long revision = prior == null ? 1 : Math.incrementExact(prior.revision());
        UUID id = snapshotId(world, revision, hash);
        var value =
            new Head(
                world,
                id,
                revision,
                prior == null ? time : Math.max(time, prior.publishedMillis()),
                hash,
                bytes.length,
                chunks.size(),
                prior == null ? null : prior.id());
        UUID operation = publishOperation(world, revision, hash);
        var request =
            new Journal.CanonicalRequest(
                1, Map.of("hash", hash, "revision", Long.toString(revision)));
        var registration =
            new JournalEngine.Registration(
                1,
                (intent, reads) -> {
                  Head latest = activeHead(reads);
                  if (!Objects.equals(value.previous(), latest == null ? null : latest.id()))
                    return reject("VALUATION_PUBLICATION_CONFLICT");
                  long accounted = retainedBytes(reads);
                  // A staged, never-published candidate is already included once in retainedBytes.
                  boolean previouslyPublished =
                      !reads
                          .query(
                              "SELECT id FROM valuation_manifests WHERE content_hash=? LIMIT 1",
                              List.of(hash),
                              1)
                          .isEmpty();
                  if (previouslyPublished && accounted > MAX_RETAINED_BYTES - value.bytes())
                    return reject("VALUATION_RETENTION_LIMIT");
                  if (accounted > MAX_RETAINED_BYTES) return reject("VALUATION_RETENTION_LIMIT");
                  String payload = JSON.toJson(value);
                  var refreshed =
                      ca.emilybelnavis.mcmods.economy.trade.pricing.TradeSources.prepareRefresh(
                          reads,
                          id,
                          ValuationData.decode(canonicalPayload),
                          operation,
                          value.publishedMillis());
                  var records = new ArrayList<Journal.Record>();
                  records.add(new Journal.Record(PUBLICATION, 1, payload));
                  for (var part : refreshed) records.addAll(part.records());
                  return new JournalEngine.Commit(
                      records,
                      new Journal.Result(1, "VALUATION_PUBLISHED", id.toString()),
                      tx -> {
                        tx.execute(
                            "INSERT INTO valuation_manifests VALUES(?,?,?,?,?,?,?)",
                            List.of(
                                id.toString(),
                                Long.toString(revision),
                                world.value().toString(),
                                operation.toString(),
                                hash,
                                Integer.toString(value.bytes()),
                                payload));
                        if (latest == null)
                          tx.execute(
                              "INSERT INTO valuation_active VALUES(1,?)", List.of(id.toString()));
                        else
                          tx.execute(
                              "UPDATE valuation_active SET manifest_id=? WHERE singleton=1",
                              List.of(id.toString()));
                        for (var part : refreshed) part.projections().apply(tx);
                      });
                });
        submit(store, PUBLISH, operation, request, registration);
        accepted =
            new Snapshot(
                value.id(),
                value.revision(),
                value.publishedMillis(),
                value.hash(),
                canonicalPayload);
      }
    }
    // Candidate and durable chunks were checked before the gate; no large read or decode under it.
    return accepted;
  }

  private static void stage(FoundationStore store, List<Chunk> chunks) {
    Chunk first = chunks.getFirst();
    UUID operation = stageOperation(first.world(), first.hash(), first.ordinal());
    String batchHash = ValuationData.hash(JSON.toJson(chunks));
    var request =
        new Journal.CanonicalRequest(
            1,
            Map.of(
                "hash",
                first.hash(),
                "start",
                Integer.toString(first.ordinal()),
                "batch",
                batchHash));
    var registration =
        new JournalEngine.Registration(
            1,
            (intent, reads) -> {
              long added = 0;
              for (Chunk chunk : chunks) {
                if (!reads
                    .query(
                        "SELECT ordinal FROM valuation_chunks WHERE content_hash=? AND ordinal=?",
                        List.of(chunk.hash(), Integer.toString(chunk.ordinal())),
                        1)
                    .isEmpty()) return reject("VALUATION_STAGE_CONFLICT");
                added += chunk.bytes();
              }
              if (retainedBytes(reads) > MAX_RETAINED_BYTES - added)
                return reject("VALUATION_RETENTION_LIMIT");
              var records =
                  chunks.stream()
                      .map(chunk -> new Journal.Record(CHUNK, 1, JSON.toJson(chunk)))
                      .toList();
              return new JournalEngine.Commit(
                  records,
                  new Journal.Result(1, "VALUATION_STAGED", batchHash),
                  tx -> {
                    for (Chunk chunk : chunks)
                      tx.execute(
                          "INSERT INTO valuation_chunks VALUES(?,?,?,?,?,?)",
                          List.of(
                              chunk.hash(),
                              Integer.toString(chunk.ordinal()),
                              chunk.world().value().toString(),
                              operation.toString(),
                              JSON.toJson(chunk),
                              Integer.toString(chunk.bytes())));
                  });
            });
    submit(store, STAGE, operation, request, registration);
  }

  private static void submit(
      FoundationStore store,
      Journal.Kind kind,
      UUID operation,
      Journal.CanonicalRequest request,
      JournalEngine.Registration registration) {
    var actor = new SystemActorId(stable("valuation-actor|" + store.snapshot().worldId().value()));
    var engine =
        new JournalEngine(
            store, (a, k) -> a.equals(actor) && k.equals(kind), Map.of(kind, registration));
    var outcome =
        engine.submit(
            new Journal.Intent(
                new OperationId(operation),
                new RequestId(operation),
                actor,
                kind,
                request,
                null,
                null));
    if (outcome.status() != Journal.Status.COMMITTED)
      throw new IllegalArgumentException("Valuation publication: " + outcome.result().code());
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }

  private static Head activeHead(JournalEngine.ReadView reads) {
    var rows =
        reads.query(
            "SELECT m.payload FROM valuation_active a JOIN valuation_manifests m ON m.id=a.manifest_id WHERE a.singleton=1",
            List.of(),
            1);
    return rows.isEmpty() ? null : decodeHead(rows.getFirst().get("payload"));
  }

  private static Head head(JournalEngine.ReadView reads, UUID id) {
    var rows =
        reads.query(
            "SELECT payload FROM valuation_manifests WHERE id=?", List.of(id.toString()), 1);
    return rows.isEmpty() ? null : decodeHead(rows.getFirst().get("payload"));
  }

  private static Snapshot snapshot(JournalEngine.ReadView reads, Head head) {
    return new Snapshot(
        head.id(),
        head.revision(),
        head.publishedMillis(),
        head.hash(),
        content(reads, head.hash(), head.bytes(), head.chunks(), head.world()));
  }

  private static long retainedBytes(JournalEngine.ReadView reads) {
    String sql =
        "SELECT (SELECT COALESCE(SUM(raw_bytes),0) FROM valuation_manifests)+(SELECT COALESCE(SUM(c.raw_bytes),0) FROM valuation_chunks c WHERE NOT EXISTS(SELECT 1 FROM valuation_manifests m WHERE m.content_hash=c.content_hash)) AS bytes";
    return Long.parseLong(reads.query(sql, List.of(), 1).getFirst().get("bytes"));
  }

  private static List<Chunk> chunks(WorldId world, String hash, byte[] bytes) {
    var result = new ArrayList<Chunk>();
    for (int offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
      byte[] part = Arrays.copyOfRange(bytes, offset, Math.min(offset + CHUNK_BYTES, bytes.length));
      result.add(
          new Chunk(
              world, hash, result.size(), part.length, Base64.getEncoder().encodeToString(part)));
    }
    return List.copyOf(result);
  }

  private static String content(
      JournalEngine.ReadView reads, String hash, int bytes, int count, WorldId world) {
    var output = new ByteArrayOutputStream(bytes);
    int seen = 0;
    while (seen < count) {
      var rows =
          reads.query(
              "SELECT payload FROM valuation_chunks WHERE content_hash=? AND ordinal>=? ORDER BY ordinal LIMIT 32",
              List.of(hash, Integer.toString(seen)),
              32);
      if (rows.isEmpty()) throw new IllegalStateException("Missing valuation chunks");
      for (var row : rows) {
        Chunk chunk = decodeChunk(row.get("payload"));
        if (seen >= count
            || chunk.ordinal() != seen
            || !chunk.hash().equals(hash)
            || !chunk.world().equals(world)
            || (seen < count - 1 && chunk.bytes() != CHUNK_BYTES))
          throw new IllegalStateException("Valuation chunk linkage");
        output.writeBytes(Base64.getDecoder().decode(chunk.data()));
        seen++;
        if (output.size() > bytes) throw new IllegalStateException("Valuation chunk length");
      }
    }
    // Detect additional rows instead of treating a valid prefix as a complete manifest.
    if (!reads
            .query(
                "SELECT ordinal FROM valuation_chunks WHERE content_hash=? AND ordinal>=? LIMIT 1",
                List.of(hash, Integer.toString(count)),
                1)
            .isEmpty()
        || output.size() != bytes) throw new IllegalStateException("Valuation manifest length");
    String payload = utf8(output.toByteArray());
    if (!ValuationData.hash(payload).equals(hash))
      throw new IllegalStateException("Valuation manifest hash");
    requireCanonical(payload);
    return payload;
  }

  private static void requireCanonical(String payload) {
    if (!ValuationData.encode(ValuationData.decode(payload)).equals(payload))
      throw new IllegalArgumentException("Noncanonical valuation manifest");
  }

  private static Chunk decodeChunk(String payload) {
    if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > 16384)
      throw new IllegalArgumentException("Valuation chunk payload");
    Chunk chunk = JSON.fromJson(payload, Chunk.class);
    if (chunk == null
        || chunk.world() == null
        || chunk.ordinal() < 0
        || chunk.ordinal() >= MAX_CHUNKS
        || chunk.bytes() < 1
        || chunk.bytes() > CHUNK_BYTES)
      throw new IllegalArgumentException("Valuation chunk bounds");
    requireHash(chunk.hash());
    byte[] decoded = Base64.getDecoder().decode(chunk.data());
    if (decoded.length != chunk.bytes()
        || !Base64.getEncoder().encodeToString(decoded).equals(chunk.data())
        || !JSON.toJson(chunk).equals(payload))
      throw new IllegalArgumentException("Valuation chunk encoding");
    return chunk;
  }

  private static Head decodeHead(String payload) {
    if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > 16384)
      throw new IllegalArgumentException("Valuation head payload");
    Head head = JSON.fromJson(payload, Head.class);
    if (head == null
        || head.world() == null
        || head.id() == null
        || head.revision() < 1
        || head.publishedMillis() < 0
        || head.bytes() < 1
        || head.bytes() > MAX_MANIFEST_BYTES
        || head.chunks() < 1
        || head.chunks() > MAX_CHUNKS
        || head.chunks() != (head.bytes() + CHUNK_BYTES - 1) / CHUNK_BYTES
        || (head.revision() == 1) != (head.previous() == null))
      throw new IllegalArgumentException("Valuation head bounds");
    requireHash(head.hash());
    if (!head.id().equals(snapshotId(head.world(), head.revision(), head.hash()))
        || !JSON.toJson(head).equals(payload))
      throw new IllegalArgumentException("Valuation head encoding");
    return head;
  }

  private static void requireHash(String hash) {
    if (hash == null || !hash.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Valuation content hash");
  }

  private static String utf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException("Invalid valuation UTF-8", error);
    }
  }

  private static UUID stable(String text) {
    return UUID.nameUUIDFromBytes(("economy:a9:v1|" + text).getBytes(StandardCharsets.UTF_8));
  }

  private static UUID snapshotId(WorldId world, long revision, String hash) {
    return stable("snapshot|" + world.value() + "|" + revision + "|" + hash);
  }

  private static UUID publishOperation(WorldId world, long revision, String hash) {
    return stable("publish|" + world.value() + "|" + revision + "|" + hash);
  }

  private static UUID stageOperation(WorldId world, String hash, int firstOrdinal) {
    return stable("stage|" + world.value() + "|" + hash + "|" + firstOrdinal);
  }

  /**
   * Validate schema, every retained chunk/publication and exact immutable journal provenance on
   * reopen.
   */
  public static void validate(Connection connection) throws SQLException {
    try {
      for (String declaration : statements()) {
        String name = declaration.split(" ")[2].split("\\(", 2)[0];
        if (!normalize(declaration)
            .equals(
                normalize(scalar(connection, "SELECT sql FROM sqlite_schema WHERE name=?", name))))
          throw new SQLException("Changed valuation schema: " + name);
      }
      String world =
          scalar(connection, "SELECT world_id FROM foundation_metadata WHERE singleton=1");
      long chunkCount = 0;
      try (var query = connection.createStatement();
          var rows =
              query.executeQuery("SELECT * FROM valuation_chunks ORDER BY content_hash,ordinal")) {
        while (rows.next()) {
          Chunk chunk = decodeChunk(rows.getString("payload"));
          chunkCount++;
          if (!world.equals(chunk.world().value().toString())
              || !world.equals(rows.getString("world_id"))
              || !chunk.hash().equals(rows.getString("content_hash"))
              || chunk.ordinal() != rows.getInt("ordinal")
              || chunk.bytes() != rows.getInt("raw_bytes"))
            throw new SQLException("Valuation chunk projection");
          String operation =
              stageOperation(
                      chunk.world(), chunk.hash(), chunk.ordinal() / BATCH_CHUNKS * BATCH_CHUNKS)
                  .toString();
          if (!operation.equals(rows.getString("operation_id")))
            throw new SQLException("Valuation chunk operation");
          requireFact(connection, operation, CHUNK, rows.getString("payload"));
        }
      }
      long manifestCount = 0, retained = 0, previousTime = -1;
      UUID previous = null;
      try (var query = connection.createStatement();
          var rows = query.executeQuery("SELECT * FROM valuation_manifests ORDER BY revision")) {
        while (rows.next()) {
          Head head = decodeHead(rows.getString("payload"));
          manifestCount++;
          if (!world.equals(head.world().value().toString())
              || !world.equals(rows.getString("world_id"))
              || !head.id().toString().equals(rows.getString("id"))
              || head.revision() != rows.getLong("revision")
              || head.revision() != manifestCount
              || !Objects.equals(previous, head.previous())
              || head.publishedMillis() < previousTime
              || !head.hash().equals(rows.getString("content_hash"))
              || head.bytes() != rows.getInt("raw_bytes"))
            throw new SQLException("Valuation publication chain");
          String operation =
              publishOperation(head.world(), head.revision(), head.hash()).toString();
          if (!operation.equals(rows.getString("operation_id")))
            throw new SQLException("Valuation publication operation");
          requireFact(connection, operation, PUBLICATION, rows.getString("payload"));
          content(jdbcReads(connection), head.hash(), head.bytes(), head.chunks(), head.world());
          retained = Math.addExact(retained, head.bytes());
          previous = head.id();
          previousTime = head.publishedMillis();
        }
      }
      String active =
          scalar(connection, "SELECT manifest_id FROM valuation_active WHERE singleton=1");
      if (!Objects.equals(previous == null ? null : previous.toString(), active))
        throw new SQLException("Valuation active pointer");
      if (chunkCount
              != Long.parseLong(
                  scalar(
                      connection,
                      "SELECT count(*) FROM journal_records WHERE record_type=?",
                      CHUNK))
          || manifestCount
              != Long.parseLong(
                  scalar(
                      connection,
                      "SELECT count(*) FROM journal_records WHERE record_type=?",
                      PUBLICATION))) throw new SQLException("Orphan valuation facts");
      long staged =
          Long.parseLong(
              scalar(
                  connection,
                  "SELECT COALESCE(SUM(c.raw_bytes),0) FROM valuation_chunks c WHERE NOT EXISTS(SELECT 1 FROM valuation_manifests m WHERE m.content_hash=c.content_hash)"));
      if (Math.addExact(retained, staged) > MAX_RETAINED_BYTES)
        throw new SQLException("Valuation retention quota");
    } catch (RuntimeException error) {
      throw new SQLException("Invalid valuation evidence", error);
    }
  }

  private static JournalEngine.ReadView jdbcReads(Connection connection) {
    return (sql, parameters, maxRows) -> {
      try (var query = connection.prepareStatement(sql)) {
        for (int i = 0; i < parameters.size(); i++) query.setString(i + 1, parameters.get(i));
        try (var rows = query.executeQuery()) {
          var result = new ArrayList<Map<String, String>>();
          long bytes = 0;
          while (rows.next()) {
            if (result.size() >= maxRows || maxRows > 1024)
              throw new SQLException("Valuation query row bound");
            var row = new LinkedHashMap<String, String>();
            for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
              String value = rows.getString(column);
              if (value != null) bytes += value.getBytes(StandardCharsets.UTF_8).length;
              row.put(rows.getMetaData().getColumnLabel(column), value);
            }
            if (bytes > 1024 * 1024) throw new SQLException("Valuation query byte bound");
            result.add(Collections.unmodifiableMap(row));
          }
          return List.copyOf(result);
        }
      } catch (SQLException error) {
        throw new IllegalStateException("Valuation validation read", error);
      }
    };
  }

  private static String normalize(String text) {
    return text == null ? null : text.replaceAll("\\s+", " ").strip();
  }

  private static String scalar(Connection connection, String sql, String... parameters)
      throws SQLException {
    try (var query = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) query.setString(i + 1, parameters[i]);
      try (var rows = query.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  private static void requireFact(
      Connection connection, String operation, String type, String payload) throws SQLException {
    String sql =
        "SELECT count(*) FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE t.operation_id=? AND r.record_type=? AND r.record_version=1 AND r.payload=?";
    if (!"1".equals(scalar(connection, sql, operation, type, payload)))
      throw new SQLException("Missing immutable valuation fact");
  }
}
