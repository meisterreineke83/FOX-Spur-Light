package de.komoonlight.location

import de.komoonlight.geo.Pt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/** Port, an den GPS Logger for Android die Punkte schickt. */
const val GPS_LOGGER_PORT = 8378

/** Die URL, die man in GPS Logger unter „Log to custom URL“ einträgt. */
const val GPS_LOGGER_URL =
    "http://127.0.0.1:$GPS_LOGGER_PORT/fix?lat=%LAT&lon=%LON&acc=%ACC&alt=%ALT&ts=%TIMESTAMP"

/**
 * Ersatz-GPS über die App „GPS Logger for Android“ (mendhak), solange LightOS Spur den Standort verweigert.
 *
 * GPS Logger misst selbst und ruft bei jedem Punkt eine eigene URL auf („Log to custom URL“).
 * Spur lauscht dafür nur auf dem Phone selbst (127.0.0.1) und macht aus jedem Aufruf einen Fix.
 * Läuft, solange der Flow gesammelt wird.
 */
fun gpsLoggerLocations(port: Int = GPS_LOGGER_PORT): Flow<GpsEvent> = callbackFlow {
    val server = runCatching { ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")) }.getOrElse {
        trySend(GpsEvent.Waiting("GPS Logger: Port $port belegt"))
        awaitClose { }
        return@callbackFlow
    }
    val worker = thread(name = "gps-logger-bridge", isDaemon = true) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { handle(socket)?.let { trySend(it) } }
            runCatching { socket.close() }
        }
    }
    awaitClose { runCatching { server.close() }; worker.interrupt() }
}.flowOn(Dispatchers.IO)

private fun handle(socket: Socket): GpsEvent? {
    socket.soTimeout = 3_000
    val reader = socket.getInputStream().bufferedReader()
    val requestLine = reader.readLine() ?: return null
    // Rest der Kopfzeilen überlesen
    while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
    val event = parseGpsLoggerRequest(requestLine, System.currentTimeMillis())
    val body = if (event is GpsEvent.Fix) "ok" else "ignored"
    socket.getOutputStream().apply {
        write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
        flush()
    }
    return event
}

/**
 * Wertet eine Anfrage-Zeile wie `GET /fix?lat=..&lon=..&acc=..&ts=.. HTTP/1.1` aus.
 * Punkte, die GPS Logger verspätet nachliefert (älter als [maxAgeMs]), werden verworfen,
 * damit die Navigation nicht zurückspringt.
 */
internal fun parseGpsLoggerRequest(requestLine: String, nowMs: Long, maxAgeMs: Long = 30_000): GpsEvent? {
    val target = requestLine.split(' ').getOrNull(1) ?: return null
    val query = target.substringAfter('?', "")
    if (query.isEmpty()) return null
    val p = query.split('&').mapNotNull {
        val k = it.substringBefore('=', ""); if (k.isEmpty()) null
        else k to runCatching { URLDecoder.decode(it.substringAfter('='), "UTF-8") }.getOrDefault("")
    }.toMap()
    val lat = p["lat"]?.toDoubleOrNull() ?: return null
    val lon = p["lon"]?.toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    val acc = p["acc"]?.toDoubleOrNull() ?: 20.0
    val alt = p["alt"]?.toDoubleOrNull()
    // %TIMESTAMP liefert Sekunden; Millisekunden werden auch erkannt
    val ts = p["ts"]?.toDoubleOrNull()?.let { if (it < 1e11) (it * 1000).toLong() else it.toLong() } ?: nowMs
    if (nowMs - ts > maxAgeMs) return GpsEvent.Waiting("GPS Logger liefert verspätet nach")
    return GpsEvent.Fix(Pt(lat, lon, alt, ts.coerceAtMost(nowMs)), acc)
}
