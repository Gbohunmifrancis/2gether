// The two Heartpact characters: each partner is one half of a heart, with a face,
// arms and legs. Together they make the whole heart. Authored SVG, no runtime deps.
// svg({who:'maya'|'theo'|'both', pose, size, sticker, mood}) -> SVG string.
const INK = '#161A3D';
// Indigo Fold: Maya is the solid indigo half (white face), Theo the periwinkle half (ink face). Rose cheeks on both.
const COLORS = { maya: { body: '#262D66', shade: '#1A1F4D', blush: '#E58AA8', face: '#FFFFFF' }, theo: { body: '#7480D4', shade: '#5F6BC6', blush: '#F2A3BA', face: INK } };

// Left half of a heart in a 100x120 box; the cut runs down x=50.
const HALF = 'M50 22 C 46 10 37 2 26 2 C 11 2 0 15 0 33 C 0 60 25 80 50 106 Z';

function face(mood, mirror, FACE = INK) {
  const ex = mirror ? [62, 76] : [24, 38];
  const eyes = mood === 'sleep'
    ? `<path d="M${ex[0] - 4} 42 q4 4 8 0 M${ex[1] - 4} 42 q4 4 8 0" stroke="${FACE}" stroke-width="2.6" stroke-linecap="round" fill="none"/>`
    : mood === 'worried'
      ? `<ellipse cx="${ex[0]}" cy="42" rx="3" ry="4" fill="${FACE}"/><ellipse cx="${ex[1]}" cy="42" rx="3" ry="4" fill="${FACE}"/><path d="M${ex[0] - 4} 34 l6 -2 M${ex[1] + 4} 34 l-6 -2" stroke="${FACE}" stroke-width="2.2" stroke-linecap="round"/>`
      : `<ellipse cx="${ex[0]}" cy="42" rx="3.2" ry="4.2" fill="${FACE}"/><ellipse cx="${ex[1]}" cy="42" rx="3.2" ry="4.2" fill="${FACE}"/><circle cx="${ex[0] + 1}" cy="40.5" r="1.1" fill="${FACE === INK ? '#fff' : '#262D66'}"/><circle cx="${ex[1] + 1}" cy="40.5" r="1.1" fill="${FACE === INK ? '#fff' : '#262D66'}"/>`;
  const mx = (ex[0] + ex[1]) / 2;
  const mouth = mood === 'worried'
    ? `<path d="M${mx - 5} 56 q5 -4 10 0" stroke="${FACE}" stroke-width="2.6" stroke-linecap="round" fill="none"/>`
    : mood === 'cheer'
      ? `<path d="M${mx - 6} 52 q6 9 12 0 z" fill="${FACE}"/><path d="M${mx - 3} 56 q3 2 6 0" stroke="#F06A6A" stroke-width="2" stroke-linecap="round"/>`
      : `<path d="M${mx - 5} 53 q5 5 10 0" stroke="${FACE}" stroke-width="2.6" stroke-linecap="round" fill="none"/>`;
  return eyes + mouth;
}

// Limbs per pose: [arm path, arm path, leg path, leg path] for the left half; mirrored for the right.
const POSES = {
  stand: ['M8 56 q-8 10 -4 22', 'M40 66 q8 8 10 18', 'M30 86 l-4 22', 'M42 96 l2 14'],
  wave: ['M8 52 q-14 -8 -12 -26', 'M40 66 q8 8 10 18', 'M30 86 l-4 22', 'M42 96 l2 14'],
  cheer: ['M8 46 q-12 -14 -6 -32', 'M44 30 q10 -14 6 -30', 'M30 86 l-8 20', 'M42 96 l6 14'],
  hug: ['M8 56 q-8 10 -4 22', 'M40 62 q14 2 26 -4', 'M30 86 l-4 22', 'M42 96 l2 14'],
  rest: ['M8 56 q-10 4 -10 14', 'M40 66 q6 6 6 12', 'M30 86 q-6 10 -16 12', 'M42 96 q-2 8 -10 12'],
};

