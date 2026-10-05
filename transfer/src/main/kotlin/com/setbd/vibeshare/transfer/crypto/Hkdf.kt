package com.setbd.vibeshare.transfer.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 5869 HKDF with SHA-256. Used to derive session keys from ECDH secrets. */
object Hkdf {

    private const val HASH_LEN = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        require(outLen <= 255 * HASH_LEN) { "HKDF output too long" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(outLen)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < outLen) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, outLen - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int = 32): ByteArray =
        expand(extract(salt, ikm), info, outLen)

    /** HMAC-SHA256 helper used for pairing proofs. */
    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            append("0123456789abcdef"[(b.toInt() shr 4) and 0xf])
            append("0123456789abcdef"[b.toInt() and 0xf])
        }
    }

    fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || hex.isEmpty()) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
