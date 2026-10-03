package com.vangoghtimeline

import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.CardSpec
import com.vangoghtimeline.model.CivilCalendar
import com.vangoghtimeline.model.IiifRef
import com.vangoghtimeline.model.TimeScale
import com.vangoghtimeline.model.TimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TimelineEngineTest {
    private fun art(id: String, y: Int, m: Int, d: Int) =
        Artwork(id, id, ArtworkDate.exact(y, m, d), iiif = IiifRef("https://example.org/$id/manifest.json"))

    private val card = CardSpec(width = 100f, height = 80f, gapX = 10f, gapY = 10f)

    @Test fun oneSecondOfScaleIsExact() {
        val origin = CivilCalendar.epochDay(1888, 1, 1)
        val scale = TimeScale(origin, daysPerPixel = 2f)            // 1 px = 2 jours
        assertEquals(0f, scale.xOf(origin), 0f)
        assertEquals(50f, scale.xOf(origin + 100), 0f)              // 100 jours = 50 px
        assertEquals(-5f, scale.xOf(origin - 10), 0f)
        assertEquals(origin + 100.0, scale.dayAt(50f), 1e-9)
        // aller-retour
        val day = CivilCalendar.epochDay(1889, 6, 15)
        assertEquals(day.toDouble(), scale.dayAt(scale.xOf(day)), 1e-3)
    }

    @Test fun pixelPositionFollowsExactDays() {
        val a = art("a", 1888, 10, 1)
        val b = art("b", 1888, 10, 11)                               // 10 jours plus tard
        val plan = TimelineEngine.layout(listOf(a, b), daysPerPixel = 0.5f, card = card)   // 1 px = 0,5 jour
        val xa = plan.items.first { it.artwork.id == "a" }.x
        val xb = plan.items.first { it.artwork.id == "b" }.x
        assertEquals(20f, xb - xa, 1e-3f)                            // 10 jours / 0,5 = 20 px
    }

    @Test fun overlappingArtworksGoToDifferentLanes() {
        val plan = TimelineEngine.layout(listOf(art("a", 1888, 10, 1), art("b", 1888, 10, 2)), daysPerPixel = 1f, card = card)
        val lanes = plan.items.map { it.lane }
        assertEquals(listOf(0, 1), lanes)
        assertEquals(2, plan.laneCount)
    }

    @Test fun distantArtworksShareTheFirstLane() {
        val plan = TimelineEngine.layout(listOf(art("a", 1888, 1, 1), art("b", 1888, 12, 1)), daysPerPixel = 1f, card = card)
        assertEquals(listOf(0, 0), plan.items.map { it.lane })
        assertEquals(1, plan.laneCount)
    }

    @Test fun noTwoCardsOverlapInAnyLane() {
        val rnd = Random(42)
        val base = CivilCalendar.epochDay(1880, 1, 1)
        val arts = (0 until 200).map {
            val (y, m, d) = CivilCalendar.civil(base + rnd.nextInt(3500))
            art("w$it", y, m, d)
        }
        for (dpp in listOf(0.2f, 1f, 3f, 10f)) {
            val plan = TimelineEngine.layout(arts, dpp, card)
            plan.items.groupBy { it.lane }.values.forEach { lane ->
                lane.sortedBy { it.x }.zipWithNext().forEach { (l, r) ->
                    assertTrue("chevauchement à $dpp j/px", l.right + card.gapX <= r.x + 1e-3f)
                }
            }
        }
    }

    @Test fun zoomingOutStacksMoreLanes() {
        val arts = (1..30).map { art("w$it", 1888, 1 + it % 12, 1 + it % 28) }
        val zoomedIn = TimelineEngine.layout(arts, 0.2f, card).laneCount
        val zoomedOut = TimelineEngine.layout(arts, 8f, card).laneCount
        assertTrue(zoomedOut > zoomedIn)
    }

    @Test fun visibleMatchesBruteForce() {
        val rnd = Random(7)
        val base = CivilCalendar.epochDay(1881, 1, 1)
        val arts = (0 until 300).map {
            val (y, m, d) = CivilCalendar.civil(base + rnd.nextInt(3400))
            art("w$it", y, m, d)
        }
        val plan = TimelineEngine.layout(arts, 1.5f, card)
        repeat(200) {
            val l = rnd.nextFloat() * plan.contentWidth - 200f
            val t = rnd.nextFloat() * plan.contentHeight - 100f
            val r = l + rnd.nextFloat() * 900f
            val b = t + rnd.nextFloat() * 700f
            val fast = plan.visible(l, t, r, b).map { it.artwork.id }.toSet()
            val slow = plan.items.filter { it.right > l && it.x < r && it.bottom > t && it.y < b }.map { it.artwork.id }.toSet()
            assertEquals("fenêtre ($l,$t,$r,$b)", slow, fast)
        }
    }

    @Test fun emptyTimeline() {
        val plan = TimelineEngine.layout(emptyList(), 1f, card)
        assertEquals(0, plan.items.size)
        assertEquals(1, plan.laneCount)
        assertTrue(plan.visible(0f, 0f, 500f, 500f).isEmpty())
    }
}
