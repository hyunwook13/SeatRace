import http from "k6/http";
import { check } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://nginx";
const TARGET_PATH = __ENV.TARGET_PATH || "/health";

export const options = {
  vus: parseInt(__ENV.VUS || "5", 10),
  duration: __ENV.DURATION || "30s",
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<1000"],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}${TARGET_PATH}`, { tags: { name: TARGET_PATH } });
  check(res, {
    "status ok": (r) => r.status >= 200 && r.status < 400,
  });
}
