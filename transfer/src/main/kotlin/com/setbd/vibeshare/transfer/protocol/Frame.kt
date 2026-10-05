package com.setbd.vibeshare.transfer.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * One protocol frame.
 *
 * Wire format (12-byte header):
 * [4B magic "VBSH"][1B version][1B type][1B flags][1B reserved][4B payload length][payload]
 *
 * When encrypted, payload = [12B nonce][AES-256-GCM ciphertext+tag],
 * authenticated against the 12-byte header as AAD.
 */
class Frame(
    val type: FrameType,
    var payload: ByteArray = EMPTY,
) {
    var encrypted: Boolean = false

    companion object {
        val MAGIC: ByteArray = byteArrayOf(0x56, 0x42, 0x53, 0x48) // "VBSH"
        const val VERSION: Int = 1
        const val FLAG_ENCRYPTED: Int = 0x01
        const val HEADER_SIZE: Int = 12
        val EMPTY: ByteArray = ByteArray(0)
    }
}

object FrameCodec {

    /** Writes one frame. Single-writer assumption: synchronize at the connection level. */
    fun write(out: DataOutputStream, frame: Frame) {
        val flags = if (frame.encrypted) Frame.FLAG_ENCRYPTED else 0
        out.write(Frame.MAGIC)
        out.writeByte(Frame.VERSION)
        out.writeByte(frame.type.id)
        out.writeByte(flags)
        out.writeByte(0)
        out.writeInt(frame.payload.size)
        out.write(frame.payload)
        out.flush()
    }

    /**
     * Reads one frame, or null on clean EOF before any byte of a frame.
     * Throws [ProtocolException] on corruption (bad magic, bad version, oversize payload)
     * and IOException on mid-frame connection loss.
     */
    fun read(input: DataInputStream, maxPayloadBytes: Int): Frame? {
        val magic = ByteArray(4)
        val first = try {
            input.readFully(magic)
            true
        } catch (eof: EOFException) {
            return null
        } catch (io: IOException) {
            return null
        }
        require(first)
        if (!magic.contentEquals(Frame.MAGIC)) {
            throw ProtocolException("Bad frame magic: ${magic.joinToString(" ") { "%02x".format(it) }}")
        }
        val version = input.readUnsignedByte()
        if (version != Frame.VERSION) {
            throw ProtocolException("Unsupported protocol version $version")
        }
        val typeId = input.readUnsignedByte()
        val flags = input.readUnsignedByte()
        input.readUnsignedByte() // reserved
        val length = input.readInt()
        if (length < 0 || length > maxPayloadBytes) {
            throw ProtocolException("Invalid payload length $length")
        }
        val type = FrameType.fromId(typeId)
        val payload = if (length > 0) ByteArray(length).also { input.readFully(it) } else Frame.EMPTY
        val frame = Frame(type, payload)
        frame.encrypted = (flags and Frame.FLAG_ENCRYPTED) != 0
        return frame
    }
}
