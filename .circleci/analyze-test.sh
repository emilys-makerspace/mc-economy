#!/usr/bin/env bash
set -euo pipefail

coverage_file="${1:?Expected LCOV output path}"
shift
if (( $# != 1 )); then
  echo 'Impact analysis requires exactly one test class' >&2
  exit 1
fi

mkdir -p build/circleci/analysis
impact_dir="$(mktemp -d build/circleci/analysis/atom.XXXXXX)"
# Keep analysis reports separate from normal tests and Codecov coverage.
./gradlew test --tests "$1" jacocoTestReport --console=plain -I .circleci/gradle-tests.init.gradle \
  "-PcircleciBatchDir=$impact_dir" "-PcircleciJunitDir=$impact_dir/junit" \
  "-PcircleciImpactDir=$impact_dir"
python3 .circleci/impact-data.py lcov "$impact_dir/jacoco.xml" "$coverage_file"
