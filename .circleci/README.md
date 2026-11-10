# CircleCI test coverage

The four test nodes run the complete JUnit inventory and every autonomous integrated-client scenario. Selection is deterministic round-robin; test impact selection and timing-based splitting remain disabled. Current inventory: 178 JUnit classes, 22 client scenarios and 23 client processes. These counts are informational; discovery and validation use the actual source inventory.

`ci_inventory.py` is shared by the runners and the aggregate coverage gate. It discovers conventional and annotated JUnit classes, including test files with nonstandard names. The live inventory includes dashboard/history, trader hooks/custody, kiosks, branch generation/placement, bankers, village-bank persistence, simulation production, valuation/recipes/brewing/variants/API, reference traders, all three layout modes, debt composition and admin settings. Adding a new autonomous Gradle client fixture without a matching assignment fails inventory validation.

Each JUnit assignment is passed explicitly to Gradle and forced to execute, rather than relying on cached task outputs. Empty assignments never fall back to an unfiltered test run. Each client scenario runs in its own freshly created temporary root; the village-bank scenario preserves that root across its two ordered processes. Each phase has separate JUnit XML, logs, pass/fail markers and JaCoCo data. One scenario's failure does not prevent other assigned scenarios from running. A failed setup phase prevents its dependent restart phase; interruption stops the runner.

The aggregate gate requires identical current discoveries, exactly-once assignments, successful setup/JUnit/live statuses, passing unskipped JUnit reports and nonempty execution data for every client phase. Partial evidence is retained for diagnosis but cannot be published as complete coverage.

## Scope

The user selected all JUnit tests and automated client probes. Dedicated-server fixtures/workload/restore and authenticated external-machine multiplayer remain outside this workflow. `bankingWorld`/`financeWorld` reopen server-prepared saves; they are not autonomous client scenarios. The screenshot-only terminal preview requires manual interaction and has no automatic completion marker. No runner accepts a dedicated-server EULA or opens an existing player world.

## Validation

Runner contract tests use real subprocesses with temporary fake Gradle/display executables, not Minecraft:

```
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s .circleci -p test_ci_runners.py -v
bash -n .circleci/run-full-tests.sh .circleci/run-test-batch.sh
circleci config validate .circleci/config.yml
python3 .circleci/run-live-probe.py --node 0 --list
```

The setup step runs the seven runner contract tests in CircleCI as well. Local verification passed all seven cases (five initially, two after explicitly excluding the server-prepared `financeWorld` path), CircleCI configuration validation and shell syntax. The three Linux-compatible fixture changes compiled with `compileInventoryProbeJava`; Java/shell format checks passed after formatting those edits. No full JUnit suite or actual Minecraft client was launched locally for this CI change, and no remote CircleCI pipeline was triggered.

Runtime will increase because each node now runs five or six client scenarios instead of one; the village-bank scenario has two client processes. Existing four-way parallelism and the 900-second per-process deadline remain. No measured remote duration is claimed. To roll back, revert this CI inventory/runner/config change; no database migration or player-world change is involved. Preserve unrelated SonarQube configuration edits.
