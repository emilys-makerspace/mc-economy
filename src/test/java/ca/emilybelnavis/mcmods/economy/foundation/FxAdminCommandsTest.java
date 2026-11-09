package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FxAdminCommandsTest {
    @TempDir Path directory;

    @Test void revokedQueuedAuthorityCannotEditButOriginalCommittedOperationStillResolves() throws Exception {
        var wall = Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC);
        var runtime = new FoundationRuntime(FoundationRuntime.Configuration.production(wall));
        runtime.initialize(directory.toRealPath());
        try {
            boolean ready = false;
            for (int step = 0; step < 100; step++) if (runtime.startupStep().ready()) { ready = true; break; }
            assertTrue(ready);
            var actor = new PlayerId(UUID.randomUUID());
            var opening = new JournalEngine(runtime, (who, kind) -> who.equals(actor) && kind.equals(FxService.OPEN),
                    Map.of(FxService.OPEN, FxService.opening(wall)));
            assertEquals(Journal.Status.COMMITTED, opening.submit(intent(actor, FxService.OPEN,
                    Map.of("currency", "CAD"))).status());
            var live = new AtomicBoolean(true);
            var edits = new JournalEngine(runtime, FxAdminCommands.admission(actor),
                    Map.of(FxService.EDIT, FxAdminCommands.guardedEditing(wall, live)));
            var first = intent(actor, FxService.EDIT, settings("USD", "110"));
            var committed = edits.submit(first);
            assertEquals(Journal.Status.COMMITTED, committed.status());
            assertEquals("2026-11-04", committed.result().payload(), "Existing next-UTC-midnight policy is retained");
            assertEquals(first.operationId().value(), runtime.readFxDiagnostics().pending().id());
            var replacement = intent(actor, FxService.EDIT, settings("USD", "111"));
            assertEquals(Journal.Status.COMMITTED, edits.submit(replacement).status());
            var saved = runtime.readFxDiagnostics().pending();
            assertEquals(replacement.operationId().value(), saved.id());
            long sequence = runtime.lastCommittedSequence();

            // Admission has happened; disconnect/deop/session shutdown revokes this same atomic lease before worker execution.
            var queued = intent(actor, FxService.EDIT, settings("USD", "112"));
            live.set(false);
            var declined = edits.submit(queued);
            assertEquals(Journal.Status.NOT_ACCEPTED, declined.status());
            assertEquals("ACCESS_DENIED", declined.result().code());
            assertEquals(saved, runtime.readFxDiagnostics().pending());
            assertEquals(sequence, runtime.lastCommittedSequence(), "Revoked authority creates no new policy or journal effect");

            var retry = new Journal.Intent(first.operationId(), new RequestId(UUID.randomUUID()), actor,
                    first.kind(), first.request(), null, null);
            assertEquals(committed, edits.submit(retry), "Original outcome resolves before the new-effect authority gate");
            assertEquals(sequence, runtime.lastCommittedSequence(), "Retry retains the original operation and receipt without another effect");
            var impostor = new PlayerId(UUID.randomUUID());
            assertEquals("ACCESS_DENIED", edits.submit(intent(impostor, FxService.EDIT, settings("USD", "114"))).result().code());
        } finally { runtime.close(); }
    }
    private static Map<String, String> settings(String currency, String baseline) {
        return Map.of("currency", currency, "baseline", baseline, "variation", "0", "pull", "0", "bounds", "50000");
    }
    private static Journal.Intent intent(ActorId actor, Journal.Kind kind, Map<String, String> fields) {
        return new Journal.Intent(new OperationId(UUID.randomUUID()), new RequestId(UUID.randomUUID()), actor,
                kind, new Journal.CanonicalRequest(1, fields), null, null);
    }
}
