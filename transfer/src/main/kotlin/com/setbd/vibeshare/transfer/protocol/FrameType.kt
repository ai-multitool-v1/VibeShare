package com.setbd.vibeshare.transfer.protocol

/** VTP (VibeShare Transfer Protocol) frame types. */
enum class FrameType(val id: Int) {
    /** First frame on the wire: device identity, no secrets. */
    HELLO(1),

    /** Client pairing request: credential proof + ECDH public key. */
    PAIR_INIT(2),

    /** Host accepts pairing: nonce + ECDH public key. Encryption starts after this. */
    PAIR_OK(3),

    /** Host rejects pairing. */
    PAIR_REJECT(4),

    /** Sender announces a file (metadata). Receiver answers with FILE_ACK / FILE_REJECT. */
    FILE_META(5),

    /** Receiver approves a file; carries resume offset. */
    FILE_ACK(6),

    /** Receiver refuses a file (storage, policy, duplicate skip). */
    FILE_REJECT(7),

    /** Raw file chunk. Payload: [2B idLen][id utf8][chunk bytes]. */
    CHUNK(8),

    /** Receiver confirms bytes persisted for a file. */
    CHUNK_ACK(9),

    /** Sender finished a file; carries declared sha-256 for verification. */
    FILE_DONE(10),

    /** Session finished successfully. */
    TRANSFER_COMPLETE(11),

    /** Pause sending (TCP backpressure handles the rest). */
    PAUSE(12),

    /** Resume sending. */
    RESUME(13),

    /** Cancel the whole session. */
    CANCEL(14),

    /** Keepalive. */
    HEARTBEAT(15),

    /** Error report between peers. */
    ERROR(16),

    /** Graceful goodbye before closing. */
    SHUTDOWN(17);

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: Int): FrameType = byId[id]
            ?: throw ProtocolException("Unknown frame type $id")
    }
}

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
