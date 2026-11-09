package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.trade.menu.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Fresh-world native reference-source activation, offered network checkout and quiet omission. */
public final class ReferenceTraderClient {
  private boolean opened, stopped;
  private final long started = System.nanoTime();
  private int action, delay;
  private long lastRevision = -1, lastAction = System.currentTimeMillis();

  public ReferenceTraderClient() {
    if (!Boolean.getBoolean("economy.referenceTraderProbe"))
      throw new IllegalStateException("Explicit reference-trader probe required");
    NeoForge.EVENT_BUS.addListener(this::client);
    NeoForge.EVENT_BUS.addListener(this::server);
  }

  private void server(ServerTickEvent.Post event) {
    if (!Boolean.getBoolean("economy.referenceTraderProbe")
        || ReferenceTraderProbe.result != null
        || ReferenceTraderProbe.failure != null) return;
    try {
      ReferenceTraderProbe.tick(event.getServer());
    } catch (Throwable failure) {
      failure.printStackTrace();
      ReferenceTraderProbe.failure = failure.toString();
    }
  }

  private void client(ClientTickEvent.Post event) {
    if (stopped) return;
    var client = Minecraft.getInstance();
    client.options.pauseOnLostFocus = false;
    try {
      if (!opened && client.getOverlay() == null && client.screen != null && client.level == null) {
        opened = true;
        Path root = client.gameDirectory.toPath().toAbsolutePath().normalize();
        require(
            root.startsWith(Path.of("/private/tmp"))
                && root.getParent().getFileName().toString().startsWith("economy-a9-06-"),
            "Guarded disposable client root required");
        require(
            !Files.exists(root.resolve("config/economy-valuation.json")),
            "Existing fixture valuation config refused");
        String name = "economy-reference-trader-" + UUID.randomUUID();
        require(!Files.exists(root.resolve("saves").resolve(name)), "Fresh world only");
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
                new WorldOptions(906L, false, false),
                WorldPresets::createNormalWorldDimensions,
                client.screen);
      }
      if (ReferenceTraderProbe.phase >= 5
          && client.player != null
          && client.player.containerMenu instanceof TraderMenu menu
          && ++delay % 8 == 0
          && !menu.busy()) {
        require(
            menu.choices().stream()
                .noneMatch(choice -> choice.action().equals("offer") && choice.value().equals("1")),
            "Unsupported custom component listing must be omitted");
        require(
            menu.lines().stream()
                .noneMatch(
                    line ->
                        line.toLowerCase(Locale.ROOT).contains("unsupported")
                            || line.contains("MISSING_INPUT")
                            || line.contains("UNSUPPORTED_IDENTITY")),
            "No unsupported-listing reason in player presentation");
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
              case 1 -> "CAD";
              case 2 -> "1";
              default -> "";
            };
        if (menu.revision() != lastRevision
            && menu.choices().stream()
                .anyMatch(choice -> choice.action().equals(kind) && choice.value().equals(value))) {
          if (action == 4) {
            ReferenceTraderProbe.quoteLines = List.copyOf(menu.lines());
            require(
                menu.lines().stream().anyMatch(line -> line.startsWith("Unit price: CAD ")),
                "Reference unit price shown for review");
            net.minecraft.client.Screenshot.grab(
                client.gameDirectory,
                "reference-trader-quote.png",
                client.getMainRenderTarget(),
                message -> System.out.println("REFERENCE_QUOTE_CAPTURED " + message.getString()));
          }
          TraderNetwork.action(menu, kind, value);
          System.out.println(
              "REFERENCE_TRADER_ACTION "
                  + action
                  + " "
                  + kind
                  + "="
                  + value
                  + " status="
                  + menu.status());
          action++;
          lastRevision = menu.revision();
          lastAction = System.currentTimeMillis();
        }
        if (action >= 3
            && System.currentTimeMillis() - lastAction > 30_000
            && ReferenceTraderProbe.result == null)
          throw new IllegalStateException(
              "Reference checkout stalled action="
                  + action
                  + " status="
                  + menu.status()
                  + " choices="
                  + menu.choices());
      }
      String failure = ReferenceTraderProbe.failure, result = ReferenceTraderProbe.result;
      if (failure == null && result == null && System.nanoTime() - started > 300_000_000_000L)
        failure = "Reference trader fixture deadline phase=" + ReferenceTraderProbe.phase;
      if (failure != null || result != null) {
        stopped = true;
        Files.writeString(
            client
                .gameDirectory
                .toPath()
                .resolve(
                    failure == null
                        ? "reference-trader-passed.txt"
                        : "reference-trader-failed.txt"),
            failure == null ? result : failure);
        client.execute(client::stop);
      }
    } catch (Throwable failure) {
      failure.printStackTrace();
      stopped = true;
      try {
        Files.writeString(
            client.gameDirectory.toPath().resolve("reference-trader-failed.txt"),
            failure.toString());
      } catch (Exception ignored) {
      }
      client.execute(client::stop);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
