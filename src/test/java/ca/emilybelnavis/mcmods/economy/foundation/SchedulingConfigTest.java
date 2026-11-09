package ca.emilybelnavis.mcmods.economy.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchedulingConfigTest {
  @TempDir Path directory;

  @Test
  void missingFileAndMissingKeysUseDefaults() throws IOException {
    Path file = directory.resolve("economy-scheduling.properties");
    assertEquals(new SchedulingConfig(2, 256, 64, 1, 5000, 30), SchedulingConfig.load(file));
    Files.writeString(file, "calculationWorkers=16\nsqliteLockTimeoutMillis=200\n");
    assertEquals(new SchedulingConfig(16, 256, 64, 1, 200, 30), SchedulingConfig.load(file));
    Files.writeString(file, "debugGui=true\n");
    assertTrue(SchedulingConfig.load(file).debugGui());
  }

  @Test
  void invalidSettingsIdentifyTheFileAndKey() throws IOException {
    Path file = directory.resolve("economy-scheduling.properties");
    for (String entry :
        new String[] {
          "storageQueueCapacity=4097",
          "calculationQueueCapacity=0",
          "callbackBudgetMillis=1.5",
          "calculationWorkers=17",
          "sqliteLockTimeoutMillis=-1",
          "drainSeconds=301",
          "debugGui=yes",
          "storageWorkers=2"
        }) {
      Files.writeString(file, entry);
      var error = assertThrows(IllegalArgumentException.class, () -> SchedulingConfig.load(file));
      assertTrue(error.getMessage().contains(file.toString()));
      assertTrue(error.getMessage().contains(entry.substring(0, entry.indexOf('='))));
    }
  }

  @Test
  void publicConfigurationFailureOmitsPathAndInput() throws IOException {
    Path file = directory.resolve("private-path.properties");
    Files.writeString(file, "calculationWorkers=private-input");
    var error =
        assertThrows(
            SchedulingConfig.InvalidConfiguration.class, () -> SchedulingConfig.load(file));
    assertEquals("calculationWorkers must be an integer", error.safeReason());
  }
}
