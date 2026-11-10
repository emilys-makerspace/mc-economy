package ca.emilybelnavis.mcmods.economy.banking;

import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Bounded transport. Actor identity and original financial operations never come from packet
 * fields.
 */
public final class BankingNetwork {
  public static final int MAX_SNAPSHOT_BYTES = 24 * 1024;
  public static final Set<String> ACTIONS =
      Set.of(
          "home",
          "payments",
          "manage_accounts",
          "setup",
          "accounts",
          "accounts_next",
          "account_select",
          "account_open",
          "account_close_preview",
          "account_close_confirm",
          "history",
          "history_next",
          "fx",
          "exchange_preview",
          "exchange_confirm",
          "bank_retry",
          "deposit",
          "deposit_preview",
          "deposit_confirm",
          "withdraw",
          "withdraw_preview",
          "withdraw_confirm",
          "withdraw_mix",
          "withdraw_mix_change",
          "withdraw_mix_review",
          "withdraw_mix_reset",
          "shop",
          "buy_preview",
          "buy_confirm",
          "claims",
          "claims_next",
          "collect",
          "settings",
          "configure",
          "configure_confirm",
          "banking",
          "price",
          "retry",
          "cancel",
          "branch_open",
          "branch_assign",
          "branch_switch",
          "branch_accept",
          "branch_close",
          "branch_products",
          "brand_preview",
          "brand_confirm",
          "cards",
          "card_select",
          "card_preview",
          "card_confirm",
          "card_lost",
          "credit_profile",
          "credit_history",
          "credit_next",
          "credit_facilities",
          "credit_facilities_next",
          "credit_facility",
          "credit_statements",
          "credit_statements_next");

  private BankingNetwork() {}

