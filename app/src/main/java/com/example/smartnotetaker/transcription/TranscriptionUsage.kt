package com.example.smartnotetaker.transcription

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.util.UUID

class TranscriptionUsage(context: Context) {
    private val file = AtomicFile(File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "openrouter-usage.json"))
    private fun read(): JSONArray = if (file.baseFile.exists()) JSONArray(String(file.readFully(), Charsets.UTF_8)) else JSONArray()
    private fun write(rows: JSONArray) {
        val output = file.startWrite()
        try { output.write(compact(rows).toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    private fun compact(rows: JSONArray): JSONArray {
        val all = rows.objects()
        val completed = all.filter { it.optString("state") == "complete" }
        if (completed.size < 500) return rows
        val recent = completed.takeLast(200)
        val aggregated = completed.dropLast(200).groupBy { listOf(it.optString("model"), it.isNull("cost").toString(), it.optBoolean("durationEstimated").toString()) }
            .map { (_, items) ->
                val first = JSONObject(items.first().toString())
                val seconds = items.mapNotNull { decimal(it.opt("seconds")) }.fold(BigDecimal.ZERO, BigDecimal::add)
                val cost = items.mapNotNull { decimal(it.opt("cost")) }.fold(BigDecimal.ZERO, BigDecimal::add)
                first.put("id", "summary-" + UUID.randomUUID()).put("generation", JSONObject.NULL).put("seconds", seconds.toPlainString())
                    .put("cost", if (first.isNull("cost")) JSONObject.NULL else cost.toPlainString())
                    .put("requestCount", items.sumOf { it.optInt("requestCount", 1) })
            }
        return JSONArray(all.filter { it.optString("state") == "pending" } + aggregated + recent)
    }
    fun begin(model: TranscriptionModel, seconds: Double): String = synchronized(lock) {
        val id = UUID.randomUUID().toString()
        write(read().put(JSONObject().put("id", id).put("model", model.id).put("name", model.name)
            .put("publisher", model.publisher).put("inputSeconds", seconds.toString()).put("state", "pending")))
        activeIds.add(id)
        id
    }
    fun release(id: String) = synchronized(lock) { activeIds.remove(id); Unit }
    fun complete(id: String, usage: JSONObject?, generation: String?) = update(id) {
        val cost = decimal(usage?.opt("cost")); val seconds = decimal(usage?.opt("seconds"))
        it.put("state", "complete").put("cost", cost?.toPlainString() ?: JSONObject.NULL)
            .put("seconds", seconds?.toPlainString() ?: it.getString("inputSeconds"))
            .put("durationEstimated", seconds == null).put("generation", generation ?: JSONObject.NULL)
    }
    fun reject(id: String) = update(id) { it.put("state", "rejected") }
    private fun update(id: String, action: (JSONObject) -> Unit) = synchronized(lock) {
        val rows = read(); val row = rows.objects().first { it.getString("id") == id }; action(row); write(rows)
    }
    fun reset() = synchronized(lock) { write(JSONArray(read().objects().filter { it.optString("state") == "pending" && it.optString("id") in activeIds })) }
    fun total(): BigDecimal = synchronized(lock) { read().objects().filter { it.optString("state") == "complete" }
        .mapNotNull { decimal(it.opt("cost")) }.fold(BigDecimal.ZERO, BigDecimal::add) }
    fun summary(byModel: Boolean = false): String = synchronized(lock) {
        read().objects().filter { it.optString("state") != "rejected" }.groupBy { it.optString(if (byModel) "name" else "publisher") }
            .entries.joinToString("\n\n") { (name, rows) ->
                val complete = rows.filter { it.optString("state") == "complete" }
                val seconds = complete.mapNotNull { decimal(it.opt("seconds")) }.fold(BigDecimal.ZERO, BigDecimal::add)
                val cost = complete.mapNotNull { decimal(it.opt("cost")) }.fold(BigDecimal.ZERO, BigDecimal::add)
                val pending = rows.size - complete.size; val unknown = complete.filter { decimal(it.opt("cost")) == null }.sumOf { it.optInt("requestCount", 1) }
                "$name\n${audioTime(seconds.multiply(BigDecimal(1000)).toLong())} · ${dollars(cost)}" +
                    (if (unknown > 0) " + unreported cost ($unknown)" else "") +
                    (if (pending > 0) "\n$pending unconfirmed request(s)" else "") +
                    (if (complete.any { it.optBoolean("durationEstimated") }) "\nIncludes measured file duration" else "")
            }
    }
    companion object { private val lock = Any(); private val activeIds = mutableSetOf<String>() }
}
