package com.setbd.vibeshare.transfer

import com.setbd.vibeshare.transfer.protocol.Frame
import com.setbd.vibeshare.transfer.protocol.FrameCodec
import com.setbd.vibeshare.transfer.protocol.FrameType
import com.setbd.vibeshare.transfer.protocol.Payloads
import com.setbd.vibeshare.transfer.protocol.FileMetaPayload
import com.setbd.vibeshare.transfer.protocol.ProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Frame framing / corruption detection (spec sections 10, 13). */
class FrameCodecTest {

    @Test
    fun `frame roundtrip`() {
        val out = ByteArrayOutputStream()
        FrameCodec.write(DataOutputStream(out), Frame(FrameType.FILE_META, byteArrayOf(1, 2, 3, 4, 5)))
        val frame = FrameCodec.read(DataInputStream(ByteArrayInputStream(out.toByteArray())), 1 shl 20)!!
        assertEquals(FrameType.FILE_META, frame.type)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), frame.payload)
    }

    @Test
    fun `empty payload frame roundtrip`() {
        val out = ByteArrayOutputStream()
        FrameCodec.write(DataOutputStream(out), Frame(FrameType.PAUSE))
        val frame = FrameCodec.read(DataInputStream(ByteArrayInputStream(out.toByteArray())), 1 shl 20)!!
        assertEquals(FrameType.PAUSE, frame.type)
        assertEquals(0, frame.payload.size)
    }

    @Test
    fun `bad magic is detected as corruption`() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00, 1, 1, 0, 0, 0, 0, 0, 0))
        assertThrows(ProtocolException::class.java) {
            FrameCodec.read(DataInputStream(ByteArrayInputStream(out.toByteArray())), 1 shl 20)
        }
    }

    @Test
    fun `oversize payload is rejected`() {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.write(Frame.MAGIC)
        dos.writeByte(1)
        dos.writeByte(FrameType.CHUNK.id)
        dos.writeByte(0)
        dos.writeByte(0)
        dos.writeInt(10 * 1024 * 1024) // 10 MB > 4 MB limit
        assertThrows(ProtocolException::class.java) {
            FrameCodec.read(DataInputStream(ByteArrayInputStream(out.toByteArray())), 1 shl 22)
        }
    }

    @Test
    fun `clean eof returns null`() {
        assertNull(FrameCodec.read(DataInputStream(ByteArrayInputStream(ByteArray(0))), 1024))
    }

    @Test
    fun `payload json roundtrip`() {
        val meta = FileMetaPayload(
            fileId = "abc123", index = 2, count = 5, name = "video.mp4",
            size = 123456789, mime = "video/mp4", sha256 = "deadbeef", sessionTotalBytes = 999999,
        )
        val decoded = Payloads.decode<FileMetaPayload>(Payloads.encode(meta))
        assertEquals(meta, decoded)
    }
}
