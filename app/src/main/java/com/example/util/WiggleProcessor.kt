package com.example.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

object WiggleProcessor {
    private const val TAG = "WiggleProcessor"

    /**
     * Crop and resize two bitmaps to be exactly the same size, centering both.
     */
    fun alignBitmaps(bitmapA: Bitmap, bitmapB: Bitmap): Pair<Bitmap, Bitmap> {
        val targetWidth = minOf(bitmapA.width, bitmapB.width)
        val targetHeight = minOf(bitmapA.height, bitmapB.height)

        Log.d(TAG, "Aligning bitmaps to match sizing: ${targetWidth}x${targetHeight}")

        val alignedA = centerCrop(bitmapA, targetWidth, targetHeight)
        val alignedB = centerCrop(bitmapB, targetWidth, targetHeight)

        return Pair(alignedA, alignedB)
    }

    private fun centerCrop(src: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (src.width == targetWidth && src.height == targetHeight) return src

        val xOffset = (src.width - targetWidth) / 2
        val yOffset = (src.height - targetHeight) / 2

        val cropped = Bitmap.createBitmap(src, xOffset, yOffset, targetWidth, targetHeight)
        if (cropped != src && !src.isRecycled) {
            // Do not immediately recycle src as it might be used elsewhere, 
            // but log if we generated a new instance.
        }
        return cropped
    }

    /**
     * Blends two bitmaps.
     * blendFactor: 0.0f = entirely A, 1.0f = entirely B. Since we want an in-between frame, we use 0.5f.
     * offsetX/offsetY: can be used to shift the second bitmap relative to the first to calibrate parallax alignment.
     */
    fun blendBitmaps(
        bitmapA: Bitmap,
        bitmapB: Bitmap,
        blendFactor: Float = 0.5f,
        offsetX: Float = 0f,
        offsetY: Float = 0f
    ): Bitmap {
        val (finalA, finalB) = alignBitmaps(bitmapA, bitmapB)

        val width = finalA.width
        val height = finalA.height

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        // 1. Draw original base background (A) with appropriate opacity
        paint.alpha = ((1f - blendFactor) * 255).toInt()
        canvas.drawBitmap(finalA, 0f, 0f, paint)

        // 2. Blend/overlay camera B with appropriate opacity and alignment offsets
        paint.alpha = (blendFactor * 255).toInt()
        canvas.drawBitmap(finalB, offsetX, offsetY, paint)

        return result
    }

    /**
     * Save a bitmap to internal app directories for stable local retrieval.
     */
    fun saveToInternalFiles(context: Context, bitmap: Bitmap, fileName: String): String? {
        return try {
            val directory = File(context.filesDir, "wiggle_captures")
            if (!directory.exists()) {
                directory.mkdirs()
            }
            val file = File(directory, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            Log.d(TAG, "Saved to internal storage: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save locally", e)
            null
        }
    }

    /**
     * Save a bitmap to the device's public photo gallery.
     */
    fun saveToGallery(context: Context, bitmap: Bitmap, publicName: String): String? {
        val resolver = context.contentResolver
        val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$publicName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WiggleCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        return try {
            val uri = resolver.insert(imageCollection, contentValues) ?: return null
            resolver.openOutputStream(uri).use { out ->
                if (out != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            Log.d(TAG, "Successfully exported to public gallery URI: $uri")
            uri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to MediaStore", e)
            null
        }
    }

    /**
     * Load image from file path safely.
     */
    fun loadBitmap(path: String): Bitmap? {
        return try {
            BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding file: $path", e)
            null
        }
    }
}
