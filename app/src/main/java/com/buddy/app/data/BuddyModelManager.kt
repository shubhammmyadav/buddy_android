package com.buddy.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Owns the model lifecycle: presence check → download → LLM initialisation → inference.
 * All heavy work is dispatched to [Dispatchers.IO].
 */
class BuddyModelManager(private val context: Context) {

    companion object {
        private const val MODEL_FILE_NAME = "buddy_core_model.task"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"

        // Gemma models are gated. To download:
        // 1. Accept terms at https://huggingface.co/google/gemma-3-1b-it
        // 2. Generate a "Read" token at https://huggingface.co/settings/tokens
        // 3. Add  HF_TOKEN=hf_your_token_here  to local.properties (never commit that file)
        private val HF_TOKEN: String =
            try {
                val props = java.util.Properties()
                props.load(java.io.FileInputStream("local.properties"))
                props.getProperty("HF_TOKEN", "")
            } catch (_: Exception) { "" }

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS    = 60_000
        private const val BUFFER_SIZE        = 16_384

        // Gemma-3 chat template delimiters
        private const val START_TURN  = "<start_of_turn>"
        private const val END_TURN    = "<end_of_turn>"

        private const val SYSTEM_PROMPT =
            "You are Buddy, a warm, supportive, and slightly humorous best friend. " +
            "The user will share their daily activities. Your goal is to listen empathetically, " +
            "highlight their small wins, and give them a short, uplifting boost of motivation. " +
            "Keep responses conversational, brief (2-3 sentences max), and deeply human."
    }

    // Exposes 0-100 download progress to the ViewModel
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private var llmInference: LlmInference? = null

    // Public path used by the rest of the app
    val modelFile: File
        get() = File(context.filesDir, MODEL_FILE_NAME)

    fun isModelDownloaded(): Boolean =
        modelFile.exists() && modelFile.length() > 0L

    /** Returns true when an active network is reachable, regardless of type. */
    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Returns true only when connected via an unmetered Wi-Fi network. */
    fun isOnUnmeteredWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Streams the model file into private app storage.
     * Uses a .tmp file so that an interrupted download never leaves a corrupt model behind.
     * Progress is emitted via [downloadProgress] as an integer 0-100.
     */
    suspend fun downloadModel(): Result<Unit> = withContext(Dispatchers.IO) {
        val tempFile = File(context.filesDir, "$MODEL_FILE_NAME.tmp")
        var connection: HttpURLConnection? = null
        var outputStream: FileOutputStream? = null
        var inputStream: InputStream? = null

        try {
            Log.d("BuddyModelManager", "Starting download from $MODEL_URL")
            // Clean up any leftover partial download
            if (tempFile.exists()) tempFile.delete()

            connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout    = READ_TIMEOUT_MS
                requestMethod  = "GET"
                setRequestProperty("User-Agent", "BuddyApp/1.0 Android")
                setRequestProperty("Accept", "application/octet-stream")
                
                // Ensure the token is sent if it's not the placeholder
                if (HF_TOKEN.startsWith("hf_") && HF_TOKEN != "hf_YOUR_TOKEN_HERE") {
                    Log.d("BuddyModelManager", "Sending Authorization header...")
                    setRequestProperty("Authorization", "Bearer $HF_TOKEN")
                } else {
                    Log.w("BuddyModelManager", "No valid HF_TOKEN found on line 33. Download will likely fail with 401.")
                }

                instanceFollowRedirects = true
            }
            connection.connect()

            val responseCode = connection.responseCode
            Log.d("BuddyModelManager", "HTTP response code: $responseCode")
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(
                    Exception("Server returned HTTP $responseCode for model URL.")
                )
            }

            val contentLength = connection.contentLengthLong
            inputStream  = connection.inputStream
            outputStream = FileOutputStream(tempFile)

            val buffer        = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            var totalRead     = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                if (contentLength > 0L) {
                    val progress = ((totalRead * 100L) / contentLength).toInt().coerceIn(0, 100)
                    if (progress % 5 == 0 && progress != _downloadProgress.value) {
                        Log.d("BuddyModelManager", "Download progress: $progress%")
                    }
                    _downloadProgress.emit(progress)
                }
            }

            outputStream.flush()
            Log.d("BuddyModelManager", "Download complete, promoting temp file")

            // Atomically promote the temp file — guarantees the model is never partially written
            if (!tempFile.renameTo(modelFile)) {
                // renameTo can fail across mount points; fall back to copy + delete
                tempFile.copyTo(modelFile, overwrite = true)
                tempFile.delete()
            }

            _downloadProgress.emit(100)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("BuddyModelManager", "Download failed", e)
            tempFile.delete()
            Result.failure(e)
        } finally {
            runCatching { outputStream?.close() }
            runCatching { inputStream?.close() }
            connection?.disconnect()
        }
    }

    /**
     * Constructs the [LlmInference] instance pointing at the local model file.
     * Must be called after [downloadModel] succeeds.
     */
    suspend fun initializeLlm(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(512)
                .setMaxTopK(40)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("LLM init failed: ${e.message}", e))
        }
    }

    /**
     * Synchronously generates a response (runs on [Dispatchers.IO]).
     * Wraps [history] in the Gemma-3 instruction-tuned chat template so the
     * model respects the system prompt and conversation context on every turn.
     * [history] should be a list of pairs: (Role, MessageText).
     * Role should be "user" or "model".
     */
    suspend fun generateResponse(history: List<Pair<String, String>>): Result<String> = withContext(Dispatchers.IO) {
        try {
            val inference = llmInference
                ?: return@withContext Result.failure(Exception("LLM engine not initialised."))

            val prompt = buildGemmaPrompt(history)
            val raw    = inference.generateResponse(prompt)
            Result.success(raw.trim())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Formats the conversation history for Gemma-3-1B-IT using the standard chat template:
     *   <start_of_turn>system\n…<end_of_turn>
     *   <start_of_turn>user\n…<end_of_turn>
     *   <start_of_turn>model\n…<end_of_turn>
     *   ...
     *   <start_of_turn>model\n          ← model begins generating here
     */
    private fun buildGemmaPrompt(history: List<Pair<String, String>>): String = buildString {
        // System prompt first
        append(START_TURN).append("system\n")
        append(SYSTEM_PROMPT)
        append(END_TURN).append("\n")

        // Then all past turns (user/model)
        history.forEach { (role, text) ->
            append(START_TURN).append(role).append("\n")
            append(text.trim())
            append(END_TURN).append("\n")
        }

        // Finally, signal that the model should reply
        append(START_TURN).append("model\n")
    }

    /** Must be called when the ViewModel is cleared to free the native LLM handle. */
    fun release() {
        runCatching { llmInference?.close() }
        llmInference = null
    }
}
