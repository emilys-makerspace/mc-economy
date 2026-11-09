package ca.emilybelnavis.mcmods.economy.collection;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class CollectionDialoguePolicyTest {
  @Test
  void positiveRepresentableAmountsHaveNoContractualMinimumAndExponentInputRejects() {
    assertEquals(1, CollectionDialogue.parseAmount("0.01", EconomyCurrency.CAD));
    assertEquals(5, CollectionDialogue.parseAmount("0.05", EconomyCurrency.CHF));
    assertEquals(1, CollectionDialogue.parseAmount("0.01", EconomyCurrency.CHF));
    assertEquals(1, CollectionDialogue.parseAmount("1", EconomyCurrency.JPY));
    for (var value :
        List.of("0", "-1", "+1", "1e999999999", "0.0001", " 1", "01", "9223372036854775808"))
      assertThrows(
          RuntimeException.class, () -> CollectionDialogue.parseAmount(value, EconomyCurrency.CAD));
    assertThrows(
        IllegalArgumentException.class,
        () -> CollectionDialogue.parseAmount("0.001", EconomyCurrency.CHF));
    assertThrows(
        ArithmeticException.class,
        () -> CollectionDialogue.parseAmount("0.5", EconomyCurrency.JPY));
  }

  @Test
  void transportRequiresBoundedIdentityActionAndSnapshot() {
    UUID session = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () -> new CollectionNetwork.Action(-1, session, 0, "exit", ""));
    assertThrows(
        NullPointerException.class, () -> new CollectionNetwork.Action(1, null, 0, "exit", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CollectionNetwork.Action(1, session, -1, "exit", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CollectionNetwork.Action(1, session, 0, "edit_debt", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CollectionNetwork.Action(1, session, 0, "preview_partial", "1".repeat(129)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CollectionNetwork.Snapshot(
                1, session, 1, "cases", Collections.nCopies(17, "case"), "", false, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CollectionNetwork.Snapshot(
                1,
                session,
                1,
                "cases",
                List.of(),
                "",
                false,
                Collections.nCopies(
                    17, new CollectionNetwork.Choice("cases", "", "Cases", false))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CollectionNetwork.Snapshot(
                1, session, 1, "arbitrary", List.of(), "", false, List.of()));
  }

  @Test
  void codecsRoundTripAndStaleOrDifferentMenuIdentityRejects() {
    UUID session = UUID.randomUUID();
    var action = new CollectionNetwork.Action(2, session, 7, "preview_partial", "0.05");
    var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    try {
      CollectionNetwork.Action.CODEC.encode(buffer, action);
      assertEquals(action, CollectionNetwork.Action.CODEC.decode(buffer));
    } finally {
      buffer.release();
    }
    var snapshot =
        new CollectionNetwork.Snapshot(
            2,
            session,
            8,
            "review",
            List.of("Debt receives 0.05 CHF"),
            "Confirm the exact debit",
            false,
            List.of(new CollectionNetwork.Choice("confirm", "", "Confirm", false)));
    buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    try {
      CollectionNetwork.Snapshot.CODEC.encode(buffer, snapshot);
      assertEquals(snapshot, CollectionNetwork.Snapshot.CODEC.decode(buffer));
    } finally {
      buffer.release();
    }
    assertTrue(CollectionMenu.identityMatches(2, session, 7, action));
    assertFalse(CollectionMenu.identityMatches(3, session, 7, action));
    assertFalse(CollectionMenu.identityMatches(2, UUID.randomUUID(), 7, action));
    assertFalse(CollectionMenu.identityMatches(2, session, 8, action));
  }

  @Test
  void onlyServerOfferedIdentifiersCanBeSelectedAndExitHasNoPaymentField() {
    String offered = UUID.randomUUID().toString();
    var choices =
        List.of(
            new CollectionNetwork.Choice("select_case", offered, "Case", false),
            new CollectionNetwork.Choice("preview_partial", "", "Partial", false));
    assertTrue(CollectionMenu.offered(choices, "select_case", offered));
    assertFalse(CollectionMenu.offered(choices, "select_case", UUID.randomUUID().toString()));
    assertFalse(CollectionMenu.offered(choices, "confirm", ""));
    assertTrue(CollectionMenu.offered(choices, "preview_partial", "0.01"));
    assertTrue(CollectionMenu.offered(List.of(), "exit", ""));
    assertFalse(CollectionMenu.offered(List.of(), "exit", offered));
  }
}
