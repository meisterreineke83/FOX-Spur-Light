package de.komoonlight

import de.komoonlight.location.GpsEvent
import de.komoonlight.location.parseGpsLoggerRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GpsLoggerBridgeTest {
    private val now = 1_800_000_000_000L

    @Test
    fun parsesFixFromGpsLoggerUrl() {
        val e = parseGpsLoggerRequest("GET /fix?lat=52.52&lon=13.405&acc=7.5&alt=34&ts=${now / 1000} HTTP/1.1", now)
        assertIs<GpsEvent.Fix>(e)
        assertEquals(52.52, e.pt.lat); assertEquals(13.405, e.pt.lon)
        assertEquals(7.5, e.accuracyM); assertEquals(34.0, e.pt.alt)
        assertEquals(now, e.pt.time)
    }

    @Test
    fun missingTimestampUsesNow() {
        val e = parseGpsLoggerRequest("GET /fix?lat=1&lon=2 HTTP/1.1", now)
        assertIs<GpsEvent.Fix>(e); assertEquals(now, e.pt.time)
    }

    @Test
    fun latePointsAreNotUsedAsPosition() {
        val e = parseGpsLoggerRequest("GET /fix?lat=1&lon=2&ts=${(now - 120_000) / 1000} HTTP/1.1", now)
        assertIs<GpsEvent.Waiting>(e)
    }

    @Test
    fun ignoresGarbage() {
        assertNull(parseGpsLoggerRequest("GET /favicon.ico HTTP/1.1", now))
        assertNull(parseGpsLoggerRequest("GET /fix?lat=%LAT&lon=%LON HTTP/1.1", now))
        assertNull(parseGpsLoggerRequest("GET /fix?lat=999&lon=2 HTTP/1.1", now))
    }
}
