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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
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
import dev.skyform.weather.Aqi
import dev.skyform.weather.Astro
import dev.skyform.weather.Forecast
import dev.skyform.weather.Hour
import dev.skyform.weather.R
import dev.skyform.weather.Sky
import dev.skyform.weather.Units
import dev.skyform.weather.Wmo
import dev.skyform.weather.compass
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

fun nowSec() = System.currentTimeMillis() / 1000

private fun Double.or(other: Double) = if (isNaN()) other else this

// --- hero ------------------------------------------------------------------------------------------------------------

@Composable
fun Hero(fc: Forecast, u: Units) {
    val c = fc.current
    val now = nowSec()
    val today = fc.daysFrom(now).firstOrNull()
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
        versusYesterday(fc, u, now)?.let { Text(it, Modifier.padding(top = 2.dp), fontSize = 14.sp, color = Dim) }
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

/** "3° warmer than yesterday" (this hour yesterday against now). */
fun versusYesterday(fc: Forecast, u: Units, now: Long): String? {
    val y = fc.yesterday(now) ?: return null
    val d = u.tv(fc.current.temp) - u.tv(y.temp)
    if (d.isNaN()) return null
    val n = abs(d).roundToInt()
    return when {
        n == 0 -> "The same as yesterday at this time"
        d > 0 -> "$n° warmer than yesterday at this time"
        else -> "$n° cooler than yesterday at this time"
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

/**
 * What the 15-minute forecast says about the next two hours; null when it stays dry. Open-Meteo gives each quarter's
 * precipitation at its end, so a quarter ending at [it.time] holds what falls in the 15 minutes before.
 */
class Soon(val amounts: List<Double>, val start: Long, val wetNow: Boolean, val change: Long?, val snow: Boolean) {
    val kind get() = if (snow) "Snow" else "Rain"
    fun text(u: Units, fc: Forecast) = when {
        wetNow && change == null -> "$kind for the next 2 hours"
        wetNow -> "$kind easing around ${u.clock(change!!, fc.offset)}"
        else -> "$kind starting around ${u.clock(change ?: start, fc.offset)}"
    }

    companion object {
        const val WET = 0.1   // mm in a quarter hour: light rain

        fun of(fc: Forecast, now: Long): Soon? {
            val q = fc.quarters.filter { it.time > now }.take(8)
            if (q.size < 4 || q.none { it.precip >= WET }) return null
            val wetNow = q[0].precip >= WET
            val change = q.drop(1).firstOrNull { (it.precip >= WET) != wetNow }?.let { it.time - 900 }
            val snow = fc.hoursFrom(now).take(3).any { Wmo.sky(it.code) == Sky.SNOW }
            return Soon(q.map { it.precip }, q[0].time - 900, wetNow, change, snow)
        }
    }
}

/** One line about the next hours: the 15-minute forecast's when it sees rain within two hours, else the hourly's. */
fun outlook(fc: Forecast, u: Units): String? {
    val now = nowSec()
    Soon.of(fc, now)?.let { return it.text(u, fc) }
    val next = fc.hoursFrom(now).take(13)
    if (next.size < 2) return null
    val wetSkies = setOf(Sky.DRIZZLE, Sky.RAIN, Sky.SNOW, Sky.THUNDER)
    fun wet(h: Hour) = h.pop >= 50 && Wmo.sky(h.code) in wetSkies || h.precip >= 0.3
    fun kind(h: Hour) = when (Wmo.sky(h.code)) { Sky.SNOW -> "Snow"; Sky.THUNDER -> "Storms"; else -> "Rain" }
    val nowWet = wet(next[0])
    val change = next.drop(1).firstOrNull { wet(it) != nowWet } ?: return if (nowWet) "${kind(next[0])} for the next 12 hours" else null
    return if (nowWet) "${kind(next[0])} easing around ${u.hour(change.time, fc.offset)}"
    else "${kind(change)} likely around ${u.hour(change.time, fc.offset)}"
}

// --- rain soon -------------------------------------------------------------------------------------------------------

/** The next two hours in 15-minute bars; shown only when rain or snow is falling or about to. */
@Composable
fun RainSoonCard(fc: Forecast, u: Units) {
    val s = Soon.of(fc, nowSec()) ?: return
    Glass {
        CardTitle("Next 2 hours")
        Spacer(Modifier.height(6.dp))
        Text(s.text(u, fc), fontSize = 17.sp, color = Color.White)
        Spacer(Modifier.height(10.dp))
        val spoken = s.amounts.joinToString(", ") { "%.1f".format(it * 4) } + " millimeters an hour, every 15 minutes"
        Canvas(Modifier.fillMaxWidth().height(70.dp).clearAndSetSemantics { contentDescription = spoken }) {
            val n = s.amounts.size
            val cw = size.width / n
            val floor = size.height
            // bar height: light (0.1-0.6 mm a quarter) to heavy (2 and more), on a square-root scale
            s.amounts.forEachIndexed { i, a ->
                if (a <= 0) return@forEachIndexed
                val h = (sqrt(a / 2.0).toFloat().coerceIn(0.06f, 1f)) * (floor - 4.dp.toPx())
                drawRoundRect(Brush.verticalGradient(listOf(RainBlue, RainBlue.copy(alpha = 0.4f)), floor - h, floor),
                    Offset(cw * i + cw * 0.12f, floor - h), Size(cw * 0.76f, h), CornerRadius(4.dp.toPx()))
            }
            drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, floor), Offset(size.width, floor), 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            listOf("Now", "30 min", "1 h", "1½ h").forEach { Text(it, Modifier.weight(1f), fontSize = 12.sp, color = Dim) }
        }
    }
}

// --- hourly ----------------------------------------------------------------------------------------------------------

/** One thing the hourly chart can show: bars (from zero) or a line, with each hour's label. */
private class Metric(val name: String, val color: Color, val bars: Boolean, val value: (Hour) -> Double,
                     val label: (Hour) -> String, val fixed: ClosedFloatingPointRange<Double>? = null, val minTop: Double = 1.0,
                     val arrows: Boolean = false)

@Composable
fun HourlyCard(fc: Forecast, u: Units) {
    val metrics = remember(u) {
        listOf(
            Metric("Temp", Gold, false, { u.tv(it.temp) }, { u.t(it.temp) }),
            Metric("Feels like", Color(0xFFFF9E6B), false, { u.tv(it.feels.or(it.temp)) }, { u.t(it.feels.or(it.temp)) }),
            Metric("Rain", RainBlue, true, { it.pop.toDouble() }, { "${it.pop}%" }, 0.0..100.0),
            Metric("Amount", Color(0xFF5FA8FF), true, { if (u.inches) it.precip / 25.4 else it.precip },
                { if (it.precip < 0.05) "" else if (u.inches) "%.2f".format(it.precip / 25.4) else "%.1f".format(it.precip) },
                minTop = if (u.inches) 0.1 else 2.5),
            Metric("Wind", Color(0xFF8CF0D8), false, { u.windV(it.wind) }, { "${u.windV(it.wind).roundToInt()}" }, arrows = true),
            Metric("Humidity", Color(0xFFA9C8FF), false, { it.humidity.toDouble() }, { "${it.humidity}%" }, 0.0..100.0),
            Metric("Dew point", Color(0xFF9FE3C4), false, { u.tv(it.dew.or(it.temp)) }, { u.t(it.dew) }),
            Metric("UV", Color(0xFFFFB347), true, { it.uv.or(0.0) }, { if (it.uv.or(0.0) < 0.5) "" else "${it.uv.roundToInt()}" }, 0.0..11.0),
            Metric("Clouds", Color(0xFFCFD8E3), true, { it.cloud.coerceAtLeast(0).toDouble() }, { "${it.cloud.coerceAtLeast(0)}%" }, 0.0..100.0),
            Metric("Pressure", Color(0xFFD4B5FF), false, { u.pressureV(it.pressure.or(1013.0)) }, { pressureShort(u, it.pressure) }),
        )
    }
    var pick by rememberSaveable { mutableIntStateOf(0) }
    val m = metrics[pick.coerceIn(metrics.indices)]
    val hours = remember(fc) { fc.hoursFrom(nowSec()).take(48) }
    if (hours.size < 2) return
    // Its own cached layer: the chart (long curves, 48 labels and icons) is the costliest thing on the page to draw,
    // so swipes and scrolls move a finished picture of it
    Glass(Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        CardTitle("48 hours")
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
            metrics.forEachIndexed { i, x ->
                val on = i == pick
                Text(x.name, Modifier.padding(end = 4.dp)
                    .background(if (on) Color.White.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(50))
                    .tap(RoundedCornerShape(50), label = "Show ${x.name}") { pick = i }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
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
                Chart(hours, m, Modifier.fillMaxWidth().height(if (m.arrows) 116.dp else 96.dp))
                Row {
                    hours.forEach { h ->
                        Box(Modifier.width(colW), contentAlignment = Alignment.Center) {
                            if (h.pop >= 10 && m.name != "Rain") Text("${h.pop}%", fontSize = 12.sp, color = RainBlue)
                        }
                    }
                }
            }
        }
    }
}

private fun pressureShort(u: Units, hpa: Double) = when {
    hpa.isNaN() -> ""
    u.pressureUnit == "inhg" -> "%.2f".format(hpa * 0.02953)
    else -> "${u.pressureV(hpa).roundToInt()}"
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
        val arrowRoom = if (m.arrows) 20.dp.toPx() else 0f
        val top = 24.dp.toPx()
        val bottom = size.height - 6.dp.toPx() - arrowRoom
        fun x(i: Int) = colW * (i + 0.5f)
        if (m.bars) {
            val hi = m.fixed?.endInclusive ?: maxOf(values.max() * 1.1, m.minTop)
            fun y(v: Double) = (bottom - (bottom - top) * (v / hi).coerceIn(0.0, 1.0)).toFloat()
            val bw = colW * 0.42f
            hours.indices.forEach { i ->
                val yy = y(values[i]).coerceAtMost(bottom - 2.dp.toPx())
                drawRoundRect(Brush.verticalGradient(listOf(m.color, m.color.copy(alpha = 0.35f)), yy, bottom),
                    Offset(x(i) - bw / 2, yy), Size(bw, bottom - yy), CornerRadius(4.dp.toPx()))
                val t = labels[i]
                drawText(t, topLeft = Offset(x(i) - t.size.width / 2f, yy - t.size.height - 2.dp.toPx()))
            }
            return@Canvas
        }
        val lo = m.fixed?.start ?: values.min()
        val hi = m.fixed?.endInclusive ?: values.max()
        val range = (hi - lo).takeIf { it > 0.01 } ?: 1.0
        fun y(v: Double) = (bottom - (bottom - top) * ((v - lo) / range)).toFloat()
        val pts = values.mapIndexed { i, v -> Offset(x(i), y(v)) }
        val line = smoothPath(pts)
        val fill = Path().apply { addPath(line); lineTo(pts.last().x, bottom + 6.dp.toPx()); lineTo(pts.first().x, bottom + 6.dp.toPx()); close() }
        drawPath(fill, Brush.verticalGradient(listOf(m.color.copy(alpha = 0.35f), Color.Transparent), top, bottom))
        drawPath(line, m.color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        for (i in hours.indices) {
            drawCircle(Color.White, 3.dp.toPx(), pts[i])
            drawCircle(m.color, 3.dp.toPx(), pts[i], style = Stroke(1.5.dp.toPx()))
            val t = labels[i]
            drawText(t, topLeft = Offset(pts[i].x - t.size.width / 2f, pts[i].y - t.size.height - 4.dp.toPx()))
            if (m.arrows && hours[i].windDir >= 0) windArrow(Offset(x(i), size.height - arrowRoom / 2 - 2.dp.toPx()), hours[i].windDir, 7.dp.toPx())
        }
    }
}

/** An arrow pointing where the wind blows (it comes from [from] degrees). */
fun DrawScope.windArrow(c: Offset, from: Int, r: Float, color: Color = Color.White) {
    rotate(from + 180f, c) {
        val p = Path().apply {
            moveTo(c.x, c.y - r)
            lineTo(c.x + r * 0.62f, c.y + r * 0.8f)
            lineTo(c.x, c.y + r * 0.38f)
            lineTo(c.x - r * 0.62f, c.y + r * 0.8f)
            close()
        }
        drawPath(p, color)
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

/** The days ahead; a tap opens the day's charts and details. */
@Composable
fun DailyCard(fc: Forecast, u: Units, onDay: (Long) -> Unit) {
    val days = remember(fc) { fc.daysFrom(nowSec()) }
    if (days.isEmpty()) return
    val lo = days.minOf { it.min }
    val hi = days.maxOf { it.max }
    Glass {
        CardTitle("${days.size}-day forecast")
        Spacer(Modifier.height(6.dp))
        days.forEachIndexed { i, d ->
            val name = if (i == 0) "Today" else u.weekday(d.time, fc.offset)
            Row(Modifier.fillMaxWidth().height(50.dp).tap(RoundedCornerShape(12.dp), label = "$name details") { onDay(d.time) }
                .padding(horizontal = 4.dp).clearAndSetSemantics {
                    contentDescription = "$name: ${d.text.ifEmpty { Wmo.text(d.code) }}, low ${u.t(d.min)}, high ${u.t(d.max)}" +
                        if (d.pop >= 10) ", rain ${d.pop}%" else ""
                }, verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.width(58.dp), fontSize = 16.sp,
                    color = Color.White, fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal)
                Image(painterResource(Wmo.icon(d.code, true)), null, Modifier.size(32.dp))
                Text(if (d.pop >= 10) "${d.pop}%" else "", Modifier.width(44.dp).padding(start = 4.dp), fontSize = 13.sp, color = RainBlue)
                Text(u.t(d.min), Modifier.width(38.dp), fontSize = 16.sp, color = Dim, textAlign = TextAlign.End)
                RangeBar(d.min, d.max, lo, hi, if (i == 0) fc.current.temp else null,
                    Modifier.weight(1f).padding(horizontal = 10.dp).height(6.dp))
                Text(u.t(d.max), Modifier.width(38.dp), fontSize = 16.sp, color = Color.White, textAlign = TextAlign.End)
            }
        }
        Text("Tap a day for its hours and details", Modifier.fillMaxWidth().padding(top = 6.dp), fontSize = 12.sp,
            color = Faint, textAlign = TextAlign.Center)
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
    val ahead = fc.hoursFrom(now)
    val hour = ahead.firstOrNull()
    val today = fc.daysFrom(now).firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val diff = u.tv(c.feels) - u.tv(c.temp)
            Tile("Feels like", u.t(c.feels), when {
                diff.isNaN() || abs(diff) < 2 -> "Close to the actual temperature"
                diff < 0 -> "Wind makes it feel colder"
                else -> "Humidity makes it feel warmer"
            }, Modifier.weight(1f))
            Tile("Humidity", "${c.humidity}%", hour?.dew?.takeIf { !it.isNaN() }?.let { "Dew point ${u.t(it)} · ${comfort(it)}" } ?: "",
                Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Wind", u.w(c.wind), "From ${compass(c.windDir)} · gusts ${u.w(c.gust)}", Modifier.weight(1f)) {
                Icon(painterResource(R.drawable.ic_arrow), "Wind direction", Modifier.size(22.dp).rotate(c.windDir + 180f), tint = Color.White)
            }
            val uv = hour?.uv?.takeIf { !it.isNaN() } ?: 0.0
            Tile("UV index", "${uv.roundToInt()}", uvText(uv) + (today?.uv?.takeIf { !it.isNaN() }?.let { " · today's high ${it.roundToInt()}" } ?: ""),
                Modifier.weight(1f)) {
                Canvas(Modifier.width(56.dp).height(6.dp)) {
                    drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF6BD66B), Color(0xFFF2E15A), Color(0xFFFFA23E),
                        Color(0xFFFF5A5A), Color(0xFFB77BFF))), cornerRadius = CornerRadius(size.height / 2))
                    drawCircle(Color.White, size.height, Offset(size.width * (uv / 11).toFloat().coerceIn(0f, 1f), size.height / 2))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val later = ahead.getOrNull(3)?.pressure ?: Double.NaN
            val trend = (later - c.pressure).let { d -> when { d.isNaN() -> "At sea level"; d >= 1 -> "Rising"; d <= -1 -> "Falling"; else -> "Steady" } }
            Tile("Pressure", u.pressure(c.pressure), trend, Modifier.weight(1f))
            val vis = hour?.visibility ?: Double.NaN
            Tile("Visibility", u.dist(vis), when {
                vis.isNaN() -> ""
                vis >= 10_000 -> "Clear view"
                vis >= 4_000 -> "Light haze"
                vis >= 1_000 -> "Haze"
                else -> "Fog"
            }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val cloud = if (c.cloud >= 0) c.cloud else hour?.cloud ?: -1
            Tile("Clouds", if (cloud >= 0) "$cloud%" else "–", when {
                cloud < 0 -> ""
                cloud < 20 -> "Clear sky"
                cloud < 50 -> "Partly cloudy"
                cloud < 85 -> "Mostly cloudy"
                else -> "Overcast"
            }, Modifier.weight(1f))
            if (today != null) {
                val next24 = ahead.take(24).sumOf { it.precip.or(0.0) }
                val snow = ahead.take(24).sumOf { it.snow }
                Tile("Precipitation", u.p(today.precip), "Next 24 h ${u.p(next24)}" + if (snow >= 0.1) " · snow ${u.snow(snow)}" else "",
                    Modifier.weight(1f))
            } else Spacer(Modifier.weight(1f))
        }
    }
}

private fun comfort(dew: Double) = when {
    dew.isNaN() -> ""
    dew < 10 -> "dry"
    dew < 16 -> "comfortable"
    dew < 18 -> "a little humid"
    dew < 21 -> "humid"
    dew < 24 -> "muggy"
    else -> "oppressive"
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

fun uvText(uv: Double) = when {
    uv < 3 -> "Low"
    uv < 6 -> "Moderate"
    uv < 8 -> "High · use sun protection"
    uv < 11 -> "Very high · seek shade"
    else -> "Extreme"
}

// --- sun and moon ----------------------------------------------------------------------------------------------------

/** The sun's arc and times (with dawn, dusk and the golden hour) and the moon's phase and times, for [lat], [lon]. */
@Composable
fun SunMoonCard(fc: Forecast, u: Units, lat: Double, lon: Double) {
    val now = nowSec()
    val today = fc.daysFrom(now).firstOrNull() ?: return
    if (today.sunrise <= 0) return
    val yesterday = fc.days.firstOrNull { it.time == today.time - 86400 }
    val sky = remember(today.time, lat, lon) {
        val noon = today.time + 43200
        SkyTimes(Astro.sunTimes(noon, lat, lon, -6.0), Astro.sunTimes(noon, lat, lon, 6.0), Astro.moonTimes(today.time, lat, lon),
            Astro.nextPhase(today.time, 0.5), Astro.nextPhase(today.time, 0.0))
    }
    val (lit, phase) = remember(now / 3600) { Astro.moonPhase(now) }
    Glass {
        CardTitle("Sun")
        Spacer(Modifier.height(8.dp))
        SunArc(today.sunrise, today.sunset, now)
        Row(Modifier.fillMaxWidth()) {
            Fact("Sunrise", u.clock(today.sunrise, fc.offset), Modifier.weight(1f))
            val len = today.sunset - today.sunrise
            Fact("Daylight", "${len / 3600} h ${(len % 3600) / 60} m", Modifier.weight(1f), Alignment.CenterHorizontally)
            Fact("Sunset", u.clock(today.sunset, fc.offset), Modifier.weight(1f), Alignment.End)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Fact("Dawn", sky.dawn?.let { u.clock(it.first, fc.offset) } ?: "–", Modifier.weight(1f))
            Fact("Golden hour", sky.golden?.let { u.clock(it.second, fc.offset) } ?: "–", Modifier.weight(1f), Alignment.CenterHorizontally)
            Fact("Dusk", sky.dawn?.let { u.clock(it.second, fc.offset) } ?: "–", Modifier.weight(1f), Alignment.End)
        }
        yesterday?.takeIf { it.sunrise > 0 }?.let { y ->
            val change = ((today.sunset - today.sunrise) - (y.sunset - y.sunrise)) / 60
            Text(when {
                change > 0 -> "$change min more daylight than yesterday"
                change < 0 -> "${-change} min less daylight than yesterday"
                else -> "As much daylight as yesterday"
            }, Modifier.padding(top = 8.dp), fontSize = 13.sp, color = Dim)
        }
        Spacer(Modifier.height(16.dp))
        CardTitle("Moon")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(64.dp)) { moon(phase, lat < 0) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(Astro.phaseName(phase), fontSize = 20.sp, color = Color.White)
                Text("${(lit * 100).roundToInt()}% lit", fontSize = 14.sp, color = Dim)
                val (full, new) = sky.full to sky.new
                Text(if (full <= new) "Full moon ${u.date(full + 43200, fc.offset)}" else "New moon ${u.date(new + 43200, fc.offset)}",
                    fontSize = 14.sp, color = Dim)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Fact("Moonrise", sky.moon.first?.let { u.clock(it, fc.offset) } ?: "–", Modifier.weight(1f))
            Fact("Moonset", sky.moon.second?.let { u.clock(it, fc.offset) } ?: "–", Modifier.weight(1f), Alignment.End)
        }
    }
}

private class SkyTimes(val dawn: Pair<Long, Long>?, val golden: Pair<Long, Long>?, val moon: Pair<Long?, Long?>, val full: Long, val new: Long)

@Composable
fun Fact(label: String, value: String, modifier: Modifier = Modifier, align: Alignment.Horizontal = Alignment.Start) {
    Column(modifier, horizontalAlignment = align) {
        Text(label, fontSize = 13.sp, color = Dim)
        Text(value, fontSize = 18.sp, color = Color.White)
    }
}

@Composable
private fun SunArc(rise: Long, set: Long, now: Long) {
    val frac = ((now - rise).toFloat() / (set - rise).coerceAtLeast(1)).coerceIn(0f, 1f)
    val up = now in rise..set
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val cx = size.width / 2
        val r = minOf(size.width / 2 - 16.dp.toPx(), size.height - 10.dp.toPx())
        val base = size.height - 4.dp.toPx()
        drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, base), Offset(size.width, base), 1.dp.toPx())
        drawArc(Color.White.copy(alpha = 0.35f), 180f, 180f, false, Offset(cx - r, base - r), Size(2 * r, 2 * r),
            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
        if (up) drawArc(Gold.copy(alpha = 0.9f), 180f, 180f * frac, false, Offset(cx - r, base - r), Size(2 * r, 2 * r),
            style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        val ang = PI * (1 - frac)
        val p = Offset(cx + (r * cos(ang)).toFloat(), base - (r * sin(ang)).toFloat())
        if (up) {
            drawCircle(Brush.radialGradient(listOf(Gold.copy(alpha = 0.6f), Color.Transparent), p, 22.dp.toPx()), 22.dp.toPx(), p)
            drawCircle(Gold, 7.dp.toPx(), p)
        }
    }
}

