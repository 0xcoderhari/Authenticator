import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import exec from 'k6/execution';

// Custom metrics to track specific spans
const loginLatency = new Trend('login_latency');
const profileLatency = new Trend('profile_latency');
const signupSuccessRate = new Rate('signup_success_rate');
const loginSuccessRate = new Rate('login_success_rate');

export const options = {
    // A moderate ramp-up to see how latency handles 10 concurrent virtual users
    stages: [
        { duration: '5s', target: 5 },   // Ramp up to 5 users
        { duration: '15s', target: 10 }, // Stay at 10 users measuring throughput
        { duration: '5s', target: 0 },   // Ramp down
    ],
    thresholds: {
        // Success criteria: 95% of logins must complete within 500ms and 99% within 1s
        login_latency: ['p(95)<500', 'p(99)<1000'],
        signup_success_rate: ['rate>0.95'],
        login_success_rate: ['rate>0.95']
    }
};

const BASE_URL = 'http://localhost:8081';

export default function () {
    // 1. Signup Phase
    // Use unique identifier per virtual user and iteration to avoid email collisions
    const uniqueId = `${exec.vu.idInTest}_${exec.scenario.iterationInTest}_${Date.now()}`;
    const email = `loadtest_${uniqueId}@example.com`;
    const password = "AuthX#2026!";

    const signupPayload = JSON.stringify({
        email: email,
        password: password,
        confirmPassword: password
    });

    const headers = { 'Content-Type': 'application/json' };

    const signupRes = http.post(`${BASE_URL}/api/auth/signup`, signupPayload, { headers });
    
    // Check if account created (assuming 201 Created based on backend)
    const isSignupSuccess = check(signupRes, {
        'signup status is 201': (r) => r.status === 201,
    });
    signupSuccessRate.add(isSignupSuccess);

    if (isSignupSuccess) {
        // 2. Login Phase
        const loginPayload = JSON.stringify({
            email: email,
            password: password
        });

        // Time the login strictly
        const loginRes = http.post(`${BASE_URL}/api/auth/login`, loginPayload, { headers });
        loginLatency.add(loginRes.timings.duration); // use k6 native timings

        const isLoginSuccess = check(loginRes, {
            'login status is 200': (r) => r.status === 200,
            'is not 2FA bound': (r) => r.json('requires2fa') !== true,
            'has auth token': (r) => r.json('token') !== undefined,
        });
        loginSuccessRate.add(isLoginSuccess);

        if (isLoginSuccess) {
            const token = loginRes.json('token');
            const authHeaders = {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`
            };

            // 3. Authorized request (Fast path, bypassing BCrypt)
            const profileRes = http.get(`${BASE_URL}/api/user/profile`, { headers: authHeaders });
            profileLatency.add(profileRes.timings.duration);

            check(profileRes, {
                'profile status is 200': (r) => r.status === 200,
                'email matches test email': (r) => r.json('email') === email
            });
        }
    }

    // Small sleep to simulate realistic user pacing
    sleep(1);
}
