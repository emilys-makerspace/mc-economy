package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/** Trusted server query entry points. Call from the server thread. */
public final class AccountQueries {
  // Reserve one of the catalog's rows to determine whether a next page exists.
  public static final int MAX_PAGE_SIZE = AccountCatalog.MAX_PAGE_SIZE - 1;

  private AccountQueries() {}

  public record Page(List<AccountIdentity> accounts, String nextCursor) {
    public Page {
      accounts = List.copyOf(accounts);
    }
  }

  public static CompletionStage<Optional<AccountIdentity>> inspectAccount(
      CommandSourceStack source,
      Function<MinecraftServer, ServerServices> sessions,
      PartyRef target,
      AccountId id) {
    return inspect(
        source,
        sessions,
        target,
        (services, grant) ->
            services.inspectAccount(
                grant, id, () -> stillAuthorized(source, sessions, target, services)));
  }

  public static CompletionStage<Optional<Page>> inspectAccounts(
      CommandSourceStack source,
      Function<MinecraftServer, ServerServices> sessions,
      PartyRef target,
      String cursor,
      int limit) {
    return inspect(
        source,
        sessions,
        target,
        (services, grant) ->
            services.inspectAccounts(
                grant, cursor, limit, () -> stillAuthorized(source, sessions, target, services)));
  }

  private static <T> CompletionStage<Optional<T>> inspect(
      CommandSourceStack source,
      Function<MinecraftServer, ServerServices> sessions,
      PartyRef target,
      BiFunction<ServerServices, PartyAuthorization.Grant, CompletionStage<Optional<T>>> query) {
    var grant = PartyAuthorization.inspection(source, sessions, target);
    if (grant.isEmpty()) return CompletableFuture.completedStage(Optional.empty());
    var services = sessions.apply(source.getServer());
    return services == null
        ? CompletableFuture.completedStage(Optional.empty())
        : query.apply(services, grant.get());
  }

  private static boolean stillAuthorized(
      CommandSourceStack source,
      Function<MinecraftServer, ServerServices> sessions,
      PartyRef target,
      ServerServices services) {
    return PartyAuthorization.inspection(source, sessions, target).isPresent()
        && sessions.apply(source.getServer()) == services;
  }

  record Cursor(WorldId world, PlayerId owner, AccountId after) {
    static Cursor decode(String token) {
      if (token == null) return null;
      if (token.length() != 113 || !token.startsWith("v1:"))
        throw new IllegalArgumentException("Malformed account cursor");
      var fields = token.split(":", -1);
      if (fields.length != 4) throw new IllegalArgumentException("Malformed account cursor");
      var cursor =
          new Cursor(
              new WorldId(UUID.fromString(fields[1])),
              new PlayerId(UUID.fromString(fields[2])),
              new AccountId(UUID.fromString(fields[3])));
      if (!cursor.encode().equals(token))
        throw new IllegalArgumentException("Noncanonical account cursor");
      return cursor;
    }

    boolean matches(PartyRef target) {
      return world.equals(target.world())
          && target.owner() instanceof PartyRef.Player player
          && owner.equals(player.id());
    }

    String encode() {
      return "v1:" + world.value() + ":" + owner.value() + ":" + after.value();
    }
  }

  static Cursor cursor(String token, int limit) {
    if (limit < 1 || limit > MAX_PAGE_SIZE) throw new IllegalArgumentException("Account page size");
    return Cursor.decode(token);
  }

  static Optional<Page> page(
      JournalEngine.ReadView reads, PartyRef owner, Cursor cursor, int limit) {
    if (cursor != null && !cursor.matches(owner)) return Optional.empty();
    var rows =
        AccountCatalog.history(reads, owner, cursor == null ? null : cursor.after(), limit + 1);
    if (rows.size() <= limit) return Optional.of(new Page(rows, null));

    var accounts = rows.subList(0, limit);
    var player = (PartyRef.Player) owner.owner();
    var next = new Cursor(owner.world(), player.id(), accounts.getLast().id());
    return Optional.of(new Page(accounts, next.encode()));
  }
}
