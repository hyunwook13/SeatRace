#!/usr/bin/env bash
set -euo pipefail

HOST="${HOST:-http://localhost:8080}"
LABEL="${1:-baseline}"
OUT_DIR="tools/lock-benchmark"
OUT_FILE="${OUT_DIR}/metrics_${LABEL}.txt"

mkdir -p "${OUT_DIR}"

METRICS="$(curl -s "${HOST}/actuator/prometheus")"

{
  echo "# label=${LABEL}"
  echo "# time=$(date -Iseconds)"
  echo "# host=${HOST}"
  echo ""
  echo "## lock metrics"
  echo "${METRICS}" | rg -n "seatrace_lock_acquire_success_total|seatrace_lock_acquire_fail_total|seatrace_lock_acquire_duration|seatrace_lock_release_total" || true
  echo ""
  echo "## optimistic lock metrics"
  echo "${METRICS}" | rg -n "seatrace_hold_optimistic_lock_conflict_total" || true
  echo ""
  echo "## http latency p95/p99"
  echo "${METRICS}" | rg -n "http_server_requests_seconds\\{.*quantile=\"0.95\".*\\}|http_server_requests_seconds\\{.*quantile=\"0.99\".*\\}" || true
  echo ""
  echo "## hikari connection"
  echo "${METRICS}" | rg -n "hikaricp_connections_(active|pending|idle|total)" || true
} > "${OUT_FILE}"

echo "saved: ${OUT_FILE}"
