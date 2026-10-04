#!/usr/bin/env python3
"""Maquette interactive de parcours (page HTML autonome, images incluses). Usage : build_mock.py sortie.html"""
import base64, io, json, os, sys
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from content import SEQS, SUBTITLE, TITLE  # noqa: E402

plan = json.load(open(os.path.join(HERE, "plan.json"), encoding="utf-8"))
IMG = os.path.join(HERE, "images")


def data_uri(path, max_side, q):
    im = Image.open(path).convert("RGB")
    im.thumbnail((max_side, max_side))
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=q, optimize=True, progressive=True)
    return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode(), im.size


works = {}
for w in plan["works"]:
    uri, size = data_uri(os.path.join(IMG, w["id"] + ".jpg"), 1300, 76)
    works[w["id"]] = {"artist": w["artist"], "title": w["title"], "date": w["date"], "aspect": w["w"] / w["h"], "src": uri, "crops": {}}
for c in plan["crops"]:
    p = os.path.join(IMG, f"{c['work']}-{c['id']}.jpg")
    if os.path.exists(p):
        uri, _ = data_uri(p, 1000, 78)
        works[c["work"]]["crops"][c["id"]] = {"x": c["x"], "y": c["y"], "w": c["w"], "h": c["h"], "src": uri}

DATA = json.dumps({"works": works, "seqs": SEQS, "title": TITLE}, ensure_ascii=False)

