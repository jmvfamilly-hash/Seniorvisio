# Démo : indexation du style par SigLIP / OpenCLIP et un modèle de vision-langage

Maquette **à part** (aucun lien avec l'appli ni avec Senior Visio). Elle fait tourner, sur des œuvres de Gauguin de techniques différentes (huile, gravure sur bois, aquarelle, fusain, lithographie, nature morte, pastel — téléchargées depuis Wikimedia Commons) :

1. **SigLIP** (`google/siglip-base-patch16-224`) et **OpenCLIP** (ViT-B-32, laion2b) : classification de la technique, du sujet et de la palette par similarité image/texte, et recherche libre par le texte ;
2. un **modèle de vision-langage** (Qwen2.5-VL-3B, à défaut Moondream2) : description de la facture et JSON structuré.

Le workflow `style-index-demo.yml` (CPU, ~1 h) exécute `run_demo.py fetch|clip|vlm` et enregistre `results/results.json` et les vignettes ; `build_report.py` en fait une page HTML autonome.
Les durées sont mesurées : elles disent ce qui est envisageable sur un téléphone et ce qui reste côté serveur.
