package com.vangoghtimeline.model

/**
 * Calendrier grégorien proleptique, sans dépendance : conversion date civile ⇄ jours depuis le 1970-01-01.
 * (Algorithme de Howard Hinnant, « days_from_civil » / « civil_from_days ».)
 *
 * On travaille en jours entiers (« epoch day ») pour que l'axe du temps soit exact : une date = un entier,
 * sans fuseau horaire ni heure d'été qui décaleraient une œuvre d'un jour.
 */
object CivilCalendar {

    fun isLeap(year: Int) = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeap(year)) 29 else 28
        else -> throw IllegalArgumentException("mois invalide: $month")
    }

    fun isValid(year: Int, month: Int, day: Int) =
        month in 1..12 && day in 1..daysInMonth(year, month)

    /** Jours écoulés depuis 1970-01-01 (négatif avant). */
    fun epochDay(year: Int, month: Int, day: Int): Long {
        require(isValid(year, month, day)) { "date invalide: $year-$month-$day" }
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400                                   // [0, 399]
        val mp = ((month + 9) % 12).toLong()                      // mars = 0 … février = 11
        val doy = (153 * mp + 2) / 5 + day - 1                    // [0, 365]
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy           // [0, 146096]
        return era * 146097 + doe - 719468
    }

    /** Inverse de [epochDay] : (année, mois, jour). */
    fun civil(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097                                // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        return Triple(year, month, day)
    }
}
