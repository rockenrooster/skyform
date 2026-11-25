package dev.skyform.weather.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.AlarmClock
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import dev.skyform.weather.Forecast
import dev.skyform.weather.MainActivity
import dev.skyform.weather.Place
import dev.skyform.weather.R
import dev.skyform.weather.Units
import dev.skyform.weather.Wmo
import dev.skyform.weather.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The home-screen widget: a live clock and date (TextClock), the place, and a picture of the next 24 hours
 * (humidity area, temperature line, every 3 hours: humidity, sky, time) and the week (rain chance bars, temperature
 * range bars, sky, day). The system calls it every 30 minutes; the refresh button asks at once.
 */
class WeatherWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = refresh(c, force = false)

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, options: Bundle) = Widget.updateAll(c)

    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) refresh(c, force = true) else super.onReceive(c, intent)
    }

    private fun refresh(c: Context, force: Boolean) {
        val pending = goAsync()
        val done = AtomicBoolean(false)
        fun finish() { if (done.compareAndSet(false, true)) pending.finish() }
        val app = c.app
        app.scope.launch { delay(9_000); finish() }   // a broadcast has about 10 s; a slow network finishes later, unseen
        app.scope.launch {
            try {
                Widget.updateAll(c)   // at once: the hour columns move on even before new data comes
                val repo = app.repo
                val first = repo.places.value.firstOrNull() ?: return@launch
                if (first.gps) repo.locate(quick = true)
                else repo.refresh(first, if (force) 0 else 20 * 60_000L)
            } catch (_: Exception) {
            } finally {
                finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "dev.skyform.weather.REFRESH"
    }
}

object Widget {
    /** Redraws every placed widget from what the app already has (no network). */
    fun updateAll(c: Context) {
        val m = AppWidgetManager.getInstance(c) ?: return
        val ids = m.getAppWidgetIds(ComponentName(c, WeatherWidget::class.java))
        if (ids.isEmpty()) return
        val repo = c.app.repo
        val place = repo.places.value.firstOrNull()
        val fc = place?.let { repo.forecast(it.id) }
        for (id in ids) runCatching { m.updateAppWidget(id, views(c, m.getAppWidgetOptions(id), place, fc, repo.units.value, repo.widgetAlpha.value)) }
    }

    private fun views(c: Context, opts: Bundle, place: Place?, fc: Forecast?, u: Units, alpha: Float): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget)
        val res = c.resources
        val d = res.displayMetrics.density
        v.setInt(R.id.bg, "setImageAlpha", (alpha * 255).roundToInt())
        v.setViewVisibility(R.id.ampm, if (DateFormat.is24HourFormat(c)) View.GONE else View.VISIBLE)
        v.setTextViewText(R.id.place, place?.let { if (it.gps && it.detail.isNotBlank()) it.detail else it.name } ?: "Open Skyform to choose a place")
        v.setTextViewText(R.id.legend, if (fc != null) "Temperature ${u.tName}, Humidity %" else "")
        if (fc != null) {
            // Portrait size; the header (clock, place, legend) is about 100 sp tall, plus padding.
            val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 320
            val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 380
            val headerDp = 22 + 100 * res.configuration.fontScale
            val w = ((wDp - 24) * d).roundToInt().coerceAtLeast(100)
            val h = ((hDp - headerDp) * d).roundToInt().coerceAtLeast(60)
            v.setImageViewBitmap(R.id.chart, WidgetArt.draw(c, fc, u, w, h))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 0,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags))
        v.setOnClickPendingIntent(R.id.clock_box, PendingIntent.getActivity(c, 1,
            Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags))
        v.setOnClickPendingIntent(R.id.refresh, PendingIntent.getBroadcast(c, 2,
            Intent(c, WeatherWidget::class.java).setAction(WeatherWidget.ACTION_REFRESH), flags))
        return v
    }
}

/** Draws the widget's chart area into a bitmap (also the preview in Settings). */
object WidgetArt {
    private const val GOLD = 0xFFD6A12B.toInt()
    private const val BLUE = 0xFF4A90D9.toInt()
    private const val BLUE_TOP = 0xE03F86CF.toInt()
    private const val BLUE_BOTTOM = 0x801E4B78.toInt()
    private const val GRID = 0x55FFFFFF

