package dev.skyform.weather

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import java.time.LocalDateTime

/**
 * Notifications, all for the first place (the widget's): the temperature in the status bar (a silent, ongoing
 * notification), NWS alerts as they are issued, rain or snow about to start (the 15-minute forecast) and the day's
 * forecast once a day after a set time. Each is a setting, off until the owner turns it on. They are posted after
 * refreshes: the app's own, and the background job's ([RefreshJob]).
 */
object Notify {
    private const val NOW = "now"
    private const val ALERTS = "alerts"
    private const val RAIN = "rain"
    private const val DAILY = "daily"
    private const val ID_NOW = 1
    private const val ID_RAIN = 2
    private const val ID_DAILY = 3

    fun allowed(c: Context) =
        Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun manager(c: Context): NotificationManager? {
        if (!allowed(c)) return null
        val nm = c.getSystemService(NotificationManager::class.java) ?: return null
        if (nm.getNotificationChannel(DAILY) == null) nm.createNotificationChannels(listOf(
            NotificationChannel(NOW, "Temperature in the status bar", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            NotificationChannel(ALERTS, "Weather alerts", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(RAIN, "Rain or snow starting soon", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(DAILY, "Today's forecast", NotificationManager.IMPORTANCE_DEFAULT).apply { setShowBadge(false) },
        ))
        return nm
    }

    /** After a refresh of the first place. */
    fun onForecast(c: Context, p: Place, fc: Forecast) {
        val repo = c.app.repo
        val nm = manager(c) ?: return
        if (repo.notifyNow.value) now(c, nm, p, fc, repo.units.value)
        if (repo.notifyAlerts.value) alerts(c, nm, p, fc)
        if (repo.notifyRain.value) rain(c, nm, p, fc, repo.units.value)
        daily(c)
    }

    /** A setting changed: shows or removes the status-bar temperature from what is already known. */
    fun refresh(c: Context) {
        val repo = c.app.repo
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val p = repo.places.value.firstOrNull()
        val fc = p?.let { repo.forecast(it.id) }
        if (!repo.notifyNow.value || p == null || fc == null || manager(c) == null) nm.cancel(ID_NOW)
        else now(c, nm, p, fc, repo.units.value)
    }

    private fun open(c: Context) = PendingIntent.getActivity(c, 10, Intent(c, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** The temperature drawn as the status-bar icon (Android uses only its alpha). */
    private fun tempIcon(c: Context, text: String): Icon {
        val px = (24 * c.resources.displayMetrics.density).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = px * 0.8f
        }
        while (paint.measureText(text) > px && paint.textSize > 6) paint.textSize -= 1
        val m = paint.fontMetrics
        Canvas(bmp).drawText(text, px / 2f, px / 2f - (m.ascent + m.descent) / 2, paint)
        return Icon.createWithBitmap(bmp)
    }

    private fun picture(c: Context, res: Int): Icon {
        val px = (48 * c.resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        c.getDrawable(res)?.apply { setBounds(0, 0, px, px); draw(Canvas(bmp)) }
        return Icon.createWithBitmap(bmp)
    }

    private fun now(c: Context, nm: NotificationManager, p: Place, fc: Forecast, u: Units) {
        val cur = fc.current
        val today = fc.daysFrom(System.currentTimeMillis() / 1000).firstOrNull()
        val sub = listOfNotNull(p.name, today?.let { "H ${u.t(it.max)} L ${u.t(it.min)}" },
            cur.feels.takeIf { !it.isNaN() }?.let { "Feels like ${u.t(it)}" }).joinToString(" · ")
        nm.notify(ID_NOW, Notification.Builder(c, NOW)
            .setSmallIcon(tempIcon(c, u.t(cur.temp)))
            .setLargeIcon(picture(c, Wmo.icon(cur.code, cur.isDay)))
            .setContentTitle("${u.t(cur.temp)} · ${cur.text.ifEmpty { Wmo.text(cur.code, cur.isDay) }}")
            .setContentText(sub)
            .setContentIntent(open(c))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .build())
    }

    private fun alerts(c: Context, nm: NotificationManager, p: Place, fc: Forecast) {
        val repo = c.app.repo
        val now = System.currentTimeMillis() / 1000
        val seen = repo.note("alerts").split(",").toSet()
        val live = fc.alerts.filter { it.ends == 0L || it.ends > now }
        for (a in live) {
            val key = "${a.event}|${a.ends}|${a.headline}".hashCode()
            if ("$key" in seen) continue
            nm.notify(100 + (key and 0xFFFF), Notification.Builder(c, ALERTS)
                .setSmallIcon(R.drawable.ic_alert)
                .setContentTitle("${a.event} · ${p.name}")
                .setContentText(a.headline.ifBlank { a.description.take(120) })
                .setStyle(Notification.BigTextStyle().bigText(listOf(a.headline, a.description.take(800)).filter { it.isNotBlank() }.joinToString("\n\n")))
                .setContentIntent(open(c))
                .setAutoCancel(true)
                .apply { if (a.ends > now) setTimeoutAfter((a.ends - now) * 1000) }
                .build())
        }
        // remember the alerts still in force (a changed alert is announced again)
        repo.setNote("alerts", live.joinToString(",") { "${"${it.event}|${it.ends}|${it.headline}".hashCode()}" })
    }

    private fun rain(c: Context, nm: NotificationManager, p: Place, fc: Forecast, u: Units) {
        val repo = c.app.repo
        val now = System.currentTimeMillis() / 1000
        val q = fc.quarters.filter { it.time > now }
        if (q.isEmpty() || q[0].precip >= 0.1) return                  // dry ahead, or already falling
        val start = q.firstOrNull { it.precip >= 0.1 } ?: return
        val at = start.time - 900
        if (at - now > 45 * 60) return                                     // a later run will tell
        if (now - (repo.note("rain").toLongOrNull() ?: 0L) < 3 * 3600) return   // at most every 3 hours
        repo.setNote("rain", "$now")
        val snow = fc.hours.firstOrNull { it.time <= at && at < it.time + 3600 }?.let { Wmo.sky(it.code) == Sky.SNOW } ?: false
        val kind = if (snow) "Snow" else "Rain"
        val mins = ((at - now) / 60).coerceAtLeast(0)
        nm.notify(ID_RAIN, Notification.Builder(c, RAIN)
            .setSmallIcon(R.drawable.ic_drop)
            .setContentTitle(if (mins < 10) "$kind starting now" else "$kind starting in about ${(mins + 4) / 5 * 5} minutes")
            .setContentText("${p.name} · around ${u.clock(at, fc.offset)}")
            .setContentIntent(open(c))
            .setAutoCancel(true)
            .setTimeoutAfter(90 * 60_000L)
            .build())
    }

    /** Today's forecast, once a day, at the first refresh after the set time (the phone's clock). */
    fun daily(c: Context) {
        val repo = c.app.repo
        if (!repo.notifyDaily.value) return
        val nm = manager(c) ?: return
        val p = repo.places.value.firstOrNull() ?: return
        val fc = repo.forecast(p.id) ?: return
        val local = LocalDateTime.now()
        val date = local.toLocalDate().toString()
        if (repo.note("daily") == date || local.hour * 60 + local.minute < repo.dailyAt.value) return
        val d = fc.daysFrom(System.currentTimeMillis() / 1000).firstOrNull() ?: return
        repo.setNote("daily", date)
        val u = repo.units.value
        val text = d.text.ifEmpty { Wmo.text(d.code) }
        val more = listOfNotNull("Rain ${d.pop}%".takeIf { d.pop >= 10 }, "wind up to ${u.w(d.wind)}",
            d.uv.takeIf { !it.isNaN() && it >= 3 }?.let { "UV ${it.toInt()}" }).joinToString(" · ")
        nm.notify(ID_DAILY, Notification.Builder(c, DAILY)
            .setSmallIcon(tempIcon(c, u.t(d.max)))
            .setLargeIcon(picture(c, Wmo.icon(d.code, true)))
            .setContentTitle("Today in ${p.name}: $text, ${u.t(d.max)} / ${u.t(d.min)}")
            .setContentText(more)
            .setStyle(Notification.BigTextStyle().bigText(d.detail.substringBefore("\n\n").substringAfter(": ").ifBlank { more }))
            .setContentIntent(open(c))
            .setAutoCancel(true)
            .setTimeoutAfter(16 * 3600_000L)
            .build())
    }
}
