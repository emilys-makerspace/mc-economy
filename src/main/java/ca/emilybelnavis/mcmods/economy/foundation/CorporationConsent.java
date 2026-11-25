package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.OperationId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.RequestId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.banking.BankingMenu;
import ca.emilybelnavis.mcmods.economy.banking.BankingNetwork;
import ca.emilybelnavis.mcmods.economy.corporation.CorporateAccounts;
import ca.emilybelnavis.mcmods.economy.corporation.Corporations;
import ca.emilybelnavis.mcmods.economy.corporation.IntegrationBindings;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * One reviewed integration request per connected player, admitted by an explicit player command.
 */
public final class CorporationConsent {
  private static final long REVIEW_LIFETIME = Duration.ofMinutes(2).toNanos();
  private static final long LOOKUP_LIFETIME = Duration.ofMinutes(10).toNanos();
  private static final int MAX_PER_SERVER = 128;
  private static final Map<MinecraftServer, Map<UUID, Review>> REVIEWS =
      Collections.synchronizedMap(new IdentityHashMap<>());

  private static final Map<BankingMenu, UUID> DISPLAYED = new WeakHashMap<>();

  private CorporationConsent() {}

  private record Review(
      UUID token,
      ServerPlayer player,
      ServerGamePacketListenerImpl connection,
      ServerServices services,
      OperationId operation,
      IntegrationBindings.Command command,
      BooleanSupplier providerCurrent,
      long deadline,
      boolean submitted) {
    boolean live() {
      return player.connection == connection
          && player.server.getPlayerList().getPlayer(player.getUUID()) == player
          && Economy.serverServices(player.server) == services
          && services.state() == ServerServices.State.READY;
    }

    Review submit() {
      return new Review(
          token,
          player,
          connection,
          services,
          operation,
          command,
          providerCurrent,
          System.nanoTime() + LOOKUP_LIFETIME,
          true);
    }
  }

  public static boolean offer(
      ServerPlayer founder,
      ServerServices services,
      UUID operation,
      IntegrationBindings.Command command,
      BooleanSupplier providerCurrent) {
    Objects.requireNonNull(operation);
    Objects.requireNonNull(command);
    Objects.requireNonNull(providerCurrent);
    if (command instanceof IntegrationBindings.Revoke
        || grant(founder, services) == null
        || !providerCurrent.getAsBoolean()) return false;
    var reviews = reviews(founder.server);
    var prior = reviews.get(founder.getUUID());
    if ((prior != null && !prior.submitted())
        || (prior == null && reviews.size() >= MAX_PER_SERVER)) return false;

    var review =
        new Review(
            UUID.randomUUID(),
            founder,
            founder.connection,
            services,
            new OperationId(operation),
            command,
            providerCurrent,
            System.nanoTime() + REVIEW_LIFETIME,
            false);
    reviews.put(founder.getUUID(), review);
    var company = company(command);
    if (company == null) announce(review);
    else
      new CorporationService(founder)
          .profile(company)
          .thenAccept(
              profile -> {
                if (reviews(founder.server).get(founder.getUUID()) == review && profile.isPresent())
                  announce(review);
              });
    return true;
  }

  private static void announce(Review review) {
    message(
        review.player(),
        review.command().key().provider()
            + " requested access. Open /economy corp gui, then More options → Connected mods, to review it.");
  }

  private static UUID company(IntegrationBindings.Command command) {
    return switch (command) {
      case IntegrationBindings.Bind bind -> bind.company();
      case IntegrationBindings.Approve approve -> approve.company();
      case IntegrationBindings.Revoke revoke -> revoke.company();
      case IntegrationBindings.Create ignored -> null;
    };
  }

