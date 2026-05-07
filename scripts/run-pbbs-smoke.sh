#!/usr/bin/env sh
set -eu

# Tiny PBBS JMH smoke run for local/CI validation of the benchmark harness.
# This is intentionally not a performance run.

export MAVEN_OPTS="${MAVEN_OPTS:-} --add-exports=java.base/jdk.internal.vm=ALL-UNNAMED"

mvn -q test-compile exec:java -Pbenchmarks \
  -Dexec.args="PbbsRadixSortBench \
    -p size=128 \
    -p distribution=bounded_random \
    -p threshold=16 \
    -wi 0 \
    -i 1 \
    -r 10ms \
    -f 0 \
    -foe true \
    -rf json \
    -rff target/pbbs-smoke.json"
