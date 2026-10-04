package com.example.smartnotetaker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import com.example.smartnotetaker.transcription.*
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
// Minimum audio bytes to bother processing (1s @ 16kHz mono 16-bit = 32000 B/s);
// guards against accidental short taps. WAV PCM payload = file length - 44 header.
const val MIN_RECORDING_BYTES = 32000

const val WHISPER_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
const val LLM_ENDPOINT = "https://api.openai.com/v1/chat/completions"
// GPT-5.6 Luna: the cost-tier model of OpenAI's current GPT-5.6 family. Cleanup is a short
// rewrite, so reasoning effort is pinned low; sampling params (temperature etc.) are rejected.
const val OPENAI_LLM_MODEL = "gpt-5.6-luna"
const val OPENAI_REASONING_EFFORT = "low"
const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
const val DEEPGRAM_ENDPOINT = "https://api.deepgram.com/v1/listen"
const val ELEVENLABS_ENDPOINT = "https://api.elevenlabs.io/v1/speech-to-text"
const val ASSEMBLYAI_BASE = "https://api.assemblyai.com/v2"

// --- TRANSCRIPTION PROVIDERS (values persisted as model_choice) ---
const val PROVIDER_OPENROUTER = "OpenRouter"
const val PROVIDER_OPENAI = "OpenAI"
const val PROVIDER_DEEPGRAM = "Deepgram"
const val PROVIDER_GROQ = "Groq"
const val PROVIDER_ELEVENLABS = "ElevenLabs"
const val PROVIDER_ASSEMBLYAI = "AssemblyAI"
const val PROVIDER_SONIOX = "Soniox"
const val PROVIDER_LOCAL_TINY = "Local (tiny)"
const val PROVIDER_LOCAL_BASE = "Local (base)"
const val PROVIDER_LOCAL_SMALL = "Local (small)"

// Cleanup/modify LLM spend is metered into its own bucket, separate from any
// same-vendor transcription spend (so OpenAI transcription vs. cleanup are distinct lines).
const val PROVIDER_LLM = "OpenAI (cleanup LLM)"

/** System instruction given to the cleanup LLM; users can override it in Settings. */
const val DEFAULT_CLEANUP_PROMPT =
    "You are an assistant that cleans up dictated voice notes. Fix punctuation, grammar, and formatting. Remove filler words (ums, ahs). Do not add new information or conversational filler. Output ONLY the cleaned text."

// Groq is deliberately not offered: its STT API has no streaming, so no live text.
val TRANSCRIPTION_PROVIDERS = listOf(
    PROVIDER_OPENROUTER,
    PROVIDER_OPENAI, PROVIDER_DEEPGRAM, PROVIDER_ELEVENLABS, PROVIDER_ASSEMBLYAI, PROVIDER_SONIOX,
    PROVIDER_LOCAL_TINY, PROVIDER_LOCAL_BASE, PROVIDER_LOCAL_SMALL,
)

fun isLocalProvider(choice: String): Boolean = choice.startsWith("Local")

/** Language options for the Settings dropdown: display label -> code ("auto" = auto-detect). */
val TRANSCRIBE_LANGUAGES = listOf(
    "Auto-detect" to "auto",
    "English" to "en",
    "Spanish" to "es",
    "Catalan" to "ca",
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
    val soniox: String = "",
    val openrouter: String = "",
    val openrouterModel: String = TranscriptionCatalog.DEFAULT,
) {
    /** The key required for [choice], or "" for local providers (no key needed). */
    fun keyFor(choice: String): String = when (choice) {
        PROVIDER_OPENROUTER -> openrouter
        PROVIDER_OPENAI -> openai
        PROVIDER_DEEPGRAM -> deepgram
        PROVIDER_GROQ -> groq
        PROVIDER_ELEVENLABS -> elevenLabs
        PROVIDER_ASSEMBLYAI -> assemblyAi
        PROVIDER_SONIOX -> soniox
        else -> ""
    }
}

