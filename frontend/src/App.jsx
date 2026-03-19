import { useEffect, useState } from "react";

const apiBaseUrl = getApiBaseUrl();

const initialSignup = {
  email: "",
  password: "",
  confirmPassword: "",
};

const initialLogin = {
  email: "",
  password: "",
};

const initialReset = {
  password: "",
  confirmPassword: "",
};

function App() {
  const [mode, setMode] = useState(() => getInitialMode());
  const [signupForm, setSignupForm] = useState(initialSignup);
  const [loginForm, setLoginForm] = useState(() => ({
    ...initialLogin,
    email: localStorage.getItem("auth_email") || "",
  }));
  const [forgotPasswordEmail, setForgotPasswordEmail] = useState(
    () => localStorage.getItem("auth_email") || "",
  );
  const [resetForm, setResetForm] = useState(initialReset);
  const [verificationToken, setVerificationToken] = useState(() =>
    getQueryToken("verify"),
  );
  const [resetToken, setResetToken] = useState(() => getQueryToken("reset"));
  const [token, setToken] = useState(
    () => localStorage.getItem("auth_token") || "",
  );
  const [accountEmail, setAccountEmail] = useState(
    () => localStorage.getItem("auth_email") || "",
  );
  const [authMessage, setAuthMessage] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const isAuthenticated = Boolean(token);
  const displayEmail = accountEmail || loginForm.email || "You are signed in.";

  useEffect(() => {
    clearAuthQueryParams();
  }, []);

  useEffect(() => {
    if (mode === "verify" && verificationToken) {
      void handleVerifyEmail();
    }
  }, [mode, verificationToken]);

  useEffect(() => {
    if (token) {
      localStorage.setItem("auth_token", token);
    } else {
      localStorage.removeItem("auth_token");
    }
  }, [token]);

  useEffect(() => {
    if (accountEmail) {
      localStorage.setItem("auth_email", accountEmail);
    } else {
      localStorage.removeItem("auth_email");
    }
  }, [accountEmail]);

  function switchMode(nextMode) {
    setMode(nextMode);
    setAuthMessage("");
    setError("");
    setLoading(false);

    if (nextMode === "forgot") {
      setForgotPasswordEmail(accountEmail || loginForm.email || "");
    }
  }

  async function handleSignup(event) {
    event.preventDefault();
    setLoading(true);
    setError("");
    setAuthMessage("");

    try {
      const email = normalizeEmail(signupForm.email);
      const response = await fetch(buildApiUrl("/api/auth/signup"), {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          ...signupForm,
          email,
        }),
      });

      const data = await parseResponse(response);
      if (!response.ok) {
        throw new Error(extractErrorMessage(data));
      }

      setAccountEmail(email);
      setLoginForm({
        ...initialLogin,
        email,
      });
      setForgotPasswordEmail(email);
      setSignupForm(initialSignup);
      setMode("login");
      setAuthMessage(
        typeof data === "string"
          ? data
          : "Account created. Check your email to verify your account.",
      );
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setLoading(false);
    }
  }

  async function handleLogin(event) {
    event.preventDefault();
    setLoading(true);
    setError("");
    setAuthMessage("");

    try {
      const email = normalizeEmail(loginForm.email);
      const response = await fetch(buildApiUrl("/api/auth/login"), {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        credentials: "include",
        body: JSON.stringify({
          ...loginForm,
          email,
        }),
      });

      const data = await parseResponse(response);
      if (!response.ok) {
        throw new Error(extractErrorMessage(data));
      }

      setAccountEmail(email);
      setForgotPasswordEmail(email);
      setToken(data.token || "");
      setLoginForm((current) => ({
        ...initialLogin,
        email,
      }));
      setAuthMessage(data.message || "Signed in.");
    } catch (requestError) {
      setToken("");
      setError(requestError.message);
    } finally {
      setLoading(false);
    }
  }

  async function handleForgotPassword(event) {
    event.preventDefault();
    setLoading(true);
    setError("");
    setAuthMessage("");

    try {
      const email = normalizeEmail(forgotPasswordEmail);
      const response = await fetch(buildApiUrl("/api/auth/forgot-password"), {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ email }),
      });

      const data = await parseResponse(response);
      if (!response.ok) {
        throw new Error(extractErrorMessage(data));
      }

      setForgotPasswordEmail(email);
      setAuthMessage(
        typeof data === "string"
          ? data
          : "If that email exists, a password reset link has been sent.",
      );
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setLoading(false);
    }
  }

  async function handleResetPassword(event) {
    event.preventDefault();
    setLoading(true);
    setError("");
    setAuthMessage("");

    try {
      const response = await fetch(buildApiUrl("/api/auth/reset-password"), {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          token: resetToken,
          password: resetForm.password,
          confirmPassword: resetForm.confirmPassword,
        }),
      });

      const data = await parseResponse(response);
      if (!response.ok) {
        throw new Error(extractErrorMessage(data));
      }

      setResetForm(initialReset);
      setResetToken("");
      setMode("login");
      setAuthMessage(
        typeof data === "string"
          ? data
          : "Password reset successful. You can sign in now.",
      );
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setLoading(false);
    }
  }

  async function handleVerifyEmail() {
    if (!verificationToken) {
      setError("The verification link is invalid or has expired.");
      return;
    }

    setLoading(true);
    setError("");
    setAuthMessage("");

    try {
      const response = await fetch(buildApiUrl(
        `/api/auth/verify-email?token=${encodeURIComponent(verificationToken)}`,
      ), {
        method: "POST",
      });

      const data = await parseResponse(response);
      if (!response.ok) {
        throw new Error(extractErrorMessage(data));
      }

      setVerificationToken("");
      setMode("login");
      setAuthMessage(
        typeof data === "string"
          ? data
          : "Email verified. You can sign in now.",
      );
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setLoading(false);
    }
  }

  function logout() {
    setToken("");
    setAuthMessage("Signed out.");
    setError("");
    setMode("login");
    setLoginForm((current) => ({
      ...initialLogin,
      email: current.email || accountEmail,
    }));
  }

  const title = isAuthenticated
    ? "Signed in"
    : mode === "signup"
      ? "Create account"
      : mode === "forgot"
        ? "Forgot password"
        : mode === "reset"
          ? "Reset password"
          : mode === "verify"
            ? "Verify email"
            : "Sign in";

  const subtitle = isAuthenticated
    ? "Your session is active."
    : mode === "signup"
      ? "Create your account, then verify your email before signing in."
      : mode === "forgot"
        ? "Enter your email to receive a password reset link."
        : mode === "reset"
          ? "Choose a new password for your account."
          : mode === "verify"
            ? "Finishing your email verification."
            : "Use your email and password to continue.";

  return (
    <div className="page-shell">
      <div className="backdrop backdrop-left" />
      <div className="backdrop backdrop-right" />

      <main className="auth-shell">
        <section className="auth-card">
          <div className="brand-row">
            <div className="brand-mark">AX</div>
            <div>
              <p className="eyebrow">AuthX</p>
              <h1>{title}</h1>
              <p className="subtitle">{subtitle}</p>
            </div>
          </div>

          {!isAuthenticated &&
            mode !== "forgot" &&
            mode !== "reset" &&
            mode !== "verify" && (
              <div className="tab-row">
                <button
                  className={mode === "login" ? "tab active" : "tab"}
                  onClick={() => switchMode("login")}
                  type="button"
                >
                  Sign in
                </button>
                <button
                  className={mode === "signup" ? "tab active" : "tab"}
                  onClick={() => switchMode("signup")}
                  type="button"
                >
                  Sign up
                </button>
              </div>
            )}

          {(authMessage || error) && (
            <div
              aria-live="polite"
              className={error ? "message error" : "message success"}
              role={error ? "alert" : "status"}
            >
              {error || authMessage}
            </div>
          )}

          {isAuthenticated ? (
            <div className="signed-in-state">
              <p className="signed-in-email">{displayEmail}</p>
              <button className="primary-button" onClick={logout} type="button">
                Sign out
              </button>
            </div>
          ) : mode === "login" ? (
            <form
              autoComplete="on"
              className="auth-form"
              onSubmit={handleLogin}
            >
              <label className="field">
                <span>Email</span>
                <input
                  autoCapitalize="none"
                  autoComplete="username"
                  autoCorrect="off"
                  inputMode="email"
                  name="email"
                  onChange={(event) =>
                    setLoginForm((current) => ({
                      ...current,
                      email: event.target.value,
                    }))
                  }
                  placeholder="name@company.com"
                  required
                  spellCheck="false"
                  type="email"
                  value={loginForm.email}
                />
              </label>

              <label className="field">
                <span>Password</span>
                <input
                  autoComplete="current-password"
                  name="password"
                  onChange={(event) =>
                    setLoginForm((current) => ({
                      ...current,
                      password: event.target.value,
                    }))
                  }
                  placeholder="Enter your password"
                  required
                  type="password"
                  value={loginForm.password}
                />
              </label>

              <button
                className="primary-button"
                disabled={loading}
                type="submit"
              >
                {loading ? "Signing in..." : "Sign in"}
              </button>

              <button
                className="text-link"
                onClick={() => switchMode("forgot")}
                type="button"
              >
                Forgot password?
              </button>
            </form>
          ) : mode === "signup" ? (
            <form
              autoComplete="on"
              className="auth-form"
              onSubmit={handleSignup}
            >
              <label className="field">
                <span>Email</span>
                <input
                  autoCapitalize="none"
                  autoComplete="username"
                  autoCorrect="off"
                  inputMode="email"
                  name="email"
                  onChange={(event) =>
                    setSignupForm((current) => ({
                      ...current,
                      email: event.target.value,
                    }))
                  }
                  placeholder="name@company.com"
                  required
                  spellCheck="false"
                  type="email"
                  value={signupForm.email}
                />
              </label>

              <label className="field">
                <span>Password</span>
                <input
                  autoComplete="new-password"
                  minLength={8}
                  name="password"
                  onChange={(event) =>
                    setSignupForm((current) => ({
                      ...current,
                      password: event.target.value,
                    }))
                  }
                  placeholder="Create a password"
                  required
                  type="password"
                  value={signupForm.password}
                />
              </label>

              <label className="field">
                <span>Confirm password</span>
                <input
                  autoComplete="new-password"
                  minLength={8}
                  name="confirmPassword"
                  onChange={(event) =>
                    setSignupForm((current) => ({
                      ...current,
                      confirmPassword: event.target.value,
                    }))
                  }
                  placeholder="Confirm your password"
                  required
                  type="password"
                  value={signupForm.confirmPassword}
                />
              </label>

              <p className="helper-text">
                8+ characters with uppercase, lowercase, number, and special
                character.
              </p>

              <button
                className="primary-button"
                disabled={loading}
                type="submit"
              >
                {loading ? "Creating account..." : "Sign up"}
              </button>
            </form>
          ) : mode === "forgot" ? (
            <form
              autoComplete="on"
              className="auth-form"
              onSubmit={handleForgotPassword}
            >
              <label className="field">
                <span>Email</span>
                <input
                  autoCapitalize="none"
                  autoComplete="username"
                  autoCorrect="off"
                  inputMode="email"
                  name="email"
                  onChange={(event) =>
                    setForgotPasswordEmail(event.target.value)
                  }
                  placeholder="name@company.com"
                  required
                  spellCheck="false"
                  type="email"
                  value={forgotPasswordEmail}
                />
              </label>

              <button
                className="primary-button"
                disabled={loading}
                type="submit"
              >
                {loading ? "Sending reset link..." : "Send reset link"}
              </button>

              <button
                className="text-link"
                onClick={() => switchMode("login")}
                type="button"
              >
                Back to sign in
              </button>
            </form>
          ) : mode === "reset" ? (
            <form
              autoComplete="on"
              className="auth-form"
              onSubmit={handleResetPassword}
            >
              <label className="field">
                <span>New password</span>
                <input
                  autoComplete="new-password"
                  minLength={8}
                  name="password"
                  onChange={(event) =>
                    setResetForm((current) => ({
                      ...current,
                      password: event.target.value,
                    }))
                  }
                  placeholder="Create a new password"
                  required
                  type="password"
                  value={resetForm.password}
                />
              </label>

              <label className="field">
                <span>Confirm password</span>
                <input
                  autoComplete="new-password"
                  minLength={8}
                  name="confirmPassword"
                  onChange={(event) =>
                    setResetForm((current) => ({
                      ...current,
                      confirmPassword: event.target.value,
                    }))
                  }
                  placeholder="Confirm your new password"
                  required
                  type="password"
                  value={resetForm.confirmPassword}
                />
              </label>

              <button
                className="primary-button"
                disabled={loading}
                type="submit"
              >
                {loading ? "Resetting password..." : "Reset password"}
              </button>

              <button
                className="text-link"
                onClick={() => switchMode("login")}
                type="button"
              >
                Back to sign in
              </button>
            </form>
          ) : (
            <div className="auth-form">
              <p className="helper-text">
                {loading
                  ? "Verifying your email..."
                  : "Use the link in your email to verify your account."}
              </p>

              {!loading && (
                <button
                  className="text-link"
                  onClick={() => switchMode("login")}
                  type="button"
                >
                  Back to sign in
                </button>
              )}
            </div>
          )}
        </section>
      </main>
    </div>
  );
}

