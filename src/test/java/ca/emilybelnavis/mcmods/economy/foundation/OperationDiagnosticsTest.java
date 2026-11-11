package ca.emilybelnavis.mcmods.economy.foundation;

import static ca.emilybelnavis.mcmods.economy.foundation.OperationDiagnostics.Event.OPERATION_PUBLICATION_FAILED;
import static ca.emilybelnavis.mcmods.economy.foundation.OperationDiagnostics.Event.OPERATION_STORAGE_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OperationDiagnosticsTest {
  @Test
  void logsOnlyCodesAndCorrelationsAndTracksSuppressedFailures() {
    AtomicLong time = new AtomicLong();
    List<String> lines = new ArrayList<>();
    OperationDiagnostics diagnostics = new OperationDiagnostics(time::get, lines::add);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID request = UUID.randomUUID();

    assertNull(diagnostics.lastFailure());
    diagnostics.record(OPERATION_STORAGE_FAILED, first, request);
    diagnostics.record(OPERATION_PUBLICATION_FAILED, first, request);
    diagnostics.record(OPERATION_STORAGE_FAILED, second, request);
    assertEquals(
        List.of(
            "event=OPERATION_STORAGE_FAILED operation_id=" + first + " request_id=" + request,
            "event=OPERATION_PUBLICATION_FAILED operation_id=" + first + " request_id=" + request),
        lines);
    assertEquals(
        new OperationDiagnostics.Failure("OPERATION_STORAGE_FAILED", second),
        diagnostics.lastFailure());

    time.set(TimeUnit.SECONDS.toNanos(10));
    diagnostics.record(OPERATION_STORAGE_FAILED, second, request);
    assertEquals(3, lines.size());
  }
}
