package com.example.smartnotetaker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig

// --- CONFIGURATION ---
const val WHISPER_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
const val LLM_ENDPOINT = "https://api.openai.com/v1/chat/completions"
const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
const val DEEPGRAM_ENDPOINT = "https://api.deepgram.com/v1/listen"
const val ELEVENLABS_ENDPOINT = "https://api.elevenlabs.io/v1/speech-to-text"
const val ASSEMBLYAI_BASE = "https://api.assemblyai.com/v2"

// --- TRANSCRIPTION PROVIDERS (values persisted as model_choice) ---
const val PROVIDER_OPENAI = "OpenAI"
const val PROVIDER_DEEPGRAM = "Deepgram"
const val PROVIDER_GROQ = "Groq"
const val PROVIDER_ELEVENLABS = "ElevenLabs"
const val PROVIDER_ASSEMBLYAI = "AssemblyAI"
const val PROVIDER_LOCAL_TINY = "Local (tiny)"
const val PROVIDER_LOCAL_BASE = "Local (base)"
const val PROVIDER_LOCAL_SMALL = "Local (small)"

// Cleanup/modify LLM spend is metered into its own bucket, separate from any
// same-vendor transcription spend (so OpenAI transcription vs. cleanup are distinct lines).
const val PROVIDER_LLM = "OpenAI (cleanup LLM)"

val TRANSCRIPTION_PROVIDERS = listOf(
    PROVIDER_OPENAI, PROVIDER_DEEPGRAM, PROVIDER_GROQ, PROVIDER_ELEVENLABS, PROVIDER_ASSEMBLYAI,
    PROVIDER_LOCAL_TINY, PROVIDER_LOCAL_BASE, PROVIDER_LOCAL_SMALL,
)

fun isLocalProvider(choice: String): Boolean = choice.startsWith("Local")

/** Language options for the Settings dropdown: display label -> code ("auto" = auto-detect). */
val TRANSCRIBE_LANGUAGES = listOf(
    "Auto-detect" to "auto",
    "English" to "en",
    "Spanish" to "es",
    "French" to "fr",
    "German" to "de",
    "Italian" to "it",
    "Portuguese" to "pt",
    "Dutch" to "nl",
    "Russian" to "ru",
    "Chinese" to "zh",
    "Japanese" to "ja",
    "Korean" to "ko",
    "Hindi" to "hi",
    "Arabic" to "ar",
)

/** Holds every transcription provider's API key. Local providers need none. */
data class ApiKeys(
    val openai: String,
    val deepgram: String,
    val groq: String,
    val elevenLabs: String,
    val assemblyAi: String,
) {
    /** The key required for [choice], or "" for local providers (no key needed). */
    fun keyFor(choice: String): String = when (choice) {
        PROVIDER_OPENAI -> openai
        PROVIDER_DEEPGRAM -> deepgram
        PROVIDER_GROQ -> groq
        PROVIDER_ELEVENLABS -> elevenLabs
        PROVIDER_ASSEMBLYAI -> assemblyAi
        else -> ""
    }
}

// --- THIRD-PARTY COST TRACKING ---
// Paid third-party providers whose usage we meter. Local Whisper / Gemma run
// on-device and cost nothing, so they are intentionally excluded.
val COST_PROVIDERS = listOf(
    PROVIDER_OPENAI, PROVIDER_DEEPGRAM, PROVIDER_GROQ, PROVIDER_ELEVENLABS, PROVIDER_ASSEMBLYAI,
    PROVIDER_LLM,
)

/** Estimates USD cost (returned as integer micro-dollars to avoid float drift). */
object CostEstimator {
    // gpt-4o-mini token pricing (USD per token).
    private const val GPT4O_MINI_IN = 0.15 / 1_000_000
    private const val GPT4O_MINI_OUT = 0.60 / 1_000_000
    // ~160 words/min of speech ≈ ~500 tokens/min in; cleanup output is of similar length.
    private const val LLM_TOKENS_PER_MIN = 500

    /** Estimated cleanup-LLM (gpt-4o-mini) cost per minute of speech. */
    fun llmUsdPerMinute(): Double =
        LLM_TOKENS_PER_MIN * GPT4O_MINI_IN + LLM_TOKENS_PER_MIN * GPT4O_MINI_OUT

    /** Published pay-as-you-go transcription rate, USD per minute (0 for on-device). */
    fun usdPerMinute(provider: String): Double = when (provider) {
        PROVIDER_OPENAI -> 0.006
        PROVIDER_GROQ -> 0.04 / 60.0          // $0.04/hr
        PROVIDER_DEEPGRAM -> 0.0077
        PROVIDER_ELEVENLABS -> 0.40 / 60.0    // $0.40/hr
        PROVIDER_ASSEMBLYAI -> 0.27 / 60.0    // $0.27/hr
        PROVIDER_LLM -> llmUsdPerMinute()
        else -> 0.0
    }

