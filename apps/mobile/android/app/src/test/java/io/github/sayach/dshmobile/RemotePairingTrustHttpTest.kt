package io.github.sayach.dshmobile

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Runs the pairing trust selection against a real local HTTPS gateway.
 *
 * A public-CA dsh1 entry may return 404 and keep the platform trust store; a CA-required dsh2
 * entry must reject that same response before sending its pairing token. Both decisions use
 * a real TLS fetch here. A server error must not be interpreted as an absent CA.
 *
 * The unit-test classpath is the Android stub jar, which has no `com.sun.net.httpserver`, so the
 * single endpoint these tests need is answered by a minimal HTTP/1.1 responder on an
 * [SSLServerSocket].
 */
class RemotePairingTrustHttpTest {
    private val instanceId = TestCertificates.instanceIdOf(TestCertificates.ingressCa)
    private val publicKey = PairingKey(instanceId, "A".repeat(43))
    private val caRequiredKey = publicKey.copy(requiresCa = true)

    @Test
    fun nativeAuthRequestsNegotiateIdentityButAnonymousBootstrapDoesNot() {
        val gateway = LoopbackGateway(TestCertificates.ingressCa, 200, nativeAuth = true)
        try {
            NativeAuthClient.fetchPairingCa(gateway.origin)
            val probe = NativeAuthClient.probe(gateway.origin, "D".repeat(43), TestCertificates.ingressCa, instanceId)
            val renew = NativeAuthClient.renew(gateway.origin, "D".repeat(43), TestCertificates.ingressCa, instanceId)
            val pair = NativeAuthClient.pair(gateway.origin, "P".repeat(43), TestCertificates.ingressCa, instanceId)
            assertEquals("f".repeat(64), probe.installationId)
            assertEquals("f".repeat(64), renew.installationId)
            assertEquals("f".repeat(64), pair.installationId)
            assertEquals("-", gateway.capabilitiesByPath["/mobile-access/ca.cer"])
            listOf("native-probe", "native-renew", "native-pair").forEach {
                assertEquals("installation-id", gateway.capabilitiesByPath["/mobile-access/auth/$it"])
            }
        } finally { gateway.close() }
    }

    @Test
    fun aPublicCaEntryWithoutCaCerKeepsThePlatformTrustStore() {
        withGateway(caCer = null) { origin ->
            val trust = selectRemoteTrust(origin, publicKey)

            // Byte-for-byte the pre-fix remote decision: null bytes plus the pairing-key instance.
            assertEquals(instanceId, trust?.second)
            assertNull(trust?.first)
        }
    }

    @Test
    fun aCaRequiredKeyRejectsA404BeforeSendingThePairingToken() {
        withGateway(caCer = null) { origin ->
            assertNull(selectRemoteTrust(origin, caRequiredKey))
        }
    }

    @Test
    fun theSelfSignedEntryPublishesItsCaAndThatCaGetsPinned() {
        withGateway(caCer = TestCertificates.ingressCa) { origin ->
            val trust = selectRemoteTrust(origin, caRequiredKey)

            assertEquals(instanceId, trust?.second)
            assertArrayEquals(TestCertificates.ingressCa, trust?.first)
        }
    }

    @Test
    fun aSubstitutedCaOnTheWireFailsTheAttemptInsteadOfDowngrading() {
        withGateway(caCer = TestCertificates.foreignCa) { origin ->
            assertNull(selectRemoteTrust(origin, caRequiredKey))
        }
    }

    @Test
    fun aServerFailureDoesNotDowngradeToThePlatformTrustStore() {
        withGateway(caCer = null, responseStatus = 503) { origin ->
            val failure = assertThrows(NativeAuthFailure::class.java) { selectRemoteTrust(origin, caRequiredKey) }
            assertEquals(NativeAuthFailureKind.SERVER_UNAVAILABLE, failure.kind)
        }
    }

    @Test
    fun theLanPathStillRequiresACa() {
        withGateway(caCer = null) { origin ->
            assertNull(PairingTrust.selectTrustAnchor(AccessMode.LAN, publicKey) { fetchCa(origin) })
        }
        withGateway(caCer = TestCertificates.ingressCa) { origin ->
            assertArrayEquals(
                TestCertificates.ingressCa,
                PairingTrust.selectTrustAnchor(AccessMode.LAN, publicKey) { fetchCa(origin) }?.first,
            )
        }
    }

    private fun selectRemoteTrust(origin: GatewayOrigin, key: PairingKey): Pair<ByteArray?, String?>? =
        PairingTrust.selectTrustAnchor(AccessMode.REMOTE, key) { fetchCa(origin) }

