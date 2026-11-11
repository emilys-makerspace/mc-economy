package ca.emilybelnavis.mcmods.economy.institution;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.banking.BankingMenu;
import ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-thread leases publish detached revocation flags; the storage worker never reads Minecraft
 * state.
 */
public final class KioskAdmission {
  private record Lease(
      ServerPlayer player,
      BankingMenu menu,
      TerminalPolicy.Snapshot terminal,
      BranchLeaseState live,
      BranchAdmission.Issuer issuer,
      long expires) {}

  private static final Map<MinecraftServer, List<Lease>> LEASES = new IdentityHashMap<>();

  private KioskAdmission() {}

  public static BranchAdmission.Proof issue(
      ServerPlayer player,
      BankingMenu menu,
      TerminalPolicy.Snapshot terminal,
      BranchAdmission.Context context) {
    if (menu.access() != BankingMenu.Access.KIOSK
        || !menu.valid(player)
        || !terminal.active()
        || !terminal.anchor().equals(menu.anchor())
        || !terminal.world().equals(context.world())
        || Economy.serverServices(player.server) == null
        || !terminal.world().equals(Economy.serverServices(player.server).worldId())
        || terminal.kind() != TerminalPolicy.Kind.KIOSK
        || (terminal.binding() == null || !terminal.binding().bound())
        || !terminal.binding().institution().equals(context.bank())
        || !terminal.generation().equals(menu.generation())
        || !context.session().equals(menu.session())
        || !context.actor().value().equals(player.getUUID()))
      throw new IllegalArgumentException("BRANCH_UNAVAILABLE");
    var live = state(player, menu, terminal);
    var issuer =
        new BranchAdmission.Issuer(
            System::currentTimeMillis,
            (candidate, reads) ->
                live.live()
                    && candidate.equals(context)
                    && TerminalPolicy.matches(
                        reads,
                        context.world(),
                        terminal.anchor(),
                        terminal.generation(),
                        terminal.revision())
                    && ca.emilybelnavis.mcmods.economy.journal.TerminalBanking.find(
                            reads, context.world(), terminal.anchor(), terminal.generation())
                        .filter(
                            binding ->
                                binding.bound() && binding.institution().equals(context.bank()))
                        .isPresent());
    var list = LEASES.computeIfAbsent(player.server, ignored -> new ArrayList<>());
    if (list.size() >= 256) throw new IllegalStateException("BRANCH_BUSY");
    list.add(new Lease(player, menu, terminal, live, issuer, System.currentTimeMillis() + 30_000));
    return issuer.issue(context, 30_000);
  }

  public static TerminalPolicy.Authority authority(
      ServerPlayer player,
      BankingMenu menu,
      TerminalPolicy.Snapshot terminal,
      ca.emilybelnavis.mcmods.economy.journal.Journal.Intent expected) {
    if (!menu.valid(player) || menu.access() != BankingMenu.Access.ADMIN)
      throw new IllegalArgumentException("TERMINAL_ACCESS");
    var live = state(player, menu, terminal);
    var list = LEASES.computeIfAbsent(player.server, ignored -> new ArrayList<>());
    if (list.size() >= 256) throw new IllegalStateException("TERMINAL_BUSY");
    list.add(new Lease(player, menu, terminal, live, null, System.currentTimeMillis() + 30_000));
    return (action, intent, current, reads) ->
        live.live()
            && expected.equals(intent)
            && current != null
            && current.generation().equals(terminal.generation())
            && current.revision() == terminal.revision();
  }

