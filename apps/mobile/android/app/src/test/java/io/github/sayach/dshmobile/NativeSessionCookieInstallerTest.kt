package io.github.sayach.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Held platform callbacks distinguish cancelled logical work from physically settled cookie writes. */
class NativeSessionCookieInstallerTest {
    private class Fixture {
        data class Write(val origin: String, val cookie: String, val callback: (Boolean) -> Unit)
        val writes = ArrayDeque<Write>()
        val stored = mutableMapOf<String, String>()
        var flushed = 0
        val installer = NativeSessionCookieInstaller<String, String>(
            write = { origin, cookie, callback -> writes.addLast(Write(origin, cookie, callback)) },
            flush = { flushed++ },
        )
        fun commitNext(accepted: Boolean = true): Write {
            val write = writes.removeFirst()
            if (accepted) stored["${write.origin}/${write.cookie.substringBefore('=')}"] = write.cookie.substringAfter('=').substringBefore(';')
            return write
        }
        fun settle(accepted: Boolean = true): Write {
            val write = commitNext(accepted)
            write.callback(accepted)
            return write
        }
    }

    private fun sessionCookies(tag: String): List<String> = nativeSessionCookies(NativeSession(
        origin = requireNotNull(GatewayOrigin.parse("https://computer.example")),
        instanceId = "a".repeat(64), deviceId = "b".repeat(32), deviceToken = null, deviceExpiresAt = null,
        sessionToken = "session-$tag", csrfToken = "csrf-$tag", sessionExpiresAt = 1_900_000_000_000L,
    ))

    private fun expectedCookies(host: String, cookies: List<String>): Map<String, String> = cookies.associate {
        "$host/${it.substringBefore('=')}" to it.substringAfter('=').substringBefore(';')
    }

    @Test fun aStartedCancelledBatchWithoutASuccessorLeavesMatchingCookiesAfterStopConnectionRecoveryOrActivityDisposal() {
        for (reason in listOf("user stop", "connection already connected", "Activity disposed")) {
            val fixture = Fixture()
            fixture.stored.putAll(expectedCookies("computer", sessionCookies("old")))
            val currentCookies = sessionCookies("new")
            var current = true
            var completions = 0
            var failures = 0
            val replies = mutableListOf<String?>()
            val waiter = NativeSessionRenewalGate()
            val request = requireNotNull(waiter.begin(reason, replies::add))
            fixture.installer.install("computer", currentCookies, { current && waiter.isCurrent(request) }, { failures++ }) {
                completions++
                waiter.finish(request)
            }
            fixture.settle(); fixture.settle()
            val protectedSession = fixture.commitNext()
            assertTrue(protectedSession.cookie.startsWith("__Host-dsh_ma_session="))
            assertEquals("session-new", fixture.stored["computer/__Host-dsh_ma_session"])
            assertEquals("csrf-old", fixture.stored["computer/__Host-dsh_ma_csrf"])
            current = false
            waiter.cancel()
            protectedSession.callback(true)
            assertTrue("$reason must still finish the already-started physical commit", fixture.writes.single().cookie.startsWith("__Host-dsh_ma_csrf="))
            fixture.settle()
            assertEquals("$reason must leave the complete Session and CSRF aliases consistent", expectedCookies("computer", currentCookies), fixture.stored)
            assertEquals(1, fixture.flushed)
            assertEquals(0, completions)
            assertEquals(0, failures)
            assertEquals(listOf("cancelled"), replies)
            protectedSession.callback(true)
            assertTrue(fixture.writes.isEmpty())
            assertEquals(1, fixture.flushed)
        }
    }

