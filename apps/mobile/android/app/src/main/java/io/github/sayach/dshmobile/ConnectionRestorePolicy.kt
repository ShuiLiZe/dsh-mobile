package io.github.sayach.dshmobile

/** The gateway transport selected by the user for the most recent successful connection. */
internal enum class AccessMode {
    LAN,
    REMOTE,
    ;

    companion object {
        fun parse(value: String?): AccessMode? = entries.firstOrNull { it.name == value }
    }
}

/** App launch destination; direct DSH keeps the existing single-device flow. */
internal enum class LaunchBehavior {
    DIRECT_DSH,
    DEVICE_LIST,
    ;

    companion object {
        fun parse(value: String?): LaunchBehavior? = entries.firstOrNull { it.name == value }
    }
}

/** Whether a failed trusted-session renewal is worth retrying without user input. */
internal enum class RestoreFailureDisposition {
    RETRY_TRANSIENT,
    REQUIRE_USER_ACTION,
}

/** Selects paired devices and classifies trusted-session restore failures. */
internal object ConnectionRestorePolicy {
    /** Select the saved or most recently used device for direct startup. */
    fun selectStartupDevice(
        devices: List<PairedDeviceRecord>,
        savedKey: String?,
        preferredMode: AccessMode?,
        now: Long,
    ): PairedDeviceRecord? {
        val usable = devices.filter {
            it.status != PairedDeviceStatus.REVOKED
                && it.status != PairedDeviceStatus.EXPIRED
                && it.status != PairedDeviceStatus.ADDRESS_CHANGED
                && it.expiresAt > now
        }
        return usable.firstOrNull { it.key == savedKey }
            ?: usable.filter { it.lastConnectedAt != null }.maxWithOrNull(
                compareBy<PairedDeviceRecord> { it.lastConnectedAt ?: Long.MIN_VALUE }
                    .thenBy { it.key },
            )
            ?: preferredMode?.let { mode -> usable.firstOrNull { it.mode == mode } }
            ?: usable.firstOrNull()
    }

    /** Send a persisted bearer credential only to the exact remote origin that previously received it. */
    fun shouldRenewBeforePairing(
        mode: AccessMode,
        credential: DeviceCredential?,
        instanceId: String,
        candidateOrigin: GatewayOrigin,
        savedOrigin: GatewayOrigin?,
        now: Long,
    ): Boolean = mode == AccessMode.REMOTE && credential != null && credential.expiresAt > now
        && credential.instanceId == instanceId && candidateOrigin == savedOrigin

    fun mayPairAfterRenewFailure(failure: Throwable): Boolean =
        (failure as? NativeAuthFailure)?.kind in setOf(
            NativeAuthFailureKind.PAIRING_EXPIRED,
            NativeAuthFailureKind.DEVICE_REVOKED,
            NativeAuthFailureKind.DEVICE_EXPIRED,
        )

    fun failureDisposition(
        failure: Throwable?,
        instanceMismatch: Boolean,
    ): RestoreFailureDisposition {
        if (instanceMismatch) return RestoreFailureDisposition.REQUIRE_USER_ACTION
        return when ((failure as? NativeAuthFailure)?.kind) {
            NativeAuthFailureKind.TIMEOUT,
            NativeAuthFailureKind.NETWORK,
            NativeAuthFailureKind.SERVER_UNAVAILABLE,
            -> RestoreFailureDisposition.RETRY_TRANSIENT
            else -> RestoreFailureDisposition.REQUIRE_USER_ACTION
        }
    }
}

/**
 * Automatic recovery pacing. A transient outage keeps retrying on an exponential schedule capped
 * at two minutes instead of abandoning the connection after a few seconds, and every delay carries
 * a deterministic ±20% spread so independent App instances do not probe the gateway in phase.
 */
internal object ConnectionBackoff {
    /** Delay before each recovery attempt; index 0 fires immediately and later indexes grow. */
    val RECOVERY_DELAYS_BASE_MS = longArrayOf(0L, 2_000L, 5_000L, 11_000L, 30_000L, 60_000L, 120_000L)
    const val RECOVERY_DELAYS = 7
    const val CAP_MS = 120_000L
    const val JITTER_FRACTION = 0.2

    /** Device-list probe cadence while every saved computer still answers promptly. */
    const val DEVICE_PROBE_ACTIVE_MS = 20_000L

    /** Device-list probe cadence after the most recent success is a minute old. */
    const val DEVICE_PROBE_RECENT_MS = 60_000L

    /** Device-list probe cadence after the most recent success is five minutes old. */
    const val DEVICE_PROBE_IDLE_MS = 300_000L
    const val DEVICE_PROBE_RECENT_AFTER_MS = 60_000L
    const val DEVICE_PROBE_IDLE_AFTER_MS = 300_000L

    /** Minimum time between two foreground liveness checks triggered by [onResume]. */
    const val FOREGROUND_PROBE_MIN_INTERVAL_MS = 15_000L

    fun recoveryDelay(attempt: Int): Long = recoveryDelay(attempt, Math.random())

    /** Pure mapping, deterministic for [jitterFraction] in [0,1). Attempts beyond the table stay capped. */
    fun recoveryDelay(attempt: Int, jitterFraction: Double): Long {
        if (attempt <= 0) return 0L
        val base = RECOVERY_DELAYS_BASE_MS.getOrElse(attempt) { CAP_MS }
        val spread = (base * JITTER_FRACTION).toLong()
        val fraction = jitterFraction.coerceIn(0.0, 1.0)
        return (base - spread + (spread * 2 * fraction).toLong()).coerceIn(1L, CAP_MS)
    }

    /** Probe cadence for one saved computer, measured from its most recent successful probe. */
    fun deviceProbeInterval(elapsedSinceLastSuccessMs: Long): Long = when {
        elapsedSinceLastSuccessMs < DEVICE_PROBE_RECENT_AFTER_MS -> DEVICE_PROBE_ACTIVE_MS
        elapsedSinceLastSuccessMs < DEVICE_PROBE_IDLE_AFTER_MS -> DEVICE_PROBE_RECENT_MS
        else -> DEVICE_PROBE_IDLE_MS
    }

    /** Slowest cadence required by any row: one answering device keeps the whole list at 20 s. */
    fun deviceProbeTickMs(devices: List<PairedDeviceRecord>, now: Long): Long =
        devices.minOfOrNull { device ->
            deviceProbeInterval(
                when {
                    device.status == PairedDeviceStatus.REVOKED -> DEVICE_PROBE_IDLE_AFTER_MS
                    else -> (device.lastReachableAt ?: device.lastConnectedAt ?: 0L).let { last ->
                        if (last <= 0L || now <= last) 0L else now - last
                    }
                },
            )
        } ?: DEVICE_PROBE_ACTIVE_MS

    /** A foreground check is due once the last one is old enough; repeated resumes cannot burst. */
    fun shouldProbeOnForeground(lastProbeAt: Long, now: Long): Boolean =
        lastProbeAt <= 0L || now - lastProbeAt >= FOREGROUND_PROBE_MIN_INTERVAL_MS
}
