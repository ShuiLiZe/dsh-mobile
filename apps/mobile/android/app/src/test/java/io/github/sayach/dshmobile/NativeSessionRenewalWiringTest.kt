package io.github.sayach.dshmobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks that the Activity connects bridge requests to the same tested restore and cookie owners. */
class NativeSessionRenewalWiringTest {
    @Test fun bridgeRenewalUsesTheActiveExactDeviceWithoutASecondNetworkOrCookieImplementation() {
        val source = source("java/io/github/sayach/dshmobile/MainActivity.kt")
        val renew = method(source, "renewSessionFromPage")
        for (condition in listOf("activeDeviceKey", "it.key == key && it.origin == origin", "bridgeSessionRenewal.pending",
            "pendingConnection != null", "restoreAttemptGate.restorePending", "recoveryStoppedBrowser === browser",
            "device.status == PairedDeviceStatus.REVOKED", "device.expiresAt <= System.currentTimeMillis()")) {
            assertTrue("Missing renewal admission: $condition", renew.contains(condition))
        }
        for (condition in listOf("bridgeSessionRenewal.isCurrent(request)", "bridgeIsCurrent()", "webView === browser",
            "gatewayOrigin == origin", "activeDeviceKey == key", "currentDocumentGeneration == documentGeneration",
            "!fileChooserTransitions.active", "!isFinishing && !isDestroyed")) {
            assertTrue("Missing current-document fence: $condition", renew.contains(condition))
        }
        assertTrue(renew.contains("restoreTrustedDevice("))
        assertTrue(renew.contains("requestIsCurrent = ::ownsDocument"))
        assertTrue(renew.contains("allowOriginRediscovery = false"))
        assertFalse(renew.contains("NativeAuthClient.renew"))
        assertFalse(renew.contains("restoreExecutor.execute"))
        assertFalse(renew.contains("installNativeSession("))
        assertFalse(renew.contains("maxByOrNull"))
        assertFalse(renew.contains("loadUrl("))
        assertTrue(renew.contains("done(false, \"no_credential\")"))
        assertTrue(renew.contains("done(false, \"busy\")"))
    }

    @Test fun stoppingNavigatingOrDestroyingTheOwnerWithdrawsTheBridgeWaiterAndNetwork() {
        val source = source("java/io/github/sayach/dshmobile/MainActivity.kt")
        val cancel = method(source, "cancelPageSessionRenewal")
        assertTrue(cancel.contains("bridgeSessionRenewal.cancel(requestId)"))
        assertTrue(cancel.contains("withdrawConnectionAttempt()"))
        assertTrue(cancel.contains("cancelAutomaticRecovery()"))
        assertTrue(cancel.contains("invalidateRestoreAttempts()"))
        assertTrue(method(source, "withdrawConnectionAttempt").contains("bridgeSessionRenewal.cancel()"))
        assertTrue(method(source, "invalidateRestoreAttempts").contains("bridgeSessionRenewal.cancel(errorCode = errorCode)"))
        assertTrue(method(source, "destroyWebView").contains("cancelPageSessionRenewal()"))
        val browser = method(source, "showBrowser")
        for (callback in listOf("onNavigationStarted", "onBeforeTopLevelNavigation", "onTopLevelUrlChanged")) {
            assertTrue("$callback withdraws the old document's renewal", browser.substringAfter(callback).substringBefore("\n            },").contains("cancelPageSessionRenewal()"))
        }
    }

    @Test fun theApplicationOwnsTheCookieQueueAndSerializesByHostRatherThanPort() {
        val application = source("java/io/github/sayach/dshmobile/MobileApplication.kt")
        assertTrue(application.contains("class MobileApplication : Application()"))
        assertTrue(application.contains("val sessionCookieInstaller = NativeSessionCookieInstaller"))
        assertFalse(application.contains("companion object"))
        assertTrue(source("AndroidManifest.xml").contains("android:name=\".MobileApplication\""))
        val install = method(source("java/io/github/sayach/dshmobile/MainActivity.kt"), "installNativeSession")
        assertTrue(install.contains("(application as MobileApplication).sessionCookieInstaller.install("))
        assertTrue(install.contains("key = origin.host"))
        assertTrue(install.contains("NativeSessionCookieWrite(origin.serialized, it)"))
        assertTrue(install.contains("!isFinishing && !isDestroyed && isCurrent()"))
        assertFalse(install.contains("setCookie("))
    }

