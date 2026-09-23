import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Rate } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://nginx";
const EVENT_ID = __ENV.EVENT_ID || "1";
const USERNAME = __ENV.USERNAME || "user";
const PASSWORD = __ENV.PASSWORD || "1234";

const VUS = Number(__ENV.VUS || "200");
const DURATION = __ENV.DURATION || "30s";
const SEAT_ID_FROM = Number(__ENV.SEAT_ID_FROM || "1");
const SEAT_ID_TO = Number(__ENV.SEAT_ID_TO || "500");
const THINK_TIME_MS = Number(__ENV.THINK_TIME_MS || "0");
const REQUEST_TIMEOUT = __ENV.REQUEST_TIMEOUT || "5s";
const LOG_ERROR_SAMPLES = String(__ENV.LOG_ERROR_SAMPLES || "true").toLowerCase() === "true";
const ERROR_SAMPLE_BODY_CHARS = Number(__ENV.ERROR_SAMPLE_BODY_CHARS || "300");

const status200 = new Counter("random_hold_status_200");
const status400 = new Counter("random_hold_status_400");
const status401 = new Counter("random_hold_status_401");
const status403 = new Counter("random_hold_status_403");
const status404 = new Counter("random_hold_status_404");
const status408 = new Counter("random_hold_status_408");
const status409 = new Counter("random_hold_status_409");
const status422 = new Counter("random_hold_status_422");
const status429 = new Counter("random_hold_status_429");
const status4xxOther = new Counter("random_hold_status_4xx_other");
const status5xx = new Counter("random_hold_status_5xx");
const successfulHoldRate = new Rate("random_hold_success_rate");
const expectedStatusRate = new Rate("random_hold_expected_status_rate");
let loggedUnexpectedSample = false;

if (SEAT_ID_TO < SEAT_ID_FROM) {
  throw new Error(`SEAT_ID_TO must be >= SEAT_ID_FROM: from=${SEAT_ID_FROM}, to=${SEAT_ID_TO}`);
}

export const options = {
  scenarios: {
    random_seat_hold: {
      executor: "constant-vus",
      vus: VUS,
      duration: DURATION,
      gracefulStop: __ENV.GRACEFUL_STOP || "5s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.05"],
    http_req_duration: [`p(95)<${Number(__ENV.MAX_P95_MS || "3000")}`],
    random_hold_expected_status_rate: ["rate>0.99"],
    random_hold_success_rate: [`rate>${Number(__ENV.MIN_SUCCESS_RATE || "0.01")}`],
  },
};

function randomSeatId() {
  const width = SEAT_ID_TO - SEAT_ID_FROM + 1;
  return SEAT_ID_FROM + Math.floor(Math.random() * width);
}

function login() {
  const loginRes = http.post(
    `${BASE_URL}/login`,
    `username=${encodeURIComponent(USERNAME)}&password=${encodeURIComponent(PASSWORD)}`,
    {
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      tags: { name: "login" },
    }
  );

  const ok = check(loginRes, { "login status 200": (r) => r.status === 200 });
  if (!ok) {
    const snippet = (loginRes.body || "").slice(0, 200);
    throw new Error(`login failed: status=${loginRes.status}, body_prefix=${JSON.stringify(snippet)}`);
  }

  const token = loginRes.json("accessToken");
  if (!token) {
    throw new Error("login did not return accessToken");
  }

  return token;
}

export function setup() {
  return { token: login() };
}

export default function (data) {
  const seatId = randomSeatId();
  const requestId = `k6-random-hold-vu${__VU}-iter${__ITER}-seat${seatId}`;
  const res = http.post(
    `${BASE_URL}/api/events/${EVENT_ID}/holds`,
    JSON.stringify({ seatIds: [seatId] }),
    {
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${data.token}`,
        "X-Request-Id": requestId,
      },
      tags: { name: "random_hold" },
      timeout: REQUEST_TIMEOUT,
    }
  );

  if (res.status === 200) {
    status200.add(1);
  } else if (res.status === 400) {
    status400.add(1);
  } else if (res.status === 401) {
    status401.add(1);
  } else if (res.status === 403) {
    status403.add(1);
  } else if (res.status === 404) {
    status404.add(1);
  } else if (res.status === 408) {
    status408.add(1);
  } else if (res.status === 409) {
    status409.add(1);
  } else if (res.status === 422) {
    status422.add(1);
  } else if (res.status === 429) {
    status429.add(1);
  } else if (res.status >= 400 && res.status < 500) {
    status4xxOther.add(1);
  } else if (res.status >= 500) {
    status5xx.add(1);
  }

  const expected = res.status === 200 || res.status === 409 || res.status === 429;
  logUnexpectedSample(res, requestId, seatId, expected);
  expectedStatusRate.add(expected);
  successfulHoldRate.add(res.status === 200);

  check(res, {
    "hold expected status": () => expected,
    "no server error": (r) => r.status < 500,
  });

  if (THINK_TIME_MS > 0) {
    sleep(THINK_TIME_MS / 1000);
  }
}

function logUnexpectedSample(res, requestId, seatId, expected) {
  if (!LOG_ERROR_SAMPLES || expected) {
    return;
  }

  // Keep logs bounded: at most one unexpected response sample per VU.
  if (loggedUnexpectedSample) {
    return;
  }
  loggedUnexpectedSample = true;

  const body = String(res.body || "").slice(0, ERROR_SAMPLE_BODY_CHARS).replace(/\s+/g, " ");
  console.error(
    `unexpected_hold_response requestId=${requestId} status=${res.status} seatId=${seatId} ` +
      `durationMs=${res.timings.duration} body=${JSON.stringify(body)}`
  );
}
