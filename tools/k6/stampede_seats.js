import http from "k6/http";
import { check } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://nginx";
const EVENT_ID = __ENV.EVENT_ID || "1";
const USERNAME = __ENV.USERNAME || "user";
const PASSWORD = __ENV.PASSWORD || "1234";

const RPS = parseInt(__ENV.RPS || "1000", 10);
const DURATION = __ENV.DURATION || "90s";

const PREALLOCATED_VUS = parseInt(__ENV.PREALLOCATED_VUS || "500", 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || "1500", 10);
const MAX_P95_MS = parseInt(__ENV.MAX_P95_MS || "5000", 10);

export const options = {
  scenarios: {
    seats: {
      executor: "constant-arrival-rate",
      rate: RPS,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: PREALLOCATED_VUS,
      maxVUs: MAX_VUS,
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
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

export default function (data) {
  const res = http.get(`${BASE_URL}/api/events/${EVENT_ID}/seats`, {
    headers: { Authorization: `Bearer ${data.token}` },
    tags: { name: "seats" },
  });

  check(res, {
    "seats status ok": (r) => r.status === 200 || r.status === 429,
  });
}
