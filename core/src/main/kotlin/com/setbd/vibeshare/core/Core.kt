package com.setbd.vibeshare.core

import java.security.SecureRandom

/** Protocol / product wide constants. */
object Constants {
    /** Wire protocol version. Bump on incompatible protocol changes. */
    const val PROTOCOL_VERSION: Int = 1

    /** Default TCP port for the VibeShare transfer protocol (VTP). */
    const val DEFAULT_PORT: Int = 4747

    /** NSD/mDNS service type advertised and browsed by VibeShare. */
    const val NSD_SERVICE_TYPE: String = "_vibeshare._tcp."

    /** Default chunk size for streaming transfers. */
    const val DEFAULT_CHUNK_SIZE_BYTES: Int = 256 * 1024

    /** Chunk size bounds enforced by the engine (protects both peers). */
    const val MIN_CHUNK_SIZE_BYTES: Int = 16 * 1024
    const val MAX_CHUNK_SIZE_BYTES: Int = 1024 * 1024

    /** Maximum accepted frame payload (guards against malicious length headers). */
    const val MAX_FRAME_PAYLOAD_BYTES: Int = 4 * 1024 * 1024

    /** Default pairing token / PIN lifetime. */
    const val TOKEN_TTL_MS: Long = 5 * 60 * 1000L

    /** Socket connect timeout. */
    const val CONNECT_TIMEOUT_MS: Int = 8_000

    /** Idle read timeout on sockets (heartbeats keep sessions alive). */
    const val SOCKET_READ_TIMEOUT_MS: Int = 15_000

    /** Number of chunks allowed in flight before the sender waits for acks. */
    const val IN_FLIGHT_CHUNK_WINDOW: Int = 16

    /** Temp extension for in-flight received files, finalized atomically after verification. */
    const val TEMP_PART_SUFFIX: String = ".vibeshare_part"
}

/** Random identifier helpers. */
object Ids {
    private val random = SecureRandom()
    private val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun shortId(length: Int = 12): String = buildString(length) {
        repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
    }
}

/** Byte-size / speed / duration formatting shared by UI and logs. */
object Formats {
    fun bytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024L * 1024 -> "%.1f KB".format(value / 1024.0)
        value < 1024L * 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024))
        value < 1024L * 1024 * 1024 * 1024 -> "%.2f GB".format(value / (1024.0 * 1024 * 1024))
        else -> "%.2f TB".format(value / (1024.0 * 1024 * 1024 * 1024))
    }

    /** Human readable transfer speed, e.g. "67.4 MB/s". */
    fun speed(bytesPerSecond: Long): String = when {
        bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
        bytesPerSecond < 1024L * 1024 -> "%.1f KB/s".format(bytesPerSecond / 1024.0)
        bytesPerSecond < 1024L * 1024 * 1024 -> "%.1f MB/s".format(bytesPerSecond / (1024.0 * 1024))
        else -> "%.1f GB/s".format(bytesPerSecond / (1024.0 * 1024 * 1024))
    }

    /** Human readable ETA, e.g. "6 sec remaining", "2 min remaining". */
    fun eta(secondsRemaining: Long): String = when {
        secondsRemaining < 0 -> "calculating…"
        secondsRemaining < 5 -> "a few seconds"
        secondsRemaining < 60 -> "$secondsRemaining sec remaining"
        secondsRemaining < 3600 -> "${secondsRemaining / 60} min remaining"
        else -> "${secondsRemaining / 3600} h ${"%02d".format((secondsRemaining % 3600) / 60)} min remaining"
    }

    fun duration(millis: Long): String {
        val s = millis / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
