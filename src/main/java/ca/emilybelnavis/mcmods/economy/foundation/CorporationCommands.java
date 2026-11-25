package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.banking.BankingController;
import ca.emilybelnavis.mcmods.economy.banking.BankingMenu;
import ca.emilybelnavis.mcmods.economy.banking.BankingSaves;
import ca.emilybelnavis.mcmods.economy.corporation.Corporations;
import ca.emilybelnavis.mcmods.economy.corporation.Managers;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;

/** Direct-player reviews for the native founder service. */
public final class CorporationCommands {
  private static final int PAGE = 8;
  private static final int MAX_REVIEWS_PER_SESSION = 128;
  private static final long REVIEW_LIFETIME = Duration.ofMinutes(2).toNanos();
  private static final String LAST_RECEIPT = "economy_founder_last_receipt";
  private static final String PENDING = "economy_founder_pending";
  private static final String PREFIX = "/economy corp ";
  private static final Map<ServerPlayer, Review> REVIEWS =
      Collections.synchronizedMap(new WeakHashMap<>());

  @FunctionalInterface
  private interface Submit {
    CompletionStage<Journal.Outcome> apply(
        CorporationService service, OperationId id, RequestId attempt);
  }

  private record Review(
      UUID id,
      UUID session,
      WeakReference<ServerGamePacketListenerImpl> connection,
      long deadline,
      Submit submit) {}

  private final CommandContext<CommandSourceStack> ctx;
  private final ServerPlayer player;
  private final BankingMenu office;
  private UUID selectedCompany;
  private final ServerServices services;
  private final CorporationService founder;
  private final ServerGamePacketListenerImpl connection;

  private CorporationCommands(CommandContext<CommandSourceStack> ctx) {
    this(ctx.getSource().getPlayer(), null, ctx);
  }

