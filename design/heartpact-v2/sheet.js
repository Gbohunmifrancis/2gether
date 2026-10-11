// Contact sheet of png/*.png (2x renders shown at 1x): node sheet.js out.png [filter]
const fs = require('fs'), path = require('path');
const { chromium } = require('/opt/node-tools/node_modules/playwright');
(async () => {
  const [out, only] = process.argv.slice(2);
  const dir = path.join(__dirname, 'png');
  const files = fs.readdirSync(dir).filter(f => f.endsWith('.png') && !f.startsWith('00') && (!only || new RegExp(only).test(f))).sort();
  const browser = await chromium.launch(); const p = await browser.newPage({ deviceScaleFactor: 1 });
  const cards = files.map(f => {
    const b64 = fs.readFileSync(path.join(dir, f)).toString('base64');
    const wide = /camera/.test(f);
    return `<figure style="${wide ? 'grid-column:span 2' : ''}"><img src="data:image/png;base64,${b64}" style="width:${wide ? 844 : 390}px"><figcaption>${f.replace('.png', '')}</figcaption></figure>`;
  }).join('');
  await p.setContent(`<body style="margin:0;padding:36px;background:#CDD3CF;font:600 16px system-ui;color:#18202B;width:max-content">
    <div style="display:grid;grid-template-columns:repeat(5,390px);gap:28px 28px;align-items:start">${cards}</div>
    <style>figure{margin:0}img{display:block;border-radius:30px;box-shadow:0 26px 50px -26px rgba(0,0,0,.45)}figcaption{margin-top:10px}</style></body>`);
  await p.screenshot({ path: out, fullPage: true }); await browser.close();
})();
