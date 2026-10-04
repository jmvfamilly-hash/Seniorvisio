#!/usr/bin/env python3
"""Télécharge les images de fond du menu (une par artiste) dans app/src/main/assets/backdrops/ pour qu'elles soient EMBARQUÉES dans l'APK.

Usage : fetch_backdrops.py <dossier assets/backdrops>
- source « nga » : image open access (CC0) du National Gallery of Art, `{iiif}/full/!1600,1600/0/default.jpg`.
- source « wikidata » : le tableau de l'artiste qui a le plus de pages Wikipédia (Wikidata P170 + image P18), servi par Commons à une largeur
  standard (1280). Pour ces artistes, titre, date, crédit et dimensions sont renseignés ici dans index.json.
- point d'intérêt (poiX, poiY) : si OpenCV est disponible et trouve un visage dans l'image, le centre des yeux du plus grand visage remplace
  l'estimation de l'index (kind = face). Sinon l'estimation de l'index est conservée (arbre ou centre).
Ne plante jamais : une image qui échoue reste absente et l'appli retombe sur l'adresse distante, puis sur un dégradé. Sortie 0 toujours.
Aucune adresse e-mail dans l'identifiant d'agent.
"""
import json, os, re, struct, sys, time, urllib.parse, urllib.request

UA = "VanGoghTimeline/1.0 (https://github.com/jmvfamilly-hash/Seniorvisio; fond du menu)"


def get(url, tries=3):
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001 - on réessaie puis on abandonne proprement
            last = e
            time.sleep(2 * (i + 1))
    raise RuntimeError(f"{url} : {last}")


def jpeg_size(data):
    """(largeur, hauteur) d'un JPEG, lue dans l'en-tête SOF (aucune dépendance)."""
    i = 2
    while i + 9 < len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        m = data[i + 1]
        if m in (0xC0, 0xC1, 0xC2):
            h, w = struct.unpack(">HH", data[i + 5:i + 9])
            return w, h
        i += 2 + struct.unpack(">H", data[i + 2:i + 4])[0]
    return None


def wikidata_work(name):
    """(titre, année, nom de fichier Commons) du tableau le plus connu de l'artiste, ou None."""
    s = json.loads(get("https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=en&type=item&limit=5&search=" + urllib.parse.quote(name)))
    qid = next((r["id"] for r in s.get("search", []) if re.search(r"painter|peintre|pittore|schilder|maler", r.get("description", ""), re.I)), None)
    if not qid:
        return None
    q = ("SELECT ?img ?l ?y WHERE { ?p wdt:P170 wd:%s ; wdt:P18 ?img ; wikibase:sitelinks ?sl . "
         "OPTIONAL { ?p wdt:P571 ?d . BIND(YEAR(?d) AS ?y) } "
         'SERVICE wikibase:label { bd:serviceParam wikibase:language "fr,en". ?p rdfs:label ?l } } ORDER BY DESC(?sl) LIMIT 3' % qid)
    r = json.loads(get("https://query.wikidata.org/sparql?format=json&query=" + urllib.parse.quote(q)))
    for b in r["results"]["bindings"]:
        img = urllib.parse.unquote(b["img"]["value"].rsplit("/", 1)[-1])
        return b["l"]["value"], b.get("y", {}).get("value", ""), img
    return None


def named_work(search, match):
    """(titre, année, fichier Commons) du tableau précis `search` dont la description cite `match`, ou None."""
    s = json.loads(get("https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=en&type=item&limit=8&search=" + urllib.parse.quote(search)))
    for r in s.get("search", []):
        if not re.search(match, r.get("description", "") + " " + r.get("label", ""), re.I):
            continue
        ent = json.loads(get("https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=claims|labels&languages=fr|en&ids=" + r["id"]))["entities"][r["id"]]
        claims = ent.get("claims", {})
        p18 = claims.get("P18")
        if not p18:
            continue
        label = (ent.get("labels", {}).get("fr") or ent.get("labels", {}).get("en") or {}).get("value", r.get("label", search))
        year = ""
        try:
            year = claims["P571"][0]["mainsnak"]["datavalue"]["value"]["time"][1:5].lstrip("0")
        except Exception:  # noqa: BLE001
            pass
        return label, year, p18[0]["mainsnak"]["datavalue"]["value"]
    return None


def commons_license(fname):
    """Licence courte de la page Commons (ex. « CC BY-SA 4.0 », « Public domain »), ou ''."""
    try:
        r = json.loads(get("https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=extmetadata&titles=File:" + urllib.parse.quote(fname)))
        for page in r["query"]["pages"].values():
            return page["imageinfo"][0]["extmetadata"]["LicenseShortName"]["value"]
    except Exception:  # noqa: BLE001
        pass
    return ""


def face_poi(path):
    """Centre des yeux du plus grand visage (fraction de l'image), ou None. OpenCV facultatif."""
    try:
        import cv2  # type: ignore
        img = cv2.imread(path)
        if img is None:
            return None
        g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        det = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
        faces = det.detectMultiScale(g, scaleFactor=1.1, minNeighbors=6, minSize=(g.shape[1] // 25, g.shape[0] // 25))
        if len(faces) == 0:
            return None
        x, y, w, h = max(faces, key=lambda f: f[2] * f[3])
        return round((x + w / 2) / g.shape[1], 3), round((y + 0.40 * h) / g.shape[0], 3)
    except Exception:  # noqa: BLE001
        return None


def main(out):
    index_path = os.path.join(out, "index.json")
    doc = json.load(open(index_path, encoding="utf-8"))
    ok = 0
    for e in doc["backdrops"]:
        path = os.path.join(out, e["artistId"] + ".jpg")
        try:
            if e["source"] == "wikidata":
                work = next((w for q in e.get("workSearch", "").split("|") if q for w in [named_work(q, e.get("workMatch", "."))] if w), None) or wikidata_work(e["searchName"])
                if not work:
                    print(e["artistId"], ": aucun tableau trouvé sur Wikidata")
                    continue
                title, year, fname = work
                url = "https://commons.wikimedia.org/wiki/Special:FilePath/" + urllib.parse.quote(fname) + "?width=1280"
                data = get(url)
                e["title"], e["date"] = title, year
                lic = commons_license(fname) or "domaine public"
                e["credit"] = f"{title}{', ' + year if year else ''} — Wikimedia Commons ({lic})"
                e["remoteUrl"] = url
            else:
                data = get(e["remoteUrl"])
            size = jpeg_size(data)
            if not size or data[:2] != b"\xff\xd8":
                print(e["artistId"], ": réponse qui n'est pas un JPEG")
                continue
            open(path, "wb").write(data)
            e["width"], e["height"] = size
            poi = face_poi(path) if e["kind"] != "tree" and e.get("fit") != "focus" else None   # un paysage garde son arbre
            if poi:
                e["poiX"], e["poiY"], e["kind"] = poi[0], poi[1], "face"
            ok += 1
            print(e["artistId"], ":", size, e["kind"], e["poiX"], e["poiY"])
        except Exception as ex:  # noqa: BLE001
            print(e["artistId"], ": échec", ex)
        time.sleep(1)
    json.dump(doc, open(index_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"{ok}/{len(doc['backdrops'])} images embarquées")


if __name__ == "__main__":
    try:
        main(sys.argv[1])
    except Exception as ex:  # noqa: BLE001
        print("fetch_backdrops :", ex)
    sys.exit(0)
