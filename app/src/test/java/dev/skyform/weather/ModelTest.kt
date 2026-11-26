package dev.skyform.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Open-Meteo parsing (nulls included), hour/day windows and unit formatting. */
class ModelTest {
    // Shaped like api.open-meteo.com/v1/forecast with timeformat=unixtime (3 hours, 2 days; a null where the API puts them)
    private val json = """
        {"latitude":41.9,"longitude":-87.6,"utc_offset_seconds":-21600,"timezone":"America/Chicago",
         "current":{"time":1795620600,"interval":900,"temperature_2m":13.3,"relative_humidity_2m":80,"apparent_temperature":11.9,
                    "is_day":0,"precipitation":0.0,"weather_code":3,"pressure_msl":1016.2,"wind_speed_10m":9.4,
                    "wind_direction_10m":200,"wind_gusts_10m":22.3},
         "hourly":{"time":[1795618800,1795622400,1795626000],
                   "temperature_2m":[13.5,13.1,12.6],"relative_humidity_2m":[80,83,null],
                   "precipitation_probability":[21,55,null],"precipitation":[0.0,0.4,1.1],"weather_code":[3,61,63],
                   "is_day":[0,0,0],"uv_index":[0.0,0.0,0.0],"visibility":[24140.0,16000.0,9000.0],
                   "dew_point_2m":[10.1,10.3,10.4],"wind_speed_10m":[9.4,10.1,11.0]},
         "daily":{"time":[1795586400,1795672800],"weather_code":[61,3],"temperature_2m_max":[15.6,11.1],
                  "temperature_2m_min":[10.6,6.7],"precipitation_probability_max":[96,7],"precipitation_sum":[5.2,0.0],
                  "sunrise":[1795611960,1795698420],"sunset":[1795648200,1795734580],"uv_index_max":[2.1,3.0],
                  "wind_speed_10m_max":[18.0,14.2]}}
    """.trimIndent()

    @Test
    fun parses() {
        val f = OpenMeteo.parse(json, 42L)
        assertEquals(ZoneOffset.ofHours(-6), f.offset)
        assertEquals(3, f.current.code)
        assertEquals(false, f.current.isDay)
        assertEquals(3, f.hours.size)
        assertEquals("null humidity becomes 0", 0, f.hours[2].humidity)
        assertEquals(55, f.hours[1].pop)
        assertEquals(2, f.days.size)
        assertEquals(96, f.days[0].pop)
        assertEquals(1795648200L, f.days[0].sunset)
        // the hour holding "now" comes first; days start today
        assertEquals(1795622400L, f.hoursFrom(1795623000L).first().time)
        assertEquals(1795672800L, f.daysFrom(1795680000L).first().time)
    }

