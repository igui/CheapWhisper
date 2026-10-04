package com.example.smartnotetaker

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File

private const val TAG = "VoiceKeyboard"

// Local-Whisper live preview chunking, in PCM bytes (16 kHz mono 16-bit = 32,000 bytes/sec).
private const val PREVIEW_MIN_CHUNK_BYTES = 32_000L    // never transcribe under 1 s
private const val PREVIEW_PAUSE_BYTES = 19_200L        // 0.6 s of silence after speech cuts a chunk
private const val PREVIEW_MAX_CHUNK_BYTES = 192_000L   // ...or force a cut every 6 s
private const val PREVIEW_POLL_MS = 150L

private const val MODE_WRITE = "write"
private const val MODE_MODIFY = "modify"

class VoiceKeyboardService : InputMethodService() {
    private lateinit var wavRecorder: WavRecorder
    private lateinit var aiProcessor: AIProcessor
    private lateinit var modelDownloader: LocalModelDownloader
    private lateinit var usageTracker: UsageTracker

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val wsClient = OkHttpClient()

    // recordMode: null = idle, MODE_WRITE = dictate & commit, MODE_MODIFY = spoken edit instruction.
    private var recordMode: String? = null
    private var processing = false
    private var imeJob: Job? = null

    // Live text while still speaking. Providers with a streaming API get a [LiveTranscriber];
    // local Whisper gets pause-delimited chunk transcription ([previewJob]); other providers
    // get nothing. In write mode the text is shown as composing (underlined) text in the
    // field and replaced by the final result; in modify mode it goes to the transcript box.
    private var liveStream: LiveTranscriber? = null
    private var previewJob: Job? = null
    private var previewText = ""

    private var fieldBeforeDictation = ""