function half(who, pose, mood, mirror) {
  const c = COLORS[who];
  const t = mirror ? 'transform="translate(100 0) scale(-1 1)"' : '';
  const [a1, a2, l1, l2] = POSES[pose] || POSES.stand;
  const shoe = (x, y) => `<ellipse cx="${x}" cy="${y}" rx="6.5" ry="4" fill="${INK}"/>`;
  const foot = (p) => { const m = p.match(/l(-?\d+) (-?\d+)$|q-?\d+ -?\d+ (-?\d+) (-?\d+)$/); return p; };
  const end = (p, x0, y0) => { const n = p.match(/-?\d+(\.\d+)?/g).map(Number); return [x0 + n[n.length - 2], y0 + n[n.length - 1]]; };
  const [f1x, f1y] = end(l1, 30, 86), [f2x, f2y] = end(l2, 42, 96);
  return `<g ${t}>
    <g stroke="${INK}" stroke-width="4.2" stroke-linecap="round" fill="none"><path d="${a1}"/><path d="${a2}"/><path d="${l1}"/><path d="${l2}"/></g>
    ${shoe(f1x - 1, f1y + 2)}${shoe(f2x + 1, f2y + 2)}
    <path d="${HALF}" fill="${c.body}"/>
    <path d="M50 20 C 46 10 38 4 29 4 C 22 4 16 7 12 12 C 20 9 34 10 42 24 C 46 30 48 34 50 36 Z" fill="#fff" opacity=".14"/>
    <path d="M50 104 C 40 93 30 83 22 72 C 32 80 42 86 50 88 Z" fill="${c.shade}"/>
    <path d="M46.5 26 L46.5 98" stroke="#fff" stroke-width="2.4" stroke-dasharray="5 5" stroke-linecap="round" opacity=".6"/>
    <ellipse cx="18" cy="52" rx="5" ry="3.2" fill="${c.blush}" opacity=".75"/><ellipse cx="44" cy="52" rx="4" ry="3" fill="${c.blush}" opacity=".75"/>
  </g>${mirror ? `<g transform="translate(100 0) scale(-1 1)"><g transform="translate(100 0) scale(-1 1)">${face(mood, true, c.face)}</g></g>` : face(mood, false, c.face)}`;
}

function svg({ who = 'maya', pose = 'stand', size = 120, sticker = false, mood = 'smile', gap = 0 } = {}) {
  let inner, w;
  if (who === 'both') {
    inner = `<g>${half('maya', pose === 'hug' ? 'hug' : pose, mood, false)}</g><g transform="translate(${gap} 0)">${half('theo', pose === 'hug' ? 'hug' : pose, mood, true)}</g>`;
    w = 100 + gap;
  } else { inner = half(who, pose, mood, who === 'theo'); w = 100; }
  const vb = `-22 -36 ${w + 44} 166`;
  const keyline = sticker ? `<filter id="k${who}${pose}${size}" filterUnits="userSpaceOnUse" x="-40" y="-60" width="${w + 80}" height="230"><feMorphology in="SourceAlpha" operator="dilate" radius="6" result="d"/><feFlood flood-color="#fff"/><feComposite in2="d" operator="in" result="w"/><feGaussianBlur in="d" stdDeviation="5" result="b"/><feOffset in="b" dy="5" result="o"/><feFlood flood-color="#1E2350" flood-opacity=".24"/><feComposite in2="o" operator="in" result="s"/><feMerge><feMergeNode in="s"/><feMergeNode in="w"/><feMergeNode in="SourceGraphic"/></feMerge></filter>` : '';
  return `<svg viewBox="${vb}" width="${size * (w + 44) / 144}" height="${size * 166 / 144}" xmlns="http://www.w3.org/2000/svg" fill="none">${keyline ? `<defs>${keyline}</defs>` : ''}<g ${sticker ? `filter="url(#k${who}${pose}${size})"` : ''}>${inner}</g></svg>`;
}
module.exports = { svg, COLORS, INK };

if (require.main === module) {
  const fs = require('fs');
  const cells = [['maya', 'stand'], ['theo', 'stand'], ['maya', 'wave'], ['theo', 'cheer'], ['both', 'hug'], ['both', 'cheer'], ['maya', 'rest', 'sleep'], ['theo', 'stand', 'worried']]
    .map(([w, p, m]) => `<div>${svg({ who: w, pose: p, mood: m || (p === 'cheer' ? 'cheer' : 'smile'), size: 150, sticker: true })}<p>${w} · ${p}</p></div>`).join('');
  fs.writeFileSync(process.argv[2] || 'mascots.html', `<body style="margin:0;padding:30px;background:#E3E5F6;display:flex;flex-wrap:wrap;gap:28px;font:14px system-ui;width:1100px">${cells}</body>`);
}
