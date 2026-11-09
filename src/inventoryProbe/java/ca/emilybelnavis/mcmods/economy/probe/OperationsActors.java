package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.account.*;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.trade.catalog.*;
import ca.emilybelnavis.mcmods.economy.trade.market.*;
import ca.emilybelnavis.mcmods.economy.trade.pricing.*;
import ca.emilybelnavis.mcmods.economy.trade.settlement.*;
import ca.emilybelnavis.mcmods.economy.trade.stock.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Twenty detached synthetic service owners. No network or physical delivery claim. All money,
 * catalog, stock and market changes use the existing journal-backed services; no projection seeds.
 */
public final class OperationsActors {
  private static final long INITIAL_CAD = 1_000_000;
  private static final int TRADE_BATCH = 64, TRADER_STOCK = 1024;
  private static final UUID SOURCE = stable("price-source"), EPOCH = stable("price-epoch");
  private static final TradeCatalog.Offer OFFER =
      new TradeCatalog.Offer(
          0,
          Pricing.Side.BUY,
          new TradeCatalog.Goods("minecraft:bread", TradeCatalog.Match.EXACT_COMPONENTS, "{}"),
          1,
          1,
          List.of(),
          TRADER_STOCK,
          2);
  private final MinecraftServer server;
  private final ServerServices services;
  private final WorldId world;
  private final UUID session;
  private final List<Actor> actors = new ArrayList<>();
  private final Map<String, Long> counts = new TreeMap<>();
  private final List<Replay> replays = new ArrayList<>();
  private BranchAdmission.Issuer issuer;
  private volatile boolean live = true;
  public BiConsumer<String, Long> outcomeTiming = (type, nanos) -> {};

  private static final class Actor {
    final int index;
    final PlayerId id;
    final UUID trader;
    final ServerPlayer player;
    final PartyAuthorization.Grant grant;
    AccountIdentity cad, usd;
    long expectedCad = INITIAL_CAD, expectedUsd;

    Actor(int index, ServerPlayer player, PartyAuthorization.Grant grant) {
      this.index = index;
      this.player = player;
      this.grant = grant;
      id = new PlayerId(player.getUUID());
      trader = stable("trader-" + index);
    }
  }

  private record Replay(
      Actor actor,
      OperationId operation,
      RequestId request,
      SameCurrency.Instruction payment,
      FxSettlement.Exchange exchange,
      UUID trade,
      Journal.Outcome outcome) {}

  public OperationsActors(MinecraftServer server, ServerServices services) {
    this.server = server;
    this.services = services;
    this.world = services.worldId();
    this.session = services.sessionId();
  }

  public Map<String, Long> counters() {
    return Map.copyOf(counts);
  }

  private void count(String key) {
    counts.merge(key, 1L, Long::sum);
  }

  private static UUID stable(String value) {
    return UUID.nameUUIDFromBytes(("a10-08|" + value).getBytes(StandardCharsets.UTF_8));
  }

  private static OperationId operation() {
    return new OperationId(UUID.randomUUID());
  }

  private static RequestId request() {
    return new RequestId(UUID.randomUUID());
  }

