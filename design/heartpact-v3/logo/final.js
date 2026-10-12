// Heartpact brand: Indigo Fold with the Kept Promise tick. Writes the mark (light/dark SVG), the Android adaptive icon layers,
// a 512px Play Store icon and brand.png. Usage: node final.js
const fs = require('fs'), path = require('path');
const { chromium } = require('/opt/node-tools/node_modules/playwright');
const out = f => path.join(__dirname, f);

// One geometric heart: a square turned 45deg with two round lobes, split on its centre line.
const S = 60 / Math.SQRT2, R = 30, TX = 50, TY = 100, g = n => +n.toFixed(2);
const LEFT = `M${TX} ${TY} L${g(TX - S)} ${g(TY - S)} A${R} ${R} 0 0 1 ${TX} ${g(TY - 2 * S)} Z`;
const RIGHT = `M${TX} ${TY} L${g(TX + S)} ${g(TY - S)} A${R} ${R} 0 0 0 ${TX} ${g(TY - 2 * S)} Z`;
const C = { indigo: '#1F2557', peri: '#7480D4', periNight: '#A9B1F2', white: '#FFFFFF', ink: '#161A3D', page: '#E3E5F6', iconTop: '#2C3474', iconBottom: '#1A1F4D' };
const VB = '-2 4.4 104 97.6'; // tight box around the heart
// The tick is cut out of the heart (a mask, so it works on any ground): a short stroke in Maya's half,
// a long one in Theo's. A promise both of you kept.
const TICK = 'M28 56 L44 72 L74 38', TICK_W = 9.5;
let mid = 0;
const cut = (l, r, dx = 0, dy = 0) => { const id = 'tick' + (++mid);
  return `<defs><mask id="${id}" maskUnits="userSpaceOnUse" x="-10" y="-10" width="130" height="130"><rect x="-10" y="-10" width="130" height="130" fill="#fff"/><path d="${TICK}" fill="none" stroke="#000" stroke-width="${TICK_W}" stroke-linecap="round" stroke-linejoin="round" transform="translate(${dx} ${dy})"/></mask></defs><g mask="url(#${id})"><path d="${LEFT}" fill="${l}" transform="translate(${dx} ${dy})"/><path d="${RIGHT}" fill="${r}" transform="translate(${dx} ${dy})"/></g>`; };
const mark = (l, r, attrs = '') => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${VB}" ${attrs}>${cut(l, r)}</svg>`;

