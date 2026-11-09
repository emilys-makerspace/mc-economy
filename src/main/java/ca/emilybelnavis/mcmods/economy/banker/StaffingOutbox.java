package ca.emilybelnavis.mcmods.economy.banker;

import ca.emilybelnavis.mcmods.economy.journal.BankStaffing;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-owned positive removal evidence and original checkpoint intents, outside removed
 * entities/blocks.
 */
public final class StaffingOutbox extends SavedData {
  public record Event(
      UUID id,
      String action,
      UUID branch,
      UUID desk,
      UUID assignment,
      UUID employee,
      int slot,
      String dimension,
      BlockPos position) {}

  public record Checkpoint(UUID operation, StaffingRecords.Command command) {}

  private final LinkedHashMap<UUID, Event> events = new LinkedHashMap<>();
  private final LinkedHashMap<UUID, Checkpoint> checkpoints = new LinkedHashMap<>();

  private StaffingOutbox() {}

  public static StaffingOutbox get(MinecraftServer server) {
    return server
        .overworld()
        .getDataStorage()
        .computeIfAbsent(
            new SavedData.Factory<>(StaffingOutbox::new, StaffingOutbox::load),
            "economy_banker_outbox");
  }

  public void event(
      String action,
      UUID branch,
      UUID desk,
      UUID assignment,
      UUID employee,
      int slot,
      String dimension,
      BlockPos position) {
    UUID id =
        UUID.nameUUIDFromBytes(
            (action + "|" + branch + "|" + desk + "|" + assignment + "|" + employee)
                .getBytes(StandardCharsets.UTF_8));
    if (events.putIfAbsent(
            id,
            new Event(
                id,
                action,
                branch,
                desk,
                assignment,
                employee,
                slot,
                dimension,
                position.immutable()))
        == null) setDirty();
  }

  public List<Event> events() {
    return events.values().stream().limit(16).toList();
  }

  public void completeEvent(UUID id) {
    if (events.remove(id) != null) setDirty();
  }

  public void checkpoint(UUID operation, StaffingRecords.Command command) {
    if (checkpoints.putIfAbsent(operation, new Checkpoint(operation, command)) == null) setDirty();
  }

  public List<Checkpoint> checkpoints() {
    return checkpoints.values().stream().limit(16).toList();
  }

  public void completeCheckpoint(UUID id) {
    if (checkpoints.remove(id) != null) setDirty();
  }

  public static StaffingOutbox load(CompoundTag tag, HolderLookup.Provider registries) {
    var out = new StaffingOutbox();
    var list = tag.getList("events", Tag.TAG_COMPOUND);
    for (int i = 0; i < list.size(); i++) {
      var e = list.getCompound(i);
      var value =
          new Event(
              e.getUUID("id"),
              e.getString("action"),
              e.getUUID("branch"),
              e.getUUID("desk"),
              e.hasUUID("assignment") ? e.getUUID("assignment") : null,
              e.hasUUID("employee") ? e.getUUID("employee") : null,
              e.getInt("slot"),
              e.getString("dimension"),
              new BlockPos(e.getInt("x"), e.getInt("y"), e.getInt("z")));
      if (!Set.of("DEATH", "DESK_REMOVED").contains(value.action())
          || value.slot() < -1
          || value.slot() > 1)
        throw new IllegalArgumentException("Invalid staffing removal evidence");
      out.events.put(value.id(), value);
    }
    list = tag.getList("checkpoints", Tag.TAG_COMPOUND);
    for (int i = 0; i < list.size(); i++) {
      var e = list.getCompound(i);
      var command = BankStaffing.decodeCommand(e.getString("command"));
      if (command.action() != StaffingRecords.Action.CHECKPOINT)
        throw new IllegalArgumentException("Unexpected saved staffing action");
      var op = e.getUUID("operation");
      out.checkpoints.put(op, new Checkpoint(op, command));
    }
    return out;
  }

  @Override
  public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
    var list = new ListTag();
    for (var value : events.values()) {
      var e = new CompoundTag();
      e.putUUID("id", value.id());
      e.putString("action", value.action());
      e.putUUID("branch", value.branch());
      e.putUUID("desk", value.desk());
      if (value.assignment() != null) e.putUUID("assignment", value.assignment());
      if (value.employee() != null) e.putUUID("employee", value.employee());
      e.putInt("slot", value.slot());
      e.putString("dimension", value.dimension());
      e.putInt("x", value.position().getX());
      e.putInt("y", value.position().getY());
      e.putInt("z", value.position().getZ());
      list.add(e);
    }
    tag.put("events", list);
    list = new ListTag();
    for (var value : checkpoints.values()) {
      var e = new CompoundTag();
      e.putUUID("operation", value.operation());
      e.putString("command", BankStaffing.encodeCommand(value.command()));
      list.add(e);
    }
    tag.put("checkpoints", list);
    return tag;
  }
}
