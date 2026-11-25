package dev.skyform.weather.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import dev.skyform.weather.Sky
import kotlin.math.sin
import kotlin.random.Random

val Dim = Color.White.copy(alpha = 0.74f)
val Faint = Color.White.copy(alpha = 0.5f)
val RainBlue = Color(0xFF7CC4FF)
val Gold = Color(0xFFFFC857)
val SheetBg = Color(0xFF172131)

/**
 * The owner's rule: every interactive element has a clear, high-contrast hover and pressed state. Pressed: a bright
 * fill and a white outline; hover or keyboard focus: a softer fill. [light] for elements drawn on a light fill.
 */
fun Modifier.tap(shape: Shape = RoundedCornerShape(14.dp), light: Boolean = false, enabled: Boolean = true,
                 label: String? = null, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val hovered by src.collectIsHoveredAsState()
    val focused by src.collectIsFocusedAsState()
    val ink = if (light) Color.Black else Color.White
    val fill = when {
        pressed -> ink.copy(alpha = if (light) 0.28f else 0.32f)
        hovered || focused -> ink.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    this.clip(shape)
        .background(fill)
        .then(if (pressed || focused) Modifier.border(1.5.dp, ink.copy(alpha = 0.9f), shape) else Modifier)
        .clickable(src, indication = null, enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
}

/** A frosted card over the sky. */
@Composable
fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Color(0x33000000))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape).padding(16.dp),
        content = content,
    )
}

@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier, color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)

@Composable
fun IconBtn(icon: Int, label: String, size: Dp = 44.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(size).alpha(if (enabled) 1f else 0.3f).tap(CircleShape, enabled = enabled, label = label, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), label, Modifier.size(size * 0.5f), tint = Color.White)
    }
}

/** A pill button: [primary] is white with dark text. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.clip(shape)
            .background(if (primary) Color.White else Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = if (primary) 0f else 0.35f), shape)
            .tap(shape, light = primary, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (primary) Color(0xFF10233F) else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// --- the sky ---------------------------------------------------------------------------------------------------------

/**
 * When the sky may move: only while [enabled] (the setting, and not in battery saver) and for 30 seconds after the
 * last touch, scroll or return to the app. Asleep, it asks for no frames at all, so a page left open costs nothing.
 */
class Motion {
    @Volatile private var last = System.nanoTime()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile var enabled = true
        set(value) { field = value; wake.trySend(Unit) }

    fun poke() { last = System.nanoTime(); wake.trySend(Unit) }
    fun awake() = enabled && System.nanoTime() - last < 30_000_000_000L
    suspend fun awaitAwake() { while (!awake()) wake.receive() }
}

private fun sky(sky: Sky, day: Boolean): Pair<Color, Color> = when (sky) {
    Sky.CLEAR -> if (day) Color(0xFF2A74E0) to Color(0xFF5AA9F0) else Color(0xFF060A1D) to Color(0xFF223068)
    Sky.PARTLY -> if (day) Color(0xFF3577CC) to Color(0xFF7DAFDD) else Color(0xFF0A1130) to Color(0xFF2C3961)
    Sky.CLOUDY -> if (day) Color(0xFF4F6278) to Color(0xFF8798AC) else Color(0xFF141A25) to Color(0xFF343E4E)
    Sky.FOG -> if (day) Color(0xFF6C7884) to Color(0xFF98A3AE) else Color(0xFF1B2026) to Color(0xFF3C434B)
    Sky.DRIZZLE, Sky.RAIN -> if (day) Color(0xFF34455A) to Color(0xFF687D93) else Color(0xFF0E141C) to Color(0xFF2A3543)
    Sky.SNOW -> if (day) Color(0xFF6B87AD) to Color(0xFF9FB4CC) else Color(0xFF1A2335) to Color(0xFF43516A)
    Sky.THUNDER -> if (day) Color(0xFF2B2640) to Color(0xFF575070) else Color(0xFF110E1D) to Color(0xFF312A45)
}

/**
 * The page background: a gradient for the weather and time of day, with rain, snow, stars, sun or drifting mist.
 * It has its own cached layer (its frames never redraw the cards in front, and a still sky costs one copy), drawn at
 * half resolution and scaled up (soft gradients and dots: a quarter of the pixels, no visible loss), and builds its
 * gradients once per size and color, not per frame. A new sky (another page's weather) fades in over the old one:
 * two finished pictures blended, not a sky redrawn in changing colors every frame.
 */
@Composable
fun SkyBackground(sky: Sky, day: Boolean, motion: Motion, modifier: Modifier = Modifier, still: () -> Boolean = { false }) =
    Crossfade(sky to day, modifier, tween(900), label = "sky") { (s, d) -> SkyLayer(s, d, motion, still) }

