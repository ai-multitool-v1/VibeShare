package com.setbd.vibeshare.transfer

import com.setbd.vibeshare.transfer.crypto.Hkdf
import com.setbd.vibeshare.transfer.crypto.SessionCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

/** Spec section 13: modern cryptographic primitives, tamper detection. */
class CryptoTest {

    @Test
    fun `hkdf derivation is deterministic and key-length correct`() {
        val ikm = "shared-secret-material".toByteArray()
        val a = Hkdf.derive(ikm, "salt".toByteArray(), "info".toByteArray(), 32)
        val b = Hkdf.derive(ikm, "salt".toByteArray(), "info".toByteArray(), 32)
        assertEquals(32, a.size)
        assertArrayEquals(a, b)
        assertNotEquals(a, Hkdf.derive(ikm, "other-salt".toByteArray(), "info".toByteArray(), 32))
        assertNotEquals(a, Hkdf.derive(ikm, "salt".toByteArray(), "other-info".toByteArray(), 32))
    }

    @Test
    fun `ecdh both sides derive the same session key`() {
        val alice = SessionCrypto.generateEcdhKeyPair()
        val bob = SessionCrypto.generateEcdhKeyPair()
        val secretA = SessionCrypto.ecdh(alice.private, SessionCrypto.decodePublicKey(SessionCrypto.encodePublicKey(bob.public)))
        val secretB = SessionCrypto.ecdh(bob.private, SessionCrypto.decodePublicKey(SessionCrypto.encodePublicKey(alice.public)))
        assertArrayEquals(secretA, secretB)
        val keyA = SessionCrypto.deriveSessionKey(secretA, "token-123".toByteArray(), "transcript".toByteArray())
        val keyB = SessionCrypto.deriveSessionKey(secretB, "token-123".toByteArray(), "transcript".toByteArray())
        assertEquals(keyA.encoded.toList(), keyB.encoded.toList())
    }

    @Test
    fun `aes-gcm roundtrip with header aad`() {
        val key = SessionCrypto.deriveSessionKey(
            ByteArray(32) { it.toByte() }, "cred".toByteArray(), "tx".toByteArray()
        )
        val plaintext = "frame-payload-0123456789".toByteArray()
        val aad = ByteArray(12) { 0x56 }
        val encrypted = SessionCrypto.encrypt(key, plaintext, aad)
        assertNotEquals(plaintext.toList(), encrypted.toList())
        assertArrayEquals(plaintext, SessionCrypto.decrypt(key, encrypted, aad))
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val key = SessionCrypto.deriveSessionKey(
            ByteArray(32) { (it * 7).toByte() }, "cred".toByteArray(), "tx".toByteArray()
        )
        val encrypted = SessionCrypto.encrypt(key, "payload".toByteArray(), ByteArray(12))
        encrypted[encrypted.size - 1] = (encrypted.last().toInt() xor 0x1).toByte()
        var rejected = false
        try {
            SessionCrypto.decrypt(key, encrypted, ByteArray(12))
        } catch (expected: AEADBadTagException) {
            rejected = true
        }
        assertTrue("Tampered payload must be rejected", rejected)
    }

    @Test
    fun `wrong aad is rejected`() {
        val key = SessionCrypto.deriveSessionKey(ByteArray(32), "c".toByteArray(), "t".toByteArray())
        val encrypted = SessionCrypto.encrypt(key, "payload".toByteArray(), ByteArray(12) { 1 })
        var rejected = false
        try {
            SessionCrypto.decrypt(key, encrypted, ByteArray(12) { 2 })
        } catch (expected: AEADBadTagException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun `hex roundtrip`() {
        val bytes = ByteArray(48) { (it * 31).toByte() }
        assertEquals(96, Hkdf.toHex(bytes).length)
        assertFalse(Hkdf.toHex(bytes).contains('g', ignoreCase = false))
    }
}