    /** Cleanup-model picker label: FREE on-device, else the per-minute LLM estimate. */
    fun llmRateLabel(llmChoice: String): String =
        if (llmChoice == "OpenAI") "est. $" + String.format("%.4f", llmUsdPerMinute()) + "/min" else "FREE"

    /** Human-readable per-minute estimate, e.g. "$0.0077/min" (or "free" on-device). */
    fun formatPerMin(provider: String): String {
        val rate = usdPerMinute(provider)
        return if (rate <= 0.0) "free" else "$" + String.format("%.4f", rate) + "/min"
    }

    /** Picker label: "FREE" for on-device, otherwise "est. $X/min". */
    fun rateLabel(provider: String): String {
        val rate = usdPerMinute(provider)
        return if (rate <= 0.0) "FREE" else "est. $" + String.format("%.4f", rate) + "/min"
    }

    fun transcriptionMicros(provider: String, audioDurationSec: Double): Long =
        Math.round(usdPerMinute(provider) * (audioDurationSec / 60.0) * 1_000_000)

    /** Exact OpenAI gpt-4o-mini cost from returned token usage. */
    fun llmMicros(promptTokens: Int, completionTokens: Int): Long =
        Math.round((promptTokens * GPT4O_MINI_IN + completionTokens * GPT4O_MINI_OUT) * 1_000_000)
}

/** Persists cumulative spend per provider (micro-USD) in plain prefs. */
class UsageTracker(context: Context) {
    private val prefs = context.getSharedPreferences("usage_prefs", Context.MODE_PRIVATE)

    fun add(provider: String, micros: Long) {
        if (micros <= 0L) return
        prefs.edit().putLong(provider, getMicros(provider) + micros).apply()
    }

    fun getMicros(provider: String): Long = prefs.getLong(provider, 0L)

    /** Per-provider spend in display order. */
    fun byProvider(): List<Pair<String, Long>> = COST_PROVIDERS.map { it to getMicros(it) }

    fun totalMicros(): Long = COST_PROVIDERS.sumOf { getMicros(it) }

    fun reset() = prefs.edit().clear().apply()

