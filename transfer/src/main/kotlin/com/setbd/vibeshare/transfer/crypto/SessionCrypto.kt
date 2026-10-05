package com.setbd.vibeshare.transfer.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Session transport cryptography.
 *
 * - Ephemeral ECDH over secp256r1 to agree on a shared secret.
 * - HKDF-SHA256 (salt = pairing credential bytes) to derive an AES-256-GCM key.
 * - Every frame payload: [12B nonce][AES-GCM ciphertext+tag], AAD = frame header.
 *
 * An attacker observing the wire learns nothing without the pairing credential
 * (QR token / PIN), which is exchanged out-of-band.
 */
object SessionCrypto {

    private const val GCM_NONCE_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val KEY_INFO = "vibeshare-vtp1-session-key"

    fun generateEcdhKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        return generator.generateKeyPair()
    }

    fun encodePublicKey(publicKey: PublicKey): ByteArray = publicKey.encoded

    fun decodePublicKey(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

    /** Computes the ECDH shared secret between our private key and the peer public key. */
    fun ecdh(privateKey: java.security.PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        return agreement.generateSecret()
    }

    /**
     * Derives the AES-256 session key from the ECDH secret and the pairing credential.
     * Both sides must pass the same transcript [info] material (client nonce + host nonce).
     */
    fun deriveSessionKey(ecdhSecret: ByteArray, credential: ByteArray, transcriptInfo: ByteArray): SecretKey {
        val keyBytes = Hkdf.derive(ecdhSecret, credential, KEY_INFO.toByteArray() + transcriptInfo, 32)
        return SecretKeySpec(keyBytes, "AES")
    }

    /** Encrypts plaintext into [nonce][ciphertext+tag]. */
    fun encrypt(key: SecretKey, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(GCM_NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        return nonce + ct
    }

    /** Decrypts [nonce][ciphertext+tag]; throws AEADBadTagException on tampering. */
    fun decrypt(key: SecretKey, encrypted: ByteArray, aad: ByteArray): ByteArray {
        require(encrypted.size > GCM_NONCE_LEN) { "Encrypted payload too short" }
        val nonce = encrypted.copyOfRange(0, GCM_NONCE_LEN)
        val ct = encrypted.copyOfRange(GCM_NONCE_LEN, encrypted.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ct)
    }
}
