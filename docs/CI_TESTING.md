# Full-suite CI testing

The CircleCI `test` workflow runs the complete JUnit suite and four automated Minecraft client scenarios. This full-suite gate is explicitly selected for CI; it does not change the project's focused local verification policy.

## Four test nodes

The `test` job has fixed `parallelism: 4`. Every discovered `src/test/java/**/*Test.java` class is assigned to exactly one node using CircleCI timing data, with an initial fallback split when timings are unavailable. Impact-based selection and automatic retries are disabled for this workflow.

Each node runs its JUnit share, followed by one live client scenario:

| Node | Scenario | What it exercises |
| --- | --- | --- |
| 0 | Banking dashboard | Real banking controls, withdrawal review/confirmation, account balance, received cash, original-operation lookup |
| 1 | Banking history | History rows/detail, filters, frozen navigation, historical FX controls |
| 2 | Trader hooks | Native trader hooks and inventory-backed interactions |
| 3 | Trader custody | Inventory capacity, saved delivery evidence, and single-delivery/replay protection |

Minecraft 1.21.1, NeoForge 21.1.252 and Java 21 remain pinned by the project. Each node runs one client at a time under Xvfb and Mesa software rendering. The existing single-player probes create fresh UUID worlds under guarded system temporary directories; they do not open player saves or start a dedicated server. The runner enforces a process deadline and checks both the exit status and explicit success marker. A crash, missing marker, or failure marker fails the scenario.

These scenarios exercise their existing scripted interactions. They do not claim exhaustive screen coverage, visual layout acceptance, dedicated-server networking, or multiplayer acceptance.

## Results and coverage

The job uploads JUnit results for both the JUnit classes and live scenarios. Logs, crash reports, screenshots where captured, and pass/fail markers are retained under `build/circleci/live/`. World directories are excluded from artifacts and workspaces.

JaCoCo instruments the mod in both the JUnit JVM and the live client JVM. Each test node passes only execution data, test assignments and JUnit XML through its own `coverage/node-N` workspace directory. The `aggregate-coverage` job verifies that all discovered classes were assigned exactly once and have results, and that every node supplied JUnit and live execution data. It then generates one combined report:

- `build/reports/jacoco/test/html/index.html`
- `build/reports/jacoco/test/jacocoTestReport.xml`

The combined XML is uploaded to Codecov once. A failing test job remains failed even if aggregation succeeds. Incomplete coverage inputs fail verification; any generated partial report is diagnostic evidence, not a complete-suite coverage claim. Subprocesses launched by existing process-fixture tests are still outside the parent test agent unless separately instrumented.

## Running the same scenarios

The CI runner requires Linux, Java 21, Xvfb, Mesa/OpenGL libraries, and the packages listed in `.circleci/config.yml`:

```bash
python3 .circleci/run-live-probe.py --node 0
```

Use `--node 0` through `--node 3` locally; CircleCI supplies `CIRCLE_NODE_INDEX` automatically. `--timeout` controls the outer process deadline. For local native rendering, retain the existing Gradle probe properties and use a fresh disposable run root with the required scenario prefix. The CI init script attaches client coverage only when `-PcircleciLiveDir=...` is passed; ordinary development clients are unaffected.

The four-node gate uses more compute than a single focused test run. Rollback is a revert of the CI configuration, runner scripts and opt-in probe portability changes; production code and saved data are unaffected. A first successful CircleCI execution is required to establish Linux rendering and actual job duration.

## Setup verification

The configuration passes `circleci config validate`. The mod build, probe-source compilation, `runClient` task inspection and CI-agent dry run pass. A native macOS banking-dashboard run with the CI JaCoCo agent completed its real withdrawal interaction, observed CAD20 cash/CAD80 bank balance, saved screenshots, and shut down with `drained=true`. Its standalone coverage report contained 10,184 covered mod lines, confirming client execution data can be read by the report task. That is scenario-only evidence, not an aggregate percentage.

Runner checks also cover successful subprocess completion, nonzero exits, missing pass markers, timeouts and valid failure XML. Coverage-input checks reject inconsistent assignments/results and missing live execution data. The Linux software-rendering environment and all four parallel clients still require the first CircleCI run.
