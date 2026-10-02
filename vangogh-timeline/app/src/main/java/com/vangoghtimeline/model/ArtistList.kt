package com.vangoghtimeline.model

enum class SortMode(val label: String) { PERIOD("PÉRIODE"), COUNTRY("PAYS"), ALPHA("ALPHABÉTIQUE") }

/** Une ligne de la liste des artistes : un titre de groupe, ou un artiste. */
sealed interface MenuRow {
    data class Header(val text: String) : MenuRow
    data class Item(val artist: Artist) : MenuRow
}

/** Filtre de la recherche (nom, pays, style : sans casse) puis tri ; PÉRIODE et PAYS regroupent sous des titres. Pur : testé sur la JVM. */
fun menuRows(artists: List<Artist>, query: String, sort: SortMode): List<MenuRow> {
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) artists else artists.filter { a -> listOf(a.name, a.origin, a.mainStyle, a.emblematicWork).any { it.lowercase().contains(q) } }
    return when (sort) {
        SortMode.ALPHA -> shown.sortedBy { it.name.lowercase() }.map { MenuRow.Item(it) }
        SortMode.COUNTRY -> shown.groupBy { it.origin.ifBlank { "—" } }.toSortedMap()
            .flatMap { (country, list) -> listOf<MenuRow>(MenuRow.Header(country.uppercase())) + list.sortedBy { it.birthYear ?: it.activeStart ?: 0 }.map { MenuRow.Item(it) } }
        // comme la maquette : les plus récents en haut (après l'impressionnisme), les plus anciens en bas
        SortMode.PERIOD -> listOf(Movement.POST_IMPRESSIONISM, Movement.IMPRESSIONISM, Movement.PRE_IMPRESSIONISM).flatMap { m ->
            val group = shown.filter { it.movement == m }.sortedBy { it.birthYear ?: it.activeStart ?: 0 }
            if (group.isEmpty()) emptyList() else listOf<MenuRow>(MenuRow.Header(m.labelFr.uppercase())) + group.map { MenuRow.Item(it) }
        }
    }
}
