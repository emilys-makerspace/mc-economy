package ca.emilybelnavis.mcmods.economy.recovery;

import static ca.emilybelnavis.mcmods.economy.clock.FinancialCalendar.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FinancialCalendarTest {
  @TempDir Path directory;

  @Test
  void approvedCalendarAndServicingDurationsAreExact() {
    assertEquals(1_200_000L, DAY_MILLIS);
    assertEquals(7 * DAY_MILLIS, WEEK_MILLIS);
    assertEquals(28 * DAY_MILLIS, MONTH_MILLIS);
    assertEquals(364 * DAY_MILLIS, YEAR_MILLIS);
    assertEquals(13 * MONTH_MILLIS, YEAR_MILLIS);
    long anchor = 1_000_123;
    long statement = at(anchor, Period.MONTH, 1);
    assertEquals(anchor + MONTH_MILLIS, statement);
    assertEquals(statement + 14 * DAY_MILLIS, cardMinimumDue(statement));
    assertEquals(statement + 2 * DAY_MILLIS, reportAt(statement));
    assertEquals(statement + 84 * DAY_MILLIS, defaultAt(statement));
    assertEquals(statement + 7 * YEAR_MILLIS, reportExpiry(statement));
    assertEquals(statement + 2 * YEAR_MILLIS, collectionExpiry(statement));
    assertEquals(0, completedPeriods(anchor, anchor + DAY_MILLIS - 1, Period.DAY));
    assertEquals(1, completedPeriods(anchor, anchor + DAY_MILLIS, Period.DAY));
    assertEquals(0, completedPeriods(anchor, 0, Period.MONTH));
  }

  @Test
  void boundaryIdentitiesRetainWorldSubjectAnchorPeriodAndIndex() {
    UUID world = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID facility = UUID.fromString("00000000-0000-0000-0000-000000000002");
    var original = boundary(world, facility, 123, Period.MONTH, 1);
    assertEquals(UUID.fromString("9810b87c-fb63-3b89-9dc7-e25cefaea0a6"), original.id());
    assertEquals(original, boundary(world, facility, 123, Period.MONTH, 1));
    assertEquals(VERSION, original.version());
    assertNotEquals(original.id(), boundary(facility, facility, 123, Period.MONTH, 1).id());
    assertNotEquals(original.id(), boundary(world, world, 123, Period.MONTH, 1).id());
    assertNotEquals(original.id(), boundary(world, facility, 124, Period.MONTH, 1).id());
    assertNotEquals(original.id(), boundary(world, facility, 123, Period.DAY, 28).id());
    assertNotEquals(original.id(), boundary(world, facility, 123, Period.MONTH, 2).id());
    assertEquals(
        original.instantMillis(), boundary(world, facility, 123, Period.DAY, 28).instantMillis());
  }

  @Test
  void rejectsInvalidIndicesVersionsInstantsAndOverflow() {
    assertThrows(IllegalArgumentException.class, () -> at(0, Period.DAY, 0));
    assertThrows(IllegalArgumentException.class, () -> at(0, Period.DAY, -1));
    assertThrows(IllegalArgumentException.class, () -> at(-1, Period.DAY, 1));
    assertThrows(ArithmeticException.class, () -> at(0, Period.DAY, Long.MAX_VALUE));
    assertThrows(ArithmeticException.class, () -> at(Long.MAX_VALUE, Period.DAY, 1));
    assertThrows(ArithmeticException.class, () -> collectionExpiry(Long.MAX_VALUE));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Boundary(UUID.randomUUID(), 0, Period.DAY, 1, 2, DAY_MILLIS));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Boundary(UUID.randomUUID(), 0, Period.DAY, 1, VERSION, DAY_MILLIS + 1));
  }

  @Test
  void strictWorkConfigurationCannotChangeCalendarMeaning() throws Exception {
    Path path = directory.resolve("calendar.properties");
    assertEquals(new Config(16), Config.load(path));
    Files.writeString(path, "pageSize=8\n");
    assertEquals(new Config(8), Config.load(path));
    for (String invalid :
        new String[] {
          "pageSize=0", "pageSize=17", "pagesPerStep=1", "pageSize=1.5", "dayMillis=10"
        }) {
      Files.writeString(path, invalid);
      assertThrows(IllegalArgumentException.class, () -> Config.load(path), invalid);
    }
  }
}
