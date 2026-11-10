#!/usr/bin/env bash
set -euo pipefail

# Always distribute the complete discovered suite; PR impact selection is intentionally off.
if [[ "${CIRCLE_NODE_TOTAL:-}" != 4 ]]; then
  echo 'The full-suite job requires exactly four CircleCI test nodes.' >&2
  exit 2
fi
if [[ ! "${CIRCLE_NODE_INDEX:-}" =~ ^[0-3]$ ]]; then
  echo 'Expected a CircleCI test node index from 0 through 3.' >&2
  exit 2
fi
mkdir -p build/circleci/junit build/circleci/batches
python3 .circleci/ci_inventory.py junit > build/circleci/all-tests.txt
if [[ ! -s build/circleci/all-tests.txt ]]; then
  echo 'No JUnit test classes were discovered.' >&2
  exit 2
fi
# Discover every JUnit class, including new precision tests and nonstandard annotated names.
# Round-robin the sorted class list without impact selection or timing data.
awk -v node="$CIRCLE_NODE_INDEX" '(NR - 1) % 4 == node' \
  build/circleci/all-tests.txt > build/circleci/node-tests.txt
filters=()
while IFS= read -r test_class; do
  [[ -z "$test_class" ]] || filters+=("$test_class")
done < build/circleci/node-tests.txt
bash .circleci/run-test-batch.sh build/circleci/junit "${filters[@]}"