@Composable
private fun SkyLayer(sky: Sky, day: Boolean, motion: Motion, still: () -> Boolean) {
    val (t0, t1) = sky(sky, day)
    // The sky's clock: 30 frames a second, and it stops while [still] (a swipe or a scroll), so the layer below keeps
    // its last picture and the GPU only moves the cards. It sleeps between its frames: asking for every display frame
    // (120 a second) would cost the UI thread time in each and keep the phone from idling.
    var seconds by remember { mutableFloatStateOf(0f) }
    val isStill by rememberUpdatedState(still)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            if (!motion.awake()) { last = 0L; motion.awaitAwake() }
            delay(28)
            withFrameNanos { now ->
                if (last != 0L && !isStill()) seconds += ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
            }
        }
    }
    val rnd = remember { Random(7).let { r -> FloatArray(600) { r.nextFloat() } } }
    Box(Modifier.fillMaxSize()) { Spacer(Modifier.fillMaxSize(SKY_SCALE).graphicsLayer {
        scaleX = 1 / SKY_SCALE; scaleY = 1 / SKY_SCALE
        transformOrigin = TransformOrigin(0f, 0f)
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithCache {
        val k = density * SKY_SCALE   // pixels per dp in the layer
        val bg = Brush.verticalGradient(listOf(t0, t1))
        val glowR = size.width * 0.95f
        val glow = if (day) Brush.radialGradient(listOf(Color(0x80FFF4C6), Color(0x26FFE08A), Color.Transparent), Offset.Zero, glowR)
        else Brush.radialGradient(listOf(Color(0x26CFE0FF), Color.Transparent), Offset.Zero, glowR)
        val mistR = FloatArray(7) { (140 + rnd[it + 10] * 140) * k }
        val mist = List(7) { Brush.radialGradient(listOf(Color.White.copy(alpha = 0.07f + rnd[it + 50] * 0.07f), Color.Transparent), Offset.Zero, mistR[it]) }
        onDrawBehind {
            drawRect(bg)
            val t = seconds
            if (sky == Sky.CLEAR || sky == Sky.PARTLY) {
                translate(size.width * 0.86f, size.height * 0.06f) { drawCircle(glow, glowR, Offset.Zero) }
                if (!day) stars(t, rnd, k)
            }
            if (sky == Sky.PARTLY || sky == Sky.CLOUDY || sky == Sky.FOG) {
                for (i in 0 until if (sky == Sky.PARTLY) 4 else 7) {
                    val r = mistR[i]
                    val span = size.width + 2 * r
                    val x = ((rnd[i + 30] * span + t * (6 + rnd[i + 20] * 10) * k) % span) - r
                    translate(x, size.height * (0.05f + rnd[i + 40] * 0.55f)) { drawCircle(mist[i], r, Offset.Zero) }
                }
            }
            when (sky) {
                Sky.RAIN, Sky.THUNDER -> rain(t, rnd, 110, k)
                Sky.DRIZZLE -> rain(t, rnd, 50, k)
                Sky.SNOW -> snow(t, rnd, k)
                else -> Unit
            }
            if (sky == Sky.THUNDER) {
                val p = t % 7.3f
                val a = when { p < 0.12f -> 0.32f * (1 - p / 0.12f); p in 0.22f..0.3f -> 0.18f; else -> 0f }
                if (a > 0) drawRect(Color.White.copy(alpha = a))
            }
        }
    }) }
}

private const val SKY_SCALE = 0.5f

private fun DrawScope.stars(t: Float, rnd: FloatArray, k: Float) {
    for (i in 0 until 90) {
        val x = rnd[i] * size.width
        val y = rnd[i + 100] * size.height * 0.7f
        val a = 0.2f + 0.65f * (0.5f + 0.5f * sin(t * (0.6f + rnd[i + 200] * 1.6f) + rnd[i + 300] * 6.28f))
        drawCircle(Color.White.copy(alpha = a), (0.7f + rnd[i + 400] * 1.2f) * k, Offset(x, y))
    }
}

private fun DrawScope.rain(t: Float, rnd: FloatArray, n: Int, k: Float) {
    val slant = 0.14f
    for (i in 0 until n) {
        val len = (14 + rnd[i + 60] * 16) * k
        val speed = (650 + rnd[i + 160] * 450) * k
        val span = size.height + len
        val y = ((rnd[i + 260] * span + t * speed) % span) - len
        val x = ((rnd[i + 360] * (size.width + 200 * k)) - y * slant).let { ((it % size.width) + size.width) % size.width }
        drawLine(Color.White.copy(alpha = 0.14f + rnd[i + 460] * 0.2f), Offset(x, y), Offset(x + len * slant, y - len),
            strokeWidth = 1.3f * k, cap = StrokeCap.Round)
    }
}

private fun DrawScope.snow(t: Float, rnd: FloatArray, k: Float) {
    for (i in 0 until 80) {
        val r = (1.4f + rnd[i + 70] * 2.4f) * k
        val speed = (28 + rnd[i + 170] * 50) * k
        val span = size.height + 2 * r
        val y = ((rnd[i + 270] * span + t * speed) % span) - r
        val x = rnd[i + 370] * size.width + sin(t * (0.5f + rnd[i + 470]) + i) * 14 * k
        drawCircle(Color.White.copy(alpha = 0.45f + rnd[i + 520] * 0.45f), r, Offset(x, y))
    }
}

/** Temperature (°C) to a color for range bars: blue when cold, green, yellow, orange, red when hot. */
fun tempColor(c: Double): Color {
    val stops = listOf(-10.0 to Color(0xFF6FA8FF), 0.0 to Color(0xFF8FD3FF), 10.0 to Color(0xFF7FE3A0),
        18.0 to Color(0xFFE8E36A), 25.0 to Color(0xFFFFB347), 32.0 to Color(0xFFFF6B5A))
    if (c <= stops.first().first) return stops.first().second
    if (c >= stops.last().first) return stops.last().second
    val i = stops.indexOfLast { it.first <= c }
    val (a, ca) = stops[i]
    val (b, cb) = stops[i + 1]
    return androidx.compose.ui.graphics.lerp(ca, cb, ((c - a) / (b - a)).toFloat())
}

@Composable
fun Center(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
