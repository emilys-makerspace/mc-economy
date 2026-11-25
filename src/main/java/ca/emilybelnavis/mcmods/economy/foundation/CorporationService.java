package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.corporation.CorporateCapital;
import ca.emilybelnavis.mcmods.economy.corporation.Corporations;
import ca.emilybelnavis.mcmods.economy.corporation.Managers;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-thread founder entry points. Each call admits and publishes to the original connection.
 */
public final class CorporationService {
  private final ServerPlayer sender;
  private final ServerServices services;
  private final PartyRef owner;

  public CorporationService(ServerPlayer sender) {
    this.sender = Objects.requireNonNull(sender);
    services =
        Objects.requireNonNull(Economy.serverServices(Objects.requireNonNull(sender.getServer())));
    owner =
        new PartyRef(services.partyWorld(), new PartyRef.Player(new PlayerId(sender.getUUID())));
    grant();
  }

  private PartyAuthorization.Grant grant() {
    return PartyAuthorization.player(
            sender, server -> Economy.serverServices(server) == services ? services : null, owner)
        .orElseThrow(() -> new IllegalStateException("Corporation session unavailable"));
  }

  private <T> CompletionStage<T> deliver(CompletionStage<T> stage) {
    return stage.thenApplyAsync(
        value -> {
          grant();
          return value;
        },
        Objects.requireNonNull(sender.getServer()));
  }

  public CompletionStage<Optional<List<Managers.Membership>>> managers(
      UUID company, UUID after, int limit) {
    var grant = grant();
    return deliver(
        services.readAccounts(
            grant,
            false,
            reads -> Managers.roster(reads, (PlayerId) grant.actor(), company, after, limit)));
  }

  public CompletionStage<Journal.Outcome> updateManager(
      OperationId operation, RequestId attempt, Managers.Change change) {
    CorporateBanking.authorityChanging(services);
    return deliver(
        services
            .submitOwned(
                grant(),
                Managers.KIND,
                operation,
                attempt,
                change.request(),
                Managers.mutation(owner, change),
                null)
            .completion()
            .thenApply(JournalService.Delivery::outcome));
  }

  public CompletionStage<Optional<Journal.Outcome>> lookupManagerChange(
      OperationId operation, RequestId attempt, Managers.Change original) {
    return deliver(
        services.lookupOwned(
            grant(),
            Managers.KIND,
            operation,
            attempt,
            original.request(),
            Managers.mutation(owner, original)));
  }

  public CompletionStage<List<AccountIdentity>> fundingAccounts() {
    return deliver(
        services
            .readAccounts(
                grant(), false, reads -> Optional.of(AccountCatalog.openBanks(reads, owner)))
            .thenApply(Optional::orElseThrow));
  }

  public record Receipt(String status, String code, String details) {}

  /** Recover only this founder's native management outcomes, including after a restart. */
  public CompletionStage<Optional<Receipt>> receipt(UUID operation) {
    var grant = grant();
    return deliver(
        services
            .readAccounts(
                grant,
                false,
                reads ->
                    Optional.of(
                        reads
                            .query(
                                "SELECT status,result_code,result_payload FROM operation_outcomes"
                                    + " WHERE world_id=? AND operation_id=? AND actor=? AND"
                                    + " service=? AND operation_type IN (?,?)",
                                List.of(
                                    owner.world().value().toString(),
                                    operation.toString(),
                                    Journal.actorKey(grant.actor()),
                                    Corporations.KIND.service(),
                                    Corporations.KIND.operationType(),
                                    Managers.KIND.operationType()),
                                1)
                            .stream()
                            .findFirst()
                            .map(row -> receipt(reads, (PlayerId) grant.actor(), row))))
            .thenApply(Optional::orElseThrow));
  }

  private static Receipt receipt(
      JournalEngine.ReadView reads, PlayerId actor, Map<String, String> row) {
    String code = row.get("result_code"), details = row.get("result_payload");
    if (Set.of("CORPORATION_ACTIVE", "CORPORATION_WINDING_DOWN", "CORPORATION_DISSOLVED")
        .contains(code)) {
      var fields = details.split("\\|", -1);
      if (fields.length == 2)
        details =
            Corporations.find(reads, actor, UUID.fromString(fields[0]))
                .map(company -> company.identifier() + " (revision " + fields[1] + ")")
                .orElse("Company unavailable");
    } else if (code.equals("MEMBERSHIP_UPDATED")) details = "Membership revision " + details;
    return new Receipt(row.get("status"), code, details);
  }

  public CompletionStage<Optional<Corporations.Fee>> quoteRegistration(AccountId account) {
    return deliver(
        services.readAccounts(
            grant(),
            false,
            reads -> Corporations.quote(reads, owner, account, services.financialMillis())));
  }

  public CompletionStage<Optional<Corporations.Company>> resolve(String identifier) {
    var grant = grant();
    var actor = (PlayerId) grant.actor();
    return deliver(
        services.readAccounts(
            grant, false, reads -> Corporations.resolve(reads, actor, identifier)));
  }

  public CompletionStage<Optional<CorporateCapital.Profile>> profile(UUID id) {
    var grant = grant();
    var actor = (PlayerId) grant.actor();
    return deliver(
        services.readAccounts(
            grant,
            false,
            reads ->
                Corporations.find(reads, actor, id)
                    .flatMap(company -> CorporateCapital.profile(reads, company.id()))));
  }

  public CompletionStage<Optional<Corporations.Company>> find(UUID id) {
    var actor = (PlayerId) grant().actor();
    return deliver(
        services.readAccounts(grant(), false, reads -> Corporations.find(reads, actor, id)));
  }

  public CompletionStage<List<Corporations.Company>> companies(UUID after, int limit) {
    var grant = grant();
    return deliver(
        services
            .readAccounts(
                grant,
                false,
                reads ->
                    Optional.of(Corporations.page(reads, (PlayerId) grant.actor(), after, limit)))
            .thenApply(Optional::orElseThrow));
  }

  public CompletionStage<Journal.Outcome> submit(
      OperationId operation, RequestId attempt, Corporations.Command command) {
    CorporateBanking.authorityChanging(services);
    var submission =
        services.submitOwned(
            grant(),
            Corporations.KIND,
            operation,
            attempt,
            command.request(),
            Corporations.handler(owner, command, services::financialMillis),
            null);
    return deliver(submission.completion().thenApply(JournalService.Delivery::outcome));
  }

  public CompletionStage<Optional<Journal.Outcome>> lookup(
      OperationId operation, RequestId attempt, Corporations.Command original) {
    return deliver(
        services.lookupOwned(
            grant(),
            Corporations.KIND,
            operation,
            attempt,
            original.request(),
            Corporations.handler(owner, original, services::financialMillis)));
  }
}
