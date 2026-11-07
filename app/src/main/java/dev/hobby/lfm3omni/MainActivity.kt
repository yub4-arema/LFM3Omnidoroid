package dev.hobby.lfm3omni

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { App(vm) } }
    }
}

@Composable
private fun App(vm: ChatViewModel) {
    Scaffold { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (vm.phase) {
                Phase.Ready -> Chat(vm)
                else -> Setup(vm)
            }
        }
    }
}

@Composable
private fun Setup(vm: ChatViewModel) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (vm.phase) {
            Phase.NeedDownload -> {
                Button(onClick = vm::download) { Text("Download 1.4 GB") }
            }
            Phase.Downloading -> {
                LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(vm.status)
            }
            Phase.Loading -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(vm.status)
            }
            else -> {
                Text(vm.status)
                Spacer(Modifier.height(16.dp))
                Button(onClick = vm::retry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun Chat(vm: ChatViewModel) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::attachImage) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(vm::attachAudio) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.toggleRecording()
    }

    val last = vm.messages.lastOrNull()
    LaunchedEffect(vm.messages.size, last?.text) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.messages) { Bubble(it) }
        }

        if (vm.pending.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.pending.forEach { a ->
                    InputChip(selected = true, onClick = { vm.removePending(a) }, label = { Text(a.label) },
                        leadingIcon = { Icon(if (a.kind == MediaKind.Image) Icons.Default.Image else Icons.Default.Mic, null) },
                        trailingIcon = { Icon(Icons.Default.Close, "remove") })
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.messages.isNotEmpty() && !vm.generating) {
                IconButton(onClick = vm::newChat) { Icon(Icons.Default.Refresh, "New chat") }
            }
            IconButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Default.Image, "Attach image")
            }
            IconButton(onClick = { pickAudio.launch("audio/*") }) { Icon(Icons.Default.AudioFile, "Attach audio") }
            IconButton(onClick = {
                if (vm.recording) vm.toggleRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }) {
                Icon(if (vm.recording) Icons.Default.Stop else Icons.Default.Mic, "Record",
                    tint = if (vm.recording) MaterialTheme.colorScheme.error else LocalContentColor.current)
            }
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), maxLines = 4,
                shape = RoundedCornerShape(24.dp))
            if (vm.generating) {
                IconButton(onClick = vm::stop) { Icon(Icons.Default.Stop, "Stop") }
            } else {
                IconButton(onClick = { vm.send(input); input = "" }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val user = m.fromUser
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                m.attachments.forEach {
                    Icon(if (it.kind == MediaKind.Image) Icons.Default.Image else Icons.Default.Mic, it.label,
                        Modifier.size(18.dp))
                }
                SelectionContainer { Text(m.text.ifEmpty { "…" }) }
            }
        }
    }
}
