package io.github.sayach.dshmobile

import android.os.Handler
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener

/** Memory-only transaction: the source is never released until target restoration and revalidation. */
internal class PlainTextDraftTransfer(private val handler: Handler) {
    private val commitGate = DraftTransferCommitGate()
    private var ended = false
    private var cleanup: () -> Unit = {}
    fun abort() { if (!ended) { ended = true; commitGate.abort(); cleanup() } }

    fun release(browser: WebView, snapshot: JSONObject) {
        evaluate(browser, "var p=window.__dshMobileDraftTransfer;if(p)p.release(${snapshot});return true;") {}
    }

    fun capture(source: WebView, complete: (JSONObject?) -> Unit) {
        var completed = false
        val timeout = Runnable { if (!completed) { completed = true; complete(null) } }
        handler.postDelayed(timeout, 2000)
        evaluate(source, "var p=window.__dshMobileDraftTransfer;return p&&p.capture();") { raw ->
            val snapshot = if (raw.length > 2 * 1024 * 1024) null
                else runCatching { JSONTokener(raw).nextValue() as? JSONObject }.getOrNull()
            if (completed) { if (snapshot != null) release(source, snapshot); return@evaluate }
            completed = true
            handler.removeCallbacks(timeout)
            val valid = snapshot != null && snapshot.optInt("version") == 1 &&
                snapshot.opt("token") is String && snapshot.optString("token").length in 1..256 &&
                snapshot.opt("sessionId") is String && snapshot.optString("sessionId").length in 1..1024 &&
                snapshot.optString("href") == source.url && snapshot.opt("text") is String &&
                snapshot.getString("text").toByteArray(Charsets.UTF_8).size <= 256 * 1024 &&
                snapshot.optLong("revision", -1) >= 0
            if (!valid && snapshot != null) release(source, snapshot)
            complete(snapshot.takeIf { valid })
        }
    }

    fun restore(source: WebView, target: WebView, snapshot: JSONObject, current: () -> Boolean,
                onAbort: () -> Unit, onCommit: () -> Unit) {
        val json = snapshot.toString()
        fun release(browser: WebView) { evaluate(browser, "var p=window.__dshMobileDraftTransfer;if(p)p.release($json);return true;") {} }
        cleanup = {
            var cleaned = false
            fun finishRollback() { if (!cleaned) { cleaned = true; release(source); onAbort() } }
            val rollbackDeadline = Runnable { finishRollback() }
            handler.postDelayed(rollbackDeadline, 1500)
            evaluate(target, "var p=window.__dshMobileDraftTransfer;return !!(p&&p.rollback($json));") {
                handler.removeCallbacks(rollbackDeadline)
                finishRollback()
            }
        }
        val deadline = Runnable { abort() }
        handler.postDelayed(deadline, 30_000L)
        val poll = object : Runnable {
            override fun run() {
                if (ended) return
                if (!current()) { abort(); return }
                evaluate(target, "var p=window.__dshMobileDraftTransfer;return !!(p&&p.restore($json));") { restored ->
                    if (ended) return@evaluate
                    if (!current()) { abort(); return@evaluate }
                    if (restored != "true") { handler.postDelayed(this, 100); return@evaluate }
                    if (!commitGate.targetRestored()) { abort(); return@evaluate }
                    evaluate(target, "var p=window.__dshMobileDraftTransfer;return !!(p&&p.verify($json));") verifyTarget@ { intact ->
                        if (ended) return@verifyTarget
                        if (intact != "true" || !current()) { abort(); return@verifyTarget }
                        if (!commitGate.targetVerified()) { abort(); return@verifyTarget }
                        evaluate(source, "var p=window.__dshMobileDraftTransfer;return !!(p&&p.validate($json));") finalSource@ { valid ->
                            if (ended) return@finalSource
                            if (valid != "true" || !current()) { abort(); return@finalSource }
                            if (!commitGate.sourceValidated() || !commitGate.commit(targetIntact = true, ownerCurrent = current())) { abort(); return@finalSource }
                            ended = true
                            handler.removeCallbacks(deadline)
                            release(target)
                            release(source)
                            onCommit()
                        }
                    }
                }
            }
        }
        handler.post(poll)
    }

    private fun evaluate(browser: WebView, body: String, complete: (String) -> Unit) {
        runCatching { browser.evaluateJavascript("(function(){try{$body}catch(_){return null;}})()", complete) }
            .onFailure { complete("null") }
    }
}
