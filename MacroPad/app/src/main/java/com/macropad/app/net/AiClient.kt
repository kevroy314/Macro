package com.macropad.app.net

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.macropad.app.data.entity.AiProposal
import com.macropad.app.data.entity.AiSettings
import com.macropad.app.data.entity.MacroPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** One thing the agent did on the way to an answer. */
data class AiStep(val at: Long = 0, val text: String = "")

/** A job as the daemon reports it. */
data class ServerJob(
    val id: String,
    @SerializedName("client_job_id") val clientJobId: String?,
    @SerializedName("parent_job_id") val parentJobId: String?,
    val status: String,
    val progress: String = "",
    val steps: List<AiStep> = emptyList(),
    @SerializedName("prompt_text") val promptText: String = "",
    @SerializedName("threshold_mode") val thresholdMode: String = "percent",
    @SerializedName("threshold_value") val thresholdValue: Float = 10f,
    @SerializedName("image_count") val imageCount: Int = 0,
    val revision: Int = 0,
    val error: String? = null,
    @SerializedName("cost_usd") val costUsd: Double? = null,
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("updated_at") val updatedAt: Long = 0,
    /**
     * Null until the agent finishes. Typed as JsonElement, not JsonObject: Gson
     * refuses to read a JSON null into a JsonObject field and throws instead of
     * assigning null. Use [resultObject].
     */
    val result: JsonElement? = null
) {
    val resultObject: JsonObject?
        get() = result?.takeIf { it.isJsonObject }?.asJsonObject
}

data class ServerJobList(
    val jobs: List<ServerJob> = emptyList(),
    @SerializedName("server_time") val serverTime: Long = 0
)

data class ServerHealth(
    val ok: Boolean = false,
    val service: String = "",
    val model: String = "",
    @SerializedName("active_jobs") val activeJobs: Int = 0
)

data class AiAnswer(val questionId: String, val answer: String)

/** What the daemon is holding for this user. */
data class ServerBackupMeta(
    val exists: Boolean = false,
    @SerializedName("updated_at") val updatedAt: Long = 0,
    @SerializedName("size_bytes") val sizeBytes: Long = 0,
    @SerializedName("export_date") val exportDate: String = "",
    @SerializedName("device_id") val deviceId: String = "",
    val days: Int = 0,
    val presets: Int = 0,
    /** How many backups the server is holding, and how far back they go. */
    val count: Int = 0,
    @SerializedName("oldest_at") val oldestAt: Long = 0
)

/** One backup in the series, any of which can be restored. */
data class ServerBackupVersion(
    val at: Long = 0,
    @SerializedName("size_bytes") val sizeBytes: Long = 0,
    val days: Int = 0,
    val presets: Int = 0,
    val entries: Int = 0,
    val unreadable: Boolean = false
)

data class ServerBackupHistory(
    val backups: List<ServerBackupVersion> = emptyList()
)

/** A person with their own key on the shared daemon. */
data class ServerUser(
    val id: String = "",
    val name: String = "",
    val email: String = "",
    @SerializedName("is_primary") val isPrimary: Boolean = false,
    /** Only present on the response that creates them. */
    val key: String = ""
)

/** A planning thread as the daemon reports it. */
data class ServerThread(
    val id: String,
    @SerializedName("client_thread_id") val clientThreadId: String?,
    val title: String = "",
    val status: String = "idle",
    val progress: String = "",
    val steps: List<AiStep> = emptyList(),
    val error: String? = null,
    @SerializedName("cost_usd") val costUsd: Double? = null,
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("updated_at") val updatedAt: Long = 0,
    val messages: List<ServerThreadMessage> = emptyList()
)

data class ServerThreadMessage(
    val id: String,
    val role: String = "user",
    val text: String = "",
    @SerializedName("image_count") val imageCount: Int = 0,
    val proposals: List<AiProposal> = emptyList(),
    @SerializedName("created_at") val createdAt: Long = 0
)

data class ServerThreadList(
    val threads: List<ServerThread> = emptyList(),
    @SerializedName("server_time") val serverTime: Long = 0
)

