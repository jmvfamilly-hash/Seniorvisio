package com.iiifviewer

import org.junit.Assert.assertEquals
import org.junit.Test

class DoubleTapZoomTest {
    private val min = 0.25f
    private val max = 2f

    @Test fun fromFitGoesToMax() = assertEquals(max, DoubleTapZoom.targetScale(min, min, max))
    @Test fun fromAnyIntermediateZoomGoesToMax() {
        assertEquals(max, DoubleTapZoom.targetScale(0.5f, min, max))
        assertEquals(max, DoubleTapZoom.targetScale(1.5f, min, max))
    }
    @Test fun fromMaxGoesBackToFit() {
        assertEquals(min, DoubleTapZoom.targetScale(max, min, max))
        assertEquals(min, DoubleTapZoom.targetScale(max * 0.99f, min, max))   // à 1 % du max : atteint
    }
    @Test fun threeDoubleTapsCycle() {
        var s = min
        s = DoubleTapZoom.targetScale(s, min, max); assertEquals(max, s)
        s = DoubleTapZoom.targetScale(s, min, max); assertEquals(min, s)
        s = DoubleTapZoom.targetScale(s, min, max); assertEquals(max, s)
    }
}
