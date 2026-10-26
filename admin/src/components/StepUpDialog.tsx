import { useEffect, useRef, useState, type FormEvent } from "react";
import { setStepUpPrompt } from "../api/client";
import { Lock } from "./Art";
import { Notice } from "./ui";

type Phase = "closed" | "asking" | "checking" | "open";

/**
 * Asks for a fresh authenticator code when the API wants one for a destructive action, then lets the request go
 * again with it. The padlock springs open when the code works and shakes when it doesn't.
 */
export function StepUpDialog() {
  const ref = useRef<HTMLDialogElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const [phase, setPhase] = useState<Phase>("closed");
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [shake, setShake] = useState(0);
  const resolver = useRef<((code: string | null) => void) | null>(null);

  useEffect(
    () =>
      setStepUpPrompt({
        ask: ({ invalid, message }) =>
          new Promise<string | null>((resolve) => {
            resolver.current = resolve;
            setCode("");
            setError(invalid ? message : null);
            if (invalid) setShake((value) => value + 1);
            setPhase("asking");
          }),
        settle: (ok) => {
          if (!ok) {
            setPhase("closed");
            return;
          }
          setPhase("open");
          setTimeout(() => setPhase((current) => (current === "open" ? "closed" : current)), 750);
        },
      }),
    [],
  );

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (phase === "closed") {
      if (dialog.open) dialog.close();
    } else if (!dialog.open) {
      dialog.showModal?.();
    }
    if (phase === "asking") setTimeout(() => input.current?.focus(), 30);
  }, [phase]);

  const cancel = () => {
    resolver.current?.(null);
    resolver.current = null;
    setPhase("closed");
  };

  function submit(event?: FormEvent) {
    event?.preventDefault();
    const digits = code.replace(/\D/g, "");
    if (digits.length !== 6 || phase !== "asking") return;
    setPhase("checking");
    resolver.current?.(digits);
    resolver.current = null;
  }

  return (
    <dialog
      ref={ref}
      className="sheet step-up"
      aria-labelledby="step-up-title"
      onCancel={(event) => {
        event.preventDefault();
        if (phase === "asking") cancel();
      }}
    >
      <form onSubmit={submit}>
        <div className="step-up-art">
          <Lock open={phase === "open"} shake={shake} />
        </div>
        <div style={{ textAlign: "center" }}>
          <h2 id="step-up-title" className="title">
            {phase === "open" ? "Unlocked" : "Confirm it's you"}
          </h2>
          <p className="muted" style={{ marginTop: 6 }}>
            {phase === "open" ? "Carrying on…" : "This changes an account or money, so it needs a fresh six-digit code from your authenticator app."}
          </p>
        </div>
        <label className="field">
          <span className="visually-hidden">Authenticator code</span>
          <input
            ref={input}
            className="input code"
            inputMode="numeric"
            autoComplete="one-time-code"
            aria-label="Authenticator code"
            maxLength={7}
            value={code}
            disabled={phase !== "asking"}
            onChange={(event) => {
              const next = event.target.value.replace(/[^0-9 ]/g, "");
              setCode(next);
              setError(null);
              // Six digits is the whole code: go without a click.
              if (next.replace(/\D/g, "").length === 6)
                setTimeout(() => (document.activeElement === input.current ? input.current?.form?.requestSubmit() : null), 60);
            }}
          />
        </label>
        {error ? <Notice tone="bad">{error}</Notice> : null}
        <div className="actions">
          <button type="button" className="btn ghost" onClick={cancel} disabled={phase !== "asking"}>
            Cancel
          </button>
          <button type="submit" className="btn filled" disabled={phase !== "asking" || code.replace(/\D/g, "").length !== 6}>
            {phase === "checking" ? "Checking…" : phase === "open" ? "Done" : "Confirm"}
          </button>
        </div>
      </form>
    </dialog>
  );
}
