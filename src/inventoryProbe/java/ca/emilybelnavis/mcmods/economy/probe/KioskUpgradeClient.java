package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.banking.BankingMenu;
import ca.emilybelnavis.mcmods.economy.banking.BankingNetwork;
import java.nio.file.Files;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Creates only a new disposable integrated world; never accepts the dedicated-server EULA. */
public final class KioskUpgradeClient {
  private boolean opened, stopped, captured;
  private int ticks;

  public KioskUpgradeClient() {
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  private void tick(ClientTickEvent.Post event) {
    if (!Boolean.getBoolean("economy.kioskUpgradeProbe") || stopped) return;
    var minecraft = Minecraft.getInstance();
    ticks++;
    try {
      if (!opened
          && minecraft.getOverlay() == null
          && minecraft.screen instanceof TitleScreen
          && minecraft.level == null) {
        opened = true;
        var name =
            System.getProperty("economy.kioskUpgradeWorld", "economy-a7-03-" + UUID.randomUUID());
        if (!name.startsWith("economy-a7-03-") || name.contains("/") || name.contains("\\"))
          throw new IllegalArgumentException("Disposable kiosk world name required");
        var target = minecraft.gameDirectory.toPath().resolve("saves").resolve(name);
        if (Files.exists(target))
          throw new IllegalStateException("Refusing existing world: " + target);
        var settings =
            new LevelSettings(
                name,
                GameType.SURVIVAL,
                false,
                Difficulty.PEACEFUL,
                true,
                new GameRules(),
                WorldDataConfiguration.DEFAULT);
        minecraft
            .createWorldOpenFlows()
            .createFreshLevel(
                name,
                settings,
                new WorldOptions(703L, false, false),
                WorldPresets::createNormalWorldDimensions,
                minecraft.screen);
        return;
      }
      if (minecraft.player != null
          && minecraft.player.containerMenu instanceof BankingMenu menu
          && menu.worldSetup()
          && !menu.busy()
          && ticks % 20 == 0) BankingNetwork.action(menu, "setup", "CAD");
      if (!captured
          && KioskUpgradeProbe.captureReady()
          && minecraft.level != null
          && minecraft.getOverlay() == null
          && minecraft.screen == null) {
        captured = true;
        Screenshot.grab(
            minecraft.gameDirectory,
            "kiosk-upgrade-wide.png",
            minecraft.getMainRenderTarget(),
            message -> {
              System.out.println("KIOSK_UPGRADE_SCREENSHOT " + message.getString());
              KioskUpgradeProbe.captured();
            });
      }
      String failure = KioskUpgradeProbe.failure(), result = KioskUpgradeProbe.result();
      if (failure == null && result == null && ticks > 18000)
        failure = "Kiosk integrated-client fixture timed out before completion";
      if (failure != null || result != null) {
        stopped = true;
        Files.writeString(
            minecraft
                .gameDirectory
                .toPath()
                .resolve(failure == null ? "kiosk-upgrade-passed.txt" : "kiosk-upgrade-failed.txt"),
            (failure == null ? result : failure)
                + "\nDisposable world: "
                + KioskUpgradeProbe.root()
                + "\n");
        minecraft.execute(minecraft::stop);
      }
    } catch (Throwable problem) {
      stopped = true;
      KioskUpgradeProbe.fail(problem);
      try {
        Files.writeString(
            minecraft.gameDirectory.toPath().resolve("kiosk-upgrade-failed.txt"),
            problem.toString());
      } catch (Exception ignored) {
      }
      minecraft.execute(minecraft::stop);
    }
  }
}
