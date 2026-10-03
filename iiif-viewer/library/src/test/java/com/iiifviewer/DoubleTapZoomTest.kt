package com.iiifviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class DoubleTapZoomTest {
    private val min = 0.25f
    private val max = 2f
    private val mid = sqrt(min * max)      // ≈ 0,707 : milieu perceptif (×2,83 de l'image entière, ×2,83 jusqu'au maximum)

    @Test fun stepIsTheGeometricMeanSoBothJumpsHaveTheSameFactor() {
        val step = DoubleTapZoom.targetScale(min, min, max)
        assertEquals(mid, step, 1e-6f)
        assertEquals(step / min, max / step, 1e-4f)
    }

    @Test fun anyZoomBelowTheStepGoesToTheStep() = assertEquals(mid, DoubleTapZoom.targetScale(0.5f, min, max), 1e-6f)

    @Test fun fromTheStepOrBetweenGoesToMax() {
        assertEquals(max, DoubleTapZoom.targetScale(mid, min, max))
        assertEquals(max, DoubleTapZoom.targetScale(1.6f, min, max))
    }

    @Test fun fromMaxGoesBackToFit() {
        assertEquals(min, DoubleTapZoom.targetScale(max, min, max))
        assertEquals(min, DoubleTapZoom.targetScale(max * 0.99f, min, max))
    }

    @Test fun fullCycleIsFitStepMaxFit() {
        var s = min
        s = DoubleTapZoom.targetScale(s, min, max); assertEquals(mid, s, 1e-6f)
        s = DoubleTapZoom.targetScale(s, min, max); assertEquals(max, s)
        s = DoubleTapZoom.targetScale(s, min, max); assertEquals(min, s)
        s = DoubleTapZoom.targetScale(s, min, max); assertEquals(mid, s, 1e-6f)
    }

    @Test fun eightTimesRangeGivesTwoPointEightTimes() {
        val step = DoubleTapZoom.targetScale(1f, 1f, 8f)
        assertEquals(2.828f, step, 1e-3f)
    }

    @Test fun smallImageSkipsTheIntermediateStep() {
        // min 0,9 et max 1,1 : l'étape (≈ 0,995) n'est qu'à +10 % de l'image entière → directement le maximum
        assertEquals(1.1f, DoubleTapZoom.targetScale(0.9f, 0.9f, 1.1f))
        assertTrue(DoubleTapZoom.targetScale(1.1f, 0.9f, 1.1f) == 0.9f)
    }
}
