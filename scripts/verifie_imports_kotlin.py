#!/usr/bin/env python3
"""
Filet local contre la seule erreur que la CI a déjà attrapée deux fois :
un identifiant utilisé sans son import.

Ce dépôt ne peut pas compiler du Kotlin hors CI (le dépôt Maven de Google
n'est pas joignable depuis l'environnement de développement). La CI est donc
le seul compilateur, et chaque oubli d'import coûte un aller-retour complet :
commit, push, build, lecture des logs, correctif. Deux fois déjà — Log dans
CallListenerService, BuildConfig dans KioskManager.

Ce script ne remplace pas le compilateur et ne prétend pas le faire. Il
répond à une question étroite et vérifiable : cet identifiant en majuscule,
utilisé comme Truc.machin ou Truc(...), est-il soit importé, soit déclaré
dans le même paquet, soit connu du préambule implicite de Kotlin ? Si non,
il le signale. Les faux positifs sont possibles ; les faux négatifs aussi.
C'est un filet, pas une preuve.

Usage : python3 scripts/verifie_imports_kotlin.py
Sortie : code 1 s'il reste un identifiant inexpliqué.
"""

import pathlib
import re
import sys

RACINE = pathlib.Path(__file__).resolve().parent.parent
SOURCES = RACINE / "app" / "src"

# Le préambule que Kotlin importe sans qu'on le demande, plus le socle Java
# que le compilateur résout tout seul. Liste volontairement conservatrice :
# tout ce qui n'y figure pas et n'est pas importé sera signalé, quitte à
# produire du bruit qu'on ajoutera ici au fur et à mesure.
IMPLICITES = {
    # kotlin.*
    "Any", "Array", "Boolean", "Byte", "ByteArray", "Char", "CharArray",
    "CharSequence", "Comparable", "Double", "DoubleArray", "Enum", "Error",
    "Exception", "Float", "FloatArray", "Function", "IllegalArgumentException",
    "IllegalStateException", "Int", "IntArray", "Iterable", "Iterator", "Lazy",
    "List", "Long", "LongArray", "Map", "MutableList", "MutableMap",
    "MutableSet", "Nothing", "Number", "Pair", "Regex", "Result",
    "RuntimeException", "Set", "Short", "String", "StringBuilder", "Throwable",
    "Triple", "Unit", "UnsupportedOperationException", "OutOfMemoryError",
    "NumberFormatException", "ArithmeticException", "ClassCastException",
    "IndexOutOfBoundsException", "NullPointerException", "Runnable", "Thread",
    "System", "Math", "Object", "Class", "Comparator", "Deprecated",
    "JvmStatic", "JvmField", "JvmOverloads", "Volatile", "Synchronized",
    "Suppress", "SuppressLint", "Throws", "Transient", "Byte", "UByte",
    # collections et utilitaires kotlin.collections / kotlin.text
    "ArrayDeque", "LinkedHashMap", "LinkedHashSet", "HashMap", "HashSet",
    "ArrayList", "Charsets", "Sequence", "ShortArray", "BooleanArray",
    "Character", "Integer", "Void", "Iterable",
    # kotlin.text et kotlin.io, importés d'office comme kotlin.collections.
    "RegexOption", "MatchResult", "StringBuilder", "Appendable", "Typography",
}

# Les annotations et mots-clés qu'on rencontre en tête de ligne et qui ne
# sont pas des références de type.
IGNORÉS = IMPLICITES | {"T", "R", "K", "V", "E"}

# Truc.machin ou Truc( en position de référence. On exige au moins une
# minuscule : les noms tout en majuscules sont des entrées d'enum ou des
# constantes de companion, jamais des classes à importer dans ce dépôt, et
# les inclure ne produisait que du bruit.
USAGE = re.compile(r"(?<![\w.@\"'])([A-Z][A-Za-z0-9_]*[a-z][A-Za-z0-9_]*)\s*(?=[.(<])")
IMPORT = re.compile(r"^import\s+(?:[\w.]+\.)?([\w*]+)", re.M)
PAQUET = re.compile(r"^package\s+([\w.]+)", re.M)
# Déclarations locales au fichier : class, object, interface, enum, typealias…
# Un type de premier niveau : déclaré en colonne zéro, contrairement aux
# classes imbriquées qui sont indentées.
TYPE_RACINE = re.compile(
    r"^(?:public |internal |private |abstract |sealed |open |data |enum |annotation |value )*"
    r"(?:class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)"
)
COMPANION = re.compile(r"^\s*(?:private\s+|internal\s+|protected\s+)?companion\s+object\b", re.M)
DÉCLARATION = re.compile(
    r"^\s*(?:@\w+\s+)*(?:public\s+|internal\s+|private\s+|abstract\s+|sealed\s+|open\s+|data\s+|enum\s+|annotation\s+|inner\s+|value\s+)*"
    r"(?:class|object|interface|typealias)\s+([A-Z][A-Za-z0-9_]*)",
    re.M,
)


