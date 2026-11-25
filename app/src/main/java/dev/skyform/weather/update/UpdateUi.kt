package dev.skyform.weather.update

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyform.weather.app
import dev.skyform.weather.ui.Dim
import dev.skyform.weather.ui.Pill

/** The update pill under the top bar and its sheet (the flow is Trueform's; see its docs/in-app-updater.md). */
object UpdateUi {
    /** Shown while a newer build exists or a download is under way. */
    @Composable
    fun Banner(onOpen: () -> Unit) {
        val s by LocalContext.current.app.updater.state.collectAsState()
        val r = releaseOf(s) ?: return
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Pill(if (s is UpdateState.Downloading) "Downloading update…" else "Update available · build ${r.build}", primary = true, onClick = onOpen)
        }
    }

    @Composable
    fun Sheet() {
        val context = LocalContext.current
        val updater = context.app.updater
        val s by updater.state.collectAsState()
        val r = releaseOf(s)
        // "Download and install": the installer starts as soon as the verified file is there
        LaunchedEffect(s) { (s as? UpdateState.Ready)?.let { ready -> context.activity()?.let { Installer.install(it, ready.file) } } }
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(r?.title ?: "Updates", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            if (r == null) {
                Text("Skyform is up to date.", fontSize = 15.sp, color = Dim)
                return@Column
            }
            Text("%.1f MB".format(r.apkSize / 1e6), fontSize = 13.sp, color = Dim)
            val st = s
            if (st is UpdateState.Downloading) {
                Text("Downloading… ${(st.fraction * 100).toInt()}%", fontSize = 15.sp, color = Color.White)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.15f))) {
                    Box(Modifier.fillMaxWidth(st.fraction).height(6.dp).background(Color.White))
                }
            }
            if (st is UpdateState.Failed) Text("Not installed: ${st.reason}", fontSize = 13.sp, color = Color(0xFFFF8A80), maxLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                when (st) {
                    is UpdateState.Downloading -> Unit
                    is UpdateState.Ready -> Pill("Install", primary = true) { context.activity()?.let { Installer.install(it, st.file) } }
                    is UpdateState.Failed -> Pill("Try again", primary = true) { updater.download(r) }
                    else -> Pill("Download and install", primary = true) { updater.download(r) }
                }
                Pill("View on GitHub") { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl))) } }
            }
            Box(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                Text(notesText(r.notes), fontSize = 14.sp, color = Dim)
            }
        }
    }

    /** A sheet's LocalContext is a wrapper around the activity, not the activity itself. */
    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

    private fun releaseOf(s: UpdateState): Release? = when (s) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.Ready -> s.release
        is UpdateState.Failed -> s.release
        UpdateState.Idle -> null
    }

    /** Release notes as plain text: no Markdown emphasis or code marks, "-" lines as bullets. */
    fun notesText(md: String): String = md.lines().joinToString("\n") { line ->
        line.replace("**", "").replace("`", "").let { if (it.startsWith("- ")) "•  " + it.removePrefix("- ") else it }
    }.trim()
}
