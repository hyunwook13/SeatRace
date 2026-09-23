#!/usr/bin/env bash
set -euo pipefail

BASE="${1:-baseline}"
LOCK="${2:-distributed}"

BASE_FILE="tools/lock-benchmark/metrics_${BASE}.txt"
LOCK_FILE="tools/lock-benchmark/metrics_${LOCK}.txt"

if [ ! -f "${BASE_FILE}" ] || [ ! -f "${LOCK_FILE}" ]; then
  echo "metrics files not found. run snapshot_metrics.sh first."
  exit 1
fi

echo "=== diff: ${BASE_FILE} vs ${LOCK_FILE} ==="
diff -u "${BASE_FILE}" "${LOCK_FILE}" || true