// --- THIRD-PARTY COST TRACKING ---
// Paid third-party providers whose usage we meter. Local Whisper / Gemma run
// on-device and cost nothing, so they are intentionally excluded.
val COST_PROVIDERS = listOf(
    PROVIDER_OPENROUTER,
    PROVIDER_OPENAI, PROVIDER_DEEPGRAM, PROVIDER_GROQ, PROVIDER_ELEVENLABS, PROVIDER_ASSEMBLYAI, PROVIDER_SONIOX,
    PROVIDER_LLM,
)

/** Estimates USD cost (returned as integer micro-dollars to avoid float drift). */
object CostEstimator {
    // gpt-5.6-luna token pricing (USD per token): $0.20 / 1M input, $1.20 / 1M output.
    private const val OPENAI_LLM_IN = 0.20 / 1_000_000
    private const val OPENAI_LLM_OUT = 1.20 / 1_000_000
    // ~160 words/min of speech ≈ ~500 tokens/min in; cleanup output is of similar length.
    private const val LLM_TOKENS_PER_MIN = 500

    /** Estimated cleanup-LLM (gpt-5.6-luna) cost per minute of speech. */
    fun llmUsdPerMinute(): Double =
        LLM_TOKENS_PER_MIN * OPENAI_LLM_IN + LLM_TOKENS_PER_MIN * OPENAI_LLM_OUT

    /** Published pay-as-you-go transcription rate, USD per minute (0 for on-device). */
    fun usdPerMinute(provider: String): Double = when (provider) {
        PROVIDER_OPENROUTER -> 0.10 / 60.0
        PROVIDER_OPENAI -> 0.006
        PROVIDER_GROQ -> 0.04 / 60.0          // $0.04/hr
        PROVIDER_DEEPGRAM -> 0.0077
        PROVIDER_ELEVENLABS -> 0.40 / 60.0    // $0.40/hr
        PROVIDER_ASSEMBLYAI -> 0.27 / 60.0    // $0.27/hr
        PROVIDER_SONIOX -> 0.10 / 60.0        // stt-async-v5 $0.10/hr
        PROVIDER_LLM -> llmUsdPerMinute()
        else -> 0.0
    }

    /** Cleanup-model picker label: FREE on-device, else the per-hour LLM estimate. */
    fun llmRateLabel(llmChoice: String): String =
        if (llmChoice == "OpenAI") "est. $" + String.format("%.2f", llmUsdPerMinute() * 60) + "/hr" else "FREE"

    /** Human-readable per-hour estimate, e.g. "$0.46/hr" (or "free" on-device). */
    fun formatPerHour(provider: String): String {
        val ratePerHour = usdPerMinute(provider) * 60
        return if (ratePerHour <= 0.0) "free" else "$" + String.format("%.2f", ratePerHour) + "/hr"
    }

    /** Picker label: "FREE" for on-device, otherwise "est. $X/hr". */
    fun rateLabel(provider: String): String {
        if (provider == PROVIDER_OPENROUTER) return "Selected model rate; actual cost reported"
        val ratePerHour = usdPerMinute(provider) * 60
        return if (ratePerHour <= 0.0) "FREE" else "est. $" + String.format("%.2f", ratePerHour) + "/hr"
    }

    fun transcriptionMicros(provider: String, audioDurationSec: Double): Long =
        Math.round(usdPerMinute(provider) * (audioDurationSec / 60.0) * 1_000_000)

    /** Streaming (WebSocket) rate where it differs from the one-shot rate, USD per minute. */
    fun streamingUsdPerMinute(provider: String): Double = when (provider) {
        PROVIDER_OPENAI -> 0.003                 // gpt-4o-mini-transcribe realtime
        PROVIDER_ASSEMBLYAI -> 0.15 / 60.0       // Universal-Streaming $0.15/hr
        PROVIDER_SONIOX -> 0.12 / 60.0           // stt-rt-v5 $0.12/hr
        PROVIDER_ELEVENLABS -> 0.39 / 60.0       // Scribe v2 realtime $0.39/hr
        else -> usdPerMinute(provider)           // Deepgram Nova-3 streaming = 0.0077
    }

    fun streamingMicros(provider: String, audioDurationSec: Double): Long =
        Math.round(streamingUsdPerMinute(provider) * (audioDurationSec / 60.0) * 1_000_000)

