import http from "k6/http";
import { check } from "k6";
import { Rate, Trend } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://nginx";
const EVENT_ID = __ENV.EVENT_ID || "1";
const USERNAME = __ENV.USERNAME || "user";
const PASSWORD = __ENV.PASSWORD || "1234";
const RATE = Number(__ENV.READ_RPS || "500");
const DURATION = __ENV.DURATION || "30s";

const readOk = new Rate("redis_fallback_read_ok");
const readLatency = new Trend("redis_fallback_read_latency_ms", true);

export const options = {
  discardResponseBodies: true,
  scenarios: {
    read_seats: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: Number(__ENV.READ_VUS || "100"),
      maxVUs: Number(__ENV.READ_MAX_VUS || "300"),
    },
  },
};

export function setup() {
  const response = http.post(
    `${BASE_URL}/login`,
    `username=${encodeURIComponent(USERNAME)}&password=${encodeURIComponent(PASSWORD)}`,
    {
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      timeout: "5s",
      responseType: "text",
    }
  );

  if (response.status !== 200) {
    throw new Error(`login failed: status=${response.status}`);
  }

  const token = response.json("accessToken");
  if (!token) {
    throw new Error("login response did not contain accessToken");
  }
  return { token };
}

export default function (data) {
  const response = http.get(`${BASE_URL}/api/events/${EVENT_ID}/seats`, {
    headers: { Authorization: `Bearer ${data.token}` },
    tags: { name: "redis_fallback_read" },
    timeout: "5s",
  });

  readLatency.add(response.timings.duration);
  readOk.add(response.status === 200);
  check(response, { "seat read returns 200": (res) => res.status === 200 });
}

export function handleSummary(data) {
  const latencyMetric = data.metrics.redis_fallback_read_latency_ms;
  const okMetric = data.metrics.redis_fallback_read_ok;
  const p95 = latencyMetric && latencyMetric.values ? latencyMetric.values["p(95)"] || 0 : 0;
  const p99 = latencyMetric && latencyMetric.values ? latencyMetric.values["p(99)"] || 0 : 0;
  const successRate = okMetric && okMetric.values ? (okMetric.values.rate || 0) * 100 : 0;

  return {
    stdout: [
      "=== Redis Fallback Summary (k6) ===",
      `read_ok_rate=${successRate.toFixed(2)}%`,
      `read_p95=${p95.toFixed(2)}ms`,
      `read_p99=${p99.toFixed(2)}ms`,
      "",
    ].join("\n"),
  };
}
