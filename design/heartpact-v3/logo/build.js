// Logo exploration: four directions for the Heartpact mark, wordmark and app icon.
// Usage: node build.js  ->  explore.png
const fs = require('fs'), path = require('path');
const { chromium } = require('/opt/node-tools/node_modules/playwright');

// Geometric heart: a square rotated 45deg plus two semicircle lobes, split on its vertical diagonal.
const S = 56 / Math.SQRT2, R = 28, TX = 50, TY = 92;
const f = n => +n.toFixed(2);
const LEFT = `M${TX} ${TY} L${f(TX - S)} ${f(TY - S)} A${R} ${R} 0 0 1 ${TX} ${f(TY - 2 * S)} Z`;
const RIGHT = `M${TX} ${TY} L${f(TX + S)} ${f(TY - S)} A${R} ${R} 0 0 0 ${TX} ${f(TY - 2 * S)} Z`;
const WHOLE = `M${TX} ${TY} L${f(TX - S)} ${f(TY - S)} A${R} ${R} 0 0 1 ${TX} ${f(TY - 2 * S)} A${R} ${R} 0 0 1 ${f(TX + S)} ${f(TY - S)} Z`;
let uid = 0;

const MARKS = {
  // A: two halves held apart by a hairline seam, lit from above.
  seam: (c) => { const id = 'g' + (++uid); return `
    <defs><linearGradient id="${id}l" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${c.l1}"/><stop offset="1" stop-color="${c.l2}"/></linearGradient>
    <linearGradient id="${id}r" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${c.r1}"/><stop offset="1" stop-color="${c.r2}"/></linearGradient></defs>
    <path d="${LEFT}" fill="url(#${id}l)" transform="translate(-2.2 0)"/><path d="${RIGHT}" fill="url(#${id}r)" transform="translate(2.2 0)"/>`; },
  // B: one colour, folded: a solid half and a tinted half.
  fold: (c) => `<path d="${LEFT}" fill="${c.l}"/><path d="${RIGHT}" fill="${c.r}"/>`,
  // C: two half-heart links, interlocked like a chain.
  knot: (c) => { const id = 'k' + (++uid); const w = 7.5, o = 9;
    const A = `<path d="${LEFT}" fill="none" stroke="${c.l}" stroke-width="${w}" stroke-linejoin="round" transform="translate(${o} 0)"/>`;
    const B = `<path d="${RIGHT}" fill="none" stroke="${c.r}" stroke-width="${w}" stroke-linejoin="round" transform="translate(${-o} 0)"/>`;
    // A over B at the top crossing, B over A at the bottom: the two links thread through each other.
    return `<defs><clipPath id="${id}"><rect x="0" y="50" width="100" height="60"/></clipPath></defs><g transform="translate(50 50) scale(.92) translate(-50 -50)">${B}${A}<g clip-path="url(#${id})">${B}</g></g>`; },
  // D: the current sticker, refined: deeper inks, crisp keyline, soft lift.
  sticker: (c) => `<path d="${WHOLE}" fill="#fff" stroke="#fff" stroke-width="12" stroke-linejoin="round" style="filter:drop-shadow(0 3px 4px rgba(30,36,90,.28))"/>
    <path d="${LEFT}" fill="${c.l}"/><path d="${RIGHT}" fill="${c.r}"/><path d="M50 ${f(TY - 2 * S + 4)} L50 ${TY - 6}" stroke="#fff" stroke-width="2" stroke-dasharray="4 4" stroke-linecap="round" opacity=".9"/>`,
};
const mark = (kind, c, size) => `<svg width="${size}" height="${size}" viewBox="-6 -2 112 106">${MARKS[kind](c)}</svg>`;

const DIRS = [
  { key: 'A', name: 'Midnight Seam', line: 'Two exact halves with a hairline of air between them. Lit jewel tones on midnight. Reads like a watch face, not a toy.',
    kind: 'seam', dark: { l1: '#4FD1BC', l2: '#1F9C8E', r1: '#FFA27A', r2: '#E8683E' }, light: { l1: '#2BA595', l2: '#16786E', r1: '#F58A5E', r2: '#D2532A' },
    icon: 'radial-gradient(120% 90% at 50% 0%, #2A3266 0%, #141938 55%, #0B0E22 100%)', iconMark: 'dark',
    word: { text: 'Heartpact', font: "'Instrument Serif'", weight: 400, size: 60, track: '-0.01em' },
    chips: [['Midnight', '#0B0E22'], ['Jade', '#2BA595'], ['Ember', '#E8683E'], ['Ivory', '#F7F4EF']] },
  { key: 'B', name: 'Indigo Fold', line: 'One ink, folded in half: a solid side and a tinted side. Calm and quiet, and it takes the app\'s own indigo page.',
    kind: 'fold', dark: { l: '#FFFFFF', r: '#9CA5EC' }, light: { l: '#1F2557', r: '#8E98E4' },
    icon: 'linear-gradient(160deg, #2C3474 0%, #1A1F4D 100%)', iconMark: 'dark',
    word: { text: 'heartpact', font: "'Sora'", weight: 600, size: 50, track: '-0.045em' },
    chips: [['Indigo', '#1F2557'], ['Periwinkle', '#8E98E4'], ['Page', '#DFE3FB'], ['White', '#FFFFFF']] },
  { key: 'C', name: 'Linked Halves', line: 'Each partner is a link, threaded through the other. The pact as a chain you both hold. Line-drawn and light.',
    kind: 'knot', dark: { l: '#3CC4AF', r: '#FF8C62' }, light: { l: '#167A70', r: '#DA5A2E' },
    icon: '#F6F1EA', iconMark: 'light',
    word: { text: 'Heartpact', font: "'Outfit'", weight: 600, size: 54, track: '-0.035em' },
    chips: [['Deep jade', '#167A70'], ['Terracotta', '#DA5A2E'], ['Linen', '#F6F1EA'], ['Ink', '#1B2230']] },
  { key: 'D', name: 'Sticker, refined', line: 'Today\'s sticker heart, redrawn on the geometric heart with deeper inks and a stitched seam. Keeps the app\'s world intact.',
    kind: 'sticker', dark: { l: '#23A08F', r: '#EE7246' }, light: { l: '#1F8B7E', r: '#E2643A' },
    icon: 'linear-gradient(170deg, #E7EAFD 0%, #C9CFF4 100%)', iconMark: 'light',
    word: { text: 'Heartpact', font: "'Fredoka'", weight: 600, size: 54, track: '-0.02em' },
    chips: [['Teal', '#1F8B7E'], ['Persimmon', '#E2643A'], ['Album page', '#DFE3FB'], ['Ink', '#1B2230']] },
];

