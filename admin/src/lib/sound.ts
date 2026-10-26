import { useSyncExternalStore } from "react";

/* Little sounds for the live feed, made with Web Audio (no files): a two-note chime when a couple links, a tick for a
   dare done, a warm heart chime for Plus and a low tone for alerts. Off until the admin turns them on; remembered. */

export type SoundKind = "chime" | "tick" | "kaching" | "alert";

const KEY = "heartpact.admin.sound";
const listeners = new Set<() => void>();
let enabled = read();
let context: AudioContext | null = null;

function read(): boolean {
  try {
    return localStorage.getItem(KEY) === "on";
  } catch {
    return false;
  }
}

export const soundEnabled = () => enabled;

export function setSoundEnabled(on: boolean) {
  enabled = on;
  try {
    localStorage.setItem(KEY, on ? "on" : "off");
  } catch {
    // Storage blocked: on for this page only.
  }
  // Turning sound on is a click, so the audio context may start now (browsers block it before a gesture).
  if (on) ensureContext();
  listeners.forEach((listener) => listener());
}

export function useSoundEnabled(): boolean {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    soundEnabled,
    soundEnabled,
  );
}

function ensureContext(): AudioContext | null {
  if (context) {
    if (context.state === "suspended") void context.resume();
    return context;
  }
  const Ctor = globalThis.AudioContext ?? (globalThis as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!Ctor) return null;
  try {
    context = new Ctor();
  } catch {
    context = null;
  }
  return context;
}

interface Note {
  /** Hz. */
  frequency: number;
  /** Seconds after the sound starts. */
  at: number;
  length: number;
  type?: OscillatorType;
  gain?: number;
  /** Slide to this frequency over the note. */
  glideTo?: number;
}

/** The notes of each sound: soft sine bells, quick attack, long gentle release. */
export const SOUNDS: Record<SoundKind, Note[]> = {
  // E5 then A5: a doorbell-ish "together".
  chime: [
    { frequency: 659.25, at: 0, length: 0.5, gain: 0.22 },
    { frequency: 880, at: 0.16, length: 0.8, gain: 0.2 },
    { frequency: 1760, at: 0.16, length: 0.4, gain: 0.03 },
  ],
  // A short wooden tick.
  tick: [
    { frequency: 1900, at: 0, length: 0.05, type: "triangle", gain: 0.16 },
    { frequency: 2600, at: 0.045, length: 0.04, type: "triangle", gain: 0.08 },
  ],
  // A warm "ka-ching": a soft click, then a bright arpeggio over a bell.
  kaching: [
    { frequency: 2400, at: 0, length: 0.04, type: "square", gain: 0.04 },
    { frequency: 1046.5, at: 0.05, length: 0.35, gain: 0.16 },
    { frequency: 1318.5, at: 0.11, length: 0.35, gain: 0.15 },
    { frequency: 1568, at: 0.17, length: 0.9, gain: 0.16 },
    { frequency: 2093, at: 0.17, length: 0.7, gain: 0.05 },
    { frequency: 523.25, at: 0.17, length: 1.1, gain: 0.08 },
  ],
  // Low and round, slipping down a little.
  alert: [
    { frequency: 233, at: 0, length: 0.55, type: "triangle", gain: 0.26, glideTo: 196 },
    { frequency: 196, at: 0.32, length: 0.7, type: "triangle", gain: 0.22, glideTo: 174.6 },
  ],
};

export function playSound(kind: SoundKind) {
  if (!enabled) return;
  const audio = ensureContext();
  if (!audio || audio.state === "closed") return;
  const start = audio.currentTime + 0.02;
  const master = audio.createGain();
  master.gain.value = 0.9;
  master.connect(audio.destination);
  for (const note of SOUNDS[kind]) {
    const osc = audio.createOscillator();
    const gain = audio.createGain();
    osc.type = note.type ?? "sine";
    osc.frequency.setValueAtTime(note.frequency, start + note.at);
    if (note.glideTo) osc.frequency.exponentialRampToValueAtTime(note.glideTo, start + note.at + note.length);
    const peak = note.gain ?? 0.15;
    gain.gain.setValueAtTime(0.0001, start + note.at);
    gain.gain.exponentialRampToValueAtTime(peak, start + note.at + 0.012);
    gain.gain.exponentialRampToValueAtTime(0.0001, start + note.at + note.length);
    osc.connect(gain).connect(master);
    osc.start(start + note.at);
    osc.stop(start + note.at + note.length + 0.05);
  }
}