async function parseResponse(response) {
  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("application/json")) {
    return response.json();
  }

  return response.text();
}

function extractErrorMessage(data) {
  if (typeof data === "string" && data) {
    return data;
  }

  if (data && typeof data === "object") {
    if (typeof data.message === "string") {
      return data.message;
    }

    const firstValue = Object.values(data)[0];
    if (typeof firstValue === "string") {
      return firstValue;
    }
  }

  return "Request failed";
}

function getInitialMode() {
  if (getQueryToken("reset")) {
    return "reset";
  }

  if (getQueryToken("verify")) {
    return "verify";
  }

  return "login";
}

function getQueryToken(name) {
  if (typeof window === "undefined") {
    return "";
  }

  return new URLSearchParams(window.location.search).get(name) || "";
}

function clearAuthQueryParams() {
  if (typeof window === "undefined" || !window.location.search) {
    return;
  }

  const url = new URL(window.location.href);
  url.searchParams.delete("verify");
  url.searchParams.delete("reset");
  window.history.replaceState({}, "", url.pathname + url.search + url.hash);
}

function getApiBaseUrl() {
  const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL;
  if (!configuredBaseUrl) {
    return "";
  }

  return configuredBaseUrl.endsWith("/")
    ? configuredBaseUrl.slice(0, -1)
    : configuredBaseUrl;
}

function buildApiUrl(path) {
  return `${apiBaseUrl}${path}`;
}

function normalizeEmail(value) {
  return value.trim().toLowerCase();
}

export default App;
