// Builds the direction-round sketches: node build.js -> sketches/*.html (self-contained, no JS)
const fs = require('fs'), path = require('path');
const { svg } = require('../mascots.js');
const M = (who, pose, size, o = {}) => svg({ who, pose, size, mood: o.mood || (pose === 'cheer' ? 'cheer' : 'smile'), sticker: !!o.sticker });
const ms = n => `<span class="ms">${n}</span>`;
const statusbar = (light) => `<div class="sb" style="${light ? 'color:#fff' : ''}"><span>7:48</span><span>${ms('signal_cellular_alt')}${ms('wifi')}${ms('battery_5_bar')}</span></div>`;
const nav = (on, c) => `<div class="nav">${[['today', 'Today'], ['favorite', 'Pacts'], ['restaurant', 'Food'], ['diversity_1', 'Us'], ['photo_library', 'Album']].map(([i, l], k) => `<div class="${k === 0 ? 'on' : ''}"><i>${ms(i)}</i>${l}</div>`).join('')}</div><div class="handle"></div>`;
const base = (fonts, css, body) => `<!doctype html><html><head><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?${fonts}&family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@20..24,300..400,0..1,0&display=block" rel="stylesheet">
<style>*{box-sizing:border-box;margin:0;padding:0}body{width:390px;height:844px;overflow:hidden;position:relative;font-variant-numeric:tabular-nums}
.ms{font-family:'Material Symbols Rounded';font-size:22px;line-height:1;font-variation-settings:'FILL' 0,'wght' 300,'opsz' 24;display:inline-block;vertical-align:middle}
.sb{height:34px;padding:0 22px;display:flex;align-items:center;justify-content:space-between;font-size:13px;font-weight:600}.sb .ms{font-size:16px;margin-left:4px}
.nav{position:absolute;left:0;right:0;bottom:0;height:96px;display:flex;padding:12px 8px 0}.nav div{flex:1;display:flex;flex-direction:column;align-items:center;gap:4px;font-size:12px;font-weight:600}
.nav i{width:64px;height:32px;border-radius:16px;display:grid;place-items:center;font-style:normal}.nav .on .ms{font-variation-settings:'FILL' 1,'wght' 400,'opsz' 24}
.handle{position:absolute;bottom:8px;left:50%;width:108px;height:4px;margin-left:-54px;border-radius:2px;background:currentColor;opacity:.35}
svg{display:block;overflow:visible}${css}</style></head><body>${body}</body></html>`;