    @Test fun cancellationKeepsTheOldPlatformSlotUntilItsCallbackBeforeInstallingTheNewCompleteSession() {
        val fixture = Fixture()
        var oldCurrent = true
        var oldCompleted = 0
        var nextCompleted = 0
        val oldCookies = listOf("session=old", "csrf=old", "host-session=old", "host-csrf=old")
        val nextCookies = oldCookies.map { it.replace("old", "next") }
        fixture.installer.install("computer", oldCookies, { oldCurrent }, { throw AssertionError("Old failure") }) { oldCompleted++ }
        assertEquals(1, fixture.writes.size)
        oldCurrent = false
        // A new Activity/document uses the same Application-owned installer.
        fixture.installer.install("computer", nextCookies, { true }, { throw AssertionError("Next failure") }) { nextCompleted++ }
        assertEquals("A successor cannot overtake a platform-accepted write", 1, fixture.writes.size)
        val oldWrite = fixture.settle()
        assertEquals("csrf=old", fixture.writes.single().cookie)
        assertEquals(0, oldCompleted)
        oldWrite.callback(true)
        assertEquals("Duplicate platform callbacks cannot advance physical cleanup twice", 1, fixture.writes.size)
        repeat(oldCookies.size - 1) { fixture.settle() }
        assertEquals("A successor starts only after the cancelled batch's complete commit", "session=next", fixture.writes.single().cookie)
        repeat(nextCookies.size) { fixture.settle() }
        assertEquals(nextCookies.map { it.substringAfter('=') }.toSet(), fixture.stored.values.toSet())
        assertEquals(4, fixture.stored.size)
        assertEquals(0, oldCompleted)
        assertEquals(1, nextCompleted)
        assertEquals(2, fixture.flushed)
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun anEndedNetworkWorkerDoesNotReleaseTheCookieOwnerOrReplyBeforeItsLastAlias() {
        val fixture = Fixture()
        val logical = RestoreAttemptGate()
        val bridge = NativeSessionRenewalGate()
        val replies = mutableListOf<String?>()
        val request = requireNotNull(bridge.begin("first", replies::add))
        val generation = logical.begin()
        assertTrue(logical.claimRestore(generation))
        fixture.installer.install("computer", listOf("session=a", "csrf=a"),
            { logical.isCurrent(generation) && bridge.isCurrent(request) }, { throw AssertionError("Cookie failure") }) {
            logical.completeRestore(generation)
            bridge.finish(request)
        }
        assertTrue(logical.restorePending)
        assertTrue(bridge.pending)
        assertFalse(logical.claimRestore(generation))
        assertTrue(replies.isEmpty())
        fixture.settle()
        assertTrue(replies.isEmpty())
        fixture.settle()
        assertEquals(listOf<String?>(null), replies)
        assertFalse(logical.restorePending)
        assertFalse(bridge.pending)
    }

    @Test fun cancellationDuringTheSecondAliasFinishesTheOldBatchAndSkipsWithdrawnQueuedDocuments() {
        val fixture = Fixture()
        var oldCurrent = true
        var waitingCurrent = true
        var completed = 0
        fixture.installer.install("computer", listOf("session=old", "csrf=old", "host=old"), { oldCurrent }, {}) { throw AssertionError("Old completion") }
        fixture.settle()
        assertEquals("csrf=old", fixture.writes.single().cookie)
        fixture.installer.install("computer", listOf("session=withdrawn"), { waitingCurrent }, {}) { throw AssertionError("Withdrawn completion") }
        waitingCurrent = false
        fixture.installer.install("computer", listOf("session=current", "csrf=current", "host=current"), { true }, {}) { completed++ }
        oldCurrent = false
        fixture.settle()
        assertEquals("host=old", fixture.writes.single().cookie)
        fixture.settle()
        assertEquals("session=current", fixture.writes.single().cookie)
        repeat(3) { fixture.settle() }
        assertEquals(setOf("current"), fixture.stored.values.toSet())
        assertEquals(1, completed)
    }

    @Test fun anotherOriginCanWriteWhileTheFirstOriginsPlatformCallbackIsHeld() {
        val fixture = Fixture()
        fixture.installer.install("first", listOf("session=first"), { true }, {}) {}
        fixture.installer.install("second", listOf("session=second"), { true }, {}) {}
        assertEquals(listOf("first", "second"), fixture.writes.map { it.origin })
        fixture.settle(); fixture.settle()
        assertEquals(2, fixture.flushed)
    }

    @Test fun differentPortsOnTheSameHostShareThePhysicalCookieSlot() {
        val first = requireNotNull(GatewayOrigin.parse("https://computer.example:3443"))
        val next = requireNotNull(GatewayOrigin.parse("https://computer.example:4443"))
        assertEquals(first.host, next.host)
        val writes = ArrayDeque<Pair<NativeSessionCookieWrite, (Boolean) -> Unit>>()
        val stored = mutableMapOf<String, String>()
        var firstCurrent = true
        var completed = 0
        val installer = NativeSessionCookieInstaller<String, NativeSessionCookieWrite>(
            write = { _, cookie, done -> writes.addLast(cookie to done) }, flush = {},
        )
        installer.install(first.host, listOf(NativeSessionCookieWrite(first.serialized, "session=old")),
            { firstCurrent }, {}) { throw AssertionError("Withdrawn Activity completion") }
        firstCurrent = false
        installer.install(next.host, listOf("session=new", "csrf=new").map { NativeSessionCookieWrite(next.serialized, it) },
            { true }, {}) { completed++ }
        assertEquals(1, writes.size)
        assertEquals(first.serialized, writes.first().first.origin)
        fun settle() {
            val (cookie, done) = writes.removeFirst()
            stored[cookie.value.substringBefore('=')] = cookie.value.substringAfter('=')
            done(true)
        }
        settle()
        assertEquals(next.serialized, writes.single().first.origin)
        settle(); settle()
        assertEquals(mapOf("session" to "new", "csrf" to "new"), stored)
        assertEquals(1, completed)
    }

    @Test fun rejectionReleasesTheSlotWithoutSendingRemainingAliasesAndAQueuedRetryCanComplete() {
        val fixture = Fixture()
        var failures = 0
        var completed = 0
        fixture.installer.install("computer", listOf("session=bad", "csrf=bad"), { true }, { failures++ }) { throw AssertionError("Rejected completion") }
        fixture.installer.install("computer", listOf("session=good"), { true }, { throw AssertionError("Retry failure") }) { completed++ }
        fixture.settle(false)
        assertEquals(1, failures)
        assertEquals("session=good", fixture.writes.single().cookie)
        fixture.settle()
        assertEquals(1, completed)
    }

    @Test fun aPartialPlatformFailureReportsFailureWithoutRetryOrSuccessAndAnExplicitSuccessorRepairsTheWholeSet() {
        val fixture = Fixture()
        fixture.stored.putAll(expectedCookies("computer", sessionCookies("old")))
        var failures = 0
        var completed = 0
        fixture.installer.install("computer", sessionCookies("failed"), { true }, { failures++ }) { completed++ }
        repeat(3) { fixture.settle() }
        fixture.settle(false)
        assertEquals(1, failures)
        assertEquals(0, completed)
        assertEquals(0, fixture.flushed)
        assertTrue("A rejected write is not silently retried", fixture.writes.isEmpty())
        assertEquals("session-failed", fixture.stored["computer/__Host-dsh_ma_session"])
        assertEquals("csrf-old", fixture.stored["computer/__Host-dsh_ma_csrf"])
        val repaired = sessionCookies("repaired")
        fixture.installer.install("computer", repaired, { true }, { failures++ }) { completed++ }
        repeat(repaired.size) { fixture.settle() }
        assertEquals(expectedCookies("computer", repaired), fixture.stored)
        assertEquals(1, failures)
        assertEquals(1, completed)
        assertEquals(1, fixture.flushed)
    }

    @Test fun synchronousPlatformAndFlushFailuresEndTheOwnerOnceAndDoNotPoisonTheInstaller() {
        var failures = 0
        var completions = 0
        var failWrite = true
        var failFlush = true
        val installer = NativeSessionCookieInstaller<String, String>(
            write = { _, _, done -> if (failWrite) throw IllegalStateException("Owned failure") else done(true) },
            flush = { if (failFlush) throw IllegalStateException("Owned flush failure") },
        )
        fun install() = installer.install("computer", listOf("session=value"), { true }, { failures++ }) { completions++ }
        install()
        assertEquals(1, failures)
        failWrite = false
        install()
        assertEquals(2, failures)
        failFlush = false
        install()
        assertEquals(2, failures)
        assertEquals(1, completions)
    }
}
