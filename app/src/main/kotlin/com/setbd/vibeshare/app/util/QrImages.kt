package com.setbd.vibeshare.app.util

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** QR rendering for the Receive screen via the maintained zxing core. */
object QrImages {

    fun render(text: String, size: Int = 640): ImageBitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap.asImageBitmap()
    }.getOrNull()
}

/** Small state colors used across screens. */
object StateColors {
    val ok = Color(0xFF3AF0B6)
    val busy = Color(0xFF22E4FF)
    val warn = Color(0xFFFFC24B)
    val error = Color(0xFFFF5C7A)
}
