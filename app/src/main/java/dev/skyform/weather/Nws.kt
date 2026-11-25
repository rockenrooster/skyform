package dev.skyform.weather

import dev.skyform.weather.update.UrlHttp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The National Weather Service (api.weather.gov: US only, free, no key), the default source. It gives the hourly and
 * 12-hour forecasts with the forecasters' wording, the nearest station's observation and active alerts. Open-Meteo
 * fills what it lacks (UV, sunrise and sunset, rain amounts, visibility, days 8-14) and covers places outside the US.
 */
object Nws {
    private const val API = "https://api.weather.gov"
    private const val ACCEPT = "application/geo+json"

    /** The raw documents for a place ("hourly", "daily", and when they come, "obs", "alerts"); throws outside the US. */
    suspend fun fetch(lat: Double, lon: Double): Map<String, String> = coroutineScope {
        val ll = "%.4f,%.4f".format(Locale.US, lat, lon)
        val pt = JSONObject(get("$API/points/$ll")).getJSONObject("properties")
        val hourly = async { get(pt.getString("forecastHourly")) }
        val daily = async { get(pt.getString("forecast")) }
        val obs = async {
            runCatching {
                val st = JSONObject(get(pt.getString("observationStations") + "?limit=1")).getJSONArray("features")
                    .getJSONObject(0).getJSONObject("properties").getString("stationIdentifier")
                get("$API/stations/$st/observations/latest")
            }.getOrNull()
        }
        val alerts = async { runCatching { get("$API/alerts/active?point=$ll") }.getOrNull() }
        buildMap {
            put("hourly", hourly.await())
            put("daily", daily.await())
            obs.await()?.let { put("obs", it) }
            alerts.await()?.let { put("alerts", it) }
        }
    }

    /** One retry on a server error: the NWS API has brief 5xx hiccups. */
    private fun get(url: String): String = try {
        String(UrlHttp.get(url, 4 shl 20, ACCEPT), Charsets.UTF_8)
    } catch (e: IOException) {
        if (e.message?.startsWith("HTTP 5") != true) throw e
        Thread.sleep(500)
        String(UrlHttp.get(url, 4 shl 20, ACCEPT), Charsets.UTF_8)
    }

