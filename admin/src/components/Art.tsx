import { useId, type CSSProperties } from "react";

/* Heartpact's art, drawn as SVG: the folded heart and its halves (marketing/play-store/logo), the live mark with a
   face (LiveLogo.kt) and the two characters, Maya and Theo (design/heartpact-v3/mascots.js). */

/** The heart's halves in its 100-unit space, tip at 50,100 (HeartGeometry.kt). */
export const LEFT_HALF = "M50 100 L7.57 57.57 A30 30 0 0 1 50 15.15 Z";
export const RIGHT_HALF = "M50 100 L92.43 57.57 A30 30 0 0 0 50 15.15 Z";
export const WHOLE = "M50 100 L7.57 57.57 A30 30 0 0 1 50 15.15 A30 30 0 0 1 92.43 57.57 Z";
export const TICK = "M28 56 L44 72 L74 38";
const MARK_BOX = "-2 4.4 104 97.6";

function TickMask({ id }: { id: string }) {
  return (
    <mask id={id} maskUnits="userSpaceOnUse" x="-10" y="-10" width="130" height="130">
      <rect x="-10" y="-10" width="130" height="130" fill="#fff" />
      <path d={TICK} fill="none" stroke="#000" strokeWidth="9.5" strokeLinecap="round" strokeLinejoin="round" />
    </mask>
  );
}

/** The Heartpact mark: the heart folded in two, indigo and periwinkle, with the tick cut out. */
export function Mark({ className, style }: { className?: string; style?: CSSProperties }) {
  const id = useId().replace(/:/g, "");
  return (
    <svg viewBox={MARK_BOX} aria-hidden="true" className={className} style={style}>
      <defs>
        <TickMask id={`t${id}`} />
      </defs>
      <g mask={`url(#t${id})`}>
        <path d={LEFT_HALF} fill="var(--art-left)" />
        <path d={RIGHT_HALF} fill="var(--art-right)" />
      </g>
    </svg>
  );
}

/**
 * The two halves as separate pieces, for the "couple linked" moment: each flies in from its side and they meet.
 * `className` drives the motion (see .halves in styles.css).
 */
export function Halves({ className, size = 44 }: { className?: string; size?: number }) {
  return (
    <svg viewBox="-30 0 160 104" width={size * 1.5} height={size} aria-hidden="true" className={`halves ${className ?? ""}`}>
      <path className="half-l" d={LEFT_HALF} fill="var(--art-left)" />
      <path className="half-r" d={RIGHT_HALF} fill="var(--art-right)" />
    </svg>
  );
}

/** A small whole heart, for counts (hearts made). `half` shows only one side filled. */
export function MiniHeart({ size = 16, tone = "both", className }: { size?: number; tone?: "both" | "left" | "right" | "empty" | "pink"; className?: string }) {
  return (
    <svg viewBox="2 10 96 92" width={size} height={size} aria-hidden="true" className={className}>
      {tone === "pink" ? (
        <path d={WHOLE} fill="var(--pen)" />
      ) : tone === "empty" ? (
        <path d={WHOLE} fill="none" stroke="var(--ink-4)" strokeWidth="7" strokeDasharray="10 8" />
      ) : (
        <>
          <path d={LEFT_HALF} fill={tone === "right" ? "var(--page-3)" : "var(--art-left)"} />
          <path d={RIGHT_HALF} fill={tone === "left" ? "var(--page-3)" : "var(--art-right)"} />
        </>
      )}
    </svg>
  );
}

/* ---- The live mark ------------------------------------------------------------------------------------------- */

export type MascotMood = "idle" | "hello" | "cheer" | "worried" | "sleepy";

/**
 * The mark, alive: eyes that blink, a breath, and now and then a hop or a clap of its halves (LiveLogo.kt's moods).
 * Changing `beat` makes it cheer once (a hop, a flip and a puff of hearts). Still with reduced motion.
 */