    fun draw(c: Context, fc: Forecast, u: Units, w: Int, h: Int, now: Long = System.currentTimeMillis() / 1000): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val dp = c.resources.displayMetrics.density
        val s = (w / dp / 340f).coerceIn(0.8f, 1.3f)   // text and icon scale for the widget's width
        val k = Kit(c, dp, s)
        val withDays = h / dp >= 230
        val split = if (withDays) h * 0.5f else h.toFloat()
        hourly(cv, k, fc, u, w.toFloat(), split, now)
        if (withDays) daily(cv, k, fc, u, w.toFloat(), split + 8 * dp, h.toFloat(), now)
        return bmp
    }

    /** Paints, icons and sizes for one drawing. */
    private class Kit(val c: Context, val dp: Float, val s: Float) {
        private val icons = HashMap<Int, Drawable>()
        fun icon(res: Int): Drawable = icons.getOrPut(res) { c.getDrawable(res)!!.mutate() }
        fun text(size: Float, light: Boolean = false, align: Paint.Align = Paint.Align.CENTER) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = size * dp * s
            textAlign = align
            typeface = Typeface.create(if (light) "sans-serif-light" else "sans-serif", Typeface.NORMAL)
            setShadowLayer(3 * dp, 0f, 1 * dp, 0xAA000000.toInt())
        }
        fun drawIcon(cv: Canvas, res: Int, cx: Float, top: Float, size: Float) {
            val half = size * dp * s / 2
            icon(res).apply { setBounds((cx - half).toInt(), top.toInt(), (cx + half).toInt(), (top + 2 * half).toInt()); draw(cv) }
        }
        val px get() = dp * s
    }

    private fun hourly(cv: Canvas, k: Kit, fc: Forecast, u: Units, w: Float, bottom: Float, now: Long) {
        val hours = fc.hoursFrom(now).take(25)
        if (hours.size < 2) return
        val n = hours.size - 1
        val cw = w / 9
        fun x(i: Int) = cw / 2 + (w - cw) * i / 24f
        val rows = 58 * k.px                      // humidity, icon, time
        val top = 2 * k.dp
        val base = bottom - rows                  // the chart's floor
        val chartH = base - top

        // humidity: an area on a 0-100 scale
        fun yh(v: Int) = base - chartH * 0.9f * v / 100f
        val hum = hours.mapIndexed { i, h -> x(i) to yh(h.humidity) }
        val humLine = smooth(hum)
        val area = Path(humLine).apply { lineTo(x(n), base); lineTo(x(0), base); close() }
        cv.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, top, 0f, base, BLUE_TOP, BLUE_BOTTOM, Shader.TileMode.CLAMP) })
        cv.drawPath(humLine, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * k.dp; color = BLUE })
        cv.drawLine(x(0), base, x(n), base, Paint().apply { color = GRID; strokeWidth = k.dp })

        // temperature: a line over the day's own range, labels every 3 hours
        val temps = hours.map { u.tv(it.temp) }
        val lo = temps.min()
        val range = max(temps.max() - lo, 4.0)
        val labelRoom = 20 * k.px
        fun yt(v: Double) = (top + labelRoom + (chartH - labelRoom - 10 * k.px) * (1 - (v - lo) / range)).toFloat()
        val tLine = smooth(temps.mapIndexed { i, t -> x(i) to yt(t) })
        cv.drawPath(tLine, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2.6f * k.dp; color = GOLD; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            setShadowLayer(2 * k.dp, 0f, 0f, 0x88000000.toInt())
        })
        val tPaint = k.text(17f, light = true)
        val humPaint = k.text(14f)
        val timePaint = k.text(14f)
        for (i in 0..n step 3) {
            val h = hours[i]
            val p = Paint(tPaint).apply { textAlign = when (i) { 0 -> Paint.Align.LEFT; 24 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER } }
            val tx = when (i) { 0 -> x(i) - cw * 0.35f; 24 -> x(i) + cw * 0.35f; else -> x(i) }
            cv.drawText(u.t(h.temp), tx, yt(temps[i]) - 6 * k.dp, p)
            cv.drawText("${h.humidity}", x(i), base + 17 * k.px, humPaint)
            k.drawIcon(cv, Wmo.icon(h.code, h.isDay), x(i), base + 20 * k.px, 22f)
            cv.drawText(u.hourShort(h.time, fc.offset), x(i), base + 56 * k.px, timePaint)
        }
    }

    private fun daily(cv: Canvas, k: Kit, fc: Forecast, u: Units, w: Float, top: Float, bottom: Float, now: Long) {
        val days = fc.daysFrom(now).take(7)
        if (days.isEmpty()) return
        val cw = w / days.size
        val rows = 60 * k.px                      // rain chance, icon, day
        val base = bottom - rows
        val area = base - top
        // rain chance: bars up from the floor
        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        days.forEachIndexed { i, d ->
            if (d.pop <= 0) return@forEachIndexed
            val cx = cw * (i + 0.5f)
            val bh = max(area * 0.78f * d.pop / 100f, 2 * k.dp)
            bar.shader = LinearGradient(0f, base - bh, 0f, base, BLUE_TOP, BLUE_BOTTOM, Shader.TileMode.CLAMP)
            cv.drawRect(cx - cw * 0.3f, base - bh, cx + cw * 0.3f, base, bar)
        }
        cv.drawLine(0f, base, w, base, Paint().apply { color = GRID; strokeWidth = k.dp })
        // temperature range: a gold bar from the low to the high, on the week's scale
        val lo = days.minOf { u.tv(it.min) }
        val hi = days.maxOf { u.tv(it.max) }
        val span = max(hi - lo, 4.0)
        val labelH = 22 * k.px
        fun y(v: Double) = (top + labelH + (area - 2 * labelH) * (1 - (v - lo) / span)).toFloat()
        val range = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(2 * k.dp, 0f, 0f, 0x66000000) }
        val hiPaint = k.text(16f, light = true)
        val pctPaint = k.text(15f)
        val pctSmall = k.text(10f, align = Paint.Align.LEFT)
        val dayPaint = k.text(16f)
        val barW = 3.2f * k.px
        days.forEachIndexed { i, d ->
            val cx = cw * (i + 0.5f)
            val yTop = y(u.tv(d.max))
            val yBot = max(y(u.tv(d.min)), yTop + 6 * k.dp)
            range.shader = LinearGradient(0f, yTop, 0f, yBot, 0xFFE9BE50.toInt(), 0xFFB98A1F.toInt(), Shader.TileMode.CLAMP)
            cv.drawRoundRect(RectF(cx - barW, yTop, cx + barW, yBot), barW, barW, range)
            cv.drawText(u.t(d.max), cx, yTop - 6 * k.dp, hiPaint)
            cv.drawText(u.t(d.min), cx, yBot + 18 * k.px, hiPaint)
            // "21%" with a small percent sign, centered as one
            val num = "${d.pop}"
            val nw = pctPaint.measureText(num)
            val pw = pctSmall.measureText("%")
            val left = cx - (nw + pw) / 2
            cv.drawText(num, left + nw / 2, base + 17 * k.px, pctPaint)
            cv.drawText("%", left + nw, base + 17 * k.px, pctSmall)
            k.drawIcon(cv, Wmo.icon(d.code, true), cx, base + 21 * k.px, 22f)
            cv.drawText(u.weekday(d.time, fc.offset), cx, base + 58 * k.px, dayPaint)
        }
    }

    /** A smooth curve through the points (Catmull-Rom as cubic Béziers). */
    private fun smooth(p: List<Pair<Float, Float>>): Path = Path().apply {
        moveTo(p[0].first, p[0].second)
        for (i in 0 until p.size - 1) {
            val p0 = p[(i - 1).coerceAtLeast(0)]
            val p1 = p[i]
            val p2 = p[i + 1]
            val p3 = p[(i + 2).coerceAtMost(p.size - 1)]
            cubicTo(p1.first + (p2.first - p0.first) / 6, p1.second + (p2.second - p0.second) / 6,
                p2.first - (p3.first - p1.first) / 6, p2.second - (p3.second - p1.second) / 6, p2.first, p2.second)
        }
    }
}
