package dev.skyform.weather

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.skyform.weather.ui.SkyformApp
import dev.skyform.weather.update.Installer
import dev.skyform.weather.update.Updater
import dev.skyform.weather.update.UrlHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repo by lazy { Repo(this) }
    val updater by lazy {
        Updater(BuildConfig.BUILD_NUMBER, BuildConfig.REPO, File(cacheDir, "updates"), UrlHttp,
            { Installer.verify(this, it) }, System::currentTimeMillis, scope)
    }
}

val Context.app: App get() = applicationContext as App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        // Test runs (scripts\swipe-test.ps1) hold this window at the display's top refresh rate: adb's swipes do not
        // raise it the way a finger does, and frame times are judged against 120 Hz.
        if (intent.getBooleanExtra("hz120", false)) {
            @Suppress("DEPRECATION") val d = windowManager.defaultDisplay
            d.supportedModes.filter { it.physicalWidth == d.mode.physicalWidth && it.physicalHeight == d.mode.physicalHeight }
                .maxByOrNull { it.refreshRate }?.let { m -> window.attributes = window.attributes.apply { preferredDisplayModeId = m.modeId } }
        }
        setContent { SkyformApp() }
    }
}
