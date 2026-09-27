#!/usr/bin/env bash
#
# Runs the JMH benchmarks (see src/bench/java).
#
#   scripts/bench.sh                              all benchmarks
#   scripts/bench.sh TransformBenchmark           one benchmark
#   scripts/bench.sh AnalysisBenchmark 1024       one benchmark, one image size
#
# Results are written to target/jmh-result.json.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

INCLUDE="${1:-.*Benchmark.*}"
SIZE="${2:-}"

mvn -B -ntp -Pbench test-compile exec:exec \
  -Dbench.include="$INCLUDE" \
  ${SIZE:+-Dbench.size="$SIZE"}
