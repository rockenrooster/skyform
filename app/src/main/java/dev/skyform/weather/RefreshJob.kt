package dev.skyform.weather

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dev.skyform.weather.widget.Widget
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The app's one background task: about every 30 minutes, when the phone is online and Android allows (it batches
 * jobs, and in Doze runs them in its maintenance windows), it refreshes the first place. That redraws the widgets and
 * the notifications. It is scheduled only while a widget is placed or a notification is on; nothing else ever wakes
 * the phone (the widgets' own update period is 0).
 */
class RefreshJob : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext.app
        job = app.scope.launch {
            try {
                val repo = app.repo
                val first = repo.places.value.firstOrNull()
                if (first != null) {
                    if (first.gps) repo.locate(quick = true) else repo.refresh(first, 20 * 60_000L)
                    Notify.daily(app)   // also when the forecast was fresh enough not to fetch
                }
            } catch (_: Exception) {
            } finally {
                if (isActive) jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return false   // periodic: it simply runs again next time
    }

    companion object {
        private const val ID = 1
        private const val PERIOD = 30 * 60_000L

        /** Schedules the job while there is something for it to do, and cancels it otherwise. */
        fun sync(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            val repo = c.app.repo
            val needed = Widget.placed(c) || repo.notifyNow.value || repo.notifyAlerts.value || repo.notifyRain.value || repo.notifyDaily.value
            if (!needed) { js.cancel(ID); return }
            if (js.getPendingJob(ID)?.intervalMillis == PERIOD) return   // scheduling it again would restart its clock
            runCatching {   // a refusal (a phone's own limits) must never take the app down: it refreshes when opened
                js.schedule(JobInfo.Builder(ID, ComponentName(c, RefreshJob::class.java))
                    .setPeriodic(PERIOD, 10 * 60_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build())
            }
        }
    }
}
