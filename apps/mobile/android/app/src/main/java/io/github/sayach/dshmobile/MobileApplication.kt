package io.github.sayach.dshmobile

import android.app.Application
import android.webkit.CookieManager

/** Owns pending platform cookie writes across Activity replacement. */
internal class MobileApplication : Application() {
    val sessionCookieInstaller = NativeSessionCookieInstaller<String, NativeSessionCookieWrite>(
        write = { _, cookie, done -> CookieManager.getInstance().setCookie(cookie.origin, cookie.value) { done(it) } },
        flush = { CookieManager.getInstance().flush() },
    )
}