  private static SameCurrency.Revision revision(AccountIdentity a) {
    return new SameCurrency.Revision(a.id(), a.revision());
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  public CompletionStage<Void> prepare() {
    try {
      var constructor =
          BranchAdmission.Issuer.class.getDeclaredConstructor(
              java.util.function.LongSupplier.class, BranchAdmission.LiveCheck.class);
      constructor.setAccessible(true);
      issuer =
          constructor.newInstance(
              (java.util.function.LongSupplier) services::financialMillis,
              (BranchAdmission.LiveCheck)
                  (context, reads) ->
                      live
                          && context.session().equals(session)
                          && actors.stream().anyMatch(a -> a.id.equals(context.actor())));
      for (int i = 0; i < 20; i++) {
        var player = BankingAcceptanceProbe.fixture(server, stable("owner-" + i), "OpsOwner" + i);
        player.setPos(0, 100, 0);
        var grant = FinanceMenuSupport.grant(player, services);
        require(grant != null, "Synthetic owner grant");
        actors.add(new Actor(i, player, grant));
      }
    } catch (Exception error) {
      return CompletableFuture.failedStage(error);
    }
    count("setup.denomination-read");
    return services
        .tradeStorage(store -> store.transaction(tx -> RebaseSettlement.denomination(tx::query)))
        .thenCompose(
            denomination -> {
              if (denomination.isPresent()) {
                require(
                    denomination.get().currency() == EconomyCurrency.CAD,
                    "Disposable CAD required");
                return CompletableFuture.completedStage(null);
              }
              // The already registered synthetic owner is the chooser's first-player lease holder.
              server
                  .getCommands()
                  .performPrefixedCommand(
                      actors.getFirst().player.createCommandSourceStack(), "economy choose CAD");
              return waitDenomination(0);
            })
        .thenCompose(
            ignored -> {
              CompletionStage<Void> chain = CompletableFuture.completedStage(null);
              for (var actor : actors) chain = chain.thenCompose(v -> prepare(actor));
              return chain;
            });
  }

  private CompletionStage<Void> waitDenomination(int turn) {
    count("setup.denomination-read");
    return services
        .tradeStorage(store -> store.transaction(tx -> RebaseSettlement.denomination(tx::query)))
        .thenCompose(
            found -> {
              if (found.isPresent()) return CompletableFuture.completedStage(null);
              require(turn < 200, "Denomination chooser did not commit");
              if (actors.getFirst().player.containerMenu
                      instanceof ca.emilybelnavis.mcmods.economy.banking.BankingMenu menu
                  && menu.worldSetup())
                server
                    .getCommands()
                    .performPrefixedCommand(
                        actors.getFirst().player.createCommandSourceStack(), "economy choose CAD");
              return waitDenomination(turn + 1);
            });
  }

  /** Yield through normal bounded storage/publication turns while production catch-up runs. */
  private CompletionStage<Void> waitEconomicReady() {
    return waitEconomicReady(System.nanoTime() + 120_000_000_000L, 0);
  }

  private CompletionStage<Void> waitEconomicReady(long deadline, int turn) {
    var metrics = services.metrics();
    require(
        System.nanoTime() < deadline && turn < 10_000,
        "Synthetic setup economic catch-up deadline: " + metrics.economy());
    count("setup.readiness-yield");
    return services
        .tradeStorage(
            store -> {
              try {
                store.requireEconomicCurrent();
                return true;
              } catch (ca.emilybelnavis.mcmods.economy.storage.StorageException error) {
                if (error.kind()
                    == ca.emilybelnavis.mcmods.economy.storage.StorageException.Kind
                        .ECONOMIC_CATCH_UP) return false;
                throw error;
              }
            })
        .thenCompose(
            current ->
                current
                        && services.metrics().economy().ready()
                        && !services.metrics().economicStepInFlight()
                    ? CompletableFuture.completedStage(null)
                    : waitEconomicReady(deadline, turn + 1));
  }

  private CompletionStage<Void> prepare(Actor a) {
    count("setup.branch-review");
    return waitEconomicReady()
        .thenCompose(
            ignored ->
                services.tradeStorage(
                    store ->
                        store.transaction(
                            tx ->
                                RebaseSettlement.denomination(tx::query).orElseThrow().revision())))
        .thenCompose(
            dr -> {
              var proof =
                  issuer.issue(
                      new BranchAdmission.Context(
                          world,
                          session,
                          a.id,
                          BranchAdmission.Action.PRIMARY,
                          new InstitutionCatalog.InstitutionId("economy:bank/crownstone"),
                          null,
                          null,
                          0,
                          0,
                          dr),
                      30_000);
              count("setup.primary-submit");
              return committed(
                  services.bankLifecycle(
                      a.grant, operation(), request(), new BankProviders.Request(proof, 0), null));
            })
        .thenCompose(ignored -> waitEconomicReady())
        .thenCompose(
            ignored -> {
              count("setup.primary-read");
              return services.ledger().primaryFunds(a.grant);
            })
        .thenCompose(
            found -> {
              a.cad = found.orElseThrow().account();
              return waitEconomicReady()
                  .thenCompose(
                      ignored ->
                          services.tradeStorage(
                              store ->
                                  store.transaction(
                                      tx ->
                                          RebaseSettlement.denomination(tx::query)
                                              .orElseThrow()
                                              .revision())))
                  .thenCompose(
                      dr -> {
                        var proof =
                            issuer.issue(
                                new BranchAdmission.Context(
                                    world,
                                    session,
                                    a.id,
                                    BranchAdmission.Action.SECONDARY,
                                    new InstitutionCatalog.InstitutionId("economy:bank/crosswind"),
                                    null,
                                    null,
                                    a.cad.revision(),
                                    0,
                                    dr),
                                30_000);
                        count("setup.secondary-submit");
                        return committed(
                            services.bankLifecycle(
                                a.grant,
                                operation(),
                                request(),
                                new BankProviders.Request(proof, 0),
                                null));
                      });
            })
        .thenCompose(ignored -> refresh(a))
        .thenCompose(ignored -> waitEconomicReady())
        .thenCompose(
            ignored -> {
              var adjustment =
                  new SameCurrency.Adjustment(
                      a.id, revision(a.cad), new Money(EconomyCurrency.CAD, INITIAL_CAD));
              var journal =
                  services
                      .ledger()
                      .adjustments(
                          SameCurrency.CREDIT,
                          (actor, kind) -> actor.equals(a.id),
                          (intent, instruction, reads) -> live,
                          null);
              count("setup.funding-submit");
              return committed(
                  journal.submit(
                      new Journal.Intent(
                          operation(),
                          request(),
                          a.id,
                          SameCurrency.CREDIT,
                          adjustment.request(),
                          null,
                          null)));
            })
        .thenCompose(ignored -> waitEconomicReady())
        .thenCompose(
            ignored -> {
              count("setup.trading-submit");
              return committed(services.initializeTrading(a.grant, operation(), request(), null));
            })
        .thenCompose(ignored -> waitEconomicReady())
        .thenCompose(
            ignored -> {
              count("setup.trader-catalog-stock-market");
              return services.tradeStorage(
                  a.id,
                  store -> {
                    var position =
                        new MarketFacts.Position("minecraft:overworld", a.index * 4096, 64, 0);
                    boolean resolved = false;
                    for (int n = 0; n < 100; n++) {
                      if (!MarketStore.resolve(
                              store, a.trader, position, position, EconomyCurrency.CAD, true)
                          .pending()) {
                        resolved = true;
                        break;
                      }
                    }
                    require(resolved, "Synthetic trader market ready");
                    CatalogStore.capture(
                        store, new TradeCatalog.Capture(a.trader, 1, List.of(OFFER)));
                    StockStore.install(store, a.trader, 1, false);
                    return null;
                  });
            });
  }

  private CompletionStage<Void> refresh(Actor a) {
    count("account-list");
    return services
        .ledger()
        .historyAccounts(a.grant, null)
        .thenAccept(
            found -> {
              var accounts = found.orElseThrow();
              a.cad =
                  accounts.stream()
                      .filter(x -> x.currency() == EconomyCurrency.CAD)
                      .findFirst()
                      .orElseThrow();
              a.usd =
                  accounts.stream()
                      .filter(x -> x.currency() == EconomyCurrency.USD)
                      .findFirst()
                      .orElseThrow();
              require(
                  accounts.stream().allMatch(x -> x.owner().equals(a.grant.target())),
                  "Foreign account result");
            });
  }

  private CompletionStage<Journal.Outcome> committed(JournalService.Submission submission) {
    return submission
        .completion()
        .thenApply(
            delivery -> {
              require(
                  delivery.outcome().status() == Journal.Status.COMMITTED,
                  "Expected commit: " + delivery.outcome().result().code());
              return delivery.outcome();
            });
  }

  public CompletionStage<String> action(int actorIndex, int cycleIndex) {
    var a = actors.get(actorIndex);
    int type = Math.floorMod(cycleIndex, 100);
    if (type < 40) {
      if ((type & 1) == 0) {
        count("dashboard-read");
        return services
            .ledger()
            .dashboard(a.grant, a.cad.id(), null)
            .thenApply(
                found -> {
                  var dashboard = found.orElseThrow();
                  require(
                      dashboard.accounts().rows().stream()
                          .allMatch(
                              x ->
                                  x.id().equals(a.cad.id().value())
                                      || x.id().equals(a.usd.id().value())),
                      "Foreign dashboard result");
                  return "read.dashboard";
                });
      }
      count("history-read");
      return services
          .ledger()
          .ownerHistory(a.grant, OwnerHistory.Query.all(), null, null)
          .thenApply(
              found -> {
                require(found.isPresent(), "Owner history denied");
                var page = found.orElseThrow();
                require(
                    page.availability() == OwnerHistory.Availability.AVAILABLE,
                    "Owner history unavailable: " + page.reason());
                require(
                    page.rows().stream()
                        .allMatch(
                            row ->
                                row.account().equals(a.cad.id().value())
                                    || row.account().equals(a.usd.id().value())),
                    "Foreign history result");
                return "read.history";
              });
    }
    if (type < 60) {
      if ((type & 1) == 0) {
        count("payment-review");
        return services
            .ledger()
            .planPayment(a.grant, actors.get((actorIndex + 1) % 20).id, 1)
            .thenApply(
                found -> {
                  require(found.isPresent(), "Payment review denied");
                  return "review.payment";
                });
      }
      count("fx-review");
      return quote(a)
          .thenApply(
              q -> {
                require(
                    q.debit().minorUnits() > 0 && q.credit().minorUnits() > 0,
                    "Positive FX review");
                return "review.fx";
              });
    }
    if (type < 80) return payment(a);
    if (type < 90) return trade(a);
    return exchange(a);
  }

  private CompletionStage<String> payment(Actor a) {
    var recipient = actors.get((a.index + 1) % 20);
    count("payment-review");
    return services
        .ledger()
        .planPayment(a.grant, recipient.id, 1)
        .thenCompose(
            found -> {
              var terms = found.orElseThrow();
              var op = operation();
              var attempt = request();
              long start = System.nanoTime();
              count("payment-confirm");
              return committed(services.ledger().submit(a.grant, op, attempt, terms))
                  .thenApply(
                      outcome -> {
                        outcomeTiming.accept("financial.payment", System.nanoTime() - start);
                        a.expectedCad -= terms.amount().minorUnits();
                        recipient.expectedCad += terms.amount().minorUnits();
                        replays.add(new Replay(a, op, attempt, terms, null, null, outcome));
                        return "financial.payment";
                      });
            });
  }

  private CompletionStage<FxQuotes.Quote> quote(Actor a) {
    return services
        .ledger()
        .quote(
            a.grant,
            request(),
            new FxQuotes.Pair(EconomyCurrency.CAD, EconomyCurrency.USD),
            FxQuotes.Side.EXACT_DEBIT,
            new Money(EconomyCurrency.CAD, 100));
  }

  private CompletionStage<String> exchange(Actor a) {
    return refresh(a)
        .thenCompose(
            v -> {
              count("fx-review");
              return quote(a);
            })
        .thenCompose(
            q -> {
              var terms = new FxSettlement.Exchange(q.id(), a.id, revision(a.cad), revision(a.usd));
              var op = operation();
              var attempt = request();
              long start = System.nanoTime();
              count("fx-confirm");
              return committed(services.ledger().exchange(a.grant, op, attempt, terms))
                  .thenApply(
                      outcome -> {
                        outcomeTiming.accept("financial.fx", System.nanoTime() - start);
                        a.expectedCad -=
                            Math.addExact(q.debit().minorUnits(), q.fee().minorUnits());
                        a.expectedUsd += q.credit().minorUnits();
                        replays.add(new Replay(a, op, attempt, null, terms, null, outcome));
                        return "financial.fx";
                      });
            });
  }

  private NpcTrades trades(ca.emilybelnavis.mcmods.economy.storage.FoundationStore store, Actor a) {
    return new NpcTrades(
        store,
        new NpcAccountFx(new FxQuotes(world), new ServicePricing(new WealthValuation())),
        services.financialClock(),
        (phase, terms, quote, reads) ->
            live
                && terms.actor().equals(a.id)
                && terms.session().equals(session)
                && terms.trader().equals(a.trader)
                && terms.account().equals(a.cad.id()));
  }

  private CompletionStage<String> trade(Actor a) {
    count("trader-review");
    return services
        .tradeMonetaryStorage(
            a.id,
            store -> {
              var publication = store.transaction(tx -> FxService.latest(tx::query));
              var source =
                  new TradePriceSource.EmeraldV1(
                      SOURCE,
                      1,
                      Rational.ONE,
                      publication.updateId(),
                      EconomyCurrency.CAD,
                      Rational.ONE);
              var comparison = CatalogPricing.compare(OFFER, List.of(OFFER), true, source);
              UUID op = UUID.randomUUID();
              var terms =
                  new NpcFacts.Terms(
                      op,
                      a.id,
                      session,
                      a.trader,
                      1,
                      0,
                      TRADE_BATCH,
                      a.cad.id(),
                      EPOCH,
                      source,
                      Rational.ZERO,
                      1,
                      "synthetic-owner-reputation",
                      comparison.comparisons(),
                      null,
                      "",
                      UUID.randomUUID(),
                      "synthetic-capacity:" + op,
                      1);
              return trades(store, a).quote(terms);
            })
        .thenCompose(
            q -> {
              long start = System.nanoTime();
              count("trader-confirm");
              return services
                  .tradeMonetaryStorage(
                      a.id,
                      store ->
                          trades(store, a)
                              .settle(NpcTrades.intent(world, q.terms().operation(), a.id)))
                  .thenApply(
                      outcome -> {
                        require(
                            outcome.status() == Journal.Status.COMMITTED,
                            "Trader expected commit: " + outcome.result().code());
                        outcomeTiming.accept("financial.trade", System.nanoTime() - start);
                        a.expectedCad -= q.price().minor();
                        replays.add(
                            new Replay(
                                a,
                                new OperationId(q.terms().operation()),
                                request(),
                                null,
                                null,
                                q.terms().operation(),
                                outcome));
                        return "financial.trade";
                      });
            });
  }

  public CompletionStage<String> reconcile() {
    CompletionStage<Void> chain = CompletableFuture.completedStage(null);
    // Every intended spend resolves to its original receipt before the independent balance check.
    for (var replay : replays)
      chain =
          chain.thenCompose(
              v -> {
                count("reconcile.original-lookup");
                if (replay.payment != null)
                  return services
                      .ledger()
                      .lookup(replay.actor.grant, replay.operation, replay.request, replay.payment)
                      .thenAccept(
                          outcome ->
                              require(
                                  outcome.orElseThrow().equals(replay.outcome),
                                  "Payment original outcome changed"));
                if (replay.exchange != null)
                  return services
                      .ledger()
                      .lookupExchange(
                          replay.actor.grant, replay.operation, replay.request, replay.exchange)
                      .thenAccept(
                          outcome ->
                              require(
                                  outcome.orElseThrow().equals(replay.outcome),
                                  "FX original outcome changed"));
                return services
                    .tradeMonetaryStorage(
                        replay.actor.id,
                        store ->
                            trades(store, replay.actor)
                                .settle(NpcTrades.intent(world, replay.trade, replay.actor.id)))
                    .thenAccept(
                        outcome ->
                            require(
                                outcome.equals(replay.outcome), "Trader original outcome changed"));
              });
    for (var actor : actors)
      chain =
          chain.thenCompose(
              v -> {
                count("reconcile.cad-funds");
                return services
                    .ledger()
                    .funds(actor.grant, actor.cad.id())
                    .thenAccept(
                        found -> {
                          var f = found.orElseThrow();
                          require(
                              f.postedMinorUnits() == actor.expectedCad
                                  && f.reservedMinorUnits() == 0,
                              "CAD reconciliation owner="
                                  + actor.index
                                  + " expected="
                                  + actor.expectedCad
                                  + " actual="
                                  + f);
                        })
                    .thenCompose(
                        ignored -> {
                          count("reconcile.usd-funds");
                          return services.ledger().funds(actor.grant, actor.usd.id());
                        })
                    .thenAccept(
                        found -> {
                          var f = found.orElseThrow();
                          require(
                              f.postedMinorUnits() == actor.expectedUsd
                                  && f.reservedMinorUnits() == 0,
                              "USD reconciliation owner="
                                  + actor.index
                                  + " expected="
                                  + actor.expectedUsd
                                  + " actual="
                                  + f);
                        })
                    .thenCompose(
                        ignored ->
                            journalSum(
                                    actor,
                                    actor.cad.id(),
                                    EconomyCurrency.CAD,
                                    null,
                                    java.math.BigInteger.ZERO,
                                    0)
                                .thenAccept(
                                    total ->
                                        require(
                                            total.equals(
                                                java.math.BigInteger.valueOf(actor.expectedCad)),
                                            "CAD immutable journal sum differs")))
                    .thenCompose(
                        ignored ->
                            journalSum(
                                    actor,
                                    actor.usd.id(),
                                    EconomyCurrency.USD,
                                    null,
                                    java.math.BigInteger.ZERO,
                                    0)
                                .thenAccept(
                                    total ->
                                        require(
                                            total.equals(
                                                java.math.BigInteger.valueOf(actor.expectedUsd)),
                                            "USD immutable journal sum differs")))
                    .thenCompose(
                        ignored -> {
                          count("reconcile.foreign-owner");
                          return services
                              .ledger()
                              .ownedBankAccount(
                                  actor.grant, actors.get((actor.index + 1) % 20).cad.id());
                        })
                    .thenAccept(
                        found -> require(found.isEmpty(), "Unauthorized foreign-owner result"));
              });
    return chain.thenCompose(
        v -> {
          count("reconcile.independent-stock-custody-transactions");
          return services.tradeStorage(
              store ->
                  store.transaction(
                      tx -> {
                        var outputs = new HashSet<UUID>();
                        for (var replay : replays) {
                          var rows =
                              tx.query(
                                  "SELECT count(*) AS n FROM journal_transactions WHERE world_id=? AND operation_id=?",
                                  List.of(
                                      world.value().toString(),
                                      replay.operation.value().toString()),
                                  1);
                          require(
                              "1".equals(rows.getFirst().get("n")),
                              "Exactly one original transaction per spend");
                          if (replay.trade != null) {
                            var receipt = NpcTrades.receipt(tx::query, replay.trade);
                            require(
                                receipt != null
                                    && receipt.actor().equals(replay.actor.id)
                                    && receipt.trader().equals(replay.actor.trader)
                                    && outputs.add(receipt.outputCustody()),
                                "Distinct original trader receipt/output custody");
                            var output =
                                Custody.find(
                                        tx::query, replay.actor.id, receipt.outputCustody(), false)
                                    .orElseThrow();
                            require(
                                output.state() == Custody.State.COMMITTED
                                    && replay.trade.equals(output.financialOperation()),
                                "Protected output custody bound to original spend");
                          }
                        }
                        for (var actor : actors) {
                          long purchases =
                              replays.stream()
                                  .filter(r -> r.actor == actor && r.trade != null)
                                  .count();
                          var stock = StockStore.trader(tx::query, actor.trader);
                          require(
                              stock.offers().getFirst().remaining()
                                  == TRADER_STOCK - purchases * TRADE_BATCH,
                              "Trader stock decremented once per original");
                        }
                        long receipts =
                            Long.parseLong(
                                tx.query(
                                        "SELECT count(*) AS n FROM npc_trade_receipts",
                                        List.of(),
                                        1)
                                    .getFirst()
                                    .get("n"));
                        require(receipts == outputs.size(), "No unexplained trader receipt");
                        return "20 owners; exact CAD/USD expected balances and immutable journal sums; zero reservations; "
                            + replays.size()
                            + " original transactions/outcomes unchanged; foreign owner reads denied; "
                            + outputs.size()
                            + " distinct protected output custody and stock effects (no physical delivery witness)";
                      }));
        });
  }

  private CompletionStage<java.math.BigInteger> journalSum(
      Actor actor,
      AccountId account,
      EconomyCurrency currency,
      String cursor,
      java.math.BigInteger sum,
      int pages) {
    require(pages < 100, "Bounded fixture history reconciliation");
    count("reconcile.immutable-history-page");
    return services
        .ledger()
        .history(actor.grant, account, cursor, LedgerHistory.MAX_PAGE_SIZE)
        .thenCompose(
            found -> {
              var page = found.orElseThrow();
              var total =
                  sum.add(
                      page.entries().stream()
                          .map(LedgerHistory.Entry::amount)
                          .filter(amount -> amount.currency() == currency)
                          .map(amount -> java.math.BigInteger.valueOf(amount.minorUnits()))
                          .reduce(java.math.BigInteger.ZERO, java.math.BigInteger::add));
              return page.nextCursor() == null
                  ? CompletableFuture.completedStage(total)
                  : journalSum(actor, account, currency, page.nextCursor(), total, pages + 1);
            });
  }

  public void close() {
    live = false;
    if (issuer != null) issuer.disable();
    for (var actor : actors) {
      try {
        BankingAcceptanceProbe.unregister(server, actor.player);
      } catch (Exception error) {
        throw new IllegalStateException(error);
      }
    }
  }
}