export function Mascot({ mood = "idle", size = 44, beat = 0, title }: { mood?: MascotMood; size?: number; beat?: number; title?: string }) {
  const id = useId().replace(/:/g, "");
  const shut = mood === "sleepy";
  const happy = mood === "cheer";
  const eye = (x: number, ink: string, catchlight: string) =>
    shut || happy ? (
      <path
        className="eye"
        d={shut ? `M${x - 5.5} 24 q5.5 6 11 0` : `M${x - 5.5} 27.5 q5.5 -8.5 11 0`}
        stroke={ink}
        strokeWidth="3.2"
        strokeLinecap="round"
        fill="none"
      />
    ) : (
      <g className="eye">
        <ellipse cx={x} cy="25" rx={mood === "worried" ? 3.3 : 3.9} ry={mood === "worried" ? 4.4 : 5.2} fill={ink} />
        <circle cx={x + 1.3} cy="23" r="1.5" fill={catchlight} />
      </g>
    );
  const face = (side: "l" | "r") => (
    <>
      {side === "l" ? eye(30, "var(--art-left-ink)", "var(--art-left-catch)") : eye(70, "#161A3D", "#fff")}
      {mood !== "worried" ? <ellipse cx={side === "l" ? 18 : 82} cy="32.2" rx="4" ry="2.25" fill="#E58AA8" opacity="0.6" /> : null}
      {mood === "worried" ? (
        <path
          d={side === "l" ? "M24 17 l8.5 -2.5" : "M76 17 l-8.5 -2.5"}
          stroke={side === "l" ? "var(--art-left-ink)" : "#161A3D"}
          strokeWidth="2.8"
          strokeLinecap="round"
        />
      ) : null}
    </>
  );
  return (
    <svg
      viewBox="-30 -48 160 152"
      width={size * 1.54}
      height={size * 1.46}
      className={`mascot mood-${mood}`}
      role={title ? "img" : undefined}
      aria-label={title}
      aria-hidden={title ? undefined : true}
      style={{ margin: `${-size * 0.48}px ${-size * 0.27}px ${-size * 0.02}px` }}
    >
      <defs>
        <TickMask id={`m${id}`} />
      </defs>
      <g key={beat} className={beat ? "cheer-once" : undefined}>
        <g className="hop">
          <g className="spin">
            <g className="breathe">
              <g className="half half-l">
                <path d={LEFT_HALF} fill="var(--art-left)" mask={`url(#m${id})`} />
                {face("l")}
              </g>
              <g className="half half-r">
                <path d={RIGHT_HALF} fill="var(--art-right)" mask={`url(#m${id})`} />
                {face("r")}
              </g>
            </g>
          </g>
        </g>
        <g className="puff" aria-hidden="true">
          {[-2, -1, 0, 1, 2].map((spread, index) => (
            <path
              key={spread}
              d="M0 4 C-6.5 -0.5 -5 -6.2 0 -2.6 C5 -6.2 6.5 -0.5 0 4 Z"
              fill={["#FF8FB1", "var(--art-right)", "#FF5C8A", "#E58AA8", "#FF8FB1"][index]}
              style={
                { "--dx": `${spread * 22}px`, "--r": `${spread * 12}deg`, "--s": index % 2 ? 1.3 : 1.7, animationDelay: `${index * 40}ms` } as CSSProperties
              }
              transform="translate(50 10)"
            />
          ))}
        </g>
      </g>
      {shut ? (
        <g className="zzz" fill="none" stroke="var(--art-right)" strokeWidth="1.8" strokeLinecap="round">
          {[0, 1, 2].map((index) => (
            <path key={index} d="M0 0h6l-6 6h6" style={{ animationDelay: `${index * 1}s` }} transform={`translate(${88 + index * 4} ${-2 - index * 2})`} />
          ))}
        </g>
      ) : null}
    </svg>
  );
}

/* ---- Maya and Theo ------------------------------------------------------------------------------------------- */

export type Who = "maya" | "theo";
export type Pose = "stand" | "wave" | "cheer" | "hug" | "rest";
export type Mood = "smile" | "cheer" | "worried" | "sleep";

