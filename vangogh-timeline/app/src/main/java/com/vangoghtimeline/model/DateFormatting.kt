package com.vangoghtimeline.model

private val MONTHS_FR = listOf(
    "janvier", "février", "mars", "avril", "mai", "juin",
    "juillet", "août", "septembre", "octobre", "novembre", "décembre",
)
private val MONTHS_FR_SHORT = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")

fun monthNameFr(month: Int, short: Boolean = false): String = (if (short) MONTHS_FR_SHORT else MONTHS_FR)[month - 1]

/** « 1ᵉʳ octobre 1888 » / « octobre 1888 » / « 1888 » : on n'affiche que ce qui est réellement connu. */
fun ArtworkDate.formatFr(): String = if (estimated) "vers $year (date inconnue)" else when (precision) {
    DatePrecision.DAY -> "${if (day == 1) "1er" else day.toString()} ${monthNameFr(month)} $year"
    DatePrecision.MONTH -> "${monthNameFr(month)} $year"
    DatePrecision.YEAR -> year.toString()
}
