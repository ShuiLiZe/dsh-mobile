package io.github.sayach.dshmobile

/** UI-owned bridge waiter; network work remains owned by the existing restore controllers. */
internal class NativeSessionRenewalGate {
    class Request internal constructor(val requestId: String, internal val complete: (String?) -> Unit)

    private var current: Request? = null
    val pending: Boolean get() = current != null

    /** Refuse duplicate admission instead of starting a second credential renewal. */
    fun begin(requestId: String, complete: (String?) -> Unit): Request? {
        if (current != null) return null
        return Request(requestId, complete).also { current = it }
    }

    fun isCurrent(request: Request): Boolean = current === request

    /** Clear ownership before replying, so a reply cannot complete a successor. */
    fun finish(request: Request, errorCode: String? = null): Boolean {
        if (current !== request) return false
        current = null
        request.complete(errorCode)
        return true
    }

    /** A cancellation from an old page cannot cancel a newer page's request. */
    fun cancel(requestId: String? = null, errorCode: String = "cancelled"): Boolean {
        val request = current ?: return false
        if (requestId != null && request.requestId != requestId) return false
        return finish(request, errorCode)
    }
}
