package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.LedgerProjection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Private NPC authority composer. Generic posting admission never accepts its bridge role. */
public final class NpcSettlement {
  private NpcSettlement() {}

  public static final String BRIDGE = "economy:a8/npc-bridge",
      GROUPS = "economy:a8/npc-posting-groups";

  public record Group(PostingBatch.Authority authority, List<PostingBatch.Leg> legs) {
    public Group {
      Objects.requireNonNull(authority);
      legs = List.copyOf(legs);
    }
  }

  public record Manifest(WorldId world, List<Group> groups) {
    public Manifest {
      Objects.requireNonNull(world);
      groups = List.copyOf(groups);
      shape(world, groups);
    }
  }

  private record Key(UUID id, EconomyCurrency currency) {}

  public static UUID bridge(WorldId world, EconomyCurrency currency) {
    return UUID.nameUUIDFromBytes(
        ("economy:a8:npc-bridge:v1|" + world.value() + "|" + currency)
            .getBytes(StandardCharsets.UTF_8));
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog
        .with(new RecordCatalog.Key(BRIDGE, 1), NpcSettlement::decodeBridge)
        .with(new RecordCatalog.Key(GROUPS, 1), NpcSettlement::decode);
  }

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE npc_bridges(currency TEXT PRIMARY KEY,world_id TEXT NOT NULL,account_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL)",
        "CREATE INDEX npc_posting_group_facts ON journal_records(record_type,sequence,position) WHERE record_type IN ('economy:a8/npc-bridge','economy:a8/npc-posting-groups')",
        "CREATE TRIGGER npc_bridges_update BEFORE UPDATE ON npc_bridges BEGIN SELECT RAISE(ABORT,'immutable NPC bridge'); END",
        "CREATE TRIGGER npc_bridges_delete BEFORE DELETE ON npc_bridges BEGIN SELECT RAISE(ABORT,'immutable NPC bridge'); END",
        "CREATE TRIGGER npc_bridges_replace BEFORE INSERT ON npc_bridges WHEN EXISTS(SELECT 1 FROM npc_bridges WHERE currency=NEW.currency OR account_id=NEW.account_id) BEGIN SELECT RAISE(ABORT,'existing NPC bridge'); END");
  }

  /** Validate every balanced group on one overlay, then write each final ledger balance once. */
  public static JournalEngine.Decision prepare(
      JournalEngine.ReadView reads,
      WorldId world,
      List<Group> groups,
      List<FinancialAccounts.Account> planned) {
    try {
      if (!world
          .value()
          .toString()
          .equals(
              reads
                  .query("SELECT world_id FROM foundation_metadata WHERE singleton=1", List.of(), 1)
                  .getFirst()
                  .get("world_id"))) return reject("NPC_WORLD");
      var manifest = new Manifest(world, groups);
      var currencies = shape(world, groups);
      var registered = new HashMap<UUID, FinancialAccounts.Account>();
      for (var account : planned) {
        if (!account.world().equals(world)) return reject("NPC_FINANCIAL_WORLD");
        var previous = registered.put(account.id(), account);
        if (previous != null && !previous.equals(account)) return reject("NPC_FINANCIAL_IDENTITY");
        var existing = FinancialAccounts.find(reads, account.id());
        if (existing.isPresent() && !existing.get().equals(account))
          return reject("NPC_FINANCIAL_IDENTITY");
      }
      var balances = new LinkedHashMap<Key, Long>();
      var roles = new HashMap<Key, PostingBatch.Role>();
      var records = new ArrayList<Journal.Record>();
      for (var currency : currencies) {
        var key = new Key(bridge(world, currency), currency);
        if (Settlement.posted(reads, key.id(), currency) != 0)
          return reject("NPC_BRIDGE_NOT_EMPTY");
        var row =
            reads.query(
                "SELECT payload FROM npc_bridges WHERE currency=?", List.of(currency.name()), 1);
        String payload = bridgePayload(world, currency);
        if (row.isEmpty()) records.add(new Journal.Record(BRIDGE, 1, payload));
        else if (!row.getFirst().get("payload").equals(payload))
          return reject("NPC_BRIDGE_IDENTITY");
      }
      records.add(new Journal.Record(GROUPS, 1, encode(manifest)));
      for (var group : groups) {
        var changed = new LinkedHashSet<Key>();
        for (var leg : group.legs()) {
          var key = new Key(leg.account(), leg.accountCurrency());
          if (roles.putIfAbsent(key, leg.role()) != null && roles.get(key) != leg.role())
            return reject("NPC_POSTING_ROLE_ALIAS");
          if (!validAccount(reads, world, leg, registered)) return reject("NPC_POSTING_ACCOUNT");
          long previous =
              balances.computeIfAbsent(
                  key,
                  k -> {
                    return Settlement.posted(reads, k.id(), k.currency());
                  });
          balances.put(key, Math.addExact(previous, leg.amount().minorUnits()));
          changed.add(key);
          records.add(new Journal.Record("economy:posting", 4, posting(leg, group.authority())));
        }
        for (var key : changed) {
          long value = balances.get(key);
          var role = roles.get(key);
          if (role == PostingBatch.Role.NPC_BRIDGE && value < 0)
            return reject("NPC_BRIDGE_INTERMEDIATE");
          if (role == PostingBatch.Role.ACCOUNT
              && value < Holds.reserved(reads, key.id(), key.currency()))
            return reject("INSUFFICIENT_FUNDS");
          var financial = registered.get(key.id());
          if (financial != null && !normal(financial.role(), value))
            return reject("NPC_INSTITUTION_BALANCE");
        }
      }
      for (var currency : currencies)
        if (balances.getOrDefault(new Key(bridge(world, currency), currency), 0L) != 0)
          return reject("NPC_BRIDGE_TERMINAL");
      return new JournalEngine.Commit(
          records,
          new Journal.Result(1, "NPC_POSTED", ""),
          tx -> {
            for (var currency : currencies)
              if (reads
                  .query(
                      "SELECT currency FROM npc_bridges WHERE currency=?",
                      List.of(currency.name()),
                      1)
                  .isEmpty())
                tx.execute(
                    "INSERT INTO npc_bridges VALUES(?,?,?,?)",
                    List.of(
                        currency.name(),
                        world.value().toString(),
                        bridge(world, currency).toString(),
                        bridgePayload(world, currency)));
            balances.forEach(
                (key, value) -> LedgerProjection.write(tx, key.id(), key.currency(), value));
          });
    } catch (ArithmeticException overflow) {
      return reject("NPC_POSTING_OVERFLOW");
    } catch (IllegalArgumentException invalid) {
      return reject("NPC_POSTING_INVALID");
    }
  }

  private static boolean validAccount(
      JournalEngine.ReadView reads,
      WorldId world,
      PostingBatch.Leg leg,
      Map<UUID, FinancialAccounts.Account> financial) {
    var role = leg.role();
    var currency = leg.accountCurrency();
    var id = leg.account();
    if (id.equals(bridge(world, currency))) return role == PostingBatch.Role.NPC_BRIDGE;
    return switch (role) {
      case NPC_BRIDGE -> false;
      case ISSUANCE -> id.equals(Settlement.issuanceAccount(currency));
      case CLEARING -> id.equals(Settlement.clearingAccount(currency));
      case ACCOUNT -> Settlement.openBank(reads, id, currency);
      case FEE -> false;
      case INSTITUTION_CASH, INSTITUTION_CLAIM -> {
        var account = financial.get(id);
        if (account == null) account = FinancialAccounts.find(reads, id).orElse(null);
        if (account == null
            || !account.world().equals(world)
            || account.currency() != currency
            || account.role().spendable() != (role == PostingBatch.Role.INSTITUTION_CASH))
          yield false;
        financial.put(id, account);
        yield true;
      }
    };
  }

  private static boolean normal(FinancialAccounts.Role role, long value) {
    return switch (role) {
      case CASH, RESERVATION, RECEIVABLE, EXPENSE -> value >= 0;
      case LIABILITY, PAYABLE, INCOME, EQUITY, ALLOWANCE -> value <= 0;
      case ROUNDING -> true;
    };
  }

  private static EnumSet<EconomyCurrency> shape(WorldId world, List<Group> groups) {
    if (groups.isEmpty() || groups.size() > 16)
      throw new IllegalArgumentException("NPC group bound");
    var bridgeCurrencies = EnumSet.noneOf(EconomyCurrency.class);
    int count = 0;
    boolean monetary = false;
    for (var group : groups) {
      if (group.legs().size() < 2 || group.legs().size() > 16)
        throw new IllegalArgumentException("NPC group legs");
      count = Math.addExact(count, group.legs().size());
      var sums = new EnumMap<EconomyCurrency, Long>(EconomyCurrency.class);
      int issuance = 0, clearing = 0, bridges = 0;
      for (var leg : group.legs()) {
        Objects.requireNonNull(leg);
        Objects.requireNonNull(leg.account());
        Objects.requireNonNull(leg.role());
        Objects.requireNonNull(leg.amount());
        if (leg.accountCurrency() != leg.amount().currency() || leg.amount().minorUnits() == 0)
          throw new IllegalArgumentException("NPC leg");
        sums.merge(leg.accountCurrency(), leg.amount().minorUnits(), Math::addExact);
        if (leg.role() == PostingBatch.Role.NPC_BRIDGE) {
          if (!bridge(world, leg.accountCurrency()).equals(leg.account()))
            throw new IllegalArgumentException("NPC bridge identity");
          bridgeCurrencies.add(leg.accountCurrency());
          bridges++;
        }
        if (leg.role() == PostingBatch.Role.ISSUANCE) {
          if (!leg.account().equals(Settlement.issuanceAccount(leg.accountCurrency())))
            throw new IllegalArgumentException("NPC issuance identity");
          issuance++;
        }
        if (leg.role() == PostingBatch.Role.CLEARING) {
          if (!leg.account().equals(Settlement.clearingAccount(leg.accountCurrency())))
            throw new IllegalArgumentException("NPC clearing identity");
          clearing++;
        }
        if (leg.role() == PostingBatch.Role.FEE)
          throw new IllegalArgumentException("NPC fee supplier must be explicit");
      }
      if (sums.values().stream().anyMatch(v -> v != 0))
        throw new IllegalArgumentException("NPC group imbalance");
      switch (group.authority()) {
        case ISSUE, REDEEM -> {
          monetary = true;
          if (issuance != 1 || bridges != 1 || group.legs().size() != 2)
            throw new IllegalArgumentException("Narrow NPC monetary group");
          for (var leg : group.legs())
            if (group.authority() == PostingBatch.Authority.ISSUE
                ? (leg.amount().minorUnits() < 0) != (leg.role() == PostingBatch.Role.ISSUANCE)
                : (leg.amount().minorUnits() > 0) != (leg.role() == PostingBatch.Role.ISSUANCE))
              throw new IllegalArgumentException("NPC monetary sign");
        }
        case FX -> {
          if (issuance != 0
              || clearing != 2
              || bridges < 1
              || group.legs().size() != 4
              || sums.size() != 2) throw new IllegalArgumentException("NPC FX group");
          var clearingCurrencies = EnumSet.noneOf(EconomyCurrency.class);
          int positiveClearing = 0;
          for (var leg : group.legs())
            if (leg.role() == PostingBatch.Role.CLEARING) {
              if (!clearingCurrencies.add(leg.accountCurrency()))
                throw new IllegalArgumentException("NPC FX clearing currency");
              if (leg.amount().minorUnits() > 0) positiveClearing++;
            } else if (leg.role() != PostingBatch.Role.ACCOUNT
                && leg.role() != PostingBatch.Role.NPC_BRIDGE)
              throw new IllegalArgumentException("NPC FX account role");
          if (positiveClearing != 1 || !clearingCurrencies.equals(sums.keySet()))
            throw new IllegalArgumentException("NPC FX principal signs");
        }
        case TRANSFER -> {
          if (issuance != 0 || clearing != 0 || sums.size() != 1)
            throw new IllegalArgumentException("NPC transfer group");
        }
        default -> throw new IllegalArgumentException("NPC authority");
      }
    }
    if (!monetary || bridgeCurrencies.isEmpty() || count > 48)
      throw new IllegalArgumentException("NPC composition bound");
    return bridgeCurrencies;
  }

  private static String posting(PostingBatch.Leg l, PostingBatch.Authority a) {
    return l.account()
        + "|"
        + l.role()
        + "|"
        + l.accountCurrency()
        + "|"
        + l.amount().minorUnits()
        + "|"
        + a;
  }

  private static String bridgePayload(WorldId world, EconomyCurrency c) {
    return world.value() + "|" + c + "|" + bridge(world, c);
  }

  private static void decodeBridge(String text) {
    String[] p = text.split("\\|", -1);
    if (p.length != 3
        || !bridgePayload(new WorldId(UUID.fromString(p[0])), EconomyCurrency.valueOf(p[1]))
            .equals(text)) throw new IllegalArgumentException("NPC bridge fact");
  }

  public static String encode(Manifest value) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(bytes)) {
        out.writeInt(1);
        out.writeUTF(value.world().value().toString());
        out.writeInt(value.groups().size());
        for (var g : value.groups()) {
          out.writeUTF(g.authority().name());
          out.writeInt(g.legs().size());
          for (var l : g.legs()) {
            out.writeUTF(l.account().toString());
            out.writeUTF(l.role().name());
            out.writeUTF(l.accountCurrency().name());
            out.writeLong(l.amount().minorUnits());
          }
        }
      }
      String text = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
      if (text.length() > 16384) throw new IllegalArgumentException("NPC manifest size");
      return text;
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  public static Manifest decode(String text) {
    if (text == null || text.length() > 16384)
      throw new IllegalArgumentException("NPC manifest size");
    try (var in =
        new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(text)))) {
      if (in.readInt() != 1) throw new IllegalArgumentException("NPC manifest version");
      var world = new WorldId(UUID.fromString(in.readUTF()));
      int n = in.readInt();
      if (n < 1 || n > 16) throw new IllegalArgumentException("NPC group count");
      var groups = new ArrayList<Group>();
      for (int i = 0; i < n; i++) {
        var authority = PostingBatch.Authority.valueOf(in.readUTF());
        int m = in.readInt();
        if (m < 2 || m > 16) throw new IllegalArgumentException("NPC group count");
        var legs = new ArrayList<PostingBatch.Leg>();
        for (int j = 0; j < m; j++) {
          var id = UUID.fromString(in.readUTF());
          var role = PostingBatch.Role.valueOf(in.readUTF());
          var c = EconomyCurrency.valueOf(in.readUTF());
          legs.add(new PostingBatch.Leg(id, role, c, new Money(c, in.readLong())));
        }
        groups.add(new Group(authority, legs));
      }
      var result = new Manifest(world, groups);
      if (in.available() != 0 || !encode(result).equals(text))
        throw new IllegalArgumentException("Noncanonical NPC manifest");
      return result;
    } catch (IOException malformed) {
      throw new IllegalArgumentException("Malformed NPC manifest", malformed);
    }
  }

  /** Validate explicit v4 group boundaries and zero bridge float without rerunning admission. */
  public static void validate(Connection c) throws SQLException {
    try {
      for (var expected : statements()) {
        String name = expected.split(" ")[2].split("\\(")[0];
        if (!normalize(expected)
            .equals(normalize(scalar(c, "SELECT sql FROM sqlite_schema WHERE name=?", name))))
          throw new SQLException("NPC posting schema declaration");
      }
      String world = scalar(c, "SELECT world_id FROM foundation_metadata WHERE singleton=1");
      long bridgeCount = 0;
      try (var s = c.createStatement();
          var rows = s.executeQuery("SELECT * FROM npc_bridges")) {
        while (rows.next()) {
          bridgeCount++;
          var currency = EconomyCurrency.valueOf(rows.getString("currency"));
          String payload = bridgePayload(new WorldId(UUID.fromString(world)), currency);
          if (!payload.equals(rows.getString("payload"))
              || !world.equals(rows.getString("world_id"))
              || !bridge(new WorldId(UUID.fromString(world)), currency)
                  .toString()
                  .equals(rows.getString("account_id")))
            throw new SQLException("NPC bridge projection");
          if (!"1"
              .equals(
                  scalar(
                      c,
                      "SELECT count(*) FROM journal_records WHERE record_type=? AND record_version=1 AND payload=?",
                      BRIDGE,
                      payload))) throw new SQLException("NPC bridge source");
          if (!"0"
              .equals(
                  scalar(
                      c,
                      "SELECT COALESCE(sum(minor_units),0) FROM ledger_balances WHERE account_id=?",
                      rows.getString("account_id"))))
            throw new SQLException("NPC bridge terminal float");
        }
      }
      if (bridgeCount
          != Long.parseLong(
              scalar(c, "SELECT count(*) FROM journal_records WHERE record_type=?", BRIDGE)))
        throw new SQLException("Orphan NPC bridge facts");
      long total = 0;
      try (var s =
          c.prepareStatement(
              "SELECT r.sequence,r.payload,t.world_id FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE r.record_type=? ORDER BY r.sequence")) {
        s.setString(1, GROUPS);
        try (var rows = s.executeQuery()) {
          while (rows.next()) {
            var manifest = decode(rows.getString("payload"));
            if (!manifest.world().value().toString().equals(world)
                || !world.equals(rows.getString("world_id")))
              throw new SQLException("NPC group world");
            if (!"1"
                .equals(
                    scalar(
                        c,
                        "SELECT count(*) FROM journal_records WHERE sequence=? AND record_type IN ('economy:a8/npc-trade','economy:a8/npc-cash-trade','economy:a9/reference-npc-trade','economy:a9/reference-cash-trade') AND record_version=1",
                        Long.toString(rows.getLong("sequence")))))
              throw new SQLException("NPC groups require one owning source receipt");
            var expected = new ArrayList<String>();
            var balances = new HashMap<Key, Long>();
            for (var g : manifest.groups()) {
              for (var l : g.legs()) {
                expected.add(posting(l, g.authority()));
                if (l.role() == PostingBatch.Role.NPC_BRIDGE) {
                  var key = new Key(l.account(), l.accountCurrency());
                  balances.merge(key, l.amount().minorUnits(), Math::addExact);
                  if (!bridgePayload(manifest.world(), l.accountCurrency())
                      .equals(
                          scalar(
                              c,
                              "SELECT payload FROM npc_bridges WHERE currency=?",
                              l.accountCurrency().name())))
                    throw new SQLException("Unregistered NPC bridge");
                }
              }
              if (balances.values().stream().anyMatch(v -> v < 0))
                throw new SQLException("NPC historical intermediate float");
            }
            if (balances.values().stream().anyMatch(v -> v != 0))
              throw new SQLException("NPC historical terminal float");
            var actual = new ArrayList<String>();
            try (var q =
                c.prepareStatement(
                    "SELECT payload FROM journal_records WHERE sequence=? AND record_type='economy:posting' AND record_version=4 ORDER BY position")) {
              q.setLong(1, rows.getLong("sequence"));
              try (var r = q.executeQuery()) {
                while (r.next()) {
                  if (actual.size() >= 48) throw new SQLException("NPC posting count");
                  actual.add(r.getString(1));
                }
              }
            }
            if (!expected.equals(actual))
              throw new SQLException("NPC posting group correspondence");
            total = Math.addExact(total, actual.size());
          }
        }
      }
      if (total
          != Long.parseLong(
              scalar(
                  c,
                  "SELECT count(*) FROM journal_records WHERE record_type='economy:posting' AND record_version=4")))
        throw new SQLException("Orphan v4 postings");
    } catch (RuntimeException malformed) {
      throw new SQLException("Invalid NPC posting evidence", malformed);
    }
  }

  private static String scalar(Connection c, String sql, String... params) throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) s.setString(i + 1, params[i]);
      try (var r = s.executeQuery()) {
        return r.next() ? r.getString(1) : null;
      }
    }
  }

  private static String normalize(String text) {
    return text == null ? "" : text.replaceAll("\\s+", " ").strip();
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }
}
