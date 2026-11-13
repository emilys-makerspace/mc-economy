package ca.emilybelnavis.mcmods.economy.foundation;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.rcon.RconConsoleSource;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class DatabaseUpgradeCommandsTest {
  @Test
  void upgradeApprovalCannotComeFromConsoleRconOrAutomation() {
    var dispatcher = new CommandDispatcher<CommandSourceStack>();
    DatabaseUpgradeCommands.register(
        dispatcher,
        server -> fail("A source without a live administrator reached storage admission"));
    for (var source :
        List.of(
            new Source(CommandSource.NULL, 0),
            new Source(CommandSource.NULL, 2),
            new Source(new RconConsoleSource(null), 4))) {
      for (String command : List.of("economy upgrade review", "economy upgrade confirm token"))
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(command, source));
    }
  }

  private static final class Source extends CommandSourceStack {
    Source(CommandSource source, int permission) {
      super(
          source, Vec3.ZERO, Vec2.ZERO, null, permission, "fixture", Component.empty(), null, null);
    }
  }
}
