import http from "k6/http";
import { check } from "k6";
import { Counter, Trend } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://nginx";
const EVENT_ID = __ENV.EVENT_ID || "1";
const USERNAME = __ENV.USERNAME || "user";
const PASSWORD = __ENV.PASSWORD || "1234";

const RPS = parseInt(__ENV.RPS || "100", 10);
const ON_S = parseInt(__ENV.ON_S || "60", 10);
const OFF_S = parseInt(__ENV.OFF_S || "60", 10);
const RECOVER_S = parseInt(__ENV.RECOVER_S || "0", 10);

const PREALLOCATED_VUS = parseInt(__ENV.PREALLOCATED_VUS || "200", 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || "600", 10);
const MAX_P95_MS = parseInt(__ENV.MAX_P95_MS || "10000", 10);

// Prefer passing this from a wrapper script to align phases across VUs.
const TEST_START_EPOCH_S = parseInt(__ENV.TEST_START_EPOCH_S || "0", 10);

const totalS = ON_S + OFF_S + RECOVER_S;
const duration = `${totalS}s`;

const seatsDurOn = new Trend("seatrace_seats_duration_on_ms", true);
const seatsDurOff = new Trend("seatrace_seats_duration_off_ms", true);
const seatsDurRecover = new Trend("seatrace_seats_duration_recover_ms", true);
const seatsFailOn = new Counter("seatrace_seats_fail_on_total");
const seatsFailOff = new Counter("seatrace_seats_fail_off_total");
const seatsFailRecover = new Counter("seatrace_seats_fail_recover_total");

export const options = {
  scenarios: {
    seats: {
      executor: "constant-arrival-rate",
      rate: RPS,
      timeUnit: "1s",
      duration,
      preAllocatedVUs: PREALLOCATED_VUS,
      maxVUs: MAX_VUS,
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.05"],
    http_req_duration: [`p(95)<${MAX_P95_MS}`],
  },
};

function nowEpochS() {
  return Date.now() / 1000;
}

function phase() {
  const start = TEST_START_EPOCH_S > 0 ? TEST_START_EPOCH_S : nowEpochS();
  const elapsed = nowEpochS() - start;
  if (elapsed < ON_S) return "on";
  if (elapsed < ON_S + OFF_S) return "off";
  return "recover";
}

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
  const token = data && (data.accessToken || data.token) ? data.accessToken || data.token : "";
  if (!token) {
    throw new Error(`login did not return accessToken: ${res.body}`);
  }
  return token;
}

export function setup() {
  return { token: login() };
}

export default function (data) {
  const p = phase();

  const res = http.get(`${BASE_URL}/api/events/${EVENT_ID}/seats`, {
    headers: { Authorization: `Bearer ${data.token}` },
    tags: { name: "seats" },
  });

  const ok = res.status === 200 || res.status === 429;
  check(res, { "seats status ok": () => ok });

  if (p === "on") {
    seatsDurOn.add(res.timings.duration);
    if (!ok) seatsFailOn.add(1);
  } else if (p === "off") {
    seatsDurOff.add(res.timings.duration);
    if (!ok) seatsFailOff.add(1);
  } else {
    seatsDurRecover.add(res.timings.duration);
    if (!ok) seatsFailRecover.add(1);
  }
}