const icon = (d, size, radius) => `<div class="icon" style="width:${size}px;height:${size}px;border-radius:${radius};background:${d.icon}">${mark(d.kind, d[d.iconMark], Math.round(size * 0.6))}</div>`;
const lock = (d, tone) => `<div class="lock ${tone}">${mark(d.kind, d[tone], 64)}<span style="font-family:${d.word.font};font-weight:${d.word.weight};font-size:${d.word.size}px;letter-spacing:${d.word.track}">${d.word.text}</span></div>`;

const rows = DIRS.map(d => `<section>
  <div class="meta"><div class="k">${d.key}</div><h2>${d.name}</h2><p>${d.line}</p>
    <div class="chips">${d.chips.map(([n, h]) => `<div><i style="background:${h}"></i><b>${n}</b><span>${h}</span></div>`).join('')}</div></div>
  <div class="icons">${icon(d, 168, '38px')}<div class="sm">${icon(d, 72, '50%')}${icon(d, 48, '14px')}</div></div>
  <div class="locks">${lock(d, 'light')}${lock(d, 'dark')}</div>
</section>`).join('');

const html = `<!doctype html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Instrument+Serif&family=Outfit:wght@600&family=Sora:wght@600&display=block">
<style>
@font-face{font-family:Fredoka;src:url(../fonts/fredoka-latin.woff2) format('woff2');font-weight:300 700}
@font-face{font-family:Figtree;src:url(../fonts/figtree-latin.woff2) format('woff2');font-weight:300 900}
*{margin:0;padding:0;box-sizing:border-box}
body{width:1480px;background:#ECEDF3;font-family:Figtree;color:#1B2230;padding:44px 48px 48px}
header h1{font:600 34px Fredoka;letter-spacing:-.01em}header p{font-size:16px;color:#4B5370;margin-top:6px}
section{display:grid;grid-template-columns:330px 260px 1fr;gap:28px;align-items:center;background:#fff;border-radius:28px;padding:26px 28px;margin-top:22px;box-shadow:0 18px 36px -26px rgba(30,36,90,.35)}
.meta .k{width:34px;height:34px;border-radius:17px;background:#1B2230;color:#fff;font:700 15px Figtree;display:grid;place-items:center}
.meta h2{font:700 24px Figtree;margin-top:12px;letter-spacing:-.01em}.meta p{font-size:14px;line-height:1.45;color:#4B5370;margin-top:6px}
.chips{display:grid;grid-template-columns:1fr 1fr;gap:8px 12px;margin-top:14px}
.chips div{display:grid;grid-template-columns:22px 1fr;column-gap:8px;align-items:center;font-size:12px}
.chips i{grid-row:span 2;width:22px;height:22px;border-radius:7px;box-shadow:inset 0 0 0 1px rgba(27,34,48,.12)}
.chips b{font-weight:700}.chips span{color:#6D7492;font-variant-numeric:tabular-nums}
.icons{display:flex;align-items:center;gap:18px}.sm{display:flex;flex-direction:column;align-items:center;gap:16px}
.icon{display:grid;place-items:center;box-shadow:0 14px 26px -16px rgba(11,14,34,.6),inset 0 0 0 1px rgba(255,255,255,.06);overflow:hidden}
.locks{display:grid;gap:12px}
.lock{display:flex;align-items:center;gap:14px;height:118px;border-radius:20px;padding:0 28px}
.lock.light{background:#FAF8F5;color:#141938}.lock.dark{background:#11152E;color:#F4F2EE}
.lock span{line-height:1}
</style></head><body>
<header><h1>Heartpact logo directions</h1><p>Each mark is built on one geometric heart: a turned square with two round lobes, split on its centre line, so each partner owns an exact half.</p></header>
${rows}</body></html>`;

(async () => {
  const tmp = path.join(__dirname, '.explore.html');
  fs.writeFileSync(tmp, html);
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1480, height: 800 }, deviceScaleFactor: 2 });
  await p.goto('file://' + tmp, { waitUntil: 'networkidle' });
  await p.evaluate(() => document.fonts.ready);
  console.log('missing fonts:', await p.evaluate(() => ['Instrument Serif', 'Outfit', 'Sora', 'Fredoka', 'Figtree'].filter(f => !document.fonts.check(`16px "${f}"`))));
  await p.screenshot({ path: path.join(__dirname, 'explore.png'), fullPage: true });
  await b.close(); fs.unlinkSync(tmp);
})();
