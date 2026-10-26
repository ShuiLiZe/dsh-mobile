package io.github.sayach.dshmobile

/** Keep the exact HTTPS write URL while serializing the host-only cookie scope independently of its port. */
internal data class NativeSessionCookieWrite(val origin: String, val value: String)

/** Host-only Session aliases installed on the paired HTTPS origin. */
internal fun nativeSessionCookies(session: NativeSession): List<String> {
    val sessionValue = "=${session.sessionToken}; Path=/; Secure; HttpOnly; SameSite=Strict"
    val csrfValue = "=${session.csrfToken}; Path=/; Secure; SameSite=Strict"
    return listOf(
        "dsh_ma_session$sessionValue",
        "dsh_ma_csrf$csrfValue",
        "__Host-dsh_ma_session$sessionValue",
        "__Host-dsh_ma_csrf$csrfValue",
    )
}
