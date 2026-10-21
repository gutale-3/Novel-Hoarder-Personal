package com.example.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Decodes cover images at a small size for notifications and widgets (full covers are large). */
object CoverBitmaps {
    fun decodeDownsampled(file: File, maxSize: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }
}
