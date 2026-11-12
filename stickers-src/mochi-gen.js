const { chromium } = require('playwright');
const fs = require('fs');
const BODY='#FFD45C', DARK='#E8A92E', BELLY='#FFE79A', BEAK='#FFA62B', INK='#2B2118';
const eye = (x,y,k) => {
  switch(k){
  case 'normal': return `<circle cx="${x}" cy="${y}" r="24" fill="#fff"/><circle cx="${x+3}" cy="${y+3}" r="14" fill="${INK}"/><circle cx="${x+8}" cy="${y-3}" r="5" fill="#fff"/>`;
  case 'big': return `<circle cx="${x}" cy="${y}" r="28" fill="#fff"/><circle cx="${x}" cy="${y}" r="9" fill="${INK}"/><circle cx="${x+3}" cy="${y-3}" r="3" fill="#fff"/>`;
  case 'side': return `<circle cx="${x}" cy="${y}" r="24" fill="#fff"/><circle cx="${x+12}" cy="${y+2}" r="12" fill="${INK}"/><path d="M${x-26} ${y-10} L${x+26} ${y-10} L${x+26} ${y-26} L${x-26} ${y-26}Z" fill="${BODY}"/><path d="M${x-24} ${y-10} L${x+24} ${y-10}" stroke="${INK}" stroke-width="5" stroke-linecap="round"/>`;
  case 'happy': return `<path d="M${x-18} ${y+6} Q${x} ${y-20} ${x+18} ${y+6}" fill="none" stroke="${INK}" stroke-width="8" stroke-linecap="round"/>`;
  case 'closed': return `<path d="M${x-18} ${y} Q${x} ${y+14} ${x+18} ${y}" fill="none" stroke="${INK}" stroke-width="7" stroke-linecap="round"/>`;
  case 'squeeze': return `<path d="M${x-16} ${y-12} L${x+12} ${y} L${x-16} ${y+12}" fill="none" stroke="${INK}" stroke-width="8" stroke-linecap="round" stroke-linejoin="round" transform="${x>200?`rotate(180 ${x} ${y})`:''}"/>`;
  case 'heart': return `<path transform="translate(${x} ${y}) scale(1.5)" d="M0 12 C-22 -2 -14 -18 0 -8 C14 -18 22 -2 0 12Z" fill="#FF4D6D"/>`;
  case 'star': return `<path transform="translate(${x} ${y}) scale(1.4)" d="M0 -16 L4.7 -5 L16 -5 L7 2 L10 14 L0 7 L-10 14 L-7 2 L-16 -5 L-4.7 -5Z" fill="#FFD23F" stroke="${INK}" stroke-width="2"/>`;
  case 'angry': return `<circle cx="${x}" cy="${y+4}" r="20" fill="#fff"/><circle cx="${x}" cy="${y+8}" r="11" fill="${INK}"/><path d="M${x-26} ${y-12} L${x+22} ${y+(x<200?6:-26)}" transform="" stroke="${INK}" stroke-width="0"/>`;
  case 'sad': return `<circle cx="${x}" cy="${y}" r="24" fill="#fff"/><circle cx="${x}" cy="${y+6}" r="14" fill="${INK}"/><circle cx="${x+5}" cy="${y}" r="5" fill="#fff"/>`;
  }
};
const brows = k => k==='angry' ? `<path d="M132 168 L182 184" stroke="${INK}" stroke-width="9" stroke-linecap="round"/><path d="M268 168 L218 184" stroke="${INK}" stroke-width="9" stroke-linecap="round"/>`
  : k==='sad' ? `<path d="M135 170 L178 160" stroke="${INK}" stroke-width="7" stroke-linecap="round"/><path d="M265 170 L222 160" stroke="${INK}" stroke-width="7" stroke-linecap="round"/>` : '';
