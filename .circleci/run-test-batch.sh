#!/usr/bin/env bash
set -euo pipefail

junit_dir="${1:?Expected the JUnit output directory}"
shift
# An idle node must never fall back to Gradle's unfiltered test task.
if (( $# == 0 )); then
  exit 0
fi

# Each assigned class must execute, even if a restored Gradle cache has matching test outputs.
mkdir -p build/circleci/batches "$junit_dir"
batch_dir="$(mktemp -d build/circleci/batches/batch.XXXXXX)"
filters=()
for test_class in "$@"; do
  filters+=(--tests "$test_class")
done

./gradlew test --rerun --console=plain -x jacocoTestReport -I .circleci/gradle-tests.init.gradle \
  "-PcircleciBatchDir=$batch_dir" "-PcircleciJunitDir=$junit_dir" "${filters[@]}"