    /** Exact OpenAI cleanup-LLM cost from returned token usage. */
    fun llmMicros(promptTokens: Int, completionTokens: Int): Long =
        Math.round((promptTokens * OPENAI_LLM_IN + completionTokens * OPENAI_LLM_OUT) * 1_000_000)
}

/** Persists cumulative spend per provider (micro-USD) in plain prefs. */
class UsageTracker(context: Context) {
    val appContext: Context = context.applicationContext
    // Device-protected (unencrypted) storage: readable before the first unlock after a
    // reboot, which the direct-boot-aware IME needs. Totals are not secret.
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("usage_prefs", Context.MODE_PRIVATE)

    init {
        // One-time migration of totals recorded under the old credential-encrypted prefs.
        val unlocked = context.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == true
        if (unlocked && !prefs.getBoolean(MIGRATED_KEY, false)) {
            val old = context.getSharedPreferences("usage_prefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            old.all.forEach { (k, v) -> if (v is Long) editor.putLong(k, prefs.getLong(k, 0L) + v) }
            editor.putBoolean(MIGRATED_KEY, true).apply()
            old.edit().clear().apply()
        }
    }

    fun add(provider: String, micros: Long) {
        if (micros <= 0L) return
        prefs.edit().putLong(provider, getMicros(provider) + micros).apply()
    }

    fun getMicros(provider: String): Long = if (provider == PROVIDER_OPENROUTER) {
        runCatching { TranscriptionUsage(appContext).total().multiply(java.math.BigDecimal(1000000)).toLong() }.getOrDefault(0L)
    } else prefs.getLong(provider, 0L)

    /** Per-provider spend in display order. */
    fun byProvider(): List<Pair<String, Long>> = COST_PROVIDERS.map { it to getMicros(it) }.filter { it.second > 0L }

    fun totalMicros(): Long = COST_PROVIDERS.sumOf { getMicros(it) }

    fun reset() { prefs.edit().clear().putBoolean(MIGRATED_KEY, true).apply(); TranscriptionUsage(appContext).reset() }

    companion object {
        private const val MIGRATED_KEY = "_migrated_from_ce"

        // Rounded to cents. Non-zero amounts under a cent show as "< $0.01".
        fun formatUsd(micros: Long): String = when {
            micros <= 0L -> "$0.00"
            micros < 10_000L -> "< $0.01"   // 1 cent == 10,000 micro-USD
            else -> "$" + String.format("%.2f", micros / 1_000_000.0)
        }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var transcription: FileTranscriptionViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        transcription = ViewModelProvider(this)[FileTranscriptionViewModel::class.java]
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { FileTranscriptionScreen(transcription) } } }
        if (savedInstanceState == null) transcription.acceptShare(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); transcription.acceptShare(intent); transcription.reloadSettings() }
    override fun onStart() { super.onStart(); if (::transcription.isInitialized) transcription.reloadSettings() }
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

    fun saveOpenRouterApiKey(key: String) {
        check(sharedPreferences.edit().putString("openrouter_api_key", key).commit()) { "Could not securely save OpenRouter key" }
    }
    fun getOpenRouterApiKey(): String = sharedPreferences.getString("openrouter_api_key", "") ?: ""
    fun saveOpenRouterModel(model: String) { sharedPreferences.edit().putString("openrouter_model", model).apply() }
    fun getOpenRouterModel(): String = sharedPreferences.getString("openrouter_model", TranscriptionCatalog.DEFAULT) ?: TranscriptionCatalog.DEFAULT

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

    fun saveSonioxApiKey(key: String) {
        sharedPreferences.edit().putString("soniox_api_key", key).apply()
    }

    fun getSonioxApiKey(): String {
        return sharedPreferences.getString("soniox_api_key", "") ?: ""
    }

    /** Convenience holder of every transcription key, for AIProcessor.transcribe(). */
    fun getApiKeys(): ApiKeys = ApiKeys(
        openai = getOpenAiApiKey(),
        deepgram = getDeepgramApiKey(),
        groq = getGroqApiKey(),
        elevenLabs = getElevenLabsApiKey(),
        assemblyAi = getAssemblyAiApiKey(),
        soniox = getSonioxApiKey(),
        openrouter = getOpenRouterApiKey(),
        openrouterModel = getOpenRouterModel(),
    )

    fun saveModelChoice(choice: String) {
        sharedPreferences.edit().putString("model_choice", choice).apply()
    }

    fun getModelChoice(): String {
        val stored = sharedPreferences.getString("model_choice", "OpenAI") ?: "OpenAI"
        // A previously selected provider that is no longer offered falls back to the default.
        return if (stored in TRANSCRIPTION_PROVIDERS) stored else "OpenAI"
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

    fun saveCleanupPrompt(prompt: String) {
        sharedPreferences.edit().putString("cleanup_prompt", prompt).apply()
    }

    /** The cleanup LLM's system prompt; a blank saved value falls back to [DEFAULT_CLEANUP_PROMPT]. */
    fun getCleanupPrompt(): String {
        val saved = sharedPreferences.getString("cleanup_prompt", "") ?: ""
        return if (saved.isBlank()) DEFAULT_CLEANUP_PROMPT else saved
    }

}