PAGE = r"""<title>Parcours Monet Gauguin</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Bodoni+Moda:ital,opsz,wght@0,6..96,500;1,6..96,500&family=Source+Sans+3:wght@400;600&family=IBM+Plex+Mono:wght@400;500&display=swap">
<style>
/* Salle sombre de musée : l'image occupe la scène, le texte se lit à côté (dessous sur téléphone). Un seul thème, volontairement sombre. */
:root{
  --room:#121110; --wall:#1b1a18; --line:#34312c; --ink:#ece6da; --mute:#a59d8f; --brass:#d6ad62; --graph:#7fb3d5; --hist:#d6ad62;
  --display:"Bodoni Moda", Didot, "Times New Roman", serif; --body:"Source Sans 3", "Segoe UI", system-ui, sans-serif; --mono:"IBM Plex Mono", ui-monospace, monospace;
  color-scheme:dark;
}
html,body{height:100%}
body{background:var(--room);color:var(--ink);font-family:var(--body);font-size:16px;line-height:1.5;margin:0}
.app{height:100%;display:grid;grid-template-rows:auto minmax(0,1fr);}
header{display:flex;flex-wrap:wrap;align-items:center;gap:8px 16px;padding-block:10px;padding-inline:16px;border-bottom:1px solid var(--line);background:var(--wall)}
header h1{font-family:var(--display);font-weight:500;font-size:20px;margin:0;text-wrap:balance}
.crumb{font-family:var(--mono);font-size:12px;color:var(--mute)}
.crumb b{color:var(--brass);font-weight:500}
.tools{margin-left:auto;display:flex;flex-wrap:wrap;gap:8px;align-items:center}
.seg{display:inline-flex;border:1px solid var(--line);border-radius:8px;overflow:hidden}
.seg button{background:none;border:0;color:var(--mute);font:500 12px var(--mono);padding:6px 9px;cursor:pointer}
.seg button[aria-pressed="true"]{background:var(--brass);color:var(--room)}
.seg button:focus-visible,.btn:focus-visible,.chip:focus-visible,.dot:focus-visible{outline:2px solid var(--brass);outline-offset:2px}
.label{font:500 11px var(--mono);letter-spacing:.06em;text-transform:uppercase;color:var(--mute)}
main{display:grid;grid-template-columns:minmax(0,1fr) minmax(300px,400px);min-height:0}
.stage{position:relative;overflow:hidden;background:#0b0a09;min-height:0}
.layer{position:absolute;inset:0;transition:opacity .7s ease, transform .9s cubic-bezier(.2,.7,.2,1)}
.wrap{position:absolute;transform-origin:0 0;transition:transform 1.25s cubic-bezier(.25,.8,.25,1)}
.wrap img{position:absolute;display:block;user-select:none;-webkit-user-drag:none}
.wrap img.full{inset:0;width:100%;height:100%}
.wrap img.crop{opacity:0;transition:opacity .5s ease .9s}
.wrap img.crop.on{opacity:1}
.layer.hist .wrap{filter:brightness(.42) saturate(.8)}
.layer.trans .wrap{filter:brightness(.35) blur(2px)}
.over{position:absolute;inset:auto 0 0 0;padding:28px 20px 22px;background:linear-gradient(transparent,rgba(10,9,8,.85));pointer-events:none}
.over.center{inset:0;display:grid;place-items:center;text-align:center;background:none}
.over .big{font-family:var(--display);font-size:clamp(22px,4vw,40px);line-height:1.15;max-width:22ch;text-wrap:balance}
.badge{display:inline-block;font:500 11px var(--mono);letter-spacing:.06em;text-transform:uppercase;padding:3px 8px;border-radius:20px;border:1px solid currentColor}
.badge.g{color:var(--graph)} .badge.h{color:var(--hist)} .badge.t{color:var(--mute)}
.depth{position:absolute;top:12px;left:12px;display:flex;gap:6px;align-items:center;font:500 11px var(--mono);color:var(--ink);background:rgba(10,9,8,.6);padding:5px 9px;border-radius:20px}
.depth i{width:8px;height:8px;border-radius:50%;border:1px solid var(--graph);display:inline-block}
.depth i.on{background:var(--graph)}
aside{border-left:1px solid var(--line);background:var(--wall);display:flex;flex-direction:column;min-height:0}
.text{padding:18px 20px;overflow:auto;min-height:0;flex:1}
.text h2{font-family:var(--display);font-weight:500;font-size:26px;line-height:1.15;margin:8px 0 2px;text-wrap:balance}
.text .who{color:var(--mute);font-size:14px;margin:0 0 12px}
.text p.body{margin:0;max-width:62ch;font-size:16.5px}
.text .lvl1 p.body{font-family:var(--display);font-size:22px;line-height:1.3}
.chips{display:flex;flex-wrap:wrap;gap:8px;margin-top:16px}
.chip{background:none;border:1px solid var(--brass);color:var(--ink);border-radius:10px;padding:8px 11px;text-align:left;cursor:pointer;font:inherit;font-size:14px}
.chip small{display:block;color:var(--mute);font:400 11px var(--mono)}
.branchnote{margin-top:16px;font:500 11px var(--mono);color:var(--brass);letter-spacing:.05em;text-transform:uppercase}
.controls{border-top:1px solid var(--line);padding:10px 16px;display:flex;flex-wrap:wrap;gap:10px;align-items:center}
.btn{background:var(--ink);color:var(--room);border:0;border-radius:10px;padding:10px 14px;font:600 14px var(--body);cursor:pointer}
.btn.ghost{background:none;color:var(--ink);border:1px solid var(--line)}
.btn[disabled]{opacity:.35;cursor:default}
.progress{display:flex;gap:4px;flex-wrap:wrap;align-items:center;padding:0 16px 10px}
.dot{width:14px;height:6px;border-radius:3px;border:0;padding:0;cursor:pointer;background:var(--line)}
.dot.g{background:color-mix(in srgb,var(--graph) 35%,var(--line))} .dot.h{background:color-mix(in srgb,var(--hist) 35%,var(--line))}
.dot.cur{outline:2px solid var(--ink);outline-offset:1px} .dot.seen.g{background:var(--graph)} .dot.seen.h{background:var(--hist)}
.dot.br{position:relative} .dot.br::after{content:"";position:absolute;left:50%;top:-6px;width:4px;height:4px;margin-left:-2px;border-radius:50%;background:var(--brass)}
.meta{font:12px var(--mono);color:var(--mute);margin-left:auto;font-variant-numeric:tabular-nums}
dialog{background:var(--wall);color:var(--ink);border:1px solid var(--line);border-radius:14px;max-width:min(1100px,94vw);width:100%;padding:0}
dialog::backdrop{background:rgba(0,0,0,.6)}
.dlg-h{display:flex;align-items:center;gap:10px;padding:14px 18px;border-bottom:1px solid var(--line)}
.dlg-h h3{margin:0;font-family:var(--display);font-weight:500;font-size:20px}
.dlg-h .btn{margin-left:auto}
.cmp{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;padding:16px 18px;max-height:70vh;overflow:auto}
.cmp section{min-width:0} .cmp p{margin:6px 0 0} .cmp .stat{font:12px var(--mono);color:var(--mute)}
.log{padding:12px 18px;max-height:65vh;overflow:auto;font:13px var(--mono)}
.log table{border-collapse:collapse;width:100%} .log td,.log th{padding:4px 8px;border-bottom:1px solid var(--line);text-align:left;font-variant-numeric:tabular-nums}
.log .sum{padding:10px 0;color:var(--brass)}
.note{font-size:12px;color:var(--mute);padding:0 16px 10px}
@media (max-width:820px){
  .app{height:auto;min-height:100%}
  main{grid-template-columns:minmax(0,1fr);grid-template-rows:min(60vh,520px) auto}
  aside{border-left:0;border-top:1px solid var(--line)}
  .text{overflow:visible}
  header .btn{padding:6px 10px;font-size:13px}
  .tools{margin-left:0}
  .cmp{grid-template-columns:minmax(0,1fr)}
}
@media (prefers-reduced-motion:reduce){.wrap,.layer,.wrap img.crop{transition:none!important}}
</style>
<div class="app">
  <header>
    <h1 id="ttl"></h1>
    <span class="crumb" id="crumb"></span>
    <div class="tools">
      <span class="label">Détail</span>
      <div class="seg" id="lvl"><button data-v="1">1</button><button data-v="2">2</button><button data-v="3">3</button></div>
      <span class="label">Transition</span>
      <div class="seg" id="tr"><button data-v="fondu">Fondu</button><button data-v="zoom">Zoom</button><button data-v="glisse">Glissé</button></div>
      <button class="btn ghost" id="cmpBtn" type="button">Comparer les niveaux</button>
      <button class="btn ghost" id="logBtn" type="button">Ce que l'appli apprend</button>
    </div>
  </header>
  <main>
    <div class="stage" id="stage" aria-live="polite"></div>
    <aside>
      <div class="text" id="text"></div>
      <div class="progress" id="prog"></div>
      <div class="controls">
        <button class="btn ghost" id="prev" type="button">‹ Précédent</button>
        <button class="btn" id="next" type="button">Suivant ›</button>
        <button class="btn ghost" id="play" type="button">▷ Lecture</button>
        <span class="meta" id="meta"></span>
      </div>
      <p class="note">Maquette : textes de démonstration écrits pour tester la fluidité, non vérifiés. Images : National Gallery of Art, Washington (CC0). Touches ← → et balayage acceptés.</p>
    </aside>
  </main>
</div>
<dialog id="cmp"><div class="dlg-h"><h3>Les trois niveaux pour cette étape</h3><button class="btn" id="cmpClose" type="button">Fermer</button></div><div class="cmp" id="cmpBody"></div></dialog>
<dialog id="log"><div class="dlg-h"><h3>Ce que l'appli apprend de vous</h3><button class="btn" id="logClose" type="button">Fermer</button></div><div class="log" id="logBody"></div></dialog>
<script>
const D = __DATA__;
const $ = s => document.querySelector(s);
const stage = $('#stage');
const S = {stack:[{seq:'main', i:0}], stop:0, level:1, tr:'zoom', playing:false, timer:null, seen:new Set(), log:[], enter:performance.now(), maxStop:0, lvlUsed:new Set([1]), branches:[]};
try{ const v = localStorage.getItem('pc-level'); if(v) S.level = +v; const t = localStorage.getItem('pc-tr'); if(t) S.tr = t; }catch(e){}

const cur = () => S.stack[S.stack.length-1];
const seq = () => D.seqs[cur().seq];
const step = () => seq().steps[cur().i];
const work = id => D.works[id];
const words = t => t.trim().split(/\s+/).length;
const readSec = t => Math.max(3, Math.round(words(t)/3.3));     // ~200 mots / minute
const stopsOf = st => st.type==='g' ? st.stops : [{crop: st.bg || null}];

function fit(w){
  const r = stage.getBoundingClientRect(), a = w.aspect;
  let dw = Math.min(r.width, r.height*a), dh = dw/a;
  return {sw:r.width, sh:r.height, dw, dh, left:(r.width-dw)/2, top:(r.height-dh)/2};
}
function transformFor(f, crop){
  if(!crop) return 'translate(0px,0px) scale(1)';
  const s = Math.min(f.sw/(crop.w*f.dw), f.sh/(crop.h*f.dh));
  const cx = (crop.x+crop.w/2)*f.dw, cy = (crop.y+crop.h/2)*f.dh;
  return `translate(${f.sw/2 - f.left - s*cx}px,${f.sh/2 - f.top - s*cy}px) scale(${s})`;
}
function buildLayer(st){
  const w = work(st.work), L = document.createElement('div');
  L.className = 'layer ' + ({g:'graph', h:'hist', t:'trans', end:'trans', back:'trans'}[st.type]);
  const f = fit(w), wrap = document.createElement('div');
  wrap.className = 'wrap';
  Object.assign(wrap.style, {left:f.left+'px', top:f.top+'px', width:f.dw+'px', height:f.dh+'px'});
  const full = new Image(); full.className='full'; full.src = w.src; full.alt = w.title; wrap.appendChild(full);
  for(const [id,c] of Object.entries(w.crops)){
    const im = new Image(); im.className='crop'; im.dataset.id=id; im.src=c.src; im.alt='';
    Object.assign(im.style,{left:(c.x*100)+'%', top:(c.y*100)+'%', width:(c.w*100)+'%', height:(c.h*100)+'%'});
    wrap.appendChild(im);
  }
  L.appendChild(wrap);
  if(st.type==='g'){
    const d = document.createElement('div'); d.className='depth'; L.appendChild(d);
  }
  if(st.type!=='g'){
    const o = document.createElement('div'); o.className='over center';
    o.innerHTML = `<div><span class="badge ${st.type==='h'?'h':'t'}">${st.type==='h'?'Histoire':'Transition'}</span><div class="big" style="margin-top:10px">${st.title}</div></div>`;
    L.appendChild(o);
  }
  return L;
}
function applyStop(L, st, stopIdx){
  const w = work(st.work), stops = stopsOf(st), cropId = stops[stopIdx].crop;
  const crop = cropId ? w.crops[cropId] : null, f = fit(w), wrap = L.querySelector('.wrap');
  Object.assign(wrap.style, {left:f.left+'px', top:f.top+'px', width:f.dw+'px', height:f.dh+'px'});
  wrap.style.transform = transformFor(f, crop);
  // un détail reste affiché sous le suivant : on ne voit jamais l'image basse définition au fond d'un zoom
  const order = stops.map(s=>s.crop).filter(Boolean);
  const upto = cropId ? order.slice(0, order.indexOf(cropId)+1) : [];
  wrap.querySelectorAll('img.crop').forEach(im => im.classList.toggle('on', upto.includes(im.dataset.id)));
  const d = L.querySelector('.depth');
  if(d) d.innerHTML = 'Zoom ' + stops.map((_,k)=>`<i class="${k<=stopIdx?'on':''}"></i>`).join('') + (cropId ? ` ×${Math.round(1/(crop.w))}` : ' entier');
}
let layer = null;
function show(direction){
  const st = step(), L = buildLayer(st);
  const old = layer; layer = L;
  const kind = S.tr, dir = direction || 1;
  if(old){
    if(kind==='fondu'){ L.style.opacity=0; }
    else if(kind==='zoom'){ L.style.opacity=0; L.style.transform='scale(.94)'; }
    else { L.style.transform=`translateX(${dir*100}%)`; }
  }
  stage.appendChild(L);
  applyStop(L, st, S.stop);
  requestAnimationFrame(()=>requestAnimationFrame(()=>{
    L.style.opacity=1; L.style.transform='none';
    if(old){
      if(kind==='fondu') old.style.opacity=0;
      else if(kind==='zoom'){ old.style.opacity=0; old.style.transform='scale(1.08)'; }
      else old.style.transform=`translateX(${-dir*100}%)`;
      setTimeout(()=>old.remove(), 950);
    }
  }));
  renderText();
}
function textFor(st, stopIdx, lvl){
  if(st.type==='g') return st.stops[stopIdx].cap[lvl];
  return st.text[lvl];
}
function renderText(){
  const st = step(), w = work(st.work), lvl = String(S.level);
  const typeLbl = {g:['g','Wow graphique'], h:['h','Histoire'], t:['t','Transition'], end:['t','Fin'], back:['t','Retour']}[st.type];
  let html = `<span class="badge ${typeLbl[0]}">${typeLbl[1]}</span><h2>${st.title}</h2>`;
  if(st.type==='g') html += `<p class="who">${w.artist}, ${w.date}</p>`;
  html += `<div class="lvl${lvl}"><p class="body">${textFor(st, S.stop, lvl)}</p></div>`;
  if(st.type==='g' && S.stop < st.stops.length-1) html += `<p class="branchnote">▸ Encore ${st.stops.length-1-S.stop} niveau(x) de zoom dans ce tableau</p>`;
  if(st.branches){
    html += `<p class="branchnote">Ici, vous pouvez ouvrir un sous-parcours</p><div class="chips">` +
      st.branches.map(b=>`<button class="chip" type="button" data-seq="${b.seq}">${b.label}<small>${b.hint}</small></button>`).join('') + `</div>`;
  }
  if(st.type==='back') html += `<div class="chips"><button class="chip" type="button" data-back="1">Reprendre le parcours principal<small>${D.seqs.main.label}</small></button></div>`;
  $('#text').innerHTML = html;
  $('#text').querySelectorAll('[data-seq]').forEach(b=>b.onclick=()=>enterBranch(b.dataset.seq));
  $('#text').querySelectorAll('[data-back]').forEach(b=>b.onclick=()=>goBack());
  $('#text').scrollTop = 0;
  // fil d'Ariane, progression, durée
  $('#crumb').innerHTML = S.stack.map((f,k)=> k===S.stack.length-1 ? `<b>${D.seqs[f.seq].label}</b>` : D.seqs[f.seq].label).join(' › ');
  const steps = seq().steps;
  $('#prog').innerHTML = steps.map((s,k)=>`<button class="dot ${s.type==='g'?'g':(s.type==='h'?'h':'')} ${k===cur().i?'cur':''} ${S.seen.has(cur().seq+':'+k)?'seen':''} ${s.branches?'br':''}" data-k="${k}" type="button" aria-label="Étape ${k+1} : ${s.title}"></button>`).join('');
  $('#prog').querySelectorAll('.dot').forEach(b=>b.onclick=()=>jump(+b.dataset.k));
  const total = steps.reduce((t,s)=> t + (s.type==='g' ? s.stops.reduce((u,p)=>u+readSec(p.cap[lvl])+2,0) : readSec(s.text[lvl])), 0);
  $('#meta').textContent = `étape ${cur().i+1}/${steps.length} · ~${Math.round(total/60*10)/10} min au niveau ${lvl}`;
  $('#prev').disabled = cur().i===0 && S.stop===0 && S.stack.length===1;
  $('#next').disabled = cur().i===steps.length-1 && (step().type!=='g' || S.stop===step().stops.length-1) && S.stack.length===1;
  S.seen.add(cur().seq+':'+cur().i);
}
function record(){
  const st = step(), now = performance.now();
  S.log.push({seq:cur().seq, title:st.title, type:st.type, secs:Math.round((now-S.enter)/1000), zoom:st.type==='g'?S.maxStop:'–', level:S.level});
  S.enter = now; S.maxStop = 0;
}
function next(){
  const st = step();
  if(st.type==='g' && S.stop < st.stops.length-1){ S.stop++; S.maxStop=Math.max(S.maxStop,S.stop); applyStop(layer, st, S.stop); renderText(); return; }
  if(cur().i < seq().steps.length-1){ record(); cur().i++; S.stop=0; show(1); return; }
  if(S.stack.length>1){ goBack(); return; }
  stopPlay();
}
function prev(){
  const st = step();
  if(st.type==='g' && S.stop>0){ S.stop--; applyStop(layer, st, S.stop); renderText(); return; }
  if(cur().i>0){ record(); cur().i--; S.stop=0; show(-1); return; }
  if(S.stack.length>1) goBack();
}
function jump(k){ record(); const d = k>cur().i?1:-1; cur().i=k; S.stop=0; show(d); }
function enterBranch(id){ record(); S.branches.push(D.seqs[id].label); S.stack.push({seq:id, i:0}); S.stop=0; show(1); }
function goBack(){ record(); S.stack.pop(); if(cur().i < seq().steps.length-1) cur().i++; S.stop=0; show(-1); }
function dwell(){
  const st = step(), t = textFor(st, S.stop, String(S.level));
  return (readSec(t) + (st.type==='g' && S.stop>0 ? 2.5 : 1)) * 1000;
}
function loop(){ if(!S.playing) return; S.timer = setTimeout(()=>{ next(); loop(); }, dwell()); }
function stopPlay(){ S.playing=false; clearTimeout(S.timer); $('#play').textContent='▷ Lecture'; }
$('#play').onclick = ()=>{ if(S.playing) stopPlay(); else { S.playing=true; $('#play').textContent='❚❚ Pause'; loop(); } };
$('#next').onclick = ()=>{ stopPlay(); next(); };
$('#prev').onclick = ()=>{ stopPlay(); prev(); };
document.addEventListener('keydown', e=>{ if(document.querySelector('dialog[open]')) return; if(e.key==='ArrowRight'){stopPlay();next();} if(e.key==='ArrowLeft'){stopPlay();prev();} });
let tx=null; stage.addEventListener('touchstart',e=>{tx=e.touches[0].clientX},{passive:true});
stage.addEventListener('touchend',e=>{ if(tx==null) return; const dx=e.changedTouches[0].clientX-tx; tx=null; if(Math.abs(dx)>40){stopPlay(); dx<0?next():prev();} });
stage.addEventListener('click',()=>{ stopPlay(); next(); });
function seg(id, key, cb){
  const el = $(id);
  const paint = ()=>el.querySelectorAll('button').forEach(b=>b.setAttribute('aria-pressed', String(b.dataset.v==String(S[key]))));
  el.querySelectorAll('button').forEach(b=>b.onclick=()=>{ S[key] = key==='level'? +b.dataset.v : b.dataset.v; paint(); cb(); try{localStorage.setItem(key==='level'?'pc-level':'pc-tr', S[key]);}catch(e){} });
  paint();
}
seg('#lvl','level',()=>{ S.lvlUsed.add(S.level); renderText(); });
seg('#tr','tr',()=>{});
$('#cmpBtn').onclick = ()=>{
  const st = step();
  $('#cmpBody').innerHTML = ['1','2','3'].map(l=>{ const t = textFor(st, S.stop, l); return `<section><span class="badge ${st.type==='h'?'h':(st.type==='g'?'g':'t')}">Niveau ${l}</span><p class="stat">${words(t)} mots · ~${readSec(t)} s de lecture</p><p>${t}</p></section>`; }).join('');
  $('#cmp').showModal();
};
$('#cmpClose').onclick = ()=>$('#cmp').close();
$('#logBtn').onclick = ()=>{
  const rows = S.log.slice(-40).map(r=>`<tr><td>${r.title}</td><td>${{g:'graphique',h:'histoire',t:'transition',end:'fin',back:'retour'}[r.type]}</td><td>${r.secs} s</td><td>${r.zoom}</td><td>${r.level}</td></tr>`).join('');
  const g = S.log.filter(r=>r.type==='g'), h = S.log.filter(r=>r.type==='h');
  const avg = a => a.length ? Math.round(a.reduce((s,r)=>s+r.secs,0)/a.length) : 0;
  const deep = g.length ? Math.round(100*g.filter(r=>r.zoom>=2).length/g.length) : 0;
  $('#logBody').innerHTML = `<p class="sum">Temps moyen : ${avg(g)} s sur un tableau, ${avg(h)} s sur un texte d'histoire · zoom au plus profond : ${deep} % des tableaux · niveaux utilisés : ${[...S.lvlUsed].sort().join(', ')} · sous-parcours ouverts : ${S.branches.length ? S.branches.join(', ') : 'aucun'}</p>
  <p>Dans l'appli, ces signaux nourriraient le profil (dosage graphique / histoire, profondeur de zoom, niveau de texte) sans aucune question. Rien n'est envoyé : ce journal vit dans cette page.</p>
  <table><tr><th>étape</th><th>type</th><th>temps</th><th>zoom atteint</th><th>niveau</th></tr>${rows || '<tr><td colspan="5">Parcourez quelques étapes pour voir apparaître les signaux.</td></tr>'}</table>`;
  $('#log').showModal();
};
$('#logClose').onclick = ()=>$('#log').close();
let rz; addEventListener('resize', ()=>{ clearTimeout(rz); rz=setTimeout(()=>{ if(layer){ layer.querySelector('.wrap').style.transition='none'; applyStop(layer, step(), S.stop); requestAnimationFrame(()=>layer.querySelector('.wrap').style.transition=''); } },120); });
$('#ttl').textContent = D.title;
show(1);
</script>
"""
out = sys.argv[1]
html = PAGE.replace("__DATA__", DATA)
open(out, "w", encoding="utf-8").write(html)
print(out, len(html) // 1024, "Ko", sum(len(w["crops"]) for w in works.values()), "détails")
