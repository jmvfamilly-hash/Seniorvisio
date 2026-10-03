package com.vangoghtimeline.model

/**
 * Où en sont les droits d'une œuvre, du point de vue de la CONSULTATION PRIVÉE dans l'appli :
 *
 *  - [PUBLIC_DOMAIN] : domaine public ou CC0 — consultation et réutilisation libres ;
 *  - [OPEN_LICENSE] : licence ouverte (Creative Commons…) — consultation libre, réutilisation sous conditions (citer l'auteur, partage…) ;
 *  - [VIEW_ONLY] : image protégée mais publiée en ligne par le fournisseur pour la consultation — consultation privée seulement,
 *    ni redistribution ni publication sans autorisation ;
 *  - [UNKNOWN] : droits non précisés par le fournisseur — consultation privée par prudence, mêmes restrictions.
 *
 * [badge] : mention courte sur la carte.
 */
enum class RightsKind(val badge: String, val labelFr: String) {
    PUBLIC_DOMAIN("PD", "domaine public"),
    OPEN_LICENSE("CC", "licence ouverte"),
    VIEW_ONLY("©", "consultation privée"),
    UNKNOWN("?", "droits non précisés"),
}

/**
 * Licence et conditions d'une œuvre, telles qu'affichées à l'utilisateur.
 *
 * @property label licence en une ligne (« CC BY-SA 4.0 — attribution et partage dans les mêmes conditions »)
 * @property url page de la licence ou de la déclaration de droits chez le fournisseur
 * @property attribution mention à citer (auteur de la photographie, musée…), si la licence l'exige ou si elle est connue
 * @property conditions ce que l'on peut faire de l'image, en français
 */
data class RightsInfo(
    val kind: RightsKind,
    val label: String,
    val url: String? = null,
    val attribution: String? = null,
    val conditions: String = RightsCatalog.conditionsFor(kind),
)

/**
 * Correspondances licence → texte français. Pur Kotlin : testé sur la JVM.
 */
object RightsCatalog {
    fun conditionsFor(kind: RightsKind): String = when (kind) {
        RightsKind.PUBLIC_DOMAIN -> "Domaine public : consultation et réutilisation libres ; citer la source est recommandé."
        RightsKind.OPEN_LICENSE -> "Consultation libre. Toute réutilisation ou republication doit respecter les conditions de la licence (citer l'auteur, éventuellement partager dans les mêmes conditions)."
        RightsKind.VIEW_ONLY -> "Image protégée, publiée en ligne par le fournisseur pour la consultation : usage privé uniquement, dans cette appli. Ne pas redistribuer, publier ni utiliser commercialement sans autorisation du détenteur des droits."
        RightsKind.UNKNOWN -> "Droits non précisés par le fournisseur : par prudence, usage privé uniquement ; ne pas redistribuer ni publier."
    }

    fun publicDomain(label: String, url: String? = null, attribution: String? = null) = RightsInfo(RightsKind.PUBLIC_DOMAIN, label, url, attribution)

    fun viewOnly(label: String, url: String? = null, attribution: String? = null) = RightsInfo(RightsKind.VIEW_ONLY, label, url, attribution)

    /**
     * Déclaration de droits d'Europeana (`rights`) : une adresse Creative Commons ou RightsStatements.org.
     * Les adresses inconnues donnent [RightsKind.UNKNOWN] avec l'adresse en référence.
     */
    fun fromRightsUrl(rawUrl: String?, attribution: String? = null): RightsInfo {
        val url = rawUrl?.trim().orEmpty()
        if (url.isEmpty()) return RightsInfo(RightsKind.UNKNOWN, "Droits non précisés", null, attribution)
        val u = url.lowercase().removePrefix("https://").removePrefix("http://").removeSuffix("/")
        fun info(kind: RightsKind, label: String, conditions: String? = null) =
            RightsInfo(kind, label, url, attribution, conditions ?: conditionsFor(kind))
        return when {
            u.startsWith("creativecommons.org/publicdomain/mark") -> info(RightsKind.PUBLIC_DOMAIN, "Domaine public (Public Domain Mark 1.0)")
            u.startsWith("creativecommons.org/publicdomain/zero") -> info(RightsKind.PUBLIC_DOMAIN, "CC0 1.0 — dédié au domaine public")
            u.startsWith("creativecommons.org/licenses/") -> ccLicense(u, url, attribution)
            u.startsWith("rightsstatements.org/vocab/noc-") || u.startsWith("rightsstatements.org/vocab/noc/") ->
                info(RightsKind.PUBLIC_DOMAIN, "Pas de droit d'auteur connu (${u.substringAfter("vocab/").substringBefore('/').uppercase()})",
                    "Aucun droit d'auteur connu, mais le fournisseur peut imposer des restrictions de réutilisation (voir la déclaration). Consultation privée libre.")
            u.startsWith("rightsstatements.org/vocab/inc-edu") -> info(RightsKind.VIEW_ONLY, "Protégé par le droit d'auteur — usage éducatif autorisé (InC-EDU)",
                "Image protégée, usage éducatif autorisé par le détenteur ; consultation privée dans l'appli. Ne pas redistribuer ni publier.")
            u.startsWith("rightsstatements.org/vocab/inc-nc") -> info(RightsKind.VIEW_ONLY, "Protégé par le droit d'auteur — usage non commercial autorisé (InC-NC)",
                "Image protégée, usage non commercial autorisé par le détenteur ; consultation privée dans l'appli. Ne pas redistribuer ni publier.")
            u.startsWith("rightsstatements.org/vocab/inc-ow-eu") -> info(RightsKind.VIEW_ONLY, "Œuvre orpheline protégée (InC-OW-EU)")
            u.startsWith("rightsstatements.org/vocab/inc-ruu") -> info(RightsKind.VIEW_ONLY, "Protégé, détenteur des droits introuvable (InC-RUU)")
            u.startsWith("rightsstatements.org/vocab/inc") -> info(RightsKind.VIEW_ONLY, "Protégé par le droit d'auteur (InC)")
            u.startsWith("rightsstatements.org/vocab/cne") -> info(RightsKind.UNKNOWN, "Droit d'auteur non évalué (CNE)")
            u.startsWith("rightsstatements.org/vocab/und") -> info(RightsKind.UNKNOWN, "Statut du droit d'auteur indéterminé (UND)")
            u.startsWith("rightsstatements.org/vocab/nkc") -> info(RightsKind.UNKNOWN, "Statut du droit d'auteur inconnu (NKC)")
            else -> info(RightsKind.UNKNOWN, "Droits : $url")
        }
    }

