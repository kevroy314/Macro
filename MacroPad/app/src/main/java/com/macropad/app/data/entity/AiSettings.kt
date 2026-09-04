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

    val lastPolledServerTime: Long = 0,

    /**
     * The notes for the build being installed, kept across the install so the app can
     * say what changed once it is running — knowing beforehand is only half of it.
     * Cleared once the user has seen them.
     */
    val lastUpdateNotes: String = "",
    val lastUpdateVersionCode: Int = 0,

    /**
     * Back up to the server once a day without being asked.
     *
     * On by default once a server is configured — a maintainer decision, recorded in
     * CONTRIBUTING. It talks to the server on its own schedule, which normally means
     * a flag that starts off, but a backup you have to remember to take is the one
     * that turns out to be a month old when the phone goes in a river. It sends your
     * own data to your own server and costs nothing, and the switch is one tap away
     * in Settings.
     */
    val autoBackup: Boolean = true,
    val lastAutoBackupAt: Long = 0
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
