package com.iiifviewer

import org.junit.Assert.assertEquals
import org.junit.Test

class DoubleTapZoomTest {
    private val min = 0.25f
    private val max = 2f      // moitié du maximum = 1,0

    @Test fun firstDoubleTapGoesToHalfOfMax() = assertEquals(1f, DoubleTapZoom.targetScale(min, min, max))

    @Test fun anyZoomBelowHalfGoesToHalf() = assertEquals(1f, DoubleTapZoom.targetScale(0.6f, min, max))

    @Test fun fromHalfOrBetweenGoesToMax() {
        assertEquals(max, DoubleTapZoom.targetScale(1f, min, max))
        assertEquals(max, DoubleTapZoom.targetScale(1.6f, min, max))
    }

    @Test fun fromMaxGoesBackToFit() {
        assertEquals(min, DoubleTapZoom.targetScale(max, min, max))
        assertEquals(min, DoubleTapZoom.targetScale(max * 0.99f, min, max))
    }

    @Test fun fullCycleIsFitHalfMaxFit() {
        var s = min
        s = DoubleTapZoom.targetScale(s, min, max); assertEquals(1f, s)
        s = DoubleTapZoom.targetScale(s, min, max); assertEquals(max, s)
        s = DoubleTapZoom.targetScale(s, min, max); assertEquals(min, s)
        s = DoubleTapZoom.targetScale(s, min, max); assertEquals(1f, s)
    }

    @Test fun smallImageSkipsTheIntermediateStep() {
        // min 0,9 et max 2 : la moitié (1,0) n'est qu'à +11 % de l'image entière → directement le maximum
        assertEquals(2f, DoubleTapZoom.targetScale(0.9f, 0.9f, 2f))
        assertEquals(0.9f, DoubleTapZoom.targetScale(2f, 0.9f, 2f))
    }
}
