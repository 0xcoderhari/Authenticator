@echo off
REM Apache Bench burst test. Run from this directory.
REM Usage: burst-test.bat [BASE_URL] [REQUESTS] [CONCURRENCY]
REM   BASE_URL    defaults to http://localhost:8081  (direct backend)
REM   REQUESTS    defaults to 200
REM   CONCURRENCY defaults to 20
REM
REM Requires: ab (Apache Bench) installed via XAMPP or Apache Lounge

setlocal enabledelayedexpansion

set "BASE=%~1"
if "%BASE%"=="" set "BASE=http://localhost:8081"

set "N=%~2"
if "%N%"=="" set "N=200"

set "C=%~3"
if "%C%"=="" set "C=20"

set "EMAIL=%TEST_EMAIL%"
if "%EMAIL%"=="" set "EMAIL=perf-test@test.com"

set "PASSWORD=%TEST_PASSWORD%"
if "%PASSWORD%"=="" set "PASSWORD=Test@12345"

REM Create payload files
echo {"email":"%EMAIL%","password":"%PASSWORD%"} > "%TEMP%\login-payload.json"
echo {"email":"%EMAIL%","password":"%PASSWORD%","confirmPassword":"%PASSWORD%"} > "%TEMP%\signup-payload.json"

echo.
echo ================================================
echo LOGIN -- %N% requests, %C% concurrent
echo POST %BASE%/api/auth/login
ab -n %N% -c %C% -T "application/json" -p "%TEMP%\login-payload.json" "%BASE%/api/auth/login"

REM ─── Signup Burst (unique emails) ───
echo.
echo ================================================
echo SIGNUP -- 50 requests, 10 concurrent (each with unique email)

for /L %%i in (1,1,50) do (
    echo {"email":"burst-%%i@test.com","password":"Test@12345","confirmPassword":"Test@12345"} > "%TEMP%\signup-%%i.json"
)

ab -n 50 -c 10 -T "application/json" -p "%TEMP%\signup-payload.json" "%BASE%/api/auth/signup"

REM Cleanup
del /q "%TEMP%\login-payload.json" 2>NUL
del /q "%TEMP%\signup-payload.json" 2>NUL
del /q "%TEMP%\signup-*.json" 2>NUL

echo.
echo ================================================
echo Done.

endlocal
