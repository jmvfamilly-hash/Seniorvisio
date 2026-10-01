package com.vangoghtimeline

import com.vangoghtimeline.model.CylinderProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CylinderProjectionTest {
    private val proj = CylinderProjection(viewportWidth = 1000f)

    @Test fun centerIsAlmostUntouched() {
        assertEquals(0f, proj.project(0f), 1e-4f)
        assertEquals(1f, proj.alpha(proj.angle(0f)), 1e-4f)
        assertEquals(1f, proj.scaleX(proj.angle(0f)), 1e-4f)
        // à 10 % de la demi-largeur, l'écart avec la position « à plat » est inférieur à 1 %
        assertEquals(50f, proj.project(50f), 0.5f)
    }

    @Test fun symmetricAroundTheCenter() {
        for (o in listOf(30f, 200f, 480f, 700f)) {
            assertEquals(-proj.project(o), proj.project(-o), 1e-3f)
            assertEquals(proj.alpha(proj.angle(o)), proj.alpha(proj.angle(-o)), 1e-4f)
        }
    }

    @Test fun compressesTowardTheEdges() {
        // la carte au bord de l'écran apparaît plus près du centre que sa position à plat
        assertTrue(proj.project(500f) < 500f)
        assertTrue(proj.project(500f) > 0f)
        // et ne sort jamais du cylindre
        assertTrue(proj.project(10_000f) <= proj.radius + 1e-3f)
    }

    @Test fun monotonicInOffsetUpToTheSide() {
        var last = -1f
        for (o in 0..1500 step 25) {
            val p = proj.project(o.toFloat())
            assertTrue("project doit croître avec l'offset ($o)", p >= last - 1e-3f)
            last = p
        }
    }

    @Test fun cardsFadeAndNarrowTowardTheEdges() {
        var lastAlpha = 2f
        var lastScale = 2f
        for (o in 0..1500 step 50) {
            val a = proj.angle(o.toFloat())
            val alpha = proj.alpha(a)
            val scale = proj.scaleX(a)
            assertTrue(alpha <= lastAlpha + 1e-4f)
            assertTrue(scale <= lastScale + 1e-4f)
            assertTrue(scale >= CylinderProjection.MIN_SCALE - 1e-6f)
            lastAlpha = alpha; lastScale = scale
        }
        assertEquals(0f, proj.alpha(proj.angle(5000f)), 1e-3f)            // de profil : invisible
    }

    @Test fun rotationHasTheSignOfTheSide() {
        assertTrue(proj.rotationYDegrees(proj.angle(400f)) > 0f)           // droite : le bord droit s'éloigne
        assertTrue(proj.rotationYDegrees(proj.angle(-400f)) < 0f)
        assertEquals(0f, proj.rotationYDegrees(proj.angle(0f)), 1e-4f)
    }

    @Test fun degenerateViewportDoesNotBlowUp() {
        val p = CylinderProjection(0f)
        assertEquals(0f, p.project(0f), 1e-6f)
        assertTrue(p.project(100f).isFinite())
    }
}
