package dev.skyform.weather

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** A saved place. [id] "gps" is the phone's own location, refreshed on every open. Times below are epoch seconds. */
data class Place(val id: String, val name: String, val detail: String, val lat: Double, val lon: Double) {
    val gps get() = id == GPS
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("detail", detail).put("lat", lat).put("lon", lon)

    companion object {
        const val GPS = "gps"
        fun fromJson(o: JSONObject) = Place(o.getString("id"), o.getString("name"), o.optString("detail"), o.getDouble("lat"), o.getDouble("lon"))
        fun city(name: String, detail: String, lat: Double, lon: Double) = Place("c:%.3f,%.3f".format(Locale.US, lat, lon), name, detail, lat, lon)
    }
}

// Metric throughout (°C, km/h, mm, hPa, m); [Units] converts for display. NaN: unknown. [text]: the provider's own
// wording ("Chance Light Rain"); empty means use the code's.
data class Current(val time: Long, val temp: Double, val feels: Double, val humidity: Int, val code: Int, val isDay: Boolean,
                   val wind: Double, val windDir: Int, val gust: Double, val pressure: Double, val precip: Double,
                   val text: String = "")

data class Hour(val time: Long, val temp: Double, val humidity: Int, val pop: Int, val code: Int, val isDay: Boolean,
                val uv: Double, val visibility: Double, val dew: Double, val wind: Double, val precip: Double)

/** [detail]: the forecaster's paragraphs for the day and the night (NWS). */
data class Day(val time: Long, val code: Int, val max: Double, val min: Double, val pop: Int, val precip: Double,
               val sunrise: Long, val sunset: Long, val uv: Double, val wind: Double, val text: String = "", val detail: String = "")

/** A watch, warning or advisory (NWS). [ends]: epoch seconds, 0 when open-ended. */
data class Alert(val event: String, val headline: String, val severity: String, val description: String,
                 val instruction: String, val ends: Long)

/**
 * [source]: "nws" (National Weather Service, Open-Meteo filling gaps) or "om" (Open-Meteo alone). Never changed once
 * made (a refresh makes a new one), which lets Compose skip redrawing a page whose forecast is the same.
 */
@Immutable
class Forecast(val fetchedAt: Long, val offset: ZoneOffset, val current: Current, val hours: List<Hour>, val days: List<Day>,
               val alerts: List<Alert> = emptyList(), val source: String = "om") {
    /** Hours from the one that contains [now]. */
    fun hoursFrom(now: Long): List<Hour> = hours.dropWhile { it.time + 3600 <= now }
    /** Days from today (at the place) on. */
    fun daysFrom(now: Long): List<Day> = days.dropWhile { it.time + 86400 <= now }
}

/** Open-Meteo's forecast API (free, no key; CC BY 4.0, credited in the app). */
object OpenMeteo {
    private const val CURRENT = "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code," +
        "pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
    private const val HOURLY = "temperature_2m,relative_humidity_2m,precipitation_probability,precipitation,weather_code,is_day," +
        "uv_index,visibility,dew_point_2m,wind_speed_10m"
    private const val DAILY = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum," +
        "sunrise,sunset,uv_index_max,wind_speed_10m_max"

