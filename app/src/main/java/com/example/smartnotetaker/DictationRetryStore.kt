package com.example.smartnotetaker

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class PendingDictation(
    val id: String, val mode: String, val wav: File, val rawText: String?, val fieldBefore: String,
    val editor: String, val provider: String, val language: String, val openRouterModel: String,
    val llm: String, val cleanupPrompt: String, val waitingForNetwork: Boolean = false
) {
    fun matches(editor: String, text: String): Boolean = this.editor == editor && fieldBefore == text
}

class DictationRetryStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "pending-dictations").apply { mkdirs() }
    private val index = AtomicFile(File(directory, "index.json"))
    private fun read(): List<PendingDictation> {
        if (!index.baseFile.exists()) return emptyList()
        val rows = try { JSONArray(String(index.readFully(), Charsets.UTF_8)) } catch (_: org.json.JSONException) {
            index.baseFile.copyTo(File(directory, "index-corrupt-${System.currentTimeMillis()}.json"), overwrite = false)
            return emptyList()
        }
        return (0 until rows.length()).mapNotNull { i -> runCatching {
            val row = rows.getJSONObject(i); val id = row.getString("id")
            require(UUID.fromString(id).toString() == id)
            PendingDictation(id, row.getString("mode"), File(directory, "$id.wav"), row.optString("raw").takeIf { !row.isNull("raw") },
                row.getString("field"), row.getString("editor"), row.getString("provider"), row.getString("language"),
                row.getString("model"), row.getString("llm"), row.getString("prompt"), row.optBoolean("offline"))
        }.getOrNull() }.filter { it.wav.isFile }
    }
    private fun write(rows: List<PendingDictation>) {
        val json = JSONArray(rows.map { row -> JSONObject().put("id", row.id).put("mode", row.mode)
            .put("raw", row.rawText ?: JSONObject.NULL).put("field", row.fieldBefore).put("editor", row.editor)
            .put("provider", row.provider).put("language", row.language).put("model", row.openRouterModel)
            .put("llm", row.llm).put("prompt", row.cleanupPrompt).put("offline", row.waitingForNetwork) })
        val output = index.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); index.finishWrite(output) }
        catch (e: Exception) { index.failWrite(output); throw e }
    }
    fun first(): PendingDictation? = synchronized(lock) { read().firstOrNull() }
    fun forEditor(editor: String, text: String): PendingDictation? = synchronized(lock) {
        val rows = read(); rows.firstOrNull { it.matches(editor, text) } ?: rows.firstOrNull()
    }
    fun enqueue(source: File, mode: String, field: String, editor: String, provider: String, language: String,
        model: String, llm: String, prompt: String): PendingDictation = synchronized(lock) {
        val id = UUID.randomUUID().toString(); val audio = File(directory, "$id.wav")
        try {
            source.inputStream().use { input -> audio.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
            val row = PendingDictation(id, mode, audio, null, field, editor, provider, language, model, llm, prompt)
            write(read() + row); row
        } catch (e: Exception) { audio.delete(); throw e }
    }
    fun save(row: PendingDictation) = synchronized(lock) {
        val rows = read(); write(rows.map { if (it.id == row.id) row else it })
    }
    fun remove(row: PendingDictation) = synchronized(lock) { write(read().filter { it.id != row.id }); row.wav.delete(); Unit }
    companion object { private val lock = Any() }
}