// A. Sticker Book: every sealed pact becomes a heart sticker on your page.
const halfSlot = (side, color) => `<svg viewBox="-4 -2 108 112" width="46" height="50"><path d="${side === 'l' ? 'M50 20 C 46 10 38 4 29 4 C 14 4 4 16 4 33 C 4 58 26 78 50 104 Z' : 'M50 20 C 54 10 62 4 71 4 C 86 4 96 16 96 33 C 96 58 74 78 50 104 Z'}" fill="none" stroke="${color}" stroke-width="4" stroke-dasharray="7 7" stroke-linejoin="round"/></svg>`;
const heart = (fill, s = 22) => `<svg viewBox="-6 -4 112 116" width="${s}" height="${s * 1.08}"><path d="M50 20 C 46 10 38 4 29 4 C 14 4 4 16 4 33 C 4 58 26 78 50 104 C 74 78 96 58 96 33 C 96 16 86 4 71 4 C 62 4 54 10 50 20 Z" fill="#fff" stroke="#fff" stroke-width="12" stroke-linejoin="round"/><path d="M50 20 C 46 10 38 4 29 4 C 14 4 4 16 4 33 C 4 58 26 78 50 104 Z" fill="#2C8C85"/><path d="M50 20 C 54 10 62 4 71 4 C 86 4 96 16 96 33 C 96 58 74 78 50 104 Z" fill="#E2683C"/></svg>`;
const halfHeart = (side, fill, s = 22) => `<svg viewBox="0 0 100 108" width="${s}" height="${s * 1.08}"><path d="${side === 'l' ? 'M50 20 C 46 10 38 4 29 4 C 14 4 4 16 4 33 C 4 58 26 78 50 104 Z' : 'M50 20 C 54 10 62 4 71 4 C 86 4 96 16 96 33 C 96 58 74 78 50 104 Z'}" fill="${fill}" stroke="#fff" stroke-width="7" paint-order="stroke"/></svg>`;
const A = base('family=Fredoka:wght@500;600&family=Figtree:wght@400;500;600;700', `
body{background:#DFE3FB;color:#1B2230;font:500 14px/1.4 'Figtree',sans-serif}
h1{font:600 30px/1 'Fredoka';letter-spacing:-.01em}.sub{font-size:13px;color:#4B5370;margin-top:6px}
.card{background:#fff;border-radius:30px;box-shadow:0 2px 0 rgba(27,34,48,.04),0 22px 40px -22px rgba(40,44,90,.45)}
.big{font:600 64px/.9 'Fredoka';letter-spacing:-.02em}
.btn{height:54px;border-radius:27px;background:#2C8C85;color:#fff;display:flex;align-items:center;justify-content:center;gap:10px;font:700 16px 'Figtree'}
.txt{height:44px;display:flex;align-items:center;justify-content:center;color:#23746E;font-weight:700}
.row{display:flex;align-items:center;gap:12px;height:52px;padding:0 18px}.row+.row{border-top:1.5px dashed #E1E4F2}.row .v{margin-left:auto;color:#4B5370;font-size:13px}
.nav{background:#D3D8F5;color:#4B5370}.nav .on{color:#1B2230}.nav .on i{background:#fff;color:#2C8C85}`,
`${statusbar()}<div style="padding:8px 20px 0;display:flex;justify-content:space-between;align-items:flex-end"><div><h1>Today</h1><div class="sub">Sat 11 Oct · your sticker page has 14 hearts</div></div></div>
<div class="card" style="margin:16px 12px 0;padding:20px 20px 14px;position:relative">
 <div style="position:absolute;right:10px;top:-34px">${M('theo', 'wave', 70, { sticker: true })}</div>
 <div style="font:600 22px/1.15 'Fredoka';max-width:250px">Theo dared you: 20 sit-ups by 8:00</div>
 <div style="display:flex;align-items:flex-end;gap:10px;margin-top:12px"><span class="big">12</span><span class="sub" style="margin:0 0 4px">minutes before<br>his alarm rings</span></div>
 <div style="display:flex;align-items:center;gap:12px;margin-top:14px;padding:10px 12px;border-radius:18px;background:#F3F4FC">
   <div style="display:flex;align-items:center;flex:none">${halfSlot('l', '#2C8C85')}<span style="margin-left:-23px">${halfHeart('r', '#E2683C', 46)}</span></div>
   <div style="font-size:13px;line-height:1.35"><b>Theo stuck his half.</b><br>Do the reps and the camera sticks yours.</div></div>
 <div class="btn" style="margin-top:14px">${ms('photo_camera')}Start on camera</div><div class="txt">Ask Theo to call it off</div>
</div>
<div class="card" style="margin:12px 12px 0;padding:14px 18px;display:flex;align-items:center;gap:6px">
 <span style="font-weight:700;margin-right:auto">This week</span>${[1, 2, 3, 4, 5].map(() => heart('', 26)).join('')}${halfHeart('r', '#E2683C', 26)}<span style="width:22px;height:24px;border:2px dashed #B9BEDD;border-radius:8px"></span></div>
<div class="card" style="margin:12px 12px 0"><div class="row">${ms('restaurant')}<b>Food</b><span class="v"><b>~1,240</b> of ~1,900 kcal</span></div><div class="row">${ms('forum')}<b>Today's question</b><span class="v">${ms('lock')} answer to see Theo's</span></div></div>
${nav()}`);

