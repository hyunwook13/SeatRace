import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://nginx";
const EVENT_ID = __ENV.EVENT_ID || "1";

const USERNAME = __ENV.USERNAME || "user";
const PASSWORD = __ENV.PASSWORD || "1234";
const USER_COUNT = Number(__ENV.USER_COUNT || "1");
const USER_PREFIX = __ENV.USER_PREFIX || `load-${Date.now()}`;

const SEAT_ID_FROM = Number(__ENV.SEAT_ID_FROM || "1");
const SEAT_ID_TO = Number(__ENV.SEAT_ID_TO || "100");

const READ_RPS = Number(__ENV.READ_RPS || "500");
const WRITE_RPS = Number(__ENV.WRITE_RPS || "100");
const TOTAL_DURATION = __ENV.DURATION || "30s";
const WRITE_START = __ENV.WRITE_START || "10s";
const WRITE_DURATION = __ENV.WRITE_DURATION || "10s";

const readOk = new Rate("cqrs_read_ok");
const writeOk = new Rate("cqrs_write_ok"); // non-5xx
const write2xx = new Rate("cqrs_write_2xx");
const read2xx = new Rate("cqrs_read_2xx");
const read4xx = new Rate("cqrs_read_4xx");
const read5xx = new Rate("cqrs_read_5xx");
const write4xx = new Rate("cqrs_write_4xx");
const write5xx = new Rate("cqrs_write_5xx");

const readLatency = new Trend("cqrs_read_latency_ms", true);
const writeLatency = new Trend("cqrs_write_latency_ms", true);

function loginOnce() {
  return http.post(
    `${BASE_URL}/login`,
    `username=${encodeURIComponent(USERNAME)}&password=${encodeURIComponent(PASSWORD)}`,
    {
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      tags: { name: "login" },
      timeout: "5s",
      responseType: "text",
    }
  );
}

function loginTokenWithRetry(maxAttempts) {
  for (let i = 0; i < maxAttempts; i++) {
    const res = loginOnce();

    // Request errors (DNS/connection) show up as status=0 with null body.
    if (res.status === 0 || res.status >= 500) {
      sleep(0.2);
      continue;
    }

    const ok = check(res, { "login 200": (r) => r.status === 200 });
    if (!ok) {
      const snippet = (res.body || "").slice(0, 200);
      throw new Error(`login failed: status=${res.status}, body_prefix=${JSON.stringify(snippet)}`);
    }

    const body = res.json();
    if (body && body.accessToken) {
      return body.accessToken;
    }
    throw new Error("login did not return accessToken");
  }

  throw new Error("login failed: request error (status=0) persisted");
}

function signupIfNeeded(username, password, maxAttempts) {
  for (let i = 0; i < maxAttempts; i++) {
    const res = http.post(
      `${BASE_URL}/api/auth/signup`,
      JSON.stringify({
        email: username,
        name: username.slice(0, 20),
        password,
      }),
      {
        headers: { "Content-Type": "application/json" },
        tags: { name: "signup" },
        timeout: "10s",
        responseType: "text",
      }
    );

    if (res.status === 201 || res.status === 409) {
      return;
    }
    if (res.status === 0 || res.status >= 500) {
      sleep(0.2);
      continue;
    }

    const snippet = (res.body || "").slice(0, 200);
    throw new Error(`signup failed: username=${username}, status=${res.status}, body_prefix=${JSON.stringify(snippet)}`);
  }

  throw new Error(`signup failed: transient errors persisted: username=${username}`);
}

function loginToken(username, password, maxAttempts) {
  for (let i = 0; i < maxAttempts; i++) {
    const res = http.post(
      `${BASE_URL}/login`,
      `username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`,
      {
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        tags: { name: "login" },
        timeout: "5s",
        responseType: "text",
      }
    );

    if (res.status === 0 || res.status >= 500) {
      sleep(0.2);
      continue;
    }
    if (res.status !== 200) {
      const snippet = (res.body || "").slice(0, 200);
      throw new Error(`login failed: username=${username}, status=${res.status}, body_prefix=${JSON.stringify(snippet)}`);
    }

    const body = res.json();
    if (body && body.accessToken) {
      return body.accessToken;
    }
    throw new Error(`login did not return accessToken: username=${username}`);
  }

  throw new Error(`login failed: request error persisted: username=${username}`);
}

