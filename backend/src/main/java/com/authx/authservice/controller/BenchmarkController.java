package com.authx.authservice.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/benchmark")
public class BenchmarkController {

    @GetMapping(value = "/html", produces = MediaType.TEXT_HTML_VALUE)
    public String getBenchmarkHtml() {
        return buildHtmlReport();
    }

    private String buildHtmlReport() {
        String timestamp = LocalDateTime.now().toString();
        
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"en\">\n");
        sb.append("<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("<title>Email Latency Benchmark</title>\n");
        sb.append("<style>\n");
        sb.append("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
        sb.append("body { font-family: 'Segoe UI', system-ui, sans-serif; background: linear-gradient(135deg, #0f0f23 0%, #1a1a2e 100%); min-height: 100vh; padding: 40px 20px; }\n");
        sb.append(".container { max-width: 900px; margin: 0 auto; }\n");
        sb.append("h1 { color: #fff; text-align: center; margin-bottom: 8px; font-size: 2.5rem; font-weight: 300; }\n");
        sb.append(".subtitle { color: #6c757d; text-align: center; margin-bottom: 40px; }\n");
        sb.append(".run-btn { display: block; margin: 30px auto; padding: 14px 32px; background: linear-gradient(135deg, #3b82f6, #1d4ed8); color: white; border: none; border-radius: 12px; font-size: 1rem; font-weight: 600; cursor: pointer; }\n");
        sb.append(".run-btn:hover { transform: translateY(-2px); box-shadow: 0 8px 25px rgba(59, 130, 246, 0.4); }\n");
        sb.append(".summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 20px; margin-bottom: 40px; }\n");
        sb.append(".summary-card { background: rgba(255,255,255,0.08); backdrop-filter: blur(10px); border: 1px solid rgba(255,255,255,0.1); border-radius: 16px; padding: 24px; text-align: center; }\n");
        sb.append(".summary-card .label { color: #9ca3af; font-size: 0.75rem; text-transform: uppercase; margin-bottom: 8px; }\n");
        sb.append(".summary-card .value { color: #fff; font-size: 2rem; font-weight: 600; }\n");
        sb.append(".summary-card .value.fast { color: #10b981; }\n");
        sb.append(".summary-card .value.slow { color: #ef4444; }\n");
        sb.append(".link-btn { display: inline-block; margin: 10px 5px; padding: 10px 20px; background: rgba(255,255,255,0.1); color: #3b82f6; text-decoration: none; border-radius: 8px; }\n");
        sb.append(".link-btn:hover { background: rgba(255,255,255,0.2); }\n");
        sb.append("</style>\n");
        sb.append("</head>\n<body>\n");
        sb.append("<div class=\"container\">\n");
        sb.append("<h1>Email Latency Benchmark</h1>\n");
        sb.append("<p class=\"subtitle\">Interactive benchmark for email delivery endpoints</p>\n");
        sb.append("<a href=\"/\" class=\"link-btn\">Home</a>\n");
        sb.append("<a href=\"/api/auth/signup\" class=\"link-btn\">Signup API</a>\n");
        sb.append("<button class=\"run-btn\" onclick=\"runBenchmark()\">Run Benchmark</button>\n");
        sb.append("<div id=\"results\"></div>\n");
        sb.append("<p class=\"subtitle\">Generated: ").append(timestamp).append("</p>\n");
        sb.append("</div>\n");
        sb.append("<script>\n");
        sb.append("const baseUrl = window.location.origin;\n");
        sb.append("const ITERATIONS = 5;\n");
        sb.append("const WARMUP = 2;\n");
        sb.append("async function benchmarkEndpoint(name, url, data) {\n");
        sb.append("  for (let i = 0; i < WARMUP; i++) { await fetch(url, {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)}).catch(()=>{}); }\n");
        sb.append("  const times = [];\n");
        sb.append("  for (let i = 0; i < ITERATIONS; i++) {\n");
        sb.append("    const start = performance.now();\n");
        sb.append("    await fetch(url, {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)}).catch(()=>{});\n");
        sb.append("    times.push(Math.round(performance.now() - start));\n");
        sb.append("  }\n");
        sb.append("  return { endpoint: name, avg: Math.round(times.reduce((a,b)=>a+b,0)/times.length), min: Math.min(...times), max: Math.max(...times) };\n");
        sb.append("}\n");
        sb.append("async function runBenchmark() {\n");
        sb.append("  const btn = document.querySelector('.run-btn');\n");
        sb.append("  btn.textContent = 'Running...';\n");
        sb.append("  btn.disabled = true;\n");
        sb.append("  const resultsDiv = document.getElementById('results');\n");
        sb.append("  resultsDiv.innerHTML = '<p style=\"color:#fff;text-align:center;\">Testing endpoints...</p>';\n");
        sb.append("  const testEmail = 'benchmark' + Date.now() + '@test.com';\n");
        sb.append("  const testPassword = 'Test@123456';\n");
        sb.append("  const eps = [\n");
        sb.append("    {name:'Signup',url:baseUrl+'/api/auth/signup',data:{email:testEmail,password:testPassword,confirmPassword:testPassword}},\n");
        sb.append("    {name:'Login',url:baseUrl+'/api/auth/login',data:{email:testEmail,password:testPassword}},\n");
        sb.append("    {name:'ForgotPassword',url:baseUrl+'/api/auth/forgot-password',data:{email:testEmail}},\n");
        sb.append("    {name:'MagicLink',url:baseUrl+'/api/auth/magic-link',data:{email:testEmail}},\n");
        sb.append("    {name:'ResendVerif',url:baseUrl+'/api/auth/resend-verification',data:{email:testEmail}}\n");
        sb.append("  ];\n");
        sb.append("  let results = [];\n");
        sb.append("  for (const ep of eps) {\n");
        sb.append("    try { results.push(await benchmarkEndpoint(ep.name, ep.url, ep.data)); } catch(e) { console.error(e); }\n");
        sb.append("  }\n");
        sb.append("  let html = '<div class=\"summary-grid\">';\n");
        sb.append("  const avgAll = Math.round(results.reduce((a,b)=>a+b.avg,0)/results.length);\n");
        sb.append("  const minAll = Math.min(...results.map(r=>r.min));\n");
        sb.append("  const maxAll = Math.max(...results.map(r=>r.max));\n");
        sb.append("  html += '<div class=\"summary-card\"><div class=\"label\">Avg Latency</div><div class=\"value\">'+avgAll+'ms</div></div>';\n");
        sb.append("  html += '<div class=\"summary-card\"><div class=\"label\">Min</div><div class=\"value fast\">'+minAll+'ms</div></div>';\n");
        sb.append("  html += '<div class=\"summary-card\"><div class=\"label\">Max</div><div class=\"value slow\">'+maxAll+'ms</div></div>';\n");
        sb.append("  html += '</div><table style=\"width:100%;border-collapse:collapse;\">'+'<tr style=\"background:rgba(0,0,0,0.4);\"><th style=\"padding:12px;text-align:left;color:#9ca3af;\">Endpoint</th><th style=\"padding:12px;text-align:left;color:#9ca3af;\">Avg</th><th style=\"padding:12px;text-align:left;color:#9ca3af;\">Min/Max</th></tr>';\n");
        sb.append("  results.forEach(r => {\n");
        sb.append("    const perf = r.avg < 500 ? 'fast' : (r.avg < 1500 ? 'medium' : 'slow');\n");
        sb.append("    const color = perf === 'fast' ? '#10b981' : (perf === 'medium' ? '#f59e0b' : '#ef4444');\n");
        sb.append("    html += '<tr style=\"border-bottom:1px solid rgba(255,255,255,0.1);\"><td style=\"padding:12px;color:#fff;\">'+r.endpoint+'</td><td style=\"padding:12px;color:#fff;\">'+r.avg+'ms</td><td style=\"padding:12px;color:#9ca3af;\">'+r.min+'ms / '+r.max+'ms</td></tr>';\n");
        sb.append("  });\n");
        sb.append("  html += '</table>';\n");
        sb.append("  resultsDiv.innerHTML = html;\n");
        sb.append("  btn.textContent = 'Run Benchmark';\n");
        sb.append("  btn.disabled = false;\n");
        sb.append("}\n");
        sb.append("</script>\n");
        sb.append("</body>\n</html>");
        
        return sb.toString();
    }
}