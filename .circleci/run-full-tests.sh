#!/usr/bin/env bash
set -euo pipefail

# Always distribute the complete discovered suite; PR impact selection is intentionally off.
if [[ "${CIRCLE_NODE_TOTAL:-}" != 4 ]]; then
  echo 'The full-suite job requires exactly four CircleCI test nodes.' >&2
  exit 2
fi
mkdir -p build/circleci/junit build/circleci/batches
python3 - <<'PY' > build/circleci/all-tests.txt
from pathlib import Path
root = Path('src/test/java')
for path in sorted(root.rglob('*Test.java')):
    print('.'.join(path.relative_to(root).with_suffix('').parts))
PY
if [[ ! -s build/circleci/all-tests.txt ]]; then
  echo 'No JUnit test classes were discovered.' >&2
  exit 2
fi
circleci tests split --split-by=timings --timings-type=classname \
  < build/circleci/all-tests.txt > build/circleci/node-tests.txt
filters=()
while IFS= read -r test_class; do
  [[ -z "$test_class" ]] || filters+=("$test_class")
done < build/circleci/node-tests.txt
bash .circleci/run-test-batch.sh build/circleci/junit "${filters[@]}"