function pickToken(data) {
  if (!data.tokens || data.tokens.length === 0) {
    return data.token;
  }
  return data.tokens[(__VU - 1) % data.tokens.length];
}

function pickQueueToken(data) {
  if (!data.queueTokens || data.queueTokens.length === 0) {
    return "";
  }
  return data.queueTokens[(__VU - 1) % data.queueTokens.length] || "";
}

function authHeaders(token, queueToken) {
  const headers = { Authorization: `Bearer ${token}` };
  if (queueToken) {
    headers["X-Queue-Token"] = queueToken;
  }
  return headers;
}

function obtainQueueToken(token) {
  const headers = { Authorization: `Bearer ${token}` };
  for (let i = 0; i < Number(__ENV.QUEUE_TOKEN_ATTEMPTS || "15"); i++) {
    const enter = http.post(`${BASE_URL}/api/events/${EVENT_ID}/queue/enter`, null, {
      headers,
      tags: { name: "queue_enter" },
      timeout: "5s",
      responseType: "text",
    });

    if (enter.status === 200) {
      const body = enter.json();
      if (body && body.admitted && body.admissionToken) {
        return body.admissionToken;
      }
    }

    const status = http.get(`${BASE_URL}/api/events/${EVENT_ID}/queue/status`, {
      headers,
      tags: { name: "queue_status" },
      timeout: "5s",
      responseType: "text",
    });
    if (status.status === 200) {
      const body = status.json();
      if (body && body.admitted && body.admissionToken) {
        return body.admissionToken;
      }
    }

    sleep(Number(__ENV.QUEUE_TOKEN_RETRY_SLEEP || "0.2"));
  }
  return "";
}

function pickSeatId() {
  const span = Math.max(1, SEAT_ID_TO - SEAT_ID_FROM + 1);
  const offset = (__VU + __ITER) % span;
  return SEAT_ID_FROM + offset;
}

export const options = {
  setupTimeout: __ENV.SETUP_TIMEOUT || "5m",
  discardResponseBodies: true,
  scenarios: {
    read_bg: {
      executor: "constant-arrival-rate",
      rate: READ_RPS,
      timeUnit: "1s",
      duration: TOTAL_DURATION,
      preAllocatedVUs: Number(__ENV.READ_VUS || "100"),
      maxVUs: Number(__ENV.READ_MAX_VUS || "300"),
      exec: "readSeats",
    },
    write_burst: {
      executor: "constant-arrival-rate",
      rate: WRITE_RPS,
      timeUnit: "1s",
      startTime: WRITE_START,
      duration: WRITE_DURATION,
      preAllocatedVUs: Number(__ENV.WRITE_VUS || "50"),
      maxVUs: Number(__ENV.WRITE_MAX_VUS || "200"),
      exec: "writeHold",
    },
  },
};

export function setup() {
  const tokens = [];
  const queueTokens = [];
  if (USER_COUNT <= 1) {
    tokens.push(loginTokenWithRetry(20));
  } else {
    for (let i = 0; i < USER_COUNT; i++) {
      const username = `${USER_PREFIX}-${i}@load.local`;
      signupIfNeeded(username, PASSWORD, 20);
      tokens.push(loginToken(username, PASSWORD, 20));
    }
  }

  for (let i = 0; i < tokens.length; i++) {
    queueTokens.push(obtainQueueToken(tokens[i]));
  }

  const token = tokens[0];
  const headers = authHeaders(token, queueTokens[0]);

  // Pre-warm the read model (Redis/local cache) if enabled.
  for (let i = 0; i < 3; i++) {
    const res = http.get(`${BASE_URL}/api/events/${EVENT_ID}/seats`, {
      headers,
      tags: { name: "read_warmup" },
      timeout: "5s",
    });
    if (res.status === 200) break;
    sleep(0.2);
  }

  return { token, tokens, queueTokens };
}

