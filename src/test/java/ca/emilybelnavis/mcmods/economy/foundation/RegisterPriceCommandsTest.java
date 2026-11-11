package ca.emilybelnavis.mcmods.economy.foundation;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class RegisterPriceCommandsTest {
  @Test
  void priceCommandRequiresAuthenticatedPlayerBeforeAnyShopLookup() {
    var dispatcher = new CommandDispatcher<CommandSourceStack>();
    RegisterPriceCommands.register(
        dispatcher, server -> fail("Unauthenticated command reached shop service"));
    var source =
        new CommandSourceStack(
            CommandSource.NULL,
            Vec3.ZERO,
            Vec2.ZERO,
            null,
            4,
            "fixture",
            Component.empty(),
            null,
            null);
    assertThrows(
        CommandSyntaxException.class, () -> dispatcher.execute("economy shop price 5", source));
  }
}
