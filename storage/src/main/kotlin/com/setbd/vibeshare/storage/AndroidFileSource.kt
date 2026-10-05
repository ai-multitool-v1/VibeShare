package com.setbd.vibeshare.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.domain.model.TransferFile
import com.setbd.vibeshare.transfer.model.FileSource
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel

/**
 * [FileSource] over a content [Uri] (Storage Access Framework or MediaStore).
 * Reads stream through a bounded buffer; nothing is ever buffered whole.
 * Resume uses channel positioning when the provider is seekable, otherwise a
 * drain-skip (correct, slower) so behavior is always safe.
 */
class AndroidFileSource(
    private val context: Context,
    private val uri: Uri,
    private val displayName: String? = null,
    private val sizeOverride: Long? = null,
    private val mimeOverride: String? = null,
) : FileSource {

    private var pfd: android.os.ParcelFileDescriptor? = null
    private var stream: InputStream? = null
    private var channel: ReadableByteChannel? = null

    override val file: TransferFile = run {
        val resolved = queryMeta(uri)
        TransferFile(
            id = TransferFile.newId(),
            displayName = displayName ?: resolved.first ?: uri.lastPathSegment ?: "file",
            sizeBytes = sizeOverride ?: resolved.second ?: 0L,
            mimeType = mimeOverride ?: resolved.third ?: "application/octet-stream",
            sha256 = null,
        )
    }

    override fun openChannel(resumeOffset: Long): FileSource.Reader {
        val descriptor = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")
        }.getOrNull()
        if (descriptor != null) {
            val fis = java.io.FileInputStream(descriptor.fileDescriptor)
            val rawChannel = fis.channel
            val positioned = if (resumeOffset <= 0) true else runCatching {
                rawChannel.position(resumeOffset)
                rawChannel.position() == resumeOffset
            }.getOrDefault(false)
            if (positioned) {
                pfd = descriptor
                return ReaderImpl(rawChannel, fis, descriptor)
            }
            // Provider is not seekable (cloud/piped docs): fall back to drain-skip.
            runCatching { rawChannel.close() }
            runCatching { fis.close() }
            runCatching { descriptor.close() }
        }
        // Fallback: stream + drain-skip (correct, slower on resume).
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open $uri")
        stream = input
        val chan = Channels.newChannel(input)
        if (resumeOffset > 0) skip(chan, resumeOffset)
        return ReaderImpl(chan, null, null)
    }

    private fun skip(channel: ReadableByteChannel, count: Long) {
        var remaining = count
        val buffer = ByteBuffer.allocate(64 * 1024)
        while (remaining > 0) {
            buffer.clear()
            val limit = buffer.limit()
            buffer.limit(minOf(limit.toLong(), remaining).toInt())
            val read = channel.read(buffer)
            if (read <= 0) break
            remaining -= read
        }
    }

    override fun close() {
        runCatching { channel?.close() }
        runCatching { stream?.close() }
        runCatching { pfd?.close() }
        channel = null
        stream = null
        pfd = null
    }

    private fun queryMeta(uri: Uri): Triple<String?, Long?, String?> {
        var resolvedName: String? = null
        var resolvedSize: Long? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) resolvedName = cursor.getString(nameIdx)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) resolvedSize = cursor.getLong(sizeIdx)
            }
        }
        return Triple(resolvedName, resolvedSize, context.contentResolver.getType(uri))
    }

    private inner class ReaderImpl(
        private val inner: ReadableByteChannel,
        private val fis: java.io.FileInputStream?,
        private val descriptor: android.os.ParcelFileDescriptor?,
    ) : FileSource.Reader {
        override fun read(buffer: ByteBuffer): Int = inner.read(buffer)
        override fun close() {
            runCatching { inner.close() }
            runCatching { fis?.close() }
            runCatching { descriptor?.close() }
        }
    }

    companion object {
        private const val TAG = "AndroidFileSource"
    }
}
