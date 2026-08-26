export type SoundKind = "connect" | "message" | "celebrate" | "countdown" | "together" | "private";

let audioContext: AudioContext | null = null;

function context(): AudioContext | null {
  if (typeof window === "undefined") return null;
  audioContext ??= new AudioContext();
  if (audioContext.state === "suspended") void audioContext.resume();
  return audioContext;
}

function note(ctx: AudioContext, frequency: number, start: number, duration: number, volume: number, type: OscillatorType = "sine") {
  const oscillator = ctx.createOscillator();
  const gain = ctx.createGain();
  oscillator.type = type;
  oscillator.frequency.setValueAtTime(frequency, start);
  gain.gain.setValueAtTime(0.0001, start);
  gain.gain.exponentialRampToValueAtTime(volume, start + 0.025);
  gain.gain.exponentialRampToValueAtTime(0.0001, start + duration);
  oscillator.connect(gain).connect(ctx.destination);
  oscillator.start(start);
  oscillator.stop(start + duration + 0.02);
}

export function unlockSounds() { context(); }

export function playSound(kind: SoundKind) {
  const ctx = context();
  if (!ctx) return;
  const now = ctx.currentTime + 0.01;
  const patterns: Record<SoundKind, Array<[number, number, number, number]>> = {
    connect: [[523, 0, .35, .045], [659, .14, .42, .035]],
    message: [[784, 0, .22, .035], [988, .09, .28, .025]],
    celebrate: [[523, 0, .38, .04], [659, .1, .42, .04], [784, .2, .55, .045], [1047, .34, .65, .035]],
    countdown: [[440, 0, .18, .035]],
    together: [[392, 0, .55, .035], [523, .12, .6, .04], [659, .25, .75, .035]],
    private: [[330, 0, .3, .025], [494, .12, .45, .025]],
  };
  patterns[kind].forEach(([frequency, delay, duration, volume]) => note(ctx, frequency, now + delay, duration, volume));
}
