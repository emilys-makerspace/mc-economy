package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.Money;
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
  private static final String PENDING = "economy_founder_pending";
  private static final String PREFIX = "/economy corporation ";
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
  private final ServerServices services;
  private final CorporationService founder;
  private final ServerGamePacketListenerImpl connection;

  private CorporationCommands(CommandContext<CommandSourceStack> ctx) {
    this.ctx = ctx;
    player = ctx.getSource().getPlayer();
    services = Economy.serverServices(player.server);
    if (services == null || services.state() != ServerServices.State.READY)
      throw new IllegalStateException("Corporation service unavailable.");
    connection = player.connection;
    founder = new CorporationService(player);
  }

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    var root = Commands.literal("corporation").requires(CorporationCommands::direct);
    root.then(
        Commands.literal("list")
            .executes(c -> run(c, x -> x.list(null)))
            .then(uuid("after").executes(c -> run(c, x -> x.list(x.id("after"))))));
    root.then(
        Commands.literal("managers")
            .then(
                uuid("company")
                    .executes(c -> run(c, x -> x.managers(null)))
                    .then(uuid("after").executes(c -> run(c, x -> x.managers(x.id("after")))))));
    root.then(Commands.literal("funding").executes(c -> run(c, CorporationCommands::funding)));
    root.then(
        Commands.literal("register")
            .then(
                uuid("account")
                    .then(
                        Commands.argument("name", StringArgumentType.greedyString())
                            .executes(c -> run(c, CorporationCommands::registration)))));
    root.then(
        Commands.literal("rename")
            .then(
                uuid("company")
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
        .then(uuid("company").executes(c -> run(c, x -> x.lifecycle(action, ""))));
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
    return Commands.literal(name).then(uuid("company").then(manager));
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
      command.accept(new CorporationCommands(ctx));
      return 1;
    } catch (IllegalArgumentException | IllegalStateException ex) {
      ctx.getSource()
          .sendFailure(
              Component.literal(Objects.toString(ex.getMessage(), "Request unavailable.")));
      return 0;
    }
  }

  private UUID id(String key) {
    return UuidArgument.getUuid(ctx, key);
  }

  private String arg(String key) {
    return StringArgumentType.getString(ctx, key);
  }

  private void say(String text) {
    player.sendSystemMessage(Component.literal(text));
  }

  private <T> void show(CompletionStage<T> result, Consumer<T> success) {
    result.whenCompleteAsync(
        (value, error) -> {
          if (player.connection != connection
              || player.server.getPlayerList().getPlayer(player.getUUID()) != player
              || Economy.serverServices(player.server) != services
              || services.state() != ServerServices.State.READY) return;
          if (error != null) {
            say(
                "Corporation request unavailable. Check an outstanding operation with "
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
        + company.id()
        + "), "
        + company.state()
        + ", revision "
        + company.revision()
        + ", founder "
        + company.founder();
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
                    + company
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
                PREFIX + "register " + id + " ");
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

  private void registration() {
    String name = arg("name");
    show(
        founder.quoteRegistration(new AccountId(id("account"))),
        result -> {
          var fee =
              result.orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "No eligible registration fee quote for this personal account."));
          var command = new Corporations.Register(name, fee);
          offer(
              "Register “"
                  + command.name()
                  + "” with founder "
                  + player.getUUID()
                  + ". Retire "
                  + fee.currency().code()
                  + " "
                  + new Money(fee.currency(), fee.minor()).majorUnits().toPlainString()
                  + " from your personal account "
                  + fee.account().value()
                  + " at revision "
                  + fee.revision()
                  + (fee.publication() == null
                      ? "."
                      : " (CAD 500.00 equivalent, FX publication " + fee.publication() + ")."),
              (service, op, attempt) -> service.submit(op, attempt, command));
        });
  }

  private void lifecycle(Corporations.Action action, String name) {
    showCompanySnapshot(
        founder.find(id("company")),
        result -> {
          var company =
              result.orElseThrow(() -> new IllegalArgumentException("Company unavailable."));
          var command = new Corporations.Change(company.id(), company.revision(), action, name);
          String effect =
              switch (action) {
                case RENAME -> "Rename to “" + command.name() + "”. The old name stays reserved.";
                case WIND_DOWN -> "Begin wind-down. New accounts and exposure stop.";
                case CANCEL -> "Cancel wind-down and restore active status.";
                case DISSOLVE ->
                    "Permanently dissolve the company if all closure requirements are met. Its"
                        + " identity and history remain.";
                default -> throw new IllegalArgumentException("Invalid lifecycle action.");
              };
          offer(
              company(company) + ". " + effect,
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
    UUID manager = id("manager");
    var capabilities = EnumSet.noneOf(Managers.Capability.class);
    if (!capabilityText.equals("none")) {
      for (String token : capabilityText.split(",", -1))
        capabilities.add(
            switch (token) {
              case "view" -> Managers.Capability.VIEW_HISTORY;
              case "pay" -> Managers.Capability.PAY_TRANSFER;
              case "accounts" -> Managers.Capability.MANAGE_ACCOUNTS;
              default -> throw new IllegalArgumentException("Use view, pay, accounts or none.");
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
        founder.find(id("company")),
        result -> {
          var company =
              result.orElseThrow(() -> new IllegalArgumentException("Company unavailable."));
          boolean mayPay = capabilities.contains(Managers.Capability.PAY_TRANSFER);
          var change =
              new Managers.Change(
                  company.id(), company.revision(), manager, revision, action, rights, mayPay);
          offer(
              company(company)
                  + ". "
                  + action
                  + " manager "
                  + manager
                  + " at membership revision "
                  + revision
                  + ". Resulting "
                  + rights(rights)
                  + "."
                  + (mayPay ? " " + Managers.PAYMENT_DISCLOSURE : ""),
              (service, op, attempt) -> service.updateManager(op, attempt, change));
        });
  }

  private CompoundTag persisted() {
    var data = player.getPersistentData();
    if (!data.contains(Player.PERSISTED_NBT_TAG))
      data.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
    return data.getCompound(Player.PERSISTED_NBT_TAG);
  }

  private UUID pending() {
    var tag = persisted();
    return tag.hasUUID(PENDING) ? tag.getUUID(PENDING) : null;
  }

  private void offer(String description, Submit submit) {
    if (pending() != null) {
      say("Resolve your previous operation first: " + PREFIX + "receipt " + pending());
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
    say(description);
    String approve = PREFIX + "approve " + id;
    suggest("To approve within two minutes, enter " + approve, approve);
  }

  private void approve() {
    UUID id = id("operation");
    if (id.equals(pending())) {
      receipt(id);
      return;
    }
    if (pending() != null) {
      say("Resolve your previous operation first: " + PREFIX + "receipt " + pending());
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
      say("The operation was not submitted because the recovery marker could not be saved.");
      return;
    }
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
      say("Admission unavailable. Resolve the saved operation with " + PREFIX + "receipt " + id);
      return;
    }
    show(
        result,
        outcome -> {
          if (outcome.status() != Journal.Status.UNKNOWN && id.equals(pending()))
            persisted().remove(PENDING);
          say(
              outcome.status()
                  + " · "
                  + outcome.result().code()
                  + " "
                  + outcome.result().payload());
        });
  }

  private void receipt(UUID operation) {
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
          if (operation.equals(pending())) persisted().remove(PENDING);
          say(
              result
                  .map(r -> operation + " · " + r.status() + " · " + r.code() + " " + r.details())
                  .orElse("No durable founder operation with this ID for your player."));
        });
  }
}
