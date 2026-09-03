package com.macropad.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Connection and behaviour settings for the AI estimator daemon.
 */
@Entity(tableName = "ai_settings")
data class AiSettings(
    @PrimaryKey
    val id: Int = 1, // Singleton pattern

    val enabled: Boolean = false,
    /** e.g. https://macropad.example.com — no trailing slash needed. */
    val serverUrl: String = "",
    val apiKey: String = "",
    /**
     * SHA-256 of the server's public key, base64, for a self-signed certificate on a
     * home network where no public authority will issue one.
     *
     * Empty is the normal case and means ordinary certificate validation — a server
     * behind a real hostname with a Let's Encrypt certificate needs nothing here.
     */
    val certPin: String = "",

    /**
     * How large a follow-up question's potential effect has to be before the app is
     * worth interrupting for. PERCENT is of the estimated total calories.
     */
    val thresholdMode: ThresholdMode = ThresholdMode.PERCENT,
    val thresholdValue: Float = 10f,

    /** Log the estimate as soon as it lands, then adjust when a follow-up is answered. */
    val autoApply: Boolean = true,

    val lastPolledServerTime: Long = 0
) {
    val isConfigured: Boolean
        get() = enabled && serverUrl.isNotBlank() && apiKey.isNotBlank()

    /** Normalised base URL with any trailing slash removed. */
    val baseUrl: String
        get() = serverUrl.trim().trimEnd('/')
}

enum class ThresholdMode {
    PERCENT,
    ABSOLUTE;

    /** Wire value expected by the daemon. */
    val wireValue: String
        get() = if (this == PERCENT) "percent" else "absolute"
}
