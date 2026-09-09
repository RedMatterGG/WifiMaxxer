package com.wifimaxxer

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val wifi by lazy { applicationContext.getSystemService(WifiManager::class.java) }
    private var rootReady by mutableStateOf(false)
    private var lowLatencyAvailable by mutableStateOf(false)
    private var rootDetail by mutableStateOf("Superuser access has not been checked.")
    private var rootChecking by mutableStateOf(false)
    private var useService by mutableStateOf(false)
    private var status by mutableStateOf("OEM settings preserved")
    private val events = mutableStateListOf<String>()
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.containsKey(Manifest.permission.POST_NOTIFICATIONS)) {
            record(if (result[Manifest.permission.POST_NOTIFICATIONS] == true) "Session notifications enabled." else "Notifications declined. Restore remains available in the app.")
        } else record(if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) "Connection details permitted." else "Permission declined. Some details remain hidden.")
    }

    private fun record(message: String) {
        status = message
        events.addAll(runCatching { AppLog.append(this, message) }
            .getOrElse { listOf("[LOG WRITE FAILED] $message · ${it.message}") })
    }

    private fun clearLog() {
        runCatching {
            AppLog.clear(this)
            events.clear()
            record("Persistent log cleared by user.")
        }.onFailure { record("Could not clear the persistent log: ${it.message}") }
    }

    private fun openLogFile() {
        runCatching {
            record("Opening persistent log file.")
            val uri = FileProvider.getUriForFile(this, "$packageName.files", AppLog.file(this))
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri("WiFi Maxxer log", uri) }
            startActivity(Intent.createChooser(view, "Open WiFi Maxxer log"))
        }.onFailure { record("Could not open the persistent log: ${it.message}") }
    }

    private fun setWll(restore: Boolean = false) {
        runCatching {
            if (RootControlState.serviceRunning || (!restore && useService)) WllService.start(this, restore)
            else RootToggle.set(this, enabled = !restore)
        }
            .onFailure { record(it.message ?: "Could not start the WLL service.") }
    }

    private fun detectRoot() {
        if (rootChecking) return
        rootChecking = true
        lifecycleScope.launch {
            try {
                val probe = withContext(Dispatchers.IO) { RootAccess.probe() }
                rootReady = true
                lowLatencyAvailable = probe.lowLatencyAvailable
                rootDetail = probe.detail
                RootControlState.message = "Root detected automatically. ${probe.detail}"
            } catch (e: Exception) {
                rootReady = false
                lowLatencyAvailable = false
                rootDetail = e.message ?: "Root not detected"
                RootControlState.message = "Automatic root detection failed. $rootDetail"
            } finally { rootChecking = false }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        events.addAll(runCatching { AppLog.read(this) }.getOrDefault(emptyList()))
        record("WiFi Maxxer opened.")
        deleteSharedPreferences("custom-profile")
        deleteSharedPreferences("tweak-benchmark")
        useService = getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("foreground-service", false)
        RootToggle.load(this)
        expireCustomSessionAfterReboot(this)
        val baselinePrefs = getSharedPreferences("custom-baseline", MODE_PRIVATE)
        refreshTuningDirtyState(this)
        if (baselinePrefs.contains("values") && baselinePrefs.getInt("schema", 0) != 2)
            record("Safety upgrade: the previous baseline is not trusted. Reboot, then reinstall or clear app storage before changing settings.")
        detectRoot()
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFC6F279), background = Color(0xFF101410), surface = Color(0xFF1B211B), secondary = Color(0xFFAEC9A4))) {
                var page by remember { mutableIntStateOf(0) }
                var current by remember { mutableStateOf(Connection()) }
                var cellular by remember { mutableStateOf<NetworkTarget?>(null) }
                val listState = rememberLazyListState()
                val features = remember { capabilities(wifi) }
                val wll = RootControlState.active
                val busy = RootControlState.busy
                LaunchedEffect(RootControlState.message) { record(RootControlState.message) }
                LaunchedEffect(page) { listState.scrollToItem(0) }
                LaunchedEffect(Unit) {
                    while (true) {
                        current = runCatching { connection(this@MainActivity) }.getOrDefault(Connection("Details unavailable"))
                        cellular = runCatching { networkTarget(this@MainActivity, cellular = true) }.getOrNull()
                        delay(2500)
                    }
                }
                LaunchedEffect(rootReady, current.iface) {
                    if (rootReady && current.iface != null && !RootControlState.busy &&
                        !getSharedPreferences("custom-baseline", MODE_PRIVATE).getBoolean("captured", false)) {
                        RootControlState.busy = true
                        try {
                            withContext(Dispatchers.IO) { CustomTuning(applicationContext, true).scan(captureDefaults = true) }
                            record("First-run defaults available. Saved values are retained across app starts and phone reboots.")
                        } catch (e: Exception) { record(e.message ?: "Defaults unavailable") }
                        finally { RootControlState.busy = false }
                    }
                }
                Scaffold(bottomBar = {
                    NavigationBar {
                        listOf("Overview", "Device", "Activity", "Tuning").forEachIndexed { index, title ->
                            NavigationBarItem(selected = page == index, onClick = { page = index }, icon = { Text(listOf("◉", "▣", "≡", "⚙")[index], fontSize = 22.sp) }, label = { Text(title) })
                        }
                    }
                }) { padding ->
                    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp), state = listState, verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
                        item {
                            Text("WIFI MAXXER", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, letterSpacing = 3.sp)
                            Spacer(Modifier.height(12.dp))
                            Text(listOf("A better connection.", "Device details.", "Session activity.", "Basic and advanced controls.")[page], style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                            Text("Runtime tuning.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        }
                        if (page == 0) {
                            item { Panel("SUPERUSER ACCESS") {
                                Text(if (rootChecking) "Checking root…" else if (rootReady) "Root detected" else "Root not detected", style = MaterialTheme.typography.titleLarge)
                                Text("Root is checked automatically when the app opens. Authorize WiFi Maxxer in your root manager if prompted.")
                                Text(rootDetail, style = MaterialTheme.typography.bodySmall, color = if (rootReady) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!rootReady) TextButton(onClick = { page = 3 }) { Text("View unavailable runtime controls") }
                                if (useService && Build.VERSION.SDK_INT >= 33) TextButton(onClick = { permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }) { Text("Allow session notification") }
                                if (WllService.pending(this@MainActivity) && !wll) Text("WLL recovery is pending. Grant root or use Restore before applying it again.", color = MaterialTheme.colorScheme.error)
                            } }
                            item { Panel("YOUR CONNECTION") {
                                Text(current.name, style = MaterialTheme.typography.titleLarge)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Metric(current.rssi?.toString() ?: "—", "dBm · signal")
                                    Metric(current.speed?.toString() ?: "—", "Mbps · link")
                                    Metric(current.frequency?.let { String.format(Locale.US, "%.1f", it / 1000.0) } ?: "—", "GHz · band")
                                }
                                Text("Link rate is negotiated radio speed, not measured internet throughput.", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Allow connection details") }
                            } }
                            item { Panel("WI-FI LOW LATENCY") {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("WLL (Wi-Fi Low Latency)", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                                    Switch(checked = wll, onCheckedChange = { setWll(restore = !it) },
                                        enabled = !busy && !rootChecking && (wll || (rootReady && lowLatencyAvailable && !WllService.pending(this@MainActivity))))
                                }
                                Text(if (wll) "WLL is active" else "Android automatic mode", style = MaterialTheme.typography.titleMedium)
                                if (RootControlState.customChanged) Text("Other runtime settings are active. Restore them from Tuning before changing the network target.")
                                Text("WLL asks Android and the Wi-Fi firmware to prioritize packet latency. It can reduce throughput, reduce scanning and roaming, and increase battery use. Turning it off restores Android automatic behavior.", style = MaterialTheme.typography.bodyMedium)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Foreground service", modifier = Modifier.weight(1f))
                                    Switch(checked = useService, enabled = !busy && !wll && !WllService.pending(this@MainActivity), onCheckedChange = {
                                        useService = it
                                        getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("foreground-service", it).apply()
                                        record("WLL foreground service ${if (it) "enabled" else "disabled"}.")
                                    })
                                }
                                Text(if (useService) "Optional protection: connection checks, a restore notification, and a 45-second watchdog. Turn WLL off before changing this option."
                                    else "Service off: no notification, or connection monitoring. The setting remains until Restore, reboot, or a framework reset. Nothing is reapplied at boot.", style = MaterialTheme.typography.bodySmall)
                                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                                OutlinedButton(onClick = { setWll(restore = true) }, enabled = !busy && rootReady, modifier = Modifier.fillMaxWidth()) { Text("Restore Android automatic mode") }
                            } }
                            item { BandwidthLimitCard(rootReady, BandwidthTarget.WIFI, current.iface, current.maxRxSpeed, current.maxTxSpeed, ::record) }
                            item { BandwidthLimitCard(rootReady, BandwidthTarget.CELLULAR, cellular?.iface, cellular?.downstreamMbps, cellular?.upstreamMbps, ::record) }
                            item { Panel("BENCHMARKS") {
                                Text("Run independent browser tests. These tests do not change app settings.")
                                Button(
                                    onClick = { record("Opening Cloudflare speed test."); startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://speed.cloudflare.com/"))) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Open Cloudflare speed test") }
                                OutlinedButton(
                                    onClick = { record("Opening Jitter.is test."); startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://jitter.is/"))) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Open Jitter.is test") }
                            } }
                            item { Text(status, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (page == 1) {
                            item { Panel("DEVICE FINGERPRINT") {
                                Detail("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                                Detail("Android", "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
                                Detail("SoC", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE)
                                Detail("Access", if (rootReady) "Superuser · root" else "Root not authorized this session")
                                Detail("Interface", current.iface ?: "Unavailable")
                            } }
                            item { Panel("REPORTED CAPABILITIES") { features.forEach { Detail(it.first, it.second) } } }
                            item { Panel("DEVICE SAFETY") {
                                Text("Most controls are runtime-only and do not modify boot scripts, radio power, country settings or antenna configuration. Wi-Fi verbose logging is framework-persistent; Qualcomm channel bonding directly edits a detected configuration after preserving a verified original backup.")
                                Text("Android 14+ and vendor firmware manage Wi-Fi 7 MLO, OFDMA, TWT, channel choice and roaming. WiFi Maxxer reports supported standards but does not invent unsupported vendor switches.")
                                OutlinedButton(onClick = { record("Opening Android Wi-Fi settings."); startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }) { Text("Open Wi-Fi settings") }
                            } }
                        }
                        if (page == 2) {
                            item { Panel("PERSISTENT TERMINAL LOG") {
                                Text("Every app event and applied change is timestamped and appended to one file. The history remains after the app restarts.")
                                TerminalLog(events)
                                Button(onClick = { clearLog() }, modifier = Modifier.fillMaxWidth()) { Text("Clear log") }
                                OutlinedButton(onClick = { openLogFile() }, modifier = Modifier.fillMaxWidth()) { Text("Open log file") }
                                Text("Stored in private app storage:\n/data/user/0/com.wifimaxxer/files/logs/wifi-maxxer.log\nA temporary read-only copy is shared with the viewer you choose.", style = MaterialTheme.typography.bodySmall)
                            } }
                            item { OutlinedButton(onClick = {
                                record("Exporting session diagnostics with the latest persistent log entries.")
                                val report = "WiFi Maxxer root session report\n${Build.MANUFACTURER} ${Build.MODEL}\nAPI ${Build.VERSION.SDK_INT}\nRoot authorized: $rootReady\n$rootDetail\n${RootControlState.message}\n${RootAccess.diagnostic}\n" + features.joinToString("\n") { "${it.first}: ${it.second}" } + "\n\nLatest persistent log entries:\n" + events.takeLast(250).joinToString("\n")
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report) }, "Export diagnostics"))
                            }) { Text("Export session report") } }
                        }
                        if (page == 3) { item { TuningScreen(rootReady, ::record) } }
                        item { Text("WIFI MAXXER  /  v0.13.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
                    }
                }
            }
        }
    }
}

@Composable private fun TerminalLog(lines: List<String>) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.lastIndex) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF050805),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFF405240))
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("●  ●  ●", color = Color(0xFF789778), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text("wifi-maxxer.log", color = Color(0xFFAEC9A4), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            HorizontalDivider(color = Color(0xFF263226))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(430.dp).padding(12.dp),
                state = state,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (lines.isEmpty()) item { Text("Log is empty.", color = Color(0xFF91B891), fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                items(lines.size) { index -> Text(lines[index], color = Color(0xFFB8E6A8), fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
    }
}

private fun format(value: Double?) = value?.let { String.format(Locale.US, "%.1f", it) } ?: "—"

@Composable fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, letterSpacing = 1.5.sp)
        content()
    }
}

@Composable private fun Metric(value: String, label: String) {
    Column { Text(value, fontSize = 27.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary); Text(label, style = MaterialTheme.typography.labelSmall) }
}

@Composable private fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
