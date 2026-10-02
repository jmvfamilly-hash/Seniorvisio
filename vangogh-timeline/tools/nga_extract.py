#!/usr/bin/env python3
"""Extrait de l'open data de la National Gallery of Art (CSV, CC0) les œuvres des artistes de la frise.

Usage : nga_extract.py <dossier data/ du dépôt NationalGalleryOfArt/opendata> <dossier de sortie>
Produit un petit JSON par artiste (quelques dizaines de Ko) lu par l'appli (NgaParser) : l'appli ne télécharge jamais les CSV (170 Mo).
Le NGA ne publie pas d'API en ligne, seulement ces fichiers, mis à jour environ une fois par jour.
"""
import csv, json, os, sys

csv.field_size_limit(10**9)
# artiste de la frise -> identifiant de constituant NGA (constituents.csv ; vérifié sur Wikidata Q5582, Q105320, Q39931, Q155626, et le nom pour Sorolla)
ARTISTS = {
    "vincent-van-gogh": 1349,
    "berthe-morisot": 1733,
    "pierre-auguste-renoir": 1823,
    "john-singer-sargent": 1858,
    "joaquin-sorolla": 42403,
}
CLASSIFICATIONS = {"Painting", "Drawing", "Print", "Sculpture", "Photograph"}

def main(data, out):
    wanted = {cid: art for art, cid in ARTISTS.items()}
    by_object = {}   # objectid -> artiste
    for r in csv.DictReader(open(os.path.join(data, "objects_constituents.csv"), encoding="utf-8")):
        if r["roletype"] == "artist" and int(r["constituentid"]) in wanted:
            by_object.setdefault(int(r["objectid"]), wanted[int(r["constituentid"])])
    images = {}      # objectid -> meilleure image (principale, sinon la première)
    for r in csv.DictReader(open(os.path.join(data, "published_images.csv"), encoding="utf-8")):
        if not r["depictstmsobjectid"]: continue
        oid = int(r["depictstmsobjectid"])
        if oid not in by_object: continue
        rank = (r["viewtype"] != "primary", int(r["sequence"] or 0))
        if oid not in images or rank < images[oid][0]: images[oid] = (rank, r)
    works = {a: [] for a in ARTISTS}
    for r in csv.DictReader(open(os.path.join(data, "objects.csv"), encoding="utf-8")):
        oid = int(r["objectid"])
        if oid not in by_object or oid not in images: continue
        im = images[oid][1]
        works[by_object[oid]].append({
            "id": oid, "accession": r["accessionnum"], "title": r["title"], "date": r["displaydate"],
            "begin": int(r["beginyear"]) if r["beginyear"] else None, "end": int(r["endyear"]) if r["endyear"] else None,
            "medium": r["medium"], "classification": r["classification"], "credit": r["creditline"], "attribution": r["attribution"],
            "image": im["uuid"], "iiif": im["iiifurl"], "width": int(im["width"] or 0), "height": int(im["height"] or 0),
            "maxpixels": int(im["maxpixels"]) if im["maxpixels"] else None, "openaccess": im["openaccess"] == "1",
        })
    os.makedirs(out, exist_ok=True)
    for art, lst in works.items():
        lst.sort(key=lambda w: (w["begin"] or 0, w["id"]))
        with open(os.path.join(out, f"{art}.json"), "w", encoding="utf-8") as f:
            json.dump({"source": "National Gallery of Art — open data (CC0)", "artist": art, "works": lst}, f, ensure_ascii=False, separators=(",", ":"))
        print(art, len(lst), "œuvres,", sum(w["openaccess"] for w in lst), "en open access")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
