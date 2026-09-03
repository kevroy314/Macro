package com.macropad.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

private const val TAG = "AiImageStore"

/**
 * Local copies of the photos attached to an AI job.
 *
 * They are downscaled on the way in: a modern phone camera produces 4000px images
 * that cost upload time and add nothing the model can use.
 */
object AiImageStore {

    private const val MAX_EDGE = 1600
    private const val QUALITY = 85

    fun jobDir(context: Context, clientJobId: String): File =
        File(context.filesDir, "ai_jobs/$clientJobId").apply { mkdirs() }

    fun listImages(context: Context, clientJobId: String): List<File> =
        jobDir(context, clientJobId)
            .listFiles { file -> file.name.startsWith("image_") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** What came of copying a batch of picked images into a job. */
    data class StoreOutcome(val files: List<File>, val error: String?)

    fun storeAll(context: Context, clientJobId: String, uris: List<Uri>): StoreOutcome {
        val stored = mutableListOf<File>()
        var firstError: String? = null
        uris.forEachIndexed { index, uri ->
            when (val result = store(context, clientJobId, index + 1, uri)) {
                is Result.Success -> stored.add(result.file)
                is Result.Failure -> {
                    Log.w(TAG, "could not store $uri: ${result.reason}")
                    if (firstError == null) firstError = result.reason
                }
            }
        }
        return StoreOutcome(stored, firstError)
    }

    private sealed class Result {
        data class Success(val file: File) : Result()
        data class Failure(val reason: String) : Result()
    }

    /** Copies a picked or captured image into the job's directory. */
    private fun store(context: Context, clientJobId: String, index: Int, uri: Uri): Result {
        val target = File(jobDir(context, clientJobId), "image_$index.jpg")
        return try {
            val bitmap = decodeScaled(context, uri)
                ?: return Result.Failure("not a readable image")
            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            }
            bitmap.recycle()
            Result.Success(target)
        } catch (e: Exception) {
            target.delete()
            Result.Failure(e.message ?: e::class.java.simpleName)
        }
    }

    fun deleteJobFiles(context: Context, clientJobId: String) {
        jobDir(context, clientJobId).deleteRecursively()
    }

    private fun decodeScaled(context: Context, uri: Uri): Bitmap? {
        // Measure first. Note decodeStream *always* returns null under
        // inJustDecodeBounds — the dimensions come back on the Options object — so
        // this must not be written as `openInputStream(uri)?.use { decode() } ?: return`,
        // which would treat every successful measurement as a failure.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val measuring = context.contentResolver.openInputStream(uri) ?: return null
        measuring.use { BitmapFactory.decodeStream(it, null, bounds) }

        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        // Subsample on the way in so a 12 MP photo never fully lands in memory.
        var sample = 1
        while (longest / sample > MAX_EDGE * 2) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val reading = context.contentResolver.openInputStream(uri) ?: return null
        val decoded = reading.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null

        val scaled = scaleToMaxEdge(decoded)
        return applyExifRotation(context, uri, scaled)
    }

    private fun scaleToMaxEdge(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE) return bitmap
        val scale = MAX_EDGE.toFloat() / longest
        val resized = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (resized != bitmap) bitmap.recycle()
        return resized
    }

    /** Camera photos are frequently stored sideways with the rotation only in EXIF. */
    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }
}