/** The moon as it looks: the lit part for [phase] (0 new, 0.5 full), lit from the right while waxing (mirrored south). */
fun DrawScope.moon(phase: Double, south: Boolean) {
    val r = size.minDimension / 2
    val c = center
    drawCircle(Color(0xFF2A3346), r, c)
    val k = cos(2 * PI * phase).toFloat()       // 1 new, -1 full: the terminator's half-width, signed
    val waxing = phase < 0.5
    val lit = Path().apply {
        // the lit limb: a half circle on the sun's side, then back along the terminator (a half ellipse)
        moveTo(c.x, c.y - r)
        arcTo(Rect(c, r), -90f, 180f, false)
        // a crescent's terminator bulges toward the lit side (counterclockwise from the bottom), a gibbous one away
        arcTo(Rect(c.x - r * abs(k), c.y - r, c.x + r * abs(k), c.y + r), 90f, if (k > 0) -180f else 180f, false)
        close()
    }
    scale(if (waxing != south) 1f else -1f, 1f, c) {
        drawPath(lit, Color(0xFFF3EBD3))
    }
    drawCircle(Color.White.copy(alpha = 0.18f), r, c, style = Stroke(1.dp.toPx()))
}

// --- air quality -----------------------------------------------------------------------------------------------------

private val aqiColors = listOf(Color(0xFF4CD964), Color(0xFFF7E35C), Color(0xFFFF9F43), Color(0xFFFF5A5A), Color(0xFFB57BFF), Color(0xFF9E3A4F))

