// Renders video.html frame by frame in Chromium (Playwright) and streams the frames to ffmpeg.
//   node render.cjs video.html <width> <height> <fps> <seconds> <out.mp4>
//   node render.cjs video.html <width> <height> stills <t1,t2,...> <folder>     (JPEG stills, for checking the design)
const { chromium } = require('playwright');
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

(async () => {
  const [html, width, height, mode, arg, out] = process.argv.slice(2);
  const w = +width, h = +height;
  const timeline = JSON.parse(fs.readFileSync(path.resolve(path.dirname(html), '../out/build/timeline.json'), 'utf8'));
  const browser = await chromium.launch({ args: ['--allow-file-access-from-files'] });
  const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
  page.on('pageerror', e => { console.error('page error:', e.message); process.exitCode = 1; });
  await page.goto('file://' + path.resolve(html) + `?w=${w}&h=${h}`);
  await page.evaluate(t => window.init(t), timeline);
  const shot = async t => { await page.evaluate(t => window.seek(t), t); return page.screenshot({ type: 'jpeg', quality: 95 }); };

  if (mode === 'stills') {
    fs.mkdirSync(out, { recursive: true });
    for (const t of arg.split(',').map(Number)) fs.writeFileSync(path.join(out, `${t.toFixed(2).padStart(6, '0')}.jpg`), await shot(t));
  } else {
    const fps = +mode, frames = Math.round(+arg * fps);
    const ffmpeg = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(fps), '-c:v', 'mjpeg', '-i', '-',
      '-c:v', 'libx264', '-preset', 'slow', '-crf', '17', '-pix_fmt', 'yuv420p', '-r', String(fps), out], { stdio: ['pipe', 'inherit', 'inherit'] });
    const done = new Promise((ok, fail) => ffmpeg.on('close', code => code === 0 ? ok() : fail(new Error('ffmpeg ' + code))));
    for (let i = 0; i < frames; i++) {
      const jpeg = await shot(i / fps);
      if (!ffmpeg.stdin.write(jpeg)) await new Promise(ok => ffmpeg.stdin.once('drain', ok));
      if (i % 150 === 0) process.stderr.write(`  ${w}x${h}: frame ${i}/${frames}\n`);
    }
    ffmpeg.stdin.end();
    await done;
  }
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
