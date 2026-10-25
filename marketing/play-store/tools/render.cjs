// Renders one HTML page to PNG with Chromium: node render.cjs page.html out.png width height
const { chromium } = require('playwright');
const { pathToFileURL } = require('node:url');

(async () => {
  const [page, out, width, height] = process.argv.slice(2);
  const browser = await chromium.launch();
  const tab = await browser.newPage({ viewport: { width: Number(width), height: Number(height) }, deviceScaleFactor: 1 });
  await tab.goto(pathToFileURL(page).href);
  await tab.evaluate(async () => {
    await document.fonts.ready;
    await Promise.all([...document.images].map(img => img.complete ? null : new Promise(done => { img.onload = img.onerror = done; })));
  });
  // Layout that depends on text size runs once the fonts are in.
  await tab.evaluate(() => window.layout && window.layout());
  // Captions must stay inside their slice and small enough for Play (20 % of a screenshot at most).
  const problems = await tab.evaluate(() => window.checks ? window.checks() : []);
  for (const problem of problems) console.error(`layout: ${problem}`);
  await tab.screenshot({ path: out, clip: { x: 0, y: 0, width: Number(width), height: Number(height) } });
  await browser.close();
  if (problems.length) process.exitCode = 2;
})();
