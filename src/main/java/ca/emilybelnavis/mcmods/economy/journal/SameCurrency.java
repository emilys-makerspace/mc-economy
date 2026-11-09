package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Authority.*;
import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.*;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Same-currency instructions prepared inside the authoritative journal transaction. */
public final class SameCurrency {
  public static final Journal.Kind PAY = kind("pay");
  public static final Journal.Kind TRANSFER = kind("transfer");
  public static final Journal.Kind CREDIT = kind("credit");
  public static final Journal.Kind DEBIT = kind("debit");

  private SameCurrency() {}

  public record Revision(AccountId id, long revision) {
    public Revision {
      Objects.requireNonNull(id);
      if (revision < 1) throw new IllegalArgumentException("Account revision");
    }
  }

  public sealed interface Instruction permits Payment, Transfer, Adjustment {
    Journal.CanonicalRequest request();
  }

  public record Payment(
      PlayerId source, Revision debit, PlayerId recipient, Revision credit, Money amount)
      implements Instruction {
    public Journal.CanonicalRequest request() {
      return new Journal.CanonicalRequest(
          1,
          Map.of(
              "owner",
              source.value().toString(),
              "recipient",
              recipient.value().toString(),
              "debit",
              encode(debit),
              "credit",
              encode(credit),
              "amount",
              encode(amount)));
    }
  }

  public record Transfer(PlayerId owner, Revision debit, Revision credit, Money amount)
      implements Instruction {
    public Journal.CanonicalRequest request() {
      return new Journal.CanonicalRequest(
          1,
          Map.of(
              "owner",
              owner.value().toString(),
              "debit",
              encode(debit),
              "credit",
              encode(credit),
              "amount",
              encode(amount)));
    }
  }

  public record Adjustment(PlayerId owner, Revision account, Money amount) implements Instruction {
    public Journal.CanonicalRequest request() {
      return new Journal.CanonicalRequest(
          1,
          Map.of(
              "owner",
              owner.value().toString(),
              "account",
              encode(account),
              "amount",
              encode(amount)));
    }
  }

  @FunctionalInterface
  public interface Authority {
    /** Recheck the actor's operation-specific authority over the instruction on the worker. */
    boolean allows(Journal.Intent intent, Instruction instruction, JournalEngine.ReadView reads);
  }

  public static Map<Journal.Kind, JournalEngine.Registration> registrations(
      WorldId world, Authority authority) {
    Objects.requireNonNull(world);
    Objects.requireNonNull(authority);
    return Map.of(
        PAY,
        registration(world, authority),
        TRANSFER,
        registration(world, authority),
        CREDIT,
        registration(world, authority),
        DEBIT,
        registration(world, authority));
  }

