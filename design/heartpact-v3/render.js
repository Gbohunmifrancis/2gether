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
// The geometric heart from the logo: a square turned 45deg with two round lobes, split on its centre line.
const GS = 60 / Math.SQRT2, GR = 30, TX = 50, TY = 100, g = n => +n.toFixed(2);
const LEFT = `M${TX} ${TY} L${g(TX - GS)} ${g(TY - GS)} A${GR} ${GR} 0 0 1 ${TX} ${g(TY - 2 * GS)} Z`;
const RIGHT = `M${TX} ${TY} L${g(TX + GS)} ${g(TY - GS)} A${GR} ${GR} 0 0 0 ${TX} ${g(TY - 2 * GS)} Z`;
const WHOLE = `M${TX} ${TY} L${g(TX - GS)} ${g(TY - GS)} A${GR} ${GR} 0 0 1 ${TX} ${g(TY - 2 * GS)} A${GR} ${GR} 0 0 1 ${g(TX + GS)} ${g(TY - GS)} Z`;
const LOBE = { cx: g(TX + GS / 2), cy: g(TY - 1.5 * GS), top: g(TY - 1.5 * GS - GR), right: g(TX + GS / 2 + GR) };
const MAYA = '#1F2557', THEO = '#7480D4';
const box = (s, inner, vb = '-8 -2 116 112') => `<svg viewBox="${vb}" width="${s}" height="${Math.round(s * 112 / 116)}">${inner}</svg>`;
const keyline = d => `<path d="${d}" fill="#fff" stroke="#fff" stroke-width="13" stroke-linejoin="round" style="filter:drop-shadow(0 3px 3px rgba(31,37,87,.25))"/>`;
const STICKERS = {
  heart: s => box(s, keyline(WHOLE) + `<path d="${LEFT}" fill="${MAYA}"/><path d="${RIGHT}" fill="${THEO}"/>`),
  half: (side, s) => box(s, keyline(side === 'l' ? LEFT : RIGHT) + `<path d="${side === 'l' ? LEFT : RIGHT}" fill="${side === 'l' ? MAYA : THEO}"/>`),
  slot: (side, s) => box(s, `<path d="${side === 'l' ? LEFT : RIGHT}" fill="none" style="stroke:var(${side === 'l' ? '--maya' : '--theo'})" stroke-width="5" stroke-dasharray="9 8" stroke-linejoin="round" stroke-linecap="round"/>`),
  peeled: s => box(s, keyline(WHOLE) + `<path d="${LEFT}" fill="${MAYA}" opacity=".5"/><path d="${RIGHT}" fill="${THEO}" opacity=".5"/>
    <path d="M${LOBE.cx} ${LOBE.top} A${GR} ${GR} 0 0 1 ${LOBE.right} ${LOBE.cy} L${LOBE.cx} ${LOBE.cy} Z" fill="#F2F3FA"/>
    <path d="M${LOBE.cx} ${LOBE.top} L${LOBE.cx} ${LOBE.cy} L${LOBE.right} ${LOBE.cy}" fill="none" stroke="#C8CBE3" stroke-width="3" stroke-linejoin="round"/>`),
  // The mark itself: the flat fold, no keyline. On dark grounds Maya's half turns white.
  logo: (s, dark) => box(s, `<path d="${LEFT}" fill="${dark ? '#FFFFFF' : MAYA}"/><path d="${RIGHT}" fill="${dark ? '#A9B1F2' : THEO}"/>`),
};
function expand(html) {
  return html
    .replace(/\{\{m:(\w+):(\w+):(\d+)(?::(sticker|flat))?(?::(\w+))?\}\}/g, (_, who, pose, size, st, mood) => svg({ who, pose, size: +size, sticker: st === 'sticker', mood: mood || (pose === 'cheer' ? 'cheer' : 'smile') }))
    .replace(/\{\{heart:(\d+)\}\}/g, (_, s) => STICKERS.heart(+s))
    .replace(/\{\{logo:(\d+)(:dark)?\}\}/g, (_, s, d) => STICKERS.logo(+s, !!d))
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
    const fonts = await p.evaluate(() => ['Sora', 'Figtree', 'Material Symbols Rounded']
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
