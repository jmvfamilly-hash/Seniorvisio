package com.vangoghtimeline.model

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/** Un musée (ou une collection) placé sur la carte. [keys] : fragments de nom, sans accents ni casse, qui le désignent. */
data class MuseumPlace(val id: String, val name: String, val city: String, val lat: Double, val lon: Double, val keys: List<String>)

/** Les œuvres d'un même lieu de conservation : un repère sur la carte. */
data class MapCluster(val place: MuseumPlace, val artworks: List<Artwork>)

/**
 * Où une œuvre est conservée : le musée qui la fournit (sources directes) ou la collection indiquée dans sa fiche (Wikimedia « Collection »,
 * Europeana « Fournisseur »). Annuaire volontairement explicite : un lieu absent de la liste n'est pas deviné, l'œuvre va dans « lieu inconnu ».
 * Coordonnées approximatives (bâtiment principal), à l'échelle d'une carte du monde.
 */
object MuseumMap {
    private fun p(id: String, name: String, city: String, lat: Double, lon: Double, vararg keys: String) = MuseumPlace(id, name, city, lat, lon, keys.toList())

    /** L'ORDRE compte : les noms précis d'abord (« national gallery of art » avant « national gallery »). */
    val places: List<MuseumPlace> = listOf(
        // ── Amérique du Nord ──
        p("nga", "National Gallery of Art", "Washington", 38.8913, -77.0200, "national gallery of art"),
        p("aic", "Art Institute of Chicago", "Chicago", 41.8796, -87.6237, "art institute of chicago", "institute of chicago"),
        p("met", "Metropolitan Museum of Art", "New York", 40.7794, -73.9632, "metropolitan museum", "the met"),
        p("moma", "Museum of Modern Art", "New York", 40.7614, -73.9776, "museum of modern art", "moma"),
        p("brooklyn", "Brooklyn Museum", "New York", 40.6712, -73.9636, "brooklyn museum"),
        p("guggenheim", "Guggenheim", "New York", 40.7830, -73.9590, "guggenheim"),
        p("cleveland", "Cleveland Museum of Art", "Cleveland", 41.5089, -81.6119, "cleveland museum", "cleveland"),
        p("mfa", "Museum of Fine Arts", "Boston", 42.3394, -71.0940, "museum of fine arts, boston", "museum of fine arts boston", "mfa boston", "fine arts, boston"),
        p("harvard", "Harvard Art Museums", "Cambridge (MA)", 42.3741, -71.1143, "harvard"),
        p("clark", "Clark Art Institute", "Williamstown", 42.7095, -73.2132, "clark art", "sterling and francine clark"),
        p("yale", "Yale University Art Gallery", "New Haven", 41.3083, -72.9310, "yale"),
        p("philadelphia", "Philadelphia Museum of Art", "Philadelphie", 39.9656, -75.1810, "philadelphia museum"),
        p("barnes", "Barnes Foundation", "Philadelphie", 39.9608, -75.1727, "barnes"),
        p("walters", "Walters Art Museum", "Baltimore", 39.2965, -76.6158, "walters"),
        p("baltimore", "Baltimore Museum of Art", "Baltimore", 39.3260, -76.6195, "baltimore museum"),
        p("phillips", "Phillips Collection", "Washington", 38.9115, -77.0468, "phillips collection"),
        p("smithsonian", "Smithsonian American Art Museum", "Washington", 38.8977, -77.0230, "smithsonian"),
        p("carnegie", "Carnegie Museum of Art", "Pittsburgh", 40.4437, -79.9496, "carnegie"),
        p("detroit", "Detroit Institute of Arts", "Détroit", 42.3594, -83.0645, "detroit institute"),
        p("toledo", "Toledo Museum of Art", "Toledo", 41.6584, -83.5599, "toledo museum"),
        p("indianapolis", "Indianapolis Museum of Art", "Indianapolis", 39.8256, -86.1854, "indianapolis"),
        p("minneapolis", "Minneapolis Institute of Art", "Minneapolis", 44.9586, -93.2738, "minneapolis"),
        p("stlouis", "Saint Louis Art Museum", "Saint-Louis", 38.6393, -90.2944, "saint louis art", "st. louis art"),
        p("nelson", "Nelson-Atkins Museum of Art", "Kansas City", 39.0448, -94.5809, "nelson-atkins", "nelson atkins"),
        p("dallas", "Dallas Museum of Art", "Dallas", 32.7877, -96.8010, "dallas museum"),
        p("houston", "Museum of Fine Arts, Houston", "Houston", 29.7256, -95.3905, "fine arts, houston", "fine arts houston"),
        p("getty", "J. Paul Getty Museum", "Los Angeles", 34.0780, -118.4741, "getty"),
        p("lacma", "Los Angeles County Museum of Art", "Los Angeles", 34.0639, -118.3592, "los angeles county", "lacma"),
        p("norton", "Norton Simon Museum", "Pasadena", 34.1462, -118.1593, "norton simon"),
        p("sfmoma", "SFMOMA", "San Francisco", 37.7857, -122.4011, "san francisco museum of modern art", "sfmoma"),
        p("famsf", "Fine Arts Museums of San Francisco", "San Francisco", 37.7715, -122.4687, "fine arts museums of san francisco", "legion of honor", "de young"),
        p("ottawa", "National Gallery of Canada", "Ottawa", 45.4295, -75.6989, "national gallery of canada", "musee des beaux-arts du canada"),
        p("montreal", "Musée des beaux-arts de Montréal", "Montréal", 45.4985, -73.5795, "montreal museum of fine arts", "beaux-arts de montreal"),
        p("toronto", "Art Gallery of Ontario", "Toronto", 43.6536, -79.3925, "art gallery of ontario"),
        // ── Amérique latine, Océanie, Asie ──
        p("masp", "Museu de Arte de São Paulo", "São Paulo", -23.5614, -46.6559, "museu de arte de sao paulo", "masp"),
        p("mnba", "Museo Nacional de Bellas Artes", "Buenos Aires", -34.5838, -58.3930, "museo nacional de bellas artes"),
        p("ngv", "National Gallery of Victoria", "Melbourne", -37.8226, 144.9689, "national gallery of victoria"),
        p("agnsw", "Art Gallery of New South Wales", "Sydney", -33.8688, 151.2173, "art gallery of new south wales"),
        p("nmwa", "National Museum of Western Art", "Tokyo", 35.7155, 139.7757, "national museum of western art", "musee national d'art occidental"),
        p("pola", "Pola Museum of Art", "Hakone", 35.2442, 139.0490, "pola museum"),
        // ── France ──
        p("orsay", "Musée d'Orsay", "Paris", 48.8600, 2.3266, "orsay"),
        p("orangerie", "Musée de l'Orangerie", "Paris", 48.8638, 2.3227, "orangerie"),
        p("marmottan", "Musée Marmottan Monet", "Paris", 48.8592, 2.2674, "marmottan"),
        p("louvre", "Musée du Louvre", "Paris", 48.8606, 2.3376, "louvre"),
        p("petitpalais", "Petit Palais", "Paris", 48.8660, 2.3146, "petit palais"),
        p("pompidou", "Centre Pompidou", "Paris", 48.8606, 2.3522, "pompidou", "musee national d'art moderne"),
        p("rodin", "Musée Rodin", "Paris", 48.8553, 2.3159, "musee rodin"),
        p("mamvp", "Musée d'Art moderne de Paris", "Paris", 48.8644, 2.2977, "art moderne de la ville de paris", "art moderne de paris"),
        p("rouen", "Musée des Beaux-Arts de Rouen", "Rouen", 49.4447, 1.0951, "beaux-arts de rouen"),
        p("lehavre", "MuMa Le Havre", "Le Havre", 49.4877, 0.1048, "muma", "andre malraux", "musee malraux"),
        p("honfleur", "Musée Eugène Boudin", "Honfleur", 49.4190, 0.2307, "musee eugene boudin"),
        p("lille", "Palais des Beaux-Arts de Lille", "Lille", 50.6316, 3.0618, "beaux-arts de lille"),
        p("lyon", "Musée des Beaux-Arts de Lyon", "Lyon", 45.7670, 4.8335, "beaux-arts de lyon"),
        p("bordeaux", "Musée des Beaux-Arts de Bordeaux", "Bordeaux", 44.8370, -0.5822, "beaux-arts de bordeaux"),
        p("montpellier", "Musée Fabre", "Montpellier", 43.6118, 3.8808, "musee fabre"),
        p("pontaven", "Musée de Pont-Aven", "Pont-Aven", 47.8546, -3.7466, "pont-aven"),
        p("quimper", "Musée des Beaux-Arts de Quimper", "Quimper", 47.9960, -4.1022, "beaux-arts de quimper"),
        p("albi", "Musée Toulouse-Lautrec", "Albi", 43.9286, 2.1423, "musee toulouse-lautrec"),
        p("nice", "Musée Matisse", "Nice", 43.7197, 7.2761, "musee matisse"),
        p("sttropez", "Musée de l'Annonciade", "Saint-Tropez", 43.2711, 6.6378, "annonciade"),
        p("grenoble", "Musée de Grenoble", "Grenoble", 45.1942, 5.7323, "musee de grenoble"),
        p("ornans", "Musée Courbet", "Ornans", 47.1054, 6.1453, "musee courbet"),
        // ── Royaume-Uni, Irlande ──
        p("ngl", "National Gallery", "Londres", 51.5089, -0.1283, "national gallery, london", "national gallery london", "national gallery (london)"),
        p("tate", "Tate Britain / Tate Modern", "Londres", 51.4910, -0.1278, "tate"),
        p("courtauld", "Courtauld Gallery", "Londres", 51.5115, -0.1171, "courtauld"),
        p("va", "Victoria and Albert Museum", "Londres", 51.4966, -0.1722, "victoria and albert"),
        p("fitzwilliam", "Fitzwilliam Museum", "Cambridge", 52.2001, 0.1197, "fitzwilliam"),
        p("ashmolean", "Ashmolean Museum", "Oxford", 51.7555, -1.2601, "ashmolean"),
        p("cardiff", "National Museum Cardiff", "Cardiff", 51.4855, -3.1772, "national museum cardiff", "amgueddfa"),
        p("edinburgh", "Scottish National Gallery", "Édimbourg", 55.9509, -3.1959, "national gallery of scotland", "scottish national gallery", "national galleries of scotland"),
        p("glasgow", "Kelvingrove / Burrell", "Glasgow", 55.8686, -4.2906, "kelvingrove", "burrell", "glasgow museums"),
        p("dublin", "National Gallery of Ireland", "Dublin", 53.3409, -6.2525, "national gallery of ireland"),
        // ── Benelux ──
        p("rijks", "Rijksmuseum", "Amsterdam", 52.3600, 4.8852, "rijksmuseum"),
        p("vgm", "Van Gogh Museum", "Amsterdam", 52.3584, 4.8811, "van gogh museum"),
        p("stedelijk", "Stedelijk Museum", "Amsterdam", 52.3580, 4.8797, "stedelijk"),
        p("kroller", "Kröller-Müller Museum", "Otterlo", 52.0950, 5.8170, "kroller", "kroeller"),
        p("mauritshuis", "Mauritshuis", "La Haye", 52.0804, 4.3143, "mauritshuis"),
        p("kunstmuseumdh", "Kunstmuseum Den Haag", "La Haye", 52.0896, 4.2806, "kunstmuseum den haag", "gemeentemuseum"),
        p("boijmans", "Museum Boijmans Van Beuningen", "Rotterdam", 51.9141, 4.4733, "boijmans"),
        p("brussels", "Musées royaux des Beaux-Arts", "Bruxelles", 50.8417, 4.3577, "musees royaux des beaux-arts", "royal museums of fine arts of belgium", "fine arts of belgium"),
        p("kmska", "KMSKA", "Anvers", 51.2083, 4.3946, "kmska", "koninklijk museum voor schone kunsten antwerpen", "fine arts antwerp"),
        p("ghent", "MSK Gand", "Gand", 51.0386, 3.7231, "msk gent", "museum voor schone kunsten gent", "fine arts ghent"),
        // ── Scandinavie ──
        p("smk", "Statens Museum for Kunst", "Copenhague", 55.6888, 12.5788, "statens museum for kunst", "smk", "national gallery of denmark"),
        p("glyptotek", "Ny Carlsberg Glyptotek", "Copenhague", 55.6726, 12.5727, "glyptotek"),
        p("ordrupgaard", "Ordrupgaard", "Copenhague", 55.7686, 12.5813, "ordrupgaard"),
        p("hirschsprung", "Collection Hirschsprung", "Copenhague", 55.6890, 12.5782, "hirschsprung"),
        p("skagen", "Skagens Museum", "Skagen", 57.7215, 10.5878, "skagens museum"),
        p("stockholm", "Nationalmuseum", "Stockholm", 59.3283, 18.0789, "nationalmuseum", "national museum, stockholm", "nationalmuseum stockholm"),
        p("gothenburg", "Göteborgs konstmuseum", "Göteborg", 57.6967, 11.9800, "goteborgs konstmuseum", "gothenburg museum of art"),
        p("oslo", "Nasjonalmuseet", "Oslo", 59.9114, 10.7290, "nasjonalmuseet", "national museum of art, architecture and design", "nasjonalgalleriet", "national gallery of norway"),
        p("munch", "Musée Munch", "Oslo", 59.9058, 10.7553, "munchmuseet", "munch museum"),
        p("helsinki", "Ateneum", "Helsinki", 60.1700, 24.9440, "ateneum", "finnish national gallery"),
        // ── Allemagne, Autriche, Suisse ──
        p("berlin", "Alte Nationalgalerie", "Berlin", 52.5208, 13.3982, "nationalgalerie", "staatliche museen zu berlin"),
        p("munich", "Neue Pinakothek", "Munich", 48.1497, 11.5719, "pinakothek", "bayerische staatsgemaldesammlungen"),
        p("hamburg", "Hamburger Kunsthalle", "Hambourg", 53.5552, 10.0029, "hamburger kunsthalle", "kunsthalle hamburg"),
        p("bremen", "Kunsthalle Bremen", "Brême", 53.0726, 8.8152, "kunsthalle bremen"),
        p("frankfurt", "Städel Museum", "Francfort", 50.1032, 8.6741, "stadel"),
        p("cologne", "Wallraf-Richartz-Museum", "Cologne", 50.9375, 6.9603, "wallraf"),
        p("essen", "Museum Folkwang", "Essen", 51.4430, 7.0050, "folkwang"),
        p("stuttgart", "Staatsgalerie Stuttgart", "Stuttgart", 48.7800, 9.1870, "staatsgalerie stuttgart"),
        p("dresden", "Galerie Neue Meister", "Dresde", 51.0526, 13.7383, "neue meister", "staatliche kunstsammlungen dresden"),
        p("vienna", "Belvedere", "Vienne", 48.1916, 16.3809, "belvedere"),
        p("albertina", "Albertina", "Vienne", 48.2046, 16.3684, "albertina"),
        p("zurich", "Kunsthaus Zürich", "Zurich", 47.3702, 8.5481, "kunsthaus zurich", "buhrle"),
        p("basel", "Kunstmuseum Basel", "Bâle", 47.5541, 7.5940, "kunstmuseum basel"),
        p("beyeler", "Fondation Beyeler", "Riehen", 47.5876, 7.6532, "beyeler"),
        p("winterthur", "Oskar Reinhart", "Winterthur", 47.5000, 8.7240, "oskar reinhart", "kunstmuseum winterthur"),
        // ── Espagne, Italie ──
        p("sorolla", "Museo Sorolla", "Madrid", 40.4355, -3.6926, "museo sorolla"),
        p("prado", "Museo del Prado", "Madrid", 40.4138, -3.6921, "prado"),
        p("thyssen", "Museo Thyssen-Bornemisza", "Madrid", 40.4160, -3.6949, "thyssen"),
        p("reinasofia", "Museo Reina Sofía", "Madrid", 40.4086, -3.6943, "reina sofia"),
        p("bilbao", "Museo de Bellas Artes de Bilbao", "Bilbao", 43.2675, -2.9375, "bellas artes de bilbao"),
        p("valencia", "Museo de Bellas Artes de Valencia", "Valence", 39.4791, -0.3727, "bellas artes de valencia"),
        p("mnac", "Museu Nacional d'Art de Catalunya", "Barcelone", 41.3685, 2.1534, "museu nacional d'art de catalunya", "mnac"),
        p("florence", "Galleria d'arte moderna (Palazzo Pitti)", "Florence", 43.7651, 11.2499, "palazzo pitti", "galleria d'arte moderna di firenze", "uffizi"),
        p("milan", "Pinacothèque de Brera", "Milan", 45.4719, 9.1880, "brera"),
        p("rome", "Galleria Nazionale d'Arte Moderna", "Rome", 41.9172, 12.4818, "galleria nazionale d'arte moderna", "gnam"),
        p("livorno", "Museo Civico Giovanni Fattori", "Livourne", 43.5450, 10.3110, "museo civico giovanni fattori", "museo fattori"),
        // ── Europe de l'Est ──
        p("hermitage", "Musée de l'Ermitage", "Saint-Pétersbourg", 59.9398, 30.3146, "hermitage", "ermitage"),
        p("pushkin", "Musée Pouchkine", "Moscou", 55.7473, 37.6051, "pushkin", "pouchkine"),
        p("budapest", "Musée des Beaux-Arts de Budapest", "Budapest", 47.5160, 19.0770, "szepmuveszeti", "fine arts, budapest", "fine arts budapest"),
        p("prague", "Galerie nationale de Prague", "Prague", 50.0890, 14.4330, "national gallery prague", "narodni galerie"),
    )

