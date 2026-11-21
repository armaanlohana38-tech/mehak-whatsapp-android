package com.armaanlohana38.mehakwhatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechErrorPolicyTest {

    @Test
    fun backoffFollowsThreeStepSequence() {
        assertEquals(300L, SpeechErrorPolicy.backoffFor(0))
        assertEquals(600L, SpeechErrorPolicy.backoffFor(1))
        assertEquals(1200L, SpeechErrorPolicy.backoffFor(2))
    }

    @Test
    fun backoffClampsOutOfRangeAttempts() {
        assertEquals(300L, SpeechErrorPolicy.backoffFor(-5))
        assertEquals(1200L, SpeechErrorPolicy.backoffFor(3))
        assertEquals(1200L, SpeechErrorPolicy.backoffFor(99))
    }

    @Test
    fun maxRetriesIsThree() {
        assertEquals(3, SpeechErrorPolicy.MAX_RETRIES)
    }

    @Test
    fun retryableCodes() {
        val retryable = intArrayOf(11, 6, 7, 8, 1, 2, 4)
        for (c in retryable) {
            assertTrue("code $c should retry", SpeechErrorPolicy.shouldRetry(c))
        }
    }

    @Test
    fun nonRetryableCodes() {
        val no = intArrayOf(5, 9, 3, 12, 13, 14, 0, 99)
        for (c in no) {
            assertFalse("code $c should not retry", SpeechErrorPolicy.shouldRetry(c))
        }
    }

    @Test
    fun busyNeedsCancelFirst() {
        assertTrue(SpeechErrorPolicy.needsCancelFirst(8))
        assertFalse(SpeechErrorPolicy.needsCancelFirst(11))
    }

    @Test
    fun clientNeedsRecreate() {
        assertTrue(SpeechErrorPolicy.needsRecreate(5))
        assertFalse(SpeechErrorPolicy.needsRecreate(8))
    }

    @Test
    fun permissionCodeDetected() {
        assertTrue(SpeechErrorPolicy.needsPermission(9))
        assertFalse(SpeechErrorPolicy.needsPermission(11))
    }

    @Test
    fun disconnectCodeDetected() {
        assertTrue(SpeechErrorPolicy.isDisconnect(11))
        assertFalse(SpeechErrorPolicy.isDisconnect(7))
    }

    @Test
    fun everyKnownCodeHasReadableMessage() {
        val codes = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 14)
        for (c in codes) {
            val m = SpeechErrorPolicy.messageFor(c)
            assertTrue("code $c message too short", m.length > 15)
            assertFalse("code $c message is just a number", m.trim() == c.toString())
        }
    }

    @Test
    fun unknownCodeStillGetsReadableMessage() {
        val m = SpeechErrorPolicy.messageFor(77)
        assertTrue(m.contains("77"))
        assertTrue(m.length > 15)
    }

    @Test
    fun disconnectMessageMentionsConnection() {
        val m = SpeechErrorPolicy.messageFor(11)
        assertTrue(m.contains("connection", ignoreCase = true))
    }

    @Test
    fun noServiceMessageMentionsGoogle() {
        val m = SpeechErrorPolicy.messageFor(14)
        assertTrue(m.contains("Google", ignoreCase = true))
    }

    @Test
    fun escalationMentionsThreeAndGoogle() {
        val m = SpeechErrorPolicy.escalationMessage()
        assertTrue(m.contains("3"))
        assertTrue(m.contains("Google", ignoreCase = true))
    }
}
