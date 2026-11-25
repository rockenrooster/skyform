package dev.skyform.weather.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyform.weather.BuildConfig
import dev.skyform.weather.Locator
import dev.skyform.weather.Notify
import dev.skyform.weather.OpenMeteo
import dev.skyform.weather.Place
import dev.skyform.weather.R
import dev.skyform.weather.Wmo
import dev.skyform.weather.app
import dev.skyform.weather.widget.NowWidget
import dev.skyform.weather.widget.WeatherWidget
import dev.skyform.weather.widget.WeekWidget
import dev.skyform.weather.widget.WidgetArt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
private fun SheetTitle(text: String) =
    Text(text, Modifier.padding(bottom = 12.dp), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)

@Composable
private fun Section(text: String) =
    Text(text.uppercase(), Modifier.padding(top = 20.dp, bottom = 8.dp), color = Dim, fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)

// --- places ----------------------------------------------------------------------------------------------------------

@Composable
fun PlacesSheet(onPick: (Int) -> Unit, onLocate: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.app.repo
    val places by repo.places.collectAsState()
    val forecasts by repo.forecasts.collectAsState()
    val units by repo.units.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        failed = false
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        searching = true
        results = runCatching { Locator.search(query) }.getOrElse { failed = true; emptyList() }
        searching = false
    }

    Column(Modifier.fillMaxWidth().heightIn(min = 420.dp).verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
        SheetTitle("Places")
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Search for a city") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            trailingIcon = if (query.isNotEmpty()) ({ IconBtn(R.drawable.ic_close, "Clear", 40.dp) { query = "" } }) else null,
            shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.height(10.dp))
        if (query.isNotBlank()) {
            when {
                searching -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
                failed -> Text("Search failed. Check the connection and try again.", Modifier.padding(12.dp), color = Dim)
                results.isEmpty() && query.trim().length >= 2 -> Text("No places found.", Modifier.padding(12.dp), color = Dim)
            }
            results.forEach { p ->
                Row(Modifier.fillMaxWidth().tap(label = "Add ${p.name}") {
                    repo.addPlace(p)
                    scope.launch { repo.refresh(p) }
                    onPick(repo.places.value.indexOfFirst { it.id == p.id }.coerceAtLeast(0))
                }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_add), null, Modifier.size(20.dp), tint = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(p.name, fontSize = 17.sp, color = Color.White)
                        if (p.detail.isNotBlank()) Text(p.detail, fontSize = 13.sp, color = Dim)
                    }
                }
            }
            return@Column
        }
        if (places.none { it.gps }) {
            Row(Modifier.fillMaxWidth().tap(label = "Use my location", onClick = onLocate).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_my_location), null, Modifier.size(22.dp), tint = RainBlue)
                Spacer(Modifier.width(12.dp))
                Text("Use my location", fontSize = 17.sp, color = RainBlue)
            }
        }
        places.forEachIndexed { i, p ->
            val fc = forecasts[p.id]
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .tap(RoundedCornerShape(16.dp), label = "Show ${p.name}") { onPick(i) }.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (p.gps) Icon(painterResource(R.drawable.ic_place), null, Modifier.size(15.dp).padding(end = 2.dp), tint = Color.White)
                        Text(p.name, fontSize = 17.sp, color = Color.White, maxLines = 1)
                    }
                    Text(listOfNotNull(p.detail.takeIf { it.isNotBlank() }, if (i == 0) "In the widget" else null).joinToString(" · "),
                        fontSize = 12.sp, color = Dim, maxLines = 1)
                }
                if (fc != null) {
                    Image(painterResource(Wmo.icon(fc.current.code, fc.current.isDay)), null, Modifier.size(30.dp))
                    Text(units.t(fc.current.temp), Modifier.padding(horizontal = 6.dp), fontSize = 20.sp, color = Color.White)
                }
                if (i > 0) IconBtn(R.drawable.ic_up, "Move ${p.name} up", 40.dp) { repo.moveUp(i) }
                IconBtn(R.drawable.ic_close, "Remove ${p.name}", 40.dp) { repo.removePlace(p.id) }
            }
        }
        Text("The first place is the one in the home-screen widget; move a place up to change it.",
            Modifier.padding(top = 10.dp), fontSize = 12.sp, color = Faint)
    }
}

