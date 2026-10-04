package com.example.smartnotetaker.transcription

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.smartnotetaker.SettingsActivity
import com.example.smartnotetaker.TRANSCRIBE_LANGUAGES
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileTranscriptionScreen(vm: FileTranscriptionViewModel) {
    val state by vm.ui.collectAsState(); val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    val exportTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let { uri -> vm.export(uri, vm.exportDraft) } }
    val exportSrt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { it?.let { uri -> vm.export(uri, vm.exportDraft) } }
    var showExport by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(vm.playbackMs) }; var playing by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }; var playbackError by remember { mutableStateOf("") }
    val player: MediaPlayer? = remember<MediaPlayer?>(state.audio?.file) { if (state.audio == null) null else MediaPlayer() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(player, owner) {
        ready = false; playing = false; playbackError = ""
        try {
            player?.apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(state.audio!!.file.absolutePath)
                setOnPreparedListener { ready = true; it.seekTo(vm.playbackMs.toInt()); position = vm.playbackMs }
                setOnCompletionListener { playing = false }
                setOnErrorListener { _, _, _ -> ready = false; playbackError = "Android cannot play this audio format."; true }
                prepareAsync()
            }
        } catch (_: Exception) { playbackError = "Could not prepare playback." }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && ready) { player?.pause(); playing = false } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player?.release() }
    }
    LaunchedEffect(player, ready) { while (ready && player != null) { position = player.currentPosition.toLong(); vm.playbackMs = position; playing = player.isPlaying; delay(100) } }
    fun seek(ms: Long) { if (ready) { position = ms.coerceIn(0, state.audio?.durationMs ?: 0); vm.playbackMs = position; player?.seekTo(position, MediaPlayer.SEEK_CLOSEST) } }
    val active = state.transcript?.activeAt(position) ?: -1
    val continuous: ContinuousTranscript? = remember<ContinuousTranscript?>(state.transcript) { if (state.transcript == null) null else ContinuousTranscript(state.transcript!!) }
    var transcriptLayout by remember(state.transcript) { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val transcriptScroll = rememberLazyListState()
    LaunchedEffect(active, transcriptLayout) {
        val word = continuous?.words?.firstOrNull { it.cueIndex == active }
        val layout = transcriptLayout
        if (word != null && layout != null) {
            val bounds = layout.getBoundingBox(word.start)
            val viewport = transcriptScroll.layoutInfo
            val item = viewport.visibleItemsInfo.firstOrNull { it.index == 2 }
            val top = item?.offset?.plus(bounds.top)
            if (top == null || top < viewport.viewportStartOffset || top + bounds.height > viewport.viewportEndOffset) {
                val inset = (viewport.viewportEndOffset - viewport.viewportStartOffset) / 3
                transcriptScroll.animateScrollToItem(2, (bounds.top.toInt() - inset).coerceAtLeast(0))
            }
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("CheapWhisper") }, actions = {
            IconButton(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }) { Icon(Icons.Default.Settings, "Settings") }
        })
    }, bottomBar = {
        if (state.audio != null) Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(state.audio!!.name, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(onClick = {
                        if (playing) player?.pause() else { if (position >= state.audio!!.durationMs - 50) seek(0); player?.start() }
                        playing = !playing
                    }, enabled = ready, modifier = Modifier.size(48.dp)) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause audio" else "Play audio")
                    }
                    Slider(value = position.toFloat().coerceIn(0f, state.audio!!.durationMs.toFloat()),
                        onValueChange = { seek(it.toLong()) }, valueRange = 0f..state.audio!!.durationMs.coerceAtLeast(1).toFloat(),
                        enabled = ready, modifier = Modifier.weight(1f).padding(start = 8.dp))
                }
                Text("${audioTime(position)} / ${audioTime(state.audio!!.durationMs)}",
                    style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                if (playbackError.isNotEmpty()) Text(playbackError, color = MaterialTheme.colorScheme.error)
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = transcriptScroll,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceButton(state.model?.name ?: "Model", state.models, { it.dropdownLabel() }, !state.busy, Modifier.weight(.6f),
                            footer = { DropdownMenuItem(text = { Text("Refresh models") }, onClick = { vm.refreshCatalog() }, enabled = !state.busy) }) { vm.select(model = it) }
                        ChoiceButton(mainLanguageLabel(state.language), TRANSCRIBE_LANGUAGES,
                            { if (it.second == "auto") "auto" else it.first }, !state.busy, Modifier.weight(.4f)) { vm.select(language = it.second) }
                    }
                    Text(state.model?.price().orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { picker.launch(AUDIO_PICKER_TYPES) }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Import audio") }
                        Button(onClick = vm::transcribe, enabled = state.ready && state.audio != null && !state.busy, modifier = Modifier.weight(1f)) { Text("Transcribe") }
                        if (state.busy) IconButton(onClick = vm::cancel) { Icon(Icons.Default.Close, "Cancel") }
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.status, style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Transcript", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { showExport = true }, enabled = state.transcript?.text?.isNotBlank() == true) { Text("Export") }
                    }
                    state.resultSettings?.labelIfDifferent(state.model?.id, state.language, state.transcript?.language)?.let { label ->
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }

                }
            }
            item {
                val transcript = continuous
                val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
                if (transcript == null || transcript.text.isBlank()) {
                    Text(if (transcript == null) "Import or share an audio file to begin." else "No speech detected", Modifier.fillMaxWidth(), style = textStyle)
                } else {
                    val background = MaterialTheme.colorScheme.secondaryContainer
                    val foreground = MaterialTheme.colorScheme.onSecondaryContainer
                    val words: androidx.compose.ui.text.AnnotatedString = remember<androidx.compose.ui.text.AnnotatedString>(transcript, active, background, foreground) { transcript.highlighted(active, background, foreground) }
                    ClickableText(text = words, modifier = Modifier.fillMaxWidth(), style = textStyle,
                        onTextLayout = { layout ->
                            if (transcriptLayout?.size != layout.size || transcriptLayout?.lineCount != layout.lineCount) transcriptLayout = layout
                        },
                        onClick = { offset -> words.getStringAnnotations("seek", offset, offset).firstOrNull()?.item?.toLongOrNull()?.let(::seek) })
                }
            }

        }
    }
    if (showExport) AlertDialog(onDismissRequest = { showExport = false }, title = { Text("Export transcript") }, text = { Text("Save the full text or timestamped subtitles.") },
        confirmButton = { TextButton(onClick = { vm.exportDraft = state.transcript?.text.orEmpty(); showExport = false; exportTxt.launch("transcript.txt") }) { Text("TXT") } },
        dismissButton = { if (state.transcript?.cues?.isNotEmpty() == true) TextButton(onClick = { vm.exportDraft = state.transcript!!.srt(); showExport = false; exportSrt.launch("transcript.srt") }) { Text("SRT") } })
}

@Composable
fun <T> ChoiceButton(label: String, values: List<T>, name: (T) -> String, enabled: Boolean = true, modifier: Modifier = Modifier, footer: (@Composable () -> Unit)? = null, select: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label, maxLines = 2) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value -> DropdownMenuItem(text = { Text(name(value)) }, onClick = { select(value); expanded = false }) }
            footer?.invoke()
        }
    }
}
