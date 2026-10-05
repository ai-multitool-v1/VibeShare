package com.setbd.vibeshare.transfer.model

import com.setbd.vibeshare.domain.model.TransferFile
import java.nio.ByteBuffer

/**
 * Read-side abstraction for one file offered by the sender.
 * Implemented over ContentResolver/ParcelFileDescriptor on Android and over
 * plain files in JVM tests. Streaming + chunked only: never loads a whole
 * file into RAM (spec section 7).
 */
interface FileSource {
    val file: TransferFile

    /**
     * Opens a channel positioned at [resumeOffset]. Implementations must support
     * skipping when the underlying stream is not seekable (drain-skip).
     */
    fun openChannel(resumeOffset: Long): Reader

    /** Closes any resources held for this source. */
    fun close()

    /** Sequential reader handed to the send loop. */
    interface Reader {
        /** Reads up to buffer capacity. Returns -1 at EOF. */
        fun read(buffer: ByteBuffer): Int

        fun close()
    }
}
