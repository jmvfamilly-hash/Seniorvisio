package com.vangoghtimeline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vangoghtimeline.model.CivilCalendar
import com.vangoghtimeline.model.TimelinePlan
import com.vangoghtimeline.model.monthNameFr
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Règle du temps, collée en haut : suit le défilement horizontal, pas le vertical.
 * Graduations d'années, puis de mois quand le zoom le permet ; tout est calculé depuis [TimelinePlan.scale],
 * donc la règle est toujours exacte au jour près.
 */
@Composable
fun TimeAxis(plan: TimelinePlan, state: TimelineScrollState, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val yearStyle = remember { TextStyle(color = Color(0xFFE9E2D0), fontSize = 13.sp) }
    val monthStyle = remember { TextStyle(color = Color(0xFF9C978A), fontSize = 10.sp) }

    Canvas(modifier.fillMaxWidth().height(40.dp).background(Color(0xFF15171B))) {
        val sx = state.scrollX // lu ici : seul le dessin est invalidé par le défilement
        val firstDay = floor(plan.scale.dayAt(sx - plan.leftInset)).toLong()
        val lastDay = ceil(plan.scale.dayAt(sx + size.width - plan.leftInset)).toLong()
        val firstYear = CivilCalendar.civil(firstDay).first
        val lastYear = CivilCalendar.civil(lastDay).first
        val pxPerMonth = 30.4f * plan.scale.pixelsPerDay()

        // `drawText` sans taille impose au texte la largeur restante `size.width − x` : pour une étiquette qui
        // commence au-delà du bord droit, elle est NÉGATIVE et Compose lève « maxWidth(-4) must be >= minWidth(0) ».
        // Ce plantage survenait au défilement, dès qu'une graduation passait juste hors de l'écran : on ne dessine
        // une étiquette que si au moins 24 px de largeur lui restent. (Les traits, eux, se dessinent toujours.)
        fun label(text: String, x: Float, y: Float, style: TextStyle) {
            if (x < size.width - 24f) drawText(measurer, text, Offset(x, y), style)
        }

        for (year in firstYear..lastYear) {
            if (pxPerMonth >= 36f) {
                for (month in 2..12) {
                    val x = plan.contentXOf(CivilCalendar.epochDay(year, month, 1)) - sx
                    drawLine(Color(0xFF3A3D44), Offset(x, size.height - 10f), Offset(x, size.height), 1f)
                    if (pxPerMonth >= 64f) {
                        label(monthNameFr(month, short = true), x + 3f, size.height - 26f, monthStyle)
                    }
                }
            }
            val x = plan.contentXOf(CivilCalendar.epochDay(year, 1, 1)) - sx
            drawLine(Color(0xFFB9A96A), Offset(x, 4f), Offset(x, size.height), 2f)
            label(year.toString(), x + 5f, 3f, yearStyle)
        }
    }
}
