// Renders screens/*.html into png/ at 2x inside Android system chrome.
// Each screen starts with a JSON comment: {"time":"7:48","tab":"today","status":"dark|light|none",
// "handle":"dark|light|none","bg":"#hex","w":390,"h":844}. Usage: node render.js [name-filter]
const fs = require('fs'), path = require('path');
const { chromium } = require('/opt/node-tools/node_modules/playwright');
const dir = __dirname;
const TABS = [['today', 'today', 'Today'], ['pacts', 'handshake', 'Pacts'], ['food', 'restaurant', 'Food'], ['us', 'favorite', 'Us'], ['album', 'photo_library', 'Album']];

const nav = tab => `<div class="nav">${TABS.map(([id, icon, label]) =>
  `<div class="${id === tab ? 'on' : ''}"><i><span class="ms">${icon}</span></i>${label}</div>`).join('')}</div><div class="navgap"></div>`;
const status = (time, tone) => `<div class="status ${tone === 'light' ? 'light' : ''}"><span>${time}</span>
  <span class="r"><span class="ms">signal_cellular_alt</span><span class="ms">wifi</span><span class="ms">battery_5_bar</span></span></div>`;

function page(file) {
  const raw = fs.readFileSync(path.join(dir, 'screens', file), 'utf8');
  const meta = JSON.parse((raw.match(/^<!--\s*(\{.*?\})\s*-->/s) || [, '{}'])[1]);
  const body = raw.replace(/^<!--.*?-->\s*/s, '');
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
    const fonts = await p.evaluate(() => ['Bricolage Grotesque', 'Hanken Grotesk', 'Material Symbols Rounded']
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
