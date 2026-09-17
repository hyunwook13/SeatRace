import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://nginx";
const EVENT_ID = __ENV.EVENT_ID || "1";
const USER_COUNT = Number(__ENV.USER_COUNT || "200");
const USER_PREFIX = __ENV.USER_PREFIX || "queue-load";
const PASSWORD = __ENV.PASSWORD || "1234";
const SEAT_ID_FROM = Number(__ENV.SEAT_ID_FROM || "1");
const SEAT_ID_TO = Number(__ENV.SEAT_ID_TO || "500");
const READS_PER_USER = Number(__ENV.READS_PER_USER || "3");
const HOLDS_PER_USER = Number(__ENV.HOLDS_PER_USER || "1");
const ADMISSION_TIMEOUT_SECONDS = Number(__ENV.ADMISSION_TIMEOUT_SECONDS || "120");
const POLL_INTERVAL_SECONDS = Number(__ENV.POLL_INTERVAL_SECONDS || "1");

const admissionWaitMs = new Trend("queue_admission_wait_ms", true);
const admittedUsers = new Counter("queue_admitted_users_total");
const waitingResponses = new Counter("queue_waiting_responses_total");
const admissionRejected = new Counter("queue_admission_rejected_total");
const admissionTimeouts = new Counter("queue_admission_timeouts_total");
const protectedReadLatency = new Trend("queue_protected_read_latency_ms", true);
const protectedHoldLatency = new Trend("queue_protected_hold_latency_ms", true);
const protectedReadOk = new Rate("queue_protected_read_ok");
const protectedHoldOk = new Rate("queue_protected_hold_ok");

const seatCount = SEAT_ID_TO - SEAT_ID_FROM + 1;
if (seatCount < USER_COUNT * HOLDS_PER_USER) {
  throw new Error(
    `Need at least USER_COUNT * HOLDS_PER_USER unique seats: have=${seatCount}, required=${USER_COUNT * HOLDS_PER_USER}`
  );
}

export const options = {
  setupTimeout: __ENV.SETUP_TIMEOUT || "5m",
  scenarios: {
    admission_burst: {
      executor: "per-vu-iterations",
      vus: USER_COUNT,
      iterations: 1,
      maxDuration: __ENV.MAX_DURATION || "5m",
      gracefulStop: "10s",
    },
  },
};

function login(username) {
  const res = http.post(
    `${BASE_URL}/login`,
    `username=${encodeURIComponent(username)}&password=${encodeURIComponent(PASSWORD)}`,
    {
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      tags: { name: "login" },
      timeout: "10s",
      responseType: "text",
    }
  );
  if (res.status !== 200) {
    throw new Error(`login failed: user=${username}, status=${res.status}`);
  }
  const body = res.json();
  if (!body || !body.accessToken) {
    throw new Error(`login did not return accessToken: user=${username}`);
  }
  return body.accessToken;
}

function authHeaders(token, queueToken) {
  const headers = { Authorization: `Bearer ${token}` };
  if (queueToken) headers["X-Queue-Token"] = queueToken;
  return headers;
}

function enterAndAwaitAdmission(token) {
  const headers = authHeaders(token, "");
  const startedAt = Date.now();
  const enter = http.post(`${BASE_URL}/api/events/${EVENT_ID}/queue/enter`, null, {
    headers,
    tags: { name: "queue_enter" },
    timeout: "10s",
    responseType: "text",
  });

  if (enter.status !== 200) {
    admissionRejected.add(1);
    return null;
  }

  let body = enter.json();
  if (body && body.admitted) {
    admittedUsers.add(1);
    admissionWaitMs.add(Date.now() - startedAt);
    return body.admissionToken || "";
  }

  waitingResponses.add(1);
  const deadline = startedAt + ADMISSION_TIMEOUT_SECONDS * 1000;
  while (Date.now() < deadline) {
    sleep(POLL_INTERVAL_SECONDS);
    const status = http.get(`${BASE_URL}/api/events/${EVENT_ID}/queue/status`, {
      headers,
      tags: { name: "queue_status" },
      timeout: "10s",
      responseType: "text",
    });
    if (status.status !== 200) continue;

    body = status.json();
    if (body && body.admitted && body.admissionToken) {
      admittedUsers.add(1);
      admissionWaitMs.add(Date.now() - startedAt);
      return body.admissionToken;
    }
    waitingResponses.add(1);
  }

  admissionTimeouts.add(1);
  return null;
}

export function setup() {
  const tokens = [];
  for (let index = 0; index < USER_COUNT; index += 1) {
    tokens.push(login(`${USER_PREFIX}-${index}@queue.local`));
  }
  return { tokens };
}

export default function (data) {
  const userIndex = __VU - 1;
  const token = data.tokens[userIndex];
  const queueToken = enterAndAwaitAdmission(token);
  if (queueToken === null) return;

  const headers = authHeaders(token, queueToken);
  for (let index = 0; index < READS_PER_USER; index += 1) {
    const res = http.get(`${BASE_URL}/api/events/${EVENT_ID}/seats`, {
      headers,
      tags: { name: "protected_seat_read" },
      timeout: "10s",
    });
    protectedReadLatency.add(res.timings.duration);
    protectedReadOk.add(res.status === 200);
    check(res, { "protected seat read is 200": (r) => r.status === 200 });
  }

  for (let index = 0; index < HOLDS_PER_USER; index += 1) {
    const seatId = SEAT_ID_FROM + userIndex * HOLDS_PER_USER + index;
    // k6's bundled compiler does not support object spread syntax.
    const holdHeaders = authHeaders(token, queueToken);
    holdHeaders["Content-Type"] = "application/json";
    const res = http.post(
      `${BASE_URL}/api/events/${EVENT_ID}/holds`,
      JSON.stringify({ seatIds: [seatId] }),
      {
        headers: holdHeaders,
        tags: { name: "protected_hold" },
        timeout: "10s",
      }
    );
    protectedHoldLatency.add(res.timings.duration);
    protectedHoldOk.add(res.status === 200);
    check(res, { "protected hold is 200": (r) => r.status === 200 });
  }
}

function metric(data, name, key) {
  const entry = data.metrics[name];
  return entry && entry.values ? entry.values[key] : null;
}

function text(value, suffix = "") {
  return value === null || value === undefined ? "na" : `${Number(value).toFixed(2)}${suffix}`;
}

export function handleSummary(data) {
  const lines = [
    "Virtual Queue Admission Summary",
    `admitted_users=${text(metric(data, "queue_admitted_users_total", "count"))}`,
    `waiting_responses=${text(metric(data, "queue_waiting_responses_total", "count"))}`,
    `admission_rejected=${text(metric(data, "queue_admission_rejected_total", "count"))}`,
    `admission_timeouts=${text(metric(data, "queue_admission_timeouts_total", "count"))}`,
    `admission_wait_p95=${text(metric(data, "queue_admission_wait_ms", "p(95)"), "ms")}`,
    `protected_read_ok_rate=${text(metric(data, "queue_protected_read_ok", "rate") * 100, "%")}`,
    `protected_read_p95=${text(metric(data, "queue_protected_read_latency_ms", "p(95)"), "ms")}`,
    `protected_hold_ok_rate=${text(metric(data, "queue_protected_hold_ok", "rate") * 100, "%")}`,
    `protected_hold_p95=${text(metric(data, "queue_protected_hold_latency_ms", "p(95)"), "ms")}`,
  ];
  return { stdout: `${lines.join("\n")}\n` };
}