    fun url(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=$CURRENT&hourly=$HOURLY&daily=$DAILY"
            .format(Locale.US, lat, lon) + "&timezone=auto&timeformat=unixtime&forecast_days=14"

    fun parse(json: String, fetchedAt: Long): Forecast {
        val o = JSONObject(json)
        val c = o.getJSONObject("current")
        val h = o.getJSONObject("hourly")
        val d = o.getJSONObject("daily")
        val current = Current(
            c.optLong("time"), c.optDouble("temperature_2m"), c.optDouble("apparent_temperature"),
            c.optDouble("relative_humidity_2m", 0.0).roundToInt(), c.optInt("weather_code"), c.optInt("is_day", 1) == 1,
            c.optDouble("wind_speed_10m", 0.0), c.optInt("wind_direction_10m"), c.optDouble("wind_gusts_10m", 0.0),
            c.optDouble("pressure_msl"), c.optDouble("precipitation", 0.0),
        )
        val hours = (0 until h.getJSONArray("time").length()).map { i ->
            Hour(h.l("time", i), h.num("temperature_2m", i), h.int("relative_humidity_2m", i), h.int("precipitation_probability", i),
                h.int("weather_code", i), h.int("is_day", i) == 1, h.num("uv_index", i), h.num("visibility", i),
                h.num("dew_point_2m", i), h.num("wind_speed_10m", i), h.num("precipitation", i))
        }.filter { !it.temp.isNaN() }
        val days = (0 until d.getJSONArray("time").length()).map { i ->
            Day(d.l("time", i), d.int("weather_code", i), d.num("temperature_2m_max", i), d.num("temperature_2m_min", i),
                d.int("precipitation_probability_max", i), d.num("precipitation_sum", i), d.l("sunrise", i), d.l("sunset", i),
                d.num("uv_index_max", i), d.num("wind_speed_10m_max", i))
        }.filter { !it.max.isNaN() }
        return Forecast(fetchedAt, ZoneOffset.ofTotalSeconds(o.optInt("utc_offset_seconds")), current, hours, days)
    }

    /** The geocoding API's search results as places. */
    fun parseSearch(json: String): List<Place> {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { r ->
            val detail = listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.distinct().joinToString(", ")
            Place.city(r.getString("name"), detail, r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    private fun JSONObject.arr(k: String): JSONArray? = optJSONArray(k)
    private fun JSONObject.num(k: String, i: Int) = arr(k)?.optDouble(i, Double.NaN) ?: Double.NaN
    private fun JSONObject.int(k: String, i: Int) = num(k, i).let { if (it.isNaN()) 0 else it.roundToInt() }
    private fun JSONObject.l(k: String, i: Int) = arr(k)?.optLong(i) ?: 0L
}

/** The WMO weather codes Open-Meteo uses, grouped by how they look. */
enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDER }

object Wmo {
    fun sky(code: Int) = when (code) {
        0 -> Sky.CLEAR
        1, 2 -> Sky.PARTLY
        45, 48 -> Sky.FOG
        51, 53, 55, 56, 57 -> Sky.DRIZZLE
        61, 63, 65, 66, 67, 80, 81, 82 -> Sky.RAIN
        71, 73, 75, 77, 85, 86 -> Sky.SNOW
        95, 96, 99 -> Sky.THUNDER
        else -> Sky.CLOUDY
    }

    fun icon(code: Int, isDay: Boolean) = when (sky(code)) {
        Sky.CLEAR -> if (isDay) R.drawable.wx_clear_day else R.drawable.wx_clear_night
        Sky.PARTLY -> if (isDay) R.drawable.wx_partly_day else R.drawable.wx_partly_night
        Sky.CLOUDY -> R.drawable.wx_cloudy
        Sky.FOG -> R.drawable.wx_fog
        Sky.DRIZZLE -> R.drawable.wx_drizzle
        Sky.RAIN -> R.drawable.wx_rain
        Sky.SNOW -> R.drawable.wx_snow
        Sky.THUNDER -> R.drawable.wx_thunder
    }

    fun text(code: Int, isDay: Boolean = true) = when (code) {
        0 -> if (isDay) "Sunny" else "Clear"
        1 -> if (isDay) "Mostly sunny" else "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45 -> "Fog"
        48 -> "Freezing fog"
        51 -> "Light drizzle"
        53 -> "Drizzle"
        55 -> "Heavy drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Heavy showers"
        85 -> "Snow showers"
        86 -> "Heavy snow showers"
        95 -> "Thunderstorms"
        96, 99 -> "Thunderstorms with hail"
        else -> "Cloudy"
    }
}

/** Display units. Data stays metric; these convert and format it. */
data class Units(val fahrenheit: Boolean, val wind: String, val clock24: Boolean, val inches: Boolean) {
    fun tv(c: Double) = if (fahrenheit) c * 9 / 5 + 32 else c
    fun t(c: Double) = if (c.isNaN()) "–" else "${tv(c).roundToInt()}°"
    val tName get() = if (fahrenheit) "°F" else "°C"

    fun windV(kmh: Double) = when (wind) { "mph" -> kmh / 1.609344; "ms" -> kmh / 3.6; else -> kmh }
    fun w(kmh: Double) = "${windV(kmh).roundToInt()} ${windName}"
    val windName get() = when (wind) { "mph" -> "mph"; "ms" -> "m/s"; else -> "km/h" }

    fun p(mm: Double) = if (inches) "%.2f in".format(mm / 25.4) else "%.1f mm".format(mm)
    fun pressure(hpa: Double) = if (hpa.isNaN()) "–" else if (inches) "%.2f inHg".format(hpa * 0.02953) else "${hpa.roundToInt()} hPa"
    fun dist(m: Double) = when {
        m.isNaN() -> "–"
        inches -> (m / 1609.344).let { if (it >= 10) "${it.roundToInt()} mi" else "%.1f mi".format(it) }
        else -> (m / 1000).let { if (it >= 10) "${it.roundToInt()} km" else "%.1f km".format(it) }
    }

    private fun fmt(p: String) = DateTimeFormatter.ofPattern(p, Locale.getDefault())
    private fun at(epoch: Long, off: ZoneOffset) = Instant.ofEpochSecond(epoch).atOffset(off)
    /** "5 PM" / "17:00" */
    fun hour(epoch: Long, off: ZoneOffset): String = at(epoch, off).format(fmt(if (clock24) "HH:mm" else "h a"))
    /** "5p" / "17" (the widget's columns) */
    fun hourShort(epoch: Long, off: ZoneOffset): String = at(epoch, off).let {
        if (clock24) "%d".format(it.hour) else "${if (it.hour % 12 == 0) 12 else it.hour % 12}${if (it.hour < 12) "a" else "p"}"
    }
    /** "6:42 AM" / "06:42" */
    fun clock(epoch: Long, off: ZoneOffset): String = at(epoch, off).format(fmt(if (clock24) "HH:mm" else "h:mm a"))
    fun weekday(epoch: Long, off: ZoneOffset): String = at(epoch, off).format(fmt("EEE"))
    fun date(epoch: Long, off: ZoneOffset): String = at(epoch, off).format(fmt("EEE, MMM d"))

    companion object {
        /** Imperial where it is used: the US, Liberia, Myanmar. */
        fun default(clock24: Boolean): Units {
            val imperial = Locale.getDefault().country in setOf("US", "LR", "MM")
            return Units(imperial, if (imperial) "mph" else "kmh", clock24, imperial)
        }
    }
}