const beak = k => {
  switch(k){
  case 'open': return `<path d="M168 236 Q200 238 232 236 Q228 286 200 286 Q172 286 168 236Z" fill="#7A2E2E" stroke="${INK}" stroke-width="6" stroke-linejoin="round"/><path d="M182 270 Q200 258 218 270 Q210 284 200 284 Q190 284 182 270Z" fill="#FF7B8A"/>`;
  case 'small': return `<ellipse cx="200" cy="252" rx="13" ry="16" fill="#7A2E2E" stroke="${INK}" stroke-width="6"/>`;
  case 'frown': return `<path d="M178 262 Q200 240 222 262" fill="none" stroke="${INK}" stroke-width="8" stroke-linecap="round"/>`;
  default: return `<path d="M172 242 Q200 270 228 242" fill="none" stroke="${INK}" stroke-width="8" stroke-linecap="round"/>`;
  }
};
const wingL = up => up ? `<ellipse cx="78" cy="150" rx="22" ry="30" transform="rotate(-30 78 150)" fill="${BODY}" stroke="${INK}" stroke-width="6"/>` : '';
const wingR = up => up ? `<ellipse cx="322" cy="150" rx="22" ry="30" transform="rotate(30 322 150)" fill="${BODY}" stroke="${INK}" stroke-width="6"/>` : '';
const caption = t => t ? `<text x="200" y="384" text-anchor="middle" font-family="Arial Black, Arial, sans-serif" font-weight="900" font-size="${t.length>7?46:56}" fill="#fff" stroke="${INK}" stroke-width="12" stroke-linejoin="round" paint-order="stroke">${t}</text>` : '';
function bird(o){
  const [el,er] = Array.isArray(o.eyes)?o.eyes:[o.eyes||'normal',o.eyes||'normal'];
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 400" width="400" height="400">
<defs><filter id="o" x="-10%" y="-10%" width="120%" height="120%"><feMorphology in="SourceAlpha" operator="dilate" radius="7" result="d"/><feFlood flood-color="#fff"/><feComposite in2="d" operator="in" result="w"/><feGaussianBlur in="d" stdDeviation="3" result="b"/><feOffset in="b" dy="3" result="s"/><feFlood flood-color="#000" flood-opacity=".25"/><feComposite in2="s" operator="in" result="sh"/><feMerge><feMergeNode in="sh"/><feMergeNode in="w"/><feMergeNode in="SourceGraphic"/></feMerge></filter></defs>
<g filter="url(#o)" transform="translate(0 ${o.caption?-18:0}) rotate(${o.tilt||0} 200 220)">
${o.back||''}
${wingL(o.up==='l'||o.up==='both')}${wingR(o.up==='r'||o.up==='both')}
<path d="M200 96 C300 96 330 170 330 230 C330 300 276 340 200 340 C124 340 70 300 70 230 C70 170 100 96 200 96Z" fill="${BODY}" stroke="${INK}" stroke-width="7"/>
<path d="M120 300 C150 330 250 330 280 300 C300 280 318 250 322 230 C318 290 270 332 200 332 C130 332 82 290 78 230 C82 250 100 280 120 300Z" fill="${DARK}" opacity=".45"/>
<ellipse cx="140" cy="140" rx="26" ry="14" transform="rotate(-30 140 140)" fill="#fff" opacity=".7"/>
${o.blush!==false?`<ellipse cx="130" cy="240" rx="16" ry="10" fill="#FF8FA3" opacity=".7"/><ellipse cx="270" cy="240" rx="16" ry="10" fill="#FF8FA3" opacity=".7"/>`:''}
${eye(158,196,el)}${eye(242,196,er)}${brows(o.brows)}${beak(o.beak)}
${o.front||''}
</g>${caption(o.caption)}</svg>`;
}
const tear = (x,y) => `<path d="M${x} ${y} Q${x-12} ${y+22} ${x} ${y+28} Q${x+12} ${y+22} ${x} ${y}Z" fill="#7FD1FF" stroke="#fff" stroke-width="2"/>`;
const heart = (x,y,s=1,c='#FF4D6D') => `<path transform="translate(${x} ${y}) scale(${s})" d="M0 12 C-22 -2 -14 -18 0 -8 C14 -18 22 -2 0 12Z" fill="${c}"/>`;
const stickers = {
  hi: {up:'r', beak:'open', eyes:'normal', caption:'HI!'},
  gm: {up:'both', beak:'open', eyes:'happy', caption:'GM', back:`<circle cx="320" cy="80" r="40" fill="#FFD23F"/>`},
  gn: {eyes:'closed', beak:'default', caption:'GN', back:`<path d="M80 60 a40 40 0 1 0 50 60 a32 32 0 1 1 -50 -60z" fill="#FFD23F"/>`, front:`<text x="290" y="120" font-family="Arial Black" font-size="40" fill="${INK}">z</text><text x="320" y="85" font-family="Arial Black" font-size="30" fill="${INK}">z</text>`},
  love: {eyes:'heart', beak:'small', caption:'LOVE', front: heart(80,100,1.6)+heart(320,90,1.2,'#FF85A1')+heart(330,170,.9)},
  lol: {eyes:'squeeze', beak:'open', tilt:-8, caption:'LOL', front: tear(120,215)+tear(280,215)},
  sad: {eyes:'sad', brows:'sad', beak:'frown', front: tear(150,222), caption:'SAD'},
  cry: {eyes:'closed', brows:'sad', beak:'open', front:`<path d="M146 204 Q140 260 150 320" stroke="#7FD1FF" stroke-width="14" fill="none" stroke-linecap="round"/><path d="M254 204 Q260 260 250 320" stroke="#7FD1FF" stroke-width="14" fill="none" stroke-linecap="round"/>`},
  angry: {eyes:'angry', brows:'angry', beak:'frown', blush:false, caption:'GRR', front:`<g stroke="#E63946" stroke-width="8" stroke-linecap="round"><path d="M300 80 l14 14 M330 80 l-14 14 M300 110 l14 -14 M330 110 l-14 -14"/></g>`},
  wow: {eyes:'big', beak:'small', caption:'WOW', front:`<text x="300" y="120" font-family="Arial Black" font-size="70" fill="#FFD23F" stroke="${INK}" stroke-width="4">!</text>`},
  cool: {eyes:'normal', beak:'default', caption:'COOL', front:`<path d="M120 182 H280 V196 Q276 228 244 228 Q214 228 210 200 H190 Q186 228 156 228 Q124 228 120 196Z" fill="${INK}"/><path d="M136 190 l20 0" stroke="#fff" stroke-width="5" stroke-linecap="round" opacity=".6"/><path d="M220 190 l20 0" stroke="#fff" stroke-width="5" stroke-linecap="round" opacity=".6"/>`},
  wink: {eyes:['normal','happy'], beak:'open', front:`<path d="M300 110 l8 -20 l8 20 l20 8 l-20 8 l-8 20 l-8 -20 l-20 -8z" fill="#FFD23F"/>`},
  starstruck: {eyes:'star', beak:'open', caption:'OMG'},
  hmm: {eyes:'side', beak:'frown', blush:false, caption:'HMM', front:`<circle cx="300" cy="110" r="10" fill="#fff" stroke="${INK}" stroke-width="4"/><circle cx="326" cy="80" r="15" fill="#fff" stroke="${INK}" stroke-width="4"/>`},
  yes: {up:'r', eyes:'happy', beak:'open', caption:'YES!', front:`<path d="M300 96 l14 18 l28 -36" stroke="#2EC4B6" stroke-width="12" fill="none" stroke-linecap="round" stroke-linejoin="round"/>`},
  no: {eyes:'closed', brows:'angry', beak:'frown', caption:'NOPE', front:`<path d="M296 80 l40 40 M336 80 l-40 40" stroke="#E63946" stroke-width="12" stroke-linecap="round"/>`},
  party: {up:'both', eyes:'happy', beak:'open', caption:'YAY!', back:`<path d="M168 104 L200 16 L232 104Z" fill="#FF4D6D" stroke="${INK}" stroke-width="5" stroke-linejoin="round"/><circle cx="200" cy="20" r="12" fill="#FFD23F"/>`, front:`<g><rect x="70" y="80" width="14" height="8" fill="#FFD23F" transform="rotate(30 77 84)"/><rect x="320" y="70" width="14" height="8" fill="#2EC4B6" transform="rotate(-20 327 74)"/><circle cx="90" cy="150" r="6" fill="#FF4D6D"/><circle cx="330" cy="150" r="6" fill="#9B5DE5"/><rect x="300" y="40" width="12" height="7" fill="#FF4D6D"/><rect x="100" y="40" width="12" height="7" fill="#9B5DE5"/></g>`},
  thanks: {eyes:'happy', beak:'small', caption:'THANKS', front: heart(320,110,1.2)},
  sorry: {eyes:'sad', brows:'sad', beak:'small', caption:'SORRY', front:`<path d="M300 110 Q290 132 300 140 Q310 132 300 110Z" fill="#7FD1FF" stroke="#fff" stroke-width="2"/>`},
  sleepy: {eyes:'closed', beak:'small', blush:false, front:`<text x="280" y="130" font-family="Arial Black" font-size="48" fill="${INK}">Z</text><text x="320" y="90" font-family="Arial Black" font-size="34" fill="${INK}">z</text>`},
  hug: {up:'both', eyes:'happy', beak:'small', caption:'HUGS', front: heart(200,60,1.4)},
};
(async () => {
  fs.mkdirSync('out',{recursive:true});
  const b = await chromium.launch(); const p = await b.newPage({viewport:{width:400,height:400}});
  let i=0;
  for (const [n,o] of Object.entries(stickers)) {
    await p.setContent(`<html><body style="margin:0;background:transparent">${bird(o)}</body></html>`);
    await p.screenshot({path:`out/${String(i++).padStart(2,'0')}_${n}.png`, omitBackground:true, clip:{x:0,y:0,width:400,height:400}});
  }
  await b.close();
})();
