const fs=require('fs'),path=require('path');
const { chromium } = require('/opt/node-tools/node_modules/playwright');
(async()=>{
  const dir=__dirname, files=fs.readdirSync(path.join(dir,'png')).filter(f=>/^\d\d-.*\.png$/.test(f)).sort();
  const sel=process.argv.slice(2); const use=sel.length?files.filter(f=>sel.some(s=>f.startsWith(s))):files;
  const cols=parseInt(process.env.COLS||'5'), w=parseInt(process.env.W||'300'), h=Math.round(w*844/390);
  const tiles=use.map(f=>`<div style="width:${w}px"><img src="data:image/png;base64,${fs.readFileSync(path.join(dir,'png',f)).toString('base64')}" style="width:${w}px;height:${h}px;border-radius:${w/14}px;display:block;box-shadow:0 6px 20px rgba(0,0,0,.18)"><div style="font:600 ${Math.round(w/22)}px Inter;margin-top:8px;color:#333">${f.replace('.png','')}</div></div>`).join('');
  const rows=Math.ceil(use.length/cols), W=cols*(w+24)+24, H=rows*(h+60)+24;
  const html=`<body style="margin:0;background:#E9E4DD;padding:24px;display:grid;grid-template-columns:repeat(${cols},${w}px);gap:36px 24px;width:${W}px">${tiles}</body>`;
  const b=await chromium.launch(); const p=await b.newPage({viewport:{width:W,height:H},deviceScaleFactor:1});
  await p.setContent(html); await p.waitForTimeout(100);
  const out=path.join(dir,'png',process.env.OUT||'00-contact-sheet.png'); await p.screenshot({path:out,fullPage:true}); await b.close(); console.log(out);
})();