// --- settings --------------------------------------------------------------------------------------------------------

@Composable
private fun Choice(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 16.sp, color = Color.White)
        Row(Modifier.background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50)).padding(3.dp)) {
            options.forEachIndexed { i, o ->
                val on = i == selected
                Text(o, Modifier.background(if (on) Color.White else Color.Transparent, RoundedCornerShape(50))
                    .tap(RoundedCornerShape(50), light = on, label = "$label: $o") { onPick(i) }
                    .padding(horizontal = if (options.size > 3) 9.dp else 12.dp, vertical = 7.dp),
                    fontSize = 14.sp, color = if (on) Color(0xFF10233F) else Color.White, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().tap(label = title) { onChange(!checked) }.padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = Color.White)
            if (sub != null) Text(sub, fontSize = 12.sp, color = Dim, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(onUpdate: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val repo = app.repo
    val u by repo.units.collectAsState()
    val alpha by repo.widgetAlpha.collectAsState()
    val checks by repo.checkUpdates.collectAsState()
    val places by repo.places.collectAsState()
    val forecasts by repo.forecasts.collectAsState()
    val source by repo.source.collectAsState()
    val model by repo.model.collectAsState()
    val cards by repo.cards.collectAsState()
    val skyMotion by repo.skyMotion.collectAsState()
    val nNow by repo.notifyNow.collectAsState()
    val nAlerts by repo.notifyAlerts.collectAsState()
    val nRain by repo.notifyRain.collectAsState()
    val nDaily by repo.notifyDaily.collectAsState()
    val dailyAt by repo.dailyAt.collectAsState()
    val scope = rememberCoroutineScope()
    var updateMsg by remember { mutableStateOf<String?>(null) }
    fun refreshAll() = app.scope.launch { repo.places.value.forEach { repo.refresh(it, 0) } }

    // A notification setting turns on only once Android allows notifications (asked the first time, Android 13+)
    var pendingKey by remember { mutableStateOf<String?>(null) }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingKey?.let { if (granted) repo.setNotify(it, true) }
        pendingKey = null
    }
    fun setNotify(key: String, on: Boolean) {
        if (on && Build.VERSION.SDK_INT >= 33 && !Notify.allowed(ctx)) { pendingKey = key; askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) }
        else repo.setNotify(key, on)
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        .navigationBarsPadding().padding(bottom = 16.dp)) {
        SheetTitle("Settings")
        Section("Forecast")
        Choice("Source", listOf("NWS", "Open-Meteo"), if (source == "nws") 0 else 1) { i ->
            repo.setSource(if (i == 0) "nws" else "om")
            refreshAll()
        }
        Text(if (source == "nws") "National Weather Service for US places, with its alerts; Open-Meteo adds UV, sun times, air quality and days 8–14, and covers places outside the US."
            else "Open-Meteo's models everywhere; no alerts.", fontSize = 12.sp, color = Dim, lineHeight = 16.sp)
        Text("Open-Meteo model", Modifier.padding(top = 12.dp, bottom = 6.dp), fontSize = 16.sp, color = Color.White)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OpenMeteo.models.forEach { (id, name) ->
                val on = id == model
                Text(name, Modifier.background(if (on) Color.White else Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                    .tap(RoundedCornerShape(50), light = on, label = "Model: $name") { if (!on) { repo.setModel(id); refreshAll() } }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                    fontSize = 14.sp, color = if (on) Color(0xFF10233F) else Color.White, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        Text("Best match picks the best model for each place. Some models forecast fewer days or give no chance of rain.",
            Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Dim, lineHeight = 16.sp)

        Section("Units")
        Choice("Temperature", listOf("°F", "°C"), if (u.fahrenheit) 0 else 1) { repo.setUnits(u.copy(fahrenheit = it == 0)) }
        val winds = listOf("mph", "kmh", "ms", "kn", "bft")
        Choice("Wind", listOf("mph", "km/h", "m/s", "kn", "Bft"), winds.indexOf(u.wind).coerceAtLeast(0)) { repo.setUnits(u.copy(wind = winds[it])) }
        Choice("Rain and snow", listOf("in", "mm"), if (u.inches) 0 else 1) { repo.setUnits(u.copy(inches = it == 0)) }
        val pressures = listOf("inhg", "hpa", "mmhg")
        Choice("Pressure", listOf("inHg", "hPa", "mmHg"), pressures.indexOf(u.pressureUnit).coerceAtLeast(0)) {
            repo.setUnits(u.copy(pressureUnit = pressures[it]))
        }
        Choice("Clock", listOf("12 h", "24 h"), if (u.clock24) 1 else 0) { repo.setUnits(u.copy(clock24 = it == 1)) }

        Section("Cards")
        cards.forEachIndexed { i, (card, on) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(card.title, Modifier.weight(1f), fontSize = 16.sp, color = if (on) Color.White else Dim)
                IconBtn(R.drawable.ic_up, "Move ${card.title} up", 40.dp, enabled = i > 0) {
                    repo.setCards(cards.toMutableList().apply { add(i - 1, removeAt(i)) })
                }
                Spacer(Modifier.width(6.dp))
                Switch(on, { v -> repo.setCards(cards.mapIndexed { j, c -> if (j == i) c.first to v else c }) })
            }
        }
        Text("The current weather and alerts stay on top. Rain soon shows only when rain or snow is near.",
            Modifier.padding(top = 4.dp), fontSize = 12.sp, color = Dim, lineHeight = 16.sp)

        Section("Notifications")
        val first = places.firstOrNull()?.name ?: "the first place"
        SwitchRow("Temperature in the status bar", "For $first, kept up to date", nNow) { setNotify("notify_now", it) }
        SwitchRow("Weather alerts", "Watches and warnings from the National Weather Service (US)", nAlerts) { setNotify("notify_alerts", it) }
        SwitchRow("Rain or snow starting soon", "Up to 45 minutes ahead, at most every 3 hours", nRain) { setNotify("notify_rain", it) }
        SwitchRow("Today's forecast", "Each morning after the time below", nDaily) { setNotify("notify_daily", it) }
        if (nDaily) {
            val times = listOf(6, 7, 8, 9)
            Choice("Time", times.map { if (u.clock24) "%02d:00".format(it) else "$it AM" }, times.indexOf(dailyAt / 60).coerceAtLeast(0)) {
                repo.setDailyAt(times[it] * 60)
            }
        }
        Text("Notifications follow the first place and are checked about every 30 minutes, as Android allows, to save battery.",
            Modifier.padding(top = 4.dp), fontSize = 12.sp, color = Dim, lineHeight = 16.sp)

        Section("Appearance")
        SwitchRow("Moving sky", "Rain, snow, stars and clouds drift for 30 seconds after a touch; always still in battery saver",
            skyMotion) { repo.setSkyMotion(it) }

        Section("Home-screen widgets")
        val place = places.firstOrNull()
        val fc = place?.let { forecasts[it.id] }
        // The widget's look, drawn by the same code as the widget
        Column(Modifier.fillMaxWidth().background(Color(0xFF12171F).copy(alpha = 0.35f + alpha * 0.65f), RoundedCornerShape(20.dp)).padding(vertical = 12.dp)) {
            val now = LocalDateTime.now()
            val is24 = DateFormat.is24HourFormat(ctx)
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(now.format(DateTimeFormatter.ofPattern(if (is24) "H:mm" else "h:mm")), fontSize = 44.sp, fontWeight = FontWeight.Light, color = Color.White)
                Column(Modifier.padding(start = 8.dp)) {
                    if (!is24) Text(now.format(DateTimeFormatter.ofPattern("a")), fontSize = 12.sp, color = Color.White)
                    Text(now.format(DateTimeFormatter.ofPattern("EEE, MMM d")), fontSize = 16.sp, color = Color.White)
                }
            }
            Text(place?.let { if (it.gps && it.detail.isNotBlank()) it.detail else it.name } ?: "No place yet", Modifier.padding(horizontal = 12.dp),
                fontSize = 14.sp, color = Color.White)
            Text("Temperature ${u.tName}, Humidity %", Modifier.padding(horizontal = 12.dp), fontSize = 11.sp, color = Dim)
            if (fc != null) BoxWithConstraints(Modifier.fillMaxWidth()) {
                val d = LocalDensity.current
                val wPx = with(d) { maxWidth.roundToPx() }
                val hPx = (wPx * 0.95f).toInt()
                val bmp = remember(fc, u, wPx) { WidgetArt.draw(ctx, fc, u, wPx, hPx) }
                Image(bmp.asImageBitmap(), "Widget preview", Modifier.fillMaxWidth().height(with(d) { hPx.toDp() }))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Background", fontSize = 16.sp, color = Color.White)
            Slider(alpha, { repo.setWidgetAlpha(it) }, Modifier.weight(1f).padding(horizontal = 12.dp),
                onValueChangeFinished = { dev.skyform.weather.widget.Widget.updateAll(ctx) })
            Text("${(alpha * 100).toInt()}%", Modifier.width(44.dp), fontSize = 14.sp, color = Dim)
        }
        val mgr = AppWidgetManager.getInstance(ctx)
        if (mgr.isRequestPinAppWidgetSupported) {
            listOf("Add the clock and forecast" to WeatherWidget::class.java, "Add the weather now (small)" to NowWidget::class.java,
                "Add the week" to WeekWidget::class.java).forEach { (label, cls) ->
                Pill(label, Modifier.fillMaxWidth().padding(top = 6.dp)) { mgr.requestPinAppWidget(ComponentName(ctx, cls), null, null) }
            }
        }
        Text("Widgets show the first place and refresh about every 30 minutes; the button on a widget refreshes it at once.",
            Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Dim, lineHeight = 16.sp)

        Section("Updates")
        SwitchRow("Check for updates", "Asks GitHub for a newer build when the app opens", checks) { repo.setCheckUpdates(it) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Version ${BuildConfig.VERSION_NAME}" + if (BuildConfig.BUILD_NUMBER > 0) " · build ${BuildConfig.BUILD_NUMBER}" else " · local build",
                Modifier.weight(1f), fontSize = 14.sp, color = Dim)
            Pill("Check now") {
                if (BuildConfig.BUILD_NUMBER <= 0) { updateMsg = "This is a local build: it does not ask GitHub for updates."; return@Pill }
                updateMsg = "Checking…"
                scope.launch {
                    app.updater.checkNow()
                    if (app.updater.state.value is dev.skyform.weather.update.UpdateState.Available) { updateMsg = null; onUpdate() }
                    else updateMsg = "Skyform is up to date."
                }
            }
        }
        updateMsg?.let { Text(it, Modifier.padding(top = 6.dp), fontSize = 13.sp, color = Color.White) }

        Section("About")
        Text("Forecasts and alerts by the National Weather Service (weather.gov); more forecasts, air quality and place search by " +
            "Open-Meteo.com (CC BY 4.0); radar by RainViewer; map © OpenStreetMap contributors. Moon and twilight times are " +
            "computed on the phone. Street names come from your phone's geocoder. A place's coordinates are sent only to these " +
            "services, to get its weather, and the radar map asks for the area around it.", fontSize = 13.sp, color = Dim, lineHeight = 18.sp)
    }
}
