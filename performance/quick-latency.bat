@echo off
REM Quick latency test using curl. Run from this directory.
REM Usage: quick-latency.bat [BASE_URL]
REM   BASE_URL defaults to http://localhost:5173

setlocal

set "BASE=%~1"
if "%BASE%"=="" set "BASE=http://localhost:5173"

set "EMAIL=%TEST_EMAIL%"
if "%EMAIL%"=="" set "EMAIL=perf-test@test.com"

set "PASSWORD=%TEST_PASSWORD%"
if "%PASSWORD%"=="" set "PASSWORD=Test@12345"

set "CURL_FORMAT=\n  DNS:        %%{time_namelookup}s\n  Connect:    %%{time_connect}s\n  TLS:        %%{time_appconnect}s\n  TTFB:       %%{time_starttransfer}s\n  Total:      %%{time_total}s\n  HTTP Code:  %%{http_code}\n"

echo.
echo ================================================

REM ─── 1. Signup ───
echo.
echo ^>^> POST /api/auth/signup
curl -w "%CURL_FORMAT%" -s -o NUL -X POST "%BASE%/api/auth/signup" -H "Content-Type: application/json" -d "{\"email\":\"%EMAIL%\",\"password\":\"%PASSWORD%\",\"confirmPassword\":\"%PASSWORD%\"}"

REM ─── 2. Login ───
echo.
echo ================================================
echo.
echo ^>^> POST /api/auth/login
curl -s -D "%TEMP%\login-headers.txt" -o NUL -X POST "%BASE%/api/auth/login" -H "Content-Type: application/json" -d "{\"email\":\"%EMAIL%\",\"password\":\"%PASSWORD%\"}"

REM Save cookies from Set-Cookie headers
set "COOKIE_STRING="
for /f "tokens=2 delims=:;=" %%a in ('findstr /i "Set-Cookie" "%TEMP%\login-headers.txt" 2^>NUL') do (
    set "COOKIE_STRING=%COOKIE_STRING%%%a;"
)

REM Re-run with timing
curl -w "%CURL_FORMAT%" -s -o NUL -X POST "%BASE%/api/auth/login" -H "Content-Type: application/json" -d "{\"email\":\"%EMAIL%\",\"password\":\"%PASSWORD%\"}"

REM ─── 3. Get Profile ───
echo.
echo ================================================
echo.
echo ^>^> GET /api/user/profile
curl -w "%CURL_FORMAT%" -s -o NUL -H "Cookie: %COOKIE_STRING%" "%BASE%/api/user/profile"

REM ─── 4. Refresh Token ───
echo.
echo ================================================
echo.
echo ^>^> POST /api/auth/refresh
curl -w "%CURL_FORMAT%" -s -o NUL -X POST -H "Cookie: %COOKIE_STRING%" "%BASE%/api/auth/refresh"

REM ─── 5. Logout ───
echo.
echo ================================================
echo.
echo ^>^> POST /api/auth/logout
curl -w "%CURL_FORMAT%" -s -o NUL -X POST -H "Cookie: %COOKIE_STRING%" "%BASE%/api/auth/logout"

echo.
echo ================================================
echo Done.

endlocal
