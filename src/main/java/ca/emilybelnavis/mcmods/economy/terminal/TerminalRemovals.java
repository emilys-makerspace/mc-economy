package ca.emilybelnavis.mcmods.economy.terminal;

import ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Minecraft-owned removal outbox; journal admission drains a bounded page, including after reload.
 */
public final class TerminalRemovals extends SavedData {
  public record Removal(
      String dimension, BlockPos anchor, UUID generation, UUID owner, TerminalPolicy.Kind kind) {}

  private final LinkedHashMap<UUID, Removal> pending = new LinkedHashMap<>();

  public record BranchRemoval(UUID branch, TerminalPolicy.Anchor anchor, UUID generation) {}

  private final LinkedHashMap<UUID, BranchRemoval> branches = new LinkedHashMap<>();

  public void addBranch(BranchRemoval value) {
    if (branches.putIfAbsent(value.generation(), value) == null) setDirty();
  }

  public void completeBranch(UUID generation) {
    if (branches.remove(generation) != null) setDirty();
  }

  public List<BranchRemoval> branchPage(int limit) {
    if (limit < 1 || limit > 16) throw new IllegalArgumentException("Branch removal page");
    return branches.values().stream().limit(limit).toList();
  }

  private TerminalRemovals() {}

  public static TerminalRemovals get(MinecraftServer server) {
    return server
        .overworld()
        .getDataStorage()
        .computeIfAbsent(
            new SavedData.Factory<>(TerminalRemovals::new, TerminalRemovals::load),
            "economy_terminal_removals");
  }

  public void add(Removal value) {
    if (pending.putIfAbsent(value.generation(), value) == null) setDirty();
  }

  public void complete(UUID generation) {
    if (pending.remove(generation) != null) setDirty();
  }

  public List<Removal> page(int limit) {
    if (limit < 1 || limit > 16) throw new IllegalArgumentException("Removal page bounds");
    return pending.values().stream().limit(limit).toList();
  }

  public static TerminalRemovals load(CompoundTag tag, HolderLookup.Provider registries) {
    var result = new TerminalRemovals();
    var entries = tag.getList("pending", Tag.TAG_COMPOUND);
    for (int index = 0; index < entries.size(); index++) {
      var entry = entries.getCompound(index);
      var anchor =
          new TerminalPolicy.Anchor(
              entry.getString("dimension"),
              entry.getInt("x"),
              entry.getInt("y"),
              entry.getInt("z"));
      var value =
          new Removal(
              anchor.dimension(),
              new BlockPos(anchor.x(), anchor.y(), anchor.z()),
              entry.getUUID("generation"),
              entry.getUUID("owner"),
              TerminalPolicy.Kind.valueOf(entry.getString("kind")));
      result.pending.put(value.generation(), value);
    }
    var branchEntries = tag.getList("branches", Tag.TAG_COMPOUND);
    for (int i = 0; i < branchEntries.size(); i++) {
      var entry = branchEntries.getCompound(i);
      var anchor =
          new TerminalPolicy.Anchor(
              entry.getString("dimension"),
              entry.getInt("x"),
              entry.getInt("y"),
              entry.getInt("z"));
      var removal = new BranchRemoval(entry.getUUID("branch"), anchor, entry.getUUID("generation"));
      result.branches.put(removal.generation(), removal);
    }
    return result;
  }

  @Override
  public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
    var entries = new ListTag();
    for (var value : pending.values()) {
      var entry = new CompoundTag();
      entry.putString("dimension", value.dimension());
      entry.putInt("x", value.anchor().getX());
      entry.putInt("y", value.anchor().getY());
      entry.putInt("z", value.anchor().getZ());
      entry.putUUID("generation", value.generation());
      entry.putUUID("owner", value.owner());
      entry.putString("kind", value.kind().name());
      entries.add(entry);
    }
    tag.put("pending", entries);
    var branchEntries = new ListTag();
    for (var value : branches.values()) {
      var entry = new CompoundTag();
      entry.putUUID("branch", value.branch());
      entry.putUUID("generation", value.generation());
      entry.putString("dimension", value.anchor().dimension());
      entry.putInt("x", value.anchor().x());
      entry.putInt("y", value.anchor().y());
      entry.putInt("z", value.anchor().z());
      branchEntries.add(entry);
    }
    tag.put("branches", branchEntries);
    return tag;
  }
}
