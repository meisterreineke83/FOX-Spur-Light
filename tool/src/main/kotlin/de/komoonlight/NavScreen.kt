package de.komoonlight

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightFileShare
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.LightVibrator
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import de.komoonlight.geo.Geo
import de.komoonlight.geo.Gpx
import de.komoonlight.geo.Pt
import de.komoonlight.komoot.KomootClient
import de.komoonlight.location.GpsEvent
import de.komoonlight.location.gpsLoggerLocations
import de.komoonlight.location.lightOsLocations
import de.komoonlight.nav.NavEvent
import de.komoonlight.nav.NavStatus
import de.komoonlight.nav.AutoPause
import de.komoonlight.nav.Navigator
import de.komoonlight.nav.Turn
import de.komoonlight.nav.VoiceAnnouncer
import de.komoonlight.nav.VoicePrompts
import de.komoonlight.geo.fitRoute
import de.komoonlight.geo.tilesAlong
import de.komoonlight.map.MAP_ATTRIBUTION
import de.komoonlight.map.TileStore
import de.komoonlight.ui.RouteView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/** Eine Komoot-Tour, der gefolgt wird. */
data class TourRef(
    val client: KomootClient,
    val session: KomootClient.Session,
    val tour: KomootClient.TourInfo,
)

/** [ref] = null → freie Fahrt: keine Route, Aufzeichnung startet sofort mit dem ersten GPS-Fix. */
class NavViewModel(
    private val ref: TourRef?,
    /** Bei geplanten Touren aus Komoots Sportart, sonst die gewählte. */
    val activity: Activity,
    private val fileShare: LightFileShare,
    private val vibrator: LightVibrator,
    private val voice: VoiceAnnouncer,
    private val store: Store,
    val tiles: TileStore,
) : LightViewModel<Unit>() {

    /** Was der Screen gerade tut – bestimmt Kopfzeile und Knöpfe. */
    val mode = MutableStateFlow(
        when {
            ref == null -> Mode.FREE_RIDE
            ref.tour.planned -> Mode.PREVIEW
            else -> Mode.VIEW
        }
    )
    /** Bildmitte in Vorschau/Ansicht (ganze Route im Bild) bzw. nach Verschieben der Karte. */
    val viewCenter = MutableStateFlow<Pt?>(null)
    /** Mit GPS: Karte folgt der Position, bis man sie verschiebt. */
    val following = MutableStateFlow(true)
    /** Karte wurde von Hand verschoben → Knopf zum Zurückholen zeigen. */
    val panned = MutableStateFlow(false)
    private var turns: List<Turn> = emptyList()

    val route = MutableStateFlow<List<Pt>>(emptyList())
    val position = MutableStateFlow<Pt?>(null)
    /** Warum noch keine (genaue) Position da ist – null, solange alles passt. */
    val gpsInfo = MutableStateFlow<String?>(null)
    val status = MutableStateFlow<NavStatus?>(null)
    val message = MutableStateFlow<String?>(if (ref != null) "Lade Route…" else null)
    private val title = ref?.tour?.name ?: activity.tourName
    val zoomM = MutableStateFlow(300.0)

    val recording = MutableStateFlow(false)
    val track = MutableStateFlow<List<Pt>>(emptyList())
    val trackDistanceM = MutableStateFlow(0.0)
    /** Steht man 5 s still, pausiert die Aufzeichnung von selbst. */
    private val autoPause = AutoPause(radiusM = activity.pauseRadiusM)
    val autoPaused = MutableStateFlow(false)
    val movingMs = MutableStateFlow(0L)
    val voiceOn = MutableStateFlow(true)
    val mapOn = MutableStateFlow(true)
    /** Fahrtrichtung: aus der Navigation, sonst aus der eigenen Bewegung. */
    val heading = MutableStateFlow<Double?>(null)
    private var lastMoved: Pt? = null

    private var navigator: Navigator? = null
    private var gpsJob: Job? = null
    private var announcedStart = false

    init {
        viewModelScope.launch {
            voiceOn.value = store.voiceEnabled()
            voice.enabled = voiceOn.value
            mapOn.value = store.mapEnabled()
        }
        if (ref != null) viewModelScope.launch {
            try {
                val r = ref.client.route(ref.session, ref.tour.id)
                route.value = r.points
                turns = r.turns
                fitRoute(r.points)?.let { (c, radius) -> viewCenter.value = c; zoomM.value = radius }
                message.value = null
                if (ref.tour.planned) {
                    // Kartenkacheln entlang der Route vorab laden → Karte auch ohne Netz
                    // OSM-Regeln: vorab weniger als 250 Kacheln ab Zoom 13
                    if (mapOn.value) tiles.prefetch(tilesAlong(r.points, listOf(14, 15), limit = 200))
                    if (Navigator(r.points, r.turns).turns.isEmpty()) message.value = "Ohne Abbiegehinweise"
                }
            } catch (e: KomootClient.AuthException) {
                message.value = "Bitte neu anmelden"
            } catch (e: Exception) {
                message.value = "Route nicht geladen"
            }
        }
    }

    /** Startet GPS, sobald die Berechtigung da ist – nur bei freier Fahrt und Navigation. */
    fun startGps() {
        if (gpsJob != null || !mode.value.usesGps) return
        following.value = true; panned.value = false
        if (mode.value == Mode.FREE_RIDE && track.value.isEmpty()) { resetRecording(); recording.value = true }
        gpsJob = viewModelScope.launch {
            try {
                // Zwei Quellen: LightOS selbst und – solange LightOS Spur blockiert – die App „GPS Logger“.
                // LightOS hat Vorrang; Warte-Hinweise nur, solange von keiner Quelle Punkte kommen.
                var lastLightOsFix = 0L
                var lastAnyFix = 0L
                merge(
                    lightOsLocations().map { true to it },
                    gpsLoggerLocations().map { false to it },
                ).collect { (fromLightOs, e) ->
                    val now = System.currentTimeMillis()
                    when (e) {
                        is GpsEvent.Fix -> {
                            if (fromLightOs) lastLightOsFix = now
                            else if (now - lastLightOsFix < 10_000) return@collect
                            lastAnyFix = now
                            onPosition(e.pt, e.accuracyM)
                        }
                        is GpsEvent.Waiting -> if (now - lastAnyFix > 10_000) {
                            gpsInfo.value = if (fromLightOs && "untrusted" in e.reason)
                                "LightOS blockiert GPS – GPS Logger starten (siehe Anleitung)"
                            else e.reason
                        }
                    }
                }
            } finally {
                if (gpsJob === coroutineContext[Job]) gpsJob = null // damit ein erneuter Start (z. B. nach Rückkehr in die App) wieder klappt
            }
        }
    }

    /** Keine GPS-Berechtigung – im Kopf anzeigen statt endlos zu warten. */
    fun gpsDenied() { gpsInfo.value = "Keine Standort-Berechtigung für Spur" }

    private fun stopGps() {
        gpsJob?.cancel(); gpsJob = null
    }

    /** Geplante Tour: Navigation + Aufzeichnung starten. */
    fun startRoute() {
        if (route.value.size < 2) return
        navigator = Navigator(route.value, turns) // frisch, damit Ansagen wieder kommen
        announcedStart = false
        status.value = null
        resetRecording()
        recording.value = true
        zoomM.value = 300.0
        following.value = true; panned.value = false
        message.value = null
        mode.value = Mode.NAVIGATE // GPS startet über den Screen (Berechtigung)
    }

    /** Navigation beenden: Fahrt speichern (→ Upload zu Komoot), zurück zur Vorschau. */
    fun finishRoute() {
        stopGps()
        val saved = if (recording.value) saveTrack() else null
        recording.value = false
        navigator = null
        status.value = null
        position.value = null
        fitRoute(route.value)?.let { (c, radius) -> viewCenter.value = c; zoomM.value = radius }
        panned.value = false
        message.value = saved?.let { "Fahrt gespeichert – wird zu Komoot hochgeladen" }
        mode.value = Mode.PREVIEW
    }

    private fun onPosition(p: Pt, accuracyM: Double) {
        position.value = p
        // Grobe Fixes zeigen die Position schon an, aufgezeichnet/navigiert wird erst ab ±30 m
        if (accuracyM > GOOD_ACCURACY_M) {
            gpsInfo.value = "Ungenau (±${accuracyM.toInt()} m) – Aufzeichnung startet bei ±${GOOD_ACCURACY_M.toInt()} m"
            return
        }
        gpsInfo.value = null
        val prev = lastMoved
        if (prev == null) lastMoved = p
        else if (Geo.dist(prev, p) > 8) { heading.value = Geo.bearing(prev, p); lastMoved = p }
        if (recording.value) {
            val moving = autoPause.update(p)
            autoPaused.value = autoPause.paused
            movingMs.value = autoPause.movingMs
            if (moving) {
                track.value.lastOrNull()?.let { trackDistanceM.value += Geo.dist(it, p) }
                track.value = track.value + p
            }
        }
        val nav = navigator?.takeIf { mode.value == Mode.NAVIGATE } ?: return
        if (!announcedStart) { announcedStart = true; voice.play(VoicePrompts.START) }
        val (s, events) = nav.update(p)
        status.value = s
        heading.value = s.headingDeg
        events.forEach(::react)
    }

    /** Sprachansage + kurze Vibration (falls der Ton aus ist oder nicht gehört wird). */
    private fun react(e: NavEvent) {
        voice.announce(e)
        when (e) {
            is NavEvent.Upcoming -> vibrator.vibrate(200.milliseconds)
            is NavEvent.Now -> viewModelScope.launch {
                vibrator.vibrate(150.milliseconds); delay(300); vibrator.vibrate(150.milliseconds)
            }
            NavEvent.OffRoute -> vibrator.vibrate(700.milliseconds)
            else -> Unit
        }
    }

    fun toggleVoice() {
        voiceOn.value = !voiceOn.value
        voice.enabled = voiceOn.value
        viewModelScope.launch { store.setVoiceEnabled(voiceOn.value) }
    }

    fun toggleMap() {
        mapOn.value = !mapOn.value
        viewModelScope.launch { store.setMapEnabled(mapOn.value) }
    }

    /** Karte mit dem Finger verschoben. */
    fun pan(center: Pt) {
        viewCenter.value = center
        following.value = false
        panned.value = true
    }

    /** Zurück: mit GPS zur eigenen Position (folgen), sonst ganze Route zeigen. */
    fun recenter() {
        panned.value = false
        if (mode.value.usesGps && position.value != null) following.value = true
        else fitRoute(route.value)?.let { (c, radius) -> viewCenter.value = c; zoomM.value = radius }
            ?: run { following.value = true }
    }

    fun zoom(dir: Int) { zoomM.value = (zoomM.value * if (dir > 0) 0.5 else 2.0).coerceIn(75.0, 50_000.0) }

    fun toggleRecording() {
        if (recording.value) {
            recording.value = false
            saveTrack()?.let { message.value = "Gespeichert: $it" }
        } else {
            resetRecording()
            recording.value = true
            message.value = null
        }
    }

    private fun resetRecording() {
        track.value = emptyList(); trackDistanceM.value = 0.0
        autoPause.reset(); autoPaused.value = false; movingMs.value = 0
    }

    /** Schreibt den Track in den geteilten Ordner – im Tool Manager unter „Aufzeichnungen“. */
    private fun saveTrack(): String? {
        val t = track.value
        if (t.size < 2) return null
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
        val file = "${stamp}_${activity.key}.gpx" // Kennung → richtige Sportart beim Upload
        fileShare.write("$TRACKS_DIR/$file") {
            Gpx.write(it, "$title ($stamp)", t, type = if (activity == Activity.HIKE) "hiking" else "cycling")
        }
        return file
    }

    override fun onCleared() {
        if (recording.value) saveTrack()
        voice.release()
        tiles.close()
    }
}

