package ca.emilybelnavis.mcmods.economy.institution;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import java.util.*;
import java.util.function.LongSupplier;

/** Server-owned short lived capability. No network DTO can construct a proof. */
public final class BranchAdmission {
  private BranchAdmission() {}

  public enum Action {
    PRIMARY,
    SECONDARY,
    ASSIGN,
    SWITCH_SOURCE,
    SWITCH_TARGET,
    CLOSE,
    CARD_ISSUE,
    CARD_REPLACE,
    CREDIT_ISSUE,
    CREDIT_INCREASE,
    CREDIT_REPLACE,
    LOAN_APPLY
  }

  public record Context(
      WorldId world,
      UUID session,
      PlayerId actor,
      Action action,
      InstitutionCatalog.InstitutionId bank,
      InstitutionCatalog.InstitutionId target,
      AccountId account,
      long accountRevision,
      long providerRevision,
      long denominationRevision) {
    public Context {
      Objects.requireNonNull(world);
      Objects.requireNonNull(session);
      Objects.requireNonNull(actor);
      Objects.requireNonNull(action);
      Objects.requireNonNull(bank);
      if (accountRevision < 0 || providerRevision < 0 || denominationRevision < 0)
        throw new IllegalArgumentException("Negative expected revision");
    }
  }

  @FunctionalInterface
  public interface LiveCheck {
    boolean available(Context context, JournalEngine.ReadView reads);
  }

  public static final class Proof {
    private final UUID id = UUID.randomUUID();
    private final Context context;
    private final long expires;
    private final Issuer issuer;

    private Proof(Context context, long expires, Issuer issuer) {
      this.context = context;
      this.expires = expires;
      this.issuer = issuer;
    }

    public UUID id() {
      return id;
    }

    public Context context() {
      return context;
    }

    public boolean valid(Journal.Intent intent, JournalEngine.ReadView reads) {
      return issuer.enabled
          && issuer.clock.getAsLong() < expires
          && context.actor().equals(intent.actor())
          && issuer.check.available(context, reads);
    }
  }

  /** Install only in a trusted server adapter; production adapters validate live bank kiosks. */
  public static final class Issuer {
    private final LongSupplier clock;
    private final LiveCheck check;
    private volatile boolean enabled = true;

    Issuer(LongSupplier clock, LiveCheck check) {
      this.clock = Objects.requireNonNull(clock);
      this.check = Objects.requireNonNull(check);
    }

    public Proof issue(Context context, long lifetimeMillis) {
      if (!enabled || lifetimeMillis < 1 || lifetimeMillis > 30_000)
        throw new IllegalArgumentException("Unavailable issuer or invalid lease lifetime");
      return new Proof(
          Objects.requireNonNull(context), Math.addExact(clock.getAsLong(), lifetimeMillis), this);
    }

    public void disable() {
      enabled = false;
    }
  }
}
