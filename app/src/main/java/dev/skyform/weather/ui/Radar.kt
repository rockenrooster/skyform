package dev.skyform.weather.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.skyform.weather.Place
import dev.skyform.weather.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Rain radar around a place: RainViewer's radar mosaic (free, worldwide, zoom 7 at most) over OpenStreetMap's map,
 * darkened on the phone to suit the app. Each picture is one bitmap the size of the map (the radar at one pixel per
 * dp: its data is coarser than that), kept in a small shared cache; tiles go through the HTTP cache as long as their
 * servers allow (OpenStreetMap's tile policy asks for that, an app name and the credit).
 */
object Radar {
    private const val Z = 7
    private const val TILE = 256.0   // dp per tile
    private const val UA = "Skyform/1.0 (Android weather app; https://github.com/rockenrooster/skyform)"

    /** OpenStreetMap's light map made dark: inverted, then the hues turned back (luminance-preserving), then dimmed. */
    private val dark = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)).apply {
        postConcat(ColorMatrix(floatArrayOf(
            -0.574f, 1.430f, 0.144f, 0f, 0f, 0.426f, 0.430f, 0.144f, 0f, 0f, 0.426f, 1.430f, -0.856f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        postConcat(ColorMatrix(floatArrayOf(0.8f, 0f, 0f, 0f, 0f, 0f, 0.84f, 0f, 0f, 0f, 0f, 0f, 0.95f, 0f, 6f, 0f, 0f, 0f, 1f, 0f)))
    })

    class Frame(val time: Long, val url: String)

    private var framesAt = 0L
    private var framesCache: List<Frame> = emptyList()
    private val bitmaps = object : LruCache<String, Bitmap>(10 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** RainViewer's pictures of the past two hours (10-minute steps), asked for at most every 5 minutes. */
    suspend fun frames(): List<Frame> = withContext(Dispatchers.IO) {
        synchronized(this@Radar) {
            if (framesCache.isNotEmpty() && System.currentTimeMillis() - framesAt < 5 * 60_000L) return@withContext framesCache
        }
        val o = JSONObject(String(get("https://api.rainviewer.com/public/weather-maps.json", cache = false)
            ?: throw IOException("no radar"), Charsets.UTF_8))
        val host = o.getString("host")
        val past = o.getJSONObject("radar").getJSONArray("past")
        val list = (0 until past.length()).map { past.getJSONObject(it) }.map { Frame(it.getLong("time"), host + it.getString("path")) }
        if (list.isEmpty()) throw IOException("no radar frames")
        synchronized(this@Radar) { framesCache = list; framesAt = System.currentTimeMillis() }
        list
    }

    private fun get(url: String, cache: Boolean = true): ByteArray? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            useCaches = cache
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", UA)
        }
        try { if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }.getOrNull()

    /** A [w] x [h] dp window of the zoom-7 map centered on a place, and the tiles it needs. */
    private class Window(lat: Double, lon: Double, val w: Int, val h: Int) {
        val left = (lon + 180) / 360 * TILE * (1 shl Z) - w / 2.0
        val top = Math.toRadians(lat.coerceIn(-85.0, 85.0)).let { r -> (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * TILE * (1 shl Z) } - h / 2.0
        val tiles = buildList {
            for (ty in floor(top / TILE).toInt()..floor((top + h) / TILE).toInt())
                for (tx in floor(left / TILE).toInt()..floor((left + w) / TILE).toInt())
                    if (ty in 0 until (1 shl Z)) add(tx to ty)
        }
    }

    private suspend fun compose(v: Window, scale: Float, config: Bitmap.Config, filter: ColorFilter? = null,
                                url: (Int, Int) -> String): Bitmap = coroutineScope {
        val n = 1 shl Z
        val loaded = v.tiles.map { (tx, ty) ->
            async(Dispatchers.IO) { get(url(((tx % n) + n) % n, ty))?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        }.awaitAll()
        if (loaded.all { it == null }) throw IOException("no map tiles")
        withContext(Dispatchers.Default) {
            val bmp = Bitmap.createBitmap((v.w * scale).roundToInt(), (v.h * scale).roundToInt(), config)
            val cv = android.graphics.Canvas(bmp)
            cv.scale(scale, scale)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = filter }
            v.tiles.forEachIndexed { i, (tx, ty) ->
                val t = loaded[i] ?: return@forEachIndexed
                val x = (tx * TILE - v.left).toFloat()
                val y = (ty * TILE - v.top).toFloat()
                cv.drawBitmap(t, null, RectF(x, y, x + TILE.toFloat(), y + TILE.toFloat()), paint)
                t.recycle()
            }
            bmp
        }
    }

    /** The map: OpenStreetMap's tiles, darkened, at up to 2 pixels per dp; no alpha, so 2 bytes a pixel. */
    suspend fun base(lat: Double, lon: Double, w: Int, h: Int, scale: Float): Bitmap {
        val key = "base|$lat|$lon|$w|$h|$scale"
        bitmaps.get(key)?.let { return it }
        return compose(Window(lat, lon, w, h), scale, Bitmap.Config.RGB_565, dark) { x, y -> "https://tile.openstreetmap.org/$Z/$x/$y.png" }
            .also { bitmaps.put(key, it) }
    }

    /** One radar picture (RainViewer's "Universal Blue" colors, smoothed, with snow). */
    suspend fun frame(f: Frame, lat: Double, lon: Double, w: Int, h: Int): Bitmap {
        val key = "${f.url}|$lat|$lon|$w|$h"
        bitmaps.get(key)?.let { return it }
        return compose(Window(lat, lon, w, h), 1f, Bitmap.Config.ARGB_8888) { x, y -> "${f.url}/256/$Z/$x/$y/2/1_1.png" }
            .also { bitmaps.put(key, it) }
    }
}

private fun ago(t: Long): String {
    val m = ((System.currentTimeMillis() / 1000 - t) / 60).toInt()
    return when {
        m < 10 -> "Latest"
        m < 60 -> "$m min ago"
        else -> "${m / 60} h ${m % 60} min ago"
    }
}

/**
 * The radar card. Light on battery, data and memory: it loads only while it is on screen (its page in view and
 * scrolled to) and the app is in front; it shows the latest picture, fetches the past two hours only when played,
 * and lets go of them when it leaves the screen.
 */
@Composable
fun RadarCard(p: Place) {
    var base by remember(p.id) { mutableStateOf<ImageBitmap?>(null) }
    var frames by remember(p.id) { mutableStateOf<List<Pair<Long, ImageBitmap>>>(emptyList()) }
    var index by remember(p.id) { mutableIntStateOf(0) }
    var playing by remember(p.id) { mutableStateOf(false) }
    var loading by remember(p.id) { mutableStateOf(false) }
    var failed by remember(p.id) { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current.density
    Glass(Modifier.onGloballyPositioned { val b = it.boundsInWindow(); val v = b.width > 0 && b.height > 0; if (v != visible) visible = v }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle("Radar", Modifier.weight(1f))
            frames.getOrNull(index)?.let { (t, _) -> Text(if (loading) "Loading…" else ago(t), fontSize = 13.sp, color = Dim) }
            IconBtn(if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) "Pause the radar" else "Play the last 2 hours", 40.dp) {
                playing = !playing
            }
        }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(MAP_H.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F141C))) {
            val wDp = maxWidth.value.roundToInt()
            LaunchedEffect(p.lat, p.lon, wDp) {
                snapshotFlow { visible }.combine(lifecycle.currentStateFlow) { v, s -> v && s.isAtLeast(Lifecycle.State.RESUMED) }
                    .distinctUntilChanged().collectLatest { on ->
                        if (!on) {
                            playing = false
                            if (frames.size > 1) { frames = frames.takeLast(1); index = 0 }
                            return@collectLatest
                        }
                        delay(400)   // a page swiped past, or a card scrolled past, loads nothing
                        try {
                            val all = Radar.frames()
                            if (base == null) base = Radar.base(p.lat, p.lon, wDp, MAP_H, minOf(density, 2f)).asImageBitmap()
                            if (frames.isEmpty() || frames.last().first != all.last().time) {
                                frames = listOf(all.last().time to Radar.frame(all.last(), p.lat, p.lon, wDp, MAP_H).asImageBitmap())
                                index = 0
                            }
                            failed = false
                            snapshotFlow { playing }.collectLatest { play ->
                                if (!play) { index = frames.lastIndex; return@collectLatest }
                                if (frames.size < all.size) {
                                    loading = true
                                    try { frames = all.map { f -> f.time to Radar.frame(f, p.lat, p.lon, wDp, MAP_H).asImageBitmap() } }
                                    finally { loading = false }
                                }
                                while (true) {
                                    for (i in frames.indices) { index = i; delay(if (i == frames.lastIndex) 1500 else 450) }
                                }
                            }
                        } catch (e: IOException) {
                            failed = true
                            playing = false
                        }
                    }
            }
            // its own layer: a new radar picture redraws only the map, not the page around it
            Canvas(Modifier.fillMaxSize().graphicsLayer()) {
                val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
                base?.let { drawImage(it, dstSize = dst, filterQuality = FilterQuality.Low) }
                frames.getOrNull(index)?.second?.let { drawImage(it, dstSize = dst, alpha = 0.85f, filterQuality = FilterQuality.Low) }
                drawCircle(Color.White, 4.dp.toPx(), center)
                drawCircle(RainBlue, 9.dp.toPx(), center, style = Stroke(2.dp.toPx()))
            }
            if (base == null) Text(if (failed) "Radar unavailable right now" else "Loading radar…",
                Modifier.align(Alignment.Center).padding(top = 56.dp), color = Dim, fontSize = 13.sp)
            Text("© OpenStreetMap contributors · RainViewer", Modifier.align(Alignment.BottomEnd).padding(4.dp)
                .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp), fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.6f))
        }
    }
}

private const val MAP_H = 230
