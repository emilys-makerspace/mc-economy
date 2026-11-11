package ca.emilybelnavis.mcmods.economy.trade.cash;

import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.trade.market.MarketFacts;
import ca.emilybelnavis.mcmods.economy.trade.market.MarketStore;
import ca.emilybelnavis.mcmods.economy.trade.pricing.Pricing;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import java.util.*;
import java.util.function.Function;

/** Cash and ledger rules shared by legacy and reference-priced trader checkout. */
final class TraderCash {
  private TraderCash() {}

  record CashValue(long total, long unmarked, List<CashIssuance.Lot> lots) {}

  static CashValue validateCash(
      JournalEngine.ReadView reads,
      WorldId world,
      EconomyCurrency currency,
      List<Deposits.Slot> slots,
      UUID inputCustody,
      boolean own) {
    long total = 0, unmarked = 0;
    var lots = new TreeMap<String, CashIssuance.Lot>();
    for (var slot : slots) {
      var denomination = CashDenominations.require(slot.claim().item());
      if (denomination.currency() != currency || !slot.claim().world().equals(world))
        throw new IllegalArgumentException("CASH_SINGLE_SELECTED_CURRENCY_REQUIRED");
      long value = Math.multiplyExact(denomination.minorUnits(), slot.claim().count());
      total = Math.addExact(total, value);
      var lot = CashIssuance.find(reads, world, slot.claim().lot()).orElse(null);
      if (slot.unmarked()) {
        if (lot != null) throw new IllegalArgumentException("Unmarked cash has tracked identity");
        unmarked = Math.addExact(unmarked, value);
      } else {
        if (lot == null
            || lot.state() != CashIssuance.State.ACTIVE
            || !lot.item().equals(slot.claim().item()))
          throw new IllegalArgumentException("Marked original cash unavailable");
        lots.put(lot.id().toString(), lot);
      }
    }
    for (var entry : markedQuantities(slots).entrySet()) {
      var lot = lots.get(entry.getKey().toString());
      long reserved = CashIssuance.reserved(reads, lot.id(), null);
      if (own) {
        var rows =
            reads.query(
                "SELECT count FROM custody_cash_reservations WHERE custody_id=? AND lot_id=? AND state='ACTIVE'",
                List.of(inputCustody.toString(), lot.id().toString()),
                1);
        if (rows.size() != 1 || Long.parseLong(rows.getFirst().get("count")) != entry.getValue())
          throw new IllegalArgumentException("Original cash reservation mismatch");
        reserved -= entry.getValue();
      }
      if (lot.remaining() - reserved < entry.getValue())
        throw new IllegalArgumentException("Cash original quantity reserved");
    }
    return new CashValue(total, unmarked, List.copyOf(lots.values()));
  }

  static Map<UUID, Integer> markedQuantities(List<Deposits.Slot> slots) {
    var values = new LinkedHashMap<UUID, Integer>();
    for (var slot : slots)
      if (!slot.unmarked()) values.merge(slot.claim().lot(), slot.claim().count(), Math::addExact);
    return values;
  }

  static InstitutionCatalog.InstitutionId supplier(
      JournalEngine.ReadView reads, WorldId world, UUID marketId) {
    var m = MarketStore.market(reads, marketId);
    if (m == null) throw new IllegalArgumentException("Unknown cash market");
    if (m.kind() == MarketFacts.Kind.VILLAGE) {
      var plans =
          reads.query(
              "SELECT plan FROM village_plans WHERE world_id=? AND village_id=?",
              List.of(world.value().toString(), marketId.toString()),
              1);
      if (!plans.isEmpty()) {
        var plan = BranchPlans.decode(plans.getFirst().get("plan"));
        var first = plan.branches().stream().filter(b -> b.slot() == 0).findFirst();
        if (first.isPresent() && first.get().institution() != null)
          return new InstitutionCatalog.InstitutionId(first.get().institution());
      }
      var branch = Branches.find(reads, world, BranchPlans.branchId(marketId, 0));
      if (branch.isPresent() && branch.get().choice().institution() != null)
        return new InstitutionCatalog.InstitutionId(branch.get().choice().institution());
    }
    return new InstitutionCatalog.InstitutionId("economy:bank/crownstone");
  }

