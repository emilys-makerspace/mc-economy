package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.banking.*;
import java.nio.file.*;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Two fresh processes, one explicitly guarded disposable save; ordinary saves are never opened. */
public final class VillageBankAcceptanceClient {
  private boolean opened, stopped, used, openingSent;
  private int ticks;
  private long sentRevision = -1;
  private final long started = System.nanoTime();

  public VillageBankAcceptanceClient() {
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  private void tick(ClientTickEvent.Post event) {
    if (!Boolean.getBoolean("economy.villageBankAcceptance") || stopped) return;
    var client = Minecraft.getInstance();
    client.options.pauseOnLostFocus = false;
    ticks++;
    try {
      Path root = client.gameDirectory.toPath().toAbsolutePath().normalize();
      if (!opened && client.level == null && client.getOverlay() == null && client.screen != null) {
        opened = true;
        boolean restart = Boolean.getBoolean("economy.villageBankAcceptanceRestart");
        Path marker = root.resolve("village-bank-acceptance-world.txt");
        if (restart) {
          require(
              Files.isRegularFile(root.resolve("village-bank-acceptance-phase1-passed.txt")),
              "restart requires successful first process");
          String name = Files.readString(marker).strip();
          require(name.matches("economy-a7-06-[0-9a-f-]{36}"), "restart save name guard");
          require(Files.isDirectory(root.resolve("saves").resolve(name)), "restart save missing");
          client
              .createWorldOpenFlows()
              .openWorld(
                  name,
                  () -> VillageBankAcceptanceProbe.failure = "Restart world opening cancelled");
        } else {
          require(!Files.exists(marker), "fresh fixture refuses existing run marker");
          String name = "economy-a7-06-" + UUID.randomUUID();
          require(!Files.exists(root.resolve("saves").resolve(name)), "fresh save exists");
          Files.writeString(marker, name + "\n", StandardOpenOption.CREATE_NEW);
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
        }
        return;
      }
      if (client.player != null
          && client.player.containerMenu instanceof BankingMenu menu
          && menu.worldSetup()
          && !menu.busy()
          && ticks % 20 == 0) BankingNetwork.action(menu, "setup", "CAD");
      var target = VillageBankAcceptanceProbe.kioskTarget;
      if (!used
          && target != null
          && client.player != null
          && client.level != null
          && client.gameMode != null
          && client.level.hasChunkAt(target)
          && client.player.distanceToSqr(Vec3.atCenterOf(target)) < 20
          && client.screen == null) {
        used = true;
        client.gameMode.useItemOn(
            client.player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false));
      }
      if (!Boolean.getBoolean("economy.villageBankAcceptanceRestart")
          && used
          && !openingSent
          && client.player != null
          && client.player.containerMenu instanceof BankingMenu menu
          && !menu.worldSetup()
          && !menu.busy()
          && ticks % 10 == 0
          && menu.revision() != sentRevision) {
        var offered =
            menu.choices().stream().filter(c -> c.action().equals("branch_open")).findFirst();
        if (offered.isPresent()) {
          sentRevision = menu.revision();
          openingSent = true;
          BankingNetwork.action(menu, offered.get().action(), offered.get().value());
          VillageBankAcceptanceProbe.clientOpeningSent = true;
        } else
          menu.choices().stream()
              .filter(c -> c.action().equals("manage_accounts"))
              .findFirst()
              .ifPresent(
                  choice -> {
                    sentRevision = menu.revision();
                    BankingNetwork.action(menu, choice.action(), choice.value());
                  });
      }
      if (VillageBankAcceptanceProbe.failure == null
          && VillageBankAcceptanceProbe.result == null
          && !VillageBankAcceptanceProbe.phase1Ready
          && System.nanoTime() - started > 600_000_000_000L)
        VillageBankAcceptanceProbe.failure = "Integrated acceptance wall deadline exceeded";
      String failure = VillageBankAcceptanceProbe.failure,
          result = VillageBankAcceptanceProbe.result;
      if (failure != null || result != null || VillageBankAcceptanceProbe.phase1Ready) {
        stopped = true;
        String file =
            failure != null
                ? "village-bank-acceptance-failed.txt"
                : result != null
                    ? "village-bank-acceptance-passed.txt"
                    : "village-bank-acceptance-phase1-passed.txt";
        Files.writeString(
            root.resolve(file),
            (failure != null
                    ? failure
                    : result != null
                        ? result
                        : "PASS first process: real generated-kiosk client account opening; populated evidence persisted")
                + "\n");
        client.execute(client::stop);
      }
    } catch (Throwable error) {
      stopped = true;
      VillageBankAcceptanceProbe.failure = error.toString();
      try {
        Files.writeString(
            client.gameDirectory.toPath().resolve("village-bank-acceptance-failed.txt"),
            error.toString());
      } catch (Exception ignored) {
      }
      client.execute(client::stop);
    }
  }

  private static void require(boolean value, String message) {
    if (!value) throw new IllegalStateException(message);
  }
}