const INK = "#161A3D";
const COLORS = {
  maya: { body: "#262D66", shade: "#1A1F4D", blush: "#E58AA8", face: "#FFFFFF" },
  theo: { body: "#7480D4", shade: "#5F6BC6", blush: "#F2A3BA", face: INK },
} as const;
const HALF = "M50 22 C 46 10 37 2 26 2 C 11 2 0 15 0 33 C 0 60 25 80 50 106 Z";
const POSES: Record<Pose, [string, string, string, string]> = {
  stand: ["M8 56 q-8 10 -4 22", "M40 66 q8 8 10 18", "M30 86 l-4 22", "M42 96 l2 14"],
  wave: ["M8 52 q-14 -8 -12 -26", "M40 66 q8 8 10 18", "M30 86 l-4 22", "M42 96 l2 14"],
  cheer: ["M8 46 q-12 -14 -6 -32", "M44 30 q10 -14 6 -30", "M30 86 l-8 20", "M42 96 l6 14"],
  hug: ["M8 56 q-8 10 -4 22", "M40 62 q14 2 26 -4", "M30 86 l-4 22", "M42 96 l2 14"],
  rest: ["M8 56 q-10 4 -10 14", "M40 66 q6 6 6 12", "M30 86 q-6 10 -16 12", "M42 96 q-2 8 -10 12"],
};

function end(path: string, x0: number, y0: number): [number, number] {
  const numbers = (path.match(/-?\d+(\.\d+)?/g) ?? []).map(Number);
  return [x0 + numbers[numbers.length - 2]!, y0 + numbers[numbers.length - 1]!];
}

function Face({ mood, mirror, ink }: { mood: Mood; mirror: boolean; ink: string }) {
  const [e1, e2] = mirror ? [62, 76] : [24, 38];
  const mx = (e1 + e2) / 2;
  const catchlight = ink === INK ? "#fff" : "#262D66";
  return (
    <g className="face">
      {mood === "sleep" ? (
        <path d={`M${e1 - 4} 42 q4 4 8 0 M${e2 - 4} 42 q4 4 8 0`} stroke={ink} strokeWidth="2.6" strokeLinecap="round" fill="none" />
      ) : (
        <g className="eyes">
          <ellipse cx={e1} cy="42" rx={mood === "worried" ? 3 : 3.2} ry={mood === "worried" ? 4 : 4.2} fill={ink} />
          <ellipse cx={e2} cy="42" rx={mood === "worried" ? 3 : 3.2} ry={mood === "worried" ? 4 : 4.2} fill={ink} />
          {mood !== "worried" ? (
            <>
              <circle cx={e1 + 1} cy="40.5" r="1.1" fill={catchlight} />
              <circle cx={e2 + 1} cy="40.5" r="1.1" fill={catchlight} />
            </>
          ) : (
            <path d={`M${e1 - 4} 34 l6 -2 M${e2 + 4} 34 l-6 -2`} stroke={ink} strokeWidth="2.2" strokeLinecap="round" />
          )}
        </g>
      )}
      {mood === "worried" ? (
        <path d={`M${mx - 5} 56 q5 -4 10 0`} stroke={ink} strokeWidth="2.6" strokeLinecap="round" fill="none" />
      ) : mood === "cheer" ? (
        <>
          <path d={`M${mx - 6} 52 q6 9 12 0 z`} fill={ink} />
          <path d={`M${mx - 3} 56 q3 2 6 0`} stroke="#F06A6A" strokeWidth="2" strokeLinecap="round" fill="none" />
        </>
      ) : (
        <path d={`M${mx - 5} 53 q5 5 10 0`} stroke={ink} strokeWidth="2.6" strokeLinecap="round" fill="none" />
      )}
    </g>
  );
}

function CharacterHalf({ who, pose, mood, mirror }: { who: Who; pose: Pose; mood: Mood; mirror: boolean }) {
  const colors = COLORS[who];
  const [a1, a2, l1, l2] = POSES[pose];
  const [f1x, f1y] = end(l1, 30, 86);
  const [f2x, f2y] = end(l2, 42, 96);
  return (
    <g className={`character ${who}`}>
      <g transform={mirror ? "translate(100 0) scale(-1 1)" : undefined}>
        <g className="limbs" stroke={INK} strokeWidth="4.2" strokeLinecap="round" fill="none">
          <path className="arm arm-1" d={a1} />
          <path className="arm arm-2" d={a2} />
          <path d={l1} />
          <path d={l2} />
        </g>
        <ellipse cx={f1x - 1} cy={f1y + 2} rx="6.5" ry="4" fill={INK} />
        <ellipse cx={f2x + 1} cy={f2y + 2} rx="6.5" ry="4" fill={INK} />
        <path d={HALF} fill={colors.body} />
        <path d="M50 20 C 46 10 38 4 29 4 C 22 4 16 7 12 12 C 20 9 34 10 42 24 C 46 30 48 34 50 36 Z" fill="#fff" opacity=".14" />
        <path d="M50 104 C 40 93 30 83 22 72 C 32 80 42 86 50 88 Z" fill={colors.shade} />
        <path d="M46.5 26 L46.5 98" stroke="#fff" strokeWidth="2.4" strokeDasharray="5 5" strokeLinecap="round" opacity=".6" />
        <ellipse cx="18" cy="52" rx="5" ry="3.2" fill={colors.blush} opacity=".75" />
        <ellipse cx="44" cy="52" rx="4" ry="3" fill={colors.blush} opacity=".75" />
      </g>
      <Face mood={mood} mirror={mirror} ink={colors.face} />
    </g>
  );
}

