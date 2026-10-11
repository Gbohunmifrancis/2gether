// Renders screens/*.html into png/ at 2x inside Android system chrome, expanding the
// character and sticker placeholders ({{m:who:pose:size:sticker:mood}}, {{heart:size}},
// {{half:l|r:size}}, {{slot:l|r:size}}, {{peeled:size}}) into authored SVG.
// Each screen starts with a JSON comment: {"time":"7:48","tab":"today","status":"dark|light|none",
// "handle":"dark|light|none","bg":"#hex","w":390,"h":844}. Usage: node render.js [name-filter]
const fs = require('fs'), path = require('path');
const { chromium } = require('/opt/node-tools/node_modules/playwright');
const { svg } = require('./mascots.js');
const dir = __dirname;
const TABS = [['today', 'today', 'Today'], ['pacts', 'favorite', 'Pacts'], ['food', 'restaurant', 'Food'], ['us', 'diversity_1', 'Us'], ['album', 'photo_library', 'Album']];
const LEFT = 'M50 20 C 46 10 38 4 29 4 C 14 4 4 16 4 33 C 4 58 26 78 50 104 Z';
const RIGHT = 'M50 20 C 54 10 62 4 71 4 C 86 4 96 16 96 33 C 96 58 74 78 50 104 Z';
const WHOLE = 'M50 20 C 46 10 38 4 29 4 C 14 4 4 16 4 33 C 4 58 26 78 50 104 C 74 78 96 58 96 33 C 96 16 86 4 71 4 C 62 4 54 10 50 20 Z';
const box = (s, inner, vb = '-8 -6 116 120') => `<svg viewBox="${vb}" width="${s}" height="${Math.round(s * 1.04)}">${inner}</svg>`;
const keyline = d => `<path d="${d}" fill="#fff" stroke="#fff" stroke-width="13" stroke-linejoin="round" style="filter:drop-shadow(0 3px 3px rgba(52,58,120,.25))"/>`;
const STICKERS = {
  heart: s => box(s, keyline(WHOLE) + `<path d="${LEFT}" fill="#2C8C85"/><path d="${RIGHT}" fill="#E2683C"/>`),
  half: (side, s) => box(s, keyline(side === 'l' ? LEFT : RIGHT) + `<path d="${side === 'l' ? LEFT : RIGHT}" fill="${side === 'l' ? '#2C8C85' : '#E2683C'}"/>`),
  slot: (side, s) => box(s, `<path d="${side === 'l' ? LEFT : RIGHT}" fill="none" style="stroke:var(${side === 'l' ? '--maya' : '--theo'})" stroke-width="5" stroke-dasharray="9 8" stroke-linejoin="round" stroke-linecap="round"/>`),
  peeled: s => box(s, keyline(WHOLE) + `<path d="${LEFT}" fill="#2C8C85" opacity=".55"/><path d="${RIGHT}" fill="#E2683C" opacity=".55"/><path d="M70 4 C 86 4 96 16 96 33 L 70 33 Z" fill="#F3F4FC"/><path d="M70 4 L70 33 L96 33" fill="none" stroke="#C9CEE8" stroke-width="3" stroke-linejoin="round"/>`),
};
function expand(html) {
  return html
    .replace(/\{\{m:(\w+):(\w+):(\d+)(?::(sticker|flat))?(?::(\w+))?\}\}/g, (_, who, pose, size, st, mood) => svg({ who, pose, size: +size, sticker: st === 'sticker', mood: mood || (pose === 'cheer' ? 'cheer' : 'smile') }))
    .replace(/\{\{heart:(\d+)\}\}/g, (_, s) => STICKERS.heart(+s))
    .replace(/\{\{peeled:(\d+)\}\}/g, (_, s) => STICKERS.peeled(+s))
    .replace(/\{\{half:(l|r):(\d+)\}\}/g, (_, side, s) => STICKERS.half(side, +s))
    .replace(/\{\{slot:(l|r):(\d+)\}\}/g, (_, side, s) => STICKERS.slot(side, +s));
}

const nav = tab => `<div class="nav">${TABS.map(([id, icon, label]) =>
  `<div class="${id === tab ? 'on' : ''}"><i><span class="ms">${icon}</span></i>${label}</div>`).join('')}</div><div class="navgap"></div>`;
const status = (time, tone) => `<div class="status ${tone === 'light' ? 'light' : ''}"><span>${time}</span>
  <span class="r"><span class="ms">signal_cellular_alt</span><span class="ms">wifi</span><span class="ms">battery_5_bar</span></span></div>`;

function page(file) {
  const raw = fs.readFileSync(path.join(dir, 'screens', file), 'utf8');
  const meta = JSON.parse((raw.match(/^<!--\s*(\{.*?\})\s*-->/s) || [, '{}'])[1]);
  const body = expand(raw.replace(/^<!--.*?-->\s*/s, ''));
  const w = meta.w || 390, h = meta.h || 844;
  const chrome = meta.status === 'none' ? '' : status(meta.time || '7:48', meta.theme === 'dark' ? 'light' : meta.status);
  const handle = meta.handle === 'none' ? '' : `<div class="handle ${meta.handle === 'light' || meta.theme === 'dark' ? 'light' : ''}"></div>`;
  const html = `<!doctype html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@20..48,100..700,0..1,-50..200&display=block">
<link rel="stylesheet" href="file://${path.join(dir, 'styles.css')}">
<style>body{width:${w}px;height:${h}px;${meta.bg ? `background:${meta.bg};` : ''}}</style></head>
<body class="${meta.theme === 'dark' ? 'dark' : ''}"><div class="screen">${chrome}${body}${meta.tab ? nav(meta.tab) : ''}</div>${handle}</body></html>`;
  return { html, w, h };
}

(async () => {
  const only = process.argv[2];
  const files = fs.readdirSync(path.join(dir, 'screens')).filter(f => f.endsWith('.html') && (!only || f.includes(only))).sort();
  const browser = await chromium.launch();
  for (const file of files) {
    const { html, w, h } = page(file);
    const tmp = path.join(dir, 'screens', `.${file}.render.html`);
    fs.writeFileSync(tmp, html);
    const ctx = await browser.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: 2 });
    const p = await ctx.newPage();
    await p.goto('file://' + tmp, { waitUntil: 'networkidle' });
    await p.evaluate(() => document.fonts.ready);
    const fonts = await p.evaluate(() => ['Fredoka', 'Figtree', 'Material Symbols Rounded']
      .filter(f => !document.fonts.check(`16px "${f}"`)));
    const overflow = await p.evaluate(() => [...document.querySelectorAll('.screen *')].filter(el => {
      const r = el.getBoundingClientRect(); return r.width > 0 && (r.right > innerWidth + 0.5 || r.left < -0.5) && getComputedStyle(el).position !== 'absolute';
    }).length);
    await p.screenshot({ path: path.join(dir, 'png', file.replace(/\.html$/, '.png')) });
    console.log(file, fonts.length ? 'MISSING FONTS ' + fonts.join(', ') : 'fonts ok', overflow ? `· ${overflow} element(s) overflow horizontally` : '');
    fs.unlinkSync(tmp);
    await ctx.close();
  }
  await browser.close();
})();
