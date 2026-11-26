package dev.skyform.weather.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.skyform.weather.Card
import dev.skyform.weather.Forecast
import dev.skyform.weather.Locator
import dev.skyform.weather.Place
import dev.skyform.weather.R
import dev.skyform.weather.Sky
import dev.skyform.weather.Units
import dev.skyform.weather.Wmo
import dev.skyform.weather.app
import dev.skyform.weather.update.UpdateUi
import dev.skyform.weather.widget.Widget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class SheetKind { Places, Settings, Update }

/** A named section in system traces (scripts\trace-top.py); costs nothing while nobody traces. */
inline fun <T> traced(name: String, block: () -> T): T {
    android.os.Trace.beginSection(name)
    try { return block() } finally { android.os.Trace.endSection() }
}

/** Its own recomposition scope: state read in [content] recomposes only it (a plain inline block would recompose its caller). */
@Composable
private fun Scoped(content: @Composable () -> Unit) = content()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkyformApp() {
    val ctx = LocalContext.current
    val app = ctx.app
    val repo = app.repo
    val places by repo.places.collectAsState()
    val units by repo.units.collectAsState()
    val forecasts by repo.forecasts.collectAsState()
    val busy by repo.busy.collectAsState()
    val errors by repo.errors.collectAsState()
    val cards by repo.cards.collectAsState()
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<SheetKind?>(null) }
    var day by remember { mutableStateOf<Pair<String, Long>?>(null) }   // a place's id and a day's start: the day sheet
    var locating by remember { mutableStateOf(false) }
    val pager = rememberPagerState { places.size }
    // The sky moves only for a while after a touch, never in battery saver or when turned off in Settings
    val motion = remember { Motion() }
    val power = remember { ctx.getSystemService(PowerManager::class.java) }
    fun motionAllowed() = repo.skyMotion.value && power?.isPowerSaveMode != true
    // When anything last scrolled (no state: the sky reads it each frame); the sky holds still meanwhile
    val lastScroll = remember { longArrayOf(0L) }
    val scrollWatch = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                lastScroll[0] = System.nanoTime()
                motion.poke()
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(Unit) { repo.skyMotion.collect { motion.enabled = motionAllowed() } }

    fun locate() = scope.launch {
        locating = true
        try { repo.locate() } finally { locating = false }
        pager.scrollToPage(0)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) locate()
    }
    fun askLocation() {
        if (Locator.allowed(ctx)) locate()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        motion.enabled = motionAllowed()
        motion.poke()
        scope.launch {
            val list = repo.places.value
            if (list.any { it.gps } && Locator.allowed(ctx)) repo.locate()
            list.getOrNull(pager.currentPage)?.takeIf { !it.gps }?.let { repo.refresh(it) }
        }
        if (repo.checkUpdates.value) app.updater.check()
    }

    // Read inside the small composables below, not here: a swipe that changes the page then recomposes only the sky
    // and the top bar, never the pages.
    val forecastOf: (String) -> Forecast? = { id -> forecasts[id] ?: repo.forecast(id) }
    val placeInView = { places.getOrNull(pager.currentPage) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF9CCBFF), surface = SheetBg, background = SheetBg)) {
        // Refresh the place in view once a swipe has settled (nothing extra runs while the pages move); scoped so the
        // settled page is not read by this whole screen
        Scoped {
            LaunchedEffect(pager.settledPage, places.size) {
                places.getOrNull(pager.settledPage)?.let { repo.refresh(it) }
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0xFF0E1A2E)).pointerInput(Unit) {
            // every touch wakes the sky (observed on its way down, never consumed)
            awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); motion.poke() } }
        }) {
            Scoped {
                val fc = placeInView()?.let { forecastOf(it.id) }
                SkyBackground(fc?.let { Wmo.sky(it.current.code) } ?: Sky.CLEAR, fc?.current?.isDay ?: true, motion,
                    still = { pager.isScrollInProgress || System.nanoTime() - lastScroll[0] < 300_000_000L })
            }
            if (places.isEmpty()) {
                Welcome(locating, ::askLocation) { sheet = SheetKind.Places }
            } else {
                Column(Modifier.fillMaxSize().nestedScroll(scrollWatch)) {
                    Scoped {
                        val place = placeInView()
                        val fc = place?.let { forecastOf(it.id) }
                        TopBar(place, fc, pager.currentPage, places.size,
                            place != null && (place.id in busy || (place.gps && locating)),
                            onPlaces = { sheet = SheetKind.Places }, onSettings = { sheet = SheetKind.Settings },
                            onShare = { if (place != null && fc != null) share(ctx, place, fc, units) })
                    }
                    UpdateUi.Banner { sheet = SheetKind.Update }
                    // Every place's page stays built (a page built mid-swipe costs several frames)
                    HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = (places.size - 1).coerceIn(1, 9),
                        key = { places[it].id }) { page ->
                        traced("SF page $page") {
                            val p = places[page]
                            Page(p, forecastOf(p.id), units, cards, errors[p.id], onDay = { day = p.id to it }) {
                                // A pull always fetches, at once, for the spot we have. "My location" then checks in the
                                // background whether the phone has moved (a new spot fetches again): waiting for a fix first
                                // held the spinner up to 15 s, and then kept any forecast under 15 minutes old.
                                repo.refresh(p, 0)
                                if (p.gps) app.scope.launch { repo.locate() }
                            }
                        }
                    }
                }
            }
        }

        if (sheet != null) {
            val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet({ sheet = null }, sheetState = state, containerColor = SheetBg, contentColor = Color.White) {
                when (sheet) {
                    SheetKind.Places -> PlacesSheet(
                        onPick = { i -> scope.launch { snapshotFlow { pager.pageCount }.first { it > i }; pager.scrollToPage(i) }; sheet = null },
                        onLocate = { askLocation(); sheet = null })
                    SheetKind.Settings -> SettingsSheet { sheet = SheetKind.Update }
                    SheetKind.Update -> UpdateUi.Sheet()
                    null -> Unit
                }
            }
        }

        day?.let { (id, start) ->
            val p = places.firstOrNull { it.id == id }
            val fc = p?.let { forecastOf(it.id) }
            if (p != null && fc != null) {
                val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet({ day = null }, sheetState = state, containerColor = SheetBg, contentColor = Color.White) {
                    DaySheet(fc, units, p.lat, p.lon, start) { day = id to it }
                }
            }
        }
    }
}

