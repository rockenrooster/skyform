package dev.skyform.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.text.format.DateFormat
import dev.skyform.weather.update.UrlHttp
import dev.skyform.weather.widget.Widget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

/** Places, settings and forecasts (memory, then a JSON file per place, then Open-Meteo). */
class Repo(private val app: Context) {
    private val prefs = app.getSharedPreferences("skyform", Context.MODE_PRIVATE)

    val places = MutableStateFlow(loadPlaces())
    val units = MutableStateFlow(loadUnits())
    val widgetAlpha = MutableStateFlow(prefs.getFloat("widget_alpha", 0.6f))
    val checkUpdates = MutableStateFlow(prefs.getBoolean("check_updates", true))
    val forecasts = MutableStateFlow<Map<String, Forecast>>(emptyMap())
    val busy = MutableStateFlow<Set<String>>(emptySet())
    val errors = MutableStateFlow<Map<String, String>>(emptyMap())

    // --- places -------------------------------------------------------------------------------------------------

    private fun loadPlaces(): List<Place> = runCatching {
        val a = JSONArray(prefs.getString("places", "[]"))
        (0 until a.length()).map { Place.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun savePlaces(list: List<Place>) {
        val widgetPlaceChanged = list.firstOrNull() != places.value.firstOrNull()
        places.value = list
        prefs.edit().putString("places", JSONArray(list.map { it.toJson() }).toString()).apply()
        if (widgetPlaceChanged) Widget.updateAll(app)
    }

    /** The phone's location goes first; other places at the end. */
    fun addPlace(p: Place) {
        if (places.value.any { it.id == p.id }) return
        savePlaces(if (p.gps) listOf(p) + places.value else places.value + p)
    }

    fun removePlace(id: String) {
        savePlaces(places.value.filterNot { it.id == id })
        cacheFile(id).delete()
        forecasts.update { it - id }
    }

    /** Moves a place one up; the first place is the widget's. */
    fun moveUp(index: Int) {
        val l = places.value.toMutableList()
        if (index !in 1 until l.size) return
        l.add(index - 1, l.removeAt(index))
        savePlaces(l)
    }

    // --- settings -----------------------------------------------------------------------------------------------

    private fun loadUnits(): Units {
        val d = Units.default(DateFormat.is24HourFormat(app))
        return Units(prefs.getBoolean("fahrenheit", d.fahrenheit), prefs.getString("wind", d.wind) ?: d.wind,
            prefs.getBoolean("clock24", d.clock24), prefs.getBoolean("inches", d.inches))
    }

    fun setUnits(u: Units) {
        units.value = u
        prefs.edit().putBoolean("fahrenheit", u.fahrenheit).putString("wind", u.wind).putBoolean("clock24", u.clock24)
            .putBoolean("inches", u.inches).apply()
        Widget.updateAll(app)
    }

    fun setWidgetAlpha(a: Float) {
        widgetAlpha.value = a
        prefs.edit().putFloat("widget_alpha", a).apply()
    }

    fun setCheckUpdates(on: Boolean) {
        checkUpdates.value = on
        prefs.edit().putBoolean("check_updates", on).apply()
    }

    // --- forecasts ----------------------------------------------------------------------------------------------

    /** "nws" (the National Weather Service where it forecasts, Open-Meteo elsewhere) or "om" (Open-Meteo only). */
    val source = MutableStateFlow(prefs.getString("source", "nws") ?: "nws")

    fun setSource(s: String) {
        source.value = s
        prefs.edit().putString("source", s).apply()
        forecasts.value = emptyMap()   // the cached documents are read again for the new source
        Widget.updateAll(app)
    }

    // One JSON file per place holds every document fetched for it: "om" (Open-Meteo) and the NWS's (Nws.fetch).
    private fun cacheFile(id: String) = File(app.filesDir, "fc/${id.replace(Regex("[^\\w.-]"), "_")}.json")

    private fun parse(docs: JSONObject, fetchedAt: Long): Forecast {
        val om = docs.optString("om").takeIf { it.isNotEmpty() }?.let { runCatching { OpenMeteo.parse(it, fetchedAt) }.getOrNull() }
        val nws = if (source.value == "nws" || om == null) runCatching { Nws.parse(docs, fetchedAt) }.getOrNull() else null
        val f = when {
            nws != null && om != null -> Nws.fill(nws, om)
            else -> nws ?: om ?: throw IOException("no forecast in the documents")
        }
        // a day without a high or a low (the NWS alone, late in the evening) would break the range bars
        return Forecast(f.fetchedAt, f.offset, f.current, f.hours, f.days.filter { !it.max.isNaN() && !it.min.isNaN() }, f.alerts, f.source)
    }

    /** What is known about [id] without asking the network. */
    fun forecast(id: String): Forecast? = forecasts.value[id] ?: cacheFile(id).takeIf { it.exists() }?.let { f ->
        runCatching { parse(JSONObject(f.readText()), f.lastModified()) }.getOrNull()
    }?.also { fc -> forecasts.update { it + (id to fc) } }

    /** Open-Meteo always (it fills the NWS's gaps), the NWS too when it is the source; at least one must answer. */
    private suspend fun fetch(p: Place): JSONObject = coroutineScope {
        val om = async { runCatching { String(UrlHttp.get(OpenMeteo.url(p.lat, p.lon), 4 shl 20, "application/json"), Charsets.UTF_8) }.getOrNull() }
        val nws = if (source.value == "nws") async { runCatching { Nws.fetch(p.lat, p.lon) }.getOrNull() } else null
        JSONObject().apply {
            om.await()?.let { put("om", it) }
            nws?.await()?.forEach { (k, v) -> put(k, v) }
            if (!has("om") && !has("hourly")) throw IOException("no weather service answered")
        }
    }

    /** Fetches [p]'s forecast unless the one we have is younger than [maxAgeMs]; the widget is redrawn either way. */
    suspend fun refresh(p: Place, maxAgeMs: Long = 15 * 60_000L): Forecast? = withContext(Dispatchers.IO) {
        val have = forecast(p.id)
        if (have != null && System.currentTimeMillis() - have.fetchedAt < maxAgeMs) {
            if (places.value.firstOrNull()?.id == p.id) Widget.updateAll(app)
            return@withContext have
        }
        busy.update { it + p.id }
        try {
            val docs = fetch(p)
            val fc = parse(docs, System.currentTimeMillis())
            cacheFile(p.id).apply { parentFile?.mkdirs(); writeText(docs.toString()) }
            forecasts.update { it + (p.id to fc) }
            errors.update { it - p.id }
            fc
        } catch (e: Exception) {
            errors.update { it + (p.id to "Can't reach the weather service") }
            have
        } finally {
            busy.update { it - p.id }
            if (places.value.firstOrNull()?.id == p.id) Widget.updateAll(app)
        }
    }

    /**
     * Finds the phone, names the spot (street and town) and refreshes its forecast. [quick]: only the location
     * Android already knows (the widget, in the background, cannot ask for a new one).
     */
    suspend fun locate(quick: Boolean = false): Place? = withContext(Dispatchers.IO) {
        val loc = Locator.find(app, quick) ?: return@withContext places.value.firstOrNull { it.gps }?.also { refresh(it) }
        val old = places.value.firstOrNull { it.gps }
        val moved = old == null || FloatArray(1).also { Location.distanceBetween(old.lat, old.lon, loc.latitude, loc.longitude, it) }[0] > 500f
        val p = if (!moved && old.name != MY_LOCATION) old else {
            val (name, detail) = Locator.name(app, loc.latitude, loc.longitude) ?: (MY_LOCATION to "")
            Place(Place.GPS, name, detail, loc.latitude, loc.longitude)
        }
        if (old == null) addPlace(p) else if (p != old) savePlaces(places.value.map { if (it.gps) p else it })
        refresh(p, if (moved) 0 else 15 * 60_000L)
        p
    }

    companion object {
        const val MY_LOCATION = "My location"
    }
}

/** The phone's location (no Play services: Android's LocationManager) and place names. */
object Locator {
    fun allowed(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun find(c: Context, quick: Boolean): Location? {
        if (!allowed(c)) return null
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (quick || (last != null && System.currentTimeMillis() - last.time < 10 * 60_000L)) return last
        val provider = providers.firstOrNull() ?: return last
        return withTimeoutOrNull(15_000) { current(c, lm, provider) } ?: last
    }

    @SuppressLint("MissingPermission")
    private suspend fun current(c: Context, lm: LocationManager, provider: String): Location? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= 30) {
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, c.mainExecutor) { if (cont.isActive) cont.resume(it) }
            } else {
                // Android 10: every method implemented (its LocationListener has no default methods)
                val l = object : LocationListener {
                    override fun onLocationChanged(loc: Location) { if (cont.isActive) cont.resume(loc) }
                    @Deprecated("Android 10 still calls it") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) = Unit
                    override fun onProviderEnabled(p: String) = Unit
                    override fun onProviderDisabled(p: String) { if (cont.isActive) cont.resume(null) }
                }
                @Suppress("DEPRECATION") lm.requestSingleUpdate(provider, l, Looper.getMainLooper())
                cont.invokeOnCancellation { lm.removeUpdates(l) }
            }
        }
    }

    /** (town, "street number street, town") from Android's geocoder; null when it has nothing. */
    @Suppress("DEPRECATION")
    fun name(c: Context, lat: Double, lon: Double): Pair<String, String>? = runCatching {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(c, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
        val town = a.locality ?: a.subAdminArea ?: a.adminArea ?: return null
        val street = listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ")
        town to (if (street.isNotBlank()) "$street, $town" else listOfNotNull(a.adminArea, a.countryName).joinToString(", "))
    }.getOrNull()

    /** Open-Meteo's place search. */
    suspend fun search(q: String): List<Place> = withContext(Dispatchers.IO) {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(q.trim(), "UTF-8")}" +
            "&count=10&format=json&language=${Locale.getDefault().language}"
        OpenMeteo.parseSearch(String(UrlHttp.get(url, 1 shl 20, "application/json"), Charsets.UTF_8))
    }
}