  private static List<String> details(
      IntegrationBindings.Command command, String company, List<String> accounts) {
    var lines = new ArrayList<String>();
    var key = command.key();
    lines.add("Requested by " + key.provider());
    switch (command) {
      case IntegrationBindings.Create create -> {
        var registration = create.registration();
        var fee = registration.fee();
        lines.add("Register " + registration.name() + " with you as owner.");
        lines.add(
            "Registration fee: "
                + fee.currency()
                + " "
                + new Money(fee.currency(), fee.minor()).majorUnits().toPlainString()
                + " from your "
                + fee.currency()
                + " account.");
        if (registration.issue() != null)
          lines.add(CorporationOffice.shareTerms(registration.issue()));
        else lines.add("Issue and fund shares before the company starts trading.");
      }
      case IntegrationBindings.Bind bind ->
          lines.add("Connect " + company + " to " + key.externalKey() + "?");
      case IntegrationBindings.Approve approve -> {
        lines.add("Allow access to " + company + " for " + key.externalKey() + "?");
        lines.add(
            "Permissions: "
                + approve.capabilities().stream()
                    .sorted()
                    .map(
                        capability ->
                            switch (capability) {
                              case FIND -> "find company";
                              case ACCOUNTS -> "view accounts";
                              case PAYMENTS -> "make payments";
                              case CREATE -> "create company";
                            })
                    .collect(java.util.stream.Collectors.joining(", ")));
        if (!accounts.isEmpty()) lines.add("Accounts: " + String.join(", ", accounts));
      }
      case IntegrationBindings.Revoke revoke ->
          lines.add("Remove access to " + company + " for " + key.externalKey() + "?");
    }
    return lines;
  }

