import { createContext, useCallback, useContext, useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from "react";
import { errorMessage, Notice } from "./ui";

/* Toasts: one line, gone after a few seconds, announced politely. Live moments bring their own art and tone. */

export interface ToastOptions {
  tone?: "bad" | "good" | "warn" | "plus";
  art?: ReactNode;
  /** Small text under the line. */
  sub?: ReactNode;
  ms?: number;
}

interface Toast extends ToastOptions {
  id: number;
  text: ReactNode;
  leaving?: boolean;
}

type ShowToast = (text: ReactNode, bad?: boolean | ToastOptions) => void;

const ToastContext = createContext<ShowToast>(() => {});

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const next = useRef(0);
  const show = useCallback<ShowToast>((text, options) => {
    const id = ++next.current;
    const extra: ToastOptions = typeof options === "object" ? options : options ? { tone: "bad" } : {};
    setToasts((items) => [...items.slice(-3), { id, text, ...extra }]);
    const ms = extra.ms ?? 4500;
    setTimeout(() => setToasts((items) => items.map((item) => (item.id === id ? { ...item, leaving: true } : item))), ms);
    setTimeout(() => setToasts((items) => items.filter((item) => item.id !== id)), ms + 260);
  }, []);
  return (
    <ToastContext.Provider value={show}>
      {children}
      <div className="toasts" aria-live="polite">
        {toasts.map((toast) => (
          <div key={toast.id} className={`toast${toast.tone ? ` ${toast.tone}` : ""}${toast.leaving ? " leaving" : ""}${toast.art ? " has-art" : ""}`}>
            {toast.art ? <span className="toast-art">{toast.art}</span> : null}
            <span>
              {toast.text}
              {toast.sub ? <small>{toast.sub}</small> : null}
            </span>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export const useToast = () => useContext(ToastContext);

/* Confirm sheets. Every action that changes an account or money goes through one, and the API
   wants a reason for most of them, which ends up in the audit log. */

export interface ConfirmOptions {
  title: string;
  body?: ReactNode;
  confirmLabel: string;
  danger?: boolean;
  /** Ask for a reason (required). */
  reason?: boolean | string;
  /** Ask for a whole number of days (1–366). */
  days?: boolean;
  /** The person must type this exactly before the button works (account deletion). */
  typeToConfirm?: string;
  /** Runs the action. Throw to keep the sheet open and show the error. */
  run: (input: { reason: string; days: number; typed: string }) => Promise<unknown>;
}

export function ConfirmDialog({ options, onClose }: { options: ConfirmOptions | null; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  const [reason, setReason] = useState("");
  const [days, setDays] = useState("30");
  const [typed, setTyped] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ids = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (options) {
      setReason("");
      setDays("30");
      setTyped("");
      setError(null);
      setBusy(false);
      if (!dialog.open) dialog.showModal?.();
    } else if (dialog.open) {
      dialog.close();
    }
  }, [options]);

  if (!options) return <dialog ref={ref} className="sheet" />;

  const dayCount = Number.parseInt(days, 10);
  const daysOk = !options.days || (Number.isInteger(dayCount) && dayCount >= 1 && dayCount <= 366);
  const reasonOk = !options.reason || reason.trim().length > 0;
  const typedOk = !options.typeToConfirm || typed.trim().toLowerCase() === options.typeToConfirm.toLowerCase();
  const ready = daysOk && reasonOk && typedOk && !busy;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!ready || !options) return;
    setBusy(true);
    setError(null);
    try {
      await options.run({ reason: reason.trim(), days: dayCount, typed: typed.trim() });
      onClose();
    } catch (failure) {
      setError(errorMessage(failure));
      setBusy(false);
    }
  }

  return (
    <dialog ref={ref} className="sheet" aria-labelledby={`${ids}-title`} onCancel={onClose}>
      <form onSubmit={submit}>
        <h2 id={`${ids}-title`} className="title">
          {options.title}
        </h2>
        {options.body ? <div className="muted">{options.body}</div> : null}
        {options.days ? (
          <label className="field">
            <span>Days of Plus</span>
            <input className="input" type="number" min={1} max={366} value={days} onChange={(event) => setDays(event.target.value)} required />
          </label>
        ) : null}
        {options.reason ? (
          <label className="field">
            <span>{typeof options.reason === "string" ? options.reason : "Reason (kept in the audit log)"}</span>
            <textarea className="textarea" value={reason} maxLength={500} onChange={(event) => setReason(event.target.value)} required autoFocus />
          </label>
        ) : null}
        {options.typeToConfirm ? (
          <label className="field">
            <span>
              Type <b>{options.typeToConfirm}</b> to confirm
            </span>
            <input className="input" value={typed} onChange={(event) => setTyped(event.target.value)} autoComplete="off" spellCheck={false} />
          </label>
        ) : null}
        {error ? <Notice tone="bad">{error}</Notice> : null}
        <div className="actions">
          <button type="button" className="btn ghost" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button type="submit" className={`btn ${options.danger ? "danger" : "filled"}`} disabled={!ready}>
            {busy ? "Working…" : options.confirmLabel}
          </button>
        </div>
      </form>
    </dialog>
  );
}

/** One confirm sheet per page: `ask({...})` opens it. */
export function useConfirm() {
  const [options, setOptions] = useState<ConfirmOptions | null>(null);
  const dialog = <ConfirmDialog options={options} onClose={() => setOptions(null)} />;
  return [setOptions as (options: ConfirmOptions) => void, dialog] as const;
}
