package com.amneziaguard.feature.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Decodes a QR code from an image the user picked from the gallery. */
object QrDecoder {

    fun decodeFromUri(context: Context, uri: Uri): String? {
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return null
        return decode(bitmap)
    }

    private fun decode(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        return runCatching {
            MultiFormatReader().decode(binary, hints).text
        }.getOrNull()
    }
}
