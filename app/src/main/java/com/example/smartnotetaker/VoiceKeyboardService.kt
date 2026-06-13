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
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Button
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
    private val undoStack = ArrayDeque<String>()

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

        val btnModify = layout.findViewById<Button>(R.id.btn_modify)
        val btnUndo = layout.findViewById<Button>(R.id.btn_undo)

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
            tvStatus.text = "Touch and speak"
            tvStatus.setTextColor(textColorPrimary)
            btnMic.isEnabled = true
            btnModify.isEnabled = true
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
            tvStatus.text = if (mode == "write") "Recording... Tap to Stop" else "Listening for edit... Tap to Stop"
            tvStatus.setTextColor(Color.RED)
            micLevelView = activeButton
            wavRecorder.start()
            micHandler.post(micLevelRunnable)
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
            tvStatus.setTextColor(Color.GRAY)
            btnMic.isEnabled = false
            btnModify.isEnabled = false

            val secureStorage = SecureStorage(this)
            val apiKeys = secureStorage.getApiKeys()
            val transcribeLanguage = secureStorage.getTranscribeLanguage()
            val llmChoice = secureStorage.getLlmChoice()
            val modelChoice = secureStorage.getModelChoice()
            val existingText = if (mode == "modify") readFieldText() else ""

            val file = wavRecorder.stop()
            if (file == null) { finishUi(); return }

            serviceScope.launch {
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
                } catch (e: Exception) {
                    Toast.makeText(this@VoiceKeyboardService, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    finishUi()
                }
            }
        }

        btnMic.setOnClickListener {
            when {
                recordMode == "write" -> stopAndProcess()
                recordMode == null -> {
                    val ss = SecureStorage(this)
                    if (canRecord(ss.getModelChoice(), ss.getLlmChoice(), ss.getApiKeys())) startRec("write", btnMic)
                }
            }
        }

        btnModify.setOnClickListener {
            when {
                recordMode == "modify" -> stopAndProcess()
                recordMode == null -> {
                    if (readFieldText().isBlank()) {
                        Toast.makeText(this, "No text to modify", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val ss = SecureStorage(this)
                    if (canRecord(ss.getModelChoice(), ss.getLlmChoice(), ss.getApiKeys())) startRec("modify", btnModify)
                }
            }
        }

        btnUndo.setOnClickListener {
            if (recordMode != null) return@setOnClickListener
            if (undoStack.isNotEmpty()) replaceFieldText(undoStack.removeLast())
            else Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
        }

        return layout
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        deleteHandler.removeCallbacks(deleteRunnable)
        micHandler.removeCallbacks(micLevelRunnable)
        if (recordMode != null) {
            wavRecorder.stop()
        }
    }
}
