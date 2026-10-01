package io.github.bloodbear111.motogps.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The coordinate-system decision is the part of the AMap integration that can be
 * wrong without looking wrong: feeding GCJ-02 to a pipeline defined in WGS84
 * shifts every position by roughly 500 m, and the result reads as "bad GPS"
 * rather than as a bug. These tests pin the decision and the refusals.
 */
class AmapFixMapperTest {

    private val neverCalled: (Double, Double) -> DoubleArray? =
        { _, _ -> error("conversion must not be attempted") }

    private fun readout(
        coordinateType: String = "GCJ02",
        longitude: Double = 100.267638,
        latitude: Double = 25.618083,
        accuracy: Double = 12.5,
        errorCode: Int = 0,
        errorInfo: String = "",
        mock: Boolean = false,
    ) = AmapReadout(
        longitudeDeg = longitude,
        latitudeDeg = latitude,
        coordinateType = coordinateType,
        accuracyM = accuracy,
        speedMps = 3.5,
        bearingDeg = 187.0,
        timeMs = 1_700_000_000_000L,
        locationType = 5,
        isMock = mock,
        errorCode = errorCode,
        errorInfo = errorInfo,
    )

    @Test
    fun `gcj02 is inverted before it reaches the core`() {
        val calls = mutableListOf<Pair<Double, Double>>()
        val mapper = AmapFixMapper { longitude, latitude ->
            calls += longitude to latitude
            doubleArrayOf(longitude - 0.0041, latitude - 0.0020)
        }

        val outcome = assertIs<AmapFixOutcome.Fix>(mapper.map(readout(), monotonicNowMs = 42L))

        assertTrue(outcome.converted)
        assertEquals(listOf(100.267638 to 25.618083), calls)
        assertEquals(100.267638 - 0.0041, outcome.fix.longitudeDeg, 1e-9)
        assertEquals(25.618083 - 0.0020, outcome.fix.latitudeDeg, 1e-9)
    }

    @Test
    fun `wgs84 passes through untouched`() {
        val mapper = AmapFixMapper(neverCalled)

        val outcome = assertIs<AmapFixOutcome.Fix>(
            mapper.map(readout(coordinateType = "WGS84"), monotonicNowMs = 42L),
        )

        assertFalse(outcome.converted)
        assertEquals(100.267638, outcome.fix.longitudeDeg, 1e-9)
        assertEquals(25.618083, outcome.fix.latitudeDeg, 1e-9)
    }

    @Test
    fun `a missing coordinate type is refused rather than assumed`() {
        val mapper = AmapFixMapper(neverCalled)

        val outcome = mapper.map(readout(coordinateType = ""), monotonicNowMs = 42L)

        assertIs<AmapFixOutcome.Rejected>(outcome)
        assertTrue(outcome.reason.contains("coordinate type"), outcome.reason)
    }

    @Test
    fun `an unknown coordinate system is refused`() {
        val mapper = AmapFixMapper(neverCalled)

        val outcome = mapper.map(readout(coordinateType = "BD09"), monotonicNowMs = 42L)

        assertIs<AmapFixOutcome.Rejected>(outcome)
        assertTrue(outcome.reason.contains("BD09"), outcome.reason)
    }

    @Test
    fun `a fix that cannot be converted is refused, never shifted silently`() {
        val mapper = AmapFixMapper { _, _ -> null }

        val outcome = mapper.map(readout(), monotonicNowMs = 42L)

        assertIs<AmapFixOutcome.Rejected>(outcome)
        assertTrue(outcome.reason.contains("unavailable"), outcome.reason)
    }

    @Test
    fun `sdk errors carry their code and the actionable hint`() {
        val mapper = AmapFixMapper(neverCalled)

        val outcome = mapper.map(
            readout(errorCode = 35, errorInfo = "key 验证失败"),
            monotonicNowMs = 42L,
        )

        assertIs<AmapFixOutcome.Rejected>(outcome)
        assertTrue(outcome.reason.contains("35"), outcome.reason)
        assertTrue(outcome.reason.contains("SHA1"), outcome.reason)
    }

    @Test
    fun `a mock location is refused`() {
        val mapper = AmapFixMapper(neverCalled)

        val outcome = mapper.map(readout(mock = true), monotonicNowMs = 42L)

        assertIs<AmapFixOutcome.Rejected>(outcome)
        assertTrue(outcome.reason.contains("mock"), outcome.reason)
    }

    @Test
    fun `0,0 and non finite coordinates are refused`() {
        val mapper = AmapFixMapper(neverCalled)

        assertIs<AmapFixOutcome.Rejected>(
            mapper.map(readout(longitude = 0.0, latitude = 0.0), monotonicNowMs = 1L),
        )
        assertIs<AmapFixOutcome.Rejected>(
            mapper.map(
                readout(longitude = Double.NaN, latitude = 25.0),
                monotonicNowMs = 1L,
            ),
        )
    }

    @Test
    fun `the fix timestamp is monotonic, not the sdk wall clock`() {
        val mapper = AmapFixMapper(neverCalled)

        val outcome = assertIs<AmapFixOutcome.Fix>(
            mapper.map(readout(coordinateType = "WGS84"), monotonicNowMs = 987_654L),
        )

        assertEquals(987_654L, outcome.fix.timestampMs)
    }

    @Test
    fun `key validation accepts only 32 hex characters`() {
        assertEquals(
            "07ca2b5387679b3a03fd4c67ad5abdf6",
            AmapLocationSettings.normalizeKey("  07CA2B5387679B3A03FD4C67AD5ABDF6 "),
        )

        for (bad in listOf("", "   ", "07ca2b5387679b3a03fd4c67ad5abdf", "zz".repeat(16))) {
            val failure = runCatching { AmapLocationSettings.normalizeKey(bad) }
            assertTrue(failure.isFailure, "expected '$bad' to be rejected")
        }
    }

    @Test
    fun `a stored key is only ever shown masked`() {
        assertEquals("unset", AmapLocationSettings.describeKey(null))
        assertEquals(
            "07ca...bdf6",
            AmapLocationSettings.describeKey("07ca2b5387679b3a03fd4c67ad5abdf6"),
        )
    }
}
