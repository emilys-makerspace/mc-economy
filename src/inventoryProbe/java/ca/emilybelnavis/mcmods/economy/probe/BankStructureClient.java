package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.banking.*;
import java.nio.file.Files;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class BankStructureClient {
  private boolean opened, stopped;
  private final long start = System.nanoTime();
  private int ticks;

  public BankStructureClient() {
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  private void tick(ClientTickEvent.Post event) {
    if (stopped) return;
    var client = Minecraft.getInstance();
    client.options.pauseOnLostFocus = false;
    ticks++;
    try {
      if (!opened && client.getOverlay() == null && client.screen != null && client.level == null) {
        opened = true;
        String name = "economy-bank-structure-" + UUID.randomUUID();
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
                new WorldOptions(704L, true, false),
                WorldPresets::createNormalWorldDimensions,
                client.screen);
      }
      if (client.player != null
          && client.player.containerMenu instanceof BankingMenu menu
          && menu.worldSetup()
          && !menu.busy()
          && ticks % 20 == 0) BankingNetwork.action(menu, "setup", "CAD");
      String result = BankStructureProbe.result, failure = BankStructureProbe.failure;
      if (result == null && failure == null && System.nanoTime() - start > 240_000_000_000L)
        failure = "Fixture deadline";
      if (result != null || failure != null) {
        stopped = true;
        Files.writeString(
            client
                .gameDirectory
                .toPath()
                .resolve(
                    failure == null ? "bank-structure-passed.txt" : "bank-structure-failed.txt"),
            failure == null ? result : failure);
        client.execute(client::stop);
      }
    } catch (Exception e) {
      BankStructureProbe.failure = e.toString();
      stopped = true;
      client.execute(client::stop);
    }
  }
}
