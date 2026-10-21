package io.github.sayach.dshmobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the Activity's recovery entry points on the shared pacing policy rather than fixed timers. */
class ConnectionRecoveryWiringTest {
    @Test
    fun automaticRecoveryUsesTheBackoffScheduleInsteadOfAFixedRetryBudget() {
        val activity = activitySource()
        val schedule = methodBody(activity, "scheduleAutomaticRecovery")
        assertTrue(schedule.contains("recoveryAttempt >= ConnectionBackoff.RECOVERY_DELAYS"))
        assertTrue(schedule.contains("ConnectionBackoff.recoveryDelay(recoveryAttempt++)"))
        assertTrue(schedule.contains("restoreAttemptGate.retryCallback"))
        assertTrue(
            "The fixed four-attempt ladder must be gone",
            !activity.contains("longArrayOf(0L, 1_000L, 3_000L, 8_000L)"),
        )
    }

    @Test
    fun aCommittedPageRestartsTheBudget() {
        val loaded = methodBody(activitySource(), "onLoaded")
        assertTrue(loaded.contains("cancelAutomaticRecovery()"))
    }

    @Test
    fun aRetainedPageRetriesBeforeShowingTheFailureDialog() {
        val budget = methodBody(activitySource(), "finishAutomaticRecoveryAfterRetryBudget")
        val keepPage = budget.substringAfter("ExhaustedRecoveryAction.KEEP_PAGE ->").substringBefore("ExhaustedRecoveryAction.SHOW_CONNECTIONS")
        val retry = keepPage.indexOf("scheduleAutomaticRecovery()")
        assertTrue("KEEP_PAGE must retry automatically", retry >= 0)
        assertTrue("The dialog stays the last resort", retry < keepPage.indexOf("showLoadFailure(LoadFailure.NETWORK)"))
        assertTrue("A retained page restarts the budget", keepPage.contains("recoveryAttempt = 0"))
    }

    @Test
    fun returningToTheForegroundRunsOneGuardedProbe() {
        val activity = activitySource()
        val resume = methodBody(activity, "onResume")
        assertTrue(resume.contains("probeAfterForegroundReturn()"))
        val probe = methodBody(activity, "probeAfterForegroundReturn")
        assertTrue(probe.contains("ConnectionBackoff.shouldProbeOnForeground(lastForegroundProbeAt, now)"))
        assertTrue(probe.contains("pendingConnection != null"))
        assertTrue(probe.contains("failureDialog?.isShowing == true"))
        assertTrue(probe.contains("if (!scheduleAutomaticRecovery()) return"))
        assertTrue(activity.contains("private var lastForegroundProbeAt = 0L"))
    }

    @Test
    fun deviceListRefreshBacksOffFromTheMostRecentReachability() {
        val refresh = methodBody(activitySource(), "refreshDeviceStatuses")
        assertTrue(refresh.contains("ConnectionBackoff.deviceProbeTickMs(devices, System.currentTimeMillis())"))
        assertTrue(refresh.contains("deviceStatusHandler.postDelayed(it, interval)"))
        assertFalse("A fixed 20 s tick must not remain", refresh.contains("DEVICE_STATUS_REFRESH_MS"))
    }

    private fun methodBody(source: String, name: String): String {
        for (declaration in listOf("private fun $name(", "override fun $name(", "$name = {")) {
            if (!source.contains(declaration)) continue
            return source.substringAfter(declaration)
                .substringBefore("\n    private fun ")
                .substringBefore("\n            },")
        }
        throw AssertionError("Missing owning Activity declaration $name")
    }

    private fun activitySource(): String {
        val path = "java/io/github/sayach/dshmobile/MainActivity.kt"
        val directories = listOf("src/main", "app/src/main", "apps/mobile/android/app/src/main")
        return generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .flatMap { base -> directories.asSequence().map { File(base, "$it/$path") } }
            .first { it.isFile }.readText()
    }
}
