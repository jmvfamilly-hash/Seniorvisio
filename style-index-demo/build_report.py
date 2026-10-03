#!/usr/bin/env python3
"""Page HTML autonome (images incluses) à partir de results/results.json. Usage : build_report.py [sortie.html]"""
import base64, html, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from prompts import GROUPS, QUERIES  # noqa: E402

RES = os.path.join(HERE, "results")
d = json.load(open(os.path.join(RES, "results.json"), encoding="utf-8"))
esc = html.escape
byid = {i["id"]: i for i in d["images"]}


def b64(fn):
    return "data:image/jpeg;base64," + base64.b64encode(open(os.path.join(RES, "images", fn), "rb").read()).decode()


def bars(scores, top=3):
    if not scores:
        return '<p class="na">non disponible</p>'
    items = sorted(scores.items(), key=lambda kv: -kv[1])[:top]
    return "".join(f'<div class="bar"><span>{esc(k)}</span><i style="width:{v*100:.0f}%"></i><b>{v*100:.0f} %</b></div>' for k, v in items)


def vlm_block(i):
    v = i.get("vlm")
    if not v:
        return '<p class="na">non exécuté</p>'
    j = v.get("json")
    if not j:
        return f'<p class="na">réponse non structurée ({v.get("seconds","?")} s)</p><pre>{esc(v.get("raw",""))}</pre>'
    rows = "".join(f"<tr><th>{esc(str(k))}</th><td>{esc(', '.join(map(str, val)) if isinstance(val, list) else str(val))}</td></tr>" for k, val in j.items())
    return f'<table>{rows}</table><p class="t">{v.get("seconds","?")} s sur CPU</p>'


cards = ""
for i in d["images"]:
    cols = ""
    for name, label in (("siglip", "SigLIP"), ("openclip", "OpenCLIP")):
        s = i.get(name, {})
        cols += f'<div class="col"><h4>{label}</h4>' + "".join(f"<h5>{esc(g)}</h5>{bars(s.get(g))}" for g in GROUPS) + "</div>"
    cards += f"""<section class="card"><div class="pic"><img src="{b64(i['file'])}" alt="{esc(i['title'])}"><p>Recherche : <b>{esc(i['expected'])}</b><br><a href="{esc(i['page'])}">{esc(i['title'])}</a><br>{esc(i.get('license',''))}</p></div>
<div class="cols">{cols}<div class="col wide"><h4>Modèle de vision-langage</h4>{vlm_block(i)}</div></div></section>"""

qhtml = ""
for fr, _ in QUERIES:
    q = d.get("queries", {}).get(fr, {})
    cells = ""
    for name, label in (("siglip", "SigLIP"), ("openclip", "OpenCLIP")):
        ranked = q.get(name, [])[:3]
        cells += f"<div><h5>{label}</h5>" + "".join(f'<figure><img src="{b64(byid[r["id"]]["file"])}"><figcaption>{r["score"]:.3f}</figcaption></figure>' for r in ranked if r["id"] in byid) + "</div>"
    qhtml += f'<div class="q"><h4>« {esc(fr)} »</h4><div class="qr">{cells}</div></div>'

m = d.get("models", {})
mrows = "".join(
    f"<tr><td>{esc(k)}</td><td>{esc(v.get('id',''))}</td><td>{v.get('params_m','?')} M</td><td>{v.get('load_seconds','?')} s</td><td>{v.get('image_seconds_per_image','')}</td></tr>"
    for k, v in m.items()
)
errs = "".join(f"<li>{esc(k)} : {esc(v)}</li>" for k, v in d.get("errors", {}).items())
page = f"""<title>Démo style Gauguin</title><style>
:root{{--bg:#0f1114;--fg:#e6e9ed;--mut:#8b96a3;--gold:#f0d58a;--card:#181c21;--bar:rgba(240,213,138,.35);--track:rgba(127,127,127,.18)}}
@media (prefers-color-scheme: light){{:root:not([data-theme="dark"]){{--bg:#f6f4ee;--fg:#1c1f23;--mut:#5d6670;--gold:#7a5c0c;--card:#ffffff;--bar:rgba(138,106,16,.28);--track:rgba(127,127,127,.18);color-scheme:light}}}}
:root[data-theme="light"]{{--bg:#f6f4ee;--fg:#1c1f23;--mut:#5d6670;--gold:#7a5c0c;--card:#ffffff;--bar:rgba(138,106,16,.28);--track:rgba(127,127,127,.18);color-scheme:light}}
:root{{color-scheme:dark}}
body{{background:var(--bg);color:var(--fg);font:15px/1.45 system-ui,sans-serif;padding-block:16px;padding-inline:16px;max-width:1100px;margin-inline:auto}}
h1{{font:600 24px Georgia,serif;margin:0 0 6px;text-wrap:balance}} h2{{margin:28px 0 4px}} h4{{margin:4px 0;color:var(--gold)}} h5{{margin:8px 0 2px;color:var(--mut);font-weight:500}}
.card{{background:var(--card);border-radius:12px;padding:12px;margin:14px 0;display:grid;gap:12px;grid-template-columns:minmax(0,260px) minmax(0,1fr)}}
.pic img{{width:100%;border-radius:8px}} .pic p{{font-size:12px;color:var(--mut);overflow-wrap:anywhere}} a{{color:var(--gold)}}
.cols{{display:grid;gap:12px;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));min-width:0}} .wide{{grid-column:1/-1;min-width:0;overflow-x:auto}}
.bar{{position:relative;height:20px;margin:2px 0;background:var(--track);border-radius:4px;overflow:hidden;font-size:12px}}
.bar i{{position:absolute;inset:0 auto 0 0;background:var(--bar)}} .bar span{{position:relative;padding-left:6px;line-height:20px}} .bar b{{position:absolute;right:6px;line-height:20px;font-weight:500}}
table{{border-collapse:collapse;font-size:13px}} th{{text-align:left;color:var(--mut);padding:2px 10px 2px 0;vertical-align:top;white-space:nowrap}} td{{padding:2px 0;overflow-wrap:anywhere}}
.na{{color:var(--mut);font-style:italic}} .t{{color:var(--mut);font-size:12px}} pre{{white-space:pre-wrap;font-size:11px}}
.q{{margin:10px 0}} .qr{{display:flex;gap:24px;flex-wrap:wrap}} figure{{display:inline-block;margin:0 8px 0 0;text-align:center;font-size:11px;color:var(--mut)}} figure img{{height:110px;border-radius:6px;display:block}}
.scroll{{overflow-x:auto}}
@media (max-width:640px){{.card{{grid-template-columns:minmax(0,1fr)}}}}
</style>
<h1>Gauguin : indexer le style par SigLIP, OpenCLIP et un modèle de vision-langage</h1>
<p class="na">Résultats réels d'une exécution sur CPU (GitHub Actions) ; œuvres téléchargées depuis Wikimedia Commons. « Recherche » = ce que j'ai demandé à Commons, pas une vérité : le titre du fichier fait foi, et aucun modèle ne reçoit cette indication.</p>
<h2>1. Classification par œuvre</h2>{cards}
<h2>2. Recherche libre par le texte (les 3 œuvres les plus proches)</h2>{qhtml}
<h2>3. Modèles et durées mesurées</h2>
<div class="scroll"><table><tr><th>étape</th><th>modèle</th><th>paramètres</th><th>chargement</th><th>s / image</th></tr>{mrows}</table></div>
{('<h3>Erreurs</h3><ul>' + errs + '</ul>') if errs else ''}
"""
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(RES, "report.html")
open(out, "w", encoding="utf-8").write(page)
print(out, len(page) // 1024, "Ko")
