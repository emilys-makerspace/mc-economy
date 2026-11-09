package ca.emilybelnavis.mcmods.economy.trade.menu;

import ca.emilybelnavis.mcmods.economy.trade.entity.TradeHooks;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

/** No slots or financial authority: only the current server-offered actions are accepted. */
public final class TraderMenu extends AbstractContainerMenu {
  private final Inventory inventory;
  private final UUID trader, session;
  private long revision, lastActionTick = Long.MIN_VALUE;
  private List<String> lines = List.of();
  private List<TraderNetwork.Choice> choices = List.of();
  private String page = "browse", status = "Loading trader…";
  private boolean busy = true;

  public TraderMenu(int id, Inventory inventory, UUID trader, UUID session) {
    super(TraderMenuCatalog.TRADER.get(), id);
    this.inventory = Objects.requireNonNull(inventory);
    this.trader = Objects.requireNonNull(trader);
    this.session = Objects.requireNonNull(session);
  }

  static TraderMenu fromNetwork(int id, Inventory inventory, RegistryFriendlyByteBuf b) {
    return new TraderMenu(id, inventory, b.readUUID(), b.readUUID());
  }

  public void writeOpening(RegistryFriendlyByteBuf b) {
    b.writeUUID(trader);
    b.writeUUID(session);
  }

  public UUID traderId() {
    return trader;
  }

  public UUID session() {
    return session;
  }

  public long revision() {
    return revision;
  }

  public String page() {
    return page;
  }

  public String status() {
    return status;
  }

  public boolean busy() {
    return busy;
  }

  public List<String> lines() {
    return lines;
  }

  public List<TraderNetwork.Choice> choices() {
    return choices;
  }

  public AbstractVillager entity(ServerPlayer player) {
    return player.serverLevel().getEntity(trader) instanceof AbstractVillager entity
        ? entity
        : null;
  }

  public boolean valid(ServerPlayer player) {
    if (player != inventory.player
        || player.containerMenu != this
        || !player.isAlive()
        || player.server.getPlayerList().getPlayer(player.getUUID()) != player) return false;
    var entity = entity(player);
    return entity != null
        && entity.isAlive()
        && !entity.isRemoved()
        && TradeHooks.owned(entity)
        && entity.distanceToSqr(player) <= 36;
  }

  public boolean accept(ServerPlayer player, TraderNetwork.Action action) {
    if (action.menuId() != containerId
        || !session.equals(action.session())
        || revision != action.revision()
        || !valid(player)) return false;
    if (busy && !action.action().equals("cancel") && !action.action().equals("exit")) return false;
    if (!choices.stream()
        .anyMatch(c -> c.action().equals(action.action()) && c.value().equals(action.value())))
      return false;
    long tick = player.server.getTickCount();
    if (lastActionTick != Long.MIN_VALUE && tick - lastActionTick < 2) return false;
    lastActionTick = tick;
    return true;
  }

  public void publish(
      String page,
      List<String> lines,
      String status,
      boolean busy,
      List<TraderNetwork.Choice> choices) {
    var snapshot =
        new TraderNetwork.Snapshot(
            containerId, session, Math.addExact(revision, 1), page, lines, status, busy, choices);
    apply(snapshot);
    if (inventory.player instanceof ServerPlayer player) TraderNetwork.send(player, snapshot);
  }

  public void apply(TraderNetwork.Snapshot snapshot) {
    if (snapshot.menuId() != containerId
        || !session.equals(snapshot.session())
        || snapshot.revision() <= revision) return;
    revision = snapshot.revision();
    page = snapshot.page();
    lines = snapshot.lines();
    status = snapshot.status();
    busy = snapshot.busy();
    choices = snapshot.choices();
  }

  @Override
  public void removed(Player player) {
    super.removed(player);
    if (player instanceof ServerPlayer server) TraderController.closed(server, this);
  }

  @Override
  public boolean stillValid(Player player) {
    return player instanceof ServerPlayer server ? valid(server) : player == inventory.player;
  }

  @Override
  public ItemStack quickMoveStack(Player player, int index) {
    return ItemStack.EMPTY;
  }

  @Override
  public void clicked(int slot, int button, ClickType type, Player player) {}

  @Override
  public boolean canDragTo(Slot slot) {
    return false;
  }
}