    /** The forecast from the documents [fetch] returned; null without an hourly forecast. */
    fun parse(docs: JSONObject, fetchedAt: Long): Forecast? {
        val periods = docs.str("hourly").ifEmpty { return null }.let { JSONObject(it).getJSONObject("properties").getJSONArray("periods") }
            .let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        if (periods.isEmpty()) return null
        val offset = OffsetDateTime.parse(periods[0].getString("startTime")).offset
        val hours = periods.map { p ->
            Hour(epoch(p.getString("startTime")), temp(p), p.v("relativeHumidity").r(), p.v("probabilityOfPrecipitation").r(),
                code(p.str("icon")), p.optBoolean("isDaytime", true), Double.NaN, Double.NaN, p.v("dewpoint"),
                wind(p.str("windSpeed")), 0.0, windDir = degrees(p.str("windDirection")))
        }

        // 12-hour periods: each date's day period gives the high, the night after it the low (as the NWS pairs them)
        val days = docs.str("daily").takeIf { it.isNotEmpty() }?.let { JSONObject(it).getJSONObject("properties").getJSONArray("periods") }
            ?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            .groupBy { OffsetDateTime.parse(it.getString("startTime")).let { t -> t.toLocalDate() to t.offset } }
            .map { (key, ps) ->
                val (date: LocalDate, off) = key
                val day = ps.firstOrNull { it.optBoolean("isDaytime") }
                val night = ps.lastOrNull { !it.optBoolean("isDaytime") }
                val start = date.atStartOfDay().toEpochSecond(off)
                val own = hours.filter { it.time in start until start + 86400 }
                val main = day ?: night!!
                // the high and low take in the date's own hours too: after midnight, the night ahead is not today's low
                fun pick(a: Double, b: Double, f: (Double, Double) -> Double) = if (a.isNaN()) b else if (b.isNaN()) a else f(a, b)
                Day(start, code(main.str("icon")), pick(day?.let(::temp) ?: Double.NaN, own.maxOfOrNull { it.temp } ?: Double.NaN, ::maxOf),
                    pick(night?.let(::temp) ?: Double.NaN, own.minOfOrNull { it.temp } ?: Double.NaN, ::minOf),
                    ps.maxOf { it.v("probabilityOfPrecipitation").r() },
                    0.0, 0, 0, Double.NaN, ps.maxOf { wind(it.str("windSpeed")) }, main.str("shortForecast"),
                    ps.joinToString("\n\n") { "${it.str("name")}: ${it.str("detailedForecast")}" })
            }

        // now: the nearest station's observation when it is recent and has a temperature, else the first forecast hour
        val first = hours[0]
        val obs = docs.str("obs").takeIf { it.isNotEmpty() }?.let { JSONObject(it).getJSONObject("properties") }
            ?.takeIf { o -> !o.v("temperature").isNaN() && fetchedAt / 1000 - epoch(o.getString("timestamp")) < 2 * 3600 }
        val current = if (obs != null) {
            val t = obs.v("temperature")
            Current(epoch(obs.getString("timestamp")), t, obs.v("windChill").or(obs.v("heatIndex")).or(t),
                obs.v("relativeHumidity").takeIf { !it.isNaN() }?.roundToInt() ?: first.humidity,
                obs.str("icon").let { if (it.isEmpty()) first.code else code(it) }, first.isDay, obs.v("windSpeed").or(0.0),
                obs.v("windDirection").takeIf { !it.isNaN() }?.roundToInt() ?: degrees(periods[0].str("windDirection")),
                obs.v("windGust"), obs.v("seaLevelPressure").or(obs.v("barometricPressure")) / 100, 0.0,
                obs.str("textDescription").ifEmpty { periods[0].str("shortForecast") })
        } else {
            Current(first.time, first.temp, Double.NaN, first.humidity, first.code, first.isDay, first.wind,
                degrees(periods[0].str("windDirection")), Double.NaN, Double.NaN, 0.0, periods[0].str("shortForecast"))
        }

        val severity = listOf("Extreme", "Severe", "Moderate", "Minor")
        val alerts = docs.str("alerts").takeIf { it.isNotEmpty() }?.let { JSONObject(it).optJSONArray("features") }
            ?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getJSONObject("properties") } }.orEmpty()
            .map { q ->
                Alert(q.str("event"), q.str("headline"), q.str("severity"), q.str("description"), q.str("instruction"),
                    q.str("ends").ifEmpty { q.str("expires") }.let { if (it.isEmpty()) 0 else epoch(it) })
            }
            .sortedBy { severity.indexOf(it.severity).let { i -> if (i < 0) severity.size else i } }
        return Forecast(fetchedAt, offset, current, hours, days, alerts, "nws")
    }

    /**
     * The NWS forecast with Open-Meteo's UV, visibility, rain and snow amounts, feels-like, clouds, pressure, gusts,
     * sun times, 15-minute precipitation, yesterday's hours (comparisons) and the hours and days after the NWS's last.
     */
    fun fill(n: Forecast, om: Forecast): Forecast {
        val oh = om.hours.associateBy { it.time }
        val hours = n.hours.map { h ->
            oh[h.time]?.let { o ->
                h.copy(uv = o.uv, visibility = o.visibility, precip = o.precip, isDay = o.isDay, feels = o.feels, cloud = o.cloud,
                    pressure = o.pressure, gust = o.gust, snow = o.snow, windDir = if (h.windDir >= 0) h.windDir else o.windDir)
            } ?: h
        }
        val first = n.hours.firstOrNull()?.time ?: Long.MAX_VALUE
        val lastHour = n.hours.lastOrNull()?.time ?: Long.MIN_VALUE
        val od = om.days.associateBy { it.time }
        val last = n.days.lastOrNull()?.time ?: Long.MIN_VALUE
        val days = n.days.map { d ->
            od[d.time]?.let { o ->
                d.copy(precip = o.precip, sunrise = o.sunrise, sunset = o.sunset, uv = o.uv, max = d.max.or(o.max), min = d.min.or(o.min),
                    gust = o.gust, windDir = o.windDir, snow = o.snow, sunshine = o.sunshine, precipHours = o.precipHours)
            } ?: d
        } + om.days.filter { it.time > last }
        val c = n.current.let { c ->
            c.copy(feels = c.feels.or(om.current.feels), gust = c.gust.or(om.current.gust), pressure = c.pressure.or(om.current.pressure),
                precip = om.current.precip, isDay = om.current.isDay, cloud = om.current.cloud)
        }
        return Forecast(n.fetchedAt, n.offset, c, om.hours.filter { it.time < first } + hours + om.hours.filter { it.time > lastHour },
            om.days.filter { it.time < (n.days.firstOrNull()?.time ?: Long.MIN_VALUE) } + days, n.alerts, n.source, om.quarters)
    }

    /** NWS icon URL (".../icons/land/day/rain,80/tsra,60?size=small") to the WMO code of its worst sky. */
    fun code(icon: String): Int {
        val parts = icon.substringAfter("/icons/land/", "").substringBefore("?").split("/").drop(1)
        return parts.map { wmo(it.substringBefore(",")) }.maxByOrNull { Wmo.sky(it).ordinal } ?: 3
    }

    private fun wmo(c: String) = when (c.removePrefix("wind_")) {
        "skc", "hot", "cold" -> 0
        "few" -> 1
        "sct" -> 2
        "bkn", "ovc" -> 3
        "fog", "haze", "smoke", "dust" -> 45
        "rain_showers", "rain_showers_hi" -> 80
        "rain" -> 63
        "snow" -> 73
        "blizzard" -> 75
        "rain_snow", "rain_sleet", "snow_sleet", "sleet" -> 77
        "fzra", "rain_fzra", "snow_fzra" -> 67
        "tsra", "tsra_sct", "tsra_hi", "tornado", "hurricane", "tropical_storm" -> 95
        else -> 3
    }

    /** "8 mph" or "10 to 15 mph" (the higher) in km/h. */
    fun wind(s: String): Double {
        val v = Regex("\\d+").findAll(s).maxOfOrNull { it.value.toDouble() } ?: return 0.0
        return if (s.contains("km/h")) v else v * 1.609344
    }

    /** "NW" to degrees. */
    private fun degrees(s: String): Int {
        val i = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW").indexOf(s)
        return if (i < 0) 0 else (i * 22.5).roundToInt()
    }

    private fun temp(p: JSONObject): Double {
        val t = p.optDouble("temperature")
        return if (p.str("temperatureUnit") == "C") t else (t - 32) * 5 / 9
    }

    private fun epoch(iso: String) = OffsetDateTime.parse(iso).toEpochSecond()
    /** A {"unitCode", "value"} field's value; NaN when null. */
    private fun JSONObject.v(k: String) = optJSONObject(k)?.optDouble("value", Double.NaN) ?: Double.NaN
    /** A string field; "" when missing or null (Android's optString gives "null"). */
    private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k)
    private fun Double.r() = if (isNaN()) 0 else roundToInt()
    private fun Double.or(other: Double) = if (isNaN()) other else this
}
