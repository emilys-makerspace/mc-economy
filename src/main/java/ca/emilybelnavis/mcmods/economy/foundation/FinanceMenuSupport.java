package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.util.*;
import java.util.function.BiFunction;
import net.minecraft.server.level.ServerPlayer;

/** Shared authenticated audience and durable A5 intent handling for finance pages. */
public final class FinanceMenuSupport {
  private FinanceMenuSupport() {}

  public record Audience(ServerPlayer player, BankingMenu menu, UUID session, long revision) {
    public boolean live() {
      return player.server.getPlayerList().getPlayer(player.getUUID()) == player
          && player.containerMenu == menu
          && menu.valid(player)
          && menu.session().equals(session)
          && menu.revision() == revision;
    }

    public void reply(
        String page, List<String> lines, String status, List<BankingNetwork.Choice> choices) {
      respond(page, lines, status, choices, BankingOperation.offered(false, choices));
    }

    public void decline(
        String page, List<String> lines, String status, List<BankingNetwork.Choice> choices) {
      respond(
          page,
          lines,
          status,
          choices.stream().filter(c -> !BankingOperation.confirmation(c.action())).toList(),
          BankingOperation.View.of(
              BankingOperation.State.DECLINED, "screen.economy.operation.declined"));
    }

    public void respond(
        String page,
        List<String> lines,
        String status,
        List<BankingNetwork.Choice> choices,
        BankingOperation.View operation) {
      if (!live()) return;
      menu.setBusy(false);
      // Resolve and bound the complete translated terms only at the final menu publication.
      menu.publish(page, lines, status, choices, operation, null);
    }
  }

  public static Audience audience(ServerPlayer player, BankingMenu menu) {
    return new Audience(player, menu, menu.session(), menu.revision());
  }

  public static PartyAuthorization.Grant grant(ServerPlayer player, ServerServices services) {
    var world = services.partyWorld();
    if (world == null) return null;
    var target = new PartyRef(world, new PartyRef.Player(new PlayerId(player.getUUID())));
    return PartyAuthorization.player(player, Economy::serverServices, target).orElse(null);
  }

  public static boolean offered(Audience audience, String action, String value) {
    return audience.menu().choices().stream()
        .anyMatch(c -> c.action().equals(action) && c.value().equals(value));
  }

  public static BranchAdmission.Proof branch(
      Audience audience,
      PartyAuthorization.Grant grant,
      LedgerService.BranchContext view,
      BranchAdmission.Action action,
      InstitutionCatalog.InstitutionId target) {
    if (!audience.live() || audience.menu().access() != BankingMenu.Access.KIOSK)
      throw new IllegalArgumentException("BRANCH_UNAVAILABLE");
    var terminal = view.terminal();
    if (terminal == null
        || terminal.binding() == null
        || !terminal.binding().bound()
        || view.denomination() == null) throw new IllegalArgumentException("BRANCH_UNAVAILABLE");
    var account = action == BranchAdmission.Action.SECONDARY ? view.primary() : view.account();
    var context =
        new BranchAdmission.Context(
            grant.target().world(),
            audience.menu().session(),
            new PlayerId(audience.player().getUUID()),
            action,
            terminal.binding().institution(),
            target,
            account == null ? null : account.id(),
            account == null ? 0 : account.revision(),
            view.provider() == null ? 0 : view.provider().revision(),
            view.denomination().revision());
    return KioskAdmission.issue(audience.player(), audience.menu(), terminal, context);
  }

  public static void submit(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      Journal.Kind kind,
      Journal.CanonicalRequest request,
      BiFunction<OperationId, RequestId, JournalService.Submission> submit) {
    if (audience.live())
      BankingAccounts.financeSubmit(audience, services, grant, kind, request, submit);
  }
}
