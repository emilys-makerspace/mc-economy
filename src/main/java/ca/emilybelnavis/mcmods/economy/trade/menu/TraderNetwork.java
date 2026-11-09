package ca.emilybelnavis.mcmods.economy.trade.menu;

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

/** Bounded offered-action transport. Prices, custody and authority stay on the server. */
public final class TraderNetwork {
  public static final Set<String> ACTIONS =
      Set.of(
          "browse",
          "offer",
          "rail",
          "account",
          "currency",
          "quantity",
          "quote",
          "confirm",
          "cancel",
          "claim",
          "back",
          "next",
          "exit");

  private TraderNetwork() {}

  public record Action(int menuId, UUID session, long revision, String action, String value)
      implements CustomPacketPayload {
    public static final Type<Action> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("economy", "trader_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC =
        new StreamCodec<>() {
          public Action decode(RegistryFriendlyByteBuf b) {
            return new Action(
                b.readVarInt(), b.readUUID(), b.readVarLong(), b.readUtf(24), b.readUtf(128));
          }

          public void encode(RegistryFriendlyByteBuf b, Action a) {
            b.writeVarInt(a.menuId());
            b.writeUUID(a.session());
            b.writeVarLong(a.revision());
            b.writeUtf(a.action(), 24);
            b.writeUtf(a.value(), 128);
          }
        };

    public Action {
      identity(menuId, session, revision);
      if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Trader action");
      text(value, 128);
    }

    public Type<Action> type() {
      return TYPE;
    }
  }

  public record Choice(String action, String value, String label, boolean selected) {
    public Choice {
      if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Trader choice");
      text(value, 128);
      text(label, 128);
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
      List<Choice> choices)
      implements CustomPacketPayload {
    public static final Type<Snapshot> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("economy", "trader_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC =
        new StreamCodec<>() {
          public Snapshot decode(RegistryFriendlyByteBuf b) {
            int menu = b.readVarInt();
            UUID session = b.readUUID();
            long revision = b.readVarLong();
            String page = b.readUtf(24);
            int count = b.readVarInt();
            if (count < 0 || count > 16) throw new IllegalArgumentException("Trader lines");
            var lines = new ArrayList<String>();
            for (int n = 0; n < count; n++) lines.add(b.readUtf(256));
            String status = b.readUtf(256);
            boolean busy = b.readBoolean();
            int controls = b.readVarInt();
            if (controls < 0 || controls > 16) throw new IllegalArgumentException("Trader choices");
            var choices = new ArrayList<Choice>();
            for (int n = 0; n < controls; n++)
              choices.add(
                  new Choice(b.readUtf(24), b.readUtf(128), b.readUtf(128), b.readBoolean()));
            return new Snapshot(menu, session, revision, page, lines, status, busy, choices);
          }

          public void encode(RegistryFriendlyByteBuf b, Snapshot s) {
            b.writeVarInt(s.menuId());
            b.writeUUID(s.session());
            b.writeVarLong(s.revision());
            b.writeUtf(s.page(), 24);
            b.writeVarInt(s.lines().size());
            for (var line : s.lines()) b.writeUtf(line, 256);
            b.writeUtf(s.status(), 256);
            b.writeBoolean(s.busy());
            b.writeVarInt(s.choices().size());
            for (var c : s.choices()) {
              b.writeUtf(c.action(), 24);
              b.writeUtf(c.value(), 128);
              b.writeUtf(c.label(), 128);
              b.writeBoolean(c.selected());
            }
          }
        };

    public Snapshot {
      identity(menuId, session, revision);
      text(page, 24);
      lines = List.copyOf(lines);
      choices = List.copyOf(choices);
      if (lines.size() > 16 || choices.size() > 16)
        throw new IllegalArgumentException("Trader page bounds");
      int bytes = 0;
      for (var line : lines) {
        text(line, 256);
        bytes += line.getBytes(StandardCharsets.UTF_8).length;
      }
      text(status, 256);
      bytes += status.getBytes(StandardCharsets.UTF_8).length;
      for (var c : choices)
        bytes += (c.action() + c.value() + c.label()).getBytes(StandardCharsets.UTF_8).length;
      if (bytes > 12000) throw new IllegalArgumentException("Trader snapshot budget");
    }

    public Type<Snapshot> type() {
      return TYPE;
    }
  }

  private static void identity(int menu, UUID session, long revision) {
    if (menu < 0 || menu > 100 || revision < 0)
      throw new IllegalArgumentException("Trader identity");
    Objects.requireNonNull(session);
  }

  private static void text(String text, int length) {
    if (text == null
        || text.length() > length
        || text.getBytes(StandardCharsets.UTF_8).length > length * 4)
      throw new IllegalArgumentException("Trader text");
  }

  public static void initialize(IEventBus bus) {
    bus.addListener(TraderNetwork::register);
  }

  private static void register(RegisterPayloadHandlersEvent event) {
    var registrar = event.registrar("1");
    registrar.playToServer(
        Action.TYPE,
        Action.CODEC,
        (action, context) ->
            context.enqueueWork(
                () -> {
                  if (context.player() instanceof ServerPlayer player
                      && player.containerMenu instanceof TraderMenu menu
                      && menu.containerId == action.menuId()
                      && menu.accept(player, action))
                    TraderController.action(player, menu, action.action(), action.value());
                }));
    registrar.playToClient(
        Snapshot.TYPE,
        Snapshot.CODEC,
        (snapshot, context) ->
            context.enqueueWork(
                () -> {
                  if (context.player().containerMenu instanceof TraderMenu menu
                      && menu.containerId == snapshot.menuId()
                      && menu.session().equals(snapshot.session())) menu.apply(snapshot);
                }));
  }

  public static void send(ServerPlayer player, Snapshot snapshot) {
    PacketDistributor.sendToPlayer(player, snapshot);
  }

  public static void action(TraderMenu menu, String action, String value) {
    PacketDistributor.sendToServer(
        new Action(menu.containerId, menu.session(), menu.revision(), action, value));
  }
}
