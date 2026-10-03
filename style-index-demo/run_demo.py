#!/usr/bin/env python3
"""Démonstration : SigLIP / OpenCLIP (classification et recherche par le texte) et un modèle de vision-langage (annotations JSON) sur des œuvres de Gauguin.

Étapes (chacune lit et complète style-index-demo/results/results.json) :
  fetch : télécharge les œuvres depuis Wikimedia Commons ;
  clip  : SigLIP et OpenCLIP ;
  vlm   : Qwen2.5-VL-3B (à défaut Moondream2).
Les durées mesurées sont enregistrées : elles disent ce qui est envisageable sur un téléphone ou seulement sur un serveur.
"""
import json, os, re, sys, time, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from prompts import GROUPS, QUERIES, WORKS  # noqa: E402

RES = os.path.join(HERE, "results")
IMG = os.path.join(RES, "images")
JSON = os.path.join(RES, "results.json")
UA = "StyleIndexDemo/1.0 (https://github.com/jmvfamilly-hash/Seniorvisio; démonstration)"


def load():
    return json.load(open(JSON, encoding="utf-8")) if os.path.exists(JSON) else {"images": [], "models": {}, "errors": {}}


def save(d):
    os.makedirs(RES, exist_ok=True)
    json.dump(d, open(JSON, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


def get(url, tries=4):
    last = None
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(3 * (i + 1))
    raise RuntimeError(f"{url} : {last}")


def fetch():
    from PIL import Image
    import io
    d = load()
    d["images"] = []
    os.makedirs(IMG, exist_ok=True)
    used = set()
    for wid, query, expected in WORKS:
        try:
            pick = None
            for q in query.split("|"):        # plusieurs recherches de secours, dans l'ordre
                api = ("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=20&prop=imageinfo"
                       "&iiprop=url|size|mime|extmetadata&iiurlwidth=960&gsrsearch=" + urllib.parse.quote(q))
                pages = sorted(json.loads(get(api)).get("query", {}).get("pages", {}).values(), key=lambda p: p.get("index", 99))
                for p in pages:
                    ii = (p.get("imageinfo") or [{}])[0]
                    title = p.get("title", "")
                    if ii.get("mime") == "image/jpeg" and ii.get("width", 0) >= 500 and "gauguin" in title.lower() and title not in used and ii.get("thumburl"):
                        pick = (p, ii)
                        break
                if pick:
                    break
                time.sleep(1)
            if not pick:
                print(wid, ": rien trouvé pour", query)
                continue
            p, ii = pick
            used.add(p["title"])
            im = Image.open(io.BytesIO(get(ii["thumburl"]))).convert("RGB")
            im.thumbnail((640, 640))
            fn = f"{wid}.jpg"
            im.save(os.path.join(IMG, fn), quality=88)
            meta = ii.get("extmetadata", {})
            d["images"].append({
                "id": wid, "file": fn, "title": p["title"].removeprefix("File:"), "expected": expected,
                "page": "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(p["title"].replace(" ", "_")),
                "license": (meta.get("LicenseShortName") or {}).get("value", ""), "size": im.size,
            })
            print(wid, ":", p["title"], im.size)
        except Exception as e:  # noqa: BLE001
            print(wid, ": échec", e)
        time.sleep(2)
    save(d)
    print(len(d["images"]), "œuvres")


def softmax(xs):
    import math
    m = max(xs)
    es = [math.exp(x - m) for x in xs]
    s = sum(es)
    return [e / s for e in es]


def clip_stage():
    import torch
    from PIL import Image
    d = load()
    images = [Image.open(os.path.join(IMG, i["file"])).convert("RGB") for i in d["images"]]
    labels = {g: [p for _, p in items] for g, items in GROUPS.items()}
    qtexts = [q for _, q in QUERIES]
    for i in d["images"]:
        i["siglip"] = {}
        i["openclip"] = {}
    d["queries"] = {fr: {"siglip": [], "openclip": []} for fr, _ in QUERIES}

    def run(name, encode_images, encode_texts, scale_bias):
        t0 = time.time()
        iv = encode_images(images)
        iv = iv / iv.norm(dim=-1, keepdim=True)
        d["models"][name]["image_seconds_per_image"] = round((time.time() - t0) / max(1, len(images)), 3)
        for g, texts in labels.items():
            tv = encode_texts(texts)
            tv = tv / tv.norm(dim=-1, keepdim=True)
            logits = scale_bias(iv @ tv.T)
            for k, img in enumerate(d["images"]):
                probs = softmax(logits[k].tolist())
                img[name][g] = {GROUPS[g][j][0]: round(probs[j], 4) for j in range(len(texts))}
        tq = encode_texts(qtexts)
        tq = tq / tq.norm(dim=-1, keepdim=True)
        sims = (iv @ tq.T)
        for j, (fr, _) in enumerate(QUERIES):
            ranked = sorted(((float(sims[k, j]), d["images"][k]["id"]) for k in range(len(images))), reverse=True)
            d["queries"][fr][name] = [{"id": i, "score": round(s, 4)} for s, i in ranked]

    # ── SigLIP ──
    try:
        from transformers import AutoModel, AutoProcessor
        mid = "google/siglip-base-patch16-224"
        t0 = time.time()
        model = AutoModel.from_pretrained(mid).eval()
        proc = AutoProcessor.from_pretrained(mid)
        d["models"]["siglip"] = {"id": mid, "params_m": round(sum(p.numel() for p in model.parameters()) / 1e6), "load_seconds": round(time.time() - t0, 1)}
        scale = model.logit_scale.exp().item()
        bias = model.logit_bias.item()

        def vec(o):   # selon la version de transformers : un tenseur, ou un objet dont `pooler_output` est le vecteur
            return o if hasattr(o, "norm") else o.pooler_output

        def enc_i(ims):
            with torch.no_grad():
                return vec(model.get_image_features(**proc(images=ims, return_tensors="pt")))

        def enc_t(ts):
            with torch.no_grad():
                return vec(model.get_text_features(**proc(text=ts, padding="max_length", max_length=64, return_tensors="pt")))
        run("siglip", enc_i, enc_t, lambda s: s * scale + bias)
        del model
    except Exception as e:  # noqa: BLE001
        d["errors"]["siglip"] = repr(e)
        print("SigLIP échec :", e)

    # ── OpenCLIP ──
    try:
        import open_clip
        t0 = time.time()
        model, _, pre = open_clip.create_model_and_transforms("ViT-B-32", pretrained="laion2b_s34b_b79k")
        model.eval()
        tok = open_clip.get_tokenizer("ViT-B-32")
        d["models"]["openclip"] = {"id": "ViT-B-32 / laion2b_s34b_b79k", "params_m": round(sum(p.numel() for p in model.parameters()) / 1e6), "load_seconds": round(time.time() - t0, 1)}
        scale = model.logit_scale.exp().item()

        def enc_i2(ims):
            with torch.no_grad():
                return model.encode_image(torch.stack([pre(i) for i in ims]))

        def enc_t2(ts):
            with torch.no_grad():
                return model.encode_text(tok(ts))
        run("openclip", enc_i2, enc_t2, lambda s: s * scale)
        del model
    except Exception as e:  # noqa: BLE001
        d["errors"]["openclip"] = repr(e)
        print("OpenCLIP échec :", e)
    save(d)


PROMPT = (
    "You are an art historian cataloguing a thumbnail of an artwork. Look at the image and answer ONLY with one JSON object, no other text, "
    "with these keys: "
    '"medium" (technique and support, e.g. oil on canvas, watercolor, charcoal, woodcut), '
    '"subject" (portrait, landscape, still life, figures...), '
    '"dominant_colors" (list of 3 to 5 color names), '
    '"brushwork" (describe the visible touch or line: flat areas, hatching, broken strokes...), '
    '"texture_and_transparency" (paper grain, transparency of washes, canvas weave, if visible), '
    '"impasto" (true or false), '
    '"style_or_movement" (e.g. synthetism, primitivism, post-impressionism), '
    '"description_fr" (two sentences in French describing the work and its technique).'
)


def parse_json(text):
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        return None
    try:
        return json.loads(m.group(0))
    except Exception:  # noqa: BLE001
        return None


def vlm_stage():
    import torch
    from PIL import Image
    d = load()
    limit = int(os.environ.get("VLM_MAX", "8"))
    imgs = d["images"][:limit]
    out = {}
    try:
        from transformers import Qwen2_5_VLForConditionalGeneration, AutoProcessor
        mid = "Qwen/Qwen2.5-VL-3B-Instruct"
        t0 = time.time()
        model = Qwen2_5_VLForConditionalGeneration.from_pretrained(mid, torch_dtype=torch.bfloat16).eval()
        proc = AutoProcessor.from_pretrained(mid, min_pixels=128 * 28 * 28, max_pixels=400 * 28 * 28)
        d["models"]["vlm"] = {"id": mid, "params_m": round(sum(p.numel() for p in model.parameters()) / 1e6), "load_seconds": round(time.time() - t0, 1), "dtype": "bfloat16", "device": "cpu"}

        def ask(im):
            msgs = [{"role": "user", "content": [{"type": "image", "image": im}, {"type": "text", "text": PROMPT}]}]
            text = proc.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
            inputs = proc(text=[text], images=[im], return_tensors="pt")
            with torch.no_grad():
                ids = model.generate(**inputs, max_new_tokens=320, do_sample=False)
            return proc.batch_decode(ids[:, inputs["input_ids"].shape[1]:], skip_special_tokens=True)[0]
    except Exception as e:  # noqa: BLE001
        d["errors"]["qwen"] = repr(e)
        print("Qwen2.5-VL indisponible, essai de Moondream2 :", e)
        try:
            from transformers import AutoModelForCausalLM
            mid = "vikhyatk/moondream2"
            t0 = time.time()
            model = AutoModelForCausalLM.from_pretrained(mid, revision="2025-01-09", trust_remote_code=True).eval()
            d["models"]["vlm"] = {"id": mid + " (2025-01-09)", "params_m": round(sum(p.numel() for p in model.parameters()) / 1e6), "load_seconds": round(time.time() - t0, 1), "dtype": "float32", "device": "cpu"}

            def ask(im):
                return model.query(im, PROMPT)["answer"]
        except Exception as e2:  # noqa: BLE001
            d["errors"]["moondream"] = repr(e2)
            print("Moondream2 échec :", e2)
            save(d)
            return
    for i in imgs:
        im = Image.open(os.path.join(IMG, i["file"])).convert("RGB")
        t0 = time.time()
        try:
            raw = ask(im)
            i["vlm"] = {"json": parse_json(raw), "raw": raw[:1500], "seconds": round(time.time() - t0, 1)}
            print(i["id"], i["vlm"]["seconds"], "s")
        except Exception as e:  # noqa: BLE001
            i["vlm"] = {"json": None, "raw": "", "error": repr(e), "seconds": round(time.time() - t0, 1)}
            print(i["id"], "échec", e)
        save(d)
    save(d)


if __name__ == "__main__":
    {"fetch": fetch, "clip": clip_stage, "vlm": vlm_stage}[sys.argv[1]]()