/** A build the daemon is hosting. */
data class ServerRelease(
    val available: Boolean = false,
    @SerializedName("version_name") val versionName: String = "",
    @SerializedName("version_code") val versionCode: Int = 0,
    val notes: String = "",
    val file: String = "",
    @SerializedName("size_bytes") val sizeBytes: Long = 0,
    val sha256: String = "",
    @SerializedName("published_at") val publishedAt: Long = 0,
    @SerializedName("download_url") val downloadUrl: String = ""
)

/** Every call returns one of these — callers should never see an exception. */
sealed class AiCallResult<out T> {
    data class Success<T>(val value: T) : AiCallResult<T>()
    data class Failure(
        val message: String,
        val statusCode: Int? = null,
        /**
         * True when the request failed for a reason that might not fail next time —
         * no network, the server asleep, a 5xx. The caller can queue and retry
         * instead of telling the user their meal was rejected.
         */
        val retryable: Boolean = false
    ) : AiCallResult<Nothing>()

    val successOrNull: T?
        get() = (this as? Success)?.value
}

/**
 * Talks to the macropad-ai daemon. Stateless: every call takes the current
 * [AiSettings] so a changed URL or key takes effect immediately.
 */
object AiClient {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val jpegMediaType = "image/jpeg".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // Uploads are several MB over mobile data.
            .writeTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Carries the certificate pin from the settings to [execute] without every call
     * site having to pass it along.
     */
    private data class Pin(val value: String)

    private val pinnedClients = ConcurrentHashMap<String, OkHttpClient>()

    /**
     * The client to use for a request: the ordinary one, or a pinned one.
     *
     * A server on the home network has no name a public authority will certify, so it
     * presents a self-signed certificate and the setup QR carries that certificate's
     * SHA-256. With a pin set we trust exactly that one key and nothing else — which
     * is narrower than normal validation, not weaker than it.
     *
     * With no pin (a real hostname with a real certificate) this is untouched and
     * validation is entirely standard.
     */
    private fun clientFor(request: Request): OkHttpClient {
        val pin = request.tag(Pin::class.java)?.value?.trim().orEmpty()
        if (pin.isBlank()) return client
        return pinnedClients.getOrPut(pin) { buildPinnedClient(pin) }
    }

    private fun buildPinnedClient(pin: String): OkHttpClient {
        // openssl and OkHttp both hash the SubjectPublicKeyInfo, so a pin generated by
        // the installer matches CertificatePinner.pin() byte for byte. Accept it with
        // or without the "sha256/" prefix.
        val expected = if (pin.startsWith("sha256/")) pin else "sha256/$pin"

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull()
                    ?: throw CertificateException("The server presented no certificate")
                if (CertificatePinner.pin(leaf) != expected) {
                    throw CertificateException(
                        "This server's certificate does not match your setup code. " +
                            "Scan a fresh code, or check you are on the right network."
                    )
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }

        return client.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // The pin is the identity here. The hostname deliberately isn't checked:
            // a home server moves between DHCP addresses, so any name or address
            // baked into the certificate goes stale while the key stays correct.
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    // ------------------------------------------------------------------ requests

    suspend fun health(
        baseUrl: String,
        apiKey: String,
        certPin: String = ""
    ): AiCallResult<ServerHealth> {
        val url = baseUrl.trim().trimEnd('/')
        if (url.isBlank()) return AiCallResult.Failure("No server URL set")
        return execute(
            Request.Builder()
                .url("$url/api/v1/health")
                .header("Authorization", "Bearer $apiKey")
                .tag(Pin::class.java, Pin(certPin))
                .get()
                .build(),
            ServerHealth::class.java
        )
    }

    suspend fun createJob(
        settings: AiSettings,
        clientJobId: String,
        text: String,
        images: List<File>,
        thresholdMode: String,
        thresholdValue: Float
    ): AiCallResult<ServerJob> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("text", text)
            .addFormDataPart("threshold_mode", thresholdMode)
            .addFormDataPart("threshold_value", thresholdValue.toString())
            .addFormDataPart("client_job_id", clientJobId)
            .apply {
                images.forEachIndexed { index, file ->
                    if (file.exists()) {
                        addFormDataPart(
                            "images",
                            "image_${index + 1}.jpg",
                            file.asRequestBody(jpegMediaType)
                        )
                    }
                }
            }
            .build()

        return execute(request(settings, "/api/v1/jobs").post(body).build(), ServerJob::class.java)
    }

