#!/usr/bin/env bash
#
# Uploads the packaged Synthea data to the benchmark server, so `server.download` has something to
# download. Skip it if you only care about the upload workloads.
#
#   ./gradlew :benchmarks:core:packageBenchmarkData
#   benchmarks/tools/populate-benchmark-server.sh
#
# Sent as transaction bundles rather than one request per resource: at 50,000 patients that is the
# difference between roughly 840 requests and 167,000. The implementation and its tests live in
# scripts/populate_benchmark_server.py; this wrapper is the documented entry point.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

exec python3 "${REPO_ROOT}/scripts/populate_benchmark_server.py" \
  --base-url "${BASE_URL:-http://localhost:8080/fhir}" \
  --data-dir "${DATA_DIR:-${REPO_ROOT}/benchmarks/core/build/benchmark-data/synthea}" \
  "$@"