  public static void stop(MinecraftServer server) {
    REVIEWS.remove(server);
  }

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    var revoke =
        Commands.literal("revoke")
            .then(
                Commands.argument("provider", ResourceLocationArgument.id())
                    .then(
                        Commands.argument("external-key", StringArgumentType.string())
                            .then(
                                Commands.argument("company", StringArgumentType.string())
                                    .then(
                                        Commands.argument(
                                                "company-revision", LongArgumentType.longArg(1))
                                            .then(
                                                Commands.argument(
                                                        "mandate-revision",
                                                        LongArgumentType.longArg(1))
                                                    .executes(CorporationConsent::revoke))))));
    dispatcher.register(
        Commands.literal("economy")
            .then(
                Commands.literal("corp")
                    .requires(CorporationConsent::direct)
                    .then(
                        Commands.literal("confirm")
                            .then(
                                Commands.argument("token", UuidArgument.uuid())
                                    .executes(CorporationConsent::confirm)))
                    .then(
                        Commands.literal("lookup")
                            .then(
                                Commands.argument("token", UuidArgument.uuid())
                                    .executes(CorporationConsent::lookup)))
                    .then(revoke)));
  }

  private static boolean direct(CommandSourceStack source) {
    return source.getPlayer() != null && source.source == source.getPlayer();
  }

  private static int confirm(CommandContext<CommandSourceStack> context) {
    var review = review(context);
    if (review == null || review.submitted() || !review.providerCurrent().getAsBoolean())
      return unavailable(context.getSource());
    return submit(context.getSource(), review);
  }

  private static int revoke(CommandContext<CommandSourceStack> context) {
    var source = context.getSource();
    var player = source.getPlayer();
    var services = Economy.serverServices(source.getServer());
    if (!direct(source) || grant(player, services) == null) return unavailable(source);
    String[] fields = {
      ResourceLocationArgument.getId(context, "provider").toString(),
      StringArgumentType.getString(context, "external-key"),
      StringArgumentType.getString(context, "company"),
      Long.toString(LongArgumentType.getLong(context, "company-revision")),
      Long.toString(LongArgumentType.getLong(context, "mandate-revision"))
    };
    prepareRevoke(player, null, fields);
    return 1;
  }

  private record GrantRow(
      IntegrationBindings.Key key,
      String identifier,
      String companyName,
      String capabilities,
      String companyRevision,
      String mandateRevision) {}

  private static final Map<BankingMenu, List<GrantRow>> GRANTS = new WeakHashMap<>();
  private static final int GRANTS_PER_PAGE = 8;

  static void grants(ServerPlayer player, BankingMenu menu, String action) {
    if (!officeLive(player, menu)) return;
    var services = Economy.serverServices(player.server);
    var owner = grant(player, services);
    if (owner == null) {
      notify(player, menu, "Corporation office unavailable.");
      return;
    }
    var previous = GRANTS.getOrDefault(menu, List.of());
    var after =
        action.equals("grants_next") && !previous.isEmpty() ? previous.getLast().key() : null;
    String founder = player.getUUID().toString();
    long revision = menu.revision();
    menu.setBusy(true);
    services
        .readAccounts(
            owner,
            false,
            reads ->
                java.util.Optional.of(
                    reads
                        .query(
                            """
        SELECT m.provider,m.external_key,m.capabilities,m.revision AS mandate_revision,
               c.name,r.identifier,c.revision AS company_revision
        FROM corporations c
        JOIN corporation_registry r ON r.party=c.id
        JOIN corporation_integration_bindings b ON b.company=c.id
        JOIN corporation_integration_mandates m ON m.provider=b.provider AND m.external_key=b.external_key
        LEFT JOIN corporation_integration_epochs e ON e.company=c.id
        WHERE c.founder=? AND c.state='ACTIVE' AND m.active=1 AND m.epoch=COALESCE(e.epoch,0)
          AND (m.provider,m.external_key)>(?,?)
        ORDER BY m.provider,m.external_key LIMIT ?
        """,
                            List.of(
                                founder,
                                after == null ? "" : after.provider(),
                                after == null ? "" : after.externalKey(),
                                Integer.toString(GRANTS_PER_PAGE + 1)),
                            GRANTS_PER_PAGE + 1)
                        .stream()
                        .map(
                            row ->
                                new GrantRow(
                                    new IntegrationBindings.Key(
                                        row.get("provider"), row.get("external_key")),
                                    row.get("identifier"),
                                    row.get("name"),
                                    row.get("capabilities"),
                                    row.get("company_revision"),
                                    row.get("mandate_revision")))
                        .toList()))
        .whenCompleteAsync(
            (result, error) -> {
              if (!officeLive(player, menu) || menu.revision() != revision) return;
              if (grant(player, services) == null || error != null || result.isEmpty()) {
                notify(player, menu, "Could not load integration permissions.");
                return;
              }
              var rows = result.orElseThrow();
              var displayed = rows.stream().limit(GRANTS_PER_PAGE).toList();
              GRANTS.put(menu, displayed);
              var choices = new ArrayList<BankingNetwork.Choice>();
              for (int i = 0; i < displayed.size(); i++) {
                var row = displayed.get(i);
                String label =
                    row.companyName()
                        + " · "
                        + row.capabilities().toLowerCase(java.util.Locale.ROOT).replace(",", ", ")
                        + " · "
                        + row.key().provider()
                        + " / "
                        + row.key().externalKey();
                choices.add(
                    new BankingNetwork.Choice(
                        "office_pick",
                        "grant:" + i,
                        label.length() <= 128 ? label : label.substring(0, 125) + "…",
                        false));
              }
              if (rows.size() > GRANTS_PER_PAGE)
                choices.add(officeChoice("grants_next", "Next permissions"));
              if (after != null) choices.add(officeChoice("grants_first", "First page"));
              choices.add(officeChoice("integrations", "Back to integration permissions"));
              menu.setBusy(false);
              menu.publish(
                  "office_choices",
                  List.of(
                      displayed.isEmpty()
                          ? "No mods have access."
                          : "Choose a connection to manage."),
                  "",
                  choices);
            },
            player.server);
  }

  static void selectGrant(ServerPlayer player, BankingMenu menu, String selection) {
    if (!officeLive(player, menu) || !menu.page().equals("office_choices")) return;
    var rows = GRANTS.getOrDefault(menu, List.of());
    int index = Integer.parseInt(selection.substring("grant:".length()));
    if (index < 0 || index >= rows.size()) return;
    var row = rows.get(index);
    prepareRevoke(
        player,
        menu,
        new String[] {
          row.key().provider(),
          row.key().externalKey(),
          row.identifier(),
          row.companyRevision(),
          row.mandateRevision()
        });
  }

  private static void prepareRevoke(ServerPlayer player, BankingMenu menu, String[] fields) {
    var services = Economy.serverServices(player.server);
    var key = new IntegrationBindings.Key(fields[0], fields[1]);
    long companyRevision = Long.parseLong(fields[3]);
    long mandateRevision = Long.parseLong(fields[4]);
    if (companyRevision < 1 || mandateRevision < 1)
      throw new IllegalArgumentException("Enter positive revision numbers.");
    long revision = menu == null ? -1 : menu.revision();
    if (menu != null) menu.setBusy(true);
    new CorporationService(player)
        .resolve(fields[2])
        .whenCompleteAsync(
            (company, error) -> {
              if (menu != null && (!officeLive(player, menu) || menu.revision() != revision))
                return;
              if (grant(player, services) == null
                  || error != null
                  || company.isEmpty()
                  || company.get().revision() != companyRevision) {
                notify(
                    player,
                    menu,
                    "Corporation or revision unavailable. Refresh the company details.");
                return;
              }
              var reviews = reviews(player.server);
              var prior = reviews.get(player.getUUID());
              if ((prior != null && !prior.submitted())
                  || (prior == null && reviews.size() >= MAX_PER_SERVER)) {
                notify(player, menu, "Another integration request is awaiting review.");
                return;
              }
              var command =
                  new IntegrationBindings.Revoke(
                      key, company.get().id(), companyRevision, mandateRevision);
              var review =
                  new Review(
                      UUID.randomUUID(),
                      player,
                      player.connection,
                      services,
                      new OperationId(UUID.randomUUID()),
                      command,
                      () -> true,
                      System.nanoTime() + REVIEW_LIFETIME,
                      false);
              reviews.put(player.getUUID(), review);
              if (menu == null) submit(player.createCommandSourceStack(), review);
              else office(player, menu);
            },
            player.server);
  }

  private static boolean officeLive(ServerPlayer player, BankingMenu menu) {
    return menu.access() == BankingMenu.Access.CORPORATION && menu.valid(player);
  }

  static void office(ServerPlayer player, BankingMenu menu) {
    if (!officeLive(player, menu)) return;
    var review = reviews(player.server).get(player.getUUID());
    DISPLAYED.remove(menu);
    UUID company = review == null ? null : company(review.command());
    if (company == null) {
      display(menu, review, "", List.of());
      return;
    }
    long revision = menu.revision();
    menu.setBusy(true);
    var auth = grant(player, review.services());
    if (auth == null) {
      notify(player, menu, "Open the corporation office again.");
      return;
    }
    review
        .services()
        .readAccounts(
            auth,
            false,
            reads ->
                Corporations.find(reads, new PlayerId(player.getUUID()), company)
                    .map(
                        found -> {
                          var accounts = new ArrayList<String>();
                          if (review.command() instanceof IntegrationBindings.Approve approval) {
                            CorporateAccounts.list(reads, auth.target().world(), company).stream()
                                .filter(account -> approval.accounts().contains(account.id()))
                                .forEach(
                                    account ->
                                        accounts.add(account.currency().code() + " account"));
                            if (accounts.size() != approval.accounts().size())
                              throw new IllegalStateException("Account changed");
                          }
                          return new ConsentView(found.name(), accounts);
                        }))
        .whenCompleteAsync(
            (view, error) -> {
              if (!officeLive(player, menu) || menu.revision() != revision) return;
              if (error != null
                  || view.isEmpty()
                  || reviews(player.server).get(player.getUUID()) != review) {
                notify(player, menu, "This request changed. Open Connected mods again.");
                return;
              }
              display(menu, review, view.get().company(), view.get().accounts());
            },
            player.server);
  }

  private record ConsentView(String company, List<String> accounts) {}

  private static void display(
      BankingMenu menu, Review review, String identifier, List<String> accounts) {
    menu.setBusy(false);
    var lines = new ArrayList<String>();
    var choices = new ArrayList<BankingNetwork.Choice>();
    if (review == null) lines.add("No requests waiting for approval.");
    else {
      DISPLAYED.put(menu, review.token());
      lines.addAll(details(review.command(), identifier, accounts));
      if (review.submitted()) choices.add(officeChoice("integration_lookup", "Check result"));
      else choices.add(officeChoice("integration_approve", "Approve this request"));
    }
    choices.add(officeChoice("revoke", "Manage access"));
    menu.publish("office_integrations", lines, "", choices);
  }

  private static BankingNetwork.Choice officeChoice(String value, String label) {
    return new BankingNetwork.Choice("office_page", value, label, false);
  }

  static void officeAction(ServerPlayer player, BankingMenu menu, String action) {
    if (!officeLive(player, menu)
        || !menu.page().equals("office_integrations")
        || menu.choices().stream()
            .noneMatch(c -> c.action().equals("office_page") && c.value().equals(action))) return;
    var review = reviews(player.server).get(player.getUUID());
    if (review == null
        || review.player() != player
        || !review.token().equals(DISPLAYED.get(menu))) {
      notify(
          player,
          menu,
          "The integration request expired or changed. Open Integration permissions to review it again.");
      return;
    }
    if (action.equals("integration_lookup") && review.submitted())
      lookup(player.createCommandSourceStack(), review, menu);
    else if (action.equals("integration_approve")
        && !review.submitted()
        && review.providerCurrent().getAsBoolean())
      submit(player.createCommandSourceStack(), review, menu);
    else notify(player, menu, "Integration request unavailable.");
  }

  private static int submit(CommandSourceStack source, Review review) {
    return submit(source, review, null);
  }

  private static int submit(CommandSourceStack source, Review review, BankingMenu menu) {
    var grant = grant(review.player(), review.services());
    if (grant == null || !review.live()) return unavailable(source);
    var submitted = review.submit();
    var services = review.services();
    var command = review.command();
    CorporateBanking.authorityChanging(services);
    var submission =
        services.submitOwned(
            grant,
            IntegrationBindings.kind(command.key()),
            review.operation(),
            attempt(),
            request(command),
            handler(review, grant.target()),
            null);
    reviews(review.player().server).put(review.player().getUUID(), submitted);
    long revision = menu == null ? -1 : menu.revision();
    if (menu != null) menu.setBusy(true);
    if (menu == null)
      message(
          review.player(),
          "Operation "
              + review.operation().value()
              + " · Original outcome lookup /economy corp lookup "
              + review.token());
    submission
        .completion()
        .whenCompleteAsync(
            (delivery, failure) -> {
              if (submitted.live()
                  && (menu == null
                      || officeLive(submitted.player(), menu) && menu.revision() == revision))
                outcome(submitted.player(), menu, failure == null ? delivery.outcome() : null);
            },
            review.player().server);
    return 1;
  }

  private static int lookup(CommandContext<CommandSourceStack> context) {
    var review = review(context);
    if (review == null || !review.submitted()) return unavailable(context.getSource());
    return lookup(context.getSource(), review, null);
  }

  private static int lookup(CommandSourceStack source, Review review, BankingMenu menu) {
    long revision = menu == null ? -1 : menu.revision();
    var grant = grant(review.player(), review.services());
    if (grant == null) return unavailable(source);
    if (menu != null) menu.setBusy(true);
    var command = review.command();
    review
        .services()
        .lookupOwned(
            grant,
            IntegrationBindings.kind(command.key()),
            review.operation(),
            attempt(),
            request(command),
            handler(review, grant.target()))
        .whenCompleteAsync(
            (found, failure) -> {
              if (!review.live()
                  || (menu != null
                      && (!officeLive(review.player(), menu) || menu.revision() != revision)))
                return;
              if (failure != null) outcome(review.player(), menu, null);
              else if (found.isEmpty())
                notify(review.player(), menu, "No durable outcome for this operation.");
              else outcome(review.player(), menu, found.orElseThrow());
            },
            review.player().server);
    return 1;
  }

  private static JournalEngine.Registration handler(Review review, PartyRef owner) {
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!review.providerCurrent().getAsBoolean())
            return new JournalEngine.Reject(
                new Journal.Result(1, "INTEGRATION_PROVIDER_DISABLED", ""));
          var decision =
              IntegrationBindings.prepare(
                  intent, reads, owner, review.command(), review.services()::financialMillis);
          if (!(decision instanceof JournalEngine.Commit commit)) return decision;
          return new JournalEngine.Commit(
              commit.records(),
              commit.result(),
              tx -> {
                if (!review.providerCurrent().getAsBoolean())
                  throw new IllegalStateException(
                      "Integration provider disabled during consent transaction");
                commit.projections().apply(tx);
              });
        });
  }

  private static PartyAuthorization.Grant grant(ServerPlayer player, ServerServices services) {
    if (player == null || services == null || !player.server.isSameThread()) return null;
    var world = services.worldId();
    if (world == null) return null;
    var owner = new PartyRef(world, new PartyRef.Player(new PlayerId(player.getUUID())));
    return PartyAuthorization.player(
            player, server -> Economy.serverServices(server) == services ? services : null, owner)
        .orElse(null);
  }

  private static Map<UUID, Review> reviews(MinecraftServer server) {
    var reviews = REVIEWS.computeIfAbsent(server, ignored -> new HashMap<>());
    long now = System.nanoTime();
    reviews.values().removeIf(entry -> now - entry.deadline() >= 0 || !entry.live());
    return reviews;
  }

  private static Review review(CommandContext<CommandSourceStack> context) {
    var source = context.getSource();
    if (!direct(source)) return null;
    var player = source.getPlayer();
    var review = reviews(player.server).get(player.getUUID());
    return review != null
            && review.player() == player
            && review.live()
            && review.token().equals(UuidArgument.getUuid(context, "token"))
        ? review
        : null;
  }

  private static Journal.CanonicalRequest request(IntegrationBindings.Command command) {
    return new Journal.CanonicalRequest(1, command.fields());
  }

  private static RequestId attempt() {
    return new RequestId(UUID.randomUUID());
  }

  private static int unavailable(CommandSourceStack source) {
    source.sendFailure(Component.literal("Corporation integration request unavailable."));
    return 0;
  }

  private static void message(ServerPlayer player, String text) {
    player.sendSystemMessage(Component.literal(text));
  }

  private static void notify(ServerPlayer player, BankingMenu menu, String text) {
    if (menu == null) message(player, text);
    else CorporationOffice.result(menu, text);
  }

  private static void outcome(ServerPlayer player, BankingMenu menu, Journal.Outcome outcome) {
    if (outcome == null) {
      notify(
          player,
          menu,
          "Outcome unavailable. Open Integration permissions and look up the original outcome.");
      return;
    }
    if (menu != null) {
      notify(
          player,
          menu,
          switch (outcome.status()) {
            case COMMITTED -> "Request completed.";
            case DURABLY_REJECTED, NOT_ACCEPTED ->
                "Request declined. Review the company and its permissions before trying again.";
            case UNKNOWN ->
                "The result is still pending. Open Connected mods and choose Check result.";
          });
      return;
    }
    var result = outcome.result();
    notify(
        player,
        menu,
        "Corporation integration "
            + outcome.status().name()
            + " · "
            + result.code()
            + (result.payload().isEmpty() ? "" : " · " + result.payload()));
  }
}
