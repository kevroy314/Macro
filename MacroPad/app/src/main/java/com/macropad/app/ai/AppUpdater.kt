package com.macropad.app.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.macropad.app.BuildConfig
import com.macropad.app.data.repository.MacroRepository
import com.macropad.app.net.AiCallResult
import com.macropad.app.net.AiClient
import com.macropad.app.net.ServerRelease
import java.io.File

/**
 * Over-the-air updates from the user's own daemon.
 *
 * MacroPad is sideloaded from a machine the user controls, so `build_release.sh`
 * publishes each signed APK to the daemon and this pulls it down. Only ever active
 * when an AI server is configured: a copy installed from Play has no server, sees no
 * updates, and never asks to install anything — Play's own policy forbids an app
 * distributed there from updating itself by any other route.
 */
class AppUpdater(
    private val context: Context,
    private val repository: MacroRepository
) {
    sealed class State {
        object Idle : State()
        object Checking : State()
        object UpToDate : State()
        data class Available(val release: ServerRelease) : State()
        data class Downloading(val release: ServerRelease, val progress: Float) : State()
        data class ReadyToInstall(val release: ServerRelease, val file: File) : State()
        data class Failed(val message: String) : State()
    }

    val currentVersionName: String get() = BuildConfig.VERSION_NAME
    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE

    /** Returns the newer release, or null when there isn't one. */
    suspend fun check(): AiCallResult<ServerRelease?> {
        val settings = repository.getAiSettings()
        if (!settings.isConfigured) {
            return AiCallResult.Failure("AI server not configured")
        }
        return when (val result = AiClient.latestRelease(settings)) {
            is AiCallResult.Failure -> result
            is AiCallResult.Success -> {
                val release = result.value
                if (!release.available || release.versionCode <= currentVersionCode) {
                    AiCallResult.Success(null)
                } else {
                    AiCallResult.Success(release)
                }
            }
        }
    }

    suspend fun download(
        release: ServerRelease,
        onProgress: (Float) -> Unit
    ): AiCallResult<File> {
        val settings = repository.getAiSettings()
        if (!settings.isConfigured) {
            return AiCallResult.Failure("AI server not configured")
        }
        val dir = File(context.cacheDir, "updates").apply {
            mkdirs()
            // Only ever keep the build being installed.
            listFiles()?.forEach { it.delete() }
        }
        val target = File(dir, "MacroPad-${release.versionCode}.apk")
        return AiClient.downloadRelease(settings, release, target, onProgress)
    }

    /**
     * Hands the APK to the system installer.
     *
     * Returns false when the user hasn't allowed this app to install packages; the
     * caller should send them to [unknownSourcesIntent] first.
     */
    fun install(file: File): Boolean {
        if (!canInstallPackages()) return false
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Settings screen where the user grants install-from-this-app permission. */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
