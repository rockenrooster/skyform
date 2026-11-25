package dev.skyform.weather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyform.weather.Astro
import dev.skyform.weather.Forecast
import dev.skyform.weather.Hour
import dev.skyform.weather.R
import dev.skyform.weather.Units
import dev.skyform.weather.Wmo
import dev.skyform.weather.compass
import kotlin.math.roundToInt

private enum class Draw { LINE, DASH, BARS, AREA }

/** One line, area or set of bars in a day chart; [label] marks every third hour's value. */
private class Series(val values: List<Double>, val color: Color, val draw: Draw, val label: ((Double) -> String)? = null)

private class DayChartData(val title: String, val key: String, val series: List<Series>, val range: ClosedFloatingPointRange<Double>?,
                           val arrows: List<Int>? = null)

/**
 * A day in full: the forecaster's words, its hours as charts (temperature and feels-like, chance and amount of rain,
 * wind and gusts with their direction, humidity and clouds, UV, pressure) and its facts (rain, wind, sun, twilight,
 * moon). The arrows step through the days.
 */
@Composable
fun DaySheet(fc: Forecast, u: Units, lat: Double, lon: Double, start: Long, onDay: (Long) -> Unit) {
    val days = remember(fc) { fc.daysFrom(nowSec()) }
    val i = days.indexOfFirst { it.time == start }.coerceAtLeast(0)
    val d = days.getOrNull(i) ?: return
    val hours = remember(fc, d.time) { fc.hoursOf(d.time) }
    val charts = remember(hours, u) { charts(hours, u) }
    val noon = d.time + 43200
    val sky = remember(d.time, lat, lon) {
        listOf(Astro.sunTimes(noon, lat, lon, -6.0), Astro.sunTimes(noon, lat, lon, 6.0)) to Astro.moonTimes(d.time, lat, lon)
    }
    val (lit, phase) = remember(d.time) { Astro.moonPhase(noon) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        .navigationBarsPadding().padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(R.drawable.ic_back, "Previous day", enabled = i > 0) { onDay(days[i - 1].time) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when (i) { 0 -> "Today"; 1 -> "Tomorrow"; else -> u.weekdayLong(d.time, fc.offset) }, fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(u.longDate(d.time, fc.offset), fontSize = 13.sp, color = Dim)
            }
            IconBtn(R.drawable.ic_forward, "Next day", enabled = i < days.size - 1) { onDay(days[i + 1].time) }
        }
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(Wmo.icon(d.code, true)), null, Modifier.size(64.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(d.text.ifEmpty { Wmo.text(d.code) }, fontSize = 20.sp, color = Color.White)
                Text("High ${u.t(d.max)} · Low ${u.t(d.min)}" + if (d.pop >= 10) " · rain ${d.pop}%" else "", fontSize = 15.sp, color = Dim)
            }
        }
        if (d.detail.isNotBlank()) Text(d.detail, fontSize = 14.sp, color = Dim, lineHeight = 19.sp)

        charts.forEach { c ->
            Spacer(Modifier.height(18.dp))
            CardTitle(c.title)
            Spacer(Modifier.height(6.dp))
            DayChart(hours, fc, u, c)
        }

        Spacer(Modifier.height(20.dp))
        CardTitle("Details")
        Spacer(Modifier.height(6.dp))
        val facts = buildList {
            add("Rain" to (u.p(d.precip) + (d.precipHours.takeIf { !it.isNaN() && it > 0 }?.let { " in ${it.roundToInt()} h" } ?: "")))
            add("Chance of rain" to "${d.pop}%")
            if (d.snow > 0.05) add("Snow" to u.snow(d.snow))
            add("Wind" to (u.w(d.wind) + if (d.windDir >= 0) " from ${compass(d.windDir)}" else ""))
            if (!d.gust.isNaN()) add("Gusts" to u.w(d.gust))
            if (!d.uv.isNaN()) add("UV index" to "${d.uv.roundToInt()} · ${uvText(d.uv).substringBefore(" ·")}")
            if (!d.sunshine.isNaN()) add("Sunshine" to "%.1f h".format(d.sunshine / 3600))
            if (d.sunrise > 0) {
                add("Sunrise" to u.clock(d.sunrise, fc.offset))
                add("Sunset" to u.clock(d.sunset, fc.offset))
                val len = d.sunset - d.sunrise
                add("Daylight" to "${len / 3600} h ${(len % 3600) / 60} m")
            }
            sky.first[0]?.let { (dawn, dusk) -> add("Dawn" to u.clock(dawn, fc.offset)); add("Dusk" to u.clock(dusk, fc.offset)) }
            sky.first[1]?.let { (_, golden) -> add("Golden hour" to u.clock(golden, fc.offset)) }
            add("Moonrise" to (sky.second.first?.let { u.clock(it, fc.offset) } ?: "–"))
            add("Moonset" to (sky.second.second?.let { u.clock(it, fc.offset) } ?: "–"))
            add("Moon" to "${Astro.phaseName(phase)}, ${(lit * 100).roundToInt()}% lit")
        }
        facts.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                row.forEach { (k, v) -> Fact(k, v, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun Double.or(other: Double) = if (isNaN()) other else this

/** The charts for a day's [hours], in display units. */
private fun charts(hours: List<Hour>, u: Units): List<DayChartData> {
    if (hours.size < 6) return emptyList()
    val pct: (Double) -> String = { "${it.roundToInt()}%" }
    return buildList {
        add(DayChartData("Temperature · feels like (dashed)", "temp", listOf(
            Series(hours.map { u.tv(it.feels.or(it.temp)) }, Color(0xFFFF9E6B), Draw.DASH),
            Series(hours.map { u.tv(it.temp) }, Gold, Draw.LINE) { "${it.roundToInt()}°" },
        ), null))
        add(DayChartData("Chance of rain", "pop", listOf(Series(hours.map { it.pop.toDouble() }, RainBlue, Draw.BARS) { if (it >= 10) pct(it) else "" }),
            0.0..100.0))
        if (hours.any { it.precip >= 0.05 || it.snow >= 0.05 }) {
            val amt = hours.map { if (u.inches) it.precip / 25.4 else it.precip }
            add(DayChartData("Amount (${if (u.inches) "in" else "mm"})", "amount", listOf(Series(amt, Color(0xFF5FA8FF), Draw.BARS) {
                if (it < 0.005) "" else if (u.inches) "%.2f".format(it) else "%.1f".format(it)
            }), 0.0..maxOf(amt.max() * 1.15, if (u.inches) 0.1 else 2.5)))
        }
        add(DayChartData("Wind · gusts (dashed), ${u.windName}", "wind", listOf(
            Series(hours.map { u.windV(it.gust.or(it.wind)) }, Color(0xFF8CF0D8).copy(alpha = 0.7f), Draw.DASH),
            Series(hours.map { u.windV(it.wind) }, Color(0xFF8CF0D8), Draw.LINE) { "${it.roundToInt()}" },
        ), 0.0..maxOf(hours.maxOf { u.windV(it.gust.or(it.wind)) } * 1.1, 5.0), hours.map { it.windDir }))
        add(DayChartData("Humidity · clouds (shaded)", "humidity", listOf(
            Series(hours.map { it.cloud.coerceAtLeast(0).toDouble() }, Color(0xFFCFD8E3), Draw.AREA),
            Series(hours.map { it.humidity.toDouble() }, Color(0xFFA9C8FF), Draw.LINE, pct),
        ), 0.0..100.0))
        if (hours.any { it.uv.or(0.0) >= 1 }) add(DayChartData("UV index", "uv", listOf(
            Series(hours.map { it.uv.or(0.0) }, Color(0xFFFFB347), Draw.BARS) { if (it < 0.5) "" else "${it.roundToInt()}" }), 0.0..11.0))
        if (hours.none { it.pressure.isNaN() }) add(DayChartData("Pressure (${u.pressure(1013.0).substringAfter(' ')})", "pressure", listOf(
            Series(hours.map { u.pressureV(it.pressure) }, Color(0xFFD4B5FF), Draw.LINE) {
                if (u.pressureUnit == "inhg") "%.2f".format(it) else "${it.roundToInt()}"
            }), null))
    }
}

@Composable
private fun DayChart(hours: List<Hour>, fc: Forecast, u: Units, c: DayChartData, height: Dp = 130.dp) {
    val measurer = rememberTextMeasurer()
    val labeled = c.series.lastOrNull { it.label != null }
    val texts = remember(hours, c) {
        val valueStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        val timeStyle = TextStyle(color = Dim, fontSize = 11.sp)
        val values = hours.indices.map { i -> labeled?.takeIf { i % 3 == 0 }?.let { s -> measurer.measure(s.label!!(s.values[i]), valueStyle) } }
        val times = hours.indices.map { i -> if (i % 3 == 0) measurer.measure(u.hourShort(hours[i].time, fc.offset), timeStyle) else null }
        values to times
    }
    val spoken = remember(hours, c) {
        c.title + ": " + labeled?.let { s -> hours.indices.filter { it % 3 == 0 }.joinToString(", ") { "${u.hour(hours[it].time, fc.offset)} ${s.label!!(s.values[it])}" } }
    }
    Canvas(Modifier.fillMaxWidth().height(height + if (c.arrows != null) 18.dp else 0.dp).clearAndSetSemantics { contentDescription = spoken }) {
        val n = hours.size
        val colW = size.width / n
        val arrowH = if (c.arrows != null) 18.dp.toPx() else 0f
        val timeH = 16.dp.toPx()
        val top = 20.dp.toPx()
        val bottom = size.height - timeH - arrowH - 4.dp.toPx()
        val all = c.series.flatMap { s -> s.values.filter { !it.isNaN() } }
        val lo = c.range?.start ?: ((all.minOrNull() ?: 0.0) - 0.5)
        val hi = c.range?.endInclusive ?: ((all.maxOrNull() ?: 1.0) + 0.5)
        val span = (hi - lo).takeIf { it > 0.001 } ?: 1.0
        fun x(i: Int) = colW * (i + 0.5f)
        fun y(v: Double) = (bottom - (bottom - top) * ((v - lo) / span).coerceIn(0.0, 1.0)).toFloat()
        drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, (top + bottom) / 2), Offset(size.width, (top + bottom) / 2), 1.dp.toPx())
        c.series.forEach { s ->
            when (s.draw) {
                Draw.BARS -> s.values.forEachIndexed { i, v ->
                    if (v.isNaN() || v <= lo) return@forEachIndexed
                    val yy = y(v).coerceAtMost(bottom - 1.5.dp.toPx())
                    drawRoundRect(s.color, Offset(x(i) - colW * 0.32f, yy), Size(colW * 0.64f, bottom - yy), CornerRadius(2.dp.toPx()))
                }
                else -> {
                    val pts = s.values.mapIndexedNotNull { i, v -> if (v.isNaN()) null else Offset(x(i), y(v)) }
                    if (pts.size < 2) return@forEach
                    val line = smoothPath(pts)
                    if (s.draw == Draw.AREA) {
                        val fill = Path().apply { addPath(line); lineTo(pts.last().x, bottom); lineTo(pts.first().x, bottom); close() }
                        drawPath(fill, s.color.copy(alpha = 0.22f))
                        drawPath(line, s.color.copy(alpha = 0.5f), style = Stroke(1.dp.toPx()))
                    } else {
                        drawPath(line, s.color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round,
                            pathEffect = if (s.draw == Draw.DASH) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
                    }
                }
            }
        }
        val (values, times) = texts
        fun place(t: TextLayoutResult, cx: Float, yTop: Float) =
            drawText(t, topLeft = Offset((cx - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), yTop.coerceAtLeast(0f)))
        values.forEachIndexed { i, t ->
            val v = labeled?.values?.getOrNull(i) ?: return@forEachIndexed
            if (t == null || v.isNaN()) return@forEachIndexed
            place(t, x(i), y(v.coerceAtLeast(lo)) - t.size.height - 3.dp.toPx())
        }
        times.forEachIndexed { i, t -> if (t != null) place(t, x(i), size.height - t.size.height) }
        c.arrows?.forEachIndexed { i, dir ->
            if (i % 3 == 0 && dir >= 0) windArrow(Offset(x(i), bottom + 4.dp.toPx() + arrowH / 2), dir, 6.dp.toPx())
        }
    }
}
