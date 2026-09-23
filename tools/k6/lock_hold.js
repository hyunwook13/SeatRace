import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://nginx";
const EVENT_ID = __ENV.EVENT_ID || "1";
const SEAT_ID = __ENV.SEAT_ID || "1";
const USERNAME = __ENV.USERNAME || "user";
const PASSWORD = __ENV.PASSWORD || "1234";

export const options = {
  scenarios: {
    hold: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.RPS || "100"),
      timeUnit: "1s",
      duration: __ENV.DURATION || "10s",
      preAllocatedVUs: Number(__ENV.PREALLOCATED_VUS || "100"),
      maxVUs: Number(__ENV.MAX_VUS || "200"),
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

  let body;
  try {
    body = loginRes.json();
  } catch (e) {
    const snippet = (loginRes.body || "").slice(0, 200);
    throw new Error(`login returned non-JSON: status=${loginRes.status}, body_prefix=${JSON.stringify(snippet)}`);
  }
  const token = body && body.accessToken;
  if (!token) {
    throw new Error("login did not return accessToken");
  }
  return { token };
}

export default function (data) {
  const res = http.post(
    `${BASE_URL}/api/events/${EVENT_ID}/holds`,
    JSON.stringify({ seatIds: [Number(SEAT_ID)] }),
    {
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${data.token}`,
      },
      tags: { name: "hold" },
      timeout: "5s",
    }
  );

  check(res, {
    "hold success or conflict": (r) =>
      r.status === 200 || r.status === 409 || r.status === 429,
  });

  sleep(0.01);
}
