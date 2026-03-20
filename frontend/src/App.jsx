import { useCallback, useEffect, useState } from "react";

const apiBaseUrl = getApiBaseUrl();

const initialSignup = { email: "", password: "", confirmPassword: "" };
const initialLogin = { email: "", password: "" };
const initialReset = { password: "", confirmPassword: "" };

function App() {
  const [mode, setMode] = useState(() => getInitialMode());
  const [dashTab, setDashTab] = useState("profile");
  const [signupForm, setSignupForm] = useState(initialSignup);
  const [loginForm, setLoginForm] = useState(() => ({
    ...initialLogin,
    email: localStorage.getItem("auth_email") || "",
  }));
  const [forgotPasswordEmail, setForgotPasswordEmail] = useState(
    () => localStorage.getItem("auth_email") || "",
  );
  const [resetForm, setResetForm] = useState(initialReset);
  const [verificationToken, setVerificationToken] = useState(() => getQueryToken("verify"));
  const [resetToken, setResetToken] = useState(() => getQueryToken("reset"));
  const [magicToken, setMagicToken] = useState(() => getQueryToken("magic"));
  const [currentUser, setCurrentUser] = useState(null);
  const [accountEmail, setAccountEmail] = useState(
    () => localStorage.getItem("auth_email") || "",
  );
  const [authMessage, setAuthMessage] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [restoringSession, setRestoringSession] = useState(true);

  // 2FA Auth State
  const [preAuthToken, setPreAuthToken] = useState("");
  const [twoFactorCode, setTwoFactorCode] = useState("");
  
  // 2FA Setup State
  const [twoFactorSetup, setTwoFactorSetup] = useState(null); // { secret, qrCodeImageUri }
  const [setupTwoFactorCode, setSetupTwoFactorCode] = useState("");

  // Dashboard data
  const [sessions, setSessions] = useState([]);
  const [profile, setProfile] = useState(null);
  const [adminUsers, setAdminUsers] = useState([]);
  const [adminSessions, setAdminSessions] = useState([]);
  const [auditLogs, setAuditLogs] = useState({ content: [], totalPages: 0, number: 0 });
  const [auditPage, setAuditPage] = useState(0);
  const [dashLoading, setDashLoading] = useState(false);
  const [adminUserQuery, setAdminUserQuery] = useState("");

  const isAuthenticated = Boolean(currentUser);
  const isAdmin = currentUser?.role === "ADMIN";

  useEffect(() => {
    clearLegacyLocalAuthToken();
    clearAuthQueryParams();
  }, []);

  useEffect(() => { void restoreSession(); }, []);

  // Handle OAuth 2.0 redirect flow callback (id_token in URL hash)
  useEffect(() => {
    const hash = window.location.hash;
    if (hash && hash.includes("id_token=")) {
      const params = new URLSearchParams(hash.substring(1));
      const idToken = params.get("id_token");
      if (idToken) {
        // Clear the hash from the URL
        window.history.replaceState(null, null, window.location.pathname + window.location.search);
        void handleGoogleLogin(idToken);
      }
    }
  }, []);

  function initiateGoogleLogin() {
    const clientId = getGoogleClientId();
    if (!clientId) {
      setError("Google Client ID not configured.");
      return;
    }
    const redirectUri = window.location.origin;
    const nonce = Math.random().toString(36).substring(2) + Date.now().toString(36);
    const authUrl = `https://accounts.google.com/o/oauth2/v2/auth?client_id=${clientId}&redirect_uri=${encodeURIComponent(redirectUri)}&response_type=id_token&scope=openid%20email%20profile&nonce=${nonce}`;
    window.location.href = authUrl;
  }

  useEffect(() => {
    if (mode === "verify" && verificationToken) void handleVerifyEmail();
    if (mode === "magic-verify" && magicToken) void handleVerifyMagicLink();
  }, [mode, verificationToken, magicToken]);

  useEffect(() => {
    if (accountEmail) localStorage.setItem("auth_email", accountEmail);
    else localStorage.removeItem("auth_email");
  }, [accountEmail]);

  // Auto-refresh access token
  useEffect(() => {
    if (!isAuthenticated) return;
    const interval = setInterval(async () => {
      try {
        const res = await fetch(buildApiUrl("/api/auth/refresh"), {
          method: "POST", credentials: "include",
        });
        if (!res.ok) {
          clearAuthenticatedUser(currentUser?.email || accountEmail);
          setMode("login");
        }
      } catch { /* ignore */ }
    }, 12 * 60 * 1000); // 12 minutes
    return () => clearInterval(interval);
  }, [isAuthenticated]);

  // Real-time session invalidation check
  useEffect(() => {
    if (!isAuthenticated) return;
    const interval = setInterval(async () => {
      try {
        const res = await fetch(buildApiUrl("/api/user/profile"), { credentials: "include" });
        if (res.status === 401 || res.status === 403) {
          clearAuthenticatedUser();
          setMode("login");
          setAuthMessage("Your session was signed out from another device.");
        }
      } catch { /* ignore */ }
    }, 5000); // Check every 5 seconds
    return () => clearInterval(interval);
  }, [isAuthenticated]);

  // Load dashboard data on tab change
  useEffect(() => {
    if (!isAuthenticated) return;
    if (dashTab === "sessions") void loadSessions();
    if (dashTab === "profile") void loadProfile();
    if (dashTab === "admin-users") void loadAdminUsers();
    if (dashTab === "admin-sessions") void loadAdminSessions();
    if (dashTab === "admin-logs") void loadAuditLogs(0);
  }, [dashTab, isAuthenticated]);

  // Debounced admin user search
  useEffect(() => {
    if (!isAuthenticated || !isAdmin || dashTab !== "admin-users") return;
    const handle = setTimeout(() => {
      void loadAdminUsers();
    }, 300);
    return () => clearTimeout(handle);
  }, [adminUserQuery, isAuthenticated, isAdmin, dashTab]);

  function switchMode(nextMode) {
    setMode(nextMode);
    setAuthMessage("");
    setError("");
    setLoading(false);
    if (nextMode === "forgot") setForgotPasswordEmail(accountEmail || loginForm.email || "");
    if (nextMode === "login") {
      setPreAuthToken("");
      setTwoFactorCode("");
    }
  }

  async function restoreSession() {
    try {
      const profileData = await fetchProfile();
      if (profileData) { applyAuthenticatedUser(profileData); return; }
      const refreshed = await refreshSession();
      if (refreshed) applyAuthenticatedUser(refreshed);
    } catch { clearAuthenticatedUser(); } finally { setRestoringSession(false); }
  }

  async function handleSignup(e) {
    e.preventDefault();
    setLoading(true); setError(""); setAuthMessage("");
    try {
      const email = normalizeEmail(signupForm.email);
      const res = await fetch(buildApiUrl("/api/auth/signup"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...signupForm, email }),
      });
      const data = await parseResponse(res);
      if (!res.ok) throw new Error(extractErrorMessage(data));
      setAccountEmail(email);
      setLoginForm({ ...initialLogin, email });
      setForgotPasswordEmail(email);
      setSignupForm(initialSignup);
      setMode("login");
      setAuthMessage(typeof data === "string" ? data : "Account created. Check your email to verify.");
    } catch (err) { setError(err.message); } finally { setLoading(false); }
  }

  async function handleLogin(e) {
    e.preventDefault();
    setLoading(true); setError(""); setAuthMessage("");
    try {
      const email = normalizeEmail(loginForm.email);
      const res = await fetch(buildApiUrl("/api/auth/login"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({ ...loginForm, email }),
      });
      const data = await parseResponse(res);
      if (!res.ok) throw new Error(extractErrorMessage(data));
      
      if (data.requires2fa) {
        setPreAuthToken(data.preAuthToken);
        setMode("verify-2fa");
        setAuthMessage("Please enter your 2-Factor Authentication code.");
      } else {
        applyAuthenticatedUser({ ...data, email });
        setLoginForm({ ...initialLogin, email });
        setAuthMessage(data.message || "Signed in.");
      }
    } catch (err) {
      clearAuthenticatedUser(loginForm.email || accountEmail);
      setError(err.message);
    } finally { setLoading(false); }
  }

  async function handleVerify2Fa(e) {
    e.preventDefault();
    setLoading(true); setError(""); setAuthMessage("");
    try {
      const email = normalizeEmail(loginForm.email);
      const normalizedCode = normalizeOtpCode(twoFactorCode);
      const res = await fetch(buildApiUrl("/api/auth/verify-2fa"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({ token: preAuthToken, code: normalizedCode }),
      });
      const data = await parseResponse(res);
      if (!res.ok) throw new Error(extractErrorMessage(data));
      applyAuthenticatedUser({ ...data, email });
      setLoginForm({ ...initialLogin, email });
      setPreAuthToken("");
      setTwoFactorCode("");
      setAuthMessage(data.message || "Signed in with 2FA.");
    } catch (err) {
      setError(err.message);
    } finally { setLoading(false); }
  }

  async function handleForgotPassword(e) {
    e.preventDefault();
    setLoading(true); setError(""); setAuthMessage("");
    try {
      const email = normalizeEmail(forgotPasswordEmail);
      const res = await fetch(buildApiUrl("/api/auth/forgot-password"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email }),
      });
      const data = await parseResponse(res);
      if (!res.ok) throw new Error(extractErrorMessage(data));
      setAuthMessage(typeof data === "string" ? data : "If that email exists, a reset link has been sent.");
    } catch (err) { setError(err.message); } finally { setLoading(false); }
  }

  async function handleResetPassword(e) {
    e.preventDefault();
    setLoading(true); setError(""); setAuthMessage("");
    try {
      const res = await fetch(buildApiUrl("/api/auth/reset-password"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token: resetToken, ...resetForm }),
      });
      const data = await parseResponse(res);
      if (!res.ok) throw new Error(extractErrorMessage(data));
      setResetForm(initialReset);
      setResetToken("");
      setMode("login");
      setAuthMessage(typeof data === "string" ? data : "Password reset successful.");
    } catch (err) { setError(err.message); } finally { setLoading(false); }
  }

  async function handleGoogleLogin(idToken) {
    setLoading(true); setError(""); setAuthMessage("");
    try {
      const res = await fetch(buildApiUrl("/api/auth/google"), {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ idToken }),
      });
      const data = await parseResponse(res);
      if (!res.ok) throw new Error(extractErrorMessage(data));
      await restoreSession();
      setMode("dashboard");
    } catch (err) { setError(err.message); } finally { setLoading(false); }
  }

  async function handleVerifyEmail() {
    if (!verificationToken) { setError("Invalid verification link."); return; }
    setLoading(true); setError(""); setAuthMessage("");
    try {
      const res = await fetch(
        buildApiUrl(`/api/auth/verify-email?token=${encodeURIComponent(verificationToken)}`),
        { method: "POST" },
      );
      const data = await parseResponse(res);
      if (!res.ok) throw new Error(extractErrorMessage(data));
      setVerificationToken("");
      setMode("login");
      setAuthMessage(typeof data === "string" ? data : "Email verified. You can sign in now.");
    } catch (err) { setError(err.message); } finally { setLoading(false); }
  }

  async function handleVerifyMagicLink() {
    if (!magicToken) { setError("Invalid magic link."); return; }
    setLoading(true); setError(""); setAuthMessage("");
    try {
      const res = await fetch(
        buildApiUrl("/api/auth/magic-link/verify"),
        { 
          method: "POST",
          headers: { "Content-Type": "application/json" },
          credentials: "include",
          body: JSON.stringify({ token: magicToken })
        }
      );
      const data = await parseResponse(res);
      if (!res.ok) throw new Error(extractErrorMessage(data));
      
      setMagicToken("");
      
      if (data.requires2fa) {
        setPreAuthToken(data.preAuthToken);
        setMode("verify-2fa");
        setAuthMessage("Please enter your 2-Factor Authentication code.");
      } else {
        applyAuthenticatedUser(data);
        setAuthMessage(data.message || "Signed in securely.");
      }
    } catch (err) { 
      setError(err.message); 
      setMode("login"); 
    } finally { setLoading(false); }
  }

  async function handleRequestMagicLink() {
    if (!loginForm.email) {
      setError("Please enter your email address first.");
      document.querySelector('input[name="email"]')?.focus();
      return;
    }
    setLoading(true); setError(""); setAuthMessage("");
    try {
      const email = normalizeEmail(loginForm.email);
      const res = await fetch(buildApiUrl("/api/auth/magic-link"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email }),
      });
      const data = await parseResponse(res);
      if (!res.ok) throw new Error(extractErrorMessage(data));
      setAuthMessage(typeof data === "string" ? data : "Magic link sent. Check your email.");
      setMode("magic-sent");
    } catch (err) { setError(err.message); } finally { setLoading(false); }
  }

  async function handleResendVerification() {
    setLoading(true); setError(""); setAuthMessage("");
    try {
      const email = normalizeEmail(accountEmail || loginForm.email);
      const res = await fetch(buildApiUrl("/api/auth/resend-verification"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email }),
      });
      const data = await parseResponse(res);
      if (!res.ok) throw new Error(extractErrorMessage(data));
      setAuthMessage(typeof data === "string" ? data : "Verification email sent.");
    } catch (err) { setError(err.message); } finally { setLoading(false); }
  }

  async function logout() {
    setLoading(true); setError("");
    try {
      await fetch(buildApiUrl("/api/auth/logout"), { method: "POST", credentials: "include" });
      clearAuthenticatedUser(currentUser?.email || accountEmail);
      setAuthMessage("Signed out.");
      setMode("login");
      setDashTab("profile");
    } catch (err) { setError(err.message); } finally { setLoading(false); }
  }

  // ─── 2FA Setup ───
  async function handleGenerate2Fa() {
    setLoading(true); setError(""); setAuthMessage("");
    try {
      const res = await fetch(buildApiUrl("/api/user/2fa/generate"), { method: "POST", credentials: "include" });
      const data = await parseResponse(res);
      if (!res.ok) throw new Error(extractErrorMessage(data));
      setTwoFactorSetup(data); // { secret, qrCodeImageUri }
    } catch (err) { setError(err.message); } finally { setLoading(false); }
  }

  async function handleEnable2Fa(e) {
    e.preventDefault();
    setLoading(true); setError(""); setAuthMessage("");
    try {
      const normalizedCode = normalizeOtpCode(setupTwoFactorCode);
      const res = await fetch(buildApiUrl("/api/user/2fa/enable"), {
        method: "POST", 
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code: normalizedCode })
      });
      const data = await parseResponse(res);
      if (!res.ok) throw new Error(extractErrorMessage(data));
      setTwoFactorSetup(null);
      setSetupTwoFactorCode("");
      await loadProfile(); // refresh profile to update isTwoFactorEnabled
      setAuthMessage("2-Factor Authentication enabled successfully.");
    } catch (err) {
      const msg = (err?.message || "").toLowerCase();
      if (msg.includes("already enabled")) {
        // Backend says 2FA is already enabled — force UI into "enabled" state
        setTwoFactorSetup(null);
        setSetupTwoFactorCode("");
        setProfile(prev => prev ? { ...prev, twoFactorEnabled: true } : prev);
        setAuthMessage("2FA is already enabled on your account.");
        setError("");
      } else {
        setError(err.message);
      }
    } finally { setLoading(false); }
  }

  async function handleDisable2Fa() {
    if (!window.confirm("Are you sure you want to disable 2-Factor Authentication?")) return;
    const code = window.prompt("Enter the 6-digit code from your authenticator app to disable 2FA:");
    if (!code) return;
    setLoading(true); setError(""); setAuthMessage("");
    try {
      const normalizedCode = normalizeOtpCode(code);
      const res = await fetch(buildApiUrl("/api/user/2fa/disable"), {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code: normalizedCode })
      });
      const data = await parseResponse(res);
      if (!res.ok) throw new Error(extractErrorMessage(data));
      await loadProfile(); // refresh profile
      setAuthMessage("2-Factor Authentication disabled.");
    } catch (err) { setError(err.message); } finally { setLoading(false); }
  }

  // ─── Dashboard Data Loaders ───
  async function loadProfile() {
    setDashLoading(true);
    try {
      const data = await fetchProfile();
      if (data) setProfile(data);
    } catch { /* ignore */ } finally { setDashLoading(false); }
  }

  async function loadSessions() {
    setDashLoading(true);
    try {
      const res = await fetch(buildApiUrl("/api/user/sessions"), { credentials: "include" });
      if (res.ok) setSessions(await res.json());
    } catch { /* ignore */ } finally { setDashLoading(false); }
  }

  async function revokeSession(sessionId) {
    try {
      const res = await fetch(buildApiUrl(`/api/user/sessions/${sessionId}`), {
        method: "DELETE", credentials: "include",
      });
      if (res.ok) {
        setSessions(prev => prev.filter(s => s.sessionId !== sessionId));
        setAuthMessage("Session revoked.");
      }
    } catch { /* ignore */ }
  }

  async function logoutAll() {
    try {
      const res = await fetch(buildApiUrl("/api/user/sessions/logout-all"), {
        method: "POST", credentials: "include",
      });
      if (res.ok) {
        clearAuthenticatedUser(currentUser?.email || accountEmail);
        setAuthMessage("All sessions logged out.");
        setMode("login");
        setDashTab("profile");
      }
    } catch { /* ignore */ }
  }

  async function loadAdminUsers() {
    setDashLoading(true);
    try {
      const qs = adminUserQuery ? `?q=${encodeURIComponent(adminUserQuery.trim())}` : "";
      const res = await fetch(buildApiUrl(`/api/admin/users${qs}`), { credentials: "include" });
      if (res.ok) setAdminUsers(await res.json());
    } catch { /* ignore */ } finally { setDashLoading(false); }
  }

  async function loadAdminSessions() {
    setDashLoading(true);
    try {
      const res = await fetch(buildApiUrl("/api/admin/sessions"), { credentials: "include" });
      if (res.ok) setAdminSessions(await res.json());
    } catch { /* ignore */ } finally { setDashLoading(false); }
  }

  async function loadAuditLogs(page) {
    setDashLoading(true);
    try {
      const res = await fetch(buildApiUrl(`/api/admin/audit-logs?page=${page}&size=20`), { credentials: "include" });
      if (res.ok) {
        const data = await res.json();
        setAuditLogs(data);
        setAuditPage(page);
      }
    } catch { /* ignore */ } finally { setDashLoading(false); }
  }

  async function adminRevokeSession(sessionId) {
    try {
      const res = await fetch(buildApiUrl(`/api/admin/sessions/${sessionId}`), {
        method: "DELETE", credentials: "include",
      });
      if (res.ok) {
        setAdminSessions(prev => prev.filter(s => s.sessionId !== sessionId));
      }
    } catch { /* ignore */ }
  }

  async function unlockUser(userId) {
    try {
      const res = await fetch(buildApiUrl(`/api/admin/users/${userId}/unlock`), {
        method: "POST", credentials: "include",
      });
      if (res.ok) void loadAdminUsers();
    } catch { /* ignore */ }
  }

  async function lockUser(userId, type, durationMinutes) {
    const confirmMsg = type === "permanent"
      ? "Lock this account permanently? Only an admin can unlock it."
      : `Lock this account for ${durationMinutes || 15} minutes?`;
    if (!window.confirm(confirmMsg)) return;
    try {
      const body = { type };
      if (type === "temporary") body.durationMinutes = durationMinutes || 15;
      const res = await fetch(buildApiUrl(`/api/admin/users/${userId}/lock`), {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (res.ok) void loadAdminUsers();
      else {
        const data = await parseResponse(res);
        setError(extractErrorMessage(data));
      }
    } catch { /* ignore */ }
  }

  async function fetchProfile() {
    const res = await fetch(buildApiUrl("/api/user/profile"), { credentials: "include" });
    if (!res.ok) return null;
    const data = await parseResponse(res);
    return data && typeof data === "object" ? data : null;
  }

  async function refreshSession() {
    const res = await fetch(buildApiUrl("/api/auth/refresh"), { method: "POST", credentials: "include" });
    if (!res.ok) return null;
    const data = await parseResponse(res);
    return data && typeof data === "object" ? data : null;
  }

  function applyAuthenticatedUser(data) {
    const email = normalizeEmail(data?.email || accountEmail || "");
    setCurrentUser({
      email,
      role: data?.role || "",
      id: data?.userId ?? data?.id ?? null,
      sessionId: data?.sessionId || "",
    });
    if (email) {
      setAccountEmail(email);
      setForgotPasswordEmail(email);
      setLoginForm({ ...initialLogin, email });
    }
  }

  function clearAuthenticatedUser(nextEmail = "") {
    const email = normalizeEmail(nextEmail || accountEmail || "");
    setCurrentUser(null);
    setProfile(null);
    setSessions([]);
    if (email) {
      setAccountEmail(email);
      setForgotPasswordEmail(email);
      setLoginForm({ ...initialLogin, email });
    } else {
      setAccountEmail("");
      setForgotPasswordEmail("");
      setLoginForm(initialLogin);
    }
  }

  // ─── Render ───
  if (isAuthenticated) {
    return (
      <div className="page-shell">
        <div className="backdrop backdrop-left" />
        <div className="backdrop backdrop-right" />
        <div className="dashboard-shell">
          {/* Top Bar */}
          <div className="top-bar">
            <div className="top-bar-left">
              <div className="brand-mark-sm">
                <span className="lock-icon" aria-hidden="true" />
              </div>
              <div>
                <p className="eyebrow" style={{ margin: 0 }}>AuthX</p>
              </div>
              <div className="nav-tabs">
                <button className={`nav-tab ${dashTab === "profile" ? "active" : ""}`} onClick={() => setDashTab("profile")}>Profile</button>
                <button className={`nav-tab ${dashTab === "sessions" ? "active" : ""}`} onClick={() => setDashTab("sessions")}>Sessions</button>
                {isAdmin && (
                  <>
                    <button className={`nav-tab ${dashTab === "admin-users" ? "active" : ""}`} onClick={() => setDashTab("admin-users")}>Users</button>
                    <button className={`nav-tab ${dashTab === "admin-sessions" ? "active" : ""}`} onClick={() => setDashTab("admin-sessions")}>All Sessions</button>
                    <button className={`nav-tab ${dashTab === "admin-logs" ? "active" : ""}`} onClick={() => setDashTab("admin-logs")}>Audit Logs</button>
                  </>
                )}
              </div>
            </div>
            <div className="top-bar-right">
              <div className="user-badge">
                {currentUser.email}
                <span className={`role-chip ${isAdmin ? "admin" : ""}`}>{currentUser.role}</span>
              </div>
              <button className="ghost-button" onClick={logout} disabled={loading}>
                {loading ? "..." : "Sign out"}
              </button>
            </div>
          </div>

          {(authMessage || error) && (
            <div className={`message ${error ? "error" : "success"}`} style={{ marginBottom: "1rem" }} role={error ? "alert" : "status"}>
              {error || authMessage}
            </div>
          )}

          {/* Profile Tab */}
          {dashTab === "profile" && (
            <div className="content-card animate-in">
              <div className="card-header">
                <div>
                  <h2 className="card-title">Your Profile</h2>
                  <p className="card-subtitle">Account details and information</p>
                </div>
              </div>
              {dashLoading ? <div className="loading-pulse">Loading profile...</div> : profile ? (
                <>
                  <div className="profile-grid">
                    <div className="profile-item">
                      <span className="profile-label">Email</span>
                      <span className="profile-value">{profile.email}</span>
                    </div>
                    <div className="profile-item">
                      <span className="profile-label">Role</span>
                      <span className="profile-value">{profile.role}</span>
                    </div>
                    <div className="profile-item">
                      <span className="profile-label">Status</span>
                      <span className="profile-value">
                        <span className={`status-badge ${profile.verified ? "verified" : "unverified"}`}>
                          <span className="status-dot" />
                          {profile.verified ? "Verified" : "Unverified"}
                        </span>
                      </span>
                    </div>
                    <div className="profile-item">
                      <span className="profile-label">Member Since</span>
                      <span className="profile-value">{formatDate(profile.createdAt)}</span>
                    </div>
                  </div>
                  
                  <div style={{ marginTop: '2rem' }}>
                    <h3 className="card-title" style={{ marginBottom: '1rem', fontSize: '1.25rem' }}>Security Settings</h3>
                    <div className="profile-item" style={{ flexDirection: 'column', alignItems: 'flex-start', gap: '1rem', background: 'var(--bg-main)', border: '1px solid var(--border)' }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', width: '100%', alignItems: 'center' }}>
                        <div>
                          <span className="profile-label">Two-Factor Authentication (2FA)</span>
                          <p className="profile-value" style={{ marginTop: '0.25rem', fontSize: '0.9rem', color: 'var(--text-muted)' }}>
                            {profile.twoFactorEnabled ? 'Enabled. Your account is secured with 2FA.' : 'Add an extra layer of security to your account.'}
                          </p>
                        </div>
                        {profile.twoFactorEnabled ? (
                          <label className="toggle-switch" onClick={handleDisable2Fa}>
                            <span className="toggle-label active">Enabled</span>
                            <div className={`toggle-track ${!loading ? 'active' : ''}`} role="switch" aria-checked="true">
                              <div className={`toggle-thumb ${!loading ? 'active' : ''}`} />
                            </div>
                          </label>
                        ) : (
                          <label className="toggle-switch" onClick={() => !twoFactorSetup && handleGenerate2Fa()}>
                            <span className="toggle-label">Disabled</span>
                            <div className="toggle-track" role="switch" aria-checked="false">
                              <div className="toggle-thumb" />
                            </div>
                          </label>
                        )}
                      </div>
                      
                      {twoFactorSetup && !profile.twoFactorEnabled && (
                        <div className="animate-in" style={{ marginTop: '1rem', padding: '1.5rem', background: 'var(--bg-elevated)', borderRadius: '8px', border: '1px solid var(--border)', width: '100%' }}>
                          <p style={{ marginBottom: '1rem', fontWeight: 500 }}>1. Scan this QR code with your authenticator app (e.g., Google Authenticator, Authy):</p>
                          <div style={{ background: '#fff', padding: '0.5rem', display: 'inline-block', borderRadius: '4px', marginBottom: '1rem' }}>
                            <img src={twoFactorSetup.qrCode} alt="2FA QR Code" style={{ width: '200px', height: '200px', display: 'block' }} />
                          </div>
                          <p style={{ marginBottom: '0.5rem', fontFamily: 'monospace', fontSize: '0.9rem', color: 'var(--text-muted)' }}>Secret key: {twoFactorSetup.secret}</p>
                          
                          <p style={{ marginTop: '1.5rem', marginBottom: '0.75rem', fontWeight: 500 }}>2. Enter the 6-digit code generated by your app:</p>
                          <form onSubmit={handleEnable2Fa} style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                            <input 
                              type="text" 
                              placeholder="000000" 
                              maxLength={6} 
                              required 
                              value={setupTwoFactorCode}
                              onChange={e => setSetupTwoFactorCode(normalizeOtpCode(e.target.value))}
                              style={{ 
                                padding: '0.6rem 0.75rem', 
                                borderRadius: '6px', 
                                border: '1px solid var(--border)', 
                                background: 'var(--bg-main)', 
                                color: 'var(--text-main)', 
                                width: '120px',
                                fontSize: '1.1rem',
                                letterSpacing: '2px',
                                textAlign: 'center'
                              }}
                            />
                            <button type="submit" className="primary-button" disabled={loading || setupTwoFactorCode.length !== 6}>Verify & Enable</button>
                            <button type="button" className="ghost-button" onClick={() => { setTwoFactorSetup(null); setSetupTwoFactorCode(""); }}>Cancel</button>
                          </form>
                        </div>
                      )}
                    </div>
                  </div>
                </>
              ) : (
                <div className="empty-state">
                  <p className="empty-state-text">Unable to load profile.</p>
                </div>
              )}
            </div>
          )}

          {/* Sessions Tab */}
          {dashTab === "sessions" && (
            <div className="content-card animate-in">
              <div className="card-header">
                <div>
                  <h2 className="card-title">Active Sessions</h2>
                  <p className="card-subtitle">Devices where you are currently signed in</p>
                </div>
                <div style={{ display: "flex", gap: "0.5rem" }}>
                  <button className="secondary-button" onClick={loadSessions} disabled={dashLoading}>Refresh</button>
                  {sessions.length > 1 && (
                    <button className="danger-button" onClick={logoutAll}>Logout All</button>
                  )}
                </div>
              </div>
              {dashLoading ? <div className="loading-pulse">Loading sessions...</div> : sessions.length === 0 ? (
                <div className="empty-state">
                  <div className="empty-state-icon">🔒</div>
                  <p className="empty-state-text">No active sessions found.</p>
                </div>
              ) : (
                <div className="session-list">
                  {sessions.map(s => (
                    <div key={s.sessionId} className={`session-card ${s.current ? "current" : ""}`}>
                      <div className="session-info">
                        <p className="session-device">
                          {parseDevice(s.userAgent)}
                          {s.current && <span className="current-badge">This device</span>}
                        </p>
                        <p className="session-meta">
                          IP: {s.ipAddress || "—"}<br />
                          Created: {formatDate(s.createdAt)}<br />
                          Last used: {formatDate(s.lastUsedAt)}<br />
                          Expires: {formatDate(s.expiresAt)}
                        </p>
                      </div>
                      <button
                        className={s.current ? "ghost-button" : "danger-button"}
                        onClick={() => s.current ? logout() : revokeSession(s.sessionId)}
                      >
                        {s.current ? "Sign out" : "Revoke"}
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* Admin — Users */}
          {dashTab === "admin-users" && isAdmin && (
            <div className="content-card animate-in">
              <div className="card-header">
                <div>
                  <h2 className="card-title">All Users</h2>
                  <p className="card-subtitle">Manage registered accounts</p>
                </div>
                <div style={{ display: "flex", gap: "0.5rem", alignItems: "center" }}>
                  <input
                    type="search"
                    placeholder="Search by email…"
                    value={adminUserQuery}
                    onChange={e => setAdminUserQuery(e.target.value)}
                    style={{ minWidth: "220px" }}
                  />
                  <button className="secondary-button" onClick={loadAdminUsers} disabled={dashLoading}>Refresh</button>
                </div>
              </div>
              {dashLoading ? <div className="loading-pulse">Loading users...</div> : (
                <div className="data-table-wrapper">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>ID</th>
                        <th>Email</th>
                        <th>Role</th>
                        <th>Status</th>
                        <th>Created</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {adminUsers.map(u => (
                        <tr key={u.id}>
                          <td>{u.id}</td>
                          <td>{u.email}</td>
                          <td><span className={`role-chip ${u.role === "ADMIN" ? "admin" : ""}`}>{u.role}</span></td>
                          <td>
                            {u.locked ? (
                              <span className={`status-badge locked`}>
                                <span className="status-dot" />
                                {u.lockType === "permanent" ? "Locked (permanent)" : u.lockType === "auto" ? "Locked (auto)" : "Locked"}
                              </span>
                            ) : (
                              <span className={`status-badge ${u.verified ? "verified" : "unverified"}`}>
                                <span className="status-dot" />{u.verified ? "Verified" : "Unverified"}
                              </span>
                            )}
                          </td>
                          <td>{formatDate(u.createdAt)}</td>
                          <td>
                            {u.locked ? (
                              <button className="secondary-button" onClick={() => unlockUser(u.id)}>Unlock</button>
                            ) : (
                              <div style={{ display: "flex", gap: "0.4rem" }}>
                                <button className="danger-button" onClick={() => lockUser(u.id, "temporary", 15)}>Lock (15m)</button>
                                <button className="danger-button" style={{ background: "#7f1d1d", borderColor: "#991b1b" }} onClick={() => lockUser(u.id, "permanent")}>Lock (permanent)</button>
                              </div>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}

          {/* Admin — All Sessions */}
          {dashTab === "admin-sessions" && isAdmin && (
            <div className="content-card animate-in">
              <div className="card-header">
                <div>
                  <h2 className="card-title">All Active Sessions</h2>
                  <p className="card-subtitle">Sessions across all users</p>
                </div>
                <button className="secondary-button" onClick={loadAdminSessions} disabled={dashLoading}>Refresh</button>
              </div>
              {dashLoading ? <div className="loading-pulse">Loading sessions...</div> : (
                <div className="data-table-wrapper">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>User</th>
                        <th>Device</th>
                        <th>IP</th>
                        <th>Last Used</th>
                        <th>Expires</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {adminSessions.map(s => (
                        <tr key={s.sessionId}>
                          <td>{s.email}</td>
                          <td title={s.userAgent}>{parseDevice(s.userAgent)}</td>
                          <td>{s.ipAddress || "—"}</td>
                          <td>{formatDate(s.lastUsedAt)}</td>
                          <td>{formatDate(s.expiresAt)}</td>
                          <td>
                            <button className="danger-button" onClick={() => adminRevokeSession(s.sessionId)}>Revoke</button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}

          {/* Admin — Audit Logs */}
          {dashTab === "admin-logs" && isAdmin && (
            <div className="content-card animate-in">
              <div className="card-header">
                <div>
                  <h2 className="card-title">Audit Logs</h2>
                  <p className="card-subtitle">Authentication event history</p>
                </div>
                <button className="secondary-button" onClick={() => loadAuditLogs(auditPage)} disabled={dashLoading}>Refresh</button>
              </div>
              {dashLoading ? <div className="loading-pulse">Loading logs...</div> : (
                <>
                  <div className="data-table-wrapper">
                    <table className="data-table">
                      <thead>
                        <tr>
                          <th>Time</th>
                          <th>Action</th>
                          <th>Email</th>
                          <th>IP</th>
                          <th>Details</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(auditLogs.content || []).map(log => (
                          <tr key={log.id}>
                            <td>{formatDate(log.createdAt)}</td>
                            <td><span className={`action-badge ${getActionColor(log.action)}`}>{log.action}</span></td>
                            <td>{log.email || "—"}</td>
                            <td>{log.ipAddress || "—"}</td>
                            <td title={log.details}>{log.details || "—"}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  {auditLogs.totalPages > 1 && (
                    <div className="pagination">
                      <button className="page-btn" disabled={auditPage <= 0} onClick={() => loadAuditLogs(auditPage - 1)}>← Prev</button>
                      <span className="page-info">Page {auditPage + 1} of {auditLogs.totalPages}</span>
                      <button className="page-btn" disabled={auditPage >= auditLogs.totalPages - 1} onClick={() => loadAuditLogs(auditPage + 1)}>Next →</button>
                    </div>
                  )}
                </>
              )}
            </div>
          )}
        </div>
      </div>
    );
  }

  // ─── Auth Forms ───
  const title = mode === "signup" ? "Create account"
    : mode === "forgot" ? "Forgot password"
    : mode === "reset" ? "Reset password"
    : mode === "verify" ? "Verify email"
    : mode === "magic-verify" ? "Secure Sign In"
    : mode === "magic-sent" ? "Check your email"
    : mode === "verify-2fa" ? "Two-Factor Authentication"
    : "Sign in";

  const subtitle = restoringSession ? "Checking your session..."
    : mode === "signup" ? "Create your account, then verify your email."
    : mode === "forgot" ? "Enter your email to receive a reset link."
    : mode === "reset" ? "Choose a new password for your account."
    : mode === "verify" ? "Finishing your email verification."
    : mode === "magic-verify" ? "Securely signing you in."
    : mode === "magic-sent" ? "We've sent a magic link to your email."
    : mode === "verify-2fa" ? "Enter the 6-digit code from your authenticator app."
    : "Use your email and password to continue.";

  return (
    <div className="page-shell">
      <div className="backdrop backdrop-left" />
      <div className="backdrop backdrop-right" />
      <main className="auth-shell">
        <section className="auth-card">
          <div className="brand-row">
            <div className="brand-mark">
              <span className="lock-icon" aria-hidden="true" />
            </div>
            <div>
              <p className="eyebrow">AuthX</p>
              <h1>{title}</h1>
              <p className="subtitle">{subtitle}</p>
            </div>
          </div>

          {mode !== "forgot" && mode !== "reset" && mode !== "verify" && mode !== "verify-2fa" && mode !== "magic-sent" && mode !== "magic-verify" && (
            <div className="tab-row">
              <button className={mode === "login" ? "tab active" : "tab"} onClick={() => switchMode("login")} type="button">Sign in</button>
              <button className={mode === "signup" ? "tab active" : "tab"} onClick={() => switchMode("signup")} type="button">Sign up</button>
            </div>
          )}

          {(authMessage || error) && (
            <div aria-live="polite" className={error ? "message error" : "message success"} role={error ? "alert" : "status"}>
              {error || authMessage}
              {error && error.toLowerCase().includes("verify") && (
                <button className="text-link" style={{ marginTop: "0.5rem", display: "block" }} onClick={handleResendVerification} type="button">
                  Resend verification email
                </button>
              )}
            </div>
          )}

          {mode === "login" ? (
            <form autoComplete="on" className="auth-form" onSubmit={handleLogin}>
              <label className="field">
                <span>Email</span>
                <input autoCapitalize="none" autoComplete="username" autoCorrect="off" inputMode="email" name="email" onChange={e => setLoginForm(c => ({ ...c, email: e.target.value }))} placeholder="name@company.com" required spellCheck="false" type="email" value={loginForm.email} />
              </label>
              <label className="field">
                <span>Password</span>
                <input autoComplete="current-password" name="password" onChange={e => setLoginForm(c => ({ ...c, password: e.target.value }))} placeholder="Enter your password" required type="password" value={loginForm.password} />
              </label>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', marginTop: '1rem' }}>
                <button className="primary-button" disabled={loading || restoringSession} type="submit">
                  {loading ? "Signing in..." : "Sign in with password"}
                </button>
                <button className="secondary-button" disabled={loading || restoringSession} type="button" onClick={handleRequestMagicLink}>
                  {loading ? "Sending..." : "Email me a magic link"}
                </button>
              </div>
              <button className="text-link" onClick={() => switchMode("forgot")} type="button">Forgot password?</button>
              <div className="auth-divider"><span>or</span></div>
              <button className="google-button" type="button" onClick={initiateGoogleLogin}>
                <svg viewBox="0 0 24 24" width="18" height="18"><path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z" fill="#4285F4"/><path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/><path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05"/><path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/></svg>
                Continue with Google
              </button>
            </form>
          ) : mode === "signup" ? (
            <form autoComplete="on" className="auth-form" onSubmit={handleSignup}>
              <label className="field">
                <span>Email</span>
                <input autoCapitalize="none" autoComplete="username" autoCorrect="off" inputMode="email" name="email" onChange={e => setSignupForm(c => ({ ...c, email: e.target.value }))} placeholder="name@company.com" required spellCheck="false" type="email" value={signupForm.email} />
              </label>
              <label className="field">
                <span>Password</span>
                <input autoComplete="new-password" minLength={8} name="password" onChange={e => setSignupForm(c => ({ ...c, password: e.target.value }))} placeholder="Create a password" required type="password" value={signupForm.password} />
              </label>
              <label className="field">
                <span>Confirm password</span>
                <input autoComplete="new-password" minLength={8} name="confirmPassword" onChange={e => setSignupForm(c => ({ ...c, confirmPassword: e.target.value }))} placeholder="Confirm your password" required type="password" value={signupForm.confirmPassword} />
              </label>
              <p className="helper-text">8+ characters with uppercase, lowercase, number, and special character.</p>
              <button className="primary-button" disabled={loading} type="submit">
                {loading ? "Creating account..." : "Sign up"}
              </button>
              <div className="auth-divider"><span>or</span></div>
              <button className="google-button" type="button" onClick={initiateGoogleLogin}>
                <svg viewBox="0 0 24 24" width="18" height="18"><path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z" fill="#4285F4"/><path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/><path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05"/><path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/></svg>
                Continue with Google
              </button>
            </form>
          ) : mode === "forgot" ? (
            <form autoComplete="on" className="auth-form" onSubmit={handleForgotPassword}>
              <label className="field">
                <span>Email</span>
                <input autoCapitalize="none" autoComplete="username" autoCorrect="off" inputMode="email" name="email" onChange={e => setForgotPasswordEmail(e.target.value)} placeholder="name@company.com" required spellCheck="false" type="email" value={forgotPasswordEmail} />
              </label>
              <button className="primary-button" disabled={loading} type="submit">
                {loading ? "Sending..." : "Send reset link"}
              </button>
              <button className="text-link" onClick={() => switchMode("login")} type="button">Back to sign in</button>
            </form>
          ) : mode === "reset" ? (
            <form autoComplete="on" className="auth-form" onSubmit={handleResetPassword}>
              <label className="field">
                <span>New password</span>
                <input autoComplete="new-password" minLength={8} name="password" onChange={e => setResetForm(c => ({ ...c, password: e.target.value }))} placeholder="Create a new password" required type="password" value={resetForm.password} />
              </label>
              <label className="field">
                <span>Confirm password</span>
                <input autoComplete="new-password" minLength={8} name="confirmPassword" onChange={e => setResetForm(c => ({ ...c, confirmPassword: e.target.value }))} placeholder="Confirm your new password" required type="password" value={resetForm.confirmPassword} />
              </label>
              <button className="primary-button" disabled={loading} type="submit">
                {loading ? "Resetting..." : "Reset password"}
              </button>
              <button className="text-link" onClick={() => switchMode("login")} type="button">Back to sign in</button>
            </form>
          ) : mode === "verify-2fa" ? (
            <form autoComplete="off" className="auth-form" onSubmit={handleVerify2Fa}>
              <label className="field">
                <span>Authenticator Code</span>
                <input 
                  autoFocus 
                  name="code" 
                  onChange={e => setTwoFactorCode(normalizeOtpCode(e.target.value))} 
                  placeholder="000000" 
                  required 
                  type="text" 
                  value={twoFactorCode} 
                  maxLength={6} 
                  inputMode="numeric"
                  style={{ fontSize: '1.2rem', letterSpacing: '4px', textAlign: 'center' }}
                />
              </label>
              <button className="primary-button" disabled={loading || twoFactorCode.length !== 6} type="submit">
                {loading ? "Verifying..." : "Verify Code"}
              </button>
              <button className="text-link" onClick={() => switchMode("login")} type="button">Back to sign in</button>
            </form>
          ) : mode === "magic-sent" ? (
            <div className="auth-form">
              <div style={{ textAlign: "center", padding: "1rem 0" }}>
                <span style={{ fontSize: "2.5rem", display: "block", marginBottom: "1rem" }}>✉️</span>
                <p style={{ margin: 0, fontSize: "1.05rem", color: "var(--text-main)", fontWeight: 500 }}>
                  We sent a secure sign-in link to <strong>{loginForm.email}</strong>.
                </p>
                <p className="helper-text" style={{ marginTop: "0.5rem" }}>
                  Please check your inbox and click the link to continue.
                </p>
              </div>
              <button className="secondary-button" onClick={() => switchMode("login")} type="button">Back to sign in</button>
            </div>
          ) : mode === "magic-verify" ? (
            <div className="auth-form">
              <p className="helper-text" style={{ textAlign: "center", fontSize: "1.1rem" }}>
                {loading ? "Verifying magic link..." : "Signing you in..."}
              </p>
              {!loading && <button className="secondary-button" onClick={() => switchMode("login")} type="button">Back to sign in</button>}
            </div>
          ) : (
            <div className="auth-form">
              <p className="helper-text">{loading ? "Verifying your email..." : "Use the link in your email to verify."}</p>
              {!loading && <button className="text-link" onClick={() => switchMode("login")} type="button">Back to sign in</button>}
            </div>
          )}
        </section>
      </main>
    </div>
  );
}

// ─── Utilities ───
function getGoogleClientId() {
  return import.meta.env.VITE_GOOGLE_CLIENT_ID || "";
}

async function parseResponse(res) {
  const ct = res.headers.get("content-type") || "";
  return ct.includes("application/json") ? res.json() : res.text();
}

function extractErrorMessage(data) {
  if (typeof data === "string" && data) return data;
  if (data && typeof data === "object") {
    if (typeof data.message === "string") return data.message;
    const first = Object.values(data)[0];
    if (typeof first === "string") return first;
  }
  return "Request failed";
}

function getInitialMode() {
  if (getQueryToken("reset")) return "reset";
  if (getQueryToken("verify")) return "verify";
  if (getQueryToken("magic")) return "magic-verify";
  return "login";
}

function getQueryToken(name) {
  if (typeof window === "undefined") return "";
  return new URLSearchParams(window.location.search).get(name) || "";
}

function clearAuthQueryParams() {
  if (typeof window === "undefined" || !window.location.search) return;
  const url = new URL(window.location.href);
  url.searchParams.delete("verify");
  url.searchParams.delete("reset");
  url.searchParams.delete("magic");
  url.searchParams.delete("oauth");
  window.history.replaceState({}, "", url.pathname + url.search + url.hash);
}

function clearLegacyLocalAuthToken() {
  if (typeof window !== "undefined") localStorage.removeItem("auth_token");
}

function getApiBaseUrl() {
  const base = import.meta.env.VITE_API_BASE_URL || "";
  if (!base) return "";
  return base.endsWith("/") ? base.slice(0, -1) : base;
}

function buildApiUrl(path) { return `${apiBaseUrl}${path}`; }
function normalizeEmail(v) { return v.trim().toLowerCase(); }
function normalizeOtpCode(v) { return (v || "").replace(/\D/g, "").slice(0, 6); }

function formatDate(iso) {
  if (!iso) return "—";
  try {
    const d = new Date(iso);
    return d.toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" }) +
      " " + d.toLocaleTimeString("en-US", { hour: "2-digit", minute: "2-digit" });
  } catch { return iso; }
}

function parseDevice(ua) {
  if (!ua) return "Unknown device";

  const lower = ua.toLowerCase();

  // Mobile platforms
  if (lower.includes("android")) return "🤖 Android";
  if (lower.includes("iphone") || lower.includes("ipad") || lower.includes("ios")) return "📱 iOS";

  // Apple desktop / laptop
  if (lower.includes("macintosh") || lower.includes("mac os")) return "💻 Mac";

  // Windows / generic desktop
  if (lower.includes("windows")) return "💻 Windows";
  if (lower.includes("linux")) return "🖥️ Linux";

  // Fallback to browser-based hint
  if (ua.includes("Chrome")) return "🌐 Chrome";
  if (ua.includes("Firefox")) return "🌐 Firefox";
  if (ua.includes("Safari")) return "🌐 Safari";
  if (ua.includes("Edge")) return "🌐 Edge";
  if (ua.includes("Opera") || ua.includes("OPR")) return "🌐 Opera";

  return ua.length > 40 ? ua.substring(0, 40) + "…" : ua;
}

function getActionColor(action) {
  if (!action) return "info";
  if (action.includes("SUCCESS") || action.includes("VERIFIED")) return "success";
  if (action.includes("FAILURE") || action.includes("LOCKED")) return "danger";
  if (action.includes("REVOKE") || action.includes("RESET") || action.includes("LOGOUT")) return "warning";
  return "info";
}

export default App;
