package dev.skyform.weather.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyform.weather.Forecast
import dev.skyform.weather.Hour
import dev.skyform.weather.R
import dev.skyform.weather.Sky
import dev.skyform.weather.Units
import dev.skyform.weather.Wmo
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private fun nowSec() = System.currentTimeMillis() / 1000

// --- hero ------------------------------------------------------------------------------------------------------------

@Composable
fun Hero(fc: Forecast, u: Units) {
    val c = fc.current
    val today = fc.daysFrom(nowSec()).firstOrNull()
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(u.t(c.temp), fontSize = 104.sp, fontWeight = FontWeight.Thin, lineHeight = 104.sp, color = Color.White)
            Spacer(Modifier.weight(1f))
            Image(painterResource(Wmo.icon(c.code, c.isDay)), null, Modifier.size(124.dp))
        }
        Text(c.text.ifEmpty { Wmo.text(c.code, c.isDay) }, fontSize = 26.sp, fontWeight = FontWeight.Light, color = Color.White)
        Spacer(Modifier.height(4.dp))
        val hl = today?.let { "H ${u.t(it.max)}   L ${u.t(it.min)}   " } ?: ""
        Text("${hl}Feels like ${u.t(c.feels)}", fontSize = 16.sp, color = Dim)
        outlook(fc, u)?.let {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.background(Color(0x33000000), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_drop), null, Modifier.size(14.dp), tint = RainBlue)
                Spacer(Modifier.width(6.dp))
                Text(it, fontSize = 14.sp, color = Color.White)
            }
        }
    }
}