  public static TerminalPolicy.Authority financialAuthority(
      ServerPlayer player,
      BankingMenu menu,
      TerminalPolicy.Snapshot terminal,
      ca.emilybelnavis.mcmods.economy.journal.Journal.Intent expected) {
    if (!menu.valid(player)
        || terminal == null
        || terminal.binding() == null
        || !terminal.binding().bound()) throw new IllegalArgumentException("UNBOUND_BANK");
    var live = state(player, menu, terminal);
    var list = LEASES.computeIfAbsent(player.server, ignored -> new ArrayList<>());
    if (list.size() >= 256) throw new IllegalStateException("TERMINAL_BUSY");
    list.add(new Lease(player, menu, terminal, live, null, System.currentTimeMillis() + 30_000));
    return (action, intent, current, reads) -> live.live() && expected.equals(intent);
  }

  private static BranchLeaseState state(
      ServerPlayer player, BankingMenu menu, TerminalPolicy.Snapshot terminal) {
    var observation = observe(player, menu, terminal);
    var state = new BranchLeaseState(observation);
    if (!observation.generation().equals(terminal.generation())
        || observation.revision() != terminal.revision()
        || !observation.anchor().equals(terminal.anchor())) state.revoke();
    return state;
  }

  private static BranchLeaseState.Observation observe(
      ServerPlayer player, BankingMenu menu, TerminalPolicy.Snapshot terminal) {
    var a = menu.anchor();
    var position = new BlockPos(a.x(), a.y(), a.z());
    boolean loaded =
        player.serverLevel().dimension().location().toString().equals(a.dimension())
            && player.serverLevel().hasChunkAt(position);
    var entity =
        loaded
                && player.serverLevel().getBlockEntity(position)
                    instanceof ca.emilybelnavis.mcmods.economy.terminal.TerminalBlockEntity found
            ? found
            : null;
    boolean permitted =
        menu.access() != BankingMenu.Access.ADMIN
            || entity != null
                && (player.hasPermissions(2) || player.getUUID().equals(entity.owner()));
    return new BranchLeaseState.Observation(
        new ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId(player.getUUID()),
        menu.session(),
        player.serverLevel().dimension().location().toString(),
        menu.anchor(),
        entity == null ? new UUID(0, 0) : entity.generation(),
        entity == null ? 0 : entity.policyRevision(),
        player.server.getPlayerList().getPlayer(player.getUUID()) == player,
        player.isAlive(),
        player.containerMenu == menu,
        menu.valid(player),
        player.distanceToSqr(a.x() + .5, a.y() + .5, a.z() + .5),
        permitted);
  }

  /** Called before publishing worker completions each tick. */
  public static void tick(MinecraftServer server) {
    ca.emilybelnavis.mcmods.economy.credit.CreditDrawAdmission.tick(server);
    var list = LEASES.get(server);
    if (list == null) return;
    list.removeIf(
        lease -> {
          lease.live().update(observe(lease.player(), lease.menu(), lease.terminal()));
          boolean valid = System.currentTimeMillis() < lease.expires() && lease.live().live();
          if (!valid) {
            lease.live().revoke();
            if (lease.issuer() != null) lease.issuer().disable();
          }
          return !valid;
        });
  }

  public static void closed(BankingMenu menu) {
    for (var list : LEASES.values())
      list.removeIf(
          lease -> {
            if (lease.menu() != menu) return false;
            lease.live().revoke();
            if (lease.issuer() != null) lease.issuer().disable();
            return true;
          });
  }

  public static void removed(MinecraftServer server, String dimension, BlockPos position) {
    var list = LEASES.get(server);
    if (list == null) return;
    list.removeIf(
        lease -> {
          var a = lease.terminal().anchor();
          if (!a.dimension().equals(dimension)
              || a.x() != position.getX()
              || a.y() != position.getY()
              || a.z() != position.getZ()) return false;
          lease.live().revoke();
          if (lease.issuer() != null) lease.issuer().disable();
          return true;
        });
  }

  public static void stopped(MinecraftServer server) {
    ca.emilybelnavis.mcmods.economy.credit.CreditDrawAdmission.stop(server);
    var list = LEASES.remove(server);
    if (list != null)
      list.forEach(
          lease -> {
            lease.live().revoke();
            if (lease.issuer() != null) lease.issuer().disable();
          });
  }
}
