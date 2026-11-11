package ca.emilybelnavis.mcmods.economy.probe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Never opens an existing world or accepts a dedicated-server EULA. */
public final class TraderHookClient {
  private boolean opened, stopped;
  private final long started = System.nanoTime();
  private Path fixtureRoot;

  public TraderHookClient() {
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  private void tick(ClientTickEvent.Post event) {
    if (!Boolean.getBoolean("economy.traderHookProbe") || stopped) return;
    var client = Minecraft.getInstance();
    client.options.pauseOnLostFocus = false;
    try {
      if (!opened && client.getOverlay() == null && client.screen != null && client.level == null) {
        opened = true;
        fixtureRoot =
            DisposableProbeWorld.requireClientDirectory(
                client.gameDirectory.toPath(), "economy-a8-04-01-");
        String name = "economy-trader-hooks-" + UUID.randomUUID();
        DisposableProbeWorld.requireFreshWorld(fixtureRoot, name);
        var settings =
            new LevelSettings(
                name,
                GameType.CREATIVE,
                false,
                Difficulty.PEACEFUL,
                true,
                new GameRules(),
                WorldDataConfiguration.DEFAULT);
        client
            .createWorldOpenFlows()
            .createFreshLevel(
                name,
                settings,
                new WorldOptions(704L, true, false),
                WorldPresets::createNormalWorldDimensions,
                client.screen);
        return;
      }
      String failure = TraderHookProbe.failure, result = TraderHookProbe.result;
      if (failure == null && result == null && System.nanoTime() - started > 240_000_000_000L)
        failure = "Trader hook fixture deadline";
      if (failure != null || result != null) {
        stopped = true;
        Files.writeString(
            fixtureRoot.resolve(
                failure == null ? "trader-hook-passed.txt" : "trader-hook-failed.txt"),
            failure == null ? result : failure);
        client.execute(client::stop);
      }
    } catch (Throwable e) {
      e.printStackTrace();
      stopped = true;
      try {
        if (fixtureRoot != null)
          Files.writeString(fixtureRoot.resolve("trader-hook-failed.txt"), e.toString());
      } catch (Exception ignored) {
      }
      client.execute(client::stop);
    }
  }
}
