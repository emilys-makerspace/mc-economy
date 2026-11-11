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

/** Never opens an existing world or accepts a dedicated-server EULA. */
public final class SimulationProductionClient {
  private int action;
  private long lastRevision = -1, lastActionWall = System.currentTimeMillis();
  private int delay;
  private boolean opened, stopped;
  private final long started = System.nanoTime();

  public SimulationProductionClient() {
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  private void tick(ClientTickEvent.Post event) {
    if (!Boolean.getBoolean("economy.simulationProductionProbe") || stopped) return;
    var client = Minecraft.getInstance();
    client.options.pauseOnLostFocus = false;
    try {
      if (!opened && client.getOverlay() == null && client.screen != null && client.level == null) {
        opened = true;
        String name = "economy-simulation-production-" + UUID.randomUUID();
        if (Files.exists(client.gameDirectory.toPath().resolve("saves").resolve(name)))
          throw new IllegalStateException("Existing fixture refused");
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
          && client.player.containerMenu
              instanceof ca.emilybelnavis.mcmods.economy.trade.menu.TraderMenu menu
          && ++delay % 8 == 0
          && menu.revision() != lastRevision) {
        String kind =
            switch (action) {
              case 0 -> "rail";
              case 1 -> "currency";
              case 2 -> "quantity";
              case 3 -> "quote";
              case 4 -> "confirm";
              default -> "";
            };
        String value =
            switch (action) {
              case 0 -> "cash";
              case 1 -> "JPY";
              case 2 -> "1";
              default -> "";
            };
        if (menu.choices().stream()
                .anyMatch(c -> c.action().equals(kind) && c.value().equals(value))
            && !menu.busy()) {
          if (action == 4)
            net.minecraft.client.Screenshot.grab(
                client.gameDirectory,
                "simulation-quote.png",
                client.getMainRenderTarget(),
                message -> System.out.println("SIMULATION_QUOTE_CAPTURED " + message.getString()));
          ca.emilybelnavis.mcmods.economy.trade.menu.TraderNetwork.action(menu, kind, value);
          System.out.println(
              "SIMULATION_ACTION "
                  + action
                  + " "
                  + kind
                  + "="
                  + value
                  + " status="
                  + menu.status());
          lastRevision = menu.revision();
          lastActionWall = System.currentTimeMillis();
          action++;
        }
      }
      if (client.player != null
          && client.player.containerMenu
              instanceof ca.emilybelnavis.mcmods.economy.trade.menu.TraderMenu current
          && action >= 3
          && !current.busy()
          && System.currentTimeMillis() - lastActionWall > 10000
          && SimulationProductionProbe.result == null) {
        net.minecraft.client.Screenshot.grab(
            client.gameDirectory,
            "simulation-stalled.png",
            client.getMainRenderTarget(),
            message -> System.out.println(message.getString()));
        throw new IllegalStateException(
            "Checkout stalled after action "
                + action
                + ": "
                + current.status()
                + " choices="
                + current.choices());
      }
      String failure = SimulationProductionProbe.failure, result = SimulationProductionProbe.result;
      if (failure == null && result == null && System.nanoTime() - started > 240_000_000_000L)
        failure = "Simulation production fixture deadline";
      if (failure != null || result != null) {
        stopped = true;
        Files.writeString(
            client
                .gameDirectory
                .toPath()
                .resolve(
                    failure == null
                        ? "simulation-production-passed.txt"
                        : "simulation-production-failed.txt"),
            failure == null ? result : failure);
        client.execute(client::stop);
      }
    } catch (Throwable e) {
      stopped = true;
      try {
        Files.writeString(
            client.gameDirectory.toPath().resolve("simulation-production-failed.txt"),
            e.toString());
      } catch (Exception ignored) {
      }
      client.execute(client::stop);
    }
  }
}
