package ca.emilybelnavis.mcmods.economy.institution;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.RebaseSettlement;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.util.*;

/** Test-only trusted branch adapter. */
public final class BranchFixtures {
  private BranchFixtures() {}

  public static BranchAdmission.Proof proof(BranchAdmission.Context context) {
    return new BranchAdmission.Issuer(() -> 0, (c, r) -> true).issue(context, 30_000);
  }

  public static BranchAdmission.Proof closure(FoundationStore store, PartyRef owner, AccountId id) {
    return store.transaction(
        tx -> {
          var account = AccountCatalog.find(tx::query, owner, id).orElseThrow();
          var nativeBank =
              InstitutionCatalog.banks().stream()
                  .filter(b -> b.tier1() && b.baseCurrency() == account.currency())
                  .findFirst()
                  .orElseThrow();
          tx.execute(
              "INSERT OR IGNORE INTO bank_account_providers VALUES(?,?,0,1,0)",
              List.of(id.value().toString(), nativeBank.id().key()));
          var binding = BankProviders.find(tx::query, id).orElseThrow();
          var bank =
              InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(binding.bankKey()));
          tx.execute(
              "INSERT OR IGNORE INTO institution_parties VALUES(?,?,?,?,?,?,1)",
              List.of(
                  owner.world().value().toString(),
                  bank.id().key(),
                  new InstitutionCatalog.SystemActor(owner.world(), bank.id())
                      .actorId()
                      .value()
                      .toString(),
                  bank.name(),
                  bank.baseCurrency().name(),
                  bank.tier().name()));
          var denomination = RebaseSettlement.denomination(tx::query).orElseThrow();
          return proof(
              new BranchAdmission.Context(
                  owner.world(),
                  UUID.randomUUID(),
                  ((PartyRef.Player) owner.owner()).id(),
                  BranchAdmission.Action.CLOSE,
                  new InstitutionCatalog.InstitutionId(binding.bankKey()),
                  null,
                  id,
                  account.revision(),
                  binding.revision(),
                  denomination.revision()));
        });
  }
}