/** NWS watches, warnings and advisories: the event and when it ends; tap for the full text and what to do. */
@Composable
fun Alerts(fc: Forecast, u: Units) {
    if (fc.alerts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fc.alerts.forEach { a ->
            var open by remember(a) { mutableStateOf(false) }
            val tint = when (a.severity) {
                "Extreme", "Severe" -> Color(0xCCC62828)
                "Moderate" -> Color(0xCCE07B00)
                else -> Color(0xB3B08900)
            }
            val shape = RoundedCornerShape(18.dp)
            Column(Modifier.fillMaxWidth().background(tint, shape).tap(shape, label = "${a.event} details") { open = !open }.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚠", fontSize = 18.sp, color = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.event, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        if (a.ends > 0) Text("Until ${u.weekday(a.ends, fc.offset)} ${u.clock(a.ends, fc.offset)}", fontSize = 13.sp, color = Color.White)
                    }
                    Text(if (open) "Less" else "More", fontSize = 13.sp, color = Color.White)
                }
                AnimatedVisibility(open) {
                    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (a.headline.isNotBlank()) Text(a.headline, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                        Text(a.description.replace(Regex("(?<!\\n)\\n(?!\\n)"), " "), fontSize = 13.sp, color = Color.White, lineHeight = 18.sp)
                        if (a.instruction.isNotBlank()) Text(a.instruction.replace(Regex("(?<!\\n)\\n(?!\\n)"), " "), fontSize = 13.sp,
                            color = Color.White, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** One line about the next 12 hours: when rain or snow starts or stops. */
fun outlook(fc: Forecast, u: Units): String? {
    val next = fc.hoursFrom(nowSec()).take(13)
    if (next.size < 2) return null
    val wetSkies = setOf(Sky.DRIZZLE, Sky.RAIN, Sky.SNOW, Sky.THUNDER)
    fun wet(h: Hour) = h.pop >= 50 && Wmo.sky(h.code) in wetSkies || h.precip >= 0.3
    fun kind(h: Hour) = when (Wmo.sky(h.code)) { Sky.SNOW -> "Snow"; Sky.THUNDER -> "Storms"; else -> "Rain" }
    val nowWet = wet(next[0])
    val change = next.drop(1).firstOrNull { wet(it) != nowWet } ?: return if (nowWet) "${kind(next[0])} for the next 12 hours" else null
    return if (nowWet) "${kind(next[0])} easing around ${u.hour(change.time, fc.offset)}"
    else "${kind(change)} likely around ${u.hour(change.time, fc.offset)}"
}

// --- hourly ----------------------------------------------------------------------------------------------------------

private class Metric(val name: String, val color: Color, val bars: Boolean, val value: (Hour) -> Double,
                     val label: (Hour) -> String, val fixed: ClosedFloatingPointRange<Double>? = null)

@Composable
fun HourlyCard(fc: Forecast, u: Units) {
    val metrics = remember(u) {
        listOf(
            Metric("Temp", Gold, false, { u.tv(it.temp) }, { u.t(it.temp) }),
            Metric("Rain", RainBlue, true, { it.pop.toDouble() }, { "${it.pop}%" }, 0.0..100.0),
            Metric("Wind", Color(0xFF8CF0D8), false, { u.windV(it.wind) }, { "${u.windV(it.wind).roundToInt()}" }),
            Metric("Humidity", Color(0xFFA9C8FF), false, { it.humidity.toDouble() }, { "${it.humidity}%" }, 0.0..100.0),
        )
    }
    var pick by rememberSaveable { mutableIntStateOf(0) }
    val m = metrics[pick]
    val hours = remember(fc) { fc.hoursFrom(nowSec()).take(48) }
    if (hours.size < 2) return
    // Its own cached layer: the chart (long curves, 48 labels and icons) is the costliest thing on the page to draw,
    // so swipes and scrolls move a finished picture of it
    Glass(Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CardTitle("48 hours", Modifier.weight(1f))
            metrics.forEachIndexed { i, x ->
                val on = i == pick
                Text(x.name, Modifier.padding(start = 4.dp)
                    .background(if (on) Color.White.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(50))
                    .tap(RoundedCornerShape(50), label = "Show ${x.name}") { pick = i }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                    fontSize = 13.sp, color = if (on) Color.White else Dim, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        Spacer(Modifier.height(12.dp))
        val colW = 58.dp
        // One spoken summary instead of ~150 separate labels: screen readers get the hours in order, and the semantics
        // tree Android walks on every frame of a swipe stays small
        val spoken = remember(hours, u) {
            hours.joinToString("; ") { h ->
                "${u.hour(h.time, fc.offset)} ${u.t(h.temp)}, ${Wmo.text(h.code, h.isDay)}" + if (h.pop >= 10) ", rain ${h.pop}%" else ""
            }
        }
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(colW * hours.size).clearAndSetSemantics { contentDescription = spoken }) {
                Row {
                    hours.forEachIndexed { i, h ->
                        Column(Modifier.width(colW), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (i == 0) "Now" else u.hour(h.time, fc.offset), fontSize = 13.sp,
                                color = if (i == 0) Color.White else Dim, fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal)
                            Image(painterResource(Wmo.icon(h.code, h.isDay)), null, Modifier.padding(top = 4.dp).size(32.dp))
                        }
                    }
                }
                Chart(hours, m, Modifier.fillMaxWidth().height(96.dp))
                Row {
                    hours.forEach { h ->
                        Box(Modifier.width(colW), contentAlignment = Alignment.Center) {
                            if (h.pop >= 10 && !m.bars) Text("${h.pop}%", fontSize = 12.sp, color = RainBlue)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chart(hours: List<Hour>, m: Metric, modifier: Modifier) {
    // Labels laid out once per data set, not on every draw (48 of them: more than the measurer's cache holds)
    val measurer = rememberTextMeasurer()
    val labels = remember(hours, m) {
        val style = TextStyle(color = Color.White, fontSize = if (m.bars) 12.sp else 13.sp, fontWeight = FontWeight.Medium)
        hours.map { measurer.measure(m.label(it), style) }
    }
    Canvas(modifier) {
        val n = hours.size
        val colW = size.width / n
        val values = hours.map(m.value)
        val lo = m.fixed?.start ?: values.min()
        val hi = m.fixed?.endInclusive ?: values.max()
        val range = (hi - lo).takeIf { it > 0.5 } ?: 1.0
        val top = 24.dp.toPx()
        val bottom = size.height - 6.dp.toPx()
        fun x(i: Int) = colW * (i + 0.5f)
        fun y(v: Double) = (bottom - (bottom - top) * ((v - lo) / range)).toFloat()
        if (m.bars) {
            val bw = colW * 0.42f
            hours.forEachIndexed { i, h ->
                val yy = y(h.pop.toDouble()).coerceAtMost(bottom - 2.dp.toPx())
                drawRoundRect(Brush.verticalGradient(listOf(m.color, m.color.copy(alpha = 0.35f)), yy, bottom),
                    Offset(x(i) - bw / 2, yy), Size(bw, bottom - yy), CornerRadius(4.dp.toPx()))
                val t = labels[i]
                drawText(t, topLeft = Offset(x(i) - t.size.width / 2f, yy - t.size.height - 2.dp.toPx()))
            }
            return@Canvas
        }
        val pts = values.mapIndexed { i, v -> Offset(x(i), y(v)) }
        val line = smoothPath(pts)
        val fill = Path().apply { addPath(line); lineTo(pts.last().x, size.height); lineTo(pts.first().x, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(m.color.copy(alpha = 0.35f), Color.Transparent), top, size.height))
        drawPath(line, m.color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        for (i in hours.indices) {
            drawCircle(Color.White, 3.dp.toPx(), pts[i])
            drawCircle(m.color, 3.dp.toPx(), pts[i], style = Stroke(1.5.dp.toPx()))
            val t = labels[i]
            drawText(t, topLeft = Offset(pts[i].x - t.size.width / 2f, pts[i].y - t.size.height - 4.dp.toPx()))
        }
    }
}

/** A smooth curve through [p] (Catmull-Rom as cubic Béziers). */
fun smoothPath(p: List<Offset>): Path = Path().apply {
    moveTo(p[0].x, p[0].y)
    for (i in 0 until p.size - 1) {
        val p0 = p[(i - 1).coerceAtLeast(0)]
        val p1 = p[i]
        val p2 = p[i + 1]
        val p3 = p[(i + 2).coerceAtMost(p.size - 1)]
        cubicTo(p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6, p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6, p2.x, p2.y)
    }
}

// --- daily -----------------------------------------------------------------------------------------------------------

@Composable
fun DailyCard(fc: Forecast, u: Units) {
    val days = remember(fc) { fc.daysFrom(nowSec()) }
    if (days.isEmpty()) return
    val lo = days.minOf { it.min }
    val hi = days.maxOf { it.max }
    Glass {
        CardTitle("${days.size}-day forecast")
        Spacer(Modifier.height(6.dp))
        days.forEachIndexed { i, d ->
            var open by remember(d.time) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().tap(RoundedCornerShape(12.dp), label = "Details") { open = !open }.padding(horizontal = 4.dp)) {
                val name = if (i == 0) "Today" else u.weekday(d.time, fc.offset)
                Row(Modifier.fillMaxWidth().height(50.dp).clearAndSetSemantics {
                    contentDescription = "$name: ${d.text.ifEmpty { Wmo.text(d.code) }}, low ${u.t(d.min)}, high ${u.t(d.max)}" +
                        if (d.pop >= 10) ", rain ${d.pop}%" else ""
                }, verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.width(58.dp), fontSize = 16.sp,
                        color = Color.White, fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal)
                    Image(painterResource(Wmo.icon(d.code, true)), Wmo.text(d.code), Modifier.size(32.dp))
                    Text(if (d.pop >= 10) "${d.pop}%" else "", Modifier.width(44.dp).padding(start = 4.dp), fontSize = 13.sp, color = RainBlue)
                    Text(u.t(d.min), Modifier.width(38.dp), fontSize = 16.sp, color = Dim, textAlign = TextAlign.End)
                    RangeBar(d.min, d.max, lo, hi, if (i == 0) fc.current.temp else null,
                        Modifier.weight(1f).padding(horizontal = 10.dp).height(6.dp))
                    Text(u.t(d.max), Modifier.width(38.dp), fontSize = 16.sp, color = Color.White, textAlign = TextAlign.End)
                }
                AnimatedVisibility(open) {
                    Column(Modifier.fillMaxWidth().padding(start = 58.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(d.text.ifEmpty { Wmo.text(d.code) }, fontSize = 15.sp, color = Color.White)
                        if (d.detail.isNotBlank()) Text(d.detail, fontSize = 13.sp, color = Dim, lineHeight = 18.sp)
                        Text("Rain ${u.p(d.precip)} · chance ${d.pop}%", fontSize = 13.sp, color = Dim)
                        Text("Wind up to ${u.w(d.wind)}" + if (d.uv.isNaN()) "" else " · UV ${d.uv.roundToInt()}", fontSize = 13.sp, color = Dim)
                        if (d.sunrise > 0) Text("Sunrise ${u.clock(d.sunrise, fc.offset)} · sunset ${u.clock(d.sunset, fc.offset)}", fontSize = 13.sp, color = Dim)
                    }
                }
            }
        }
    }
}

@Composable
private fun RangeBar(min: Double, max: Double, lo: Double, hi: Double, now: Double?, modifier: Modifier) {
    Canvas(modifier) {
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun x(v: Double) = (size.width * ((v - lo) / span)).toFloat().coerceIn(0f, size.width)
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Color.White.copy(alpha = 0.15f), cornerRadius = r)
        val a = x(min)
        val b = x(max).coerceAtLeast(a + size.height)
        drawRoundRect(Brush.horizontalGradient(listOf(tempColor(min), tempColor(max)), a, b), Offset(a, 0f), Size(b - a, size.height), r)
        now?.let {
            drawCircle(Color(0xFF10233F), size.height * 0.95f, Offset(x(it), size.height / 2))
            drawCircle(Color.White, size.height * 0.7f, Offset(x(it), size.height / 2))
        }
    }
}

// --- details ---------------------------------------------------------------------------------------------------------

@Composable
fun Details(fc: Forecast, u: Units) {
    val c = fc.current
    val now = nowSec()
    val hour = fc.hoursFrom(now).firstOrNull()
    val today = fc.daysFrom(now).firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val diff = u.tv(c.feels) - u.tv(c.temp)
            Tile("Feels like", u.t(c.feels), when {
                abs(diff) < 2 -> "Close to the actual temperature"
                diff < 0 -> "Wind makes it feel colder"
                else -> "Humidity makes it feel warmer"
            }, Modifier.weight(1f))
            Tile("Humidity", "${c.humidity}%", hour?.let { "Dew point ${u.t(it.dew)}" } ?: "", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Wind", u.w(c.wind), "From ${compass(c.windDir)} · gusts ${u.w(c.gust)}", Modifier.weight(1f)) {
                Icon(painterResource(R.drawable.ic_arrow), "Wind direction", Modifier.size(22.dp).rotate(c.windDir + 180f), tint = Color.White)
            }
            val uv = hour?.uv?.takeIf { !it.isNaN() } ?: 0.0
            Tile("UV index", "${uv.roundToInt()}", uvText(uv), Modifier.weight(1f)) {
                Canvas(Modifier.width(56.dp).height(6.dp)) {
                    drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF6BD66B), Color(0xFFF2E15A), Color(0xFFFFA23E),
                        Color(0xFFFF5A5A), Color(0xFFB77BFF))), cornerRadius = CornerRadius(size.height / 2))
                    drawCircle(Color.White, size.height, Offset(size.width * (uv / 11).toFloat().coerceIn(0f, 1f), size.height / 2))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Pressure", u.pressure(c.pressure), "At sea level", Modifier.weight(1f))
            val vis = hour?.visibility ?: Double.NaN
            Tile("Visibility", u.dist(vis), when {
                vis.isNaN() -> ""
                vis >= 10_000 -> "Clear view"
                vis >= 4_000 -> "Light haze"
                vis >= 1_000 -> "Haze"
                else -> "Fog"
            }, Modifier.weight(1f))
        }
        if (today != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val next24 = fc.hoursFrom(now).take(24).sumOf { it.precip }
                Tile("Precipitation", u.p(today.precip), "Today · next 24 h ${u.p(next24)}", Modifier.weight(1f))
                Tile("Wind today", u.w(today.wind), "Strongest wind expected", Modifier.weight(1f))
            }
            if (today.sunrise > 0) SunCard(today.sunrise, today.sunset, now, fc, u)
        }
    }
}

@Composable
private fun Tile(title: String, value: String, sub: String, modifier: Modifier, extra: (@Composable () -> Unit)? = null) {
    Glass(modifier.height(128.dp)) {
        CardTitle(title)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.Light, color = Color.White, maxLines = 1)
            if (extra != null) { Spacer(Modifier.width(10.dp)); extra() }
        }
        Spacer(Modifier.weight(1f))
        Text(sub, fontSize = 13.sp, color = Dim, maxLines = 2, lineHeight = 16.sp)
    }
}

@Composable
private fun SunCard(rise: Long, set: Long, now: Long, fc: Forecast, u: Units) {
    Glass {
        CardTitle("Sun")
        Spacer(Modifier.height(8.dp))
        val frac = ((now - rise).toFloat() / (set - rise).coerceAtLeast(1)).coerceIn(0f, 1f)
        val up = now in rise..set
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val cx = size.width / 2
            val r = minOf(size.width / 2 - 16.dp.toPx(), size.height - 10.dp.toPx())
            val base = size.height - 4.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, base), Offset(size.width, base), 1.dp.toPx())
            drawArc(Color.White.copy(alpha = 0.35f), 180f, 180f, false, Offset(cx - r, base - r), Size(2 * r, 2 * r),
                style = Stroke(1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
            if (up) drawArc(Gold.copy(alpha = 0.9f), 180f, 180f * frac, false, Offset(cx - r, base - r), Size(2 * r, 2 * r),
                style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            val ang = PI * (1 - frac)
            val p = Offset(cx + (r * cos(ang)).toFloat(), base - (r * sin(ang)).toFloat())
            if (up) {
                drawCircle(Brush.radialGradient(listOf(Gold.copy(alpha = 0.6f), Color.Transparent), p, 22.dp.toPx()), 22.dp.toPx(), p)
                drawCircle(Gold, 7.dp.toPx(), p)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Sunrise", fontSize = 13.sp, color = Dim)
                Text(u.clock(rise, fc.offset), fontSize = 20.sp, color = Color.White)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val len = set - rise
                Text("Daylight", fontSize = 13.sp, color = Dim)
                Text("${len / 3600} h ${(len % 3600) / 60} m", fontSize = 20.sp, color = Color.White)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("Sunset", fontSize = 13.sp, color = Dim)
                Text(u.clock(set, fc.offset), fontSize = 20.sp, color = Color.White)
            }
        }
    }
}

private fun compass(deg: Int) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 360) % 360 + 22) / 45 % 8]

private fun uvText(uv: Double) = when {
    uv < 3 -> "Low"
    uv < 6 -> "Moderate"
    uv < 8 -> "High · use sun protection"
    uv < 11 -> "Very high · seek shade"
    else -> "Extreme"
}
