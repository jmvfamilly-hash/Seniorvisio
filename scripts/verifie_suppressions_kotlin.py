#!/usr/bin/env python3
"""
Le filet contre la SEULE erreur que les nettoyages produisent : un symbole
supprimé dont un usage survit quelque part.

═══ POURQUOI CELUI-CI EN PLUS DE verifie_imports_kotlin.py ═══

L'autre filet vérifie qu'un identifiant EN MAJUSCULE est importé. Il ne voit
pas les membres d'une classe, qui commencent par une minuscule et ne
s'importent pas. Un « private val flux = RafraichisseurFlux(...) » retiré
laissait donc « flux.arrêter() » dans onDestroy, et la CI l'a dit — après un
aller-retour complet de quatre minutes.

Écrire un détecteur général de références non résolues, ce serait écrire un
compilateur. Ce script répond à une question beaucoup plus étroite, et à
laquelle on peut répondre exactement :

    « Ce que ce changement SUPPRIME est-il encore utilisé quelque part ? »

C'est la bonne question, parce que c'est le seul moment où l'erreur naît. Une
référence à un symbole qui n'a jamais existé, on l'écrit rarement ; une
référence à un symbole qu'on vient de retirer, tout le temps.

═══ CE QU'IL REGARDE ═══

Les déclarations que le diff retire — val, var, fun, class, object,
interface, enum, typealias, const — y compris toutes celles d'un fichier
entièrement supprimé. Puis il cherche chacune dans l'arbre COURANT, hors
commentaires et chaînes de caractères, et signale ce qui reste.

Un nom déclaré à la fois ailleurs (renommage, ou homonyme légitime dans une
autre classe) n'est pas signalé : on ne cherche que ce qui a disparu partout.

Usage :
    python3 scripts/verifie_suppressions_kotlin.py [base]

« base » est une référence git, HEAD par défaut. Avant un commit, HEAD
compare le travail en cours à ce qui est déjà validé — ce qu'on veut.

Sortie : code 1 s'il reste un usage d'un symbole supprimé.
"""

import pathlib
import re
import subprocess
import sys

RACINE = pathlib.Path(__file__).resolve().parent.parent
SOURCES = RACINE / "app" / "src"

# Une déclaration Kotlin, à n'importe quelle indentation. Le nom capturé est
# celui qui disparaîtrait de la portée si la ligne partait.
DÉCLARATION = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:public\s+|internal\s+|private\s+|protected\s+|abstract\s+|sealed\s+|"
    r"open\s+|override\s+|data\s+|enum\s+|annotation\s+|inner\s+|value\s+|"
    r"lateinit\s+|const\s+|suspend\s+|inline\s+|external\s+|operator\s+|"
    r"infix\s+|tailrec\s+|companion\s+)*"
    r"(?:val|var|fun|class|object|interface|typealias)\s+"
    r"(?:<[^>]*>\s+)?"                      # fun <T> machin(...)
    r"(?:[A-Za-zÀ-ÖØ-öø-ÿ_][\w.]*\.)?"               # fun Truc.extension(...)
    r"([A-Za-zÀ-ÖØ-öø-ÿ_][A-Za-zÀ-ÖØ-öø-ÿ0-9_]*)"
)

# Trop courts ou trop courants pour qu'une recherche textuelle dise quoi que
# ce soit d'utile. Les signaler noierait les vrais dans le bruit.
TROP_COMMUNS = {
    "it", "e", "i", "n", "x", "y", "f", "r", "v", "id", "to", "of", "on",
    "get", "set", "run", "map", "key", "tag", "TAG", "value", "values",
    "name", "type", "state", "index", "size", "text", "data", "result",
    "start", "stop", "close", "clear", "release", "invoke", "toString",
    "equals", "hashCode", "compareTo", "iterator", "next", "hasNext",
    # Des méthodes du socle Android et Kotlin, qu'une recherche textuelle ne
    # distingue pas d'une propriété du même nom. « commit » a été le premier :
    # déclaré une fois dans un fichier supprimé, et appelé partout ailleurs
    # sous la forme prefs.edit().commit().
    "commit", "apply", "edit", "flush", "prepare", "describe", "remove",
    "add", "put", "read", "write", "send", "post", "cancel", "update",
    "show", "hide", "dismiss", "close", "open", "accept", "feed",
}


def sans_commentaires_ni_chaines(texte: str) -> str:
    """Neutralise ce qui n'est pas du code.

    Indispensable ici : ce dépôt documente abondamment en français, et les
    commentaires citent les symboles par leur nom — y compris, très souvent,
    ceux qu'on vient justement de retirer, pour dire pourquoi. Les compter
    comme des usages rendrait ce filet inutilisable dès le premier essai.
    """
    texte = re.sub(r'"""(?:.|\n)*?"""', '""', texte)
    texte = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', texte)
    texte = re.sub(r"/\*(?:.|\n)*?\*/", "", texte)
    texte = re.sub(r"//[^\n]*", "", texte)
    return texte


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=RACINE, capture_output=True, text=True, check=True
    ).stdout


def déclarations(texte: str) -> set[str]:
    return {
        m.group(1)
        for ligne in texte.splitlines()
        if (m := DÉCLARATION.match(ligne))
    }