    suspend fun listJobs(settings: AiSettings, updatedSince: Long): AiCallResult<ServerJobList> =
        execute(
            request(settings, "/api/v1/jobs?updated_since=$updatedSince&limit=200").get().build(),
            ServerJobList::class.java
        )

    suspend fun getJob(settings: AiSettings, serverJobId: String): AiCallResult<ServerJob> =
        execute(
            request(settings, "/api/v1/jobs/$serverJobId").get().build(),
            ServerJob::class.java
        )

    suspend fun answer(
        settings: AiSettings,
        serverJobId: String,
        answers: List<AiAnswer>
    ): AiCallResult<ServerJob> {
        val payload = JsonObject().apply {
            add("answers", gson.toJsonTree(answers.map {
                mapOf("question_id" to it.questionId, "answer" to it.answer)
            }))
        }
        return execute(
            request(settings, "/api/v1/jobs/$serverJobId/answers")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build(),
            ServerJob::class.java
        )
    }

    /** Tell a finished estimate what it got wrong. Resumes its session. */
    suspend fun correct(
        settings: AiSettings,
        serverJobId: String,
        text: String
    ): AiCallResult<ServerJob> {
        val body = JsonObject().apply { addProperty("text", text) }
        return execute(
            request(settings, "/api/v1/jobs/$serverJobId/correct")
                .post(gson.toJson(body).toRequestBody(jsonMediaType))
                .build(),
            ServerJob::class.java
        )
    }

    suspend fun cancel(settings: AiSettings, serverJobId: String): AiCallResult<ServerJob> =
        execute(
            request(settings, "/api/v1/jobs/$serverJobId/cancel")
                .post(ByteArray(0).toRequestBody(null))
                .build(),
            ServerJob::class.java
        )