fs.writeFileSync(out('heartpact-mark.svg'), mark(C.indigo, C.peri) + '\n');
fs.writeFileSync(out('heartpact-mark-dark.svg'), mark(C.white, C.periNight) + '\n');
// Android adaptive icon: 108dp canvas, mark inside the 66dp safe zone (about 56dp wide here).
const fg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><g transform="translate(26 27.5) scale(.54)">${cut(C.white, C.periNight, 0, -4.4)}</g></svg>\n`;
const bg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><defs><linearGradient id="b" x1="0" y1="0" x2=".35" y2="1"><stop offset="0" stop-color="${C.iconTop}"/><stop offset="1" stop-color="${C.iconBottom}"/></linearGradient></defs><rect width="108" height="108" fill="url(#b)"/></svg>\n`;
const mono = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><g transform="translate(26 27.5) scale(.54)">${cut('#000', 'rgba(0,0,0,.55)', 0, -4.4)}</g></svg>\n`;
fs.writeFileSync(out('ic_launcher_foreground.svg'), fg);
fs.writeFileSync(out('ic_launcher_background.svg'), bg);
fs.writeFileSync(out('ic_launcher_monochrome.svg'), mono);

const icon = (size, radius) => `<div class="icon" style="width:${size}px;height:${size}px;border-radius:${radius}">${bg.replace('<svg ', `<svg width="${size}" height="${size}" style="position:absolute;inset:0" `)}${fg.replace('<svg ', `<svg width="${size}" height="${size}" style="position:absolute;inset:0" `)}</div>`;
const word = (color, size) => `<span class="word" style="color:${color};font-size:${size}px">heartpact</span>`;
const lock = (l, r, color, h) => `<div class="lock" style="gap:${h * .3}px">${mark(l, r, `height="${h}"`)}${word(color, h * .86)}</div>`;

const css = `@font-face{font-family:Sora;src:url(../fonts/sora-latin.woff2) format('woff2');font-weight:400 700}
@font-face{font-family:Figtree;src:url(../fonts/figtree-latin.woff2) format('woff2');font-weight:300 900}
*{margin:0;padding:0;box-sizing:border-box}body{font-family:Figtree;color:${C.ink}}
.word{font-family:Sora;font-weight:600;letter-spacing:-.045em;line-height:1}
.lock{display:flex;align-items:center}.icon{position:relative;overflow:hidden;box-shadow:0 18px 30px -18px rgba(11,14,34,.65)}`;

const sheet = `<!doctype html><html><head><meta charset="utf-8"><style>${css}
body{width:1480px;background:#EDEEF6;padding:48px}
h1{font:600 30px Sora;letter-spacing:-.03em}h1+p{color:#474C72;margin-top:6px;font-size:16px;max-width:880px;line-height:1.45}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin-top:28px}
.panel{border-radius:28px;padding:34px;min-height:250px;display:flex;flex-direction:column;justify-content:space-between}
.panel small{font:700 12px Figtree;letter-spacing:.02em;opacity:.7}
.light{background:#FAFAFD}.page{background:${C.page}}.night{background:#10132B;color:#EEF0FB}.indigo{background:${C.indigo};color:#fff}
.icons{display:flex;align-items:flex-end;gap:26px}.icons div.c{display:flex;flex-direction:column;align-items:center;gap:10px;font-size:12px;color:#474C72}
.sw{display:grid;grid-template-columns:repeat(4,1fr);gap:14px}.sw div{border-radius:18px;padding:16px;height:118px;display:flex;flex-direction:column;justify-content:flex-end;font-size:13px}
.sw b{font-size:15px}.cs{position:relative;display:inline-block;outline:1.5px dashed #8E93B8;outline-offset:30px}
</style></head><body>
<h1>heartpact · Kept Promise</h1><p>One ink, folded in two: Maya is the solid indigo half and Theo the periwinkle half. A tick is cut through both, a short stroke in Maya's half and a long one in Theo's: a promise you both kept. That cut is what sets our heart apart from every other heart on the phone.</p>
<div class="grid">
 <div class="panel light"><small>Primary lockup on light</small>${lock(C.indigo, C.peri, C.ink, 92)}</div>
 <div class="panel night"><small>Primary lockup on night</small>${lock(C.white, C.periNight, '#F4F5FF', 92)}</div>
 <div class="panel page"><small>App icon: adaptive, shown in squircle, circle and small masks</small><div class="icons">
   <div class="c">${icon(176, '40px')}192 dp</div><div class="c">${icon(96, '50%')}launcher</div><div class="c">${icon(48, '12px')}48 dp</div><div class="c">${icon(24, '6px')}24 dp</div></div></div>
 <div class="panel indigo"><small>Clear space: one lobe on every side</small><div style="display:flex;gap:84px;align-items:center;padding:34px 0 14px 34px"><span class="cs">${mark(C.white, C.periNight, 'height="96"')}</span><span class="cs">${word('#fff', 58)}</span></div></div>
</div>
<div class="sw" style="margin-top:20px">
 <div style="background:${C.indigo};color:#fff"><b>Fold Indigo</b>#1F2557 · Maya, mark, primary action</div>
 <div style="background:${C.peri};color:#fff"><b>Periwinkle</b>#7480D4 · Theo's half (3.6:1 on white)</div>
 <div style="background:#4D58BE;color:#fff"><b>Theo action</b>#4D58BE · white labels 6.1:1</div>
 <div style="background:${C.page};color:${C.ink}"><b>Album page</b>#E3E5F6 · app background</div>
</div></body></html>`;

(async () => {
  const b = await chromium.launch();
  const shot = async (html, file, w, h, full) => {
    const tmp = out('.tmp.html'); fs.writeFileSync(tmp, html);
    const p = await b.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: file.includes('play') ? 1 : 2 });
    await p.goto('file://' + tmp, { waitUntil: 'networkidle' }); await p.evaluate(() => document.fonts.ready);
    const miss = await p.evaluate(() => ['Sora', 'Figtree'].filter(f => !document.fonts.check(`16px "${f}"`)));
    await p.screenshot({ path: out(file), fullPage: !!full, omitBackground: true }); await p.close(); fs.unlinkSync(tmp);
    console.log(file, miss.length ? 'MISSING ' + miss : 'ok');
  };
  await shot(sheet, 'brand.png', 1480, 900, true);
  await shot(`<!doctype html><style>${css}body{background:transparent}</style>${icon(512, '0')}`, 'play-store-icon-512.png', 512, 512);
  await b.close();
})();