# ═══ LES NOMS QUI NE SONT PAS DES DÉCLARATIONS, ET QUI COMPTENT QUAND MÊME ═══
#
# Le premier essai de ce script signalait « texte » et « sortie » dans sept
# fichiers. Tous des faux positifs, et tous pour la même raison : ces noms
# étaient bien retirés là où un « val » les déclarait, mais ils vivent
# ailleurs comme PARAMÈTRES — « fun vers(url: String, sortie: File) » — et un
# paramètre n'est pas une déclaration au sens de la regex ci-dessus.
#
# Sans cette seconde passe, le filet noie le seul vrai signalement sous le
# bruit, ce qui revient exactement à ne pas l'avoir.
LIAISONS = [
    re.compile(r"\b([a-zà-öø-ÿ_][A-Za-zÀ-ÖØ-öø-ÿ0-9_]*)\s*:\s*[A-Za-zÀ-ÖØ-öø-ÿ_(]"),   # paramètre ou propriété typée
    re.compile(r"\bfor\s*\(\s*([a-zà-öø-ÿ_][A-Za-zÀ-ÖØ-öø-ÿ0-9_]*)\s+in\b"),  # for (x in …)
    re.compile(r"\bcatch\s*\(\s*([a-zà-öø-ÿ_][A-Za-zÀ-ÖØ-öø-ÿ0-9_]*)\s*:"),   # catch (e: …)
    re.compile(r"[{(]\s*([a-zà-öø-ÿ_][A-Za-zÀ-ÖØ-öø-ÿ0-9_]*)\s*->"),          # { x -> … }
    re.compile(r"\bval\s*\(([^)]*)\)"),                        # val (a, b) = …
]


def noms_liés(texte: str) -> set[str]:
    trouvés: set[str] = set()
    for motif in LIAISONS:
        for m in motif.findall(texte):
            for morceau in m.split(","):
                nom = morceau.strip()
                if re.fullmatch(r"[a-zà-öø-ÿ_][A-Za-zÀ-ÖØ-öø-ÿ0-9_]*", nom):
                    trouvés.add(nom)
    return trouvés


def main() -> int:
    base = sys.argv[1] if len(sys.argv) > 1 else "HEAD"

    # Le diff, fichier par fichier. --diff-filter garde les modifications et
    # les suppressions : un ajout ne retire rien.
    modifiés = [
        l for l in git("diff", base, "--name-only", "--", "app/src").splitlines() if l
    ]
    if not modifiés:
        print("Aucune source Kotlin modifiée — rien à vérifier.")
        return 0

    supprimés: dict[str, str] = {}  # nom -> fichier d'où il vient
    for chemin in modifiés:
        if not chemin.endswith(".kt"):
            continue
        try:
            avant = git("show", f"{base}:{chemin}")
        except subprocess.CalledProcessError:
            # Fichier ajouté par ce changement : il ne supprime rien.
            continue
        fichier = RACINE / chemin
        après = fichier.read_text(encoding="utf-8") if fichier.exists() else ""
        perdus = déclarations(sans_commentaires_ni_chaines(avant)) - déclarations(
            sans_commentaires_ni_chaines(après)
        )
        for nom in perdus:
            supprimés.setdefault(nom, chemin)

    # Un nom encore déclaré AILLEURS dans l'arbre courant n'a pas disparu :
    # c'est un déplacement, ou un homonyme dans une autre classe. Le chercher
    # ne dirait rien.
    encore_déclarés: set[str] = set()
    code_courant: dict[pathlib.Path, str] = {}
    for f in sorted(SOURCES.rglob("*.kt")):
        code = sans_commentaires_ni_chaines(f.read_text(encoding="utf-8"))
        code_courant[f] = code
        encore_déclarés |= déclarations(code) | noms_liés(code)

    cherchés = {
        nom: origine
        for nom, origine in supprimés.items()
        if nom not in encore_déclarés
        and nom not in TROP_COMMUNS
        and len(nom) > 2
    }
    if not cherchés:
        print(
            f"{len(supprimés)} déclaration(s) retirée(s) — aucune n'est "
            "orpheline, ou toutes existent encore ailleurs."
        )
        return 0

    signalements: list[str] = []
    motifs = {nom: re.compile(rf"\b{re.escape(nom)}\b") for nom in cherchés}
    for f, code in code_courant.items():
        for ligne_no, ligne in enumerate(code.splitlines(), start=1):
            for nom, motif in motifs.items():
                if motif.search(ligne):
                    signalements.append(
                        f"{f.relative_to(RACINE)}:{ligne_no}: « {nom} » est "
                        f"encore utilisé, mais sa déclaration a été retirée de "
                        f"{cherchés[nom]}"
                    )

    if signalements:
        print("Des usages ont survécu à leur déclaration :\n")
        for s in signalements:
            print("  " + s)
        print(
            "\nSi l'un d'eux est un faux positif — un membre hérité, une "
            "variable locale homonyme — ajoutez son nom à TROP_COMMUNS."
        )
        return 1

    print(
        f"{len(cherchés)} déclaration(s) retirée(s) sans doublon ailleurs — "
        "aucun usage survivant."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
