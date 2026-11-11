package ca.emilybelnavis.mcmods.economy.banking;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Detached owner projection. Its bounded rows never carry service or SQL callbacks. */
public record BankingDashboardView(
    UUID world,
    long capturedAt,
    String institution,
    String currency,
    UUID recentAccount,
    Section<AccountRow> accounts,
    Section<DebtRow> credit,
    Section<DebtRow> loans,
    Section<Obligation> upcoming,
    Section<Recent> recent) {
  public static final int MAX_ROWS = 4;

  /** Conservative upper bound for the bounded wire representation, including UTF-8 expansion. */
  public static final int MAX_ENCODED_BYTES = 8_192;

  public enum Availability {
    AVAILABLE,
    UNAVAILABLE
  }

  public record Section<T>(
      Availability availability, String reasonKey, boolean hasMore, List<T> rows) {
    public Section {
      Objects.requireNonNull(availability);
      key(reasonKey, 128);
      rows = List.copyOf(rows);
      if (rows.size() > MAX_ROWS
          || availability == Availability.UNAVAILABLE && (!rows.isEmpty() || hasMore))
        throw new IllegalArgumentException("Dashboard section bounds");
    }

    public static <T> Section<T> available(List<T> rows, boolean more) {
      return new Section<>(
          Availability.AVAILABLE, "screen.economy.dashboard.available", more, rows);
    }

    public static <T> Section<T> unavailable(String reason) {
      return new Section<>(Availability.UNAVAILABLE, reason, false, List.of());
    }
  }

  public record AccountRow(UUID id, Money available, Money reserved, boolean primary) {
    public AccountRow {
      Objects.requireNonNull(id);
      positive(available);
      positive(reserved);
      if (available.currency() != reserved.currency())
        throw new IllegalArgumentException("Dashboard account currency");
    }
  }

  /** Captured posted debt, not an implicit cross-currency or complete-portfolio total. */
  public record DebtRow(UUID id, Money balance, boolean current) {
    public DebtRow {
      Objects.requireNonNull(id);
      positive(balance);
    }
  }

  public record Obligation(String id, UUID facility, String kind, long dueAt, Money amount) {
    public Obligation {
      text(id, 128);
      Objects.requireNonNull(facility);
      key(kind, 24);
      positive(amount);
      if (dueAt < 0 || amount.minorUnits() < 1)
        throw new IllegalArgumentException("Dashboard obligation");
    }
  }

  public record Recent(
      UUID transaction,
      UUID operation,
      long sequence,
      int position,
      Money amount,
      long denominationRevision,
      long recordedAt) {
    public Recent {
      Objects.requireNonNull(transaction);
      Objects.requireNonNull(operation);
      Objects.requireNonNull(amount);
      if (sequence < 1
          || position < 0
          || position > 63
          || denominationRevision < 0
          || recordedAt < 0) throw new IllegalArgumentException("Dashboard recent identity");
    }
  }

  public BankingDashboardView {
    Objects.requireNonNull(world);
    text(institution, 64);
    text(currency, 3);
    if (!currency.isEmpty()) EconomyCurrency.fromCode(currency);
    if (capturedAt < 0) throw new IllegalArgumentException("Dashboard capture time");
    Objects.requireNonNull(accounts);
    Objects.requireNonNull(credit);
    Objects.requireNonNull(loans);
    Objects.requireNonNull(upcoming);
    Objects.requireNonNull(recent);
    if (credit.rows().size() > 3
        || loans.rows().size() > 3
        || recentAccount == null && !recent.rows().isEmpty())
      throw new IllegalArgumentException("Dashboard sample bounds");
  }

  public void write(RegistryFriendlyByteBuf buffer) {
    buffer.writeUUID(world);
    buffer.writeLong(capturedAt);
    buffer.writeUtf(institution, 64);
    buffer.writeUtf(currency, 3);
    buffer.writeBoolean(recentAccount != null);
    if (recentAccount != null) buffer.writeUUID(recentAccount);
    writeSection(
        buffer,
        accounts,
        row -> {
          buffer.writeUUID(row.id());
          money(buffer, row.available());
          money(buffer, row.reserved());
          buffer.writeBoolean(row.primary());
        });
    writeSection(buffer, credit, row -> debt(buffer, row));
    writeSection(buffer, loans, row -> debt(buffer, row));
    writeSection(
        buffer,
        upcoming,
        row -> {
          buffer.writeUtf(row.id(), 128);
          buffer.writeUUID(row.facility());
          buffer.writeUtf(row.kind(), 24);
          buffer.writeLong(row.dueAt());
          money(buffer, row.amount());
        });
    writeSection(
        buffer,
        recent,
        row -> {
          buffer.writeUUID(row.transaction());
          buffer.writeUUID(row.operation());
          buffer.writeLong(row.sequence());
          buffer.writeVarInt(row.position());
          money(buffer, row.amount());
          buffer.writeLong(row.denominationRevision());
          buffer.writeLong(row.recordedAt());
        });
  }

  public static BankingDashboardView read(RegistryFriendlyByteBuf buffer) {
    UUID world = buffer.readUUID();
    long captured = buffer.readLong();
    String institution = buffer.readUtf(64), currency = buffer.readUtf(3);
    UUID account = buffer.readBoolean() ? buffer.readUUID() : null;
    var accounts =
        readSection(buffer, b -> new AccountRow(b.readUUID(), money(b), money(b), b.readBoolean()));
    var credit = readSection(buffer, BankingDashboardView::debt);
    var loans = readSection(buffer, BankingDashboardView::debt);
    var upcoming =
        readSection(
            buffer,
            b ->
                new Obligation(
                    b.readUtf(128), b.readUUID(), b.readUtf(24), b.readLong(), money(b)));
    var recent =
        readSection(
            buffer,
            b ->
                new Recent(
                    b.readUUID(),
                    b.readUUID(),
                    b.readLong(),
                    b.readVarInt(),
                    money(b),
                    b.readLong(),
                    b.readLong()));
    return new BankingDashboardView(
        world, captured, institution, currency, account, accounts, credit, loans, upcoming, recent);
  }

  private static <T> void writeSection(
      RegistryFriendlyByteBuf buffer, Section<T> section, java.util.function.Consumer<T> writer) {
    buffer.writeEnum(section.availability());
    buffer.writeUtf(section.reasonKey(), 128);
    buffer.writeBoolean(section.hasMore());
    buffer.writeVarInt(section.rows().size());
    section.rows().forEach(writer);
  }

  private static <T> Section<T> readSection(
      RegistryFriendlyByteBuf buffer, Function<RegistryFriendlyByteBuf, T> reader) {
    var availability = buffer.readEnum(Availability.class);
    String reason = buffer.readUtf(128);
    boolean more = buffer.readBoolean();
    int size = buffer.readVarInt();
    if (size < 0 || size > MAX_ROWS) throw new IllegalArgumentException("Dashboard row count");
    var rows = new ArrayList<T>(size);
    for (int i = 0; i < size; i++) rows.add(reader.apply(buffer));
    return new Section<>(availability, reason, more, rows);
  }

  private static void debt(RegistryFriendlyByteBuf buffer, DebtRow row) {
    buffer.writeUUID(row.id());
    money(buffer, row.balance());
    buffer.writeBoolean(row.current());
  }

  private static DebtRow debt(RegistryFriendlyByteBuf buffer) {
    return new DebtRow(buffer.readUUID(), money(buffer), buffer.readBoolean());
  }

  private static void money(RegistryFriendlyByteBuf buffer, Money value) {
    buffer.writeUtf(value.currency().code(), 3);
    buffer.writeLong(value.minorUnits());
  }

  private static Money money(RegistryFriendlyByteBuf buffer) {
    return new Money(EconomyCurrency.fromCode(buffer.readUtf(3)), buffer.readLong());
  }

  private static void positive(Money value) {
    Objects.requireNonNull(value);
    if (value.minorUnits() < 0) throw new IllegalArgumentException("Dashboard negative amount");
  }

  private static void text(String value, int maximum) {
    if (value == null || value.length() > maximum)
      throw new IllegalArgumentException("Dashboard text bounds");
  }

  private static void key(String value, int maximum) {
    text(value, maximum);
    if (!value.matches("[a-z0-9_.:]+")) throw new IllegalArgumentException("Dashboard key");
  }
}
