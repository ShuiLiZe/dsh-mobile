package io.github.sayach.dshmobile

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Activity callback wiring complements policy, network, cancellation and physical-slot tests. */
class SameHostSwitchWiringTest {
    private fun source(): String = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .flatMap { base -> listOf("src/main", "app/src/main", "apps/mobile/android/app/src/main").asSequence()
            .map { File(base, "$it/java/io/github/sayach/dshmobile/MainActivity.kt") } }.first { it.isFile }.readText()
    private fun method(text: String, name: String) = text.substringAfter("private fun $name(").substringBefore("\n    private fun ")

    @Test fun backgroundingWithdrawsOnlyTheAutomaticSwitchAndFencesEveryRestoreStage() {
        val text = source()
        val pause = text.substringAfter("override fun onPause()").substringBefore("override fun onDestroy()")
        assertTrue(pause.contains("pendingConnection === sameHostRestoreAttempt"))
        assertTrue(pause.contains("withdrawConnectionAttempt()"))
        assertTrue(pause.contains("invalidateRestoreAttempts()"))
        assertTrue(pause.contains("sameHostProbeCancellation?.cancel()"))
        val switch = method(text, "requestSameHostSwitch")
        assertTrue(switch.contains("claimSuccess = { sameHostGate.foreground && lifecycleOwner == sameHostGate.generation &&"))
        assertTrue(switch.contains("webView === browser && gatewayOrigin == sourceOrigin && automaticSameHost()"))
        assertTrue(switch.contains("sameHostRestoreAttempt = pendingConnection"))
        val restore = method(text, "restoreTrustedDevice")
        assertTrue(restore.contains("isCurrent = { restoreAttemptGate.isCurrent(generation) && connectionAttempts.isCurrent(attempt) && requestIsCurrent() && claimSuccess() }"))
        val invalidate = method(text, "invalidateRestoreAttempts")
        assertTrue(invalidate.contains("draftTransfer?.abort()"))
        assertTrue(invalidate.contains("releaseCapturedDraft()"))
        assertTrue(invalidate.contains("bridgeSessionRenewal.cancel(errorCode = errorCode)"))
        val cookies = restore.substringAfter("installNativeSession(")
        assertTrue(cookies.contains("onFailure = ::unavailable"))
        assertTrue(cookies.indexOf("onAuthenticated(selectedOrigin, saved)") < cookies.indexOf("activatePairedDevice(saved)"))
        assertTrue(cookies.contains("if (onSessionInstalled != null) onSessionInstalled()"))
        assertTrue(restore.indexOf("if (!claimSuccess())") < restore.indexOf("savePairedDevice(mode"))
    }

    @Test fun automaticSelectionAndPreferencesActivateOnlyAfterCurrentCookies() {
        val text = source()
        val switch = method(text, "requestSameHostSwitch")
        assertTrue(switch.contains("deferActivation = true"))
        val restore = method(text, "restoreTrustedDevice")
        assertTrue(restore.contains("if (!deferActivation) accessMode = mode"))
        assertTrue(restore.contains("activate = !deferActivation"))
        val cookies = restore.substringAfter("installNativeSession(")
        assertTrue(cookies.indexOf("if (!claimSuccess())") < cookies.indexOf("activatePairedDevice(saved)"))
        assertTrue(cookies.contains("accessMode = mode"))
        val save = method(text, "savePairedDevice")
        assertTrue(save.contains("if (activate) activatePairedDevice(record)"))
        assertFalse(save.contains("PREFERENCE_LAST_DEVICE_KEY"))
        val activate = method(text, "activatePairedDevice")
        assertTrue(activate.contains("activeDeviceKey = record.key"))
        assertTrue(activate.contains("PREFERENCE_LAST_DEVICE_KEY, record.key"))
        assertTrue(activate.contains("originPreference(record.mode), record.origin.serialized"))
    }

    @Test fun failedTargetAlwaysSettlesItsWaitAndKeepsTheSourcePage() {
        val switch = method(source(), "requestSameHostSwitch")
        val failure = switch.substringAfter("claimSuccess =").substringBefore("sameHostRestoreAttempt = pendingConnection")
        assertTrue(failure.contains("finishConnectionAttempt()"))
        assertTrue(failure.contains("sameHostRestoreAttempt = null"))
        assertTrue(failure.contains("browser != null && webView === browser"))
        assertTrue(failure.contains("Toast.makeText"))
        assertFalse(failure.contains("recoveryStoppedBrowser ="))
        assertFalse(failure.contains("destroyWebView()"))
        assertFalse(failure.contains("showDeviceList()"))
        assertFalse(switch.contains("probeMountedDshPage("))
    }

    @Test fun wifiLossProbesRemoteBeforeLanRecoveryCompletes() {
        val check = method(source(), "checkSameHostConnection")
        assertTrue(check.contains("val proactiveRemote = active.mode == AccessMode.LAN && !wifi"))
        assertTrue(check.contains("if (!proactiveRemote && (pendingConnection != null || restoreAttemptGate.restorePending)) return"))
        assertTrue(check.contains("proactiveRemote -> SameHostSwitchPolicy.candidate(active, rows, AccessMode.REMOTE"))
        assertTrue(check.contains("(!proactiveRemote && pendingConnection != null)"))
        assertTrue(check.contains("if (proactiveRemote && sameHostMonitor.hasWifi()) return@runOnUiThread"))
        assertTrue(check.contains("sameHostGate.networkChanged()"))
        assertTrue(check.indexOf("NativeAuthClient.probe(target.origin, target.deviceToken, target.caCertificate") < check.indexOf("requestSameHostSwitch(active, updated)"))
        val switch = method(source(), "requestSameHostSwitch")
        assertTrue(switch.indexOf("withdrawConnectionAttempt()") < switch.indexOf("val generation = restoreGeneration"))
        assertTrue(switch.contains("SameHostSwitchPolicy.requiresConfirmation(sameHostMode(), browser != null)"))
        assertTrue(switch.contains("if (silent) connect()"))
        assertTrue(switch.contains("if (silent) connect() else confirm()"))
        val activation = switch.substringAfter("onActivated = {").substringBefore("}) {")
        assertTrue(activation.contains("if (pendingConnection === sameHostRestoreAttempt) sameHostRestoreAttempt = null"))
        assertTrue(activation.contains("if (silent) Toast.makeText"))
        assertFalse(switch.contains("mounted, to.mode"))
    }

    @Test fun foregroundProbeUsesCancellationAndTheExistingCredentialPhysicalSlot() {
        val check = method(source(), "checkSameHostConnection")
        assertTrue(check.contains("restoreWorkGate.submit("))
        assertTrue(check.contains("workExecutor = sameHostProbeExecutor"))
        val text = source()
        assertTrue(text.contains("private val sameHostProbeExecutor = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,"))
        assertTrue(text.contains("TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue(1)"))
        assertTrue(text.contains("sameHostProbeExecutor.shutdownNow()"))
        assertTrue(check.contains("key = \"\${target.instanceId}:\${target.deviceToken}\""))
        assertTrue(check.contains("postDelayed(timeout, 10_000)"))
        assertTrue(check.contains("cancellation.cancel()"))
        assertTrue(check.contains("restoreGeneration != restoreOwner"))
        assertTrue(check.contains("recoveryStoppedBrowser === webView"))
    }
}