export function readSeats(data) {
  const token = pickToken(data);
  const res = http.get(`${BASE_URL}/api/events/${EVENT_ID}/seats`, {
    headers: authHeaders(token, pickQueueToken(data)),
    tags: { name: "read" },
    timeout: "5s",
  });
  readLatency.add(res.timings.duration);
  readOk.add(res.status === 200);
  read2xx.add(res.status >= 200 && res.status < 300);
  read4xx.add(res.status >= 400 && res.status < 500);
  read5xx.add(res.status >= 500);
}

export function writeHold(data) {
  const seatId = pickSeatId();
  const token = pickToken(data);
  const headers = authHeaders(token, pickQueueToken(data));
  headers["Content-Type"] = "application/json";
  const res = http.post(
    `${BASE_URL}/api/events/${EVENT_ID}/holds`,
    JSON.stringify({ seatIds: [seatId] }),
    {
      headers,
      tags: { name: "write" },
      timeout: "5s",
    }
  );

  writeLatency.add(res.timings.duration);
  writeOk.add(res.status > 0 && res.status < 500);
  write2xx.add(res.status >= 200 && res.status < 300);
  write4xx.add(res.status >= 400 && res.status < 500);
  write5xx.add(res.status >= 500);
}

function fmtPct(v) {
  if (v === null || v === undefined || Number.isNaN(v)) return "na";
  return `${(v * 100).toFixed(2)}%`;
}

function fmtMs(v) {
  if (v === null || v === undefined || Number.isNaN(v)) return "na";
  return `${v.toFixed(2)}ms`;
}

export function handleSummary(data) {
  function metricValue(name, key) {
    if (!data || !data.metrics || !data.metrics[name] || !data.metrics[name].values) return null;
    return data.metrics[name].values[key];
  }

  const r = metricValue("cqrs_read_ok", "rate");
  const wOk = metricValue("cqrs_write_ok", "rate");
  const w2xx = metricValue("cqrs_write_2xx", "rate");

  const readP95 = metricValue("cqrs_read_latency_ms", "p(95)");
  const readP99 = metricValue("cqrs_read_latency_ms", "p(99)");
  const writeP95 = metricValue("cqrs_write_latency_ms", "p(95)");
  const writeP99 = metricValue("cqrs_write_latency_ms", "p(99)");
  const read2xxRate = metricValue("cqrs_read_2xx", "rate");
  const read4xxRate = metricValue("cqrs_read_4xx", "rate");
  const read5xxRate = metricValue("cqrs_read_5xx", "rate");
  const write4xxRate = metricValue("cqrs_write_4xx", "rate");
  const write5xxRate = metricValue("cqrs_write_5xx", "rate");
  const httpP95 = metricValue("http_req_duration", "p(95)");
  const httpP99 = metricValue("http_req_duration", "p(99)");
  const waitingP95 = metricValue("http_req_waiting", "p(95)");
  const waitingP99 = metricValue("http_req_waiting", "p(99)");
  const droppedIterations = metricValue("dropped_iterations", "count");

  const lines = [
    "",
    "=== CQRS Isolation Summary (k6) ===",
    `read_ok_rate=${fmtPct(r)} read_p95=${fmtMs(readP95)} read_p99=${fmtMs(readP99)}`,
    `read_2xx_rate=${fmtPct(read2xxRate)} read_4xx_rate=${fmtPct(read4xxRate)} read_5xx_rate=${fmtPct(read5xxRate)}`,
    `write_ok_rate(<500)=${fmtPct(wOk)} write_2xx_rate=${fmtPct(w2xx)} write_4xx_rate=${fmtPct(write4xxRate)} write_5xx_rate=${fmtPct(write5xxRate)}`,
    `write_p95=${fmtMs(writeP95)} write_p99=${fmtMs(writeP99)}`,
    `http_req_duration_p95=${fmtMs(httpP95)} http_req_duration_p99=${fmtMs(httpP99)}`,
    `http_req_waiting_p95=${fmtMs(waitingP95)} http_req_waiting_p99=${fmtMs(waitingP99)}`,
    `dropped_iterations=${droppedIterations == null ? 0 : droppedIterations}`,
    "",
  ].join("\n");

  return { stdout: lines };
}