  static List<CashIssuance.Lot> lots(
      WorldId world, UUID original, WithdrawalPlanner.Plan plan, Function<String, UUID> stableId) {
    var values = new ArrayList<CashIssuance.Lot>();
    for (var line : plan.lines())
      values.add(
          new CashIssuance.Lot(
              world,
              stableId.apply("lot|" + world.value() + "|" + original + "|" + line.denomination().id()),
              line.denomination().id(),
              line.count(),
              line.count(),
              CashIssuance.State.ACTIVE,
              1,
              "npc-cash:" + original));
    return List.copyOf(values);
  }

  static List<NpcSettlement.Group> expectedGroups(
      WorldId world,
      PlayerId actor,
      EconomyCurrency cashCurrency,
      EconomyCurrency goodsCurrency,
      Pricing.Side side,
      long goods,
      long inputCash,
      long outputCash,
      NpcCashFx.Quote fx) {
    var out = new ArrayList<NpcSettlement.Group>();
    if (inputCash > 0) out.add(issue(world, cashCurrency, inputCash));
    if (side == Pricing.Side.SELL) out.add(issue(world, goodsCurrency, goods));
    if (fx != null) {
      var from = fx.conversion().pair().source();
      var to = fx.conversion().pair().target();
      long fee = fx.pricing().fee().minorUnits();
      if (fee > 0) {
        var bank = fx.supplier();
        var depositor = new InstitutionCatalog.InstitutionId("economy:depositor/" + actor.value());
        out.add(
            new NpcSettlement.Group(
                PostingBatch.Authority.TRANSFER,
                List.of(
                    leg(NpcSettlement.bridge(world, from), NPC_BRIDGE, from, -fee),
                    leg(
                        FinancialAccounts.id(world, bank, FinancialAccounts.Role.CASH, from),
                        INSTITUTION_CASH,
                        from,
                        fee))));
        out.add(
            new NpcSettlement.Group(
                PostingBatch.Authority.TRANSFER,
                List.of(
                    leg(
                        FinancialAccounts.id(
                            world, depositor, FinancialAccounts.Role.EXPENSE, from),
                        INSTITUTION_CLAIM,
                        from,
                        fee),
                    leg(
                        FinancialAccounts.id(world, bank, FinancialAccounts.Role.INCOME, from),
                        INSTITUTION_CLAIM,
                        from,
                        -fee))));
      }
      out.add(
          new NpcSettlement.Group(
              PostingBatch.Authority.FX,
              List.of(
                  leg(
                      NpcSettlement.bridge(world, from),
                      NPC_BRIDGE,
                      from,
                      -fx.conversion().debit().minorUnits()),
                  leg(
                      Settlement.clearingAccount(from),
                      CLEARING,
                      from,
                      fx.conversion().debit().minorUnits()),
                  leg(
                      Settlement.clearingAccount(to),
                      CLEARING,
                      to,
                      -fx.conversion().credit().minorUnits()),
                  leg(
                      NpcSettlement.bridge(world, to),
                      NPC_BRIDGE,
                      to,
                      fx.conversion().credit().minorUnits()))));
    }
    if (side == Pricing.Side.BUY) out.add(redeem(world, goodsCurrency, goods));
    if (outputCash > 0) out.add(redeem(world, cashCurrency, outputCash));
    return List.copyOf(out);
  }

  private static NpcSettlement.Group issue(WorldId w, EconomyCurrency c, long n) {
    return new NpcSettlement.Group(
        PostingBatch.Authority.ISSUE,
        List.of(
            leg(Settlement.issuanceAccount(c), ISSUANCE, c, -n),
            leg(NpcSettlement.bridge(w, c), NPC_BRIDGE, c, n)));
  }

  private static NpcSettlement.Group redeem(WorldId w, EconomyCurrency c, long n) {
    return new NpcSettlement.Group(
        PostingBatch.Authority.REDEEM,
        List.of(
            leg(NpcSettlement.bridge(w, c), NPC_BRIDGE, c, -n),
            leg(Settlement.issuanceAccount(c), ISSUANCE, c, n)));
  }

  private static PostingBatch.Leg leg(UUID id, PostingBatch.Role role, EconomyCurrency c, long n) {
    return new PostingBatch.Leg(id, role, c, new Money(c, n));
  }
}
