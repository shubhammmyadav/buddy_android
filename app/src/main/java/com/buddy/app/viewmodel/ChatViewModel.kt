package com.buddy.app.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buddy.app.data.BuddyModelManager
import com.buddy.app.service.DownloadService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─────────────────────────── Domain types ────────────────────────────────────

enum class MessageAuthor { USER, BUDDY }

enum class AppState {
    /** Checking local storage for the model file. */
    INITIALIZING,
    /** Model absent — streaming download in progress. */
    DOWNLOADING,
    /** LLM is loaded and the chat interface is fully functional. */
    READY,
    /** An unrecoverable error occurred (download failure, init failure, etc.). */
    ERROR
}

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val text: String,
    val author: MessageAuthor
)

data class UiState(
    val appState: AppState = AppState.INITIALIZING,
    val downloadProgress: Int = 0,
    val statusMessage: String = "Waking Buddy up…",
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val errorMessage: String? = null
)

// ─────────────────────────── ViewModel ───────────────────────────────────────

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val modelManager = BuddyModelManager(application)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var progressCollectorJob: Job? = null

    init {
        initializeApp()
        observeDownloadService()
    }

    private fun observeDownloadService() {
        viewModelScope.launch {
            DownloadService.downloadStatus.collect { status ->
                when (status) {
                    is DownloadService.DownloadStatus.Progress -> {
                        _uiState.update {
                            it.copy(
                                appState = AppState.DOWNLOADING,
                                downloadProgress = status.progress,
                                statusMessage = downloadStatusMessage(status.progress)
                            )
                        }
                    }
                    is DownloadService.DownloadStatus.Success -> {
                        loadLlm()
                    }
                    is DownloadService.DownloadStatus.Error -> {
                        _uiState.update {
                            it.copy(
                                appState = AppState.ERROR,
                                errorMessage = "Download failed: ${status.message}"
                            )
                        }
                    }
                    DownloadService.DownloadStatus.Idle -> {}
                }
            }
        }
    }

    // ── Startup orchestration ─────────────────────────────────────────────────

    private fun initializeApp() {
        viewModelScope.launch {
            if (modelManager.isModelDownloaded()) {
                loadLlm()
            } else {
                startDownload()
            }
        }
    }

    private suspend fun loadLlm() {
        _uiState.update { it.copy(statusMessage = "Waking Buddy up…") }

        val result = modelManager.initializeLlm()

        if (result.isSuccess) {
            transitionToReady()
        } else {
            _uiState.update {
                it.copy(
                    appState = AppState.ERROR,
                    errorMessage = "Buddy couldn't start: ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    // ── Download flow ─────────────────────────────────────────────────────────

    /** Begins (or retries) the model download after verifying connectivity. */
    fun startDownload() {
        if (!modelManager.isNetworkAvailable()) {
            _uiState.update {
                it.copy(
                    appState = AppState.ERROR,
                    errorMessage = "No internet connection. Connect to Wi-Fi and try again."
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                appState = AppState.DOWNLOADING,
                downloadProgress = 0,
                errorMessage = null,
                statusMessage = downloadStatusMessage(0)
            )
        }

        val intent = Intent(getApplication(), DownloadService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            getApplication<Application>().startForegroundService(intent)
        } else {
            getApplication<Application>().startService(intent)
        }
    }

    /** Called from the Error screen's "Try Again" button. */
    fun retryDownload() {
        _uiState.update { it.copy(appState = AppState.INITIALIZING, errorMessage = null) }
        viewModelScope.launch {
            if (modelManager.isModelDownloaded()) loadLlm() else startDownload()
        }
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    /**
     * Appends the user message immediately so the UI feels responsive,
     * then runs inference off-main and appends Buddy's reply.
     */
    fun sendMessage(userText: String) {
        if (userText.isBlank() || _uiState.value.isGenerating) return

        val userMessage = ChatMessage(text = userText.trim(), author = MessageAuthor.USER)

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                isGenerating = true
            )
        }

        viewModelScope.launch {
            // Convert existing messages to a format the model manager understands
            // We take the last 10 messages to keep the context window focused and efficient
            val history = _uiState.value.messages.takeLast(10).map { msg ->
                val role = if (msg.author == MessageAuthor.USER) "user" else "model"
                role to msg.text
            }

            val result = modelManager.generateResponse(history)

            val buddyReply = if (result.isSuccess) {
                val text = result.getOrThrow().ifBlank {
                    "Hmm, I'm at a loss for words — which almost never happens! Try again?"
                }
                ChatMessage(text = text, author = MessageAuthor.BUDDY)
            } else {
                ChatMessage(
                    text = "Oops, my brain glitched for a second! Could you repeat that?",
                    author = MessageAuthor.BUDDY
                )
            }

            _uiState.update {
                it.copy(
                    messages = it.messages + buddyReply,
                    isGenerating = false
                )
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun transitionToReady() {
        val greeting = ChatMessage(
            text = "Hey! I'm Buddy 😄 Tell me about your day — I'm all ears!",
            author = MessageAuthor.BUDDY
        )
        _uiState.update {
            it.copy(
                appState = AppState.READY,
                messages = listOf(greeting),
                isGenerating = false
            )
        }
    }

    private fun downloadStatusMessage(progress: Int): String = when {
        progress < 10  -> "Buddy is packing his bags…"
        progress < 35  -> "Loading up the brainpower…"
        progress < 60  -> "Almost halfway there!"
        progress < 80  -> "Buddy is putting on his thinking cap…"
        progress < 95  -> "Just the finishing touches…"
        else           -> "Ready to launch!"
    }

    override fun onCleared() {
        super.onCleared()
        progressCollectorJob?.cancel()
        modelManager.release()
    }
}
