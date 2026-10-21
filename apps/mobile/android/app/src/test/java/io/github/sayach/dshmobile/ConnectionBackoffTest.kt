package io.github.sayach.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies recovery pacing, foreground probing and device-list backoff as pure mappings. */
class ConnectionBackoffTest {
    private fun device(
        seed: Char = 'a',
        lastReachableAt: Long? = 1_000L,
        lastConnectedAt: Long? = 1_000L,
        status: PairedDeviceStatus = PairedDeviceStatus.REACHABLE,
    ) = PairedDeviceRecord(
        instanceId = seed.toString().repeat(64),
        deviceId = "d".repeat(32),
        displayName = "Computer $seed",
        mode = AccessMode.LAN,
        origin = GatewayOrigin.parse("https://192.168.1.10:8080")!!,
        deviceToken = "T".repeat(43),
        expiresAt = 9_999_999_999_999L,
        caCertificate = null,
        lastConnectedAt = lastConnectedAt,
        lastReachableAt = lastReachableAt,
        status = status,
    )

    @Test
    fun firstAttemptIsImmediateAndTheTableKeepsTheDocumentedLadder() {
        assertEquals(0L, ConnectionBackoff.recoveryDelay(0, 0.5))
        assertEquals(0L, ConnectionBackoff.recoveryDelay(-3, 0.5))
        assertEquals(7, ConnectionBackoff.RECOVERY_DELAYS)
        assertEquals(
            listOf(0L, 2_000L, 5_000L, 11_000L, 30_000L, 60_000L, 120_000L),
            ConnectionBackoff.RECOVERY_DELAYS_BASE_MS.toList(),
        )
    }

    @Test
    fun everyDelayCarriesAPlusMinusTwentyPercentSpread() {
        assertEquals(1_600L, ConnectionBackoff.recoveryDelay(1, 0.0))
        // [0,1) can never reach the exact upper bound; the ceiling is one millisecond short.
        assertEquals(2_399L, ConnectionBackoff.recoveryDelay(1, 0.999))
        assertEquals(2_400L, ConnectionBackoff.recoveryDelay(1, 1.0))
        assertEquals(24_000L, ConnectionBackoff.recoveryDelay(4, 0.0))
        assertEquals(96_000L, ConnectionBackoff.recoveryDelay(6, 0.0))
        for (attempt in 1 until ConnectionBackoff.RECOVERY_DELAYS) {
            val base = ConnectionBackoff.RECOVERY_DELAYS_BASE_MS[attempt]
            val low = ConnectionBackoff.recoveryDelay(attempt, 0.0)
            val high = ConnectionBackoff.recoveryDelay(attempt, 1.0)
            assertTrue("attempt $attempt low $low", low >= base * 8 / 10)
            assertTrue("attempt $attempt high $high", high <= base * 12 / 10)
            assertTrue("attempt $attempt is spread", high > low)
        }
    }

    @Test
    fun theScheduleStaysCappedInsteadOfGivingUpForever() {
        assertEquals(ConnectionBackoff.CAP_MS, ConnectionBackoff.recoveryDelay(99, 0.999))
        assertEquals(ConnectionBackoff.CAP_MS * 8 / 10, ConnectionBackoff.recoveryDelay(99, 0.0))
        // A long outage keeps asking at the ceiling rather than ending the retry budget.
        assertEquals(ConnectionBackoff.CAP_MS, ConnectionBackoff.recoveryDelay(7, 0.5))
    }

    @Test
    fun theWholeBudgetLandsInsideTheDocumentedFourMinuteWindow() {
        val fastest = (1 until ConnectionBackoff.RECOVERY_DELAYS).sumOf { ConnectionBackoff.recoveryDelay(it, 0.0) }
        val slowest = (1 until ConnectionBackoff.RECOVERY_DELAYS).sumOf { ConnectionBackoff.recoveryDelay(it, 1.0) }
        assertEquals(182_400L, fastest)
        // 249.6 s when every delay lands on its +20% ceiling, against 12 s for the previous ladder.
        assertEquals(249_600L, slowest)
        assertTrue("still a bounded window", slowest <= 5 * 60_000L)
        assertTrue("more than the previous 4 attempts", ConnectionBackoff.RECOVERY_DELAYS > 4)
    }

    @Test
    fun deviceProbeIntervalStepsThroughTwentySixtyAndThreeHundredSeconds() {
        assertEquals(20_000L, ConnectionBackoff.deviceProbeInterval(0L))
        assertEquals(20_000L, ConnectionBackoff.deviceProbeInterval(59_999L))
        assertEquals(60_000L, ConnectionBackoff.deviceProbeInterval(60_000L))
        assertEquals(60_000L, ConnectionBackoff.deviceProbeInterval(299_999L))
        assertEquals(300_000L, ConnectionBackoff.deviceProbeInterval(300_000L))
        assertEquals(300_000L, ConnectionBackoff.deviceProbeInterval(86_400_000L))
    }

    @Test
    fun oneAnsweringComputerKeepsTheWholeListAtTheFastestCadence() {
        val now = 10_000_000L
        val fresh = device('a', lastReachableAt = now - 1_000L)
        val stale = device('b', lastReachableAt = now - 600_000L)

        assertEquals(20_000L, ConnectionBackoff.deviceProbeTickMs(listOf(fresh, stale), now))
        assertEquals(60_000L, ConnectionBackoff.deviceProbeTickMs(listOf(stale.copy(lastReachableAt = now - 90_000L)), now))
        assertEquals(300_000L, ConnectionBackoff.deviceProbeTickMs(listOf(stale), now))
    }

    @Test
    fun neverProbedRowsFallBackToTheLastConnectionAndRevokedRowsStopDrivingTheTick() {
        val now = 10_000_000L
        val neverProbed = device('c', lastReachableAt = null, lastConnectedAt = now - 120_000L)
        assertEquals(60_000L, ConnectionBackoff.deviceProbeTickMs(listOf(neverProbed), now))

        val unknown = device('d', lastReachableAt = null, lastConnectedAt = null)
        assertEquals(20_000L, ConnectionBackoff.deviceProbeTickMs(listOf(unknown), now))

        val revoked = device('e', lastReachableAt = null, lastConnectedAt = null, status = PairedDeviceStatus.REVOKED)
        assertEquals(20_000L, ConnectionBackoff.deviceProbeTickMs(listOf(revoked, device('f', lastReachableAt = now)), now))
        assertEquals(ConnectionBackoff.DEVICE_PROBE_ACTIVE_MS, ConnectionBackoff.deviceProbeTickMs(emptyList(), now))
    }

    @Test
    fun aForegroundCheckIsDueOnceButRepeatedResumesCannotBurst() {
        assertTrue(ConnectionBackoff.shouldProbeOnForeground(0L, 1_000_000L))
        assertFalse(ConnectionBackoff.shouldProbeOnForeground(1_000_000L, 1_000_000L))
        assertFalse(ConnectionBackoff.shouldProbeOnForeground(1_000_000L, 1_014_999L))
        assertTrue(ConnectionBackoff.shouldProbeOnForeground(1_000_000L, 1_015_000L))
        assertEquals(15_000L, ConnectionBackoff.FOREGROUND_PROBE_MIN_INTERVAL_MS)
    }
}
