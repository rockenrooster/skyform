package dev.skyform.weather

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The moon's phase, moonrise and moonset, and the sun's twilight times, computed on the phone (no service has them).
 * The formulas are SunCalc's (Vladimir Agafonkin, BSD-2-Clause), after "Astronomy Answers" by Paul Schlyter: good to
 * a few minutes, which is what a weather app shows. Times are epoch seconds.
 */
object Astro {
    private const val RAD = PI / 180
    private const val E = RAD * 23.4397   // the obliquity of the Earth

    /** Days since J2000.0 (2000-01-01 12:00 UTC). */
    private fun days(epoch: Double) = epoch / 86400.0 - 10957.5
    private fun epochOf(days: Double) = ((days + 10957.5) * 86400).roundToLong()

    private fun ra(l: Double, b: Double) = atan2(sin(l) * cos(E) - tan(b) * sin(E), cos(l))
    private fun dec(l: Double, b: Double) = asin(sin(b) * cos(E) + cos(b) * sin(E) * sin(l))
    private fun solarAnomaly(d: Double) = RAD * (357.5291 + 0.98560028 * d)
    private fun eclipticLongitude(m: Double) =
        m + RAD * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m)) + RAD * 102.9372 + PI

    private class Coords(val ra: Double, val dec: Double, val dist: Double)

    private fun sun(d: Double): Coords = eclipticLongitude(solarAnomaly(d)).let { l -> Coords(ra(l, 0.0), dec(l, 0.0), 149598000.0) }

    private fun moon(d: Double): Coords {
        val l0 = RAD * (218.316 + 13.176396 * d)   // ecliptic longitude
        val m = RAD * (134.963 + 13.064993 * d)    // mean anomaly
        val f = RAD * (93.272 + 13.229350 * d)     // mean distance
        val l = l0 + RAD * 6.289 * sin(m)
        val b = RAD * 5.128 * sin(f)
        return Coords(ra(l, b), dec(l, b), 385001 - 20905 * cos(m))
    }

    /** The moon at [epoch]: (lit fraction 0-1, phase: 0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter). */
    fun moonPhase(epoch: Long): Pair<Double, Double> {
        val d = days(epoch.toDouble())
        val s = sun(d)
        val m = moon(d)
        val phi = acos(sin(s.dec) * sin(m.dec) + cos(s.dec) * cos(m.dec) * cos(s.ra - m.ra))
        val inc = atan2(s.dist * sin(phi), m.dist - s.dist * cos(phi))
        val angle = atan2(cos(s.dec) * sin(s.ra - m.ra), sin(s.dec) * cos(m.dec) - cos(s.dec) * sin(m.dec) * cos(s.ra - m.ra))
        return (1 + cos(inc)) / 2 to 0.5 + 0.5 * inc * (if (angle < 0) -1 else 1) / PI
    }

    fun phaseName(phase: Double) = when {
        phase < 0.034 || phase > 0.966 -> "New moon"
        phase < 0.216 -> "Waxing crescent"
        phase < 0.284 -> "First quarter"
        phase < 0.466 -> "Waxing gibbous"
        phase < 0.534 -> "Full moon"
        phase < 0.716 -> "Waning gibbous"
        phase < 0.784 -> "Last quarter"
        else -> "Waning crescent"
    }

    /** The start of the first day from [dayStart] on whose noon is past [target] phase (0.5: the next full moon). */
    fun nextPhase(dayStart: Long, target: Double): Long {
        var prev = moonPhase(dayStart + 43200).second
        for (i in 1..31) {
            val p = moonPhase(dayStart + i * 86400L + 43200).second
            val crossed = if (target == 0.0) p < prev else prev < target && p >= target
            if (crossed) return dayStart + i * 86400L
            prev = p
        }
        return dayStart
    }

    private fun moonAltitude(epoch: Double, lat: Double, lon: Double): Double {
        val d = days(epoch)
        val c = moon(d)
        val h = RAD * (280.16 + 360.9856235 * d) - RAD * -lon - c.ra
        val phi = RAD * lat
        val alt = asin(sin(phi) * sin(c.dec) + cos(phi) * cos(c.dec) * cos(h))
        val a = alt.coerceAtLeast(0.0)
        return alt + 0.0002967 / tan(a + 0.00312536 / (a + 0.08901179))   // refraction
    }

    /** Moonrise and moonset in the 24 hours from [dayStart]; null when the moon does not rise or set then. */
    fun moonTimes(dayStart: Long, lat: Double, lon: Double): Pair<Long?, Long?> {
        val hc = 0.133 * RAD
        fun alt(h: Double) = moonAltitude(dayStart + h * 3600, lat, lon) - hc
        var h0 = alt(0.0)
        var rise: Double? = null
        var set: Double? = null
        // two-hour steps: where a parabola through three altitudes crosses the horizon
        for (i in 1..23 step 2) {
            val h1 = alt(i.toDouble())
            val h2 = alt(i + 1.0)
            val a = (h0 + h2) / 2 - h1
            val b = (h2 - h0) / 2
            val xe = -b / (2 * a)
            val ye = (a * xe + b) * xe + h1
            val disc = b * b - 4 * a * h1
            var roots = 0
            var x1 = 0.0
            var x2 = 0.0
            if (disc >= 0) {
                val dx = sqrt(disc) / (abs(a) * 2)
                x1 = xe - dx
                x2 = xe + dx
                if (abs(x1) <= 1) roots++
                if (abs(x2) <= 1) roots++
                if (x1 < -1) x1 = x2
            }
            if (roots == 1) {
                if (h0 < 0) rise = i + x1 else set = i + x1
            } else if (roots == 2) {
                rise = i + if (ye < 0) x2 else x1
                set = i + if (ye < 0) x1 else x2
            }
            if (rise != null && set != null) break
            h0 = h2
        }
        return rise?.let { dayStart + (it * 3600).roundToLong() } to set?.let { dayStart + (it * 3600).roundToLong() }
    }

    /**
     * When the sun's center is at [angle] degrees of altitude, morning and evening, on the day around [noon]:
     * -0.833 sunrise and sunset, -6 civil dawn and dusk, 6 the end and start of the golden hour. Null when it never is.
     */
    fun sunTimes(noon: Long, lat: Double, lon: Double, angle: Double): Pair<Long, Long>? {
        val lw = RAD * -lon
        val phi = RAD * lat
        val n = Math.round(days(noon.toDouble()) - 0.0009 - lw / (2 * PI)).toDouble()
        val ds = 0.0009 + lw / (2 * PI) + n
        val m = solarAnomaly(ds)
        val l = eclipticLongitude(m)
        val dec = dec(l, 0.0)
        val transit = ds + 0.0053 * sin(m) - 0.0069 * sin(2 * l)
        val cosW = (sin(angle * RAD) - sin(phi) * sin(dec)) / (cos(phi) * cos(dec))
        if (cosW !in -1.0..1.0) return null
        val set = 0.0009 + (acos(cosW) + lw) / (2 * PI) + n + 0.0053 * sin(m) - 0.0069 * sin(2 * l)
        return epochOf(transit - (set - transit)) to epochOf(set)
    }
}
