package dev.skyform.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The moon and twilight against known dates (public places only). */
class AstroTest {
    @Test
    fun moonPhase() {
        // 2024-04-08 18:21 UTC: the new moon of the total solar eclipse
        val (litNew, phaseNew) = Astro.moonPhase(1712600460L)
        assertTrue("lit at new moon: $litNew", litNew < 0.01)
        assertEquals("New moon", Astro.phaseName(phaseNew))
        // 2024-04-23 23:49 UTC: full moon
        val (litFull, phaseFull) = Astro.moonPhase(1713916140L)
        assertTrue("lit at full moon: $litFull", litFull > 0.98)
        assertEquals("Full moon", Astro.phaseName(phaseFull))
        // the next full moon after the eclipse day starts on April 23 or 24 (UTC days)
        val next = Astro.nextPhase(1712534400L, 0.5)
        assertTrue("next full moon day: $next", next == 1713830400L || next == 1713916800L)
    }

    @Test
    fun sunAndMoonTimes() {
        // Chicago, 2026-06-21: sunrise about 5:15 AM CDT (10:15 UTC), civil dawn about 4:42 AM CDT
        val noon = 1782000000L + 17 * 3600
        val (rise, set) = Astro.sunTimes(noon, 41.88, -87.63, -0.833)!!
        assertTrue("sunrise ${(rise - 1782000000L) / 60} min after 0:00 UTC", abs(rise - (1782000000L + 10 * 3600 + 15 * 60)) < 10 * 60)
        assertTrue("sunset after sunrise by about 15 h", (set - rise) in 15 * 3600L..15 * 3600L + 30 * 60)
        val (dawn, dusk) = Astro.sunTimes(noon, 41.88, -87.63, -6.0)!!
        assertTrue(dawn < rise && dusk > set)
        // the sun never sets at the North Pole in June
        assertEquals(null, Astro.sunTimes(noon, 89.9, 0.0, -0.833))
        // the moon rises and sets on most days at mid latitudes, within the day asked about
        val dayStart = 1782000000L + 5 * 3600   // local midnight, CDT
        var both = 0
        for (i in 0 until 10) {
            val (mr, ms) = Astro.moonTimes(dayStart + i * 86400L, 41.88, -87.63)
            mr?.let { assertTrue(it in dayStart + i * 86400L..dayStart + (i + 1) * 86400L) }
            ms?.let { assertTrue(it in dayStart + i * 86400L..dayStart + (i + 1) * 86400L) }
            if (mr != null && ms != null) both++
        }
        assertTrue("days with a moonrise and a moonset: $both", both >= 8)
        assertNotNull(Astro.moonTimes(dayStart, 41.88, -87.63).first ?: Astro.moonTimes(dayStart, 41.88, -87.63).second)
    }
}
