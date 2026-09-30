package com.vangoghtimeline

import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.formatFr
import org.junit.Assert.assertEquals
import org.junit.Test

class DateFormattingTest {
    @Test fun onlyShowsWhatIsKnown() {
        assertEquals("1er octobre 1888", ArtworkDate.exact(1888, 10, 1).formatFr())
        assertEquals("15 juin 1889", ArtworkDate.exact(1889, 6, 15).formatFr())
        assertEquals("juin 1889", ArtworkDate.month(1889, 6).formatFr())
        assertEquals("1885", ArtworkDate.year(1885).formatFr())
    }
}
