package com.example.smartnotetaker.transcription

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.smartnotetaker.UsageTracker
import kotlinx.coroutines.*

private data class UsageSnapshot(
    val rows: List<Pair<String, Long>> = emptyList(), val total: Long = 0L,
    val providers: String = "", val models: String = ""
)

@Composable
fun UsageSettingsSection() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(UsageSnapshot()) }
    var expanded by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    suspend fun refresh() {
        try {
            snapshot = withContext(Dispatchers.IO) {
                val tracker = UsageTracker(context); val journal = TranscriptionUsage(context)
                UsageSnapshot(tracker.byProvider(), tracker.totalMicros(), journal.summary(), journal.summary(true))
            }
            error = ""
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Unable to read usage history. Try reopening Settings." }
    }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { refresh(); delay(2000) }
        }
    }
    Text("Usage", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    snapshot.rows.forEach { (provider, micros) ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(provider, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(UsageTracker.formatUsd(micros), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (snapshot.rows.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Total reported cost", style = MaterialTheme.typography.titleMedium)
        Text(UsageTracker.formatUsd(snapshot.total), style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("OpenRouter transcription", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show by model") }
    }
    if (expanded) {
        Text("Time and cost by model", style = MaterialTheme.typography.labelMedium)
        Text(snapshot.models.ifBlank { "No OpenRouter usage recorded on this device." }, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = {
        resetting = true
        scope.launch {
            try { withContext(Dispatchers.IO) { UsageTracker(context).reset() }; refresh() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Could not reset usage history." }
            finally { resetting = false }
        }
    }, enabled = !resetting && (snapshot.rows.isNotEmpty() || snapshot.providers.isNotEmpty()),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) {
        Text(if (resetting) "Resetting…" else "Reset usage")
    }
}