    companion object {
        // Rounded to cents. Non-zero amounts under a cent show as "< $0.01".
        fun formatUsd(micros: Long): String = when {
            micros <= 0L -> "$0.00"
            micros < 10_000L -> "< $0.01"   // 1 cent == 10,000 micro-USD
            else -> "$" + String.format("%.2f", micros / 1_000_000.0)
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    var currentScreen by remember { mutableStateOf("main") }
    
    if (currentScreen == "settings") {
        SettingsScreen(onBack = { currentScreen = "main" })
    } else {
        NoteTakerScreen(onOpenSettings = { currentScreen = "settings" })
    }
}

class SecureStorage(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secret_shared_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // --- Per-provider API keys ---
    fun saveOpenAiApiKey(key: String) {
        sharedPreferences.edit().putString("openai_api_key", key).apply()
    }

    fun getOpenAiApiKey(): String {
        // Backward-compat: fall back to the legacy single "api_key" if the
        // provider-specific key hasn't been set yet.
        val key = sharedPreferences.getString("openai_api_key", "") ?: ""
        if (key.isNotEmpty()) return key
        return sharedPreferences.getString("api_key", "") ?: ""
    }

    fun saveDeepgramApiKey(key: String) {
        sharedPreferences.edit().putString("deepgram_api_key", key).apply()
    }

    fun getDeepgramApiKey(): String {
        return sharedPreferences.getString("deepgram_api_key", "") ?: ""
    }

    fun saveGroqApiKey(key: String) {
        sharedPreferences.edit().putString("groq_api_key", key).apply()
    }

    fun getGroqApiKey(): String {
        return sharedPreferences.getString("groq_api_key", "") ?: ""
    }

    fun saveElevenLabsApiKey(key: String) {
        sharedPreferences.edit().putString("elevenlabs_api_key", key).apply()
    }

    fun getElevenLabsApiKey(): String {
        return sharedPreferences.getString("elevenlabs_api_key", "") ?: ""
    }

    fun saveAssemblyAiApiKey(key: String) {
        sharedPreferences.edit().putString("assemblyai_api_key", key).apply()
    }

    fun getAssemblyAiApiKey(): String {
        return sharedPreferences.getString("assemblyai_api_key", "") ?: ""
    }

    /** Convenience holder of every transcription key, for AIProcessor.transcribe(). */
    fun getApiKeys(): ApiKeys = ApiKeys(
        openai = getOpenAiApiKey(),
        deepgram = getDeepgramApiKey(),
        groq = getGroqApiKey(),
        elevenLabs = getElevenLabsApiKey(),
        assemblyAi = getAssemblyAiApiKey(),
    )

    fun saveModelChoice(choice: String) {
        sharedPreferences.edit().putString("model_choice", choice).apply()
    }

    fun getModelChoice(): String {
        return sharedPreferences.getString("model_choice", "OpenAI") ?: "OpenAI"
    }

    fun saveLlmChoice(choice: String) {
        sharedPreferences.edit().putString("llm_choice", choice).apply()
    }

    fun getLlmChoice(): String {
        return sharedPreferences.getString("llm_choice", "OpenAI") ?: "OpenAI"
    }

    fun saveTranscribeLanguage(code: String) {
        sharedPreferences.edit().putString("transcribe_language", code).apply()
    }

    /** ISO-639-1 language code, or "auto" for auto-detection (default). */
    fun getTranscribeLanguage(): String {
        return sharedPreferences.getString("transcribe_language", "auto") ?: "auto"
    }
}

@Composable
private fun CostBreakdownDialog(tracker: UsageTracker, onDismiss: () -> Unit) {
    val total = tracker.totalMicros()
    val rows = tracker.byProvider().filter { it.second > 0L }  // omit providers with no usage
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Usage cost by provider") },
        text = {
            Column {
                if (rows.isEmpty()) {
                    Text("No usage yet.", style = MaterialTheme.typography.bodyMedium)
                }
                rows.forEach { (provider, micros) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("$provider  (${CostEstimator.formatPerMin(provider)})")
                        Text(UsageTracker.formatUsd(micros))
                    }
                }
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total", style = MaterialTheme.typography.titleMedium)
                    Text(UsageTracker.formatUsd(total), style = MaterialTheme.typography.titleMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ApiKeyField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val secureStorage = remember { SecureStorage(context) }
    
    var openAiKey by remember { mutableStateOf(secureStorage.getOpenAiApiKey()) }
    var deepgramKey by remember { mutableStateOf(secureStorage.getDeepgramApiKey()) }
    var groqKey by remember { mutableStateOf(secureStorage.getGroqApiKey()) }
    var elevenLabsKey by remember { mutableStateOf(secureStorage.getElevenLabsApiKey()) }
    var assemblyAiKey by remember { mutableStateOf(secureStorage.getAssemblyAiApiKey()) }
    var transcribeLanguage by remember { mutableStateOf(secureStorage.getTranscribeLanguage()) }
    var modelChoice by remember { mutableStateOf(secureStorage.getModelChoice()) }
    var llmChoice by remember { mutableStateOf(secureStorage.getLlmChoice()) }
    var langExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var llmExpanded by remember { mutableStateOf(false) }

    val usageTracker = remember { UsageTracker(context) }
    var costMicros by remember { mutableStateOf(usageTracker.totalMicros()) }

    var downloadedModelsSize by remember { mutableStateOf(0L) }
    var downloadedModelsCount by remember { mutableStateOf(0) }
    
    fun refreshModelStats() {
        var size = 0L
        var count = 0
        context.filesDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".bin") || file.name.endsWith(".litertlm")) {
                size += file.length()
                count++
            }
        }
        downloadedModelsSize = size
        downloadedModelsCount = count
    }
    
    LaunchedEffect(Unit) {
        refreshModelStats()
    }
    
    fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = {
                        secureStorage.saveOpenAiApiKey(openAiKey)
                        secureStorage.saveDeepgramApiKey(deepgramKey)
                        secureStorage.saveGroqApiKey(groqKey)
                        secureStorage.saveElevenLabsApiKey(elevenLabsKey)
                        secureStorage.saveAssemblyAiApiKey(assemblyAiKey)
                        secureStorage.saveTranscribeLanguage(transcribeLanguage)
                        secureStorage.saveModelChoice(modelChoice)
                        secureStorage.saveLlmChoice(llmChoice)
                        Log.i("SmartNoteTaker", "Settings Saved. Transcription: $modelChoice ($transcribeLanguage), LLM: $llmChoice")
                        onBack()
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            Text("Transcription Model", style = MaterialTheme.typography.titleMedium)
            Box {
                OutlinedButton(onClick = { modelExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("$modelChoice  •  ${CostEstimator.rateLabel(modelChoice)}")
                }
                DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                    TRANSCRIPTION_PROVIDERS.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text("$choice  •  ${CostEstimator.rateLabel(choice)}") },
                            onClick = {
                                modelChoice = choice
                                modelExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Transcription Language", style = MaterialTheme.typography.titleMedium)
            val selectedLangLabel = TRANSCRIBE_LANGUAGES.firstOrNull { it.second == transcribeLanguage }?.first ?: "Auto-detect"
            // Plain button + popup menu: a DropdownMenu doesn't track its anchor on
            // every scroll frame the way ExposedDropdownMenuBox does (that caused jank).
            Box {
                OutlinedButton(onClick = { langExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Language: $selectedLangLabel")
                }
                DropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                    TRANSCRIBE_LANGUAGES.forEach { (label, code) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                transcribeLanguage = code
                                langExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("LLM Cleanup Model", style = MaterialTheme.typography.titleMedium)
            Box {
                OutlinedButton(onClick = { llmExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("$llmChoice  •  ${CostEstimator.llmRateLabel(llmChoice)}")
                }
                DropdownMenu(expanded = llmExpanded, onDismissRequest = { llmExpanded = false }) {
                    listOf("OpenAI", "Local (Gemma-4 E2B)", "Local (Gemma-4 E4B)").forEach { choice ->
                        DropdownMenuItem(
                            text = { Text("$choice  •  ${CostEstimator.llmRateLabel(choice)}") },
                            onClick = {
                                llmChoice = choice
                                llmExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // API key fields: show the one for the selected cloud transcription provider,
            // plus the OpenAI key whenever cloud cleanup (OpenAI) is selected.
            val showOpenAiKey = modelChoice == PROVIDER_OPENAI || llmChoice == "OpenAI"
            if (showOpenAiKey) {
                ApiKeyField("OpenAI API Key", openAiKey) { openAiKey = it }
            }
            if (modelChoice == PROVIDER_DEEPGRAM) {
                ApiKeyField("Deepgram API Key", deepgramKey) { deepgramKey = it }
            }
            if (modelChoice == PROVIDER_GROQ) {
                ApiKeyField("Groq API Key", groqKey) { groqKey = it }
            }
            if (modelChoice == PROVIDER_ELEVENLABS) {
                ApiKeyField("ElevenLabs API Key", elevenLabsKey) { elevenLabsKey = it }
            }
            if (modelChoice == PROVIDER_ASSEMBLYAI) {
                ApiKeyField("AssemblyAI API Key", assemblyAiKey) { assemblyAiKey = it }
            }

            Spacer(modifier = Modifier.height(32.dp))
            
            Divider()
            Spacer(modifier = Modifier.height(16.dp))
            
            Text("Storage", style = MaterialTheme.typography.titleMedium)
            Text("Downloaded Models: $downloadedModelsCount", style = MaterialTheme.typography.bodyMedium)
            Text("Total Size: ${formatSize(downloadedModelsSize)}", style = MaterialTheme.typography.bodyMedium)
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = {
                    val files = context.filesDir.listFiles()
                    var deletedCount = 0
                    files?.forEach { file ->
                        if (file.name.endsWith(".bin") || file.name.endsWith(".litertlm")) {
                            if (file.delete()) deletedCount++
                        }
                    }
                    val deletedSizeFormat = formatSize(downloadedModelsSize)
                    refreshModelStats()
                    android.widget.Toast.makeText(context, "Cleared $deletedCount files ($deletedSizeFormat)", android.widget.Toast.LENGTH_SHORT).show()
                },
                enabled = downloadedModelsCount > 0,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, disabledContainerColor = Color.LightGray),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear Downloaded Models")
            }

            Spacer(modifier = Modifier.height(32.dp))

            Divider()
            Spacer(modifier = Modifier.height(16.dp))

            Text("Usage Cost (third parties)", style = MaterialTheme.typography.titleMedium)
            val usageRows = usageTracker.byProvider().filter { it.second > 0L }  // omit no-usage providers
            if (usageRows.isEmpty()) {
                Text("No usage yet.", style = MaterialTheme.typography.bodyMedium)
            }
            usageRows.forEach { (provider, micros) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$provider  (${CostEstimator.formatPerMin(provider)})", style = MaterialTheme.typography.bodyMedium)
                    Text(UsageTracker.formatUsd(micros), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total", style = MaterialTheme.typography.titleMedium)
                Text(UsageTracker.formatUsd(costMicros), style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    usageTracker.reset()
                    costMicros = 0L
                    android.widget.Toast.makeText(context, "Usage cost reset", android.widget.Toast.LENGTH_SHORT).show()
                },
                enabled = costMicros > 0L,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, disabledContainerColor = Color.LightGray),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset Usage Cost")
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteTakerScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val secureStorage = remember { SecureStorage(context) }

    val apiKeys = remember { secureStorage.getApiKeys() }
    val transcribeLanguage = remember { secureStorage.getTranscribeLanguage() }
    var modelChoice by remember { mutableStateOf(secureStorage.getModelChoice()) }
    var llmChoice by remember { mutableStateOf(secureStorage.getLlmChoice()) }

    // recordMode: null = idle, "write" = dictate & append, "modify" = spoken edit instruction.
    var recordMode by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("Ready") }
    var notesText by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableStateOf(0f) }
    var micLevel by remember { mutableStateOf(0f) }
    val undoStack = remember { mutableStateListOf<String>() }

    val wavRecorder = remember { WavRecorder(context) }
    val aiProcessor = remember { AIProcessor() }
    val modelDownloader = remember { LocalModelDownloader(context) }
    val usageTracker = remember { UsageTracker(context) }

    var costMicros by remember { mutableStateOf(usageTracker.totalMicros()) }
    var showCostBreakdown by remember { mutableStateOf(false) }

    var hasPermission by remember { 
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) 
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        hasPermission = isGranted
    }

    // Live mic level while recording (drives the on-screen indicator).
    LaunchedEffect(recordMode) {
        while (recordMode != null) {
            micLevel = wavRecorder.amplitude
            kotlinx.coroutines.delay(60)
        }
        micLevel = 0f
    }

    fun keysMissing(): Boolean {
        val needsTranscribeKey = !isLocalProvider(modelChoice) && apiKeys.keyFor(modelChoice).isEmpty()
        val needsLlmKey = llmChoice == "OpenAI" && apiKeys.openai.isEmpty()
        return needsTranscribeKey || needsLlmKey
    }

    fun startRecording(mode: String) {
        recordMode = mode
        statusText = if (mode == "write") "Recording…" else "Listening for edit instruction…"
        wavRecorder.start()
    }

    // Stops recording and runs the pipeline. Write => transcribe + cleanup + append;
    // Modify => transcribe (as an edit instruction) + LLM-rewrite the existing notes.
    fun stopAndProcess() {
        val mode = recordMode ?: return
        recordMode = null
        statusText = "Processing…"
        val audioFile = wavRecorder.stop() ?: run { statusText = "Ready"; return }
        coroutineScope.launch {
            try {
                val rawText = aiProcessor.transcribe(
                    choice = modelChoice,
                    language = transcribeLanguage,
                    audioFile = audioFile,
                    wavRecorder = wavRecorder,
                    modelDownloader = modelDownloader,
                    keys = apiKeys,
                    usageTracker = usageTracker,
                    onStatus = { statusText = it },
                    onProgress = { p -> downloadProgress = p / 100f },
                )
                downloadProgress = 0f

                // Resolve a local LLM only when cleanup/modify runs on-device.
                val llmFile: File? = if (llmChoice != "OpenAI") {
                    statusText = "Loading LLM…"
                    val f = modelDownloader.downloadLlmModel(llmChoice) { p -> downloadProgress = p / 100f }
                    downloadProgress = 0f
                    f ?: throw Exception("Failed to load local LLM")
                } else null

                if (mode == "write") {
                    statusText = "Cleaning up text…"
                    val cleanText = if (llmChoice == "OpenAI")
                        aiProcessor.cleanText(rawText, apiKeys.openai, usageTracker)
                    else aiProcessor.cleanTextLocal(rawText, llmFile!!)
                    undoStack.add(notesText)
                    notesText += if (notesText.isEmpty()) cleanText else "\n\n$cleanText"
                } else {
                    statusText = "Applying edit…"
                    val newText = if (llmChoice == "OpenAI")
                        aiProcessor.modifyText(notesText, rawText, apiKeys.openai, usageTracker)
                    else aiProcessor.modifyTextLocal(notesText, rawText, llmFile!!)
                    undoStack.add(notesText)
                    notesText = newText.trim()
                }
                costMicros = usageTracker.totalMicros()
                statusText = "Ready"
            } catch (e: Exception) {
                statusText = "Error: ${e.message}"
                Log.e("SmartNoteTaker", "Pipeline failed", e)
            }
        }
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.logo),
                            contentDescription = "CheapWhisper Logo",
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("CheapWhisper")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            // Bottom-right: total third-party spend; tap to expand a per-provider breakdown.
            ExtendedFloatingActionButton(
                onClick = { showCostBreakdown = true },
                icon = { Icon(Icons.Filled.Info, contentDescription = "Usage cost") },
                text = { Text(UsageTracker.formatUsd(costMicros)) },
            )
        }
    ) { paddingValues ->
        if (showCostBreakdown) {
            CostBreakdownDialog(
                tracker = usageTracker,
                onDismiss = { showCostBreakdown = false },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(48.dp)
            ) {
                if (recordMode != null) {
                    // Live mic-level: the icon grows with the captured audio amplitude.
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Recording",
                        tint = Color.Red,
                        modifier = Modifier.size((20f + micLevel * 26f).dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(text = statusText, color = MaterialTheme.colorScheme.primary)
            }
            
            Text("Transcribing with: ${modelChoice}", style = MaterialTheme.typography.bodySmall)
            Text("LLM Cleanup: ${llmChoice}", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
            
            if (downloadProgress > 0f && downloadProgress < 1f) {
                LinearProgressIndicator(
                    progress = downloadProgress,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // WRITE: dictate new text (transcribe + cleanup, append to notes).
                Button(
                    onClick = {
                        when {
                            recordMode == "write" -> stopAndProcess()
                            recordMode == null -> when {
                                keysMissing() -> onOpenSettings()
                                !hasPermission -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                else -> startRecording("write")
                            }
                        }
                    },
                    enabled = recordMode != "modify",
                    modifier = Modifier.weight(1f).height(56.dp)
                ) {
                    Text(if (recordMode == "write") "Stop" else "Write")
                }

                // MODIFY: speak an edit instruction applied to the existing notes.
                Button(
                    onClick = {
                        when {
                            recordMode == "modify" -> stopAndProcess()
                            recordMode == null -> when {
                                keysMissing() -> onOpenSettings()
                                !hasPermission -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                else -> startRecording("modify")
                            }
                        }
                    },
                    // Only available when there is real text to modify.
                    enabled = recordMode != "write" && (recordMode == "modify" || notesText.isNotBlank()),
                    modifier = Modifier.weight(1f).height(56.dp)
                ) {
                    Text(if (recordMode == "modify") "Stop" else "Modify")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { if (undoStack.isNotEmpty()) notesText = undoStack.removeAt(undoStack.size - 1) },
                enabled = recordMode == null && undoStack.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (undoStack.isEmpty()) "Undo" else "Undo (${undoStack.size})")
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = notesText,
                onValueChange = { notesText = it },
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                label = { Text("Your Notes") }
            )
        }
    }
}

class AIProcessor {
    private val client = OkHttpClient()

    /** A transcript plus the provider-reported billed audio duration (seconds), if any. */
    private data class Stt(val text: String, val seconds: Double?)

    /**
     * Single entry point for transcription. Dispatches to the provider named by [choice]
     * (one of the PROVIDER_* constants), passing [language] ("auto" or an ISO-639-1 code).
     * [onStatus] surfaces user-facing progress; [onProgress] reports local model download %.
     */
    suspend fun transcribe(
        choice: String,
        language: String,
        audioFile: File,
        wavRecorder: WavRecorder,
        modelDownloader: LocalModelDownloader,
        keys: ApiKeys,
        usageTracker: UsageTracker,
        onStatus: (String) -> Unit,
        onProgress: (Int) -> Unit,
    ): String {
        val result: Stt = when (choice) {
            PROVIDER_OPENAI -> {
                onStatus("Transcribing (OpenAI)…")
                transcribeOpenAiCompatible(WHISPER_ENDPOINT, "whisper-1", keys.openai, audioFile, language)
            }
            PROVIDER_GROQ -> {
                onStatus("Transcribing (Groq)…")
                transcribeOpenAiCompatible(GROQ_ENDPOINT, "whisper-large-v3-turbo", keys.groq, audioFile, language)
            }
            PROVIDER_DEEPGRAM -> {
                onStatus("Transcribing (Deepgram)…")
                transcribeDeepgram(audioFile, keys.deepgram, language)
            }
            PROVIDER_ELEVENLABS -> {
                onStatus("Transcribing (ElevenLabs)…")
                transcribeElevenLabs(audioFile, keys.elevenLabs, language)
            }
            PROVIDER_ASSEMBLYAI -> {
                transcribeAssemblyAi(audioFile, keys.assemblyAi, language, onStatus)
            }
            else -> {
                // Local whisper.cpp (tiny / base / small)
                val modelName = when (choice) {
                    PROVIDER_LOCAL_TINY -> "tiny"
                    PROVIDER_LOCAL_SMALL -> "small"
                    else -> "base"
                }
                onStatus("Loading model ($modelName)…")
                val modelFile = modelDownloader.downloadModel(modelName, onProgress)
                    ?: throw IOException("Failed to load local Whisper model")
                onStatus("Transcribing (Local)…")
                Stt(transcribeAudioLocal(audioFile, modelFile, wavRecorder, language), null)
            }
        }
        // Meter paid providers (local is free). Prefer the duration the provider reports
        // (what they actually bill on); fall back to the WAV's own length.
        if (!isLocalProvider(choice)) {
            val seconds = result.seconds ?: ((audioFile.length() - 44).coerceAtLeast(0) / 32000.0)
            usageTracker.add(choice, CostEstimator.transcriptionMicros(choice, seconds))
        }
        return result.text
    }

    /** OpenAI Whisper API and the Groq drop-in clone share this multipart shape. */
    private suspend fun transcribeOpenAiCompatible(
        endpoint: String, model: String, apiKey: String, audioFile: File, language: String,
    ): Stt = withContext(Dispatchers.IO) {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            // verbose_json adds a top-level "duration" (audio seconds) for accurate metering.
            .addFormDataPart("response_format", "verbose_json")
        if (language != "auto") builder.addFormDataPart("language", language)

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${apiKey}")
            .post(builder.build())
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Transcription error: ${response.code} ${response.message}")
            val responseBody = response.body?.string() ?: throw IOException("Empty response")
            val json = JSONObject(responseBody)
            Stt(json.getString("text"), json.optDouble("duration").takeUnless { it.isNaN() })
        }
    }

    /** Deepgram Nova-3 prerecorded REST API: raw audio body, "Token" auth. */
    private suspend fun transcribeDeepgram(
        audioFile: File, apiKey: String, language: String,
    ): Stt = withContext(Dispatchers.IO) {
        // Nova-3 uses language=multi for multilingual auto-detection.
        val lang = if (language == "auto") "multi" else language
        val url = "$DEEPGRAM_ENDPOINT?model=nova-3&smart_format=true&language=$lang"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token ${apiKey}")
            .post(audioFile.asRequestBody("audio/wav".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Deepgram error: ${response.code} ${response.message}")
            val responseBody = response.body?.string() ?: throw IOException("Empty response")
            val json = JSONObject(responseBody)
            val transcript = json
                .getJSONObject("results")
                .getJSONArray("channels").getJSONObject(0)
                .getJSONArray("alternatives").getJSONObject(0)
                .getString("transcript")
            // metadata.duration is the billed audio length.
            val seconds = json.optJSONObject("metadata")?.optDouble("duration")?.takeUnless { it.isNaN() }
            Stt(transcript, seconds)
        }
    }

    /** ElevenLabs Scribe v1: multipart, "xi-api-key" header. */
    private suspend fun transcribeElevenLabs(
        audioFile: File, apiKey: String, language: String,
    ): Stt = withContext(Dispatchers.IO) {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model_id", "scribe_v1")
        if (language != "auto") builder.addFormDataPart("language_code", language)

        val request = Request.Builder()
            .url(ELEVENLABS_ENDPOINT)
            .header("xi-api-key", apiKey)
            .post(builder.build())
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("ElevenLabs error: ${response.code} ${response.message}")
            val responseBody = response.body?.string() ?: throw IOException("Empty response")
            val json = JSONObject(responseBody)
            // No duration field; approximate from the last word's end timestamp (null -> file fallback).
            val words = json.optJSONArray("words")
            val seconds = if (words != null && words.length() > 0) {
                words.getJSONObject(words.length() - 1).optDouble("end").takeUnless { it.isNaN() }
            } else null
            Stt(json.getString("text"), seconds)
        }
    }

    /** AssemblyAI: async upload -> create transcript -> poll until complete. */
    private suspend fun transcribeAssemblyAi(
        audioFile: File, apiKey: String, language: String, onStatus: (String) -> Unit,
    ): Stt = withContext(Dispatchers.IO) {
        // 1) Upload the audio bytes.
        onStatus("Uploading audio (AssemblyAI)…")
        val uploadRequest = Request.Builder()
            .url("$ASSEMBLYAI_BASE/upload")
            .header("authorization", apiKey)
            .post(audioFile.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val uploadUrl = client.newCall(uploadRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("AssemblyAI upload error: ${response.code} ${response.message}")
            val body = response.body?.string() ?: throw IOException("Empty response")
            JSONObject(body).getString("upload_url")
        }

        // 2) Request a transcript. Auto-detect language unless a code was forced.
        val createJson = JSONObject().apply {
            put("audio_url", uploadUrl)
            if (language == "auto") put("language_detection", true) else put("language_code", language)
        }
        val createRequest = Request.Builder()
            .url("$ASSEMBLYAI_BASE/transcript")
            .header("authorization", apiKey)
            .post(createJson.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val transcriptId = client.newCall(createRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("AssemblyAI create error: ${response.code} ${response.message}")
            val body = response.body?.string() ?: throw IOException("Empty response")
            JSONObject(body).getString("id")
        }

        // 3) Poll until completed or errored (cap ~120s).
        onStatus("Transcribing (AssemblyAI)…")
        val pollUrl = "$ASSEMBLYAI_BASE/transcript/$transcriptId"
        repeat(80) {
            val pollRequest = Request.Builder()
                .url(pollUrl)
                .header("authorization", apiKey)
                .get()
                .build()
            val json = client.newCall(pollRequest).execute().use { response ->
                if (!response.isSuccessful) throw IOException("AssemblyAI poll error: ${response.code} ${response.message}")
                JSONObject(response.body?.string() ?: throw IOException("Empty response"))
            }
            when (json.getString("status")) {
                "completed" -> return@withContext Stt(
                    json.getString("text"),
                    json.optDouble("audio_duration").takeUnless { it.isNaN() },
                )
                "error" -> throw IOException("AssemblyAI failed: ${json.optString("error")}")
            }
            delay(1500)
        }
        throw IOException("AssemblyAI timed out")
    }

    suspend fun transcribeAudioLocal(audioFile: File, modelFile: File, wavRecorder: WavRecorder, language: String): String = withContext(Dispatchers.IO) {
        val floatArray = wavRecorder.decodeWavToFloatArray(audioFile)

        val whisperContext = com.whispercpp.whisper.WhisperContext.createContextFromFile(modelFile.absolutePath)
        val result = whisperContext.transcribeData(floatArray, language = language, printTimestamp = false)
        whisperContext.release()

        result.trim()
    }

    suspend fun cleanTextLocal(rawText: String, modelFile: File): String = withContext(Dispatchers.IO) {
        if (rawText.isBlank()) return@withContext ""
        
        var resultText = ""
        
        try {
            val engine = FallbackEngine.initializeEngine(modelFile)
            
            val conversation = engine.createConversation(ConversationConfig())
            val prompt = "You are an assistant that cleans up dictated voice notes. Fix punctuation, grammar, and formatting. Remove filler words (ums, ahs). Do not add new information or conversational filler. Output ONLY the cleaned text.\n\nHere is the raw text to clean:\n${rawText}"
            
            val responseMsg = conversation.sendMessage(prompt)
            val contents = responseMsg.contents.contents
            resultText = (contents.firstOrNull() as? Content.Text)?.text ?: ""
            
            conversation.close()
            engine.close()
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
        
        resultText
    }

    suspend fun cleanText(rawText: String, apiKey: String, usageTracker: UsageTracker): String = withContext(Dispatchers.IO) {
        if (rawText.isBlank()) return@withContext ""
        val jsonBody = JSONObject().apply {
            put("model", "gpt-4o-mini")
            
            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", "You are an assistant that cleans up dictated voice notes. Fix punctuation, grammar, and formatting. Remove filler words (ums, ahs). Do not add new information or conversational filler. Output ONLY the cleaned text.")
            })
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", rawText)
            })
            
            put("messages", messages)
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(LLM_ENDPOINT)
            .header("Authorization", "Bearer ${apiKey}")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("LLM error: ${response.message}")
            val responseBody = response.body?.string() ?: throw IOException("Empty response")
            val jsonObject = JSONObject(responseBody)
            jsonObject.optJSONObject("usage")?.let { usage ->
                usageTracker.add(
                    PROVIDER_LLM,
                    CostEstimator.llmMicros(usage.optInt("prompt_tokens"), usage.optInt("completion_tokens")),
                )
            }
            val choices = jsonObject.getJSONArray("choices")
            val message = choices.getJSONObject(0).getJSONObject("message")
            message.getString("content")
        }
    }

    /** Rewrites [existingText] per a spoken [instruction], via OpenAI gpt-4o-mini. */
    suspend fun modifyText(existingText: String, instruction: String, apiKey: String, usageTracker: UsageTracker): String =
        openAiChat(
            system = "You are a precise text editor. Apply the user's instruction to the supplied text and output ONLY the revised text — no commentary, preamble, or surrounding quotes.",
            user = "TEXT:\n$existingText\n\nINSTRUCTION:\n$instruction",
            apiKey = apiKey,
            usageTracker = usageTracker,
        )

    private suspend fun openAiChat(system: String, user: String, apiKey: String, usageTracker: UsageTracker): String = withContext(Dispatchers.IO) {
        val jsonBody = JSONObject().apply {
            put("model", "gpt-4o-mini")
            val messages = JSONArray()
            messages.put(JSONObject().apply { put("role", "system"); put("content", system) })
            messages.put(JSONObject().apply { put("role", "user"); put("content", user) })
            put("messages", messages)
        }
        val request = Request.Builder()
            .url(LLM_ENDPOINT)
            .header("Authorization", "Bearer ${apiKey}")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("LLM error: ${response.message}")
            val responseBody = response.body?.string() ?: throw IOException("Empty response")
            val jsonObject = JSONObject(responseBody)
            jsonObject.optJSONObject("usage")?.let { usage ->
                usageTracker.add(
                    PROVIDER_LLM,
                    CostEstimator.llmMicros(usage.optInt("prompt_tokens"), usage.optInt("completion_tokens")),
                )
            }
            jsonObject.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
    }

    /** Local (Gemma) equivalent of [modifyText]. */
    suspend fun modifyTextLocal(existingText: String, instruction: String, modelFile: File): String = withContext(Dispatchers.IO) {
        var resultText = ""
        try {
            val engine = FallbackEngine.initializeEngine(modelFile)
            val conversation = engine.createConversation(ConversationConfig())
            val prompt = "You are a precise text editor. Apply the instruction to the text and output ONLY the revised text, with no commentary.\n\nTEXT:\n$existingText\n\nINSTRUCTION:\n$instruction"
            val responseMsg = conversation.sendMessage(prompt)
            val contents = responseMsg.contents.contents
            resultText = (contents.firstOrNull() as? Content.Text)?.text ?: ""
            conversation.close()
            engine.close()
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
        resultText
    }
}
