import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://nginx";
const EVENT_ID = __ENV.EVENT_ID || "1";
const SEAT_ID = Number(__ENV.SEAT_ID || "1");
const USERNAME = __ENV.USERNAME || "user";
const PASSWORD = __ENV.PASSWORD || "1234";
const VUS = Number(__ENV.VUS || "100");

const status200 = new Counter("hold_status_200");
const status409 = new Counter("hold_status_409");
const status429 = new Counter("hold_status_429");
const status4xxOther = new Counter("hold_status_4xx_other");
const status5xx = new Counter("hold_status_5xx");

export const options = {
  scenarios: {
    same_seat_once: {
      executor: "per-vu-iterations",
      vus: VUS,
      iterations: 1,
      maxDuration: __ENV.MAX_DURATION || "30s",
    },
  },
};

export function setup() {
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

  return { token };
}

export default function (data) {
  const res = http.post(
    `${BASE_URL}/api/events/${EVENT_ID}/holds`,
    JSON.stringify({ seatIds: [SEAT_ID] }),
    {
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${data.token}`,
      },
      tags: { name: "hold" },
      timeout: "5s",
    }
  );

  if (res.status === 200) {
    status200.add(1);
  } else if (res.status === 409) {
    status409.add(1);
  } else if (res.status === 429) {
    status429.add(1);
  } else if (res.status >= 400 && res.status < 500) {
    status4xxOther.add(1);
  } else if (res.status >= 500) {
    status5xx.add(1);
  }

  check(res, {
    "hold expected": (r) => r.status === 200 || r.status === 409 || r.status === 429,
    "no server error": (r) => r.status < 500,
  });
}
