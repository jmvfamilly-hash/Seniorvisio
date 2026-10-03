"""Étiquettes de la démonstration : (libellé français affiché, phrase anglaise donnée aux modèles — SigLIP et OpenCLIP sont entraînés surtout en anglais)."""

GROUPS = {
    "technique": [
        ("Huile sur toile", "an oil painting on canvas"),
        ("Aquarelle", "a watercolor painting on paper"),
        ("Fusain", "a charcoal drawing"),
        ("Crayon / graphite", "a pencil sketch"),
        ("Encre à la plume", "a pen and ink drawing"),
        ("Pastel", "a pastel drawing"),
        ("Gravure sur bois", "a woodcut print"),
        ("Lithographie", "a lithograph print"),
        ("Sculpture ou céramique", "a photograph of a sculpture or ceramic"),
    ],
    "sujet": [
        ("Portrait", "a portrait of a person"),
        ("Paysage", "a landscape"),
        ("Nature morte", "a still life of objects and flowers"),
        ("Scène à plusieurs personnages", "a scene with several figures"),
        ("Nu", "a nude figure"),
        ("Scène religieuse", "a religious scene"),
    ],
    "couleur": [
        ("Couleurs vives et aplats", "a painting with bright saturated colors and flat areas of color"),
        ("Tons sourds et terreux", "a painting in muted earthy tones"),
        ("Noir et blanc", "a black and white image"),
    ],
}

# Recherches libres : (requête en français, phrase anglaise)
QUERIES = [
    ("Portrait à l'aquarelle", "a portrait in watercolor"),
    ("Étude au fusain", "a charcoal study"),
    ("Huile sur toile, paysage coloré", "an oil painting of a colorful landscape"),
    ("Estampe en noir et blanc", "a black and white woodcut print"),
    ("Nature morte aux fleurs", "a still life with flowers"),
]

# Œuvres : (identifiant, ce qu'on cherche sur Commons, technique attendue — pour comparer, jamais donnée aux modèles)
WORKS = [
    ("vision", "Gauguin Vision after the Sermon Jacob wrestling with the angel", "huile sur toile (synthétisme, Pont-Aven)"),
    ("vairumati", "Gauguin Vairumati", "huile sur toile (Tahiti)"),
    ("woodcut", "Gauguin woodcut Noa Noa", "gravure sur bois"),
    ("watercolor", "Gauguin watercolor|Gauguin aquarelle|Gauguin gouache", "aquarelle"),
    ("charcoal", "Paul Gauguin drawing|Gauguin sketch pencil|Gauguin dessin|Gauguin charcoal", "fusain / dessin"),
    ("lithograph", "Gauguin lithograph|Gauguin zincograph|Gauguin Volpini|Gauguin print Te Atua", "lithographie"),
    ("stilllife", "Gauguin still life flowers", "nature morte"),
    ("pastel", "Gauguin pastel|Gauguin study heads|Gauguin Tahitian women drawing|Paul Gauguin Tahiti drawing", "pastel"),
]