  private static JournalEngine.Registration registration(WorldId world, Authority authority) {
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          Instruction instruction;
          try {
            instruction = decode(intent);
            if (!instruction.request().equals(intent.request()))
              return reject("INVALID_INSTRUCTION");
          } catch (IllegalArgumentException malformed) {
            return reject("INVALID_INSTRUCTION");
          }
          if (!authority.allows(intent, instruction, reads)) return reject("AUTHORITY_REVOKED");
          return switch (instruction) {
            case Payment pay -> payment(reads, world, pay);
            case Transfer transfer -> transfer(reads, world, transfer);
            case Adjustment adjustment ->
                adjust(reads, world, adjustment, intent.kind().equals(CREDIT));
          };
        });
  }

  private static JournalEngine.Decision payment(
      JournalEngine.ReadView reads, WorldId world, Payment pay) {
    var debit = AccountCatalog.primary(reads, party(world, pay.source())).orElse(null);
    var credit = AccountCatalog.primary(reads, party(world, pay.recipient())).orElse(null);
    if (debit == null || credit == null) return reject("MISSING_PRIMARY");
    return move(reads, debit, pay.debit(), credit, pay.credit(), pay.amount());
  }

  private static JournalEngine.Decision transfer(
      JournalEngine.ReadView reads, WorldId world, Transfer transfer) {
    var owner = party(world, transfer.owner());
    var debit = AccountCatalog.find(reads, owner, transfer.debit().id()).orElse(null);
    var credit = AccountCatalog.find(reads, owner, transfer.credit().id()).orElse(null);
    return move(reads, debit, transfer.debit(), credit, transfer.credit(), transfer.amount());
  }

  private static JournalEngine.Decision move(
      JournalEngine.ReadView reads,
      AccountIdentity debit,
      Revision expectedDebit,
      AccountIdentity credit,
      Revision expectedCredit,
      Money amount) {
    if (!matches(debit, expectedDebit) || !matches(credit, expectedCredit))
      return reject("STALE_ACCOUNT");
    if (debit.id().equals(credit.id())) return reject("SAME_ACCOUNT");
    if (!eligible(debit, amount) || !eligible(credit, amount)) return reject("POSTING_ACCOUNT");
    return Settlement.prepare(
        reads,
        List.of(
            leg(debit.id().value(), ACCOUNT, amount.negate()),
            leg(credit.id().value(), ACCOUNT, amount)),
        PostingBatch.Authority.TRANSFER,
        null);
  }

  private static JournalEngine.Decision adjust(
      JournalEngine.ReadView reads, WorldId world, Adjustment adjustment, boolean credit) {
    var account =
        AccountCatalog.find(reads, party(world, adjustment.owner()), adjustment.account().id())
            .orElse(null);
    if (!matches(account, adjustment.account())) return reject("STALE_ACCOUNT");
    var amount = adjustment.amount();
    if (!eligible(account, amount)) return reject("POSTING_ACCOUNT");
    var delta = credit ? amount : amount.negate();
    return Settlement.prepare(
        reads,
        List.of(
            leg(account.id().value(), ACCOUNT, delta),
            leg(Settlement.issuanceAccount(amount.currency()), ISSUANCE, delta.negate())),
        credit ? ISSUE : REDEEM,
        null);
  }

  private static boolean matches(AccountIdentity account, Revision expected) {
    return account != null
        && account.id().equals(expected.id())
        && account.revision() == expected.revision();
  }

  private static boolean eligible(AccountIdentity account, Money amount) {
    return account.status() == AccountIdentity.Status.OPEN
        && account.eligibleCurrency(amount.currency());
  }

  private static PostingBatch.Leg leg(UUID account, PostingBatch.Role role, Money amount) {
    return new PostingBatch.Leg(account, role, amount.currency(), amount);
  }

  private static Instruction decode(Journal.Intent intent) {
    var fields = intent.request().fields();
    var owner = new PlayerId(UUID.fromString(required(fields, "owner")));
    var amountFields = pair(required(fields, "amount"));
    var amount =
        new Money(EconomyCurrency.valueOf(amountFields[0]), Long.parseLong(amountFields[1]));
    if (amount.minorUnits() <= 0
        || amount.minorUnits()
                % (amount.currency() == EconomyCurrency.CHF
                    ? 1
                    : amount.currency().minorUnitsPerMajor())
            != 0) {
      throw new IllegalArgumentException(
          "Positive amount at the currency settlement quantum required");
    }
    if (intent.kind().equals(PAY)) {
      return new Payment(
          owner,
          revision(fields, "debit"),
          new PlayerId(UUID.fromString(required(fields, "recipient"))),
          revision(fields, "credit"),
          amount);
    }
    if (intent.kind().equals(TRANSFER)) {
      return new Transfer(owner, revision(fields, "debit"), revision(fields, "credit"), amount);
    }
    if (intent.kind().equals(CREDIT) || intent.kind().equals(DEBIT)) {
      return new Adjustment(owner, revision(fields, "account"), amount);
    }
    throw new IllegalArgumentException("Unknown same-currency operation");
  }

  private static Revision revision(Map<String, String> fields, String key) {
    var parts = pair(required(fields, key));
    return new Revision(new AccountId(UUID.fromString(parts[0])), Long.parseLong(parts[1]));
  }

  private static String required(Map<String, String> fields, String key) {
    var value = fields.get(key);
    if (value == null) throw new IllegalArgumentException("Missing instruction field");
    return value;
  }

  private static String[] pair(String field) {
    var parts = field.split("\\|", -1);
    if (parts.length != 2) throw new IllegalArgumentException("Instruction field format");
    return parts;
  }

  private static String encode(Revision revision) {
    return revision.id().value() + "|" + revision.revision();
  }

  private static String encode(Money amount) {
    return amount.currency().name() + "|" + amount.minorUnits();
  }

  private static PartyRef party(WorldId world, PlayerId player) {
    return new PartyRef(world, new PartyRef.Player(player));
  }

  private static Journal.Kind kind(String operation) {
    return new Journal.Kind("economy:ledger", "economy:" + operation);
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }
}
