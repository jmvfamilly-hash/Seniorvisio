package com.vangoghtimeline

import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.CivilCalendar
import com.vangoghtimeline.model.DatePrecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CivilCalendarTest {
    @Test fun matchesJavaTimeOverTwoCenturies() {
        // 1800-01-01 .. 2100-12-31, tous les jours : le calendrier maison doit coller à java.time.
        var d = LocalDate.of(1800, 1, 1)
        val end = LocalDate.of(2100, 12, 31)
        while (!d.isAfter(end)) {
            assertEquals(d.toString(), d.toEpochDay(), CivilCalendar.epochDay(d.year, d.monthValue, d.dayOfMonth))
            assertEquals(d.toString(), Triple(d.year, d.monthValue, d.dayOfMonth), CivilCalendar.civil(d.toEpochDay()))
            d = d.plusDays(1)
        }
    }

    @Test fun knownVanGoghDates() {
        assertEquals(LocalDate.of(1853, 3, 30).toEpochDay(), CivilCalendar.epochDay(1853, 3, 30)) // naissance
        assertEquals(LocalDate.of(1890, 7, 29).toEpochDay(), CivilCalendar.epochDay(1890, 7, 29)) // mort
        // 37 ans, 4 mois, 29 jours de vie
        assertEquals(LocalDate.of(1890, 7, 29).toEpochDay() - LocalDate.of(1853, 3, 30).toEpochDay(),
            CivilCalendar.epochDay(1890, 7, 29) - CivilCalendar.epochDay(1853, 3, 30))
    }

    @Test fun leapYears() {
        assertEquals(29, CivilCalendar.daysInMonth(1888, 2))   // 1888 est bissextile : Arles
        assertEquals(28, CivilCalendar.daysInMonth(1889, 2))
        assertEquals(28, CivilCalendar.daysInMonth(1900, 2))   // siècle non bissextile
        assertEquals(29, CivilCalendar.daysInMonth(2000, 2))
    }

    @Test fun parseIsoPrecision() {
        assertEquals(ArtworkDate.exact(1888, 10, 1), ArtworkDate.parseIso("1888-10-01T00:00:00Z"))
        assertEquals(ArtworkDate.month(1889, 6), ArtworkDate.parseIso("1889-06"))
        assertEquals(ArtworkDate.year(1885), ArtworkDate.parseIso("1885"))
        assertNull(ArtworkDate.parseIso("pas une date"))
        assertNull(ArtworkDate.parseIso("1889-02-30"))
    }

    @Test fun forcedPrecisionCannotBeFinerThanTheText() {
        // navDate est toujours une dateTime complète ; les métadonnées disent que seul le mois est connu.
        val d = ArtworkDate.parseIso("1888-10-01T00:00:00Z", DatePrecision.MONTH)!!
        assertEquals(DatePrecision.MONTH, d.precision)
        assertEquals(ArtworkDate.month(1888, 10), d)
        // …mais on ne peut pas inventer un jour que le texte n'a pas.
        assertEquals(DatePrecision.MONTH, ArtworkDate.parseIso("1888-10", DatePrecision.DAY)!!.precision)
    }

    @Test fun positionDependsOnPrecision() {
        assertEquals(CivilCalendar.epochDay(1888, 10, 1), ArtworkDate.exact(1888, 10, 1).positionEpochDay)
        assertEquals(CivilCalendar.epochDay(1888, 10, 15), ArtworkDate.month(1888, 10).positionEpochDay)
        assertEquals(CivilCalendar.epochDay(1888, 7, 2), ArtworkDate.year(1888).positionEpochDay)
    }
}