    suspend fun retry(
        settings: AiSettings,
        serverJobId: String,
        clientJobId: String,
        text: String,
        thresholdMode: String,
        thresholdValue: Float
    ): AiCallResult<ServerJob> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("text", text)
            .addFormDataPart("threshold_mode", thresholdMode)
            .addFormDataPart("threshold_value", thresholdValue.toString())
            .addFormDataPart("client_job_id", clientJobId)
            .build()
        return execute(
            request(settings, "/api/v1/jobs/$serverJobId/retry").post(body).build(),
            ServerJob::class.java
        )
    }

    // ---------------------------------------------------------- planning threads

    suspend fun createThread(
        settings: AiSettings,
        clientThreadId: String,
        text: String,
        images: List<File>,
        contextJson: String
    ): AiCallResult<ServerThread> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("text", text)
            .addFormDataPart("context", contextJson)
            .addFormDataPart("client_thread_id", clientThreadId)
            .apply { attachImages(images) }
            .build()
        return execute(
            request(settings, "/api/v1/threads").post(body).build(),
            ServerThread::class.java
        )
    }

    suspend fun sendThreadMessage(
        settings: AiSettings,
        serverThreadId: String,
        text: String,
        images: List<File>,
        contextJson: String
    ): AiCallResult<ServerThread> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("text", text)
            .addFormDataPart("context", contextJson)
            .apply { attachImages(images) }
            .build()
        return execute(
            request(settings, "/api/v1/threads/$serverThreadId/messages").post(body).build(),
            ServerThread::class.java
        )
    }

    suspend fun listThreads(settings: AiSettings, updatedSince: Long): AiCallResult<ServerThreadList> =
        execute(
            request(settings, "/api/v1/threads?updated_since=$updatedSince&limit=100").get().build(),
            ServerThreadList::class.java
        )

    suspend fun getThread(settings: AiSettings, serverThreadId: String): AiCallResult<ServerThread> =
        execute(
            request(settings, "/api/v1/threads/$serverThreadId").get().build(),
            ServerThread::class.java
        )

    suspend fun cancelThread(settings: AiSettings, serverThreadId: String): AiCallResult<ServerThread> =
        execute(
            request(settings, "/api/v1/threads/$serverThreadId/cancel")
                .post(ByteArray(0).toRequestBody(null))
                .build(),
            ServerThread::class.java
        )

    suspend fun deleteThread(settings: AiSettings, serverThreadId: String): AiCallResult<Unit> =
        when (val raw = executeRaw(request(settings, "/api/v1/threads/$serverThreadId").delete().build())) {
            is AiCallResult.Success -> AiCallResult.Success(Unit)
            is AiCallResult.Failure -> raw
        }

    // ------------------------------------------------------------------ backups

    suspend fun backupMeta(settings: AiSettings): AiCallResult<ServerBackupMeta> =
        execute(
            request(settings, "/api/v1/backup/meta").get().build(),
            ServerBackupMeta::class.java
        )

    suspend fun uploadBackup(settings: AiSettings, backupJson: String): AiCallResult<ServerBackupMeta> {
        val body = "{\"backup\":$backupJson}".toRequestBody(jsonMediaType)
        return execute(
            request(settings, "/api/v1/backup").put(body).build(),
            ServerBackupMeta::class.java
        )
    }

    /** Returns the stored backup as raw JSON, for the caller to parse into BackupData. */
    suspend fun backupHistory(settings: AiSettings): AiCallResult<ServerBackupHistory> =
        execute(
            request(settings, "/api/v1/backup/history").get().build(),
            ServerBackupHistory::class.java
        )

    /** [at] picks one backup out of the series; null takes the newest. */
    suspend fun downloadBackup(
        settings: AiSettings,
        at: Long? = null
    ): AiCallResult<String> {
        val path = if (at == null) "/api/v1/backup" else "/api/v1/backup?at=$at"
        return when (val raw = executeRaw(request(settings, path).get().build())) {
            is AiCallResult.Failure -> raw
            is AiCallResult.Success -> try {
                val obj = gson.fromJson(raw.value, JsonObject::class.java)
                val backup = obj?.get("backup")
                if (backup == null || !backup.isJsonObject) {
                    AiCallResult.Failure("Server returned no backup")
                } else {
                    AiCallResult.Success(backup.toString())
                }
            } catch (e: Exception) {
                AiCallResult.Failure("Could not read the backup: ${e.message}")
            }
        }
    }

    suspend fun whoami(settings: AiSettings): AiCallResult<ServerUser> =
        execute(request(settings, "/api/v1/whoami").get().build(), ServerUser::class.java)

    /** Invites someone. Primary user only; returns their key once. */
    suspend fun createUser(
        settings: AiSettings,
        name: String,
        email: String
    ): AiCallResult<ServerUser> {
        val payload = JsonObject().apply {
            addProperty("name", name)
            addProperty("email", email)
        }
        return execute(
            request(settings, "/api/v1/users")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build(),
            ServerUser::class.java
        )
    }

    /** Asks the daemon to label presets with search keywords. */
    suspend fun tagPresets(
        settings: AiSettings,
        presets: List<MacroPreset>
    ): AiCallResult<Map<String, List<String>>> {
        val payload = JsonObject().apply {
            add("presets", gson.toJsonTree(presets.map {
                mapOf(
                    "id" to it.id,
                    "name" to it.name,
                    "protein_g" to it.proteinG,
                    "carbs_g" to it.carbsG,
                    "fat_g" to it.fatG,
                    "calories" to it.calories
                )
            }))
        }
        return when (
            val raw = executeRaw(
                request(settings, "/api/v1/preset-tags")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
            )
        ) {
            is AiCallResult.Failure -> raw
            is AiCallResult.Success -> try {
                val type = object : com.google.gson.reflect.TypeToken<TagResponse>() {}.type
                val parsed: TagResponse? = gson.fromJson(raw.value, type)
                AiCallResult.Success(parsed?.tags ?: emptyMap())
            } catch (e: Exception) {
                AiCallResult.Failure("Could not read tags: ${e.message}")
            }
        }
    }

    private data class TagResponse(val tags: Map<String, List<String>> = emptyMap())

    private fun MultipartBody.Builder.attachImages(images: List<File>) {
        images.forEachIndexed { index, file ->
            if (file.exists()) {
                addFormDataPart(
                    "images",
                    "image_${index + 1}.jpg",
                    file.asRequestBody(jpegMediaType)
                )
            }
        }
    }

    suspend fun latestRelease(settings: AiSettings): AiCallResult<ServerRelease> =
        execute(
            request(settings, "/api/v1/release/latest").get().build(),
            ServerRelease::class.java
        )

    /**
     * Streams an APK to [target]. [onProgress] gets 0..1, or -1 when the server
     * didn't send a length.
     */
    suspend fun downloadRelease(
        settings: AiSettings,
        release: ServerRelease,
        target: File,
        onProgress: (Float) -> Unit
    ): AiCallResult<File> = withContext(Dispatchers.IO) {
        // Built from the base URL the app is already talking to rather than the
        // server's own download_url: behind a reverse proxy the server can't always
        // see the port the client used.
        val url = "${settings.baseUrl}/api/v1/release/download/${release.file}"
        val downloadRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .tag(Pin::class.java, Pin(settings.certPin))
            .get()
            .build()
        val call = clientFor(downloadRequest).newCall(downloadRequest)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext AiCallResult.Failure(
                        describeError(response.code, ""),
                        response.code
                    )
                }
                val body = response.body
                    ?: return@withContext AiCallResult.Failure("Empty download")
                val total = if (body.contentLength() > 0) {
                    body.contentLength()
                } else {
                    release.sizeBytes
                }

                target.parentFile?.mkdirs()
                var written = 0L
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(if (total > 0) written.toFloat() / total else -1f)
                        }
                    }
                }

                // A truncated or tampered APK would fail to install with a far less
                // obvious error, so check the hash the server published.
                if (release.sha256.isNotBlank()) {
                    val actual = sha256Of(target)
                    if (!actual.equals(release.sha256, ignoreCase = true)) {
                        target.delete()
                        return@withContext AiCallResult.Failure(
                            "Download was corrupted — checksum did not match"
                        )
                    }
                }
                AiCallResult.Success(target)
            }
        } catch (e: Exception) {
            target.delete()
            AiCallResult.Failure(e.message ?: "Download failed")
        }
    }

    private fun sha256Of(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun deleteJob(settings: AiSettings, serverJobId: String): AiCallResult<Unit> {
        val result = executeRaw(request(settings, "/api/v1/jobs/$serverJobId").delete().build())
        return when (result) {
            is AiCallResult.Success -> AiCallResult.Success(Unit)
            is AiCallResult.Failure -> result
        }
    }

    // ------------------------------------------------------------------- plumbing

    private fun request(settings: AiSettings, path: String): Request.Builder =
        Request.Builder()
            .url(settings.baseUrl + path)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .tag(Pin::class.java, Pin(settings.certPin))

    private suspend fun <T> execute(request: Request, type: Class<T>): AiCallResult<T> =
        when (val raw = executeRaw(request)) {
            is AiCallResult.Failure -> raw
            is AiCallResult.Success -> try {
                val parsed = gson.fromJson(raw.value, type)
                if (parsed == null) {
                    AiCallResult.Failure("Server returned an empty response")
                } else {
                    AiCallResult.Success(parsed)
                }
            } catch (e: Exception) {
                AiCallResult.Failure("Could not read the server's response: ${e.message}")
            }
        }

    private suspend fun executeRaw(request: Request): AiCallResult<String> =
        withContext(Dispatchers.IO) {
            try {
                clientFor(request).newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        AiCallResult.Success(body)
                    } else {
                        AiCallResult.Failure(
                            describeError(response.code, body),
                            response.code,
                            retryable = isRetryable(response.code)
                        )
                    }
                }
            } catch (e: IOException) {
                // No network, server asleep, wrong side of the front door. Worth
                // holding onto and sending later rather than losing the meal.
                AiCallResult.Failure(
                    e.message ?: "Could not reach the server",
                    retryable = true
                )
            } catch (e: Exception) {
                AiCallResult.Failure(e.message ?: "Unexpected error")
            }
        }

    /** 5xx and friends may work next time; a 4xx means the request itself is wrong. */
    private fun isRetryable(code: Int): Boolean =
        code >= 500 || code == 408 || code == 429

    private fun describeError(code: Int, body: String): String {
        val detail = try {
            gson.fromJson(body, JsonObject::class.java)?.get("detail")?.let {
                if (it.isJsonPrimitive) it.asString else it.toString()
            }
        } catch (e: Exception) {
            null
        }
        return when {
            detail != null -> detail
            code == 401 -> "Invalid API key"
            code == 404 -> "Not found on the server"
            code == 413 -> "Photos too large for the server to accept"
            code >= 500 -> "Server error ($code)"
            else -> "Request failed ($code)"
        }
    }
}
