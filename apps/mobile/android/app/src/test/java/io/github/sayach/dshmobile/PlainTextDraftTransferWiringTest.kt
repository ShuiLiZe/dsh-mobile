package io.github.sayach.dshmobile

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlainTextDraftTransferWiringTest {
    private fun source(name: String): String = File("src/main/java/io/github/sayach/dshmobile/$name.kt").readText().replace("\r\n", "\n")

    @Test fun restorationAndReadonlyVerificationMustPrecedeFinalSourceValidation() {
        val text = source("PlainTextDraftTransfer")
        val restore = text.indexOf("p.restore(\u0024json)")
        val verify = text.indexOf("p.verify(\u0024json)")
        val validate = text.indexOf("p.validate(\u0024json)")
        assertTrue(restore >= 0 && verify > restore && validate > verify)
        assertTrue(text.indexOf("onCommit()") > validate)
        assertTrue(text.contains("intact != \"true\" || !current()"))
        assertTrue(text.contains("p.rollback(\u0024json)"))
        assertTrue(text.contains("handler.postDelayed(rollbackDeadline, 1500)"))
        assertTrue(text.contains("handler.postDelayed(deadline, 30_000L)"))
        assertTrue(text.contains("toByteArray(Charsets.UTF_8).size <= 256 * 1024"))
    }

    @Test fun stagedBrowserIsPromotedWithoutReloadAndHasDedicatedSecurityClient() {
        val text = source("MainActivity")
        val start = text.indexOf("private fun stagePlainTextTransfer")
        val stage = text.substring(start, text.indexOf("private fun trySameHostFallback", start))
        assertTrue(stage.contains("SecureWebViewClient(targetOrigin, requested.caCertificate"))
        assertTrue(stage.contains("MIXED_CONTENT_NEVER_ALLOW"))
        val registration = stage.indexOf("targetBridge.install()")
        assertTrue(registration >= 0 && registration < stage.indexOf("target.loadUrl(url)"))
        assertTrue(stage.contains("NativeBridge(this, target, targetOrigin, activeOwner = {"))
        assertTrue(stage.contains("webView === target && gatewayOrigin == targetOrigin && !isFinishing && !isDestroyed"))
        assertTrue(stage.contains("stagedBrowser = StagedBrowser(target, targetBridge)"))
        assertTrue(stage.contains("generation == restoreGeneration"))
        assertTrue(stage.contains("lifecycleOwner == sameHostGate.generation"))
        assertTrue(stage.contains("PairedDeviceStatusPolicy.mayApplyResult(it, saved)"))
        assertTrue(stage.contains("target.destroy(); parent.removeView(shield)"))
        assertTrue(text.contains("if (stagedBrowser == null) browser.loadUrl(initialUrl)"))
    }

    @Test fun stagedPromotionReusesThePreNavigationBridgeAndInitializesDisplayScaleWithoutReload() {
        val activity = source("MainActivity")
        val show = activity.substringAfter("private fun showBrowser(").substringBefore("private fun openTaskNotificationSettings")
        assertTrue(show.contains("stagedBrowser: StagedBrowser? = null"))
        assertTrue(show.contains("val browser = stagedBrowser?.browser ?: WebView(this)"))
        assertTrue(show.contains("nativeBridge = (stagedBrowser?.bridge ?: NativeBridge(this, browser, origin, bridgeState))"))
        assertTrue(show.contains("if (stagedBrowser == null) installBrowserCompatibilityShim(browser, origin)"))
        assertTrue(show.contains("else { displayScale.onDocumentReady(); nativeBridge?.injectPage(); finishConnectionAttempt() }"))
    }

    @Test fun stagedBridgeCannotHandleMessagesOrPublishItsAdapterUntilPromoted() {
        val bridge = source("NativeBridge")
        assertTrue(bridge.contains("private val activeOwner: () -> Boolean = { true }"))
        assertTrue(bridge.contains("private val available: Boolean get() = installed && activeOwner()"))
        val message = bridge.substringAfter("override fun onPostMessage(").substringBefore("installed = true")
        assertTrue(message.indexOf("if (!available || view !== webView) return") < message.indexOf("handleMessage(message.data, replyProxy)"))
        val inject = bridge.substringAfter("fun injectPage()").substringBefore("fun updateKeyboardState")
        assertTrue(inject.contains("if (!available || !GatewayUrlPolicy.isSameOrigin"))
        assertTrue(inject.indexOf("if (!available") < inject.indexOf("webView.evaluateJavascript"))
        val renewal = bridge.substringAfter("\"auth.renew\" -> activity.runOnUiThread").substringBefore("\"files.pick\" ->")
        assertTrue(renewal.contains("available && !preservingForConfiguration && expectedDocument == documentGeneration"))
        assertTrue(bridge.contains("webView.post { if (available) runCatching { call.replyProxy.postMessage(boundedBody) } }"))
    }

    @Test fun everyUnpromotedTargetFailureDisposesItsBridgeBeforeDestroyingTheWebView() {
        val activity = source("MainActivity")
        val stage = activity.substringAfter("private fun stagePlainTextTransfer").substringBefore("private fun trySameHostFallback")
        assertTrue(stage.contains("targetBridge.dispose(); parent.removeView(target); target.destroy(); parent.removeView(shield)"))
        val abort = stage.substringAfter("}, onAbort = {").substringBefore("}, onCommit = {")
        assertTrue(abort.indexOf("targetBridge.dispose()") < abort.indexOf("target.destroy()"))
        assertTrue(abort.contains("settled()"))
    }

    @Test fun microphoneGrantsBlockMigrationUntilDocumentNavigationOrTeardown() {
        val text = source("MainActivity")
        assertTrue(text.contains("audioCaptureGrantedBrowser = pending.owner"))
        assertTrue(text.contains("audioCaptureGrantedBrowser = browser"))
        assertTrue(text.contains("audioCaptureGrantedBrowser !== browser"))
        assertTrue(text.contains("audioCaptureGrantedBrowser !== source"))
        assertTrue(text.contains("if (audioCaptureGrantedBrowser === browser) audioCaptureGrantedBrowser = null"))
        assertTrue(text.contains("pendingAudioPermission = null\n        audioCaptureGrantedBrowser = null"))
    }

    @Test fun retainedPageFeedbackIsForegroundAndSourceOwnedOnly() {
        val text = source("MainActivity")
        val abort = text.substring(text.indexOf("}, onAbort = {"), text.indexOf("}, onCommit = {"))
        assertTrue(abort.contains("sameHostGate.foreground"))
        assertTrue(abort.contains("generation == restoreGeneration && webView === source"))
        assertTrue(abort.contains("R.string.same_host_migration_retained"))
        assertFalse(abort.contains("R.string.same_host_switched"))
    }

    @Test fun nativePendingWorkAndMissingPluginCannotSilentlyDiscardDraft() {
        val text = source("MainActivity")
        assertTrue(text.contains("!webFileChooser.active"))
        assertTrue(text.contains("nativeBridge?.hasPendingTransferBlockers() != true"))
        assertTrue(text.contains("if (captured == null) confirm() else connect()"))
        val auth = text.indexOf("if (onAuthenticated != null)")
        assertTrue(text.indexOf("return@installNativeSession", auth) < text.indexOf("if (deferActivation)", auth))
    }
}