/** The US AQI now with its band and advice, the pollutants, the next day's AQI and pollen where it is forecast. */
@Composable
fun AirCard(fc: Forecast, u: Units) {
    val a = fc.air ?: return
    val level = Aqi.level(a.aqi)
    Glass {
        CardTitle("Air quality")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${a.aqi}", fontSize = 44.sp, fontWeight = FontWeight.Light, color = Color.White)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(Aqi.names[level], fontSize = 18.sp, color = aqiColors[level], fontWeight = FontWeight.Medium)
                Text("US AQI", fontSize = 12.sp, color = Dim)
            }
        }
        Canvas(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(8.dp)) {
            val stops = listOf(0f, 50f, 100f, 150f, 200f, 300f, 500f)
            val w = size.width / 6
            for (i in 0 until 6) drawRect(aqiColors[i], Offset(w * i, 0f), Size(w + 1, size.height))
            val band = level.coerceAtMost(5)
            val f = ((a.aqi - stops[band]) / (stops[band + 1] - stops[band])).coerceIn(0f, 1f)
            val x = w * (band + f)
            drawCircle(Color(0xFF10233F), size.height * 0.95f, Offset(x, size.height / 2))
            drawCircle(Color.White, size.height * 0.7f, Offset(x, size.height / 2))
        }
        Text(Aqi.advice[level], fontSize = 13.sp, color = Dim, lineHeight = 18.sp)
        Spacer(Modifier.height(12.dp))
        val items = listOf("PM2.5" to a.pm25, "PM10" to a.pm10, "Ozone" to a.o3, "NO₂" to a.no2, "SO₂" to a.so2, "CO" to a.co)
        items.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                row.forEach { (name, v) -> Fact(name, if (v.isNaN()) "–" else "${v.roundToInt()}", Modifier.weight(1f)) }
            }
        }
        Text("Micrograms per cubic meter", fontSize = 11.sp, color = Faint)
        val ahead = remember(a) { val now = nowSec(); a.hours.filter { it.first + 3600 > now }.take(24) }
        if (ahead.size > 4) {
            Spacer(Modifier.height(12.dp))
            Text("Next 24 hours", fontSize = 13.sp, color = Dim)
            Spacer(Modifier.height(6.dp))
            val spoken = "AQI by hour: " + ahead.joinToString(", ") { "${u.hour(it.first, fc.offset)} ${it.second}" }
            Canvas(Modifier.fillMaxWidth().height(44.dp).clearAndSetSemantics { contentDescription = spoken }) {
                val top = maxOf(ahead.maxOf { it.second }, 60).toFloat()
                val cw = size.width / ahead.size
                ahead.forEachIndexed { i, (_, v) ->
                    val h = (v / top * size.height).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(aqiColors[Aqi.level(v)], Offset(cw * i + cw * 0.15f, size.height - h), Size(cw * 0.7f, h), CornerRadius(2.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text(u.hour(ahead.first().first, fc.offset), Modifier.weight(1f), fontSize = 11.sp, color = Faint)
                Text(u.hour(ahead.last().first, fc.offset), fontSize = 11.sp, color = Faint)
            }
        }
        val pollen = a.pollen.filter { it.second >= 1 }   // out of season every kind reads 0: say nothing
        if (pollen.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Pollen", fontSize = 13.sp, color = Dim)
            pollen.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    row.forEach { (name, v) -> Fact(name, pollenLevel(v), Modifier.weight(1f)) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private fun pollenLevel(grains: Double) = when {
    grains < 1 -> "None"
    grains < 20 -> "Low"
    grains < 80 -> "Moderate"
    grains < 200 -> "High"
    else -> "Very high"
}