@Composable
private fun TopBar(place: Place?, fc: Forecast?, page: Int, count: Int, updating: Boolean, onPlaces: () -> Unit, onSettings: () -> Unit,
                   onShare: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(R.drawable.ic_list, "Places", onClick = onPlaces)
        Column(Modifier.weight(1f).tap(RoundedCornerShape(14.dp), label = "Places", onClick = onPlaces).padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (place?.gps == true) Icon(painterResource(R.drawable.ic_place), null, Modifier.size(16.dp), tint = Color.White)
                Text(place?.name ?: "", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            val sub = listOfNotNull(place?.detail?.takeIf { it.isNotBlank() && !place.gps },
                if (updating) "Updating…" else Widget.updated(LocalContext.current, fc).ifEmpty { null }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (count > 1) Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(count) { i ->
                    Box(Modifier.size(if (i == page) 7.dp else 5.dp).background(Color.White.copy(alpha = if (i == page) 1f else 0.4f), CircleShape))
                }
            }
        }
        IconBtn(R.drawable.ic_share, "Share the weather", enabled = fc != null, onClick = onShare)
        IconBtn(R.drawable.ic_settings, "Settings", onClick = onSettings)
    }
}

/** The weather in a few lines, through Android's share sheet. */
private fun share(ctx: Context, p: Place, fc: Forecast, u: Units) {
    val c = fc.current
    val d = fc.daysFrom(nowSec()).firstOrNull()
    val text = buildString {
        append("${p.name}: ${u.t(c.temp)}, ${c.text.ifEmpty { Wmo.text(c.code, c.isDay) }}")
        d?.let { append(". Today ${u.t(it.max)} / ${u.t(it.min)}"); if (it.pop >= 10) append(", ${it.pop}% chance of rain") }
        outlook(fc, u)?.let { append(". $it") }
        append(".")
    }
    runCatching {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share the weather"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(p: Place, fc: Forecast?, u: Units, cards: List<Pair<Card, Boolean>>, error: String?, onDay: (Long) -> Unit,
                 onRefresh: suspend () -> Unit) {
    val ctx = LocalContext.current
    // The spinner is for a pull only; refreshes the app starts itself show "Updating…" in the top bar (a spinner on
    // every swipe would also redraw the page each frame)
    val scope = rememberCoroutineScope()
    var pulled by remember { mutableStateOf(false) }
    val pull: () -> Unit = { if (!pulled) scope.launch { pulled = true; try { onRefresh() } finally { pulled = false } } }
    // Pull distance that refreshes: the finger travels twice it, plus the touch slop. Material's 80 dp meant a ~168 dp drag
    // (too stiff); 32 dp is ~72 dp. The spinner still travels 40 dp, so it is fully in view when the pull fires.
    val pullState = rememberPullToRefreshState()
    // its own render node: sliding the page moves the node instead of re-recording everything on it each frame
    PullToRefreshBox(pulled, pull, Modifier.fillMaxSize().graphicsLayer(), state = pullState, threshold = 32.dp,
        indicator = { PullToRefreshDefaults.Indicator(pullState, pulled, Modifier.align(Alignment.TopCenter), maxDistance = 40.dp) }) {
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // A plain scrolling column, not a lazy list: the cards are built once (a lazy list rebuilt them each time a
        // page slid back into view: about 11 ms in one frame)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = bottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (fc == null) {
                Column(Modifier.fillMaxWidth().padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (error == null) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Text("Fetching the forecast…", color = Dim)
                    } else {
                        Text(error, color = Color.White, fontSize = 17.sp)
                        Spacer(Modifier.height(16.dp))
                        Pill("Try again", primary = true, onClick = pull)
                    }
                }
                return@Column
            }
            if (error != null) {
                Text("$error · showing the last forecast", Modifier.fillMaxWidth().background(Color(0x55B3261E), RoundedCornerShape(12.dp))
                    .padding(10.dp), color = Color.White, fontSize = 13.sp)
            }
            if (fc.alerts.isNotEmpty()) Alerts(fc, u)
            traced("SF hero") { Hero(fc, u) }
            for ((card, on) in cards) if (on) key(card) {
                when (card) {
                    Card.RAIN -> traced("SF rain") { RainSoonCard(fc, u) }
                    Card.HOURLY -> traced("SF hourly") { HourlyCard(fc, u) }
                    Card.DAILY -> traced("SF daily") { DailyCard(fc, u, onDay) }
                    Card.RADAR -> traced("SF radar") { RadarCard(p) }
                    Card.AIR -> traced("SF air") { AirCard(fc, u) }
                    Card.DETAILS -> traced("SF details") { Details(fc, u) }
                    Card.SUN -> traced("SF sun") { SunMoonCard(fc, u, p.lat, p.lon) }
                }
            }
            run {
                val nws = fc.source == "nws"
                Text((if (nws) "Forecast and alerts by the National Weather Service (weather.gov)\nUV, sun times, air quality and days 8–14 by Open-Meteo.com"
                    else "Weather and air quality by Open-Meteo.com") + "\nRadar by RainViewer · map © OpenStreetMap contributors",
                    Modifier.fillMaxWidth().tap(RoundedCornerShape(10.dp), label = "Open the weather service's site") {
                        val url = if (nws) "https://www.weather.gov/" else "https://open-meteo.com/"
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }.padding(10.dp), color = Faint, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun Welcome(locating: Boolean, onLocate: () -> Unit, onSearch: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.wx_partly_day), null, Modifier.size(150.dp))
        Spacer(Modifier.height(12.dp))
        Text("Skyform", fontSize = 44.sp, fontWeight = FontWeight.Light, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text("Hour-by-hour charts, two weeks ahead, radar, air quality, alerts and home-screen widgets.", fontSize = 16.sp,
            color = Dim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(36.dp))
        if (locating) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text("Finding you…", color = Dim)
        } else {
            Pill("Use my location", Modifier.width(260.dp), primary = true, onClick = onLocate)
            Spacer(Modifier.height(12.dp))
            Pill("Search for a city", Modifier.width(260.dp), onClick = onSearch)
        }
    }
}