    private fun fold(s: String) = WorkSearch.fold(s)

    /** Le lieu de conservation de l'œuvre, ou `null` s'il n'est pas dans l'annuaire. */
    fun locate(a: Artwork): MuseumPlace? {
        val text = fold((listOf(a.provider) + a.details.filter { it.first in LOCATION_LABELS }.map { it.second }).joinToString(" | "))
        if (text.isBlank()) return null
        return places.firstOrNull { place -> place.keys.any { it in text } }
    }

    private val LOCATION_LABELS = setOf("Collection", "Fournisseur", "Lieu de conservation", "Musée", "Institution")

    /** Repères : un par lieu (les plus fournis d'abord), et les œuvres dont le lieu n'est pas connu. */
    fun clusters(artworks: List<Artwork>): Pair<List<MapCluster>, List<Artwork>> {
        val byPlace = LinkedHashMap<MuseumPlace, MutableList<Artwork>>()
        val unknown = ArrayList<Artwork>()
        for (a in artworks) {
            val p = locate(a)
            if (p == null) unknown += a else byPlace.getOrPut(p) { ArrayList() } += a
        }
        return byPlace.map { (p, list) -> MapCluster(p, list.sortedBy { it.date.positionEpochDay }) }.sortedByDescending { it.artworks.size } to unknown
    }

    /** Projection de Mercator normalisée : x de 0 (−180°) à 1 (+180°), y de 0 (nord, 85°) à 1 (sud, −85°). */
    fun project(lat: Double, lon: Double): Pair<Double, Double> {
        val l = lat.coerceIn(-85.0, 85.0) * PI / 180.0
        val x = (lon + 180.0) / 360.0
        val y = (1.0 - ln(tan(l) + 1.0 / kotlin.math.cos(l)) / PI) / 2.0
        return x to y
    }
}
