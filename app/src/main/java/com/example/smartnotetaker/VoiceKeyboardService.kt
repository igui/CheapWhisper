package com.example.smartnotetaker

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.Intent
import android.content.res.ColorStateList
import android.util.TypedValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class VoiceKeyboardService : InputMethodService() {
    private lateinit var wavRecorder: WavRecorder
    private lateinit var aiProcessor: AIProcessor
    private lateinit var modelDownloader: LocalModelDownloader
    private lateinit var usageTracker: UsageTracker

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // recordMode: null = idle, "write" = dictate & commit, "modify" = spoken edit instruction.
    private var recordMode: String? = null
    private var processing = false
    private var imeJob: Job? = null
    private val undoStack = ArrayDeque<String>()

    // Button refs kept so enabled/disabled state can be refreshed outside onCreateInputView.
    private var micButton: ImageButton? = null
    private var modifyButton: ImageButton? = null
    private var undoButton: ImageButton? = null

    /** Enables/greys Modify (needs field text) and Undo (needs history); disables all while busy. */
    private fun updateButtonStates() {
        fun set(b: ImageButton?, enabled: Boolean) {
            b?.let { it.isEnabled = enabled; it.alpha = if (enabled) 1f else 0.4f }
        }
        // The actively-held record button must stay enabled to receive its release event.
        set(micButton, (recordMode == null && !processing) || recordMode == "write")
        set(modifyButton, (recordMode == null && !processing && readFieldText().isNotBlank()) || recordMode == "modify")
        set(undoButton, recordMode == null && !processing && undoStack.isNotEmpty())
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        updateButtonStates()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateButtonStates()  // re-evaluate Modify as the field's text changes
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

    // Hold-to-repeat backspace, like a standard Android keyboard: starts deleting
    // characters, then switches to whole words once it has been held a while.
    private val deleteHandler = Handler(Looper.getMainLooper())
    private var deleteRepeatCount = 0
    private val deleteRunnable = object : Runnable {
        override fun run() {
            val ic = currentInputConnection ?: return
            deleteRepeatCount++
            // First ~20 repeats delete characters; after that, accelerate to words.
            if (deleteRepeatCount < 20) {
                ic.deleteSurroundingText(1, 0)
                deleteHandler.postDelayed(this, (120L - deleteRepeatCount * 4L).coerceAtLeast(40L))
            } else {
                deleteLastWord(ic)
                deleteHandler.postDelayed(this, 90L)
            }
        }
    }

    /** Deletes the run of trailing whitespace plus the word before the cursor. */
    private fun deleteLastWord(ic: InputConnection) {
        val before = ic.getTextBeforeCursor(64, 0) ?: return
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        ic.deleteSurroundingText((before.length - i).coerceAtLeast(1), 0)
    }

    override fun onCreate() {
        super.onCreate()
        wavRecorder = WavRecorder(this)
        aiProcessor = AIProcessor()
        modelDownloader = LocalModelDownloader(this)
        usageTracker = UsageTracker(this)
    }

    /** Updates the bottom-right total and the expanded per-provider breakdown. */
    private fun refreshCostViews(costButton: TextView, breakdown: TextView) {
        val total = usageTracker.totalMicros()
        costButton.text = UsageTracker.formatUsd(total)
        val rows = usageTracker.byProvider().filter { it.second > 0L }  // omit no-usage providers
        val lines = if (rows.isEmpty()) "No usage yet."
        else rows.joinToString("\n") { (provider, micros) ->
            "$provider (${CostEstimator.formatPerHour(provider)}): ${UsageTracker.formatUsd(micros)}"
        }
        breakdown.text = "$lines\n—\nTotal: ${UsageTracker.formatUsd(total)}"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val layout = layoutInflater.inflate(R.layout.keyboard_view, null)

        val btnBack = layout.findViewById<ImageButton>(R.id.btn_back)
        val btnSettings = layout.findViewById<ImageButton>(R.id.btn_settings)
        val btnMic = layout.findViewById<ImageButton>(R.id.btn_mic)
        val btnDelete = layout.findViewById<ImageButton>(R.id.btn_delete)
        val tvStatus = layout.findViewById<TextView>(R.id.tv_status)
        val btnCost = layout.findViewById<TextView>(R.id.btn_cost)
        val costPanel = layout.findViewById<LinearLayout>(R.id.cost_panel)
        val tvCostBreakdown = layout.findViewById<TextView>(R.id.tv_cost_breakdown)

        refreshCostViews(btnCost, tvCostBreakdown)

        btnCost.setOnClickListener {
            costPanel.visibility = if (costPanel.visibility == View.GONE) {
                refreshCostViews(btnCost, tvCostBreakdown)
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        btnBack.setOnClickListener {
            requestHideSelf(0)
        }

        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            requestHideSelf(0)
        }

        btnDelete.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    deleteRepeatCount = 0
                    currentInputConnection?.deleteSurroundingText(1, 0)  // immediate single delete on tap
                    deleteHandler.postDelayed(deleteRunnable, 400L)       // begin repeating after a hold
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    deleteHandler.removeCallbacks(deleteRunnable)
                    if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    true
                }
                else -> false
            }
        }

        val btnModify = layout.findViewById<ImageButton>(R.id.btn_modify)
        val btnUndo = layout.findViewById<ImageButton>(R.id.btn_undo)
        micButton = btnMic
        modifyButton = btnModify
        undoButton = btnUndo

        // Resolve primary text color from the theme (for the idle status text).
        val typedValue = TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        val textColorPrimary = if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            typedValue.data
        } else {
            ContextCompat.getColor(this, typedValue.resourceId)
        }

        fun finishUi() {
            micHandler.removeCallbacks(micLevelRunnable)
            micLevelView?.apply { scaleX = 1f; scaleY = 1f }
            micLevelView = null
            tvStatus.text = "Hold a button to speak"
            tvStatus.setTextColor(textColorPrimary)
            processing = false
            updateButtonStates()
        }

        // False (and toasts) if a required key or mic permission is missing.
        fun canRecord(modelChoice: String, llmChoice: String, apiKeys: ApiKeys): Boolean {
            val needsTranscribeKey = !isLocalProvider(modelChoice) && apiKeys.keyFor(modelChoice).isEmpty()
            val needsLlmKey = llmChoice == "OpenAI" && apiKeys.openai.isEmpty()
            if (needsTranscribeKey || needsLlmKey) {
                Toast.makeText(this, "Please open Settings to set your API Key", Toast.LENGTH_LONG).show()
                return false
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Please open CheapWhisper app and grant microphone permissions", Toast.LENGTH_LONG).show()
                return false
            }
            return true
        }

        fun startRec(mode: String, activeButton: View) {
            recordMode = mode
            tvStatus.text = if (mode == "write") "Recording... release to stop" else "Listening for edit... release to stop"
            tvStatus.setTextColor(Color.RED)
            micLevelView = activeButton
            wavRecorder.start()
            micHandler.post(micLevelRunnable)
            updateButtonStates()  // lock out the other buttons while recording
        }

        // Stop + run the pipeline. Write => transcribe + cleanup + commit;
        // Modify => transcribe (edit instruction) + LLM-rewrite the field's text.
        fun stopAndProcess() {
            val mode = recordMode ?: return
            recordMode = null
            micHandler.removeCallbacks(micLevelRunnable)
            micLevelView?.apply { scaleX = 1f; scaleY = 1f }
            micLevelView = null
            tvStatus.text = "Processing..."
            tvStatus.text = "Processing... (tap to cancel)"
            tvStatus.setTextColor(Color.GRAY)
            processing = true
            updateButtonStates()

            val secureStorage = SecureStorage(this)
            val apiKeys = secureStorage.getApiKeys()
            val transcribeLanguage = secureStorage.getTranscribeLanguage()
            val llmChoice = secureStorage.getLlmChoice()
            val modelChoice = secureStorage.getModelChoice()
            val existingText = if (mode == "modify") readFieldText() else ""

            val file = wavRecorder.stop()
            if (file == null) { finishUi(); return }
            if (file.length() - 44 < MIN_RECORDING_BYTES) {
                Toast.makeText(this, "Recording too short", Toast.LENGTH_SHORT).show()
                finishUi()
                return
            }

            imeJob = serviceScope.launch {
                try {
                    val rawText = aiProcessor.transcribe(
                        choice = modelChoice,
                        language = transcribeLanguage,
                        audioFile = file,
                        wavRecorder = wavRecorder,
                        modelDownloader = modelDownloader,
                        keys = apiKeys,
                        usageTracker = usageTracker,
                        onStatus = { tvStatus.text = it },
                        onProgress = { },
                    )

                    val llmFile: java.io.File? = if (llmChoice != "OpenAI") {
                        tvStatus.text = "Loading LLM (Local)..."
                        modelDownloader.downloadLlmModel(llmChoice) { } ?: throw Exception("Failed to load local LLM")
                    } else null

                    if (mode == "write") {
                        tvStatus.text = "Cleaning up Text..."
                        val cleanText = if (llmChoice == "OpenAI")
                            aiProcessor.cleanText(rawText, apiKeys.openai, usageTracker)
                        else aiProcessor.cleanTextLocal(rawText, llmFile!!)
                        currentInputConnection?.commitText("$cleanText ", 1)
                    } else {
                        tvStatus.text = "Applying edit..."
                        val newText = if (llmChoice == "OpenAI")
                            aiProcessor.modifyText(existingText, rawText, apiKeys.openai, usageTracker)
                        else aiProcessor.modifyTextLocal(existingText, rawText, llmFile!!)
                        undoStack.addLast(existingText)
                        replaceFieldText(newText.trim())
                    }
                    refreshCostViews(btnCost, tvCostBreakdown)
                } catch (e: CancellationException) {
                    throw e  // user cancelled; cancelActive() resets the UI
                } catch (e: Exception) {
                    if (isActive) Toast.makeText(this@VoiceKeyboardService, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    imeJob = null
                    finishUi()
                }
            }
        }

        // Push-to-talk: hold to record, release to stop + process. Returns a touch handler
        // bound to [mode]; the button only fires when enabled (disabled => no recording).
        fun recordTouch(mode: String): View.OnTouchListener = View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (recordMode == null && !processing) {
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

        btnMic.setOnTouchListener(recordTouch("write"))
        btnModify.setOnTouchListener(recordTouch("modify"))

        btnUndo.setOnClickListener {
            if (recordMode != null || processing) return@setOnClickListener
            if (undoStack.isNotEmpty()) replaceFieldText(undoStack.removeLast())
            updateButtonStates()
        }

        // Tap the status line while a request is running to cancel it.
        tvStatus.setOnClickListener {
            if (processing) {
                imeJob?.cancel()
                aiProcessor.cancelInFlight()
                Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show()
                // finishUi() runs via the cancelled job's finally block.
            }
        }

        updateButtonStates()
        return layout
    }

    override fun onDestroy() {
        super.onDestroy()
        imeJob?.cancel()
        serviceJob.cancel()
        deleteHandler.removeCallbacks(deleteRunnable)
        micHandler.removeCallbacks(micLevelRunnable)
        if (recordMode != null) {
            wavRecorder.stop()
        }
    }
}
