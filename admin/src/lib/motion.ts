import { useEffect, useRef, useState, useSyncExternalStore } from "react";

const QUERY = "(prefers-reduced-motion: reduce)";

function subscribe(listener: () => void) {
  const media = typeof matchMedia === "function" ? matchMedia(QUERY) : null;
  media?.addEventListener?.("change", listener);
  return () => media?.removeEventListener?.("change", listener);
}

export const prefersReducedMotion = () => (typeof matchMedia === "function" ? matchMedia(QUERY).matches : false);

/** True when the admin asked their system for less motion: every animation then shows its end state. */
export function useReducedMotion(): boolean {
  return useSyncExternalStore(subscribe, prefersReducedMotion, () => false);
}

const easeOutCubic = (p: number) => 1 - (1 - p) ** 3;

/** Counts up from the last value shown to `target` over `ms`; jumps straight there with reduced motion. */
export function useCountUp(target: number, ms = 900): number {
  const reduced = useReducedMotion();
  const [value, setValue] = useState(reduced ? target : 0);
  const from = useRef(reduced ? target : 0);
  useEffect(() => {
    if (reduced || typeof requestAnimationFrame !== "function") {
      from.current = target;
      setValue(target);
      return;
    }
    const start = performance.now();
    const origin = from.current;
    let frame = 0;
    const step = (now: number) => {
      const p = Math.min(1, (now - start) / ms);
      const next = origin + (target - origin) * easeOutCubic(p);
      setValue(next);
      from.current = next;
      if (p < 1) frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [target, ms, reduced]);
  return value;
}

/** A number that bumps each time `key` changes, to restart a CSS animation (key={...}). */
export function useBump(key: unknown): number {
  const [count, setCount] = useState(0);
  const first = useRef(true);
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    setCount((value) => value + 1);
  }, [key]);
  return count;
}