    // Set after a transcription/cleanup failure so the user can retry without re-speaking.
    // Holds a private copy of the recorded audio (the live note_audio.wav is overwritten by
    // the next recording) plus enough context to re-run only the stage that failed.
    private var pendingRetry: PendingDictation? = null
    private var retryStore: DictationRetryStore? = null
    private var recordingEditor = ""
    private var recordingConnection: InputConnection? = null
    private val retryConnections = mutableMapOf<String, InputConnection>()
    private val automaticAttempts = mutableSetOf<String>()
    private var discardCanceledRecording = false
    private var confirmationId: String? = null
    private var confirmationConnection: InputConnection? = null
    private var previewConnection: InputConnection? = null
    private var networkRegistered = false
    private var restoreJob: Job? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { Handler(Looper.getMainLooper()).post { maybeRetryOnline() } }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) Handler(Looper.getMainLooper()).post { maybeRetryOnline() }
        }
    }
    private fun networkAvailable(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
    private fun editorIdentity(): String = currentInputEditorInfo?.let { "${it.packageName}:${it.fieldId}:${it.fieldName}:${it.inputType}" }.orEmpty()
    private fun restoreRetry() {
        if (!isUserUnlocked() || processing || recordMode != null) return
        if (restoreJob?.isActive == true) return
        val editor = editorIdentity(); val text = readFieldText()
        restoreJob = serviceScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val store = retryStore ?: DictationRetryStore(this@VoiceKeyboardService).also { retryStore = it }
                    store.forEditor(editor, text)
                }
                if (!processing && recordMode == null && editorIdentity() == editor && readFieldText() == text) { pendingRetry = saved; finishUi(); maybeRetryOnline() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { setStatus("Saved recording unavailable", Color.RED) }
        }
    }
    private fun maybeRetryOnline() {
        val retry = pendingRetry ?: return
        if (retry.waitingForNetwork && !processing && recordMode == null && isInputViewShown &&
            isUserUnlocked() && networkAvailable() && retryConnections[retry.id] === currentInputConnection &&
            currentInputConnection != null && retry.matches(editorIdentity(), readFieldText()) && retry.id !in automaticAttempts) {
            automaticAttempts.add(retry.id)
            retry()
        }
    }

    // View refs kept so state can be refreshed outside onCreateInputView.
    private var micButton: ImageButton? = null
    private var modifyButton: ImageButton? = null
    private var deleteButton: ImageButton? = null
    private var cancelButton: ImageButton? = null
    private var retryButton: ImageButton? = null
    private var tvStatus: TextView? = null
    private var tvTranscript: TextView? = null
    private var btnCost: TextView? = null
    private var tvCostBreakdown: TextView? = null
    private var tvConfig: TextView? = null
    private var textColorPrimary = Color.BLACK

    // ---------------------------------------------------------------- UI state helpers

    /**
     * The service is direct-boot aware, so it can be bound after a reboot before the user
     * unlocks. Until then credential-encrypted storage (SecureStorage / API keys) is
     * unavailable and must not be touched.
     */
    private fun isUserUnlocked(): Boolean =
        getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    // Re-enables the keyboard once the user unlocks without needing to re-show it.
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshConfigSummary()
            updateButtonStates()
            restoreRetry()
        }
    }

    /** Enables/greys Modify (needs field text); disables everything while busy. */
    private fun updateButtonStates() {
        fun set(b: ImageButton?, enabled: Boolean) {
            b?.let { it.isEnabled = enabled; it.alpha = if (enabled) 1f else 0.4f }
        }
        if (!isUserUnlocked()) {
            set(micButton, false); set(modifyButton, false); set(deleteButton, false)
            cancelButton?.visibility = View.GONE
            retryButton?.visibility = View.GONE
            return
        }
        // The actively-held record button must stay enabled to receive its release event.
        set(micButton, (recordMode == null && !processing) || recordMode == MODE_WRITE)
        set(modifyButton, (recordMode == null && !processing && readFieldText().isNotBlank()) || recordMode == MODE_MODIFY)
        set(deleteButton, recordMode == null && !processing)
        cancelButton?.visibility = if (processing) View.VISIBLE else View.GONE
        // Retry replaces Cancel in the same corner; only one shows at a time.
        retryButton?.visibility = if (!processing && recordMode == null && pendingRetry != null) View.VISIBLE else View.GONE
    }

    private fun setStatus(text: String, color: Int = textColorPrimary) {
        tvStatus?.text = text
        tvStatus?.setTextColor(color)
    }

    private fun showTranscript(text: String) {
        tvTranscript?.let {
            it.text = text
            it.visibility = View.VISIBLE
        }
    }

    private fun hideTranscript() {
        tvTranscript?.visibility = View.GONE
    }

    /** Back to idle: stops mic animation, resets status, re-enables buttons. */
    private fun finishUi() {
        micHandler.removeCallbacks(micLevelRunnable)
        micLevelView?.apply { scaleX = 1f; scaleY = 1f }
        micLevelView = null
        if (pendingRetry != null) setStatus(if (pendingRetry?.waitingForNetwork == true) "Recording saved — waiting for network, or tap Retry" else "Recording saved — tap Retry", Color.RED)
        else setStatus("Hold a button to speak")
        processing = false
        updateButtonStates()
        serviceScope.launch { aiProcessor.releaseLocalModel() }
        maybeRetryOnline()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting) hideTranscript()
        refreshConfigSummary()  // settings may have changed since the keyboard was last shown
        updateButtonStates()
        restoreRetry()
    }

    /** Lower-right summary: "<transcription model> · <language>" / "<cleanup model>". */
    private fun refreshConfigSummary() {
        if (!isUserUnlocked()) {
            tvConfig?.text = "Unlock phone to use CheapWhisper"
            return
        }
        try {
            val ss = SecureStorage(this)
            val language = ss.getTranscribeLanguage()
            val languageLabel = TRANSCRIBE_LANGUAGES.firstOrNull { it.second == language }?.first ?: language
            val llm = ss.getLlmChoice()
            val cleanupLabel = if (llm == "OpenAI") OPENAI_LLM_MODEL else llm
            tvConfig?.text = "${ss.getModelChoice()} · $languageLabel\n$cleanupLabel"
        } catch (e: Exception) {
            Log.w(TAG, "Settings unavailable", e)
            tvConfig?.text = "Settings unavailable"
        }
    }

    /**
     * Stop editor-bound work on focus loss; accepted recordings remain saved for retry.
     */
    override fun onFinishInputView(finishingInput: Boolean) {
        stopDeleting()
        cancelCurrent(silent = true)
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        stopDeleting()
        cancelCurrent(silent = true)
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateButtonStates()  // re-evaluate Modify as the field's text changes
        if (!processing && recordMode == null) {
            if (oldSelStart != newSelStart || oldSelEnd != newSelEnd || pendingRetry?.matches(editorIdentity(), readFieldText()) == false) {
                retryConnections.clear(); confirmationId = null; confirmationConnection = null
            }
            restoreRetry()
        }
    }

    // Live mic-level: scales whichever record button is active while capturing.
    private val micHandler = Handler(Looper.getMainLooper())
    private var micLevelView: View? = null
    private val micLevelRunnable = object : Runnable {
        override fun run() {
            val s = 1f + wavRecorder.amplitude * 0.8f
            micLevelView?.apply { scaleX = s; scaleY = s }
            micHandler.postDelayed(this, 60)
        }
    }

    // ---------------------------------------------------------------- field helpers

    /** Full text of the target input field, or "" if unavailable. */
    private fun readFieldText(): String {
        val ic = currentInputConnection ?: return ""
        return ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString() ?: ""
    }

    /** Replaces the entire target field content with [text]. */
    private fun replaceFieldText(text: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    /** Removes any underlined preview text from the field without committing it. */
    private fun discardPreview() {
        if (previewText.isNotEmpty()) previewConnection?.commitText("", 1)
        previewText = ""
        previewConnection = null
    }

    // Hold-to-repeat backspace, like a standard Android keyboard: starts deleting
    // characters, then switches to whole words once it has been held a while.
    private val deleteHandler = Handler(Looper.getMainLooper())
    private var deleteRepeatCount = 0
    private var deleteConnection: InputConnection? = null
    private var suppressDeleteClick = false
    private val deleteRunnable = object : Runnable {
        override fun run() {
            val connection = deleteConnection ?: return
            if (currentInputConnection !== connection || processing || recordMode != null) { stopDeleting(); return }
            deleteRepeatCount++
            if (!EditorBackspace.delete(connection, wholeWord = deleteRepeatCount >= 20)) { stopDeleting(); return }
            deleteHandler.postDelayed(this, if (deleteRepeatCount >= 20) 90L else (120L - deleteRepeatCount * 4L).coerceAtLeast(40L))
        }
    }
    private fun stopDeleting() {
        deleteHandler.removeCallbacks(deleteRunnable)
        deleteConnection = null
    }

    // ---------------------------------------------------------------- live text

    /** Shows the running transcript where it belongs for [mode]. */
    private fun renderLiveText(mode: String, text: String) {
        previewText = text
        if (mode == MODE_WRITE) {
            previewConnection = currentInputConnection
            previewConnection?.setComposingText(text, 1)
        }
        else showTranscript(if (text.isEmpty()) "Listening…" else text)
    }

    /** The streaming client for [choice], or null when that provider has no streaming API. */
    private fun createLiveTranscriber(
        choice: String, keys: ApiKeys, language: String, onTranscript: (String, String) -> Unit,
    ): LiveTranscriber? = when (choice) {
        PROVIDER_DEEPGRAM -> DeepgramStream(wsClient, keys.deepgram, language, onTranscript)
        PROVIDER_ELEVENLABS -> ElevenLabsStream(wsClient, keys.elevenLabs, language, onTranscript)
        PROVIDER_ASSEMBLYAI -> AssemblyAiStream(wsClient, keys.assemblyAi, language, onTranscript)
        PROVIDER_OPENAI -> OpenAiStream(wsClient, keys.openai, language, onTranscript)
        PROVIDER_SONIOX -> SonioxStream(wsClient, keys.soniox, language, onTranscript)
        else -> null
    }

    /** Opens a streaming session and pipes the recorder's audio into it. */
    private fun startLiveStream(mode: String, stream: LiveTranscriber) {
        liveStream = stream
        stream.start()
        wavRecorder.onPcm = { pcm, len -> stream.send(pcm, len) }
    }

    /** Tears down a streaming session without waiting for its results. */
    private fun abortLiveStream() {
        wavRecorder.onPcm = null
        liveStream?.cancel()
        liveStream = null
    }

    /**
     * Local Whisper has no streaming API, so this mimics whisper.cpp's `stream` example:
     * poll the recorder and, whenever the speaker pauses (or 6 s elapse), transcribe the
     * audio since the previous cut and append it. Runs serially so chunks stay in order.
     * Silent stretches advance the cursor without running the model.
     */
    private fun startChunkedPreview(mode: String, choice: String, keys: ApiKeys, language: String) {
        previewJob = serviceScope.launch {
            var chunkStart = 0L
            var accumulated = ""
            while (isActive && recordMode == mode) {
                delay(PREVIEW_POLL_MS)
                val written = wavRecorder.pcmBytesWritten
                val len = written - chunkStart
                if (len < PREVIEW_MIN_CHUNK_BYTES) continue
                val lastSpeech = wavRecorder.lastSpeechByte
                val hasSpeech = lastSpeech > chunkStart
                val pausedAfterSpeech = hasSpeech && (written - lastSpeech) >= PREVIEW_PAUSE_BYTES
                if (!pausedAfterSpeech && len < PREVIEW_MAX_CHUNK_BYTES) continue
                val chunkEnd = written
                chunkStart = chunkEnd
                if (!hasSpeech) continue  // silence only: nothing to transcribe

                val text = try {
                    val chunkFile = withContext(Dispatchers.IO) {
                        wavRecorder.exportChunk(chunkEnd - len, chunkEnd, File(cacheDir, "preview_chunk.wav"))
                    }
                    aiProcessor.transcribe(
                        choice = choice, language = language, audioFile = chunkFile,
                        wavRecorder = wavRecorder, modelDownloader = modelDownloader,
                        keys = keys, usageTracker = usageTracker,
                        onStatus = { }, onProgress = { },
                    ).trim()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Live preview chunk failed", e)
                    continue
                }
                if (text.isEmpty() || !isActive) continue
                accumulated = if (accumulated.isEmpty()) text else "$accumulated $text"
                renderLiveText(mode, accumulated)
            }
        }
    }

    private fun stopChunkedPreview() {
        previewJob?.cancel()
        previewJob = null
    }

    // ---------------------------------------------------------------- record / process / cancel

    /** False (and toasts) if a required key or mic permission is missing. */
    private fun canRecord(modelChoice: String, llmChoice: String, apiKeys: ApiKeys): Boolean {
        if (!isUserUnlocked()) {
            Toast.makeText(this, "Unlock your phone first", Toast.LENGTH_SHORT).show()
            return false
        }
        val needsTranscribeKey = !isLocalProvider(modelChoice) && apiKeys.keyFor(modelChoice).isEmpty()
        val needsLlmKey = llmChoice == "OpenAI" && apiKeys.openai.isEmpty()
        if (needsTranscribeKey || needsLlmKey) {
            Toast.makeText(this, "Please open Settings to set your API Key", Toast.LENGTH_LONG).show()
            return false
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Open CheapWhisper Settings and tap Grant for microphone access", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    /** Discards any saved retry state and its audio copy. */
    private suspend fun clearPendingRetry(row: PendingDictation) {
        retryConnections.remove(row.id); automaticAttempts.remove(row.id)
        val editor = editorIdentity(); val text = readFieldText()
        withContext(NonCancellable) {
            pendingRetry = withContext(Dispatchers.IO) { retryStore?.remove(row); retryStore?.forEditor(editor, text) }
        }
    }

    private fun startRec(mode: String, activeButton: View) {
        restoreJob?.cancel()
        retryConnections.clear(); confirmationId = null; confirmationConnection = null
        stopDeleting()
        recordMode = mode
        previewText = ""
        fieldBeforeDictation = readFieldText()
        recordingEditor = editorIdentity()
        recordingConnection = currentInputConnection
        setStatus(if (mode == MODE_WRITE) "Recording... release to stop" else "Listening for edit... release to stop", Color.RED)
        if (mode == MODE_MODIFY) showTranscript("Listening…") else hideTranscript()
        micLevelView = activeButton

        val ss = SecureStorage(this)
        val choice = ss.getModelChoice()
        val keys = ss.getApiKeys()
        val language = ss.getTranscribeLanguage()
        val target = currentInputConnection
        var stream: LiveTranscriber? = null
        stream = createLiveTranscriber(choice, keys, language) { finalText, interim ->
            if (liveStream !== stream || currentInputConnection !== target || recordMode != mode) return@createLiveTranscriber
            renderLiveText(mode, listOf(finalText, interim).filter { it.isNotEmpty() }.joinToString(" "))
        }
        when {
            stream != null -> startLiveStream(mode, stream)
            isLocalProvider(choice) -> startChunkedPreview(mode, choice, keys, language)
            // else: provider has no streaming API; text appears on release only.
        }
        wavRecorder.start()  // after the stream hook so no audio frames are missed
        micHandler.post(micLevelRunnable)
        updateButtonStates()  // lock out the other buttons while recording
    }

    /** Stops the mic and abandons the recording without transcribing it. */
    private fun abortRecording() {
        recordMode = null
        stopChunkedPreview()
        abortLiveStream()
        wavRecorder.stop()
        discardPreview()
        hideTranscript()
    }

    /**
     * Cancels whatever is in progress: an active recording is thrown away; an in-flight
     * transcription/cleanup job is cancelled (its finally block restores the UI).
     */
    private fun cancelCurrent(silent: Boolean = false) {
        if (recordMode != null) {
            abortRecording()
            finishUi()
            if (!silent) Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show()
        } else if (processing) {
            if (!silent) discardCanceledRecording = true
            imeJob?.cancel()
            liveStream?.cancel()
            aiProcessor.cancelInFlight()
            if (!silent) Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The post-transcription pipeline shared by [stopAndProcess] and [retry]: run cleanup
     * (write) or the LLM edit (modify), commit to the field, and record undo snapshots.
     * [fieldBefore] is the field content before the dictation, used as the modify base and
     * the first undo step.
     */
    private suspend fun applyResult(
        mode: String, rawText: String, existingText: String, fieldBefore: String,
        expectedEditor: String, llmChoice: String, apiKeys: ApiKeys, cleanupPrompt: String,
    ) {
        val destination = currentInputConnection ?: throw IllegalStateException("Original editor is unavailable. Recording saved.")
        check(editorIdentity() == expectedEditor) { "Return to the original field to retry." }
        if (mode == MODE_MODIFY) check(readFieldText() == existingText) { "Field changed. Restore the original text before retrying this edit." }
        val llmFile: File? = if (llmChoice != "OpenAI") {
            setStatus("Loading LLM (Local)...", Color.GRAY)
            modelDownloader.downloadLlmModel(llmChoice) { } ?: throw Exception("Failed to load local LLM")
        } else null

        currentCoroutineContext().ensureActive()
        check(currentInputConnection === destination && editorIdentity() == expectedEditor) { "Editor changed. Recording saved." }
        if (mode == MODE_WRITE) {
            setStatus("Cleaning up Text...", Color.GRAY)
            // Show the raw words while the LLM works, in case they were not previewed.
            if (rawText.isNotBlank()) renderLiveText(mode, rawText.trim())
            val cleanText = (if (llmChoice == "OpenAI")
                aiProcessor.cleanText(rawText, apiKeys.openai, usageTracker, cleanupPrompt)
            else aiProcessor.cleanTextLocal(rawText, llmFile!!, cleanupPrompt)).trim()
            // commitText replaces the composing (preview) text, if any.
            currentCoroutineContext().ensureActive()
            check(currentInputConnection === destination && editorIdentity() == expectedEditor) { "Editor changed. Recording saved." }
            check(destination.commitText("$cleanText ", 1)) { "Editor rejected text. Recording saved." }
            previewText = ""
            previewConnection = null
        } else {
            showTranscript(rawText.trim().ifEmpty { "(nothing heard)" })
            setStatus("Applying edit...", Color.GRAY)
            val newText = if (llmChoice == "OpenAI")
                aiProcessor.modifyText(existingText, rawText, apiKeys.openai, usageTracker)
            else aiProcessor.modifyTextLocal(existingText, rawText, llmFile!!)
            currentCoroutineContext().ensureActive()
            check(currentInputConnection === destination && editorIdentity() == expectedEditor && readFieldText() == existingText) { "Field changed. Recording saved." }
            replaceFieldText(newText.trim())
        }
    }

    /**
     * Re-runs a failed dictation from the saved audio. If transcription had already
     * succeeded, only the cleanup/edit stage is repeated (no re-transcription, no double
     * transcription billing). Retry requires the original unchanged editor.
     */
    private fun retry() {
        val pr = pendingRetry ?: return
        if (processing || recordMode != null || !isUserUnlocked()) return
        if (!pr.matches(editorIdentity(), readFieldText())) {
            Toast.makeText(this, "Return to the original field and text to retry this recording.", Toast.LENGTH_LONG).show()
            return
        }
        val connection = currentInputConnection ?: return
        if (retryConnections[pr.id] !== connection) {
            if (confirmationId != pr.id || confirmationConnection !== connection) {
                confirmationId = pr.id; confirmationConnection = connection
                Toast.makeText(this, "Saved in an earlier editor session. Tap Retry again to apply it to this field.", Toast.LENGTH_LONG).show()
                return
            }
            retryConnections[pr.id] = connection
        }
        confirmationId = null; confirmationConnection = null
        restoreJob?.cancel()
        discardCanceledRecording = false
        processing = true
        setStatus("Retrying…", Color.GRAY)
        updateButtonStates()

        val ss = SecureStorage(this)
        val apiKeys = ss.getApiKeys().copy(openrouterModel = pr.openRouterModel)
        val language = pr.language
        val llmChoice = pr.llm
        val modelChoice = pr.provider
        val cleanupPrompt = pr.cleanupPrompt

        imeJob = serviceScope.launch {
            try {
                val rawText = pr.rawText ?: aiProcessor.transcribe(
                    choice = modelChoice, language = language, audioFile = pr.wav,
                    wavRecorder = wavRecorder, modelDownloader = modelDownloader,
                    keys = apiKeys, usageTracker = usageTracker,
                    onStatus = { setStatus(it, Color.GRAY) }, onProgress = { },
                ).also { text ->
                    val updated = pr.copy(rawText = text, waitingForNetwork = false)
                    pendingRetry = updated
                    withContext(Dispatchers.IO) { runCatching { retryStore?.save(updated) } }
                }
                applyResult(
                    pr.mode, rawText,
                    if (pr.mode == MODE_MODIFY) pr.fieldBefore else "",
                    pr.fieldBefore, pr.editor, llmChoice, apiKeys, cleanupPrompt,
                )
                btnCost?.let { c -> tvCostBreakdown?.let { b -> refreshCostViews(c, b) } }
                clearPendingRetry(pr)
            } catch (e: CancellationException) {
                discardPreview()
                if (pr.mode == MODE_MODIFY) hideTranscript()
                if (discardCanceledRecording) clearPendingRetry(pr)
                else withContext(NonCancellable) {
                    val saved = (pendingRetry ?: pr).copy(waitingForNetwork = false)
                    withContext(Dispatchers.IO) { runCatching { retryStore?.save(saved) } }
                    pendingRetry = saved
                }
                throw e
            } catch (e: Exception) {
                discardPreview()
                if (pr.mode == MODE_MODIFY) hideTranscript()
                val saved = (pendingRetry ?: pr).copy(waitingForNetwork = !networkAvailable())
                pendingRetry = saved
                withContext(Dispatchers.IO) { runCatching { retryStore?.save(saved) } }
                if (isActive) Toast.makeText(this@VoiceKeyboardService, "Retry failed; recording kept: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                imeJob = null
                finishUi()
            }
        }
    }

    /**
     * Stop + run the pipeline. Write => transcribe + cleanup + commit;
     * Modify => transcribe (edit instruction) + LLM-rewrite the field's text.
     */
    private fun stopAndProcess() {
        val mode = recordMode ?: return
        recordMode = null
        stopChunkedPreview()
        micHandler.removeCallbacks(micLevelRunnable)
        micLevelView?.apply { scaleX = 1f; scaleY = 1f }
        micLevelView = null
        setStatus("Processing...", Color.GRAY)
        discardCanceledRecording = false
        processing = true
        updateButtonStates()

        val secureStorage = SecureStorage(this)
        val apiKeys = secureStorage.getApiKeys()
        val transcribeLanguage = secureStorage.getTranscribeLanguage()
        val llmChoice = secureStorage.getLlmChoice()
        val modelChoice = secureStorage.getModelChoice()
        val cleanupPrompt = secureStorage.getCleanupPrompt()
        val existingText = if (mode == MODE_MODIFY) fieldBeforeDictation else ""

        wavRecorder.onPcm = null
        val file = wavRecorder.stop()
        if (file == null || file.length() - 44 < MIN_RECORDING_BYTES) {
            if (file != null) Toast.makeText(this, "Recording too short", Toast.LENGTH_SHORT).show()
            abortLiveStream()
            discardPreview()
            hideTranscript()
            finishUi()
            return
        }
        val audioSeconds = (file.length() - 44) / 32000.0
        val originalField = fieldBeforeDictation
        val originalEditor = recordingEditor
        val originalConnection = recordingConnection

        imeJob = serviceScope.launch {
            var savedRecording: PendingDictation? = null
            try {
                savedRecording = try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val store = retryStore ?: DictationRetryStore(this@VoiceKeyboardService).also { retryStore = it }
                        store.enqueue(file, mode, originalField, originalEditor, modelChoice, transcribeLanguage, apiKeys.openrouterModel, llmChoice, cleanupPrompt)
                    }
                } catch (_: Exception) {
                    Toast.makeText(this@VoiceKeyboardService, "Retry storage unavailable; processing this recording without a saved copy.", Toast.LENGTH_LONG).show()
                    null
                }
                if (savedRecording != null) {
                    pendingRetry = savedRecording
                    if (originalConnection != null) retryConnections[savedRecording!!.id] = originalConnection
                }
                currentCoroutineContext().ensureActive()
                check(currentInputConnection === originalConnection) { "Editor changed. Recording saved." }
                suspend fun transcribeFile(): String = aiProcessor.transcribe(
                    choice = modelChoice,
                    language = transcribeLanguage,
                    audioFile = file,
                    wavRecorder = wavRecorder,
                    modelDownloader = modelDownloader,
                    keys = apiKeys,
                    usageTracker = usageTracker,
                    onStatus = { setStatus(it, Color.GRAY) },
                    onProgress = { },
                )

                val stream = liveStream
                val rawText = if (stream != null) {
                    // Streaming already transcribed everything; just let the provider flush.
                    setStatus("Finishing ($modelChoice)…", Color.GRAY)
                    try {
                        val text = stream.finish()
                        usageTracker.add(modelChoice, CostEstimator.streamingMicros(modelChoice, audioSeconds))
                        text
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Socket died mid-dictation: the WAV still has everything, so
                        // fall back to the one-shot request.
                        Log.w(TAG, "$modelChoice stream failed, falling back to one-shot", e)
                        transcribeFile()
                    }
                } else transcribeFile()
                liveStream = null
                savedRecording = savedRecording?.copy(rawText = rawText)
                savedRecording?.let { saved ->
                    pendingRetry = saved
                    withContext(Dispatchers.IO) { runCatching { retryStore?.save(saved) } }
                }

                applyResult(mode, rawText, existingText, originalField, originalEditor, llmChoice, apiKeys, cleanupPrompt)
                btnCost?.let { c -> tvCostBreakdown?.let { b -> refreshCostViews(c, b) } }
                savedRecording?.let { clearPendingRetry(it) }
            } catch (e: CancellationException) {
                abortLiveStream()
                discardPreview()  // user cancelled: drop the underlined preview too
                if (mode == MODE_MODIFY) hideTranscript()
                if (discardCanceledRecording) savedRecording?.let { clearPendingRetry(it) }
                throw e
            } catch (e: Exception) {
                abortLiveStream()
                if (isActive) {
                    discardPreview()                       // drop the underlined partial text
                    if (mode == MODE_MODIFY) hideTranscript()
                    savedRecording?.let { record ->
                        val saved = record.copy(waitingForNetwork = !networkAvailable())
                        pendingRetry = saved
                        withContext(Dispatchers.IO) { runCatching { retryStore?.save(saved) } }
                    }
                    Toast.makeText(this@VoiceKeyboardService, if (savedRecording != null) "Recording saved: ${e.message}" else "Could not save recording: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                liveStream = null
                imeJob = null
                finishUi()
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle / view

    override fun onCreate() {
        super.onCreate()
        // Nothing here may touch credential-encrypted storage: the service can be created
        // before the user unlocks after a reboot (directBootAware). UsageTracker uses
        // device-protected storage; the others hold no prefs.
        wavRecorder = WavRecorder(this)
        aiProcessor = AIProcessor()
        modelDownloader = LocalModelDownloader(this)
        usageTracker = UsageTracker(this)
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        runCatching { getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback); networkRegistered = true }
        restoreRetry()
    }

    /** Updates the bottom-right total and the expanded per-provider breakdown. */
    private fun refreshCostViews(costButton: TextView, breakdown: TextView) {
        serviceScope.launch {
            val values = withContext(Dispatchers.IO) { usageTracker.totalMicros() to usageTracker.byProvider().filter { it.second > 0L } }
            costButton.text = UsageTracker.formatUsd(values.first)
            val lines = if (values.second.isEmpty()) "No usage yet." else values.second.joinToString("\n") { (provider, micros) ->
                if (provider == PROVIDER_OPENROUTER) "$provider: ${UsageTracker.formatUsd(micros)}"
                else "$provider (${CostEstimator.formatPerHour(provider)}): ${UsageTracker.formatUsd(micros)}"
            }
            breakdown.text = "$lines\n—\nTotal: ${UsageTracker.formatUsd(values.first)}"
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val layout = layoutInflater.inflate(R.layout.keyboard_view, null)

        val btnBack = layout.findViewById<ImageButton>(R.id.btn_back)
        val btnSettings = layout.findViewById<ImageButton>(R.id.btn_settings)
        val btnMic = layout.findViewById<ImageButton>(R.id.btn_mic)
        val btnDelete = layout.findViewById<ImageButton>(R.id.btn_delete)
        val btnModify = layout.findViewById<ImageButton>(R.id.btn_modify)
        val btnCancel = layout.findViewById<ImageButton>(R.id.btn_cancel)
        val btnRetry = layout.findViewById<ImageButton>(R.id.btn_retry)
        val status = layout.findViewById<TextView>(R.id.tv_status)
        val transcript = layout.findViewById<TextView>(R.id.tv_transcript)
        val cost = layout.findViewById<TextView>(R.id.btn_cost)
        val costPanel = layout.findViewById<LinearLayout>(R.id.cost_panel)
        val costBreakdown = layout.findViewById<TextView>(R.id.tv_cost_breakdown)
        tvConfig = layout.findViewById(R.id.tv_config)

        micButton = btnMic
        modifyButton = btnModify
        deleteButton = btnDelete
        cancelButton = btnCancel
        retryButton = btnRetry
        tvStatus = status
        tvTranscript = transcript
        btnCost = cost
        tvCostBreakdown = costBreakdown
        transcript.movementMethod = ScrollingMovementMethod()

        // Resolve primary text color from the theme (for the idle status text).
        val typedValue = TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        textColorPrimary = if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            typedValue.data
        } else {
            ContextCompat.getColor(this, typedValue.resourceId)
        }

        refreshCostViews(cost, costBreakdown)
        refreshConfigSummary()
        cost.setOnClickListener {
            costPanel.visibility = if (costPanel.visibility == View.GONE) {
                refreshCostViews(cost, costBreakdown)
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        btnBack.setOnClickListener { requestHideSelf(0) }

        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            requestHideSelf(0)
        }

        btnDelete.setOnClickListener {
            if (!suppressDeleteClick && !processing && recordMode == null) currentInputConnection?.let { EditorBackspace.delete(it) }
        }
        btnDelete.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopDeleting()
                    if (!processing && recordMode == null && isUserUnlocked()) {
                        deleteRepeatCount = 0
                        deleteConnection = currentInputConnection
                        deleteConnection?.let { EditorBackspace.delete(it) }
                        deleteHandler.postDelayed(deleteRunnable, 400L)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopDeleting()
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        suppressDeleteClick = true
                        try { view.performClick() } finally { suppressDeleteClick = false }
                    }
                    true
                }
                else -> true
            }
        }

        // Push-to-talk: hold to record, release to stop + process. Returns a touch handler
        // bound to [mode]; the button only fires when enabled (disabled => no recording).
        fun recordTouch(mode: String): View.OnTouchListener = View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isUserUnlocked()) {
                        Toast.makeText(this, "Unlock your phone first", Toast.LENGTH_SHORT).show()
                    } else if (recordMode == null && !processing) {
                        val ss = SecureStorage(this)
                        if (canRecord(ss.getModelChoice(), ss.getLlmChoice(), ss.getApiKeys())) startRec(mode, v)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    if (recordMode == mode) stopAndProcess()
                    true
                }
                else -> false
            }
        }

        btnMic.setOnTouchListener(recordTouch(MODE_WRITE))
        btnModify.setOnTouchListener(recordTouch(MODE_MODIFY))

        // Cancel the in-flight request via the red X or by tapping the status line.
        btnCancel.setOnClickListener { cancelCurrent() }
        status.setOnClickListener { if (processing) cancelCurrent() }
        btnRetry.setOnClickListener { retry() }
        btnRetry.contentDescription = "Retry saved recording; hold to discard"
        btnRetry.setOnLongClickListener {
            val row = pendingRetry
            if (row != null && !processing) serviceScope.launch {
                clearPendingRetry(row); finishUi()
                Toast.makeText(this@VoiceKeyboardService, "Saved recording discarded", Toast.LENGTH_SHORT).show()
            }
            true
        }

        updateButtonStates()
        return layout
    }

    override fun onDestroy() {
        super.onDestroy()
        if (networkRegistered) runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback) }
        try { unregisterReceiver(unlockReceiver) } catch (e: IllegalArgumentException) { }
        imeJob?.cancel()
        previewJob?.cancel()
        abortLiveStream()
        serviceJob.cancel()
        deleteHandler.removeCallbacks(deleteRunnable)
        micHandler.removeCallbacks(micLevelRunnable)
        if (recordMode != null) {
            wavRecorder.stop()
        }
    }
}
