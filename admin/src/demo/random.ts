/* Seeded randomness, so the demo looks the same on every load (and in every screenshot). */

export type Rng = () => number;

/** mulberry32: small, fast, good enough for fake data. */
export function rng(seed: number): Rng {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** A stable number for a string, to seed per-endpoint randomness. */
export function hash(text: string): number {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

export const seeded = (key: string) => rng(hash(key));

export const int = (random: Rng, min: number, max: number) => Math.floor(min + random() * (max - min + 1));

export const pick = <T>(random: Rng, items: readonly T[]): T => items[Math.floor(random() * items.length)]!;

/** Picks by weight: [[item, weight], ...]. */
export function weighted<T>(random: Rng, items: readonly (readonly [T, number])[]): T {
  const total = items.reduce((sum, [, weight]) => sum + weight, 0);
  let roll = random() * total;
  for (const [item, weight] of items) {
    roll -= weight;
    if (roll <= 0) return item;
  }
  return items[items.length - 1]![0];
}

/** Roughly normal noise around 0 (sum of uniforms). */
export const noise = (random: Rng) => (random() + random() + random() - 1.5) / 1.5;

/** A UUID-shaped id from a number, stable across loads. */
export function uuid(random: Rng): string {
  const hex = Array.from({ length: 32 }, () => Math.floor(random() * 16).toString(16)).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-a${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}
