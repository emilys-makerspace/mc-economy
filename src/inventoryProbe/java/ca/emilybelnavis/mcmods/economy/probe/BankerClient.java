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
public final class BankerClient {
  private boolean opened, stopped, captured;
  private long started = System.nanoTime();
  private int ticks, captureWarmup;

  public BankerClient() {
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  private void tick(ClientTickEvent.Post event) {
    if (!Boolean.getBoolean("economy.bankerProbe") || stopped) return;
    var client = Minecraft.getInstance();
    ticks++;
    client.options.pauseOnLostFocus = false;
    try {
      if (!opened && client.getOverlay() == null && client.screen != null && client.level == null) {
        opened = true;
        String name = "economy-banker-" + UUID.randomUUID();
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
      var camera = BankerProbe.camera;
      boolean arrived =
          camera != null
              && client.player != null
              && client.level != null
              && client.player.distanceToSqr(camera.getX() + .5, camera.getY(), camera.getZ() + .5)
                  < 4
              && client.level.hasChunkAt(camera);
      captureWarmup = arrived ? captureWarmup + 1 : 0;
      if (!captured
          && BankerProbe.captureReady
          && captureWarmup >= 40
          && client.getOverlay() == null
          && client.screen == null) {
        captured = true;
        Screenshot.grab(
            client.gameDirectory,
            "banker-desk.png",
            client.getMainRenderTarget(),
            message -> {
              System.out.println("BANKER_SCREENSHOT " + message.getString());
              BankerProbe.captured = true;
            });
      }
      String failure = BankerProbe.failure, result = BankerProbe.result;
      if (failure == null && result == null && System.nanoTime() - started > 480_000_000_000L)
        failure = "Disposable village fixture wall-clock deadline: " + "runtime fixture";
      if (failure != null || result != null) {
        stopped = true;
        Files.writeString(
            client
                .gameDirectory
                .toPath()
                .resolve(failure == null ? "banker-passed.txt" : "banker-failed.txt"),
            (failure == null ? result : failure) + "\n");
        client.execute(client::stop);
      }
    } catch (Throwable error) {
      stopped = true;
      BankerProbe.failure = error.toString();
      try {
        Files.writeString(
            client.gameDirectory.toPath().resolve("banker-failed.txt"), BankerProbe.failure);
      } catch (Exception ignored) {
      }
      client.execute(client::stop);
    }
  }
}