    @Test fun theNativeBridgeCarriesRequestCancellationAndReturnsOnlySafeErrorCodes() {
        val bridge = source("java/io/github/sayach/dshmobile/NativeBridge.kt")
        assertTrue(bridge.contains("\"auth.renew\" -> activity.runOnUiThread"))
        assertTrue(bridge.contains("expectedDocument == documentGeneration"))
        assertTrue(bridge.contains("renew(requestId, isCurrent)"))
        assertTrue(bridge.contains("JSONObject().put(\"renewed\", true)"))
        assertTrue(bridge.contains("\"auth.renew.cancel\" ->"))
        assertTrue(bridge.contains("pending[requestId]?.action == \"auth.renew\""))
        assertTrue(bridge.contains("onCancelRenewSession?.invoke(requestId)"))
        assertTrue(bridge.contains("action === 'auth.renew' ? options.signal : undefined"))
        assertTrue(bridge.contains("event: 'auth.renew.cancel', requestId"))
        assertTrue(bridge.contains("signal.removeEventListener('abort', onAbort)"))
        assertFalse(bridge.contains("failure.message"))
    }

    @Test fun rootBackExitsAfterWebLayersWithoutOpeningTheListOrRequiringSeveralPresses() {
        val activity = source("java/io/github/sayach/dshmobile/MainActivity.kt")
        val back = method(activity, "handleNativeBack")
        assertTrue(back.contains("browser.goBack()"))
        assertTrue(back.contains("setupBackAction?.invoke()"))
        assertTrue(back.contains("finish()"))
        assertFalse(activity.contains("backExitStage"))
        assertFalse(back.contains("open-sidebar"))
        assertFalse(activity.contains("back_press_again_to_exit"))
    }

    @Test fun bridgeRenewalKeepsTheVisibleDocumentAndKeyboardWithoutLettingPageReadinessCompleteAuthentication() {
        val activity = source("java/io/github/sayach/dshmobile/MainActivity.kt")
        val renew = method(activity, "renewSessionFromPage")
        assertTrue(renew.contains("preserveBrowser = browser, keepVisibleDocument = true"))
        assertTrue("Only the explicit page renewal opts into this presentation", Regex("keepVisibleDocument = true").findAll(activity).count() == 1)
        val start = method(activity, "startConnectionAttempt")
        assertTrue(start.contains("keepVisibleDocument: Boolean = false"))
        val compactBranch = start.substringAfter("if (keepVisibleDocument && preserveBrowser != null)").substringBefore("currentFocus?.let")
        assertTrue(compactBranch.contains("showCompactConnectionWaiting(attempt, stopReconnecting = true)"))
        assertTrue(compactBranch.contains("return attempt"))
        assertFalse(compactBranch.contains("hideSoftInputFromWindow"))
        assertFalse(compactBranch.contains("loadingConnectionAttempt ="))
        val presentation = method(activity, "showCompactConnectionWaiting")
        for (operation in listOf("ProgressBar", "addSetupArtwork", "hideSoftInputFromWindow", "finishConnectionAttempt(", "loadingConnectionAttempt =", "pageReadyCheck =")) {
            assertFalse("Compact presentation cannot claim authentication completion or input focus: $operation", presentation.contains(operation))
        }
        assertTrue(method(activity, "finishConnectionWhenPageReady").contains("loadingConnectionAttempt !== attempt"))
    }

    @Test fun aCredentialReadFailureAfterRenewalSettlesImmediatelyInsteadOfWaitingForTheConnectionDeadline() {
        val activity = source("java/io/github/sayach/dshmobile/MainActivity.kt")
        val read = method(activity, "readPairedRestoreRecords")
        assertTrue(read.contains("pairedDeviceStore.read()"))
        assertTrue(read.contains("CredentialRead.Absent -> emptyList()"))
        assertTrue(read.contains("CredentialRead.Unreadable -> { showCredentialStorageFailure(); null }"))
        val restore = method(activity, "restoreTrustedDevice")
        assertFalse(restore.contains("pairedDeviceStore.load()"))
        assertTrue(restore.contains("readPairedRestoreRecords() ?: run { unavailable(); return@callback }"))
        val unavailable = restore.substringAfter("fun unavailable()").substringBefore("val requestedDevice =")
        assertTrue(unavailable.contains("restoreAttemptGate.completeRestore(generation)"))
        assertTrue(unavailable.contains("if (onUnavailable != null) onUnavailable() else onFailure(RestoreFailureDisposition.REQUIRE_USER_ACTION)"))
        val changed = restore.substringAfter("!PairedDeviceStatusPolicy.mayApplyResult(current, requestedDevice)").substringBefore("if (renewed == null)")
        assertTrue(changed.contains("onFailure(RestoreFailureDisposition.REQUIRE_USER_ACTION)"))
        val bridge = method(activity, "renewSessionFromPage").substringAfter("onUnavailable = {").substringBefore("\n                },")
        assertTrue(bridge.contains("finishConnectionAttempt(attempt)"))
        assertTrue(bridge.contains("bridgeSessionRenewal.finish(request, \"unavailable\")"))
    }

    private fun method(source: String, name: String): String =
        source.substringAfter("private fun $name(").substringBefore("\n    private fun ")

    private fun source(relative: String): String = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .flatMap { base -> listOf("src/main", "app/src/main", "apps/mobile/android/app/src/main").asSequence().map { File(base, "$it/$relative") } }
        .first { it.isFile }.readText()
}
