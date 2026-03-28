#!/bin/bash

set -e

echo "╔════════════════════════════════════════════════════════════╗"
echo "║         Full Backend Latency Benchmark Tool                ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

BASE_URL="${BASE_URL:-http://localhost:8081}"
ITERATIONS="${ITERATIONS:-10}"
WARMUP="${WARMUP:-3}"
TEST_EMAIL="${TEST_EMAIL:-benchmark@test.com}"
TEST_PASSWORD="${TEST_PASSWORD:-Test@123456}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@test.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Admin@123456}"

OUTPUT_DIR="/tmp/benchmark-results"
mkdir -p "$OUTPUT_DIR"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
CSV_FILE="$OUTPUT_DIR/latency-$TIMESTAMP.csv"
JSON_FILE="$OUTPUT_DIR/latency-$TIMESTAMP.json"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Configuration:"
echo "  Base URL:    $BASE_URL"
echo "  Iterations:  $ITERATIONS"
echo "  Warmup:      $WARMUP"
echo "  Test Email:  $TEST_EMAIL"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

benchmark_endpoint() {
    local name="$1"
    local method="$2"
    local url="$3"
    local data="$4"
    local auth_token="$5"
    local category="$6"
    
    local times=()
    local total_time=0
    
    for i in $(seq 1 $((WARMUP + ITERATIONS))); do
        local start_time=$(date +%s%N)
        
        if [ -n "$auth_token" ]; then
            curl -s -w "" -X "$method" "$url" \
                -H "Content-Type: application/json" \
                -H "Authorization: Bearer $auth_token" \
                -d "$data" > /dev/null 2>&1
        else
            curl -s -w "" -X "$method" "$url" \
                -H "Content-Type: application/json" \
                -d "$data" > /dev/null 2>&1
        fi
        
        local end_time=$(date +%s%N)
        local latency=$(( (end_time - start_time) / 1000000 ))
        
        if [ $i -gt $WARMUP ]; then
            times+=($latency)
            total_time=$((total_time + latency))
        fi
    done
    
    local count=${#times[@]}
    if [ $count -eq 0 ]; then
        count=1
    fi
    
    local avg=$((total_time / count))
    local min=${times[0]}
    local max=${times[0]}
    
    for t in "${times[@]}"; do
        [ $t -lt $min ] && min=$t
        [ $t -gt $max ] && max=$t
    done
    
    echo "$name,$method,$avg,$min,$max,$category"
    
    echo "$name,$method,$avg,$min,$max,$category" >> "$CSV_FILE"
    echo "{\"name\":\"$name\",\"method\":\"$method\",\"avg\":$avg,\"min\":$min,\"max\":$max,\"category\":\"$category\"}" >> "$JSON_FILE"
}

setup_tokens() {
    echo "🔐 Setting up authentication tokens..."
    echo ""
    
    SIGNUP_RESP=$(curl -s -X POST "$BASE_URL/api/auth/signup" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$TEST_EMAIL\",\"password\":\"$TEST_PASSWORD\",\"confirmPassword\":\"$TEST_PASSWORD\"}" 2>/dev/null)
    
    LOGIN_RESP=$(curl -s -X POST "$BASE_URL/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$TEST_EMAIL\",\"password\":\"$TEST_PASSWORD\"}" 2>/dev/null)
    
    USER_TOKEN=$(echo "$LOGIN_RESP" | jq -r '.response.token // .token // empty' 2>/dev/null)
    REFRESH_TOKEN=$(echo "$LOGIN_RESP" | jq -r '.refreshToken // empty' 2>/dev/null)
    
    if [ -z "$USER_TOKEN" ] || [ "$USER_TOKEN" = "null" ]; then
        echo "⚠️  Could not get user token, some tests may fail"
        USER_TOKEN=""
    fi
    
    ADMIN_LOGIN_RESP=$(curl -s -X POST "$BASE_URL/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" 2>/dev/null)
    
    ADMIN_TOKEN=$(echo "$ADMIN_LOGIN_RESP" | jq -r '.response.token // .token // empty' 2>/dev/null)
    
    if [ -z "$ADMIN_TOKEN" ] || [ "$ADMIN_TOKEN" = "null" ]; then
        echo "⚠️  Could not get admin token"
        ADMIN_TOKEN=""
    fi
    
    echo "✓ Tokens obtained"
    echo "  User Token: ${USER_TOKEN:0:20}..."
    echo "  Admin Token: ${ADMIN_TOKEN:0:20}..."
    echo ""
}

run_benchmarks() {
    echo "🚀 Running benchmarks..."
    echo ""
    
    echo "name,method,avg_ms,min_ms,max_ms,category" > "$CSV_FILE"
    echo "[" > "$JSON_FILE"
    first=true
    
    echo ""
    
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "📧 PUBLIC ENDPOINTS (Email-Heavy)"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    
    benchmark_endpoint "signup" "POST" "$BASE_URL/api/auth/signup" \
        "{\"email\":\"$TEST_EMAIL\",\"password\":\"$TEST_PASSWORD\",\"confirmPassword\":\"$TEST_PASSWORD\"}" \
        "" "email"
    
    benchmark_endpoint "login" "POST" "$BASE_URL/api/auth/login" \
        "{\"email\":\"$TEST_EMAIL\",\"password\":\"$TEST_PASSWORD\"}" \
        "" "email"
    
    benchmark_endpoint "forgot_password" "POST" "$BASE_URL/api/auth/forgot-password" \
        "{\"email\":\"$TEST_EMAIL\"}" \
        "" "email"
    
    benchmark_endpoint "magic_link" "POST" "$BASE_URL/api/auth/magic-link" \
        "{\"email\":\"$TEST_EMAIL\"}" \
        "" "email"
    
    benchmark_endpoint "resend_verification" "POST" "$BASE_URL/api/auth/resend-verification" \
        "{\"email\":\"$TEST_EMAIL\"}" \
        "" "email"
    
    benchmark_endpoint "google_login" "POST" "$BASE_URL/api/auth/google" \
        "{\"idToken\":\"invalid\"}" \
        "" "email"
    
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "🔗 VERIFICATION/DATABASE ENDPOINTS"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    
    benchmark_endpoint "verify_email" "POST" "$BASE_URL/api/auth/verify-email" \
        "{\"token\":\"invalid\"}" \
        "" "db"
    
    benchmark_endpoint "reset_password" "POST" "$BASE_URL/api/auth/reset-password" \
        "{\"token\":\"invalid\",\"password\":\"Test@123456\",\"confirmPassword\":\"Test@123456\"}" \
        "" "db"
    
    benchmark_endpoint "magic_link_verify" "POST" "$BASE_URL/api/auth/magic-link/verify" \
        "{\"token\":\"invalid\"}" \
        "" "db"
    
    benchmark_endpoint "refresh_token" "POST" "$BASE_URL/api/auth/refresh" \
        "{\"refreshToken\":\"$REFRESH_TOKEN\"}" \
        "" "db"
    
    benchmark_endpoint "test_generate_token" "POST" "$BASE_URL/api/test/generate-token" \
        "{\"email\":\"$TEST_EMAIL\"}" \
        "" "db_only"
    
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "👤 PROTECTED USER ENDPOINTS"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    
    benchmark_endpoint "get_me" "GET" "$BASE_URL/api/user/me" \
        "" "$USER_TOKEN" "db_only"
    
    benchmark_endpoint "get_profile" "GET" "$BASE_URL/api/user/profile" \
        "" "$USER_TOKEN" "db_only"
    
    benchmark_endpoint "get_sessions" "GET" "$BASE_URL/api/user/sessions" \
        "" "$USER_TOKEN" "db_only"
    
    benchmark_endpoint "logout" "POST" "$BASE_URL/api/auth/logout" \
        "{\"refreshToken\":\"$REFRESH_TOKEN\"}" \
        "$USER_TOKEN" "db"
    
    benchmark_endpoint "2fa_generate" "POST" "$BASE_URL/api/user/2fa/generate" \
        "{\"secret\":\"JBSWY3DPEHPK3PXP\"}" \
        "$USER_TOKEN" "db_only"
    
    benchmark_endpoint "2fa_enable" "POST" "$BASE_URL/api/user/2fa/enable" \
        "{\"code\":\"123456\"}" \
        "$USER_TOKEN" "db_only"
    
    benchmark_endpoint "2fa_disable" "POST" "$BASE_URL/api/user/2fa/disable" \
        "{\"code\":\"123456\"}" \
        "$USER_TOKEN" "db_only"
    
    benchmark_endpoint "verify_2fa" "POST" "$BASE_URL/api/auth/verify-2fa" \
        "{\"preAuthToken\":\"invalid\",\"code\":\"123456\"}" \
        "$USER_TOKEN" "db"
    
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "👑 ADMIN ENDPOINTS"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    
    benchmark_endpoint "admin_get_users" "GET" "$BASE_URL/api/admin/users" \
        "" "$ADMIN_TOKEN" "db_only"
    
    benchmark_endpoint "admin_get_sessions" "GET" "$BASE_URL/api/admin/sessions" \
        "" "$ADMIN_TOKEN" "db_only"
    
    benchmark_endpoint "admin_get_audit_logs" "GET" "$BASE_URL/api/admin/audit-logs" \
        "" "$ADMIN_TOKEN" "db_only"
    
    benchmark_endpoint "admin_unlock_user" "POST" "$BASE_URL/api/admin/users/1/unlock" \
        "" "$ADMIN_TOKEN" "db"
    
    benchmark_endpoint "admin_lock_user" "POST" "$BASE_URL/api/admin/users/1/lock" \
        "{\"durationMinutes\":15}" \
        "$ADMIN_TOKEN" "db"
    
    benchmark_endpoint "admin_delete_user" "DELETE" "$BASE_URL/api/admin/users/999" \
        "" "$ADMIN_TOKEN" "db"
    
    echo "  ]" >> "$JSON_FILE"
}

generate_html_report() {
    echo "Generating HTML report..."
    
    # Extract JSON data from CSV
    JSON_DATA="["
    first=true
    while IFS=, read -r name method avg min max category; do
        [ -z "$name" ] && continue
        [ "$name" = "name" ] && continue
        if [ "$first" = true ]; then
            first=false
        else
            JSON_DATA="${JSON_DATA},"
        fi
        JSON_DATA="${JSON_DATA}{\"name\":\"$name\",\"method\":\"$method\",\"avg\":$avg,\"min\":$min,\"max\":$max,\"category\":\"$category\"}"
    done < "$CSV_FILE"
    JSON_DATA="${JSON_DATA}]"
    
    cat > "$OUTPUT_DIR/report-$TIMESTAMP.html" << EOF
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Backend Latency Benchmark - $TIMESTAMP</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: 'Segoe UI', system-ui, sans-serif; background: linear-gradient(135deg, #0f0f23, #1a1a2e); min-height: 100vh; padding: 30px; color: #e5e7eb; }
        .container { max-width: 1200px; margin: 0 auto; }
        h1 { color: #fff; font-size: 2rem; font-weight: 300; }
        .summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 15px; margin-bottom: 30px; }
        .summary-card { background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.1); border-radius: 12px; padding: 20px; text-align: center; }
        .summary-card .label { color: #9ca3af; font-size: 0.7rem; text-transform: uppercase; }
        .summary-card .value { color: #fff; font-size: 1.5rem; font-weight: 600; }
        .summary-card .value.fast { color: #10b981; }
        .summary-card .value.slow { color: #ef4444; }
        table { width: 100%; border-collapse: collapse; }
        th { background: rgba(0,0,0,0.4); color: #9ca3af; font-weight: 600; font-size: 0.7rem; padding: 12px; text-align: left; }
        td { padding: 12px; border-bottom: 1px solid rgba(255,255,255,0.05); }
    </style>
</head>
<body>
    <div class="container">
        <h1>Backend Latency Benchmark</h1>
        <p>$TIMESTAMP</p>
        <div class="summary-grid" id="summary"></div>
        <div id="tables"></div>
    </div>
    <script>
        const DATA = $JSON_DATA;
        function f(ms) { return ms < 1000 ? ms + 'ms' : (ms/1000).toFixed(2) + 's'; }
        let total = 0, min = Infinity, max = 0;
        DATA.forEach(r => { total += r.avg; min = Math.min(min, r.avg); max = Math.max(max, r.avg); });
        document.getElementById('summary').innerHTML = '<div class="summary-card"><div class="label">Tests</div><div class="value">' + DATA.length + '</div></div><div class="summary-card"><div class="label">Avg</div><div class="value">' + f(Math.round(total/DATA.length)) + '</div></div><div class="summary-card"><div class="label">Min</div><div class="value fast">' + f(min) + '</div></div><div class="summary-card"><div class="label">Max</div><div class="value slow">' + f(max) + '</div></div>';
        let html = '';
        ['email', 'db', 'db_only'].forEach(cat => {
            html += '<h2 style="color:#fff;margin:20px 0 10px">' + cat.toUpperCase() + '</h2><table><tr><th>Endpoint</th><th>Method</th><th>Avg</th><th>Min/Max</th></tr>';
            DATA.filter(d => d.category === cat).forEach(r => {
                html += '<tr><td>' + r.name + '</td><td>' + r.method + '</td><td>' + f(r.avg) + '</td><td>' + f(r.min) + '/' + f(r.max) + '</td></tr>';
            });
            html += '</table>';
        });
        document.getElementById('tables').innerHTML = html;
    </script>
</body>
</html>
EOF
    
    cp "$OUTPUT_DIR/report-$TIMESTAMP.html" "$OUTPUT_DIR/optimized.html"
    echo "✓ HTML report: $OUTPUT_DIR/report-$TIMESTAMP.html"
    echo "✓ Optimized report: $OUTPUT_DIR/optimized.html"
}

display_summary() {
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "📊 RESULTS SUMMARY"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    
    printf "%-30s %-8s %-12s %-12s\n" "Endpoint" "Method" "Avg Latency" "Category"
    echo "────────────────────────────────────────────────────────────────────────────"
    
    while IFS=, read -r name method avg min max category; do
        if [ "$name" != "name" ] && [ -n "$name" ]; then
            printf "%-30s %-8s %-12s %-12s\n" "$name" "$method" "${avg}ms" "$category"
        fi
    done < "$CSV_FILE"
    
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "📁 Output Files:"
    echo "  CSV:    $CSV_FILE"
    echo "  JSON:   $JSON_FILE"
    echo "  HTML:   $OUTPUT_DIR/report-$TIMESTAMP.html"
    echo "  Optimized: $OUTPUT_DIR/optimized.html"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
}

check_dependencies() {
    if ! command -v jq &> /dev/null; then
        echo "❌ jq is required but not installed. Install with: sudo apt install jq"
        exit 1
    fi
}

check_dependencies
setup_tokens
run_benchmarks
display_summary
generate_html_report

echo ""
echo "✅ Benchmark complete!"
echo "Open HTML report: xdg-open $OUTPUT_DIR/report-$TIMESTAMP.html 2>/dev/null || echo 'Open manually'"