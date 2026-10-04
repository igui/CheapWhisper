package com.example.smartnotetaker.transcription

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

data class TranscriptionModel(val metadata: JSONObject) {
    val id: String = metadata.getString("id")
    val name: String = metadata.optString("name", id)
    val publisher: String = metadata.optString("publisher", id.substringBefore('/'))
    val textOnly: Boolean get() = metadata.optBoolean("timestampsUnavailable")
    val maxChunkSeconds: Int get() = metadata.optInt("maxChunkSeconds", 60).coerceIn(1, 60)
    val wer: Double get() = metadata.optDouble("wer", Double.POSITIVE_INFINITY)
    fun dropdownLabel(): String = name + (if (wer.isFinite()) String.format(Locale.ROOT, " · AA WER %.2f%%", wer) else "") + "\n" + price()
    fun details(): String = price() + "\n" + (if (wer.isFinite()) String.format(Locale.ROOT, "AA WER %.2f%%", wer) else "AA WER unavailable") +
        (if (textOnly) " · Text only" else " · Timing depends on route") +
        metadata.optString("note").takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
    fun price(): String {
        val pricing = metadata.optJSONObject("pricing") ?: return "Price unavailable"
        val input = decimal(pricing.opt("prompt")) ?: return "Price unavailable"
        return when (metadata.optString("priceUnit")) {
            "hour" -> "${dollars(input)}/hour"
            "second" -> "From ${dollars(input.multiply(BigDecimal(3600)))}/hour"
            "token" -> decimal(pricing.opt("completion"))?.let { output ->
                "~${dollars(input.multiply(BigDecimal(90000)).add(output.multiply(BigDecimal(12000))))}/hour"
            } ?: "Hourly estimate unavailable"
            else -> "Pricing unit unverified"
        }
    }
}

object TranscriptionCatalog {
    const val DEFAULT = "microsoft/mai-transcribe-2"
    fun all(context: Context): List<TranscriptionModel> = context.assets.open("openrouter-models.json").bufferedReader().use {
        JSONObject(it.readText()).getJSONArray("models").objects().map(::TranscriptionModel)
    }
    fun family(id: String): String = when {
        id.startsWith("microsoft/mai-transcribe") -> "microsoft/mai"
        id.startsWith("openai/whisper") -> "openai/whisper"
        id.startsWith("openai/gpt") && "transcribe" in id -> "openai/gpt-transcribe"
        id.startsWith("mistralai/voxtral") -> "mistralai/voxtral"
        id.startsWith("qwen/qwen3-asr") -> "qwen/qwen3-asr"
        id.startsWith("fish-audio/transcribe") -> "fish-audio/transcribe"
        else -> id
    }
    private fun score(model: TranscriptionModel): Double = when (model.id) {
        "qwen/qwen3-asr-1.7b", "fish-audio/transcribe-1-pro" -> 900.0
        "qwen/qwen3-asr-flash-2026-02-10" -> 1000.0
        else -> model.wer
    }
    fun distinct(models: List<TranscriptionModel>): List<TranscriptionModel> = models.groupBy { family(it.id) }
        .values.map { family -> family.minBy { score(it) } }.sortedBy(::score)
    fun resolve(models: List<TranscriptionModel>, id: String): TranscriptionModel =
        models.firstOrNull { family(it.id) == family(id) } ?: models.first()
    fun merge(raw: JSONArray, known: List<TranscriptionModel>): List<TranscriptionModel> = distinct(raw.objects().filter {
        it.optJSONObject("architecture")?.optJSONArray("output_modalities")?.let { modes -> (0 until modes.length()).any { i -> modes.optString(i) == "transcription" } } == true
    }.map { item ->
        val id = item.getString("id")
        val prior = known.firstOrNull { it.id == id }
        val metadata = prior?.let { JSONObject(it.metadata.toString()) } ?: JSONObject().put("id", id)
            .put("name", item.optString("name", id)).put("publisher", id.substringBefore('/')).put("priceUnit", "unknown")
        TranscriptionModel(metadata.put("pricing", item.optJSONObject("pricing")))
    })
}

data class TimedCue(val startMs: Long, val endMs: Long, val text: String)
data class AudioTranscript(val text: String, val language: String, val cues: List<TimedCue>) {
    fun activeAt(ms: Long): Int {
        var low = 0; var high = cues.lastIndex; var found = -1
        while (low <= high) { val middle = (low + high) ushr 1; if (cues[middle].startMs <= ms) { found = middle; low = middle + 1 } else high = middle - 1 }
        return if (found >= 0 && ms < cues[found].endMs) found else -1
    }
    fun srt(): String = cues.mapIndexed { index, cue -> "${index + 1}\n${srtTime(cue.startMs)} --> ${srtTime(cue.endMs)}\n${cue.text}\n" }.joinToString("\n")
    companion object {
        fun parse(json: JSONObject): AudioTranscript {
            require(json.opt("text") is String) { "OpenRouter returned an invalid transcript." }
            fun cues(array: JSONArray?): List<TimedCue> = array?.objects().orEmpty().mapNotNull {
                val start = it.optDouble("start", -1.0); val end = it.optDouble("end", -1.0)
                val text = it.optString("word", it.optString("text"))
                if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start || text.isBlank()) null
                else TimedCue(Math.round(start * 1000), Math.round(end * 1000), text)
            }.sortedBy { it.startMs }
            val words = cues(json.optJSONArray("words"))
            return AudioTranscript(json.getString("text"), json.optString("language", "Not reported"), words.ifEmpty { cues(json.optJSONArray("segments")) })
        }
        private fun srtTime(ms: Long) = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
    }
}

class TranscriptAccumulator {
    private val text = StringBuilder()
    private val cues = mutableListOf<TimedCue>()
    private var language = "Not reported"
    private var completeTiming = true
    fun append(result: AudioTranscript, offsetMs: Long) {
        if (text.isNotEmpty() && result.text.isNotEmpty()) text.append(' ')
        text.append(result.text)
        if (result.language != "Not reported") language = result.language
        if (result.text.isNotBlank() && result.cues.isEmpty()) completeTiming = false
        cues += result.cues.map { it.copy(startMs = it.startMs + offsetMs, endMs = it.endMs + offsetMs) }
    }
    fun finish(): AudioTranscript = AudioTranscript(text.toString(), language, if (completeTiming) cues.sortedBy { it.startMs } else emptyList())
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun decimal(value: Any?): BigDecimal? = if (value == null || value == JSONObject.NULL) null else value.toString().toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
fun dollars(value: BigDecimal?): String = when {
    value == null -> "Not reported"
    value.signum() > 0 && value < BigDecimal("0.01") -> "< $0.01"
    else -> "$" + value.setScale(2, RoundingMode.HALF_UP).toPlainString()
}
fun audioTime(ms: Long): String = String.format(Locale.ROOT, "%d:%02d:%02d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60)

fun mainLanguageLabel(code: String): String = "language: " +
    (if (code == "auto") "auto" else com.example.smartnotetaker.TRANSCRIBE_LANGUAGES.firstOrNull { it.second == code }?.first ?: code)
