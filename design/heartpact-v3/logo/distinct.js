// Making the mark ownable: four ways to make the Heartpact heart stop reading as "a heart".
// Each is shown big, as an app icon, at 24px, and in a row of ordinary hearts. Usage: node distinct.js
const fs = require('fs'), path = require('path');
const { chromium } = require('/opt/node-tools/node_modules/playwright');
const S = 60 / Math.SQRT2, R = 30, TX = 50, TY = 100, g = n => +n.toFixed(2);
const LEFT = `M${TX} ${TY} L${g(TX - S)} ${g(TY - S)} A${R} ${R} 0 0 1 ${TX} ${g(TY - 2 * S)} Z`;
const RIGHT = `M${TX} ${TY} L${g(TX + S)} ${g(TY - S)} A${R} ${R} 0 0 0 ${TX} ${g(TY - 2 * S)} Z`;
const IND = '#1F2557', PER = '#7480D4', US = '#141A45';
let n = 0;
const MARKS = {
  now: (l = IND, r = PER) => `<path d="${LEFT}" fill="${l}"/><path d="${RIGHT}" fill="${r}"/>`,
  // A: two capsules leaning on each other; where they cross is a third, deeper colour: us.
  capsules: (l = IND, r = PER, u = US) => { const id = 'c' + (++n); const cap = (x, a) => `<rect x="-21" y="-42" width="42" height="84" rx="21" transform="translate(${x} 52) rotate(${a})"/>`;
    return `<defs><clipPath id="${id}">${cap(35, -45)}</clipPath></defs><g fill="${r}">${cap(65, 45)}</g><g fill="${l}">${cap(35, -45)}</g><g fill="${u}" clip-path="url(#${id})">${cap(65, 45)}</g>`; },
  // B: the fold, literally: Theo's half swung open on the seam like a card.
  open: (l = IND, r = PER) => `<path d="${RIGHT}" fill="${r}" transform="matrix(.62 -.16 0 1 19 8)"/><path d="${LEFT}" fill="${l}"/><path d="M50 ${g(TY - 2 * S)} L50 ${TY}" stroke="#fff" stroke-opacity=".35" stroke-width="1.6"/>`,
  // C: a round key on the seam: the halves lock into each other.
  key: (l = IND, r = PER) => `<path d="${LEFT}" fill="${l}"/><path d="${RIGHT}" fill="${r}"/><circle cx="54" cy="56" r="9" fill="${l}"/><circle cx="46" cy="80" r="0.01" fill="${r}"/>`,
  // D: a kept promise: a tick cut through the heart, short leg in Maya's half, long leg in Theo's.
  tick: (l = IND, r = PER, bg = '#FFFFFF') => `<path d="${LEFT}" fill="${l}"/><path d="${RIGHT}" fill="${r}"/><path d="M28 56 L44 72 L74 38" fill="none" stroke="${bg}" stroke-width="9" stroke-linecap="round" stroke-linejoin="round"/>`,
};
const svg = (inner, size) => `<svg width="${size}" height="${size}" viewBox="-4 2 108 104">${inner}</svg>`;
const DIRS = [
  ['Today', 'now', 'The chosen fold. Clean, but at 24px it is just a two-tone heart, and it sits beside the like button and every heart emoji.'],
  ['A · Two Capsules', 'capsules', 'Two rounded bars lean on each other to make the heart. Where they cross is a third, deeper indigo: the "us" only the two of you make. Built from a construction no other heart uses.'],
  ['B · Opened Fold', 'open', 'Theo\'s half swings open on the seam like a card being opened. It says "fold" literally and has depth without fake shine.'],
  ['C · Keyed Seam', 'key', 'A round key on the seam locks Maya\'s half into Theo\'s. Two halves that only fit each other.'],
  ['D · Kept Promise', 'tick', 'A tick cut through the heart: a short stroke in Maya\'s half, a long one in Theo\'s. It is the product in one shape, a promise both of you kept and the camera verified.'],
];
const icon = (kind, size) => {
  const dark = { now: ['#FFFFFF', '#A9B1F2'], capsules: ['#FFFFFF', '#A9B1F2', '#C9CEFF'], open: ['#FFFFFF', '#A9B1F2'], key: ['#FFFFFF', '#A9B1F2'], tick: ['#FFFFFF', '#A9B1F2', '#222A63'] }[kind];
  return `<div class="icon" style="width:${size}px;height:${size}px;border-radius:${size * .23}px">${svg(MARKS[kind](...dark), size * .62)}</div>`;
};
const generic = `<span class="ms" style="font-size:30px;color:#B3261E;font-variation-settings:'FILL' 1">favorite</span><span class="ms" style="font-size:30px;color:#474C72">favorite</span><span style="font-size:27px;line-height:1">❤️</span><span style="font-size:27px;line-height:1">💜</span>`;
const rows = DIRS.map(([name, kind, line]) => `<section>
  <div class="big">${svg(MARKS[kind](), 150)}</div>
  <div class="txt"><h2>${name}</h2><p>${line}</p></div>
  <div class="ic">${icon(kind, 104)}${icon(kind, 48)}</div>
  <div class="ctx"><small>Among ordinary hearts, at 26px</small><div class="row">${generic}${svg(MARKS[kind](), 30)}</div>
    <div class="row word">${svg(MARKS[kind](), 30)}<span>heartpact</span></div></div>
</section>`).join('');
const html = `<!doctype html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@20..48,100..700,0..1,-50..200&display=block">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Noto+Color+Emoji&display=block">
<style>@font-face{font-family:Sora;src:url(../fonts/sora-latin.woff2) format('woff2');font-weight:400 700}
@font-face{font-family:Figtree;src:url(../fonts/figtree-latin.woff2) format('woff2');font-weight:300 900}
*{margin:0;padding:0;box-sizing:border-box}body{width:1480px;background:#EDEEF6;font-family:Figtree;color:#161A3D;padding:44px 48px}
.ms{font-family:'Material Symbols Rounded';font-weight:400}
h1{font:600 30px Sora;letter-spacing:-.03em}h1+p{color:#474C72;margin-top:6px;font-size:16px;max-width:980px;line-height:1.45}
section{display:grid;grid-template-columns:190px 1fr 200px 330px;gap:26px;align-items:center;background:#fff;border-radius:28px;padding:22px 28px;margin-top:18px}
section:first-of-type{background:#F7F7FB;box-shadow:inset 0 0 0 2px #E4E6F3}
.big{display:grid;place-items:center}.txt h2{font:600 22px Sora;letter-spacing:-.02em}.txt p{font-size:14.5px;line-height:1.45;color:#474C72;margin-top:6px;max-width:440px}
.ic{display:flex;align-items:center;gap:16px}.icon{display:grid;place-items:center;background:linear-gradient(160deg,#2C3474,#1A1F4D);box-shadow:0 14px 24px -16px rgba(11,14,34,.6)}
.ctx small{font-size:12px;font-weight:700;color:#6A6F92}.row{display:flex;align-items:center;gap:16px;margin-top:10px;height:34px}
.row.word span{font:600 26px Sora;letter-spacing:-.045em}.row.word{gap:8px;margin-top:14px}
</style></head><body><h1>Making the heart ours</h1><p>A plain heart is the like button, the emoji, every dating app. Each option below keeps the two halves (Maya's indigo, Theo's periwinkle) but adds one move that only Heartpact makes.</p>${rows}</body></html>`;
(async () => {
  const tmp = path.join(__dirname, '.distinct.html'); fs.writeFileSync(tmp, html);
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1480, height: 900 }, deviceScaleFactor: 2 });
  await p.goto('file://' + tmp, { waitUntil: 'networkidle' }); await p.evaluate(() => document.fonts.ready);
  console.log('missing', await p.evaluate(() => ['Sora', 'Figtree', 'Material Symbols Rounded'].filter(f => !document.fonts.check(`16px "${f}"`))));
  await p.screenshot({ path: path.join(__dirname, 'distinct.png'), fullPage: true }); await b.close(); fs.unlinkSync(tmp);
})();