    /** `creativecommons.org/licenses/by-nc-sa/4.0` → « CC BY-NC-SA 4.0 » et ses conditions. */
    private fun ccLicense(u: String, url: String, attribution: String?): RightsInfo {
        val parts = u.removePrefix("creativecommons.org/licenses/").split('/').filter { it.isNotEmpty() }
        val code = parts.getOrNull(0).orEmpty()
        val version = parts.getOrNull(1).orEmpty()
        val terms = code.split('-')
        val conditions = buildList {
            if ("by" in terms) add("citer l'auteur")
            if ("sa" in terms) add("partager dans les mêmes conditions")
            if ("nc" in terms) add("pas d'usage commercial")
            if ("nd" in terms) add("pas de modification")
        }
        val label = "CC ${code.uppercase()}${if (version.isNotEmpty()) " $version" else ""}"
        val text = "Consultation libre. Réutilisation sous licence $label : ${conditions.joinToString(", ").ifEmpty { "voir la licence" }}."
        return RightsInfo(RightsKind.OPEN_LICENSE, label, url, attribution, text)
    }

    /**
     * Licence d'un fichier Wikimedia Commons (métadonnées `LicenseShortName`, `LicenseUrl`, `Artist`/`Credit`). Domaine public (« PD-Art »,
     * « Public domain », « CC0 ») ; sinon licence ouverte (« CC BY-SA 4.0 »…) avec l'auteur à citer ; sinon consultation privée.
     */
    fun fromCommons(shortName: String?, licenseUrl: String?, author: String?): RightsInfo {
        val name = shortName?.trim().orEmpty()
        val lower = name.lowercase()
        val credit = author?.let(::stripHtml)?.takeIf { it.isNotBlank() }
        return when {
            name.isEmpty() -> RightsInfo(RightsKind.UNKNOWN, "Licence non lue sur Commons", licenseUrl, credit)
            lower.startsWith("pd") || lower.contains("public domain") || lower.startsWith("cc0") ->
                RightsInfo(RightsKind.PUBLIC_DOMAIN, "$name (Wikimedia Commons)", licenseUrl, credit)
            lower.startsWith("cc") -> {
                val kindText = "Consultation libre. Réutilisation sous licence $name : citer l'auteur${if (lower.contains("sa")) ", partager dans les mêmes conditions" else ""}."
                RightsInfo(RightsKind.OPEN_LICENSE, "$name (Wikimedia Commons)", licenseUrl, credit, kindText)
            }
            else -> RightsInfo(RightsKind.VIEW_ONLY, "$name (Wikimedia Commons)", licenseUrl, credit)
        }
    }

    /** Retire les balises HTML des métadonnées Commons (« &lt;a href=…&gt;Auteur&lt;/a&gt; ») et condense les espaces. */
    fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace(Regex("\\s+"), " ").trim()

    /** « 120 domaine public · 14 licence ouverte · 3 consultation privée » : décompte par catégorie, pour le panneau d'un artiste. */
    fun summary(artworks: List<Artwork>): String {
        val counts = artworks.groupingBy { it.rights?.kind ?: RightsKind.UNKNOWN }.eachCount()
        return RightsKind.values().filter { (counts[it] ?: 0) > 0 }.joinToString(" · ") { "${counts[it]} ${it.labelFr}" }
    }
}