/**
 * Maya (the indigo half, white face) and Theo (the periwinkle half, ink face), each a half heart with arms and legs;
 * `who="both"` stands them together as the whole heart, `gap` apart. A white die-cut keyline makes them stickers.
 */
export function Characters({
  who,
  pose = "stand",
  mood,
  size = 120,
  gap = 0,
  sticker = true,
  className,
  title,
}: {
  who: Who | "both";
  pose?: Pose;
  mood?: Mood;
  size?: number;
  gap?: number;
  sticker?: boolean;
  className?: string;
  title?: string;
}) {
  const id = useId().replace(/:/g, "");
  const face = mood ?? (pose === "cheer" ? "cheer" : "smile");
  const width = who === "both" ? 100 + gap : 100;
  return (
    <svg
      viewBox={`-22 -36 ${width + 44} 166`}
      width={(size * (width + 44)) / 144}
      height={(size * 166) / 144}
      className={`characters ${className ?? ""}`}
      role={title ? "img" : undefined}
      aria-label={title}
      aria-hidden={title ? undefined : true}
      overflow="visible"
    >
      {sticker ? (
        <defs>
          <filter id={`k${id}`} filterUnits="userSpaceOnUse" x="-40" y="-60" width={width + 80} height="230">
            <feMorphology in="SourceAlpha" operator="dilate" radius="6" result="d" />
            <feFlood floodColor="#fff" />
            <feComposite in2="d" operator="in" result="w" />
            <feGaussianBlur in="d" stdDeviation="5" result="b" />
            <feOffset in="b" dy="5" result="o" />
            <feFlood floodColor="#1E2350" floodOpacity=".24" />
            <feComposite in2="o" operator="in" result="s" />
            <feMerge>
              <feMergeNode in="s" />
              <feMergeNode in="w" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>
      ) : null}
      <g filter={sticker ? `url(#k${id})` : undefined}>
        {who === "both" ? (
          <>
            <g className="who-maya">
              <CharacterHalf who="maya" pose={pose} mood={face} mirror={false} />
            </g>
            <g className="who-theo" transform={`translate(${gap} 0)`}>
              <CharacterHalf who="theo" pose={pose} mood={face} mirror />
            </g>
          </>
        ) : (
          <CharacterHalf who={who} pose={pose} mood={face} mirror={who === "theo"} />
        )}
      </g>
    </svg>
  );
}

/** A padlock with a heart keyhole; `open` springs the shackle up (the step-up dialog). */
export function Lock({ open, shake }: { open: boolean; shake?: number }) {
  return (
    <svg viewBox="0 0 80 92" width="72" height="83" className={`lock${open ? " open" : ""}`} aria-hidden="true" key={shake}>
      <g className={shake ? "lock-shake" : undefined}>
        <path className="shackle" d="M22 42V28a18 18 0 0 1 36 0v14" fill="none" stroke="var(--ink-3)" strokeWidth="8" strokeLinecap="round" />
        <rect x="8" y="40" width="64" height="48" rx="16" fill="#262D66" />
        <rect x="8" y="40" width="64" height="12" rx="6" fill="#fff" opacity=".08" />
        <g transform="translate(26 50) scale(0.28)">
          <path d={LEFT_HALF} fill="#fff" opacity=".95" />
          <path d={RIGHT_HALF} fill="var(--art-right)" />
        </g>
      </g>
    </svg>
  );
}
