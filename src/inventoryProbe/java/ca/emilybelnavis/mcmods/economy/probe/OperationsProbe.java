package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import com.google.gson.GsonBuilder;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * One opt-in, wall-time dedicated service-actor workload; never a network-client capacity claim.
 */
public final class OperationsProbe {
  private static Path root;
  private static ServerServices services;
  private static OperationsActors actors;
  private static long initial, readyAt, warmAt, sampleAt, recoveryAt, stopAt;
  private static long burstAt, burstRecovered, idleDrained;
  private static int warmIssued, sampleIssued, inFlight, completed, baselinePending;
  private static int[] baselineDepth = new int[3];
  private static boolean preparing, prepared, reconciling, reconciled, failed;
  private static final Map<String, Object> report = new LinkedHashMap<>();
  private static final Map<String, List<Long>> durations = new TreeMap<>();
  private static final List<Map<String, Object>> queueSamples = new ArrayList<>();
  private static final List<String> failures = new ArrayList<>();
  private static String reconciliation;
  private static long lastProgress;
  private static long tickStarted;

  private OperationsProbe() {}

  public static void register() {
    NeoForge.EVENT_BUS.register(OperationsProbe.class);
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public static void beforeTick(ServerTickEvent.Pre event) {
    tickStarted = System.nanoTime();
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void tick(ServerTickEvent.Post event) {
    if (failed || stopAt != 0) return;
    var server = event.getServer();
    long now = System.nanoTime();
    String measuredPhase =
        !prepared
            ? null
            : sampleAt != 0
                ? (recoveryAt == 0 ? "sample" : "recovery")
                : warmAt != 0 ? "warmup" : "idle";
    try {
      if (root == null) {
        root = Path.of(System.getProperty("economy.operationsRoot")).toRealPath();
        require(
            root.getParent().equals(Path.of("/private/tmp").toRealPath())
                && root.getFileName().toString().startsWith("economy-a10-08-"),
            "disposable root");
        require(server.isDedicatedServer(), "dedicated server required");
        require(
            server.getWorldPath(LevelResource.ROOT).toRealPath().startsWith(root), "fixture world");
        initial = now;
        report.put(
            "scope", "20 synthetic service owners; zero network clients; normal production clock");
        report.put("java", System.getProperty("java.runtime.version"));
        report.put("javaVendor", System.getProperty("java.vendor"));
        report.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("schedulingDefaults", SchedulingConfig.defaults());
      }
      require(now - initial < 780_000_000_000L, "13 minute watchdog");
      require(!Files.exists(root.resolve("stop.txt")), "fixture stop requested");
      services = Economy.serverServices(server);
      if (services == null) return;
      require(services.state() != ServerServices.State.UNAVAILABLE, "foundation unavailable");
      if (services.state() != ServerServices.State.READY) return;
      if (!preparing) {
        preparing = true;
        actors = new OperationsActors(server, services);
        actors.outcomeTiming =
            (type, nanos) -> {
              // Warmup has fully drained before sampleAt is assigned. Include every sample
              // confirmation, even when a trailing review completes in the recovery interval.
              if (sampleAt != 0) add("financial_" + type, nanos);
            };
        actors
            .prepare()
            .whenComplete(
                (value, error) -> {
                  if (error != null) fail(server, error);
                  else {
                    prepared = true;
                    readyAt = System.nanoTime();
                  }
                });
        return;
      }
      if (!prepared) return;
      var metrics = services.metrics();
      if (warmAt == 0) {
        if (!metrics.economy().ready()) return;
        tickDuration(server, "tick_idle");
        if (now - readyAt < 10_000_000_000L) return;
        warmAt = now;
        report.put("backgroundBefore", services.macroDiagnostics());
        report.put("economicBefore", metrics.economy());
        log("WARMUP 120s; 20 synthetic owners, 2 top-level actions/s");
      }
      if (sampleAt == 0) {
        tickDuration(server, "tick_warmup");
        while (warmIssued < 240 && now - warmAt >= warmIssued * 500_000_000L)
          issue(server, warmIssued++, false);
        if (now - warmAt < 120_000_000_000L) return;
        require(warmIssued == 240 && inFlight == 0, "warmup complete and no financial backlog");
        sampleAt = now;
        report.put("actorCountersBeforeSample", actors.counters());
        report.put("warmupCompleted", completed);
        log("SAMPLE 300s; one 20-request burst at second180");
      }
      String phase = recoveryAt == 0 ? "sample" : "recovery";
      tickDuration(server, "tick_" + phase);
      var queues = List.of(metrics.storage(), metrics.calculation(), metrics.publication());
      long elapsed = now - sampleAt;
      if (elapsed >= 170_000_000_000L && burstAt == 0) {
        for (int i = 0; i < 3; i++)
          baselineDepth[i] = Math.max(baselineDepth[i], queues.get(i).depth());
        baselinePending = Math.max(baselinePending, inFlight);
      }
      queueSamples.add(
          Map.of(
              "ms",
              elapsed / 1_000_000,
              "phase",
              phase,
              "storage",
              metrics.storage(),
              "calculation",
              metrics.calculation(),
              "publication",
              metrics.publication(),
              "inFlight",
              inFlight,
              "economicStepInFlight",
              metrics.economicStepInFlight()));
      if (recoveryAt == 0) {
        while (sampleIssued < 600) {
          long scheduled =
              sampleIssued >= 360 && sampleIssued < 380
                  ? 180_000_000_000L
                  : sampleIssued * 500_000_000L;
          if (elapsed < scheduled) break;
          if (sampleIssued == 360) burstAt = now;
          issue(server, sampleIssued++, true);
        }
        if (burstAt != 0 && burstRecovered == 0 && sampleIssued >= 380) {
          boolean back = inFlight <= baselinePending;
          for (int i = 0; i < 3; i++) back &= queues.get(i).depth() <= baselineDepth[i];
          if (back) burstRecovered = now;
        }
        if (elapsed >= 300_000_000_000L) {
          require(sampleIssued == 600, "600 sample actions");
          recoveryAt = now;
          report.put("actorCountersAfterSample", actors.counters());
          report.put("backgroundAfter", services.macroDiagnostics());
          report.put("economicAfter", metrics.economy());
          log("RECOVERY 60s; no further workload submissions");
        }
      } else {
        if (idleDrained == 0
            && inFlight == 0
            && queues.stream().allMatch(q -> q.depth() == 0 && q.oldestActiveAgeNanos() == 0))
          idleDrained = now;
        if (now - recoveryAt >= 60_000_000_000L && !reconciling) {
          reconciling = true;
          require(inFlight == 0 && completed == 840, "all 840 accepted action callbacks returned");
          actors
              .reconcile()
              .whenComplete(
                  (result, error) -> {
                    if (error != null) fail(server, error);
                    else {
                      reconciliation = result;
                      reconciled = true;
                    }
                  });
        }
        if (reconciled) finish(server, now);
      }
      if (now - lastProgress >= 30_000_000_000L) {
        lastProgress = now;
        log(
            "progress sampleMs="
                + elapsed / 1_000_000
                + " issued="
                + sampleIssued
                + " completed="
                + completed
                + " inFlight="
                + inFlight);
      }
    } catch (Throwable error) {
      fail(server, error);
    } finally {
      if (measuredPhase != null && tickStarted != 0)
        add("tick_" + measuredPhase, System.nanoTime() - tickStarted);
    }
  }

  private static void issue(MinecraftServer server, int index, boolean sample) {
    long start = System.nanoTime();
    inFlight++;
    actors
        .action(index % 20, index % 100)
        .whenComplete(
            (type, error) -> {
              inFlight--;
              completed++;
              if (error != null) {
                fail(server, error);
                return;
              }
              if (sample) add("action_" + type, System.nanoTime() - start);
            });
  }

  private static void tickDuration(MinecraftServer server, String key) {
    // Native ring excludes Post-event Economy work; inclusive Pre-to-late-Post timing is separate.
    long[] times = server.getTickTimesNanos();
    int slot = Math.floorMod(server.getTickCount(), times.length);
    if (times[slot] > 0) add("native_" + key, times[slot]);
  }

  private static void add(String key, long nanos) {
    durations.computeIfAbsent(key, k -> new ArrayList<>()).add(nanos);
  }

  private static Map<String, Object> stats(List<Long> values) {
    var sorted = values.stream().sorted().toList();
    var result = new LinkedHashMap<String, Object>();
    result.put("count", sorted.size());
    for (var entry : Map.of("p50", 0.50, "p95", 0.95, "p99", 0.99, "max", 1.0).entrySet())
      result.put(
          entry.getKey() + "Ms",
          sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * entry.getValue()) - 1))
              / 1_000_000.0);
    return result;
  }

  private static void budget(String key, double p95, double p99) {
    var s = stats(durations.getOrDefault(key, List.of()));
    require(
        (double) s.get("p95Ms") <= p95 && (double) s.get("p99Ms") <= p99,
        "budget " + key + " " + s);
  }

  private static void finish(MinecraftServer server, long now) throws Exception {
    require(
        durations.get("financial_financial.payment").size() == 120
            && durations.get("financial_financial.trade").size() == 60
            && durations.get("financial_financial.fx").size() == 60,
        "all 240 sample financial timings");
    budget("tick_sample", 50, 100);
    for (var key : durations.keySet()) {
      if (key.startsWith("action_read")) budget(key, 500, 1000);
      if (key.startsWith("financial_")) budget(key, 1000, 2000);
    }
    require(
        burstRecovered != 0 && burstRecovered - burstAt <= 5_000_000_000L, "burst recovery <=5s");
    require(idleDrained != 0 && idleDrained - recoveryAt <= 10_000_000_000L, "idle drain <=10s");
    report.put("burstRecoveryMs", (burstRecovered - burstAt) / 1_000_000.0);
    report.put("idleDrainMs", (idleDrained - recoveryAt) / 1_000_000.0);
    report.put("reconciliation", reconciliation);
    report.put("actorCounters", actors.counters());
    report.put("completedActions", completed);
    report.put("sampleActions", sampleIssued);
    report.put("queueSamples", queueSamples);
    report.put(
        "latencies",
        durations.entrySet().stream()
            .collect(
                java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> stats(e.getValue()))));
    report.put(
        "latencySamplesNanos",
        durations.entrySet().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    Map.Entry::getKey, e -> List.copyOf(e.getValue()))));
    report.put("status", "WORKLOAD_PASSED_AWAITING_SHUTDOWN");
    write();
    actors.close();
    log("WORKLOAD PASSED; requesting normal shutdown");
    stopAt = now;
    server.halt(false);
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public static void stopping(ServerStoppingEvent event) {
    if (stopAt == 0) stopAt = System.nanoTime();
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void stopped(ServerStoppedEvent event) {
    try {
      long elapsed = System.nanoTime() - stopAt;
      report.put("shutdownMs", elapsed / 1_000_000.0);
      report.put("shutdownState", services == null ? "absent" : services.state().name());
      report.put(
          "shutdownFailure",
          services == null || services.failure() == null
              ? "none"
              : ServerServices.failureCode(services.failure()));
      report.put(
          "unresolvedAtShutdown",
          services == null ? -1 : services.metrics().unresolvedAtShutdown());
      require(
          !failed
              && reconciled
              && services.state() == ServerServices.State.STOPPED
              && services.failure() == null
              && services.metrics().unresolvedAtShutdown() == 0
              && elapsed <= 30_000_000_000L,
          "graceful shutdown within30s");
      report.put("status", "PASSED");
      write();
      Files.writeString(
          root.resolve("operations-passed.txt"),
          "PASS 20 synthetic service owners; local dedicated workload; not20 network clients\n");
    } catch (Throwable error) {
      failures.add(error.toString());
      report.put("failures", failures);
      try {
        write();
      } catch (Exception ignored) {
      }
    }
  }

  private static void require(boolean condition, String reason) {
    if (!condition) throw new IllegalStateException(reason);
  }

  private static void fail(MinecraftServer server, Throwable error) {
    if (failed) return;
    failed = true;
    failures.add(error.toString());
    error.printStackTrace();
    report.put("status", "FAILED");
    if (actors != null) report.put("actorCountersAtFailure", actors.counters());
    report.put("failures", failures);
    try {
      if (root != null) {
        write();
        Files.writeString(root.resolve("operations-failed.txt"), error.toString());
      }
    } catch (Exception ignored) {
    }
    if (actors != null) actors.close();
    server.halt(false);
  }

  private static void write() throws Exception {
    Files.writeString(
        root.resolve("operations.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(report));
  }

  private static void log(String message) {
    System.out.println("A10_OPERATIONS " + message);
  }
}