@Composable
private fun ApiKeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    status: @Composable ColumnScope.() -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Column(modifier = Modifier.padding(start = 4.dp, top = 2.dp)) { status() }
    Spacer(modifier = Modifier.height(8.dp))
}

/** Result of checking one API key against its provider, for the inline indicator. */
private sealed class KeyCheck {
    object Idle : KeyCheck()
    object Checking : KeyCheck()
    object Valid : KeyCheck()
    data class Invalid(val reason: String) : KeyCheck()
    data class Unreachable(val reason: String) : KeyCheck()
}

/**
 * Validates a key only when it changes: the key loaded from storage is taken as already
 * checked, and every later edit (typing or paste) is debounced ~700 ms and then checked
 * once. Retyping the last-checked value does not re-check; a blank value shows nothing.
 * Switching [provider] (e.g. the OpenAI key being checked for the cleanup model instead)
 * counts as a change.
 */
@Composable
private fun rememberKeyCheck(provider: String, key: String, initialKey: String): KeyCheck {
    var state by remember { mutableStateOf<KeyCheck>(KeyCheck.Idle) }
    val lastChecked = remember { mutableStateOf(provider to initialKey) }
    LaunchedEffect(provider, key) {
        if (key.isBlank()) { state = KeyCheck.Idle; return@LaunchedEffect }
        if (lastChecked.value == (provider to key)) return@LaunchedEffect  // unchanged: keep result
        state = KeyCheck.Checking
        delay(700)
        val result = try {
            when (val r = withContext(Dispatchers.IO) { ApiKeyValidator.validate(provider, key) }) {
                is ApiKeyValidator.Outcome.Valid -> KeyCheck.Valid
                is ApiKeyValidator.Outcome.Invalid -> KeyCheck.Invalid(r.reason)
                is ApiKeyValidator.Outcome.Unreachable -> KeyCheck.Unreachable(r.reason)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KeyCheck.Unreachable(e.message ?: e.javaClass.simpleName)
        }
        lastChecked.value = provider to key
        state = result
    }
    return state
}

/** One status line for a key check. [what] names the thing checked, e.g. "Key" or "Cleanup model". */
@Composable
private fun KeyCheckLine(what: String, providerName: String, check: KeyCheck, validNote: String = "") {
    when (check) {
        KeyCheck.Idle -> Unit
        KeyCheck.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Checking $what…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        KeyCheck.Valid -> Text("✓ $what works$validNote", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
        is KeyCheck.Invalid -> Text("✗ $what: ${check.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        is KeyCheck.Unreachable -> Text("Could not reach $providerName: ${check.reason}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

/** Section label on the left, selector button on the right, on one line. */
@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val secureStorage = remember { SecureStorage(context) }
    
    var openRouterKey by remember { mutableStateOf(secureStorage.getOpenRouterApiKey()) }
    var openRouterSaveError by remember { mutableStateOf("") }
    var savingSettings by remember { mutableStateOf(false) }
    val settingsScope = rememberCoroutineScope()
    var openAiKey by remember { mutableStateOf(secureStorage.getOpenAiApiKey()) }
    var deepgramKey by remember { mutableStateOf(secureStorage.getDeepgramApiKey()) }
    var groqKey by remember { mutableStateOf(secureStorage.getGroqApiKey()) }
    var elevenLabsKey by remember { mutableStateOf(secureStorage.getElevenLabsApiKey()) }
    var assemblyAiKey by remember { mutableStateOf(secureStorage.getAssemblyAiApiKey()) }
    var sonioxKey by remember { mutableStateOf(secureStorage.getSonioxApiKey()) }
    var transcribeLanguage by remember { mutableStateOf(secureStorage.getTranscribeLanguage()) }
    var modelChoice by remember { mutableStateOf(secureStorage.getModelChoice()) }
    var llmChoice by remember { mutableStateOf(secureStorage.getLlmChoice()) }
    var cleanupPrompt by remember { mutableStateOf(secureStorage.getCleanupPrompt()) }
    var promptDialogOpen by remember { mutableStateOf(false) }
    // Keys as loaded from storage: treated as already checked, so only edits trigger a check.
    val storedKeys = remember { secureStorage.getApiKeys() }
    var langExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var llmExpanded by remember { mutableStateOf(false) }


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

    fun saveAndClose() {
        if (savingSettings) return
        savingSettings = true
        openRouterSaveError = ""
        settingsScope.launch {
            try {
                val candidate = openRouterKey.trim()
                val stored = withContext(Dispatchers.IO) { secureStorage.getOpenRouterApiKey() }
                var acceptedKey = candidate
                if (candidate != stored && candidate.isNotBlank()) {
                    when (val result = ApiKeyValidator.validate(PROVIDER_OPENROUTER, candidate)) {
                        ApiKeyValidator.Outcome.Valid -> Unit
                        is ApiKeyValidator.Outcome.Invalid -> { acceptedKey = stored; openRouterSaveError = "OpenRouter key unchanged: ${result.reason}" }
                        is ApiKeyValidator.Outcome.Unreachable -> { acceptedKey = stored; openRouterSaveError = "OpenRouter key unchanged: ${result.reason}" }
                    }
                }
                withContext(Dispatchers.IO) {
                    if (acceptedKey != stored) secureStorage.saveOpenRouterApiKey(acceptedKey)
                    secureStorage.saveOpenAiApiKey(openAiKey)
                    secureStorage.saveDeepgramApiKey(deepgramKey)
                    secureStorage.saveGroqApiKey(groqKey)
                    secureStorage.saveElevenLabsApiKey(elevenLabsKey)
                    secureStorage.saveAssemblyAiApiKey(assemblyAiKey)
                    secureStorage.saveSonioxApiKey(sonioxKey)
                    secureStorage.saveTranscribeLanguage(transcribeLanguage)
                    secureStorage.saveModelChoice(modelChoice)
                    secureStorage.saveLlmChoice(llmChoice)
                    secureStorage.saveCleanupPrompt(cleanupPrompt)
                }
                if (openRouterSaveError.isNotEmpty()) android.widget.Toast.makeText(context, openRouterSaveError, android.widget.Toast.LENGTH_LONG).show()
                onBack()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { openRouterSaveError = "Could not save settings. Please try again." }
            finally { savingSettings = false }
        }
    }
    BackHandler { saveAndClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { saveAndClose() }, enabled = !savingSettings) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            // The keyboard records in this app's process, so it needs the mic permission.
            var hasMic by remember {
                mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            }
            val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }
            if (!hasMic) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Microphone permission is required for dictation.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Grant") }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Plain button + popup menu: a DropdownMenu doesn't track its anchor on
            // every scroll frame the way ExposedDropdownMenuBox does (that caused jank).
            // Buttons show just the choice; rates stay in the menus for comparison.
            SettingRow("IME Transcriber (main screen transcriber)") {
                Box {
                    OutlinedButton(onClick = { modelExpanded = true }) { Text(modelChoice) }
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
            }

            Spacer(modifier = Modifier.height(12.dp))

            val selectedLangLabel = TRANSCRIBE_LANGUAGES.firstOrNull { it.second == transcribeLanguage }?.first ?: "Auto-detect"
            SettingRow("IME transcriber lang") {
                Box {
                    OutlinedButton(onClick = { langExpanded = true }) { Text(selectedLangLabel) }
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
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingRow("LLM Cleanup Model") {
                Box {
                    OutlinedButton(onClick = { llmExpanded = true }) { Text(llmChoice) }
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
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Editable system instruction for the cleanup LLM (cloud and local alike),
            // edited in a dialog so the long text doesn't dominate the screen.
            SettingRow("Cleanup prompt") {
                OutlinedButton(onClick = { promptDialogOpen = true }) {
                    Text(if (cleanupPrompt == DEFAULT_CLEANUP_PROMPT) "Default" else "Custom")
                }
            }
            if (promptDialogOpen) {
                var draft by remember { mutableStateOf(cleanupPrompt) }
                AlertDialog(
                    onDismissRequest = { promptDialogOpen = false },
                    title = { Text("Cleanup prompt") },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                minLines = 6,
                                placeholder = { Text(DEFAULT_CLEANUP_PROMPT) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(
                                onClick = { draft = DEFAULT_CLEANUP_PROMPT },
                                enabled = draft != DEFAULT_CLEANUP_PROMPT,
                            ) {
                                Text("Reset to default")
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            cleanupPrompt = draft.ifBlank { DEFAULT_CLEANUP_PROMPT }
                            promptDialogOpen = false
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { promptDialogOpen = false }) { Text("Cancel") }
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // API key fields: show the one for the selected cloud transcription provider,
            // plus the OpenAI key whenever cloud cleanup (OpenAI) is selected. A key is
            // checked against its provider only when it changes (debounced).
            val openRouterCheck = rememberKeyCheck(PROVIDER_OPENROUTER, openRouterKey, storedKeys.openrouter)
            ApiKeyField("OpenRouter API Key", openRouterKey, { openRouterKey = it; openRouterSaveError = "" }, enabled = !savingSettings) {
                KeyCheckLine("Key", "OpenRouter", openRouterCheck)
                if (openRouterSaveError.isNotEmpty()) Text(openRouterSaveError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (savingSettings) Text("Saving settings…", style = MaterialTheme.typography.bodySmall)
            }

            val showOpenAiKey = modelChoice == PROVIDER_OPENAI || llmChoice == "OpenAI"
            if (showOpenAiKey) {
                // With OpenAI cleanup, checking the cleanup model proves the key too.
                val openAiProvider = if (llmChoice == "OpenAI") PROVIDER_LLM else PROVIDER_OPENAI
                val check = rememberKeyCheck(openAiProvider, openAiKey, storedKeys.openai)
                val note = if (openAiProvider == PROVIDER_LLM) " ($OPENAI_LLM_MODEL available)" else ""
                ApiKeyField("OpenAI API Key", openAiKey, { openAiKey = it }) { KeyCheckLine("Key", "OpenAI", check, note) }
            }
            if (modelChoice == PROVIDER_DEEPGRAM) {
                val check = rememberKeyCheck(PROVIDER_DEEPGRAM, deepgramKey, storedKeys.deepgram)
                ApiKeyField("Deepgram API Key", deepgramKey, { deepgramKey = it }) { KeyCheckLine("Key", "Deepgram", check) }
            }
            if (modelChoice == PROVIDER_ELEVENLABS) {
                val check = rememberKeyCheck(PROVIDER_ELEVENLABS, elevenLabsKey, storedKeys.elevenLabs)
                ApiKeyField("ElevenLabs API Key", elevenLabsKey, { elevenLabsKey = it }) { KeyCheckLine("Key", "ElevenLabs", check) }
            }
            if (modelChoice == PROVIDER_ASSEMBLYAI) {
                val check = rememberKeyCheck(PROVIDER_ASSEMBLYAI, assemblyAiKey, storedKeys.assemblyAi)
                ApiKeyField("AssemblyAI API Key", assemblyAiKey, { assemblyAiKey = it }) { KeyCheckLine("Key", "AssemblyAI", check) }
            }
            if (modelChoice == PROVIDER_SONIOX) {
                val check = rememberKeyCheck(PROVIDER_SONIOX, sonioxKey, storedKeys.soniox)
                ApiKeyField("Soniox API Key", sonioxKey, { sonioxKey = it }) { KeyCheckLine("Key", "Soniox", check) }
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

            UsageSettingsSection()

        }
    }
}
class AIProcessor {
    private val client = OkHttpClient()
    private val openRouterClient = OpenRouterApi.defaultClient()

    /** Aborts any in-flight HTTP calls — used to cancel a running transcription/LLM request. */
    fun cancelInFlight() {
        client.dispatcher.cancelAll()
        openRouterClient.dispatcher.cancelAll()
    }

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
        if (choice == PROVIDER_OPENROUTER) {
            val context = usageTracker.appContext
            val model = TranscriptionCatalog.resolve(TranscriptionCatalog.distinct(TranscriptionCatalog.available(context)), keys.openrouterModel)
            return TranscriptionEngine(context, OpenRouterApi(openRouterClient)).transcribe(audioFile, model, language, keys.openrouter) { status ->
                android.os.Handler(android.os.Looper.getMainLooper()).post { onStatus(status) }
            }.text
        }
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
            PROVIDER_SONIOX -> {
                onStatus("Transcribing (Soniox)…")
                val (text, seconds) = SonioxRest.transcribe(client, keys.soniox, audioFile, language)
                Stt(text, seconds)
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

    // Loaded whisper.cpp model, kept across calls so live-preview chunks don't reload it
    // from disk each time. Guarded by [localMutex]; released via [releaseLocalModel].
    private val localMutex = Mutex()
    private var localCtx: WhisperContext? = null
    private var localCtxPath: String? = null

    suspend fun transcribeAudioLocal(audioFile: File, modelFile: File, wavRecorder: WavRecorder, language: String): String = withContext(Dispatchers.IO) {
        val floatArray = wavRecorder.decodeWavToFloatArray(audioFile)
        localMutex.withLock {
            if (localCtxPath != modelFile.absolutePath) {
                localCtx?.release()
                localCtx = WhisperContext.createContextFromFile(modelFile.absolutePath)
                localCtxPath = modelFile.absolutePath
            }
            localCtx!!.transcribeData(floatArray, language = language, printTimestamp = false).trim()
        }
    }

    /** Frees the cached local Whisper model (call when a dictation pipeline finishes). */
    suspend fun releaseLocalModel() = withContext(Dispatchers.IO) {
        localMutex.withLock {
            localCtx?.release()
            localCtx = null
            localCtxPath = null
        }
    }

    suspend fun cleanTextLocal(rawText: String, modelFile: File, prompt: String = DEFAULT_CLEANUP_PROMPT): String = withContext(Dispatchers.IO) {
        if (rawText.isBlank()) return@withContext ""
        
        var resultText = ""
        
        try {
            val engine = FallbackEngine.initializeEngine(modelFile)
            
            val conversation = engine.createConversation(ConversationConfig())
            val fullPrompt = "$prompt\n\nHere is the raw text to clean:\n${rawText}"
            
            val responseMsg = conversation.sendMessage(fullPrompt)
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

    suspend fun cleanText(rawText: String, apiKey: String, usageTracker: UsageTracker, prompt: String = DEFAULT_CLEANUP_PROMPT): String = withContext(Dispatchers.IO) {
        if (rawText.isBlank()) return@withContext ""
        val jsonBody = JSONObject().apply {
            put("model", OPENAI_LLM_MODEL)
            put("reasoning_effort", OPENAI_REASONING_EFFORT)
            
            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", prompt)
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

    /** Rewrites [existingText] per a spoken [instruction], via the OpenAI cleanup LLM. */
    suspend fun modifyText(existingText: String, instruction: String, apiKey: String, usageTracker: UsageTracker): String =
        openAiChat(
            system = "You are a precise text editor. Apply the user's instruction to the supplied text and output ONLY the revised text — no commentary, preamble, or surrounding quotes.",
            user = "TEXT:\n$existingText\n\nINSTRUCTION:\n$instruction",
            apiKey = apiKey,
            usageTracker = usageTracker,
        )

    private suspend fun openAiChat(system: String, user: String, apiKey: String, usageTracker: UsageTracker): String = withContext(Dispatchers.IO) {
        val jsonBody = JSONObject().apply {
            put("model", OPENAI_LLM_MODEL)
            put("reasoning_effort", OPENAI_REASONING_EFFORT)
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
