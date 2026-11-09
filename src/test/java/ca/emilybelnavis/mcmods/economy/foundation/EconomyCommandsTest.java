package ca.emilybelnavis.mcmods.economy.foundation;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.rcon.RconConsoleSource;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class EconomyCommandsTest {
  @Test
  void dispatcherKeepsDetailsFromUnprivilegedSources() throws Exception {
    var dispatcher = new CommandDispatcher<CommandSourceStack>();
    EconomyCommands.register(
        dispatcher,
        (source, view) ->
            List.of(
                view.privileged()
                    ? (view.detailed() ? "diagnostics" : "operator status")
                    : "availability"));
    var nonOperator = new TestSource(CommandSource.NULL, 0);
    assertEquals(1, dispatcher.execute("economy status", nonOperator));
    assertThrows(
        CommandSyntaxException.class, () -> dispatcher.execute("economy diagnostics", nonOperator));
    assertEquals(List.of("availability"), nonOperator.messages);

    var automation = new TestSource(CommandSource.NULL, 2);
    assertEquals(1, dispatcher.execute("economy status", automation));
    assertThrows(
        CommandSyntaxException.class, () -> dispatcher.execute("economy diagnostics", automation));
    assertEquals(List.of("availability"), automation.messages);

    var console = new TestSource(new RconConsoleSource(null), 2);
    assertEquals(1, dispatcher.execute("economy status", console));
    assertEquals(1, dispatcher.execute("economy diagnostics", console));
    assertEquals(List.of("operator status", "diagnostics"), console.messages);
  }

  @Test
  void calendarPagesUseTheExistingConsoleOperatorGate() throws Exception {
    var dispatcher = new CommandDispatcher<CommandSourceStack>();
    var cursors = new ArrayList<String>();
    EconomyCommands.register(
        dispatcher,
        (source, view) -> {
          assertTrue(view.privileged());
          assertTrue(view.detailed());
          cursors.add(view.calendarAfter());
          return List.of("calendar");
        });
    for (var denied :
        List.of(new TestSource(CommandSource.NULL, 0), new TestSource(CommandSource.NULL, 2))) {
      assertThrows(
          CommandSyntaxException.class,
          () -> dispatcher.execute("economy diagnostics calendar", denied));
      assertThrows(
          CommandSyntaxException.class,
          () ->
              dispatcher.execute("economy diagnostics calendar after domain/fixture:bank", denied));
      assertTrue(denied.messages.isEmpty());
    }
    assertTrue(cursors.isEmpty());
    var console = new TestSource(new RconConsoleSource(null), 2);
    assertEquals(1, dispatcher.execute("economy diagnostics calendar", console));
    assertEquals(
        1, dispatcher.execute("economy diagnostics calendar after domain/fixture:bank", console));
    assertEquals(List.of("", "domain/fixture:bank"), cursors);
    assertEquals(List.of("calendar", "calendar"), console.messages);
    assertThrows(
        CommandSyntaxException.class,
        () -> dispatcher.execute("economy diagnostics reset", console));
    assertThrows(
        CommandSyntaxException.class,
        () -> dispatcher.execute("economy diagnostics skip", console));
  }

  @Test
  void fxPolicyRejectsUnauthenticatedCommandSources() {
    var dispatcher = new CommandDispatcher<CommandSourceStack>();
    FxAdminCommands.register(
        dispatcher,
        server -> fail("An unauthorized source reached admission"),
        java.time.Clock.systemUTC());
    String command =
        "economy fx policy 665cb020-9894-4c09-a41c-b6fc4e63d03d USD 1.4 3000 100000 50000";
    for (var source :
        List.of(
            new TestSource(CommandSource.NULL, 0),
            new TestSource(CommandSource.NULL, 2),
            new TestSource(new RconConsoleSource(null), 2))) {
      assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(command, source));
    }
  }

  @Test
  void creditCommandsRejectConsoleAutomationAndUnauthenticatedSources() {
    var dispatcher = new CommandDispatcher<CommandSourceStack>();
    CreditCommands.register(
        dispatcher, server -> fail("Unauthenticated caller reached credit service"));
    String id = "665cb020-9894-4c09-a41c-b6fc4e63d03d";
    for (var source :
        List.of(
            new TestSource(CommandSource.NULL, 0),
            new TestSource(CommandSource.NULL, 2),
            new TestSource(new RconConsoleSource(null), 2))) {
      assertThrows(
          CommandSyntaxException.class,
          () -> dispatcher.execute("economy credit facility " + id, source));
      assertThrows(
          CommandSyntaxException.class,
          () ->
              dispatcher.execute(
                  "economy credit inspect " + id + " " + id + " payments after 0 -1 16", source));
      assertThrows(
          CommandSyntaxException.class,
          () -> dispatcher.execute("economy credit edit " + id, source));
      assertTrue(source.messages.isEmpty());
    }
  }

  @Test
  void debtCommandsRejectConsoleAutomationAndUnauthenticatedSources() {
    var dispatcher = new CommandDispatcher<CommandSourceStack>();
    DebtCommands.register(
        dispatcher, server -> fail("Unauthenticated caller reached debt service"));
    String id = "665cb020-9894-4c09-a41c-b6fc4e63d03d";
    for (var source :
        List.of(
            new TestSource(CommandSource.NULL, 0),
            new TestSource(CommandSource.NULL, 2),
            new TestSource(new RconConsoleSource(null), 2))) {
      for (String command :
          List.of(
              "economy debt cases",
              "economy debt reports",
              "economy debt history " + id,
              "economy debt inspect loan " + id + " " + id,
              "economy debt inspect_reports " + id,
              "economy debt inspect_history " + id + " " + id,
              "economy debt payments loan " + id))
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(command, source));
      assertTrue(source.messages.isEmpty());
    }
  }

  private static final class TestSource extends CommandSourceStack {
    private final List<String> messages = new ArrayList<>();

    private TestSource(CommandSource source, int level) {
      super(source, Vec3.ZERO, Vec2.ZERO, null, level, "fixture", Component.empty(), null, null);
    }

    @Override
    public void sendSuccess(Supplier<Component> message, boolean broadcast) {
      assertFalse(broadcast);
      messages.add(message.get().getString());
    }
  }
}