def sans_commentaires_ni_chaines(texte: str) -> str:
    """Neutralise ce qui n'est pas du code, pour ne pas signaler un nom cité
    dans un commentaire français — ce fichier en est plein."""
    texte = re.sub(r'"""(?:.|\n)*?"""', '""', texte)
    texte = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', texte)
    texte = re.sub(r"/\*(?:.|\n)*?\*/", "", texte)
    texte = re.sub(r"//[^\n]*", "", texte)
    return texte


def main() -> int:
    fichiers = sorted(SOURCES.rglob("*.kt"))
    if not fichiers:
        print("Aucune source Kotlin trouvée — chemin inattendu.", file=sys.stderr)
        return 2

    # Ce que chaque paquet déclare, tous fichiers confondus : un fichier peut
    # citer une classe voisine du même paquet sans l'importer.
    par_paquet: dict[str, set[str]] = {}
    contenus: dict[pathlib.Path, tuple[str, str]] = {}
    for f in fichiers:
        brut = f.read_text(encoding="utf-8")
        code = sans_commentaires_ni_chaines(brut)
        paquet = (PAQUET.search(code) or [None, ""])[1] if PAQUET.search(code) else ""
        contenus[f] = (paquet, code)
        par_paquet.setdefault(paquet, set()).update(DÉCLARATION.findall(code))
        # BuildConfig et R sont générés dans le paquet applicatif.
        par_paquet.setdefault("com.seniorvisio", set())

    signalements: list[str] = []

    # Une classe Kotlin ne peut avoir qu'UN companion object.
    #
    # Compté par TYPE et non par fichier : un fichier de ce dépôt en déclare
    # souvent plusieurs — TranscriptionEngineChoice.kt tient deux enums, qui
    # ont chacune droit au sien. La première version comptait par fichier et le
    # signalait à tort ; le découpage se fait donc sur les déclarations en
    # colonne zéro, qui sont les types de premier niveau.
    for f in fichiers:
        _, code = contenus[f]
        lignes = code.splitlines()
        débuts = [
            i for i, l in enumerate(lignes)
            if TYPE_RACINE.match(l)
        ]
        for n, début in enumerate(débuts):
            fin = débuts[n + 1] if n + 1 < len(débuts) else len(lignes)
            portion = "\n".join(lignes[début:fin])
            compagnons = COMPANION.findall(portion)
            if len(compagnons) > 1:
                nom = TYPE_RACINE.match(lignes[début]).group(1)
                signalements.append(
                    f"{f.relative_to(RACINE)}:{début + 1}: « {nom} » déclare "
                    f"{len(compagnons)} companion object — une classe Kotlin "
                    "n'en accepte qu'un seul"
                )

    for f in fichiers:
        paquet, code = contenus[f]
        importés = set(IMPORT.findall(code))
        connus = importés | par_paquet.get(paquet, set()) | IGNORÉS
        if "*" in importés:
            # Un import étoilé rend le fichier indécidable ici : on s'abstient.
            continue
        for ligne_no, ligne in enumerate(code.splitlines(), start=1):
            for nom in USAGE.findall(ligne):
                if nom in connus:
                    continue
                signalements.append(
                    f"{f.relative_to(RACINE)}:{ligne_no}: « {nom} » utilisé sans import ni déclaration locale"
                )

    if signalements:
        print("Le filet a relevé quelque chose :\n")
        for s in signalements:
            print("  " + s)
        if any("utilisé sans import" in s for s in signalements):
            print(
                "\nSi un identifiant signalé est un faux positif (nom implicite "
                "de Kotlin, générique, membre de la même classe), ajoutez-le à "
                "IMPLICITES."
            )
        return 1

    print(f"{len(fichiers)} fichiers Kotlin — aucun identifiant sans import.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