    // Shaped like api.weather.gov's documents (trimmed), in the same hours and days as [json]
    private fun nwsDocs() = org.json.JSONObject().apply {
        put("om", json)
        put("hourly", """{"properties":{"periods":[
          {"startTime":"2026-11-25T09:00:00-06:00","isDaytime":true,"temperature":56,"temperatureUnit":"F",
           "probabilityOfPrecipitation":{"value":21},"dewpoint":{"value":10.0},"relativeHumidity":{"value":80},
           "windSpeed":"8 mph","windDirection":"SE","icon":"https://api.weather.gov/icons/land/day/bkn?size=small","shortForecast":"Mostly Cloudy"},
          {"startTime":"2026-11-25T10:00:00-06:00","isDaytime":true,"temperature":55,"temperatureUnit":"F",
           "probabilityOfPrecipitation":{"value":55},"dewpoint":{"value":10.3},"relativeHumidity":{"value":83},
           "windSpeed":"10 to 15 mph","windDirection":"S","icon":"https://api.weather.gov/icons/land/day/rain,55?size=small","shortForecast":"Chance Light Rain"},
          {"startTime":"2026-11-25T11:00:00-06:00","isDaytime":true,"temperature":54,"temperatureUnit":"F",
           "probabilityOfPrecipitation":{"value":null},"dewpoint":{"value":10.4},"relativeHumidity":{"value":85},
           "windSpeed":"12 mph","windDirection":"S","icon":"https://api.weather.gov/icons/land/day/tsra,40/rain,60?size=small","shortForecast":"Showers And Thunderstorms"}]}}""")
        put("daily", """{"properties":{"periods":[
          {"name":"Today","startTime":"2026-11-25T06:00:00-06:00","isDaytime":true,"temperature":61,"temperatureUnit":"F",
           "probabilityOfPrecipitation":{"value":60},"windSpeed":"5 to 10 mph","icon":"https://api.weather.gov/icons/land/day/rain,60?size=medium",
           "shortForecast":"Rain Likely","detailedForecast":"Rain likely. Cloudy, with a high near 61."},
          {"name":"Tonight","startTime":"2026-11-25T18:00:00-06:00","isDaytime":false,"temperature":44,"temperatureUnit":"F",
           "probabilityOfPrecipitation":{"value":80},"windSpeed":"10 mph","icon":"https://api.weather.gov/icons/land/night/rain,80/tsra,30?size=medium",
           "shortForecast":"Rain","detailedForecast":"Rain. Low around 44."},
          {"name":"Wednesday","startTime":"2026-11-26T06:00:00-06:00","isDaytime":true,"temperature":52,"temperatureUnit":"F",
           "probabilityOfPrecipitation":{"value":null},"windSpeed":"5 mph","icon":"https://api.weather.gov/icons/land/day/sct?size=medium",
           "shortForecast":"Partly Sunny","detailedForecast":null}]}}""")
        put("obs", """{"properties":{"timestamp":"2026-11-25T15:20:00+00:00","textDescription":"Cloudy",
          "icon":"https://api.weather.gov/icons/land/day/ovc?size=medium","temperature":{"value":14},"windChill":{"value":null},
          "heatIndex":{"value":null},"relativeHumidity":{"value":71.5},"windSpeed":{"value":22.2},"windDirection":{"value":130},
          "windGust":{"value":null},"seaLevelPressure":{"value":null},"barometricPressure":{"value":100914}}}""")
        put("alerts", """{"features":[
          {"properties":{"event":"Coastal Flood Advisory","headline":"h1","severity":"Minor","description":"d1","instruction":null,
                         "ends":null,"expires":"2026-11-25T23:00:00-06:00"}},
          {"properties":{"event":"Tornado Warning","headline":"h2","severity":"Severe","description":"d2","instruction":"Take shelter.",
                         "ends":"2026-11-25T12:00:00-06:00","expires":"2026-11-25T12:00:00-06:00"}}]}""")
    }

    @Test
    fun nearestStation() {
        // a station list as api.weather.gov sends it (GeoJSON: longitude first), around Denver's center
        val list = Nws.stations(org.json.JSONObject("""{"features":[
            {"geometry":{"coordinates":[-104.6562,39.8466]},"properties":{"stationIdentifier":"KDEN"}},
            {"geometry":null,"properties":{"stationIdentifier":"NOGEO"}},
            {"geometry":{"coordinates":[-105.1,39.7]},"properties":{"stationIdentifier":"D5678"}},
            {"geometry":{"coordinates":[-104.98,39.74]},"properties":{"stationIdentifier":"C1234"}}]}"""))
        assertEquals(listOf("C1234", "D5678", "KDEN"), Nws.nearest(list + list.take(1), 39.7392, -104.9903))
    }

    @Test
    fun nws() {
        val docs = nwsDocs()
        val n = Nws.parse(docs, 1795620600_000L)!!
        assertEquals(3, n.hours.size)
        // the station measured 14 °C against the forecast's 56 °F: the hours start there and fade back over 6 hours
        assertEquals(14.0, n.hours[0].temp, 1e-9)
        assertEquals((55 - 32) * 5 / 9.0 + (14 - (56 - 32) * 5 / 9.0) * 5 / 6, n.hours[1].temp, 1e-9)
        assertEquals("null chance is 0", 0, n.hours[2].pop)
        assertEquals("the higher wind of a range", 15 * 1.609344, n.hours[1].wind, 1e-9)
        assertEquals("the worst sky of a split icon", 95, n.hours[2].code)
        // the observation is recent: it is "now"
        assertEquals(14.0, n.current.temp, 1e-9)
        assertEquals(14.0, n.current.feels, 1e-9)
        assertEquals(72, n.current.humidity)
        assertEquals(1009.14, n.current.pressure, 1e-9)
        assertEquals("Cloudy", n.current.text)
        assertTrue(n.current.gust.isNaN())
        // today: Today's high, Tonight's low; Wednesday has no night yet
        assertEquals(1795586400L, n.days[0].time)
        assertEquals((61 - 32) * 5 / 9.0, n.days[0].max, 1e-9)
        assertEquals((44 - 32) * 5 / 9.0, n.days[0].min, 1e-9)
        assertEquals(80, n.days[0].pop)
        assertEquals("Rain Likely", n.days[0].text)
        assertTrue(n.days[0].detail.startsWith("Today: Rain likely.") && n.days[0].detail.contains("Tonight: Rain. Low around 44."))
        assertEquals("Wednesday: ", n.days[1].detail)
        assertTrue(n.days[1].min.isNaN())
        assertEquals(listOf("Tornado Warning", "Coastal Flood Advisory"), n.alerts.map { it.event })
        assertEquals("", n.alerts[1].instruction)
        assertEquals(1795669200L, n.alerts[1].ends)   // no "ends": its expiry
        // Open-Meteo fills the gaps and nothing else
        val f = Nws.fill(n, OpenMeteo.parse(json, 0))
        assertEquals(24140.0, f.hours[0].visibility, 1e-9)
        assertEquals(n.hours[0].temp, f.hours[0].temp, 1e-9)
        assertEquals(1795648200L, f.days[0].sunset)
        assertEquals(6.7, f.days[1].min, 1e-9)
        assertEquals(n.days[0].max, f.days[0].max, 1e-9)
        assertEquals(1009.14, f.current.pressure, 1e-9)
        assertEquals(22.3, f.current.gust, 1e-9)
        assertEquals("nws", f.source)
        assertEquals(63, Nws.code("https://api.weather.gov/icons/land/night/rain,70/rain,60?size=medium"))
        assertEquals(3, Nws.code(""))
    }