  public record Action(int menuId, UUID session, long revision, String action, String value)
      implements CustomPacketPayload {
    public static final Type<Action> PAYLOAD_TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("economy", "banking_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC =
        new StreamCodec<>() {
          @Override
          public Action decode(RegistryFriendlyByteBuf buffer) {
            return new Action(
                buffer.readVarInt(),
                buffer.readUUID(),
                buffer.readVarLong(),
                buffer.readUtf(24),
                buffer.readUtf(2048));
          }

          @Override
          public void encode(RegistryFriendlyByteBuf buffer, Action value) {
            buffer.writeVarInt(value.menuId());
            buffer.writeUUID(value.session());
            buffer.writeVarLong(value.revision());
            buffer.writeUtf(value.action(), 24);
            buffer.writeUtf(value.value(), 2048);
          }
        };

    public Action {
      identity(menuId, session, revision);
      if (!ACTIONS.contains(action)
          && !ca.emilybelnavis.mcmods.economy.foundation.TerminalMenus.ACTIONS.contains(action)
          && !BankingHistoryView.ACTIONS.contains(action)
          && !FinanceActions.handles(action)) throw new IllegalArgumentException("BANKING_ACTION");
      text(value, 2048, 2048);
    }

    @Override
    public Type<Action> type() {
      return PAYLOAD_TYPE;
    }
  }

  public record Snapshot(
      int menuId,
      UUID session,
      long revision,
      String page,
      List<String> lines,
      String status,
      boolean busy,
      List<Choice> choices,
      BankingOperation.View operation,
      BankingDashboardView dashboard,
      BankingHistoryView history)
      implements CustomPacketPayload {
    public Snapshot(
        int menuId,
        UUID session,
        long revision,
        String page,
        List<String> lines,
        String status,
        boolean busy,
        List<Choice> choices,
        BankingOperation.View operation,
        BankingDashboardView dashboard) {
      this(
          menuId, session, revision, page, lines, status, busy, choices, operation, dashboard,
          null);
    }

    public Snapshot(
        int menuId,
        UUID session,
        long revision,
        String page,
        List<String> lines,
        String status,
        boolean busy,
        List<Choice> choices) {
      this(
          menuId,
          session,
          revision,
          page,
          lines,
          status,
          busy,
          choices,
          BankingOperation.offered(busy, choices),
          null);
    }

    public Snapshot(
        int menuId,
        UUID session,
        long revision,
        String page,
        List<String> lines,
        String status,
        boolean busy) {
      this(menuId, session, revision, page, lines, status, busy, List.of());
    }

    public static final Type<Snapshot> PAYLOAD_TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("economy", "banking_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC =
        new StreamCodec<>() {
          @Override
          public Snapshot decode(RegistryFriendlyByteBuf buffer) {
            if (buffer.readableBytes() > MAX_SNAPSHOT_BYTES)
              throw new IllegalArgumentException("BANKING_PAYLOAD_BUDGET");
            int menu = buffer.readVarInt();
            UUID session = buffer.readUUID();
            long revision = buffer.readVarLong();
            String page = buffer.readUtf(24);
            int count = buffer.readVarInt();
            if (count < 0 || count > 32)
              throw new IllegalArgumentException("BANKING_SNAPSHOT_LINES");
            var lines = new ArrayList<String>(count);
            for (int index = 0; index < count; index++) lines.add(buffer.readUtf(256));
            String status = buffer.readUtf(256);
            boolean busy = buffer.readBoolean();
            int choiceCount = buffer.readVarInt();
            if (choiceCount < 0 || choiceCount > 32)
              throw new IllegalArgumentException("BANKING_CHOICES");
            var choices = new ArrayList<Choice>();
            for (int index = 0; index < choiceCount; index++)
              choices.add(
                  new Choice(
                      buffer.readUtf(24),
                      buffer.readUtf(128),
                      buffer.readUtf(128),
                      buffer.readBoolean()));
            var operation = BankingOperation.View.read(buffer);
            var dashboard = buffer.readBoolean() ? BankingDashboardView.read(buffer) : null;
            var history = buffer.readBoolean() ? BankingHistoryView.read(buffer) : null;
            return new Snapshot(
                menu, session, revision, page, lines, status, busy, choices, operation, dashboard,
                history);
          }

          @Override
          public void encode(RegistryFriendlyByteBuf buffer, Snapshot value) {
            var bounded =
                new RegistryFriendlyByteBuf(
                    io.netty.buffer.Unpooled.buffer(256, MAX_SNAPSHOT_BYTES),
                    buffer.registryAccess());
            try {
              encodeBody(bounded, value);
              buffer.writeBytes(bounded);
            } catch (IndexOutOfBoundsException tooLarge) {
              throw new IllegalArgumentException("BANKING_PAYLOAD_BUDGET", tooLarge);
            } finally {
              bounded.release();
            }
          }

          private void encodeBody(RegistryFriendlyByteBuf buffer, Snapshot value) {
            buffer.writeVarInt(value.menuId());
            buffer.writeUUID(value.session());
            buffer.writeVarLong(value.revision());
            buffer.writeUtf(value.page(), 24);
            buffer.writeVarInt(value.lines().size());
            for (var line : value.lines()) buffer.writeUtf(line, 256);
            buffer.writeUtf(value.status(), 256);
            buffer.writeBoolean(value.busy());
            buffer.writeVarInt(value.choices().size());
            for (var choice : value.choices()) {
              buffer.writeUtf(choice.action(), 24);
              buffer.writeUtf(choice.value(), 128);
              buffer.writeUtf(choice.label(), 128);
              buffer.writeBoolean(choice.selected());
            }
            value.operation().write(buffer);
            buffer.writeBoolean(value.dashboard() != null);
            if (value.dashboard() != null) value.dashboard().write(buffer);
            buffer.writeBoolean(value.history() != null);
            if (value.history() != null) value.history().write(buffer);
          }
        };

    public Snapshot {
      identity(menuId, session, revision);
      Objects.requireNonNull(operation);
      if (page == null || !page.matches("[a-z_]{1,24}"))
        throw new IllegalArgumentException("BANKING_PAGE");
      Objects.requireNonNull(lines);
      if (lines.size() > 32) throw new IllegalArgumentException("BANKING_SNAPSHOT_LINES");
      int bytes = 0;
      for (var line : lines) {
        text(line, 256, 1024);
        bytes += line.getBytes(StandardCharsets.UTF_8).length;
      }
      text(status, 256, 1024);
      bytes += status.getBytes(StandardCharsets.UTF_8).length;
      if (bytes > 12_000) throw new IllegalArgumentException("BANKING_SNAPSHOT_BUDGET");
      int maximumEncoded =
          bytes
              + 512
              + lines.size() * 5
              + choices.size() * 16
              + operation.reasonKey().getBytes(StandardCharsets.UTF_8).length;
      for (var argument : operation.args())
        maximumEncoded += 16 + argument.value().getBytes(StandardCharsets.UTF_8).length;
      if (dashboard != null) maximumEncoded += BankingDashboardView.MAX_ENCODED_BYTES;
      if (history != null) maximumEncoded += history.encodedSize();
      if (maximumEncoded > MAX_SNAPSHOT_BYTES)
        throw new IllegalArgumentException("BANKING_PAYLOAD_BUDGET");
      lines = List.copyOf(lines);
      choices = List.copyOf(choices);
      if (choices.size() > 32) throw new IllegalArgumentException("BANKING_CHOICES");
      for (var choice : choices)
        bytes +=
            (choice.action() + choice.value() + choice.label())
                .getBytes(StandardCharsets.UTF_8)
                .length;
      if (bytes > 12_000) throw new IllegalArgumentException("BANKING_SNAPSHOT_BUDGET");
      maximumEncoded +=
          choices.stream()
              .mapToInt(
                  c -> (c.action() + c.value() + c.label()).getBytes(StandardCharsets.UTF_8).length)
              .sum();
      if (maximumEncoded > MAX_SNAPSHOT_BYTES)
        throw new IllegalArgumentException("BANKING_PAYLOAD_BUDGET");
    }

    @Override
    public Type<Snapshot> type() {
      return PAYLOAD_TYPE;
    }
  }

  /** Server-offered control. Destination choices are selected locally before requesting a quote. */
  public record Choice(String action, String value, String label, boolean selected) {
    public Choice {
      if (!ACTIONS.contains(action)
          && !ca.emilybelnavis.mcmods.economy.foundation.TerminalMenus.ACTIONS.contains(action)
          && !BankingHistoryView.ACTIONS.contains(action)
          && !FinanceActions.handles(action)
          && !action.equals("destination"))
        throw new IllegalArgumentException("BANKING_CHOICE_ACTION");
      text(value, 128, 512);
      text(
          label,
          UiText.isDeferred(label) ? UiText.MAX_DEFERRED : 128,
          UiText.isDeferred(label) ? UiText.MAX_DEFERRED * 4 : 512);
    }
  }

  private static void identity(int menuId, UUID session, long revision) {
    if (menuId < 0 || menuId > 100 || revision < 0)
      throw new IllegalArgumentException("BANKING_SESSION");
    Objects.requireNonNull(session);
  }

  private static void text(String value, int characters, int bytes) {
    if (value == null
        || value.length() > characters
        || value.getBytes(StandardCharsets.UTF_8).length > bytes)
      throw new IllegalArgumentException("BANKING_TEXT_BOUNDS");
  }

  public static void initialize(IEventBus bus) {
    bus.addListener(BankingNetwork::register);
  }

  private static void register(RegisterPayloadHandlersEvent event) {
    var registrar = event.registrar("12");
    registrar.playToServer(
        Action.PAYLOAD_TYPE,
        Action.CODEC,
        (request, context) ->
            context.enqueueWork(
                () -> {
                  if (context.player() instanceof ServerPlayer player
                      && player.containerMenu instanceof BankingMenu menu
                      && menu.containerId == request.menuId()
                      && menu.acceptAction(
                          player, request.session(), request.revision(), request.action()))
                    BankingController.checkedAction(
                        player, menu, request.action(), request.value());
                }));
    registrar.playToClient(
        Snapshot.PAYLOAD_TYPE,
        Snapshot.CODEC,
        (snapshot, context) ->
            context.enqueueWork(
                () -> {
                  if (context.player().containerMenu instanceof BankingMenu menu
                      && menu.containerId == snapshot.menuId()
                      && menu.session().equals(snapshot.session()))
                    menu.applyClientSnapshot(
                        snapshot.revision(),
                        snapshot.page(),
                        snapshot.lines(),
                        snapshot.status(),
                        snapshot.busy(),
                        snapshot.choices(),
                        snapshot.operation(),
                        snapshot.dashboard(),
                        snapshot.history());
                }));
  }

  public static void send(ServerPlayer player, Snapshot snapshot) {
    PacketDistributor.sendToPlayer(player, snapshot);
  }

  public static void action(BankingMenu menu, String action, String value) {
    PacketDistributor.sendToServer(
        new Action(menu.containerId, menu.session(), menu.revision(), action, value));
  }
}
