// Renders every screens/*.html body fragment inside the phone frame and screenshots it to png/.
const fs=require('fs'),path=require('path');
const { chromium } = require('/opt/node-tools/node_modules/playwright');
const dir=__dirname, css=fs.readFileSync(path.join(dir,'styles.css'),'utf8');
const ICON={
 home:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 11l9-8 9 8v9a2 2 0 0 1-2 2h-4v-6H9v6H5a2 2 0 0 1-2-2z"/></svg>',
 goals:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1.2" fill="currentColor"/></svg>',
 snap:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2"/><circle cx="12" cy="12" r="3.2"/></svg>',
 cycle:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 21s-7-4.6-7-10.2A4.2 4.2 0 0 1 12 8a4.2 4.2 0 0 1 7 2.8C19 16.4 12 21 12 21z"/></svg>',
 us:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="9" cy="8" r="3.5"/><circle cx="17" cy="9" r="2.6"/><path d="M2.5 20c0-3.6 3-6 6.5-6s6.5 2.4 6.5 6M15.5 14.4c3 0 6 1.9 6 5.6"/></svg>'
};
const status=(dark)=>`<div class="status"${dark?' style="color:#fff"':''}><span>9:41</span><span class="r">
<svg viewBox="0 0 18 12" fill="currentColor"><rect x="0" y="8" width="3" height="4" rx=".8"/><rect x="5" y="5.5" width="3" height="6.5" rx=".8"/><rect x="10" y="3" width="3" height="9" rx=".8"/><rect x="15" y="0" width="3" height="12" rx=".8"/></svg>
<svg viewBox="0 0 16 12" fill="currentColor"><path d="M8 2.6c2.4 0 4.6.9 6.3 2.4l-1.3 1.4A7 7 0 0 0 8 4.6a7 7 0 0 0-5 1.8L1.7 5A9.1 9.1 0 0 1 8 2.6zm0 3.6c1.5 0 2.8.6 3.8 1.5l-1.3 1.4A3.5 3.5 0 0 0 8 8.2c-.9 0-1.8.4-2.5.9L4.2 7.7A5.4 5.4 0 0 1 8 6.2zm0 3.6c.6 0 1.1.2 1.5.6L8 12l-1.5-1.6c.4-.4.9-.6 1.5-.6z"/></svg>
<svg viewBox="0 0 27 12" fill="none" stroke="currentColor"><rect x=".5" y=".5" width="22" height="11" rx="3.5" opacity=".4"/><rect x="2" y="2" width="17" height="8" rx="2" fill="currentColor" stroke="none"/><path d="M24.5 4v4" stroke-width="1.5" opacity=".4"/></svg></span></div>`;
const tabbar=(on)=>`<div class="tabbar">
<div class="tab${on==='home'?' on':''}">${ICON.home}Home</div>
<div class="tab${on==='goals'?' on':''}">${ICON.goals}Goals</div>
<div class="tab fab"><div class="fabc">${ICON.snap}</div></div>
<div class="tab${on==='cycle'?' on':''}">${ICON.cycle}Cycle</div>
<div class="tab${on==='us'?' on':''}">${ICON.us}Us</div></div><div class="homebar"></div>`;
(async()=>{
  const b=await chromium.launch();
  const files=fs.readdirSync(path.join(dir,'screens')).filter(f=>f.endsWith('.html')).sort();
  const only=process.argv[2];
  for(const f of files){
    if(only && !f.includes(only)) continue;
    let body=fs.readFileSync(path.join(dir,'screens',f),'utf8');
    const dark=/^<!--\s*dark/.test(body);
    const tab=(body.match(/<!--\s*tab:(\w+)/)||[])[1];
    body=body.replace('{{STATUS}}',status(dark)).replace(/\{\{TAB\}\}/,tab?tabbar(tab):'<div class="homebar"></div>');
    const html=`<!doctype html><html><head><meta charset="utf-8"><style>${css}</style></head><body><div class="phone${dark?' dark':''}">${body}</div></body></html>`;
    const p=await b.newPage({viewport:{width:390,height:844},deviceScaleFactor:2});
    await p.setContent(html); await p.waitForTimeout(80);
    const out=path.join(dir,'png',f.replace('.html','.png'));
    await p.screenshot({path:out,clip:{x:0,y:0,width:390,height:844}});
    await p.close(); console.log('rendered',out);
  }
  await b.close();
})();
