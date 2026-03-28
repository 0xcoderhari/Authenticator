# AuthX — Full-Stack Authentication Service

A production-grade, full-stack authentication service built with **Spring Boot 3.5** (Java 21) on the backend and **React 18** with Vite on the frontend. Supports password-based login, Google OAuth 2.0, TOTP-based two-factor authentication, magic links, email verification, password reset, session management with token rotation, rate limiting, audit logging, and an admin panel.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Tech Stack](#tech-stack)
- [Features](#features)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Setup & Installation](#setup--installation)
  - [Database](#database)
  - [Redis](#redis)
  - [Backend](#backend)
  - [Frontend](#frontend)
- [Configuration](#configuration)
- [API Reference](#api-reference)
  - [Auth Endpoints (`/api/auth`)](#auth-endpoints-apiauth)
  - [User Endpoints (`/api/user`)](#user-endpoints-apiuser)
  - [Admin Endpoints (`/api/admin`)](#admin-endpoints-apiadmin)
  - [Test & Benchmark Endpoints](#test--benchmark-endpoints)
- [Authentication Flows](#authentication-flows)
  - [Password Login](#password-login)
  - [Signup & Email Verification](#signup--email-verification)
  - [Google OAuth](#google-oauth)
  - [Two-Factor Authentication (TOTP)](#two-factor-authentication-totp)
  - [Magic Link (Passwordless)](#magic-link-passwordless)
  - [Password Reset](#password-reset)
  - [Session Management & Token Rotation](#session-management--token-rotation)
- [Security](#security)
- [Database Schema](#database-schema)
- [Frontend](#frontend-1)
- [Benchmark Results](#benchmark-results)

---

## Architecture Overview

```
Browser (React SPA)
    |
    v
[Vite Dev Proxy :5173] --> [Spring Boot :8081]
    |
    v
[RateLimitFilter] --> [JwtFilter] --> [SecurityFilterChain]
    |
    +--> /api/auth/*    --> AuthController    --> AuthService
    +--> /api/user/*    --> UserController    --> RefreshTokenService, TwoFactorService
    +--> /api/admin/*   --> AdminController   --> AuditService
    +--> /api/test/*    --> TestController
    +--> /api/benchmark --> BenchmarkController

Internal Services:
    AuthService
        -> JwtService             (JWT generation & validation)
        -> RefreshTokenService    (session management, token rotation, replay detection)
        -> ActionTokenService     (single-use tokens for email verify, password reset, magic links)
        -> TwoFactorService       (TOTP secret generation & code verification)
        -> MailService            -> EmailQueueProducer -> [Redis email:queue]
        -> AuditService           (async audit logging)
        -> LoginAlertService      (new device detection, IP geolocation)

    EmailQueueConsumer -> [Redis email:queue] -> MailService.executeSendEmail()

Data Stores:
    MySQL     -> users, refresh_tokens, action_tokens, audit_logs
    Redis     -> email:queue (async email delivery), email:dlq (dead letter queue)
```

---

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 21 |
| Framework | Spring Boot | 3.5.11 |
| Security | Spring Security 6 | — |
| ORM | Spring Data JPA / Hibernate | — |
| JWT | jjwt (io.jsonwebtoken) | 0.12.7 |
| TOTP | dev.samstevens.totp | 1.7.1 |
| Google Auth | google-api-client | 2.7.2 |
| Database | MySQL | 8.x+ |
| Cache/Queue | Redis | — |
| Email | Spring Boot Mail (SMTP) | — |
| Build | Maven | — |
| Frontend | React | 18.3.1 |
| Build Tool | Vite | 5.4.19 |
| Styling | Vanilla CSS (custom design system) | — |

---

## Features

### Authentication
- Email + password registration and login with BCrypt password hashing
- Google OAuth 2.0 (implicit flow with ID token verification)
- TOTP-based two-factor authentication (Google Authenticator, Authy, etc.)
- Magic link (passwordless email login)
- Email verification for new accounts
- Password reset via email

### Session Management
- Opaque refresh tokens (not JWT) with SHA-256 hashing in database
- Token rotation on every refresh (old token invalidated)
- Replay attack detection — if a stolen refresh token is reused, all sessions for that user are revoked
- Multi-device session tracking with device, IP, and user-agent metadata
- Individual session revocation and logout-all-sessions
- Account lockout after configurable failed login attempts

### Security
- HTTP-only, SameSite cookies for tokens (no localStorage)
- JWT access tokens with 15-minute TTL
- Refresh tokens with 7-day TTL
- Rate limiting on auth endpoints (20 requests/minute per IP)
- CORS configuration with credential support
- Stateless server-side sessions (no HTTP session)

### Admin Panel
- User listing with search
- Lock/unlock accounts (temporary or permanent)
- Delete user accounts
- View and revoke any active session
- Paginated audit log viewer

### Email System
- Asynchronous email delivery via Redis-backed queue
- Automatic retry (up to 3 attempts) with dead letter queue
- Email types: verification, password reset, magic link, new device alert
- New device login alerts with IP geolocation

### Audit Logging
- Asynchronous audit logging for all security events
- Events: signup, login success/failure, logout, email verified, password reset, session revoked, account locked/unlocked, 2FA enabled/disabled, Google login, token refreshed

---

## Project Structure

```
auth_project/
├── backend/                          # Spring Boot application
│   ├── pom.xml                       # Maven dependencies
│   ├── src/main/java/com/authx/authservice/
│   │   ├── AuthServiceApplication.java
│   │   ├── config/
│   │   │   ├── SecurityConfig.java       # Spring Security filter chain, CORS
│   │   │   ├── JwtFilter.java            # JWT extraction & validation filter
│   │   │   ├── RateLimitFilter.java      # In-memory rate limiter
│   │   │   ├── RedisConfig.java          # Redis connection & serializers
│   │   │   └── WebConfig.java            # Web MVC CORS configuration
│   │   ├── controller/
│   │   │   ├── AuthController.java       # Public auth endpoints
│   │   │   ├── UserController.java       # Authenticated user endpoints
│   │   │   ├── AdminController.java      # Admin-only endpoints
│   │   │   ├── TestController.java       # Token generation for testing
│   │   │   ├── BenchmarkController.java  # Benchmark UI
│   │   │   └── GlobalExceptionHandler.java
│   │   ├── dto/
│   │   │   ├── SignupRequest.java
│   │   │   ├── LoginRequest.java
│   │   │   ├── GoogleLoginRequest.java
│   │   │   ├── Verify2FaRequest.java
│   │   │   ├── MagicLinkRequest.java
│   │   │   ├── VerifyMagicLinkRequest.java
│   │   │   ├── ForgotPasswordRequest.java
│   │   │   ├── ResetPasswordRequest.java
│   │   │   ├── ResendVerificationRequest.java
│   │   │   ├── AuthResponse.java
│   │   │   ├── UserProfileResponse.java
│   │   │   ├── SessionResponse.java
│   │   │   ├── AdminSessionResponse.java
│   │   │   ├── AuditLogResponse.java
│   │   │   ├── LockAccountRequest.java
│   │   │   └── EmailMessage.java
│   │   ├── entity/
│   │   │   ├── User.java
│   │   │   ├── RefreshToken.java
│   │   │   ├── ActionToken.java
│   │   │   ├── AuditLog.java
│   │   │   ├── Role.java                 # USER, ADMIN
│   │   │   ├── ActionTokenPurpose.java   # EMAIL_VERIFICATION, PASSWORD_RESET, MAGIC_LINK
│   │   │   └── AuditAction.java          # 16 audit event types
│   │   ├── exception/
│   │   │   └── EmailDeliveryException.java
│   │   ├── repository/
│   │   │   ├── UserRepository.java
│   │   │   ├── RefreshTokenRepository.java
│   │   │   ├── ActionTokenRepository.java
│   │   │   └── AuditLogRepository.java
│   │   └── service/
│   │       ├── AuthService.java          # Core auth orchestration
│   │       ├── JwtService.java           # JWT operations
│   │       ├── RefreshTokenService.java  # Session & token rotation
│   │       ├── RefreshTokenCookieService.java  # Cookie management
│   │       ├── ActionTokenService.java   # Single-use action tokens
│   │       ├── OpaqueTokenService.java   # Opaque token generation
│   │       ├── TwoFactorService.java     # TOTP operations
│   │       ├── MailService.java          # Email composition & queueing
│   │       ├── EmailQueueProducer.java   # Redis queue producer
│   │       ├── EmailQueueConsumer.java   # Redis queue consumer
│   │       ├── AuditService.java         # Async audit logging
│   │       └── LoginAlertService.java    # New device detection
│   └── src/main/resources/
│       └── application.properties
├── frontend/                         # React SPA
│   ├── package.json
│   ├── vite.config.js                # Vite config with API proxy
│   ├── .env                          # Google Client ID
│   ├── index.html
│   └── src/
│       ├── main.jsx                  # Entry point
│       ├── App.jsx                   # Monolithic single-component app
│       └── styles.css                # 999-line design system
├── report.html                       # Email latency benchmark
├── optimized.html                    # Full backend latency benchmark
├── todo.md
└── env                               # Environment variables (shell format)
```

---

## Prerequisites

- **Java 21** (JDK)
- **Maven 3.8+**
- **MySQL 8.x+** running on `localhost:3306`
- **Redis** running on `localhost:6379`
- **Node.js 18+** and npm (for frontend)
- **Google OAuth Client ID** (for Google sign-in)

---

## Setup & Installation

### Database

Create a MySQL database and user:

```sql
CREATE DATABASE authdb;
CREATE USER 'hari'@'localhost' IDENTIFIED BY 'your_password';
GRANT ALL PRIVILEGES ON authdb.* TO 'hari'@'localhost';
FLUSH PRIVILEGES;
```

Tables are auto-created by Hibernate (`spring.jpa.hibernate.ddl-auto=update`).

### Redis

Start Redis on the default port:

```bash
redis-server
```

Redis is used as an async message broker for the email queue.

### Backend

```bash
cd backend

# Set environment variables (or use application.properties defaults)
export JWT_SECRET="your-strong-secret-key-at-least-64-characters-long"
export REDIS_HOST=localhost
export REDIS_PORT=6379
export SERVER_PORT=8081

# Build and run
./mvnw spring-boot:run
```

The backend starts on `http://localhost:8081`.

### Frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend starts on `http://localhost:5173`. The Vite dev server proxies all `/api` requests to `http://localhost:8081`.

---

## Configuration

All configuration is in `backend/src/main/resources/application.properties`. Environment variables override defaults.

| Property | Env Variable | Default | Description |
|----------|-------------|---------|-------------|
| `server.port` | `SERVER_PORT` | `8081` | Backend server port |
| `jwt.secret` | `JWT_SECRET` | (default) | HMAC-SHA key for JWT signing |
| `app.frontend-url` | `FRONTEND_URL` | `http://localhost:5173` | Frontend URL for email links |
| `app.security.access-token-minutes` | `ACCESS_TOKEN_MINUTES` | `15` | Access token TTL |
| `app.security.refresh-token-days` | `REFRESH_TOKEN_DAYS` | `7` | Refresh token TTL |
| `app.security.email-verification-minutes` | `EMAIL_VERIFICATION_MINUTES` | `30` | Verification link TTL |
| `app.security.password-reset-minutes` | `PASSWORD_RESET_MINUTES` | `15` | Password reset link TTL |
| `app.security.max-failed-logins` | `MAX_FAILED_LOGINS` | `5` | Failed attempts before lockout |
| `app.security.lockout-duration-minutes` | `LOCKOUT_DURATION_MINUTES` | `15` | Account lockout duration |
| `app.security.access-cookie-name` | `ACCESS_COOKIE_NAME` | `access_token` | Access token cookie name |
| `app.security.refresh-cookie-name` | `REFRESH_COOKIE_NAME` | `refresh_token` | Refresh token cookie name |
| `app.security.secure-cookies` | `SECURE_COOKIES` | `false` | Require HTTPS for cookies |
| `app.security.refresh-cookie-same-site` | `REFRESH_COOKIE_SAME_SITE` | `Lax` | SameSite cookie attribute |
| `app.google.client-id` | — | (hardcoded) | Google OAuth client ID |
| `app.email.consumer.enabled` | `EMAIL_CONSUMER_ENABLED` | `true` | Enable email queue consumer |
| `spring.data.redis.host` | `REDIS_HOST` | `localhost` | Redis host |
| `spring.data.redis.port` | `REDIS_PORT` | `6379` | Redis port |
| `spring.data.redis.password` | `REDIS_PASSWORD` | (empty) | Redis password |
| `spring.datasource.url` | — | `jdbc:mysql://localhost:3306/authdb` | MySQL JDBC URL |
| `spring.datasource.username` | — | `hari` | MySQL username |
| `spring.datasource.password` | — | — | MySQL password |
| `spring.mail.host` | — | `smtp.gmail.com` | SMTP server |
| `spring.mail.username` | — | — | SMTP username (Gmail) |
| `spring.mail.password` | — | — | SMTP app password |

---

## API Reference

### Auth Endpoints (`/api/auth`)

**Public — no authentication required.**

| Method | Path | Body / Params | Description |
|--------|------|--------------|-------------|
| `POST` | `/api/auth/signup` | `{ email, password, confirmPassword }` | Register a new account. Sends verification email. |
| `POST` | `/api/auth/login` | `{ email, password }` | Login. Returns tokens or pre-auth token if 2FA enabled. |
| `POST` | `/api/auth/verify-2fa` | `{ token, code }` | Verify TOTP code during login (2FA step). |
| `POST` | `/api/auth/refresh` | (cookie) | Rotate refresh token, issue new access token. |
| `POST` | `/api/auth/google` | `{ idToken }` | Google OAuth login with ID token. |
| `POST` | `/api/auth/logout` | (cookie) | Revoke current session, clear cookies. |
| `POST` | `/api/auth/magic-link` | `{ email }` | Request a passwordless magic link email. |
| `POST` | `/api/auth/magic-link/verify` | `{ token }` | Verify a magic link token. |
| `POST` | `/api/auth/forgot-password` | `{ email }` | Request a password reset email. |
| `POST` | `/api/auth/verify-email` | `?token=<token>` | Verify email address with token. |
| `POST` | `/api/auth/reset-password` | `{ token, password, confirmPassword }` | Reset password with token. |
| `POST` | `/api/auth/resend-verification` | `{ email }` | Resend the verification email. |

### User Endpoints (`/api/user`)

**Requires authentication (any role).**

| Method | Path | Body / Params | Description |
|--------|------|--------------|-------------|
| `GET` | `/api/user/me` | — | Get current user profile. |
| `GET` | `/api/user/profile` | — | Get current user profile (alias). |
| `GET` | `/api/user/sessions` | — | List active sessions for current user. |
| `DELETE` | `/api/user/sessions/{sessionId}` | — | Revoke a specific session. |
| `POST` | `/api/user/sessions/logout-all` | — | Revoke all sessions. |
| `POST` | `/api/user/2fa/generate` | — | Generate TOTP secret and QR code. |
| `POST` | `/api/user/2fa/enable` | `{ code }` | Enable 2FA after verifying a TOTP code. |
| `POST` | `/api/user/2fa/disable` | `{ code }` | Disable 2FA after verifying a TOTP code. |

### Admin Endpoints (`/api/admin`)

**Requires `ADMIN` role.**

| Method | Path | Body / Params | Description |
|--------|------|--------------|-------------|
| `GET` | `/api/admin/users` | `?q=<search>` | List/search users (max 50). |
| `GET` | `/api/admin/sessions` | — | List all active sessions. |
| `DELETE` | `/api/admin/sessions/{sessionId}` | — | Revoke any session. |
| `GET` | `/api/admin/audit-logs` | `?page=0&size=20&userId=<id>` | Paginated audit logs. |
| `POST` | `/api/admin/users/{userId}/unlock` | — | Unlock a locked account. |
| `POST` | `/api/admin/users/{userId}/lock` | `{ type, durationMinutes }` | Lock account (temporary or permanent). |
| `DELETE` | `/api/admin/users/{userId}` | — | Delete a user account. |

### Test & Benchmark Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/test/generate-token` | Generate action tokens for benchmarking. **Remove in production.** |
| `GET` | `/api/benchmark/html` | Interactive email latency benchmark UI. |

---

## Authentication Flows

### Password Login

```
1. User submits email + password
2. Backend normalizes email, finds user
3. Checks account lockout status
4. Validates password (BCrypt)
5. On failure: increments failed attempts, may lock account (5 attempts = 15min lockout)
6. On success: resets failed attempts
7. If 2FA enabled: returns pre-auth JWT (type=pre_auth, no session yet)
8. If no 2FA: creates session (opaque refresh token), issues access JWT
9. Sets httpOnly cookies for both tokens
```

### Signup & Email Verification

```
1. User submits email + password + confirm password
2. Backend validates password strength (8-128 chars, upper+lower+digit+special)
3. Checks for duplicate email
4. Creates User with BCrypt password, isVerified=false
5. Generates single-use verification token (opaque, SHA-256 hashed)
6. Queues verification email via Redis
7. User clicks link: frontend/?verify=<token>
8. Frontend POSTs to /api/auth/verify-email?token=<token>
9. Backend consumes token, sets isVerified=true
```

### Google OAuth

```
1. Frontend redirects to Google OAuth (implicit flow)
2. Google returns ID token in URL hash (#id_token=...)
3. Frontend extracts ID token, POSTs to /api/auth/google
4. Backend verifies ID token with Google's public keys
5. Extracts googleId, email, emailVerified from payload
6. If user exists: links Google account, verifies email if needed
7. If new user: creates User with googleId, no password
8. Creates session, issues access JWT, sets cookies
```

### Two-Factor Authentication (TOTP)

**Setup:**
```
1. User requests 2FA setup (POST /api/user/2fa/generate)
2. Backend generates TOTP secret (6-digit, 30s period, SHA1)
3. Returns QR code (data URI) for scanning with authenticator app
4. User scans QR, enters 6-digit code
5. Backend verifies code against secret (POST /api/user/2fa/enable)
6. Sets isTwoFactorEnabled=true, stores secret
```

**Login with 2FA:**
```
1. Password login returns requires2fa=true + preAuthToken
2. Frontend shows 6-digit code input
3. User enters code from authenticator app
4. Frontend POSTs to /api/auth/verify-2fa with preAuthToken + code
5. Backend validates code, creates session, issues tokens
```

### Magic Link (Passwordless)

```
1. User enters email, requests magic link
2. Backend generates single-use MAGIC_LINK token
3. Queues magic link email via Redis
4. User clicks link: frontend/?magic=<token>
5. Frontend POSTs to /api/auth/magic-link/verify
6. Backend consumes token, creates session (handles 2FA if enabled)
```

### Password Reset

```
1. User enters email (POST /api/auth/forgot-password)
2. Backend generates single-use PASSWORD_RESET token
3. Queues password reset email via Redis
4. User clicks link: frontend/?reset=<token>
5. User enters new password + confirmation
6. Frontend POSTs to /api/auth/reset-password
7. Backend consumes token, updates password, verifies account, revokes all sessions
```

### Session Management & Token Rotation

**Token types:**
- **Access token**: JWT (HMAC-SHA256), 15-min TTL, stored in httpOnly cookie. Contains email, userId, role, sessionId.
- **Refresh token**: Opaque (`<tokenId>.<secret>`), 7-day TTL, SHA-256 hash stored in DB.

**Token rotation on refresh:**
```
1. Client POSTs to /api/auth/refresh (refresh token sent via cookie)
2. Backend parses token, finds session by sessionId
3. Validates: not revoked, not expired, hash matches
4. Generates new opaque token (same sessionId)
5. Updates hash in DB, extends expiration
6. Returns new access token + new refresh token
```

**Replay attack detection:**
```
If a refresh token hash does not match the stored hash during rotation:
1. The session is immediately revoked
2. ALL other active sessions for that user are also revoked
3. User must re-authenticate on all devices
```

---

## Security

### Password Hashing
- BCrypt with default strength (10 rounds)

### Token Storage
- Access and refresh tokens stored in HTTP-only, SameSite=Lax cookies
- No tokens in localStorage (legacy `auth_token` key is cleared on mount)
- Cookies scoped to `/api` path

### Rate Limiting
- In-memory fixed-window counter per client IP
- 20 requests per 60-second window on `/api/auth/*` endpoints
- Returns HTTP 429 when exceeded

### Account Lockout
- 5 failed login attempts triggers 15-minute lockout
- Admin can lock accounts temporarily or permanently
- Locked accounts have all sessions revoked

### CORS
- Allowed origins: `http://localhost:*`, `http://127.0.0.1:*`, configured frontend URL
- Credentials enabled (required for cookie-based auth)
- `Set-Cookie` header exposed to frontend

### Opaque Token Security
- Raw tokens are never stored — only SHA-256 hashes
- Token format: `<UUID>.<Base64URL-encoded-random-bytes>`
- Cannot be reconstructed from the database hash

---

## Database Schema

### `users`
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | BIGINT | PK, auto-increment |
| `email` | VARCHAR | NOT NULL, UNIQUE |
| `password` | VARCHAR | NULLABLE (null for Google-only users) |
| `google_id` | VARCHAR | NULLABLE, UNIQUE |
| `role` | ENUM | USER, ADMIN |
| `is_verified` | BOOLEAN | DEFAULT false |
| `created_at` | DATETIME | NOT NULL |
| `failed_login_attempts` | INT | DEFAULT 0 |
| `locked_until` | DATETIME | NULLABLE |
| `is_two_factor_enabled` | BOOLEAN | DEFAULT false |
| `two_factor_secret` | VARCHAR | NULLABLE |

### `refresh_tokens`
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | BIGINT | PK, auto-increment |
| `session_id` | VARCHAR(64) | NOT NULL, UNIQUE |
| `token_hash` | VARCHAR(128) | NOT NULL |
| `user_id` | BIGINT | FK -> users, LAZY |
| `created_at` | DATETIME | NOT NULL |
| `last_used_at` | DATETIME | NOT NULL |
| `expires_at` | DATETIME | NOT NULL |
| `revoked_at` | DATETIME | NULLABLE |
| `user_agent` | VARCHAR(512) | — |
| `ip_address` | VARCHAR(128) | — |

### `action_tokens`
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | BIGINT | PK, auto-increment |
| `token_id` | VARCHAR(64) | NOT NULL, UNIQUE |
| `token_hash` | VARCHAR(128) | NOT NULL |
| `user_id` | BIGINT | FK -> users, LAZY |
| `purpose` | ENUM | EMAIL_VERIFICATION, PASSWORD_RESET, MAGIC_LINK |
| `created_at` | DATETIME | NOT NULL |
| `expires_at` | DATETIME | NOT NULL |
| `used_at` | DATETIME | NULLABLE |

### `audit_logs`
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | BIGINT | PK, auto-increment |
| `user_id` | BIGINT | Indexed |
| `email` | VARCHAR(320) | — |
| `action` | ENUM | 16 event types |
| `ip_address` | VARCHAR(128) | — |
| `user_agent` | VARCHAR(512) | — |
| `details` | VARCHAR(1024) | — |
| `created_at` | DATETIME | NOT NULL, Indexed |

---

## Frontend

The frontend is a single-page React application with no routing library. View switching is managed by internal state (`mode` variable).

### Views

| Mode | Description |
|------|-------------|
| `login` | Login form (email/password, magic link, Google OAuth) |
| `signup` | Registration form |
| `forgot` | Forgot password email input |
| `reset` | New password form (from `?reset=` link) |
| `verify` | Email verification (from `?verify=` link) |
| `verify-2fa` | 2FA code entry during login |
| `magic-verify` | Magic link verification (from `?magic=` link) |
| `magic-sent` | Magic link sent confirmation |
| Dashboard | Authenticated view with tabs |

### Dashboard Tabs

| Tab | Description | Access |
|-----|-------------|--------|
| Profile | User info, 2FA toggle/setup | All users |
| Sessions | Active sessions, revoke individual or all | All users |
| Users | User search, lock/unlock/delete | Admin only |
| All Sessions | View and revoke any session | Admin only |
| Audit Logs | Paginated security event log | Admin only |

### Key Behaviors

- **Session restore**: On page load, attempts `/api/user/profile`, falls back to `/api/auth/refresh`
- **Auto-refresh**: Access token refreshed every 12 minutes
- **Session invalidation**: Polls `/api/user/profile` every 5 seconds to detect remote logout
- **Google OAuth**: Implicit flow — redirects to Google, receives ID token in URL hash

---

## Benchmark Results

### Email Latency (10 iterations per endpoint)

| Endpoint | Avg (ms) | Min (ms) | Max (ms) |
|----------|----------|----------|----------|
| Signup | 20 | 12 | 41 |
| Login | 8 | 5 | 13 |
| Forgot Password | 16 | 7 | 24 |
| Magic Link | 22 | 20 | 25 |
| Resend Verification | 24 | 21 | 29 |

### Full Backend Latency (end-to-end, 10 iterations per endpoint)

**Email Endpoints:**
| Endpoint | Avg (ms) | Min (ms) | Max (ms) |
|----------|----------|----------|----------|
| Signup | 27 | 14 | 40 |
| Login | 157 | 87 | 275 |
| Forgot Password | 24 | 23 | 25 |
| Magic Link | 21 | 20 | 22 |
| Resend Verification | 21 | 20 | 23 |
| Google Login | 22 | 22 | 25 |

**DB Endpoints:**
| Endpoint | Avg (ms) | Min (ms) | Max (ms) |
|----------|----------|----------|----------|
| Verify Email | 22 | 20 | 25 |
| Reset Password | 21 | 20 | 23 |
| Magic Link Verify | 23 | 21 | 24 |
| Refresh Token | 26 | 22 | 29 |
| Logout | 21 | 21 | 23 |
| Verify 2FA | 20 | 19 | 21 |

**DB-Only Endpoints:**
| Endpoint | Avg (ms) | Min (ms) | Max (ms) |
|----------|----------|----------|----------|
| Get Me | 7 | 7 | 8 |
| Get Profile | 17 | 11 | 22 |
| Get Sessions | 21 | 20 | 24 |
| 2FA Generate | 23 | 22 | 25 |
| 2FA Enable | 21 | 20 | 24 |
| 2FA Disable | 21 | 20 | 23 |
| Admin Get Users | 20 | 20 | 21 |
| Admin Get Sessions | 21 | 20 | 23 |
| Admin Audit Logs | 20 | 20 | 22 |
| Admin Unlock User | 21 | 20 | 23 |

---

## License

This project is for educational and demonstration purposes.
