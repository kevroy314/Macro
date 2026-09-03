package com.macropad.app.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The `macropad://setup` link that carries a server address and API key between
 * devices, and the QR codes that move it across the room.
 */
object SetupLink {

    const val SCHEME = "macropad"
    const val HOST = "setup"

    /**
     * [pin] is the SHA-256 of a self-signed certificate, and is empty for a server
     * with a real certificate. Optional on the wire so setup codes made before it
     * existed still scan.
     */
    data class Setup(
        val url: String,
        val key: String,
        val name: String = "",
        val pin: String = ""
    )

    fun encode(setup: Setup): String =
        Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendQueryParameter("url", setup.url)
            .appendQueryParameter("key", setup.key)
            .apply {
                if (setup.name.isNotBlank()) appendQueryParameter("name", setup.name)
                if (setup.pin.isNotBlank()) appendQueryParameter("pin", setup.pin)
            }
            .build()
            .toString()

    /** Parses a scanned string, or a link the OS handed us. Null if it isn't ours. */
    fun decode(raw: String?): Setup? {
        if (raw.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        val url = uri.getQueryParameter("url")?.trim().orEmpty()
        val key = uri.getQueryParameter("key")?.trim().orEmpty()
        if (url.isBlank() || key.isBlank()) return null
        return Setup(
            url = url,
            key = key,
            name = uri.getQueryParameter("name")?.trim().orEmpty(),
            pin = uri.getQueryParameter("pin")?.trim().orEmpty()
        )
    }

    /** The URL that downloads a release, key included so a browser can fetch it. */
    fun downloadUrl(baseUrl: String, file: String, key: String): String =
        Uri.parse(baseUrl.trimEnd('/') + "/api/v1/release/download/" + file)
            .buildUpon()
            .appendQueryParameter("key", key)
            .build()
            .toString()

    /**
     * Renders [content] as a QR bitmap.
     *
     * Drawn white-on-black-free — a plain white background with black modules —
     * because scanners cope badly with inverted codes, whatever the app's theme.
     */
    fun qrBitmap(content: String, sizePx: Int = 640): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            val row = y * matrix.width
            for (x in 0 until matrix.width) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        bitmap
    }.getOrNull()
}