  CorporationCommands(
      ServerPlayer player, BankingMenu office, CommandContext<CommandSourceStack> ctx) {
    this.ctx = ctx;
    this.office = office;
    this.player = player;
    services = Economy.serverServices(player.server);
    if (services == null || services.state() != ServerServices.State.READY)
      throw new IllegalStateException("Corporation service unavailable.");
    connection = player.connection;
    founder = new CorporationService(player);
  }

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    var root = Commands.literal("corp").requires(CorporationCommands::direct);
    root.then(
        Commands.literal("gui")
            .executes(c -> run(c, x -> BankingController.openCorporation(x.player))));
    root.then(
        Commands.literal("list")
            .executes(c -> run(c, x -> x.list(null)))
            .then(uuid("after").executes(c -> run(c, x -> x.list(x.id("after"))))));
    root.then(
        Commands.literal("managers")
            .then(
                Commands.argument("company", StringArgumentType.string())
                    .executes(c -> run(c, x -> x.managers(null)))
                    .then(uuid("after").executes(c -> run(c, x -> x.managers(x.id("after")))))));
    root.then(Commands.literal("funding").executes(c -> run(c, CorporationCommands::funding)));
    root.then(
        Commands.literal("register")
            .then(
                uuid("account")
                    .then(
                        Commands.argument("currency", StringArgumentType.word())
                            .then(
                                Commands.argument("quantity", LongArgumentType.longArg(1))
                                    .then(
                                        Commands.argument("price", StringArgumentType.word())
                                            .then(
                                                Commands.argument(
                                                        "name", StringArgumentType.greedyString())
                                                    .executes(
                                                        c ->
                                                            run(
                                                                c,
                                                                x ->
                                                                    x.registration(
                                                                        x.id("account"),
                                                                        x.arg("name"),
                                                                        CorporationOffice
                                                                            .shareIssue(
                                                                                Long.toString(
                                                                                    LongArgumentType
                                                                                        .getLong(
                                                                                            c,
                                                                                            "quantity")),
                                                                                x.arg("price"),
                                                                                x.arg(
                                                                                    "currency")))))))))));
    root.then(
        Commands.literal("rename")
            .then(
                Commands.argument("company", StringArgumentType.string())
                    .then(
                        Commands.argument("name", StringArgumentType.greedyString())
                            .executes(
                                c ->
                                    run(
                                        c,
                                        x ->
                                            x.lifecycle(
                                                Corporations.Action.RENAME, x.arg("name")))))));
    root.then(lifecycle("wind-down", Corporations.Action.WIND_DOWN));
    root.then(lifecycle("resume", Corporations.Action.CANCEL));
    root.then(lifecycle("dissolve", Corporations.Action.DISSOLVE));
    root.then(manager("appoint", Managers.Mutation.APPOINT));
    root.then(manager("permissions", Managers.Mutation.CHANGE));
    root.then(manager("remove", Managers.Mutation.REVOKE));
    root.then(
        Commands.literal("approve")
            .then(uuid("operation").executes(c -> run(c, CorporationCommands::approve))));
    root.then(
        Commands.literal("receipt")
            .executes(c -> run(c, x -> x.receipt(x.pending())))
            .then(uuid("operation").executes(c -> run(c, x -> x.receipt(x.id("operation"))))));
    dispatcher.register(Commands.literal("economy").then(root));
  }

  private static LiteralArgumentBuilder<CommandSourceStack> lifecycle(
      String name, Corporations.Action action) {
    return Commands.literal(name)
        .then(
            Commands.argument("company", StringArgumentType.string())
                .executes(c -> run(c, x -> x.lifecycle(action, ""))));
  }

  private static LiteralArgumentBuilder<CommandSourceStack> manager(
      String name, Managers.Mutation action) {
    var revision = Commands.argument("membership-revision", LongArgumentType.longArg(0));
    switch (action) {
      case APPOINT ->
          revision
              .executes(
                  c ->
                      run(
                          c,
                          x ->
                              x.changeManager(
                                  action,
                                  LongArgumentType.getLong(c, "membership-revision"),
                                  "view",
                                  "all")))
              .then(
                  Commands.argument("scope", StringArgumentType.greedyString())
                      .executes(
                          c ->
                              run(
                                  c,
                                  x ->
                                      x.changeManager(
                                          action,
                                          LongArgumentType.getLong(c, "membership-revision"),
                                          "view",
                                          x.arg("scope")))));
      case CHANGE ->
          revision.then(
              Commands.argument("capabilities", StringArgumentType.string())
                  .then(
                      Commands.argument("scope", StringArgumentType.greedyString())
                          .executes(
                              c ->
                                  run(
                                      c,
                                      x ->
                                          x.changeManager(
                                              action,
                                              LongArgumentType.getLong(c, "membership-revision"),
                                              x.arg("capabilities"),
                                              x.arg("scope"))))));
      case REVOKE ->
          revision.executes(
              c ->
                  run(
                      c,
                      x ->
                          x.changeManager(
                              action,
                              LongArgumentType.getLong(c, "membership-revision"),
                              "none",
                              "none")));
    }
    var manager = uuid("manager").then(revision);
    if (action == Managers.Mutation.APPOINT)
      manager.executes(c -> run(c, x -> x.changeManager(action, 0, "view", "all")));
    return Commands.literal(name)
        .then(Commands.argument("company", StringArgumentType.string()).then(manager));
  }

  private static RequiredArgumentBuilder<CommandSourceStack, UUID> uuid(String name) {
    return Commands.argument(name, UuidArgument.uuid());
  }

  private static boolean direct(CommandSourceStack source) {
    return source.getPlayer() != null && source.source == source.getPlayer();
  }

  private static int run(
      CommandContext<CommandSourceStack> ctx, Consumer<CorporationCommands> command) {
    if (!direct(ctx.getSource())) return 0;
    try {
      var handler = new CorporationCommands(ctx);
      if (ctx.getNodes().stream().anyMatch(n -> n.getNode().getName().equals("company"))) {
        handler.show(
            handler.founder.resolve(handler.arg("company")),
            found -> {
              handler.selectedCompany =
                  found
                      .orElseThrow(() -> new IllegalArgumentException("Company unavailable."))
                      .id();
              command.accept(handler);
            });
      } else command.accept(handler);
      return 1;
    } catch (IllegalArgumentException | IllegalStateException ex) {
      ctx.getSource()
          .sendFailure(
              Component.literal(Objects.toString(ex.getMessage(), "Request unavailable.")));
      return 0;
    }
  }

  private UUID id(String key) {
    if (key.equals("company")) return Objects.requireNonNull(selectedCompany);
    return UuidArgument.getUuid(ctx, key);
  }

  private String arg(String key) {
    return StringArgumentType.getString(ctx, key);
  }

  private void say(String text) {
    if (office == null) player.sendSystemMessage(Component.literal(text));
    else if (office.valid(player)) CorporationOffice.result(office, text);
  }

  private <T> void show(CompletionStage<T> result, Consumer<T> success) {
    long revision = office == null ? -1 : office.revision();
    result.whenCompleteAsync(
        (value, error) -> {
          if (player.connection != connection
              || player.server.getPlayerList().getPlayer(player.getUUID()) != player
              || Economy.serverServices(player.server) != services
              || services.state() != ServerServices.State.READY
              || office != null && (!office.valid(player) || office.revision() != revision)) return;
          if (error != null) {
            say(
                office != null
                    ? "Unable to load this request. You can check Latest receipt in More options."
                    : "Corporation request unavailable. Check an outstanding operation with "
                        + PREFIX
                        + "receipt.");
            return;
          }
          try {
            success.accept(value);
          } catch (IllegalArgumentException | IllegalStateException invalid) {
            say(Objects.toString(invalid.getMessage(), "Invalid corporation request."));
          }
        },
        player.server);
  }

  private <T> void showCompanySnapshot(CompletionStage<T> result, Consumer<T> success) {
    var stamp = CorporateBanking.audienceStamp(services);
    show(
        result,
        value -> {
          if (Objects.equals(stamp, CorporateBanking.audienceStamp(services)))
            success.accept(value);
          else say("Company information changed. Request the current details again.");
        });
  }

  private static String company(Corporations.Company company) {
    return company.name()
        + " ("
        + company.identifier()
        + "), "
        + company.state()
        + ", revision "
        + company.revision();
  }

  private void list(UUID after) {
    showCompanySnapshot(
        founder.companies(after, PAGE + 1),
        rows -> {
          if (rows.isEmpty()) say("No companies on this page.");
          rows.stream().limit(PAGE).forEach(row -> say(company(row)));
          if (rows.size() > PAGE) say("Next page: " + PREFIX + "list " + rows.get(PAGE - 1).id());
        });
  }

  private void managers(UUID after) {
    var company = id("company");
    showCompanySnapshot(
        founder.managers(company, after, PAGE + 1),
        result -> {
          var rows = result.orElseThrow(() -> new IllegalArgumentException("Company unavailable."));
          if (rows.isEmpty()) say("No managers on this page.");
          rows.stream()
              .limit(PAGE)
              .forEach(
                  row ->
                      say(
                          row.manager()
                              + ", revision "
                              + row.revision()
                              + (row.active() ? ", active, " : ", removed, ")
                              + rights(row.rights())));
          if (rows.size() > PAGE)
            say(
                "Next page: "
                    + PREFIX
                    + "managers "
                    + StringArgumentType.escapeIfRequired(arg("company"))
                    + " "
                    + rows.get(PAGE - 1).manager());
        });
  }

  private void funding() {
    show(
        founder.fundingAccounts(),
        rows -> {
          if (rows.isEmpty())
            say("No open personal bank accounts. Visit a bank kiosk to open one.");
          for (var account : rows) {
            String id = account.id().value().toString();
            suggest(
                "Personal "
                    + account.currency().code()
                    + " account "
                    + id
                    + (account.primary() ? " (primary)" : ""),
                PREFIX + "gui");
          }
        });
  }

  private void suggest(String text, String command) {
    player.sendSystemMessage(
        Component.literal(text)
            .withStyle(
                style ->
                    style.withClickEvent(
                        new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))));
  }

  void registration(UUID account, String name, Corporations.ShareIssue issue) {
    show(
        founder.quoteRegistration(new AccountId(account)),
        result -> {
          var fee =
              result.orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "No eligible registration fee quote for this personal account."));
          var command = new Corporations.Register(name, fee, issue);
          offer(
              "Register "
                  + command.name()
                  + "\n"
                  + CorporationOffice.shareTerms(issue)
                  + "\nRegistration fee today: "
                  + fee.currency().code()
                  + " "
                  + new Money(fee.currency(), fee.minor()).majorUnits().toPlainString()
                  + " from your "
                  + fee.currency().code()
                  + " account.",
              (service, op, attempt) -> service.submit(op, attempt, command),
              true);
        });
  }

  private void lifecycle(Corporations.Action action, String name) {
    lifecycle(id("company"), action, name);
  }

  void issue(UUID id, Corporations.ShareIssue issue) {
    showCompanySnapshot(
        founder.find(id),
        found -> {
          var company =
              found.orElseThrow(() -> new IllegalArgumentException("Company unavailable."));
          var command = new Corporations.Issue(company.id(), company.revision(), issue);
          offer(
              "Issue " + CorporationOffice.shareTerms(issue) + " for " + company.name() + ".",
              (service, op, attempt) -> service.submit(op, attempt, command));
        });
  }

  void lifecycle(UUID companyId, Corporations.Action action, String name) {
    showCompanySnapshot(
        founder.find(companyId),
        result -> {
          var company =
              result.orElseThrow(() -> new IllegalArgumentException("Company unavailable."));
          var command = new Corporations.Change(company.id(), company.revision(), action, name);
          String effect =
              switch (action) {
                case RENAME ->
                    "Rename to “" + command.name() + "”. The old name becomes available.";
                case WIND_DOWN ->
                    "Begin wind-down. The company can no longer open accounts or borrow money.";
                case CANCEL -> "Cancel wind-down and restore active status.";
                case DISSOLVE ->
                    "Permanently dissolve the company if all closure requirements are met. Its"
                        + " identity and history remain.";
                default -> throw new IllegalArgumentException("Invalid lifecycle action.");
              };
          offer(
              company.name() + "\n" + effect,
              (service, op, attempt) -> service.submit(op, attempt, command));
        });
  }

  private static String rights(Managers.Rights rights) {
    return "capabilities "
        + rights.capabilities().stream().map(Enum::name).sorted().toList()
        + ", scope "
        + (rights.scope().allAccounts() ? "all company accounts" : rights.scope().accounts());
  }

  private void changeManager(
      Managers.Mutation action, long revision, String capabilityText, String scopeText) {
    changeManager(id("company"), id("manager"), action, revision, capabilityText, scopeText);
  }

  void changeManager(
      UUID companyId,
      UUID manager,
      Managers.Mutation action,
      long revision,
      String capabilityText,
      String scopeText) {
    var capabilities = EnumSet.noneOf(Managers.Capability.class);
    if (!capabilityText.equals("none")) {
      for (String token : capabilityText.split(",", -1))
        capabilities.add(
            switch (token) {
              case "view" -> Managers.Capability.VIEW_HISTORY;
              case "pay" -> Managers.Capability.PAY_TRANSFER;
              case "accounts" -> Managers.Capability.MANAGE_ACCOUNTS;
              case "borrow" -> Managers.Capability.BORROW;
              default ->
                  throw new IllegalArgumentException("Use view, pay, accounts, borrow or none.");
            });
    }
    var scope =
        switch (scopeText) {
          case "all" -> Managers.Scope.all();
          case "none" -> new Managers.Scope(false, Set.of());
          default ->
              new Managers.Scope(
                  false,
                  Arrays.stream(scopeText.split(",", -1))
                      .map(UUID::fromString)
                      .collect(Collectors.toSet()));
        };
    var rights = new Managers.Rights(capabilities, scope);
    showCompanySnapshot(
        founder.find(companyId),
        result -> {
          var company =
              result.orElseThrow(() -> new IllegalArgumentException("Company unavailable."));
          boolean mayPay = capabilities.contains(Managers.Capability.PAY_TRANSFER);
          var change =
              new Managers.Change(
                  company.id(), company.revision(), manager, revision, action, rights, mayPay);
          var who = CorporationOffice.playerName(player, manager);
          String description =
              switch (action) {
                case REVOKE -> "Remove " + who + " as a manager of " + company.name() + "?";
                case APPOINT -> "Add " + who + " as a manager of " + company.name() + "?";
                case CHANGE -> "Change permissions for " + who + " at " + company.name() + "?";
              };
          if (action != Managers.Mutation.REVOKE) {
            var abilities =
                capabilities.stream()
                    .map(
                        capability ->
                            switch (capability) {
                              case VIEW_HISTORY -> "view history";
                              case PAY_TRANSFER -> "make payments";
                              case MANAGE_ACCOUNTS -> "manage accounts";
                              case BORROW -> "borrow money";
                            })
                    .toList();
            description +=
                "\nAccess: "
                    + (abilities.isEmpty()
                        ? "No banking permissions"
                        : String.join(", ", abilities));
            if (scope.allAccounts()) description += "\nApplies to all company accounts.";
            else if (office != null) {
              var names =
                  office.choices().stream()
                      .filter(
                          c ->
                              scope.accounts().stream()
                                  .anyMatch(id -> c.value().equals("scope:" + id)))
                      .map(c -> c.label())
                      .toList();
              if (names.size() != scope.accounts().size())
                throw new IllegalArgumentException("Select accounts from the list.");
              description += "\nAccounts: " + (names.isEmpty() ? "None" : String.join(", ", names));
            } else description += "\nAccounts: " + scope.accounts();
            if (mayPay) description += "\n" + Managers.PAYMENT_DISCLOSURE;
          }
          offer(description, (service, op, attempt) -> service.updateManager(op, attempt, change));
        });
  }

  private CompoundTag persisted() {
    var data = player.getPersistentData();
    if (!data.contains(Player.PERSISTED_NBT_TAG))
      data.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
    return data.getCompound(Player.PERSISTED_NBT_TAG);
  }

  UUID pending() {
    var tag = persisted();
    return tag.hasUUID(PENDING) ? tag.getUUID(PENDING) : null;
  }

  private void offer(String description, Submit submit) {
    offer(description, submit, false);
  }

  private void offer(String description, Submit submit, boolean registration) {
    if (pending() != null) {
      if (office != null) receipt(pending());
      else say("Resolve your previous operation first: " + PREFIX + "receipt " + pending());
      return;
    }
    var id = UUID.randomUUID();
    synchronized (REVIEWS) {
      long now = System.nanoTime();
      REVIEWS
          .values()
          .removeIf(review -> now - review.deadline() >= 0 || review.connection().get() == null);
      if (!REVIEWS.containsKey(player)
          && REVIEWS.values().stream()
                  .filter(review -> review.session().equals(services.sessionId()))
                  .count()
              >= MAX_REVIEWS_PER_SESSION) {
        say("Corporation review capacity reached. Try again shortly.");
        return;
      }
      REVIEWS.put(
          player,
          new Review(
              id,
              services.sessionId(),
              new WeakReference<>(connection),
              System.nanoTime() + REVIEW_LIFETIME,
              submit));
    }
    if (office != null) {
      if (office.valid(player)) CorporationOffice.review(office, id, description, registration);
      return;
    }
    say(description);
    String approve = PREFIX + "approve " + id;
    suggest("To approve within two minutes, enter " + approve, approve);
  }

  private void approve() {
    approve(id("operation"));
  }

  void approve(UUID id) {
    if (id.equals(pending())) {
      receipt(id);
      return;
    }
    if (pending() != null) {
      if (office != null) receipt(pending());
      else say("Resolve your previous operation first: " + PREFIX + "receipt " + pending());
      return;
    }
    var review = REVIEWS.get(player);
    if (review == null || !review.id().equals(id)) {
      receipt(id);
      return;
    }
    if (!review.session().equals(services.sessionId())
        || review.connection().get() != connection
        || System.nanoTime() - review.deadline() >= 0)
      throw new IllegalArgumentException("Review unavailable. Request a new review.");
    REVIEWS.remove(player);
    persisted().putUUID(PENDING, id);
    try {
      BankingSaves.player(player, services.partyWorld(), id);
    } catch (RuntimeException saveFailure) {
      persisted().remove(PENDING);
      say("The request could not be saved. Please try again.");
      return;
    }
    if (office != null) say("Saving company changes…");
    else
      say(
          "Management operation "
              + id
              + ". Recover the original outcome with "
              + PREFIX
              + "receipt "
              + id);
    CompletionStage<Journal.Outcome> result;
    try {
      result =
          review.submit().apply(founder, new OperationId(id), new RequestId(UUID.randomUUID()));
    } catch (RuntimeException admissionFailure) {
      say(
          office == null
              ? "Admission unavailable. Resolve the saved operation with "
                  + PREFIX
                  + "receipt "
                  + id
              : "Check Latest receipt before trying again.");
      return;
    }
    show(
        result,
        outcome -> {
          if (outcome.status() != Journal.Status.UNKNOWN && id.equals(pending())) {
            persisted().putUUID(LAST_RECEIPT, id);
            persisted().remove(PENDING);
          }
          if (office != null && outcome.status() == Journal.Status.COMMITTED) {
            CorporationOffice.completed(office, outcome.result());
          } else if (outcome.status() == Journal.Status.COMMITTED) {
            receipt(id);
          } else {
            say(CorporationOffice.failureMessage(outcome.result().code()));
          }
        });
  }

  void latestReceipt() {
    var id = pending();
    if (id == null && persisted().hasUUID(LAST_RECEIPT)) id = persisted().getUUID(LAST_RECEIPT);
    if (id == null) say("No recent company receipt.");
    else receipt(id);
  }

  void receipt(UUID operation) {
    if (operation == null) {
      say(
          "No pending management operation. Use "
              + PREFIX
              + "receipt <operation> for an earlier receipt.");
      return;
    }
    show(
        founder.receipt(operation),
        result -> {
          if (operation.equals(pending()) && result.isPresent()) {
            persisted().putUUID(LAST_RECEIPT, operation);
            persisted().remove(PENDING);
          } else if (operation.equals(pending())) persisted().remove(PENDING);
          if (office != null) {
            say(
                result
                    .map(
                        r ->
                            r.status().equals("COMMITTED")
                                ? switch (r.code()) {
                                  case "MEMBERSHIP_UPDATED" -> "Manager permissions saved.";
                                  case "CORPORATION_SHARES_ISSUED" -> "Class A shares issued.";
                                  case "CORPORATION_WINDING_DOWN" -> "The company is winding down.";
                                  case "CORPORATION_DISSOLVED" -> "The company has closed.";
                                  default -> "Company changes saved.";
                                }
                                : CorporationOffice.failureMessage(r.code()))
                    .orElse("No company receipt found."));
            return;
          }
          say(
              result
                  .map(r -> operation + " · " + r.status() + " · " + r.code() + " " + r.details())
                  .orElse("No durable founder operation with this ID for your player."));
        });
  }
}