// B. Morning Sky: a sky that changes with the day; the two of you live in it.
const B = base('family=Nunito:wght@600;700;800&family=Figtree:wght@400;500;600;700', `
body{background:#F5F1EA;color:#1F2430;font:500 14px/1.4 'Figtree',sans-serif}
.sky{position:absolute;left:0;right:0;top:0;height:380px;background:linear-gradient(#BFE0F5,#E9F3F7 70%,#F5F1EA)}
.card{background:#fff;border-radius:28px;box-shadow:0 20px 40px -24px rgba(60,70,90,.35)}
.btn{height:54px;border-radius:27px;background:#1F2430;color:#fff;display:flex;align-items:center;justify-content:center;gap:10px;font:700 16px 'Figtree'}
.nav{background:#fff;color:#6A7080}.nav .on{color:#1F2430}.nav .on i{background:#FDE5DA;color:#C4532C}`,
`<div class="sky"><svg width="390" height="380" style="position:absolute;inset:0"><circle cx="318" cy="96" r="34" fill="#FFD36B"/><g fill="#fff" opacity=".95"><ellipse cx="80" cy="120" rx="48" ry="18"/><ellipse cx="110" cy="108" rx="30" ry="20"/><ellipse cx="270" cy="170" rx="56" ry="18"/><ellipse cx="300" cy="158" rx="30" ry="18"/></g><path d="M-10 330 C 90 280 200 300 400 270 L400 380 L-10 380 Z" fill="#CFE6C9"/><path d="M-10 350 C 120 320 260 340 400 310 L400 380 L-10 380 Z" fill="#B7D9AE"/></svg>
 ${statusbar()}<div style="position:absolute;left:22px;top:52px"><div style="font:800 28px/1.1 'Nunito'">Morning, Maya</div><div style="color:#4A5262;margin-top:4px">Theo's up too. 12 minutes to his dare.</div></div>
 <div style="position:absolute;left:120px;top:180px">${svg({ who: 'both', pose: 'hug', size: 132 })}</div></div>
<div class="card" style="position:absolute;left:12px;right:12px;top:352px;padding:20px">
 <div style="font:800 21px/1.2 'Nunito'">20 sit-ups before 8:00</div><div style="color:#5A6272;margin-top:4px">Theo set it · his alarm rings if you don't</div>
 <div style="display:flex;gap:10px;margin-top:14px"><div style="flex:1;height:10px;border-radius:5px;background:#EDE7DD"><div style="width:0%;height:100%;border-radius:5px;background:#2C8C85"></div></div><span style="font-size:12px;color:#5A6272;margin-top:-3px">0 / 20</span></div>
 <div class="btn" style="margin-top:16px">${ms('photo_camera')}Start on camera</div></div>
<div class="card" style="position:absolute;left:12px;right:12px;top:580px;padding:4px 0"><div style="display:flex;align-items:center;gap:12px;height:52px;padding:0 18px">${ms('restaurant')}<b>Food</b><span style="margin-left:auto;color:#5A6272">~1,240 of ~1,900</span></div><div style="display:flex;align-items:center;gap:12px;height:52px;padding:0 18px;border-top:1px solid #EFEAE2">${ms('forum')}<b>Question of the day</b><span style="margin-left:auto;color:#5A6272">${ms('lock')}</span></div></div>
${nav()}`);

// C. Warm Cards: a warm consumer surface, one action colour, characters in the corners.
const C = base('family=Figtree:wght@400;500;600;700;800', `
body{background:#FFF1E8;color:#2A1F1B;font:500 14px/1.4 'Figtree',sans-serif}
.card{background:#fff;border-radius:24px;padding:18px;box-shadow:0 1px 0 rgba(42,31,27,.04),0 16px 30px -22px rgba(120,60,30,.35)}
h1{font:800 28px/1.1 'Figtree'}.sub{color:#6B5A52;font-size:13px;margin-top:4px}
.btn{height:52px;border-radius:26px;background:#E2683C;color:#fff;display:flex;align-items:center;justify-content:center;gap:10px;font:700 15px 'Figtree'}
.nav{background:#FFE6D8;color:#6B5A52}.nav .on{color:#2A1F1B}.nav .on i{background:#fff;color:#E2683C}`,
`${statusbar()}<div style="padding:10px 20px 0;display:flex;justify-content:space-between;align-items:center"><div><h1>Good morning</h1><div class="sub">Saturday · 14 days in a row</div></div><div>${svg({ who: 'both', pose: 'stand', size: 58 })}</div></div>
<div class="card" style="margin:16px 12px 0;display:flex;gap:14px;align-items:center"><div style="flex:1"><div style="font:800 19px/1.2 'Figtree'">20 sit-ups by 8:00</div><div class="sub">From Theo · 12 minutes left</div><div class="btn" style="margin-top:14px">${ms('photo_camera')}Start</div></div>${M('theo', 'cheer', 92)}</div>
<div style="display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:12px 12px 0">
 <div class="card"><div class="sub" style="margin:0">Food today</div><div style="font:800 26px 'Figtree';margin-top:6px">~1,240</div><div class="sub">of ~1,900 kcal</div></div>
 <div class="card"><div class="sub" style="margin:0">Question</div><div style="font:700 15px/1.3 'Figtree';margin-top:6px">One extra free weekend a month…</div><div class="sub">Theo answered</div></div></div>
<div class="card" style="margin:12px 12px 0;display:flex;align-items:center;gap:12px">${M('maya', 'rest', 54, { mood: 'sleep' })}<div><div style="font-weight:800">Theo's push-ups at 18:30</div><div class="sub">You set it · he hasn't started</div></div></div>
${nav()}`);

const out = path.join(__dirname);
fs.writeFileSync(path.join(out, 'a-sticker-book.html'), A);
fs.writeFileSync(path.join(out, 'b-morning-sky.html'), B);
fs.writeFileSync(path.join(out, 'c-warm-cards.html'), C);
console.log('built');
