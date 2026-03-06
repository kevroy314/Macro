package com.macropad.app.sync

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.WriteMode
import com.google.gson.GsonBuilder
import com.macropad.app.BuildConfig
import com.macropad.app.data.entity.DailyMacro
import com.macropad.app.data.entity.MacroPreset
import com.macropad.app.data.entity.MacroTarget
import com.macropad.app.data.entity.WidgetSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DropboxManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = GsonBuilder().setPrettyPrinting().create()

    companion object {
        private const val PREFS_NAME = "dropbox_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_ACCOUNT_EMAIL = "account_email"

        private const val BACKUP_FILE = "/macropad_backup.json"
        private const val REDIRECT_URI = "macropad://oauth"

        // PKCE code verifier stored temporarily during auth
        private var codeVerifier: String? = null
    }

    val isLinked: Boolean
        get() = prefs.getString(KEY_ACCESS_TOKEN, null) != null

    val lastSyncTime: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0)

    val accountEmail: String?
        get() = prefs.getString(KEY_ACCOUNT_EMAIL, null)

    /**
     * Start OAuth flow - opens browser for user to authorize
     */
    fun startAuth(): Intent {
        // Generate PKCE code verifier and challenge
        codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier!!)

        val authUrl = "https://www.dropbox.com/oauth2/authorize" +
                "?client_id=${BuildConfig.DROPBOX_APP_KEY}" +
                "&response_type=code" +
                "&redirect_uri=$REDIRECT_URI" +
                "&code_challenge=$codeChallenge" +
                "&code_challenge_method=S256" +
                "&token_access_type=offline"

        return Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
    }

    /**
     * Handle OAuth callback with authorization code
     */
    suspend fun handleAuthCallback(code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val verifier = codeVerifier ?: return@withContext false
            codeVerifier = null

            // Exchange code for token
            val tokenUrl = "https://api.dropboxapi.com/oauth2/token"
            val connection = java.net.URL(tokenUrl).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val postData = "code=$code" +
                    "&grant_type=authorization_code" +
                    "&client_id=${BuildConfig.DROPBOX_APP_KEY}" +
                    "&redirect_uri=$REDIRECT_URI" +
                    "&code_verifier=$verifier"

            connection.outputStream.use { it.write(postData.toByteArray()) }

            val response = connection.inputStream.bufferedReader().readText()
            val tokenResponse = gson.fromJson(response, TokenResponse::class.java)

            // Save credentials
            prefs.edit()
                .putString(KEY_ACCESS_TOKEN, tokenResponse.access_token)
                .putString(KEY_REFRESH_TOKEN, tokenResponse.refresh_token)
                .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + (tokenResponse.expires_in * 1000))
                .apply()

            // Fetch account info
            fetchAccountInfo()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private suspend fun fetchAccountInfo() = withContext(Dispatchers.IO) {
        try {
            val client = getClient() ?: return@withContext
            val account = client.users().currentAccount
            prefs.edit().putString(KEY_ACCOUNT_EMAIL, account.email).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Get Dropbox client, refreshing token if needed
     */
    private suspend fun getClient(): DbxClientV2? = withContext(Dispatchers.IO) {
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return@withContext null
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)

        // Refresh if expired
        if (System.currentTimeMillis() > expiresAt - 60000 && refreshToken != null) {
            refreshAccessToken(refreshToken)
        }

        val currentToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return@withContext null
        val config = DbxRequestConfig.newBuilder("MacroPad/1.2").build()
        DbxClientV2(config, currentToken)
    }

    private suspend fun refreshAccessToken(refreshToken: String) = withContext(Dispatchers.IO) {
        try {
            val tokenUrl = "https://api.dropboxapi.com/oauth2/token"
            val connection = java.net.URL(tokenUrl).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val postData = "grant_type=refresh_token" +
                    "&refresh_token=$refreshToken" +
                    "&client_id=${BuildConfig.DROPBOX_APP_KEY}"

            connection.outputStream.use { it.write(postData.toByteArray()) }

            val response = connection.inputStream.bufferedReader().readText()
            val tokenResponse = gson.fromJson(response, TokenResponse::class.java)

            prefs.edit()
                .putString(KEY_ACCESS_TOKEN, tokenResponse.access_token)
                .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + (tokenResponse.expires_in * 1000))
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Upload backup to Dropbox
     */
    suspend fun uploadBackup(backup: BackupData): SyncResult = withContext(Dispatchers.IO) {
        try {
            val client = getClient() ?: return@withContext SyncResult.Error("Not linked to Dropbox")

            val json = gson.toJson(backup)
            val inputStream = ByteArrayInputStream(json.toByteArray())

            client.files().uploadBuilder(BACKUP_FILE)
                .withMode(WriteMode.OVERWRITE)
                .uploadAndFinish(inputStream)

            prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()

            SyncResult.Success("Backup uploaded successfully")
        } catch (e: Exception) {
            e.printStackTrace()
            SyncResult.Error("Upload failed: ${e.message}")
        }
    }

    /**
     * Download backup from Dropbox
     */
    suspend fun downloadBackup(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val client = getClient() ?: return@withContext SyncResult.Error("Not linked to Dropbox")

            val outputStream = ByteArrayOutputStream()
            client.files().download(BACKUP_FILE).download(outputStream)

            val json = outputStream.toString()
            val backup = gson.fromJson(json, BackupData::class.java)

            SyncResult.SuccessWithData(backup)
        } catch (e: com.dropbox.core.v2.files.DownloadErrorException) {
            // File doesn't exist yet
            SyncResult.NoRemoteData
        } catch (e: Exception) {
            e.printStackTrace()
            SyncResult.Error("Download failed: ${e.message}")
        }
    }

    /**
     * Unlink Dropbox account
     */
    fun unlink() {
        prefs.edit().clear().apply()
    }

    // PKCE helpers
    private fun generateCodeVerifier(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        return (1..64).map { chars.random() }.joinToString("")
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    data class TokenResponse(
        val access_token: String,
        val refresh_token: String?,
        val expires_in: Long
    )
}

/**
 * Complete backup data structure
 */
data class BackupData(
    val version: Int = 3,
    val exportDate: String = java.time.LocalDateTime.now().toString(),
    val deviceId: String = "",
    val targets: MacroTarget? = null,
    val widgetSettings: WidgetSettings? = null,
    val presets: List<MacroPreset> = emptyList(),
    val dailyMacros: List<DailyMacro> = emptyList()
)

sealed class SyncResult {
    data class Success(val message: String) : SyncResult()
    data class SuccessWithData(val backup: BackupData) : SyncResult()
    data class Error(val message: String) : SyncResult()
    object NoRemoteData : SyncResult()
}
