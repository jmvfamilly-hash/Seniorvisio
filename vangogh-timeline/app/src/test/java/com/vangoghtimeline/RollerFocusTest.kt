package com.vangoghtimeline

import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.FocusCandidate
import com.vangoghtimeline.model.IiifRef
import com.vangoghtimeline.model.NextScrollOrder
import com.vangoghtimeline.model.PlacedArtwork
import com.vangoghtimeline.model.RollerTopPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RollerFocusTest {
    private val w = 100f
    private fun c(id: String, off: Float, y: Float = 0f) = FocusCandidate(id, off, y)

    @Test fun cardsNearTheCenterStartAndFarOnesDoNot() {
        val p = RollerTopPolicy()
        val ch = p.update(listOf(c("a", 10f), c("b", 400f)), w)
        assertEquals(listOf("a"), ch.active)
        assertEquals(listOf("a"), ch.started)
        assertTrue(ch.released.isEmpty())
    }

    @Test fun hysteresisKeepsACardBetweenEnterAndLeave() {
        val p = RollerTopPolicy()                       // entre à 0,5 largeur, sort à 0,9
        p.update(listOf(c("a", 10f)), w)
        assertEquals(listOf("a"), p.update(listOf(c("a", 70f)), w).active)   // 0,7 : gardée
        val out = p.update(listOf(c("a", 95f)), w)                           // 0,95 : sortie
        assertTrue(out.active.isEmpty())
        assertEquals(listOf("a"), out.released)
        assertTrue(p.update(listOf(c("a", 70f)), w).active.isEmpty())        // 0,7 ne RE-entre pas
    }

    @Test fun releasedWhenTheCardDisappearsFromTheCandidates() {
        val p = RollerTopPolicy()
        p.update(listOf(c("a", 0f)), w)
        assertEquals(listOf("a"), p.update(emptyList(), w).released)
    }

    @Test fun atMostMaxActiveClosestFirstActiveOnesKept() {
        val p = RollerTopPolicy(maxActive = 2)
        val first = p.update(listOf(c("a", 40f), c("b", -30f), c("c", 5f)), w)
        assertEquals(listOf("c", "b"), first.active)
        // « a » est plus proche que « b » maintenant, mais « b » est déjà actif : pas d'éviction tant qu'il reste au sommet
        val next = p.update(listOf(c("a", 0f), c("b", -30f), c("c", 5f)), w)
        assertEquals(setOf("c", "b"), next.active.toSet())
    }

    @Test fun tiesGoTopToBottom() {
        val p = RollerTopPolicy(maxActive = 1)
        assertEquals(listOf("high"), p.update(listOf(c("low", 0f, y = 300f), c("high", 0f, y = 10f)), w).active)
    }

    private fun art(id: String) = Artwork(id, id, ArtworkDate.year(1888), null, null, IiifRef("x"))
    private fun placed(id: String, x: Float, y: Float) = PlacedArtwork(art(id), x, y, 100f, 80f, 0)

    @Test fun nextScrollFacesTheUserFirstThenTopToBottom() {
        // centre à 500 : « mid » (500) et « mid2 » (même colonne, plus bas) d'abord, puis les voisines, du haut vers le bas
        val items = listOf(
            placed("far", 900f, 0f), placed("mid2", 450f, 200f), placed("near", 300f, 0f), placed("mid", 450f, 0f),
        )
        val order = NextScrollOrder.order(items, 500f).map { it.artwork.id }
        assertEquals(listOf("mid", "mid2", "near", "far"), order)
    }

    @Test fun zoneIsWideInTheDirectionOfTravel() {
        val (l0, r0) = NextScrollOrder.zone(1000f, 400f, 0)
        val (l1, r1) = NextScrollOrder.zone(1000f, 400f, 1)
        val (l2, r2) = NextScrollOrder.zone(1000f, 400f, -1)
        assertTrue(r1 > r0 && l1 == l0)
        assertTrue(l2 < l0 && r2 == r0)
    }
}