    /** The exact fetch `MainActivity` performs before pairing. */
    private fun fetchCa(origin: GatewayOrigin): ByteArray? = NativeAuthClient.fetchPairingCa(origin)

    /** Serve [caCer] at `/mobile-access/ca.cer`, or answer the requested error status. */
    private fun withGateway(caCer: ByteArray?, responseStatus: Int = if (caCer == null) 404 else 200, block: (GatewayOrigin) -> Unit) {
        val gateway = LoopbackGateway(caCer, responseStatus)
        try {
            block(gateway.origin)
        } finally {
            gateway.close()
        }
    }

    /** A one-endpoint TLS gateway: 200 with the configured CA, or an error status, then close. */
    private class LoopbackGateway(private val caCer: ByteArray?, private val responseStatus: Int, private val nativeAuth: Boolean = false) {
        val capabilitiesByPath = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val serverSocket: SSLServerSocket =
            serverContext().serverSocketFactory.createServerSocket(0, 4, InetAddress.getByName(LOOPBACK)) as SSLServerSocket

        val origin: GatewayOrigin = GatewayOrigin.parse("https://$LOOPBACK:${serverSocket.localPort}")!!

        private val worker = Thread({ serve() }, "loopback-gateway").apply { isDaemon = true; start() }

        fun close() {
            runCatching { serverSocket.close() }
            worker.join(SOCKET_TIMEOUT_MS.toLong())
            check(!worker.isAlive) { "loopback gateway did not stop" }
        }

        private fun serve() {
            while (true) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    return
                }
                try {
                    respond(socket)
                } catch (_: IOException) {
                    // A client that aborts this connection ends only this connection.
                } finally {
                    runCatching { socket.close() }
                }
            }
        }

        private fun respond(socket: Socket) {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = socket.getInputStream().buffered()
            val requestLine = readLine(input) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            repeat(headers["content-length"]?.toIntOrNull() ?: 0) { input.read() }
            val path = requestLine.split(' ').getOrNull(1).orEmpty()
            capabilitiesByPath[path] = headers["x-dsh-mobile-capabilities"] ?: "-"
            val isNative = nativeAuth && path.startsWith("/mobile-access/auth/native-")
            val body = if (isNative) {
                val now = System.currentTimeMillis()
                org.json.JSONObject().apply {
                    put("instanceId", TestCertificates.instanceIdOf(TestCertificates.ingressCa))
                    put("deviceId", "d".repeat(32))
                    put("installationId", "f".repeat(64))
                    if (path.endsWith("probe") || path.endsWith("pair")) put("deviceExpiresAt", now + 60_000)
                    if (!path.endsWith("probe")) {
                        put("sessionToken", "S".repeat(43)); put("csrfToken", "C".repeat(43)); put("sessionExpiresAt", now + 30_000)
                    }
                    if (path.endsWith("pair")) put("deviceToken", "D".repeat(43))
                }.toString().toByteArray()
            } else caCer?.takeIf { responseStatus == 200 && path == CA_PATH }
            val output = socket.getOutputStream()
            output.write(
                if (body == null) {
                    "HTTP/1.1 $responseStatus ${if (responseStatus == 503) "Service Unavailable" else "Not Found"}\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n"
                } else {
                    "HTTP/1.1 200 OK\r\nContent-Type: ${if (isNative) "application/json" else "application/x-x509-ca-cert"}\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                }.toByteArray(),
            )
            body?.let(output::write)
            output.flush()
        }

        private fun drainHeaders(input: InputStream) {
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) return
            }
        }

        private fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val next = input.read()
                if (next < 0) return line.takeIf { it.isNotEmpty() }?.toString()
                if (next == '\n'.code) return line.toString().removeSuffix("\r")
                line.append(next.toChar())
            }
        }

        /** A TLS context presenting the fixture leaf issued by the ingress CA for the loopback address. */
        private fun serverContext(): SSLContext {
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(TestCertificates.leafLoopback)) as X509Certificate
            val key = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(TestCertificates.leafLoopbackKey))
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setKeyEntry(KEY_ALIAS, key, EMPTY_PASSWORD, arrayOf(certificate))
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(store, EMPTY_PASSWORD) }
                .keyManagers
            return SSLContext.getInstance("TLS").apply { init(managers, null, null) }
        }

        private companion object {
            const val SOCKET_TIMEOUT_MS = 5_000
            const val LOOPBACK = "127.0.0.1"
            const val CA_PATH = "/mobile-access/ca.cer"
            const val KEY_ALIAS = "gateway"
            val EMPTY_PASSWORD = charArrayOf()
        }
    }
}
