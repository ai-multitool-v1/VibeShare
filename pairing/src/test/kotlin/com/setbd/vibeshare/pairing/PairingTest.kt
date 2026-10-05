package com.setbd.vibeshare.pairing

import com.setbd.vibeshare.domain.model.TransferMode
import com.setbd.vibeshare.pairing.qr.QrPayload
import com.setbd.vibeshare.pairing.session.SessionRegistry
import com.setbd.vibeshare.pairing.token.PairingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Pairing credential lifecycle (spec section 6): randomness, format, expiry. */
class PairingTokensTest {

    @Test
    fun `tokens are 128-bit base64url`() {
        val token = PairingTokens.generateToken()
        assertEquals(22, token.length)
        assertTrue(token.none { it == '=' || it == '+' || it == '/' })
        // 22 base64url chars = 16.5 bytes -> decodes to 16 bytes with padding implied
        assertEquals(16, Base64.getUrlDecoder().decode(token + "==").size)
    }

    @Test
    fun `tokens do not repeat`() {
        val seen = mutableSetOf<String>()
        repeat(200) { assertTrue(seen.add(PairingTokens.generateToken())) }
    }

    @Test
    fun `pins are six digits and uniformly random enough`() {
        val pins = (1..200).map { PairingTokens.generatePin() }
        pins.forEach { pin ->
            assertEquals(6, pin.length)
            assertTrue(pin.all { it in '0'..'9' })
        }
        assertTrue("pins should vary", pins.toSet().size > 50)
    }

    @Test
    fun `plausibility checks`() {
        assertTrue(PairingTokens.isPlausiblePin("482913"))
        assertFalse(PairingTokens.isPlausiblePin("48291"))
        assertFalse(PairingTokens.isPlausiblePin("48291a"))
        assertTrue(PairingTokens.isPlausibleToken(PairingTokens.generateToken()))
        assertFalse(PairingTokens.isPlausibleToken("../etc/passwd"))
    }
}

class SessionRegistryTest {

    @Test
    fun `session expires after ttl`() {
        var now = 1_000_000L
        val registry = SessionRegistry(clock = { now })
        val session = registry.createSession(TransferMode.LOCAL_WIFI, "127.0.0.1", 4747, ttlMs = 5_000, deviceId = "d", deviceName = "n")
        assertNotNull(registry.findByToken(session.token))
        now += 6_000
        assertNull("Expired session must not resolve", registry.findByToken(session.token))
        assertNull(registry.findByPin(session.pin))
    }

    @Test
    fun `token proof validates against the right session only`() {
        val registry = SessionRegistry()
        val session = registry.createSession(TransferMode.LOCAL_WIFI, null, 4747, deviceId = "d", deviceName = "n")
        val nonce = ByteArray(16) { 7 }
        val proof = com.setbd.vibeshare.transfer.crypto.Hkdf.hmac(session.token.toByteArray(), nonce)
        val nonceHex = com.setbd.vibeshare.transfer.crypto.Hkdf.toHex(nonce)
        val proofHex = com.setbd.vibeshare.transfer.crypto.Hkdf.toHex(proof)
        assertNotNull(registry.validateTokenProof(nonceHex, proofHex))
        assertNull(registry.validateTokenProof(nonceHex, "00" + proofHex.drop(1)))
        assertNull(registry.validateTokenProof(nonceHex, proofHex.reversed()))
    }

    @Test
    fun `multiple sessions coexist and revoke works`() {
        val registry = SessionRegistry()
        val a = registry.createSession(TransferMode.LOCAL_WIFI, null, 4747, deviceId = "d", deviceName = "n")
        val b = registry.createSession(TransferMode.WIFI_DIRECT, null, 4748, deviceId = "d", deviceName = "n")
        assertNotEquals(a.token, b.token)
        assertEquals(2, registry.activeSessions().size)
        registry.revoke(a.sessionId)
        assertEquals(1, registry.activeSessions().size)
        assertNull(registry.findByToken(a.token))
        assertNotNull(registry.findByToken(b.token))
    }
}

class QrPayloadTest {

    @Test
    fun `qr payload json roundtrip`() {
        val payload = QrPayload(
            sessionId = "s-abc",
            deviceId = "dev-1",
            deviceName = "Galaxy Test",
            mode = "LOCAL_WIFI",
            host = "192.168.1.7",
            port = 4747,
            token = PairingTokens.generateToken(),
            expiresAtMs = 1_723_456_789_000,
        )
        val decoded = QrPayload.fromJson(payload.encode())
        assertEquals(payload, decoded)
    }

    @Test
    fun `qr payload never contains permanent secrets`() {
        val payload = QrPayload(
            sessionId = "s-abc", deviceId = "dev-1", deviceName = "d", mode = "AUTO",
            token = PairingTokens.generateToken(), expiresAtMs = 1,
        )
        val json = payload.encode()
        assertFalse(json.contains("keystore", ignoreCase = true))
        assertFalse(json.contains("password", ignoreCase = true))
        assertTrue("Token must be session-scoped", payload.token.length <= 64)
    }

    @Test
    fun `malformed json is rejected safely`() {
        assertNull(QrPayload.fromJson("not a qr payload {"))
        assertNull(QrPayload.fromJson("{}"))
        assertNull(QrPayload.fromJson(""))
    }
}
