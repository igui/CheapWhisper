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

    private var isRecording = false

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
        costButton.text = UsageTracker.formatUsd(usageTracker.totalMicros())
        breakdown.text = usageTracker.byProvider()
            .joinToString("\n") { (provider, micros) -> "$provider: ${UsageTracker.formatUsd(micros)}" } +
            "\n—\nTotal: ${UsageTracker.formatUsd(usageTracker.totalMicros())}"
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

        btnMic.setOnClickListener {
            // Read settings on-click to ensure we pick up fresh changes from SettingsActivity
            val secureStorage = SecureStorage(this)
            val apiKeys = secureStorage.getApiKeys()
            val transcribeLanguage = secureStorage.getTranscribeLanguage()
            val llmChoice = secureStorage.getLlmChoice()
            val modelChoice = secureStorage.getModelChoice()
            
            // Resolve primary text color dynamically from the theme
            val typedValue = TypedValue()
            theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            val textColorPrimary = if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                typedValue.data
            } else {
                ContextCompat.getColor(this, typedValue.resourceId)
            }
            
            val needsTranscribeKey = !isLocalProvider(modelChoice) && apiKeys.keyFor(modelChoice).isEmpty()
            val needsLlmKey = llmChoice == "OpenAI" && apiKeys.openai.isEmpty()
            if (needsTranscribeKey || needsLlmKey) {
                Toast.makeText(this, "Please open Settings to set your API Key", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Please open CheapWhisper app and grant microphone permissions", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (!isRecording) {
                isRecording = true
                tvStatus.text = "Recording... Tap mic to Stop"
                tvStatus.setTextColor(Color.RED)
                wavRecorder.start()
            } else {
                isRecording = false
                tvStatus.text = "Processing..."
                tvStatus.setTextColor(Color.GRAY)
                btnMic.isEnabled = false
                
                val file = wavRecorder.stop()
                if (file != null) {
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

                            val cleanText: String
                            if (llmChoice == "OpenAI") {
                                tvStatus.text = "Cleaning up Text (Cloud)..."
                                cleanText = aiProcessor.cleanText(rawText, apiKeys.openai, usageTracker)
                            } else {
                                val llmFile = modelDownloader.downloadLlmModel(llmChoice) { }
                                if (llmFile == null) throw Exception("Failed to load local LLM")
                                tvStatus.text = "Cleaning up Text (Local)..."
                                cleanText = aiProcessor.cleanTextLocal(rawText, llmFile)
                            }

                            currentInputConnection?.commitText("${cleanText} ", 1)
                            refreshCostViews(btnCost, tvCostBreakdown)
                        } catch (e: Exception) {
                            Toast.makeText(this@VoiceKeyboardService, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            tvStatus.text = "Touch and speak"
                            tvStatus.setTextColor(textColorPrimary) 
                            btnMic.isEnabled = true
                        }
                    }
                } else {
                    tvStatus.text = "Touch and speak"
                    tvStatus.setTextColor(textColorPrimary)
                    btnMic.isEnabled = true
                }
            }
        }

        return layout
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        deleteHandler.removeCallbacks(deleteRunnable)
        if (isRecording) {
            wavRecorder.stop()
        }
    }
}
