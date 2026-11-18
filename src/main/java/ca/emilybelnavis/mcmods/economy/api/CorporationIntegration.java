package ca.emilybelnavis.mcmods.economy.api;

import ca.emilybelnavis.mcmods.economy.foundation.CorporationProviders;
import java.util.*;
import java.util.concurrent.CompletionStage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModContainer;

/**
 * V1 of the world-local corporation provider API. Call on the owning server thread. Keep operation
 * IDs and reviewed requests across retries. Futures publish detached data on the server thread.
 * Founder requests require separate confirmation by the player.
 */
public interface CorporationIntegration {
  int VERSION = 1;

  enum Capability {
    CREATE,
    FIND,
    ACCOUNTS,
    PAYMENTS
  }

  enum Status {
    OK,
    APPROVAL_REQUIRED,
    DENIED,
    UNAVAILABLE,
    STALE,
    CONFLICT,
    REJECTED,
    UNKNOWN
  }

  record Result<T>(Status status, String code, Optional<T> value) {
    public Result {
      Objects.requireNonNull(status);
      Objects.requireNonNull(code);
      Objects.requireNonNull(value);
    }
  }

  record Company(UUID id, String name, long revision, String state) {}

  record Permission(
      UUID company,
      long companyRevision,
      long revision,
      Set<Capability> capabilities,
      Set<UUID> accounts) {
    public Permission {
      capabilities = Set.copyOf(capabilities);
      accounts = Set.copyOf(accounts);
    }
  }

  enum OwnerKind {
    PLAYER,
    CORPORATION
  }

  record Endpoint(
      OwnerKind kind,
      UUID owner,
      UUID account,
      long accountRevision,
      String bank,
      long providerRevision,
      long companyRevision) {
    public Endpoint {
      Objects.requireNonNull(kind);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(account);
      Objects.requireNonNull(bank);
    }
  }

  record Account(Endpoint endpoint, AccountFunds funds) {}

  record Route(String externalKey, long mandateRevision, Endpoint source, Endpoint target) {
    public Route {
      Objects.requireNonNull(externalKey);
      Objects.requireNonNull(source);
      Objects.requireNonNull(target);
    }
  }

  record Quote(
      Route route,
      long principalMinor,
      long feeMinor,
      long creditMinor,
      Optional<UUID> publication) {
    public Quote {
      Objects.requireNonNull(route);
      Objects.requireNonNull(publication);
    }
  }

  record Receipt(UUID operation, String outcome) {}

  static CorporationIntegration register(
      MinecraftServer server, ModContainer mod, String providerKey, Set<Capability> declarations) {
    return CorporationProviders.register(server, mod, providerKey, declarations);
  }

  default int version() {
    return VERSION;
  }

  Set<Capability> capabilities();

  CompletionStage<Result<Company>> find(String externalKey);

  CompletionStage<Result<Permission>> permissions(String externalKey);

  CompletionStage<Result<List<Account>>> accounts(
      String externalKey, long companyRevision, long mandateRevision);

  CompletionStage<Result<Quote>> quote(Route route, long principalMinor);

  CompletionStage<Result<Receipt>> pay(UUID operation, Quote reviewed);

  /** Returns status only, including for an original effect whose mandate was later revoked. */
  CompletionStage<Result<Receipt>> lookup(UUID operation);

  CompletionStage<Result<Receipt>> requestCreate(
      ServerPlayer founder, UUID operation, String externalKey, String name, UUID fundingAccount);

  CompletionStage<Result<Receipt>> requestBinding(
      ServerPlayer founder, UUID operation, String externalKey, UUID company, long companyRevision);

  CompletionStage<Result<Receipt>> requestPermissions(
      ServerPlayer founder, UUID operation, String externalKey, Permission requested);

  /** Stops admission immediately. Completion fences previously admitted worker transactions. */
  CompletionStage<Void> disable();
}
