package ca.emilybelnavis.mcmods.economy.banking;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.cash.CashStackIdentity;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/**
 * Converts saved, exact menu leftovers into owner-bound claims without issuing replacement items.
 */
public final class BankingReturns {
  public static final Journal.Kind KIND = new Journal.Kind("economy:menu_returns", "economy:claim");

  private BankingReturns() {}

  public static void create(
      ServerPlayer player, BankingMenu menu, WorldId world, List<UUID> stashIds) {
    if (!player.server.isSameThread()) throw new IllegalStateException("Server thread required");
    if (stashIds == null || stashIds.size() > 48 || stashIds.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("Menu return count");
    var ids = List.copyOf(new LinkedHashSet<>(stashIds));
    if (ids.isEmpty()) return;
    next(player, menu, world, ids, 0);
  }

  private static void next(
      ServerPlayer player, BankingMenu menu, WorldId world, List<UUID> ids, int index) {
    if (index >= ids.size()) {
      BankingController.report(
          menu,
          menu.page(),
          menu.lines(),
          "Original returns are protected. Open Claims to collect.");
      return;
    }
    if (player.server.getPlayerList().getPlayer(player.getUUID()) != player) return;
    var services = BankingController.services(player);
    if (services == null
        || services.state()
            != ca.emilybelnavis.mcmods.economy.foundation.ServerServices.State.READY) return;
    var actor = BankingController.actor(player);
    var stash = ids.get(index);
    services
        .ledger()
        .custodyCase(actor, stash, (who, reads) -> false)
        .whenComplete(
            (found, error) -> {
              if (error != null) {
                BankingController.fail(menu, error);
                return;
              }
              if (found.isPresent()) {
                // The original case may now be delivered or quarantined. Never turn replay into
                // another claim.
                var existing = found.orElseThrow();
                if (!existing.terms().source().equals(location(stash))
                    || !existing.terms().items().startsWith("menu-original-v1|")) {
                  BankingController.fail(
                      menu, new IllegalStateException("Original return identity needs review"));
                  return;
                }
                next(player, menu, world, ids, index + 1);
                return;
              }
              if (player.server.getPlayerList().getPlayer(player.getUUID()) != player) return;
              try {
                var original = BankingInventory.stashed(player, stash);
                if (original.isEmpty())
                  throw new IllegalStateException(
                      "Original return is missing or uncertain; no claim was created");
                String items = BankingInventory.menuClaimItems(player, stash);
                var cash = CashStackIdentity.claim(original, world).orElse(null);
                var operation =
                    UUID.nameUUIDFromBytes(
                        ("economy:menu-return:" + world.value() + stash)
                            .getBytes(StandardCharsets.UTF_8));
                var request =
                    new Journal.CanonicalRequest(
                        1,
                        Map.of(
                            "world",
                            world.value().toString(),
                            "stash",
                            stash.toString(),
                            "items_sha256",
                            digest(items)));
                var intent =
                    new Journal.Intent(
                        new OperationId(operation),
                        new RequestId(operation),
                        actor,
                        KIND,
                        request,
                        null,
                        null);
                BankingController.submit(
                    player,
                    menu,
                    intent,
                    () -> {
                      // Retry re-observes the same exact held originals, then verifies the actual
                      // player save.
                      if (player.server.getPlayerList().getPlayer(player.getUUID()) != player
                          || !BankingInventory.menuClaimItems(player, stash).equals(items))
                        throw new IllegalStateException(
                            "Original return changed; retained for review");
                      var proof = BankingSaves.player(player, world, operation);
                      if (!BankingInventory.menuClaimItems(player, stash).equals(items))
                        throw new IllegalStateException("Original return changed during save");
                      var evidence =
                          new Custody.Evidence(
                              Custody.EvidenceKind.UNDELIVERED,
                              proof.reference(),
                              location(stash),
                              proof.token());
                      var registration = registration(intent, stash, actor, items, evidence, cash);
                      return services
                          .ledger()
                          .trustedJournal(
                              KIND, (who, kind) -> who.equals(actor), registration, null);
                    },
                    outcome -> {
                      if (outcome.status() == Journal.Status.COMMITTED
                          || outcome.status() == Journal.Status.DURABLY_REJECTED
                              && outcome.result().code().equals("MENU_RETURN_EXISTS"))
                        next(player, menu, world, ids, index + 1);
                      else if (outcome.status() == Journal.Status.DURABLY_REJECTED)
                        BankingController.fail(
                            menu,
                            new IllegalStateException(
                                "Original return remains held: " + outcome.result().code()));
                    });
              } catch (RuntimeException failure) {
                BankingController.fail(menu, failure);
              }
            });
  }

  private static JournalEngine.Registration registration(
      Journal.Intent expected,
      UUID stash,
      PlayerId actor,
      String items,
      Custody.Evidence evidence,
      CashIssuance.Claim cash) {
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!intent.equals(expected)
              || !intent.actor().equals(actor)
              || !intent.kind().equals(KIND)
              || !digest(items).equals(intent.request().fields().get("items_sha256"))
              || evidence.saveToken().isBlank()
              || evidence.reference().isBlank())
            return new JournalEngine.Reject(new Journal.Result(1, "MENU_RETURN_PROOF", ""));
          var claim =
              Custody.returnClaim(
                  intent,
                  stash,
                  stash,
                  actor,
                  items,
                  location(stash),
                  evidence,
                  "saved menu originals did not fit the owner inventory",
                  reads);
          if (claim == null) {
            var existing = Custody.find(reads, actor, stash, false);
            if (existing.isEmpty()
                || !existing.orElseThrow().terms().source().equals(location(stash)))
              return new JournalEngine.Reject(new Journal.Result(1, "MENU_RETURN_CONFLICT", ""));
            return new JournalEngine.Reject(
                new Journal.Result(1, "MENU_RETURN_EXISTS", stash.toString()));
          }
          boolean protectCash = false;
          if (cash != null) {
            var lot = CashIssuance.find(reads, cash.world(), cash.lot()).orElse(null);
            // A marker is only a lookup key. Unbacked/collectible originals gain no redeemable
            // value here.
            protectCash =
                lot != null
                    && lot.state() == CashIssuance.State.ACTIVE
                    && lot.item().equals(cash.item())
                    && lot.remaining() - CashIssuance.reserved(reads, cash.lot(), null)
                        >= cash.count();
          }
          boolean protectedQuantity = protectCash;
          return new JournalEngine.Commit(
              claim.records(),
              new Journal.Result(1, "MENU_RETURN_CLAIM", stash.toString()),
              tx -> {
                claim.projections().apply(tx);
                if (protectedQuantity)
                  tx.execute(
                      "INSERT INTO custody_cash_reservations(custody_id,lot_id,count,state) VALUES(?,?,?,'ACTIVE')",
                      List.of(
                          stash.toString(), cash.lot().toString(), Integer.toString(cash.count())));
              });
        });
  }

  private static String location(UUID stash) {
    return "menu-return:" + stash;
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
