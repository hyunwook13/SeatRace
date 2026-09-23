import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://nginx";
const EVENT_ID = __ENV.EVENT_ID || "1";
const USERNAME = __ENV.USERNAME || "user";
const PASSWORD = __ENV.PASSWORD || "1234";

// Total rate: 250k/min ~= 4167 rps
const RPS = parseInt(__ENV.RPS || "4167", 10);
const DURATION = __ENV.DURATION || "60s";

// Mix ratio (0.0 ~ 1.0)
const SEATS_RATIO = parseFloat(__ENV.SEATS_RATIO || "0.9");

// Seat pool size for random selection (1..N)
const SEAT_POOL = parseInt(__ENV.SEAT_POOL || "15000", 10);

// VU sizing (tune for your machine)
const PREALLOCATED_VUS = parseInt(__ENV.PREALLOCATED_VUS || "1000", 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || "3000", 10);
const MAX_P95_MS = parseInt(__ENV.MAX_P95_MS || "5000", 10);

export const options = {
  scenarios: {
    mixed: {
      executor: "constant-arrival-rate",
      rate: RPS,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: PREALLOCATED_VUS,
      maxVUs: MAX_VUS,
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.05"],
    http_req_duration: [`p(95)<${MAX_P95_MS}`],
  },
};

function login() {
  const payload = `username=${encodeURIComponent(USERNAME)}&password=${encodeURIComponent(
    PASSWORD
  )}`;
  const res = http.post(`${BASE_URL}/login`, payload, {
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    tags: { name: "login" },
  });

  // Spring Security form login might respond 200/302 depending on configuration.
  check(res, { "login ok": (r) => r.status === 200 || r.status === 302 });
  if (res.status !== 200 && res.status !== 302) {
    throw new Error(`login failed: status=${res.status}, body=${res.body}`);
  }

  let data = null;
  try {
    data = res.json();
  } catch (e) {
    data = null;
  }
  const token = data && (data.accessToken || data.token) ? (data.accessToken || data.token) : "";
  if (!token) {
    throw new Error(
      `login did not return accessToken (status=${res.status}). ` +
        `Hint: check login response body.`
    );
  }

  return token;
}

export function setup() {
  return { token: login() };
}

function randInt(minInclusive, maxInclusive) {
  return Math.floor(Math.random() * (maxInclusive - minInclusive + 1)) + minInclusive;
}

function seats(token) {
  const res = http.get(`${BASE_URL}/api/events/${EVENT_ID}/seats`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: "seats" },
  });

  check(res, {
    "seats status ok": (r) => r.status === 200 || r.status === 429,
  });
}

function hold(token) {
  const seatId = randInt(1, Math.max(SEAT_POOL, 1));
  const payload = JSON.stringify({ seatIds: [seatId] });

  const headers = {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json",
  };

  const res = http.post(`${BASE_URL}/api/events/${EVENT_ID}/holds`, payload, {
    headers,
    tags: { name: "hold" },
  });

  // 200: success, 409: already taken/conflict, 429: queue blocked (if enabled), 503: infra issue
  check(res, {
    "hold status expected": (r) =>
      r.status === 200 || r.status === 409 || r.status === 429 || r.status === 503,
  });
}

export default function (data) {
  const token = data.token;
  if (Math.random() < SEATS_RATIO) {
    seats(token);
  } else {
    hold(token);
  }

  // Keep near 0 to avoid reducing arrival rate
  if (__ENV.SLEEP_MS) {
    sleep(parseInt(__ENV.SLEEP_MS, 10) / 1000);
  }
}
