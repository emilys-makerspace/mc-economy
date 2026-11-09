package ca.emilybelnavis.mcmods.economy.probe;

import java.nio.file.Files;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class BankLocateClient {
  private boolean opened, stopped;
  private final long start = System.nanoTime();

  public BankLocateClient() {
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  private void tick(ClientTickEvent.Post event) {
    if (stopped) return;
    var client = Minecraft.getInstance();
    client.options.pauseOnLostFocus = false;
    try {
      if (!opened && client.getOverlay() == null && client.screen != null && client.level == null) {
        opened = true;
        String name = "economy-bank-locate-" + UUID.randomUUID();
        if (Files.exists(client.gameDirectory.toPath().resolve("saves").resolve(name)))
          throw new IllegalStateException("Existing world refused");
        client
            .createWorldOpenFlows()
            .createFreshLevel(
                name,
                new LevelSettings(
                    name,
                    GameType.CREATIVE,
                    false,
                    Difficulty.PEACEFUL,
                    true,
                    new GameRules(),
                    WorldDataConfiguration.DEFAULT),
                new WorldOptions(704L, false, false),
                WorldPresets::createNormalWorldDimensions,
                client.screen);
      }
      if (BankLocateProbe.result == null
          && BankLocateProbe.failure == null
          && System.nanoTime() - start > 120_000_000_000L)
        BankLocateProbe.failure = "Fixture deadline";
      if (BankLocateProbe.result != null || BankLocateProbe.failure != null) {
        stopped = true;
        Files.writeString(
            client
                .gameDirectory
                .toPath()
                .resolve(
                    BankLocateProbe.failure == null
                        ? "bank-locate-passed.txt"
                        : "bank-locate-failed.txt"),
            BankLocateProbe.failure == null ? BankLocateProbe.result : BankLocateProbe.failure);
        client.execute(client::stop);
      }
    } catch (Exception e) {
      BankLocateProbe.failure = e.toString();
      stopped = true;
      client.execute(client::stop);
    }
  }
}
