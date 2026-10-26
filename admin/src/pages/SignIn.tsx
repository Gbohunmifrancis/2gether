import { getRouteApi, useNavigate } from "@tanstack/react-router";
import QRCode from "qrcode";
import { useEffect, useState, type FormEvent } from "react";
import { api, ApiError } from "../api/client";
import type { AdminSession, Enrollment } from "../api/types";
import { setSession } from "../auth/session";
import { Mark, Mascot } from "../components/Art";
import { errorMessage, Notice } from "../components/ui";

const route = getRouteApi("/sign-in");
const DEMO = import.meta.env.VITE_DEMO === "1";

type Step = { kind: "password" } | { kind: "code" } | { kind: "enroll"; secret: string; uri: string };

/** Splits a base32 secret into groups of four so it can be typed into an authenticator by hand. */
export const groupSecret = (secret: string) => secret.replace(/(.{4})(?=.)/g, "$1 ");

export function SignInPage() {
  const { redirect } = route.useSearch();
  const navigate = useNavigate();
  const [step, setStep] = useState<Step>({ kind: "password" });
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [qr, setQr] = useState<string | null>(null);

  useEffect(() => {
    if (step.kind !== "enroll") return;
    let live = true;
    QRCode.toDataURL(step.uri, { margin: 0, width: 376, errorCorrectionLevel: "M", color: { dark: "#161A3D", light: "#FFFFFF" } })
      .then((url) => live && setQr(url))
      .catch(() => live && setQr(null));
    return () => {
      live = false;
    };
  }, [step]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const session = await api<AdminSession>("/api/admin/auth/login", {
        method: "POST",
        anonymous: true,
        body: { email: email.trim(), password, code: step.kind === "password" ? undefined : code.replace(/\s/g, "") },
      });
      setPassword("");
      setSession(session);
      await navigate({ href: redirect ?? "/", replace: true });
    } catch (failure) {
      if (failure instanceof ApiError && failure.code === "admin.enrollment_required") {
        const body = failure.body as Enrollment;
        setStep({ kind: "enroll", secret: body.secret, uri: body.otpauthUri });
      } else if (failure instanceof ApiError && failure.code === "admin.code_required") {
        setStep({ kind: "code" });
      } else {
        if (failure instanceof ApiError && failure.code === "admin.invalid_credentials") setStep({ kind: "password" });
        setError(errorMessage(failure));
      }
      setCode("");
    } finally {
      setBusy(false);
    }
  }

  const back = () => {
    setStep({ kind: "password" });
    setCode("");
    setError(null);
  };

  /** Demo mode: the pretend API takes any email, password and code. */
  async function demo() {
    setBusy(true);
    try {
      const session = await api<AdminSession>("/api/admin/auth/login", {
        method: "POST",
        anonymous: true,
        body: { email: "ada@heartpact.app", password: "demo", code: "123456" },
      });
      setSession(session);
      await navigate({ href: redirect ?? "/", replace: true });
    } catch (failure) {
      setError(errorMessage(failure));
      setBusy(false);
    }
  }

  return (
    <div className="auth">
      <main className="card">
        <div className="auth-mascot">
          <Mascot mood={step.kind === "password" ? "hello" : error ? "worried" : "idle"} size={64} />
        </div>
        <div className="brand" style={{ padding: 0 }}>
          <Mark />
          <div>
            <b>heartpact</b>
            <span>Admin</span>
          </div>
        </div>

        {step.kind === "password" ? (
          <>
            <h1 className="headline" style={{ marginTop: 26 }}>
              Sign in
            </h1>
            <p className="muted" style={{ marginTop: 6 }}>
              For the people who run Heartpact. You'll need your authenticator app too.
            </p>
            <form onSubmit={submit} noValidate={false}>
              <label className="field">
                <span>Email</span>
                <input
                  className="input"
                  type="email"
                  autoComplete="username"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  required
                  autoFocus
                />
              </label>
              <label className="field">
                <span>Password</span>
                <input
                  className="input"
                  type="password"
                  autoComplete="current-password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  required
                />
              </label>
              {error ? <Notice tone="bad">{error}</Notice> : null}
              <button className="btn filled block" type="submit" disabled={busy || !email || !password}>
                {busy ? "Checking…" : "Continue"}
              </button>
              {DEMO ? (
                <>
                  <button className="btn outline block" type="button" onClick={() => void demo()} disabled={busy}>
                    Try the demo
                  </button>
                  <p className="small faint" style={{ textAlign: "center" }}>
                    Demo mode: made-up data, no server. Any email, password and six-digit code works (000000 never does).
                  </p>
                </>
              ) : null}
            </form>
          </>
        ) : (
          <>
            <h1 className="headline" style={{ marginTop: 26 }}>
              {step.kind === "enroll" ? "Set up your authenticator" : "Enter your code"}
            </h1>
            <p className="muted" style={{ marginTop: 6 }}>
              {step.kind === "enroll"
                ? "Scan this with Google Authenticator, 1Password or similar. Then enter the six-digit code it shows."
                : `The six-digit code from your authenticator app for ${email.trim()}.`}
            </p>
            <form onSubmit={submit}>
              {step.kind === "enroll" ? (
                <>
                  <div className="qr">{qr ? <img src={qr} alt="QR code for your authenticator app" /> : <div style={{ width: 188, height: 188 }} />}</div>
                  <div>
                    <div className="small faint" style={{ marginBottom: 6 }}>
                      Can't scan? Enter this key:
                    </div>
                    <div className="secret" aria-label="Authenticator key">
                      {groupSecret(step.secret)}
                    </div>
                  </div>
                </>
              ) : null}
              <label className="field">
                <span>Code</span>
                <input
                  className="input code"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  pattern="[0-9 ]*"
                  maxLength={7}
                  value={code}
                  onChange={(event) => setCode(event.target.value.replace(/[^0-9 ]/g, ""))}
                  required
                  autoFocus
                />
              </label>
              {error ? <Notice tone="bad">{error}</Notice> : null}
              <button className="btn filled block" type="submit" disabled={busy || code.replace(/\s/g, "").length !== 6}>
                {busy ? "Checking…" : step.kind === "enroll" ? "Finish set-up" : "Sign in"}
              </button>
              <button className="btn ghost" type="button" onClick={back}>
                Use a different account
              </button>
            </form>
          </>
        )}
      </main>
    </div>
  );
}
