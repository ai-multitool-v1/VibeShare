package com.setbd.vibeshare.pairing.token

import java.security.SecureRandom
import java.util.Base64

/**
 * Short-lived pairing credentials (spec section 6).
 * Tokens are 128-bit random values encoded base64url; PINs are 6 digits.
 * Neither carries any permanent secret: both expire and are registry-scoped.
 */
object PairingTokens {

    private val random = SecureRandom()

    /** 128-bit random token, base64url without padding (~22 chars). */
    fun generateToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Random 6-digit PIN with no leading-zero bias (uniform 000000-999999). */
    fun generatePin(): String = "%06d".format(random.nextInt(1_000_000))

    /** Verifies a PIN format without leaking timing details beyond length. */
    fun isPlausiblePin(pin: String): Boolean =
        pin.length == 6 && pin.all { it in '0'..'9' }

    fun isPlausibleToken(token: String): Boolean =
        token.length in 16..64 && token.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_" }
}
