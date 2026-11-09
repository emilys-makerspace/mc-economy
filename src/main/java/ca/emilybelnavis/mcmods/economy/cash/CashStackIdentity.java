package ca.emilybelnavis.mcmods.economy.cash;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.journal.CashIssuance;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Persistent lot identity. A marker is a ledger lookup key, never proof of redeemable value.
 * Trusted custody admission must validate aggregate quantity.
 */
public final class CashStackIdentity {
  public record Mark(UUID world, UUID lot) {
    public Mark {
      Objects.requireNonNull(world);
      Objects.requireNonNull(lot);
    }

    public static final Codec<Mark> CODEC =
        RecordCodecBuilder.create(
            instance ->
                instance
                    .group(
                        UUIDUtil.CODEC.fieldOf("world").forGetter(Mark::world),
                        UUIDUtil.CODEC.fieldOf("lot").forGetter(Mark::lot))
                    .apply(instance, Mark::new));
    public static final StreamCodec<ByteBuf, Mark> STREAM_CODEC =
        StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, Mark::world, UUIDUtil.STREAM_CODEC, Mark::lot, Mark::new);
  }

  private static final DeferredRegister.DataComponents COMPONENTS =
      DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, "economy");
  public static final DeferredHolder<DataComponentType<?>, DataComponentType<Mark>> CASH_LOT =
      COMPONENTS.registerComponentType(
          "cash_lot",
          builder -> builder.persistent(Mark.CODEC).networkSynchronized(Mark.STREAM_CODEC));

  private CashStackIdentity() {}

  public static void initialize(IEventBus bus) {
    COMPONENTS.register(bus);
  }

  /** Use only for delivery of a journal-committed issuance through the custody protocol. */
  public static ItemStack marked(ItemStack collectible, WorldId world, UUID lot) {
    if (CashCatalog.find(collectible).isEmpty()
        || collectible.getCount() < 1
        || collectible.getCount() > 64) throw new IllegalArgumentException("Cash stack required");
    var stack = collectible.copy();
    stack.set(CASH_LOT.get(), new Mark(world.value(), lot));
    return stack;
  }

  /** Build an immutable physical-stack observation before crossing to the worker. */
  public static Optional<CashIssuance.Claim> claim(ItemStack stack, WorldId world) {
    return mark(stack, world)
        .map(
            mark ->
                new CashIssuance.Claim(
                    world,
                    mark.lot(),
                    "economy:" + CashCatalog.find(stack).orElseThrow().id(),
                    stack.getCount()));
  }

  /** Detached recognition for server admission. The ledger must still authorize redemption. */
  public static Optional<Mark> mark(ItemStack stack, WorldId world) {
    if (CashCatalog.find(stack).isEmpty() || stack.getCount() < 1 || stack.getCount() > 64)
      return Optional.empty();
    return Optional.ofNullable(stack.get(CASH_LOT.get()))
        .filter(mark -> mark.world().equals(world.value()));
  }
}
