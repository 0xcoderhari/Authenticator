import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

// ─── Configuration ───
const BASE_URL = __ENV.BASE_URL || "http://localhost:5173";
const TEST_USER_EMAIL = __ENV.TEST_EMAIL || "perf-test@test.com";
const TEST_USER_PASSWORD = __ENV.TEST_PASSWORD || "Test@12345";

// ─── Custom Metrics ───
const loginLatency = new Trend("login_latency");
const profileLatency = new Trend("profile_latency");
const refreshLatency = new Trend("refresh_latency");
const signupFailures = new Counter("signup_failures");
const loginFailures = new Counter("login_failures");

// ─── Scenarios ───
export const options = {
  scenarios: {
    // Ramp-up load test
    load_test: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: "10s", target: 10 },
        { duration: "30s", target: 50 },
        { duration: "10s", target: 0 },
      ],
      exec: "loginFlow",
    },
    // Spike test
    spike_test: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: "5s", target: 100 },
        { duration: "10s", target: 100 },
        { duration: "5s", target: 0 },
      ],
      startTime: "55s",
      exec: "loginFlow",
    },
  },
  thresholds: {
    http_req_duration: ["p(95)<500", "p(99)<1000"],
    login_latency: ["p(95)<300"],
    profile_latency: ["p(95)<200"],
  },
};

// ─── Setup: ensure test user exists ───
export function setup() {
  // Try to signup test user (idempotent)
  const signupRes = http.post(
    `${BASE_URL}/api/auth/signup`,
    JSON.stringify({
      email: TEST_USER_EMAIL,
      password: TEST_USER_PASSWORD,
      confirmPassword: TEST_USER_PASSWORD,
    }),
    { headers: { "Content-Type": "application/json" } }
  );

  // Login to get a session
  const loginRes = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({
      email: TEST_USER_EMAIL,
      password: TEST_USER_PASSWORD,
    }),
    { headers: { "Content-Type": "application/json" } }
  );

  const cookies = loginRes.cookies || {};
  return { cookies };
}

// ─── Main Login Flow Scenario ───
export function loginFlow() {
  const headers = { "Content-Type": "application/json" };

  // 1. Login
  const loginRes = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({
      email: TEST_USER_EMAIL,
      password: TEST_USER_PASSWORD,
    }),
    { headers }
  );

  loginLatency.add(loginRes.timings.duration);

  check(loginRes, {
    "login status 200": (r) => r.status === 200,
    "login < 500ms": (r) => r.timings.duration < 500,
  }) || loginFailures.add(1);

  // Extract cookies from login response
  const jar = http.cookieJar();
  jar.setFromResponse(loginRes, "");

  // 2. Get profile (authenticated)
  const profileRes = http.get(`${BASE_URL}/api/user/profile`, {
    headers: { ...headers, Cookie: jar.cookiesForURL(`${BASE_URL}/api/user/profile`) },
  });

  profileLatency.add(profileRes.timings.duration);

  check(profileRes, {
    "profile status 200": (r) => r.status === 200,
    "profile < 300ms": (r) => r.timings.duration < 300,
  });

  sleep(0.5);
}

// ─── Signup-only scenario ───
export function signupFlow() {
  const randomEmail = `perf-${Date.now()}-${Math.random().toString(36).slice(2)}@test.com`;
  const res = http.post(
    `${BASE_URL}/api/auth/signup`,
    JSON.stringify({
      email: randomEmail,
      password: TEST_USER_PASSWORD,
      confirmPassword: TEST_USER_PASSWORD,
    }),
    { headers: { "Content-Type": "application/json" } }
  );

  check(res, {
    "signup status 201": (r) => r.status === 201,
  }) || signupFailures.add(1);
}