class NavScreen(
    private val sealedActivity: SealedLightActivity,
    private val ref: TourRef?,
    /** Für Aufzeichnungen ohne Route; bei Touren gilt deren Sportart. */
    private val freeActivity: Activity = Activity.BIKE,
) : LightScreen<Unit, NavViewModel>(sealedActivity) {

    override val viewModelClass: Class<NavViewModel> get() = NavViewModel::class.java
    override fun createViewModel() =
        NavViewModel(
            ref, ref?.let { Activity.fromKomootSport(it.tour.sport) } ?: freeActivity,
            lightContext.fileShare, sealedActivity.vibrator,
            VoiceAnnouncer(DefaultLightAudio(sealedActivity)), Store(lightContext.dataStore),
            TileStore(lightContext.filesDir, "FOX-Spur-Light (Light Phone III; github.com/meisterreineke83/FOX-Spur-Light)"),
        )

    private var resumeCount by mutableIntStateOf(0)
    override fun willShow() { resumeCount++ }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val route by viewModel.route.collectAsState()
        val track by viewModel.track.collectAsState()
        val pos by viewModel.position.collectAsState()
        val gpsInfo by viewModel.gpsInfo.collectAsState()
        val status by viewModel.status.collectAsState()
        val message by viewModel.message.collectAsState()
        val zoom by viewModel.zoomM.collectAsState()
        val rec by viewModel.recording.collectAsState()
        val recDist by viewModel.trackDistanceM.collectAsState()
        val voiceOn by viewModel.voiceOn.collectAsState()
        val mapOn by viewModel.mapOn.collectAsState()
        val heading by viewModel.heading.collectAsState()
        val mode by viewModel.mode.collectAsState()
        val autoPaused by viewModel.autoPaused.collectAsState()
        val movingMs by viewModel.movingMs.collectAsState()
        val viewCenter by viewModel.viewCenter.collectAsState()
        val following by viewModel.following.collectAsState()
        val panned by viewModel.panned.collectAsState()
        val mapStatus by viewModel.tiles.status.collectAsState()

        // GPS-Berechtigung über LightOS prüfen/anfragen, dann starten (nur freie Fahrt / Navigation)
        val permission = rememberPermissionRequestLauncher(Manifest.permission.ACCESS_FINE_LOCATION)
        LaunchedEffect(resumeCount, mode) {
            if (!mode.usesGps) return@LaunchedEffect
            val granted = checkPermission(Manifest.permission.ACCESS_FINE_LOCATION).asKotlinResult
                .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
                .getOrDefault(false)
            if (granted) viewModel.startGps() else { viewModel.gpsDenied(); permission?.launch() }
        }

        LightTheme(colors = themeColors) {
            val colors = LightThemeTokens.colors
            Column(Modifier.fillMaxSize().background(colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    // Langer Tourname kollidiert sonst mit „Ton an“ → steht unten im Kopf
                    center = if (ref == null) LightTopBarCenter.Text(viewModel.activity.tourName) else null,
                    // Ton nur, wo es Ansagen gibt
                    rightButton = if (mode == Mode.PREVIEW || mode == Mode.NAVIGATE)
                        LightBarButton.Text(if (voiceOn) "Ton an" else "Stumm", onClick = { viewModel.toggleVoice() })
                    else null,
                )

                when (mode) {
                    Mode.FREE_RIDE -> FreeRideHeader(pos, gpsInfo, message, rec, recDist, movingMs, autoPaused)
                    Mode.NAVIGATE -> Header(status, pos, gpsInfo, message, rec, recDist, autoPaused)
                    Mode.PREVIEW, Mode.VIEW -> TourHeader(message)
                }

                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    RouteView(
                        route, track, pos,
                        center = if (mode.usesGps && following) null else viewCenter,
                        headingDeg = heading, radiusM = zoom,
                        content = colors.content, background = colors.background,
                        tiles = if (mapOn) viewModel.tiles else null,
                        dark = colors.background.luminance() < 0.5f,
                        onPan = { viewModel.pan(it) },
                    )
                    // Zoom als Knöpfe auf der Karte, rechts – passt immer aufs Display
                    Column(
                        Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ZoomButton(plus = true, colors.content, colors.background) { viewModel.zoom(+1) }
                        ZoomButton(plus = false, colors.content, colors.background) { viewModel.zoom(-1) }
                        // Nach dem Verschieben: zurück zur Position bzw. ganzen Route
                        if (panned) CenterButton(colors.content, colors.background) { viewModel.recenter() }
                    }
                    if (mapOn) LightText(
                        mapStatus ?: MAP_ATTRIBUTION, LightTextVariant.Micro, lighten = mapStatus == null,
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp(), vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LightText(
                        if (mapOn) "Karte" else "Karte aus", LightTextVariant.Button, lighten = !mapOn,
                        modifier = Modifier.lightClickable { viewModel.toggleMap() }.padding(8.dp),
                    )
                    when (mode) {
                        Mode.FREE_RIDE -> LightText(
                            if (rec) "Stopp" else "Aufnehmen", LightTextVariant.Button,
                            modifier = Modifier.lightClickable { viewModel.toggleRecording() }.padding(8.dp),
                        )
                        Mode.PREVIEW -> LightText(
                            "Route starten", LightTextVariant.Button,
                            modifier = Modifier.lightClickable(enabled = route.size > 1) { viewModel.startRoute() }.padding(8.dp),
                        )
                        Mode.NAVIGATE -> LightText(
                            "Beenden", LightTextVariant.Button,
                            modifier = Modifier.lightClickable { viewModel.finishRoute() }.padding(8.dp),
                        )
                        Mode.VIEW -> Unit // gefahrene Tour: nur ansehen
                    }
                }
            }
        }
    }

    @Composable
    private fun ZoomButton(plus: Boolean, content: Color, background: Color, onClick: () -> Unit) {
        // + / − als Linien gezeichnet: Schrift-Zeilenhöhe hat die Zeichen verschoben und abgeschnitten
        Canvas(
            Modifier.size(56.dp)
                .background(background)
                .border(2.dp, content)
                .lightClickable(onClick = onClick)
        ) {
            val c = center
            val arm = size.minDimension * 0.28f
            val w = size.minDimension * 0.08f
            drawLine(content, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), strokeWidth = w)
            if (plus) drawLine(content, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), strokeWidth = w)
        }
    }

    @Composable
    private fun CenterButton(content: Color, background: Color, onClick: () -> Unit) {
        // Zielkreuz: Kreis mit Punkt und kurzen Strichen
        Canvas(
            Modifier.size(56.dp)
                .background(background)
                .border(2.dp, content)
                .lightClickable(onClick = onClick)
        ) {
            val c = center
            val r = size.minDimension * 0.22f
            val w = size.minDimension * 0.07f
            drawCircle(content, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w))
            drawCircle(content, w, c)
            val o = size.minDimension * 0.36f
            drawLine(content, Offset(c.x, c.y - o), Offset(c.x, c.y - r), strokeWidth = w)
            drawLine(content, Offset(c.x, c.y + r), Offset(c.x, c.y + o), strokeWidth = w)
            drawLine(content, Offset(c.x - o, c.y), Offset(c.x - r, c.y), strokeWidth = w)
            drawLine(content, Offset(c.x + r, c.y), Offset(c.x + o, c.y), strokeWidth = w)
        }
    }

    /** Vorschau einer geplanten bzw. Ansicht einer gefahrenen Tour. */
    @Composable
    private fun TourHeader(message: String?) {
        val t = ref?.tour ?: return
        Column(Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp(), vertical = 8.dp)) {
            LightText(t.name, LightTextVariant.Subheading, maxLines = 2, overflow = TextOverflow.Ellipsis)
            LightText("%.1f km".format(t.distanceM / 1000), LightTextVariant.Heading)
            LightText(
                listOfNotNull(formatDate(t.date), if (t.planned) "geplant" else "gefahren").joinToString(" · "),
                LightTextVariant.Detail, lighten = true,
            )
            message?.let { LightText(it, LightTextVariant.Fine, lighten = true) }
        }
    }

    @Composable
    private fun FreeRideHeader(pos: Pt?, gpsInfo: String?, message: String?, rec: Boolean, distM: Double, ms: Long, autoPaused: Boolean) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp(), vertical = 8.dp)) {
            when {
                pos == null -> LightText("Warte auf GPS…", LightTextVariant.Heading)
                else -> {
                    LightText("%.2f km".format(distM / 1000), LightTextVariant.Title)
                    val avg = if (ms > 60_000) distM / 1000 / (ms / 3_600_000.0) else null
                    LightText(
                        listOfNotNull(formatDuration(ms), avg?.let { "Ø %.1f km/h".format(it) }).joinToString(" · "),
                        LightTextVariant.Copy,
                    )
                }
            }
            when {
                !rec && pos != null -> LightText("Gestoppt", LightTextVariant.Detail, lighten = true)
                rec && autoPaused -> LightText("Pause – Aufzeichnung läuft weiter, sobald du fährst", LightTextVariant.Detail, lighten = true)
            }
            gpsInfo?.let { LightText(it, LightTextVariant.Detail, lighten = true) }
            message?.let { LightText(it, LightTextVariant.Fine, lighten = true) }
        }
    }

    @Composable
    private fun Header(status: NavStatus?, pos: Pt?, gpsInfo: String?, message: String?, rec: Boolean, recDist: Double, autoPaused: Boolean) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp(), vertical = 8.dp)) {
            val turn = status?.nextTurn
            val d = status?.nextTurnInM
            when {
                pos == null -> LightText(message ?: "Warte auf GPS…", LightTextVariant.Heading)
                status == null -> LightText(message ?: "…", LightTextVariant.Heading)
                status.offRouteM > 50 -> LightText("Abseits: %.0f m".format(status.offRouteM), LightTextVariant.Heading)
                turn != null && d != null && d <= 300 -> Row(verticalAlignment = Alignment.CenterVertically) {
                    LightText(turn.kind.arrow, LightTextVariant.Title, maxLines = 1)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        LightText(if (d < 20) "jetzt" else "${(d / 10).roundToInt() * 10} m", LightTextVariant.Heading)
                        LightText(turn.kind.say, LightTextVariant.Copy)
                        turn.street?.let { LightText(it, LightTextVariant.Detail, lighten = true, maxLines = 1) }
                    }
                }
                else -> LightText("Noch %.1f km".format(status.remainingM / 1000), LightTextVariant.Heading)
            }
            if (rec) LightText(
                (if (autoPaused) "‖ Pause · " else "● Aufnahme · ") + "%.2f km".format(recDist / 1000),
                LightTextVariant.Detail,
            )
            gpsInfo?.let { LightText(it, LightTextVariant.Detail, lighten = true) }
            if (pos != null && message != null) LightText(message, LightTextVariant.Fine, lighten = true)
        }
    }
}


/** Zustände des Navigationsscreens. */
/** Ab dieser Genauigkeit wird aufgezeichnet und navigiert. */
private const val GOOD_ACCURACY_M = 30.0

enum class Mode(val usesGps: Boolean) {
    /** „Einfach losfahren“ – nur aufzeichnen. */
    FREE_RIDE(true),
    /** Geplante Tour ansehen, „Route starten“. */
    PREVIEW(false),
    /** Geplanter Tour folgen, mit Ansagen und Aufzeichnung. */
    NAVIGATE(true),
    /** Gefahrene Tour ansehen. */
    VIEW(false),
}
