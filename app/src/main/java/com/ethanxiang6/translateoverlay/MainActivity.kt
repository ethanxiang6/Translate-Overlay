package com.ethanxiang6.translateoverlay

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val translator = TextTranslator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(translator)
                }
            }
        }
    }

    override fun onDestroy() {
        translator.close()
        super.onDestroy()
    }
}

@Composable
private fun SettingsScreen(translator: TextTranslator) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val running by RunState.running.collectAsState()

    var source by remember { mutableStateOf(prefs.sourceLang) }
    var target by remember { mutableStateOf(prefs.targetLang) }
    var ocr by remember { mutableStateOf(prefs.ocrScript) }
    var opacity by remember { mutableFloatStateOf(prefs.boxOpacity) }
    var textScale by remember { mutableFloatStateOf(prefs.textScale) }
    var sps by remember { mutableIntStateOf(prefs.scansPerSecond) }
    var minDp by remember { mutableIntStateOf(prefs.minTextDp) }
    var dark by remember { mutableStateOf(prefs.darkBoxes) }
    var packStatus by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun start() {
        if (!Settings.canDrawOverlays(context)) {
            hint = "Allow \"Display over other apps\" for this app, then press Start again."
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        hint = ""
        context.startActivity(Intent(context, CaptureRequestActivity::class.java))
    }

    fun stop() {
        context.startService(
            Intent(context, OverlayService::class.java).apply { action = OverlayService.ACTION_STOP }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Translate Overlay", style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (running) "Overlay is running" else "Overlay is stopped")
                if (running) {
                    Button(
                        onClick = { stop() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("Stop overlay") }
                } else {
                    Button(onClick = { start() }, modifier = Modifier.fillMaxWidth()) { Text("Start overlay") }
                }
                if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("Languages", style = MaterialTheme.typography.titleMedium)
        Picker("Translate from", Languages.nameOf(source), listOf("Auto detect" to "auto") + Languages.all) {
            source = it; prefs.sourceLang = it
        }
        Picker("Translate to", Languages.nameOf(target), Languages.all) {
            target = it; prefs.targetLang = it
        }
        if (source == "auto") {
            Picker(
                "Text type to look for",
                ocrLabel(ocr),
                listOf(
                    "All (slower)" to "auto", "Latin" to "latin", "Chinese" to "chinese",
                    "Japanese" to "japanese", "Korean" to "korean", "Devanagari" to "devanagari",
                ),
            ) { ocr = it; prefs.ocrScript = it }
            Text(
                "Language packs for detected languages download automatically the first time they are seen. " +
                    "After that everything works offline.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            OutlinedButton(
                onClick = {
                    packStatus = "Downloading..."
                    scope.launch {
                        val ok = translator.prepare(source, target)
                        packStatus = if (ok) "Language pack ready, works offline." else "Download failed, check your connection."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download language pack for this pair") }
            if (packStatus.isNotEmpty()) Text(packStatus, style = MaterialTheme.typography.bodySmall)
        }

        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Dark boxes")
            Switch(checked = dark, onCheckedChange = { dark = it; prefs.darkBoxes = it })
        }
        LabeledSlider("Box opacity: ${(opacity * 100).toInt()}%", opacity, 0.3f..1f) {
            opacity = it; prefs.boxOpacity = it
        }
        LabeledSlider("Text size: ${"%.1f".format(textScale)}x", textScale, 0.6f..1.5f) {
            textScale = it; prefs.textScale = it
        }

        Text("Performance", style = MaterialTheme.typography.titleMedium)
        LabeledSlider("Scans per second: $sps", sps.toFloat(), 1f..15f, steps = 13) {
            sps = it.toInt(); prefs.scansPerSecond = sps
        }
        LabeledSlider("Ignore text smaller than: $minDp dp", minDp.toFloat(), 4f..24f, steps = 19) {
            minDp = it.toInt(); prefs.minTextDp = minDp
        }
        Text(
            "Changes apply immediately, even while the overlay is running. Taps always pass through the overlay to the app underneath.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))
    }
}

private fun ocrLabel(v: String) = when (v) {
    "latin" -> "Latin"
    "chinese" -> "Chinese"
    "japanese" -> "Japanese"
    "korean" -> "Korean"
    "devanagari" -> "Devanagari"
    else -> "All (slower)"
}

@Composable
private fun Picker(label: String, current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(current) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (name, tag) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onPick(tag); open = false })
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(label)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}