    @Test
    fun extras() {
        // 15-minute precipitation; fields the document leaves out are unknown, not zero
        val f = OpenMeteo.parse(json.replace("\"daily\":", "\"minutely_15\":{\"time\":[1795620600,1795621500],\"precipitation\":[0.0,0.4]},\"daily\":"), 0)
        assertEquals(2, f.quarters.size)
        assertEquals(0.4, f.quarters[1].precip, 1e-9)
        assertEquals(-1, f.hours[0].cloud)
        assertTrue(f.hours[0].feels.isNaN())
        // air quality: nulls are skipped, pollen only where forecast
        val air = OpenMeteo.parseAir("""{"current":{"us_aqi":57.4,"pm2_5":12.1,"pm10":20.0,"ozone":61.0,"nitrogen_dioxide":9.5,
            "sulphur_dioxide":1.0,"carbon_monoxide":180.0,"grass_pollen":35.2,"birch_pollen":null},
            "hourly":{"time":[1795618800,1795622400],"us_aqi":[55,null]}}""")!!
        assertEquals(57, air.aqi)
        assertEquals(1, Aqi.level(air.aqi))
        assertEquals(listOf("Grass" to 35.2), air.pollen)
        assertEquals(listOf(1795618800L to 55), air.hours)
        assertEquals(null, OpenMeteo.parseAir("""{"current":{"us_aqi":null}}"""))
        // wind in Beaufort, pressure units
        val u = Units(fahrenheit = true, wind = "bft", clock24 = false, inches = true)
        assertEquals("inhg", u.pressureUnit)
        assertEquals("3 Bft", u.w(15.0))
        assertEquals(0, Units.beaufort(0.5))
        assertEquals(12, Units.beaufort(130.0))
        assertEquals("762 mmHg", u.copy(pressureUnit = "mmhg").pressure(1016.2))
        // the NWS's wind direction ("SE") in degrees
        assertEquals(135, Nws.parse(nwsDocs(), 1795620600_000L)!!.hours[0].windDir)
    }

    @Test
    fun search() {
        val r = OpenMeteo.parseSearch("""{"results":[{"name":"Chicago","latitude":41.85003,"longitude":-87.65005,"admin1":"Illinois","country":"United States"}]}""")
        assertEquals("Chicago", r[0].name)
        assertEquals("Illinois, United States", r[0].detail)
        assertEquals("c:41.850,-87.650", r[0].id)
        assertTrue(OpenMeteo.parseSearch("""{"generationtime_ms":0.5}""").isEmpty())
    }

    @Test
    fun units() {
        val us = Units(fahrenheit = true, wind = "mph", clock24 = false, inches = true)
        val metric = Units(fahrenheit = false, wind = "kmh", clock24 = true, inches = false)
        assertEquals("56°", us.t(13.3))
        assertEquals("13°", metric.t(13.3))
        assertEquals("–", us.t(Double.NaN))
        assertEquals("6 mph", us.w(9.4))
        val cst = ZoneOffset.ofHours(-6)
        assertEquals("5p", us.hourShort(1795586400L + 17 * 3600, cst))
        assertEquals("12a", us.hourShort(1795586400L, cst))              // local midnight
        assertEquals("0", metric.hourShort(1795586400L, cst))
        assertEquals(Sky.RAIN, Wmo.sky(81))
        assertEquals(R.drawable.wx_partly_night, Wmo.icon(2, false))
    }
}
