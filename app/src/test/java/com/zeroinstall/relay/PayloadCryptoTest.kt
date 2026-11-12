package com.zeroinstall.relay

import com.zeroinstall.relay.crypto.PayloadCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import javax.crypto.AEADBadTagException

class PayloadCryptoTest {

    private val crypto = PayloadCrypto(iterations = 2_000) // fast for unit tests

    @Test
    fun roundTrip() {
        val envelope = crypto.encrypt("Meet at the north gate at 6pm", "correct horse".toCharArray(), "alice")
        assertFalse(envelope.contains("north gate"))
        assertEquals(
            "Meet at the north gate at 6pm",
            crypto.decrypt(envelope, "correct horse".toCharArray(), "alice"),
        )
    }

    @Test
    fun freshSaltAndIvEachTime() {
        val a = crypto.encrypt("same", "secret123".toCharArray(), "bob")
        val b = crypto.encrypt("same", "secret123".toCharArray(), "bob")
        assertNotEquals(a, b)
    }

    @Test(expected = AEADBadTagException::class)
    fun wrongSecretFails() {
        val envelope = crypto.encrypt("hello", "secret123".toCharArray(), "bob")
        crypto.decrypt(envelope, "wrong-secret".toCharArray(), "bob")
    }

    @Test(expected = AEADBadTagException::class)
    fun recipientIsAuthenticated() {
        val envelope = crypto.encrypt("hello", "secret123".toCharArray(), "bob")
        crypto.decrypt(envelope, "secret123".toCharArray(), "mallory")
    }
}
