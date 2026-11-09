package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.banking.*;
import java.nio.file.Files;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** New disposable integrated world only; never opens an existing save or accepts a server EULA. */
public final class VillageGenerationClient {
  private boolean opened, stopped, captured;
  private long started = System.nanoTime();
  private int ticks, captureWarmup;

  public VillageGenerationClient() {
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  private void tick(ClientTickEvent.Post event) {
    if (!Boolean.getBoolean("economy.villageGenerationProbe") || stopped) return;
    var client = Minecraft.getInstance();
    ticks++;
    client.options.pauseOnLostFocus = false;
    try {
      if (!opened && client.getOverlay() == null && client.screen != null && client.level == null) {
        opened = true;
        String name = "economy-a7-04-" + UUID.randomUUID();
        var save = client.gameDirectory.toPath().resolve("saves").resolve(name);
        if (Files.exists(save)) throw new IllegalStateException("Refusing existing fixture world");
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
      if (client.player != null
          && client.player.containerMenu instanceof BankingMenu menu
          && menu.worldSetup()
          && !menu.busy()
          && ticks % 20 == 0) BankingNetwork.action(menu, "setup", "CAD");
      var camera = VillageGenerationProbe.captureCamera();
      boolean cameraArrived =
          camera != null
              && client.player != null
              && client.level != null
              && client.player.distanceToSqr(camera.x() + .5, camera.y(), camera.z() + .5) < 4
              && client.level.hasChunkAt(
                  new net.minecraft.core.BlockPos(camera.x(), camera.y(), camera.z()));
      captureWarmup = cameraArrived ? captureWarmup + 1 : 0;
      if (!captured
          && VillageGenerationProbe.captureReady()
          && captureWarmup >= 40
          && client.getOverlay() == null
          && client.screen == null) {
        captured = true;
        Screenshot.grab(
            client.gameDirectory,
            "village-generation-bank.png",
            client.getMainRenderTarget(),
            message -> {
              System.out.println("VILLAGE_GENERATION_SCREENSHOT " + message.getString());
              VillageGenerationProbe.captured();
            });
      }
      String failure = VillageGenerationProbe.failure(), result = VillageGenerationProbe.result();
      if (failure == null && result == null && System.nanoTime() - started > 600_000_000_000L)
        failure =
            "Disposable village fixture wall-clock deadline: " + VillageGenerationProbe.progress();
      if (failure != null || result != null) {
        stopped = true;
        Files.writeString(
            client
                .gameDirectory
                .toPath()
                .resolve(
                    failure == null
                        ? "village-generation-passed.txt"
                        : "village-generation-failed.txt"),
            (failure == null ? result : failure)
                + "\nWorld: "
                + VillageGenerationProbe.root()
                + "\n");
        client.execute(client::stop);
      }
    } catch (Throwable error) {
      stopped = true;
      VillageGenerationProbe.fail(error);
      try {
        Files.writeString(
            client.gameDirectory.toPath().resolve("village-generation-failed.txt"),
            VillageGenerationProbe.failure());
      } catch (Exception ignored) {
      }
      client.execute(client::stop);
    }
  }
}
