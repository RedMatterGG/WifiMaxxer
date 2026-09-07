package com.wifimaxxer

import android.Manifest
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    private val events = mutableStateListOf("Ready. No persistent system changes.")
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.containsKey(Manifest.permission.POST_NOTIFICATIONS)) {
            record(if (result[Manifest.permission.POST_NOTIFICATIONS] == true) "Session notifications enabled." else "Notifications declined. Restore remains available in the app.")
        } else record(if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) "Connection details permitted." else "Permission declined. Some details remain hidden.")
    }

    private fun record(message: String) {
        status = message
        events.add(0, message)
        if (events.size > 50) events.removeAt(events.lastIndex)
    }

    private fun startProfile(restore: Boolean = false) {
        runCatching {
            if (RootProfileState.serviceRunning || (!restore && useService)) RootProfileService.start(this, restore)
            else RootToggle.set(this, enabled = !restore)
        }
            .onFailure { record(it.message ?: "Could not start root profile service.") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useService = getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("foreground-service", false)
        RootToggle.load(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFC6F279), background = Color(0xFF101410), surface = Color(0xFF1B211B), secondary = Color(0xFFAEC9A4))) {
                val scope = rememberCoroutineScope()
                var page by remember { mutableIntStateOf(0) }
                var current by remember { mutableStateOf(Connection()) }
                var testing by remember { mutableStateOf(false) }
                var result by remember { mutableStateOf<PingStats?>(null) }
                var baseline by remember { mutableStateOf<PingStats?>(null) }
                val features = remember { capabilities(wifi) }
                val gaming = RootProfileState.active
                val busy = RootProfileState.busy
                LaunchedEffect(RootProfileState.message) { record(RootProfileState.message) }
                LaunchedEffect(Unit) {
                    while (true) {
                        current = runCatching { connection(this@MainActivity) }.getOrDefault(Connection("Details unavailable"))
                        delay(2500)
                    }
                }
                Scaffold(bottomBar = {
                    NavigationBar {
                        listOf("Overview", "Device", "Activity").forEachIndexed { index, title ->
                            NavigationBarItem(selected = page == index, onClick = { page = index }, icon = { Text(listOf("◉", "▣", "≡")[index], fontSize = 22.sp) }, label = { Text(title) })
                        }
                    }
                }) { padding ->
                    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
                        item {
                            Text("WIFI MAXXER", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, letterSpacing = 3.sp)
                            Spacer(Modifier.height(12.dp))
                            Text(listOf("A better connection.", "Device details.", "Session activity.")[page], style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                            Text("Runtime tuning.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        }
                        if (page == 0) {
                            item { Panel("SUPERUSER ACCESS") {
                                Text(if (rootReady) "Root connected" else "Root required", style = MaterialTheme.typography.titleLarge)
                                Text("Grant WiFi Maxxer superuser access in Magisk, KernelSU, APatch or another compatible root manager.")
                                Button(enabled = !rootChecking && !busy, onClick = {
                                    rootChecking = true
                                    scope.launch {
                                        try {
                                            val message = withContext(Dispatchers.IO) { RootAccess.probe() }
                                            rootReady = true
                                            lowLatencyAvailable = message.lowLatencyAvailable
                                            rootDetail = message.detail
                                            record(message.detail)
                                            if (RootProfileService.pending(this@MainActivity) && !RootProfileState.active) startProfile(restore = true)
                                        } catch (e: Exception) { rootReady = false; lowLatencyAvailable = false; rootDetail = e.message ?: "Root unavailable."; record(rootDetail) }
                                        finally { rootChecking = false }
                                    }
                                }) { Text(if (rootChecking) "Waiting for root…" else if (rootReady) "Recheck root" else "Grant root access") }
                                Text(rootDetail, style = MaterialTheme.typography.bodySmall, color = if (rootReady) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (useService && Build.VERSION.SDK_INT >= 33) TextButton(onClick = { permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }) { Text("Allow session notification") }
                                if (RootProfileService.pending(this@MainActivity) && !gaming) Text("Recovery pending. Grant root or use Restore before applying another profile.", color = MaterialTheme.colorScheme.error)
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
                            item { Panel("PERFORMANCE PROFILE") {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Gaming", style = MaterialTheme.typography.titleLarge)
                                    Switch(checked = gaming, onCheckedChange = { startProfile(restore = !it) },
                                        enabled = !busy && !rootChecking && (gaming || (rootReady && lowLatencyAvailable && !RootProfileService.pending(this@MainActivity))))
                                }
                                Text(if (gaming) "Gaming profile applied" else "Apply once. Use any app.", style = MaterialTheme.typography.titleMedium)
                                Text("The toggle applies the low-latency profile globally through root. Turn it off or use Restore to return to automatic mode. It may increase battery use.", style = MaterialTheme.typography.bodyMedium)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Foreground service", modifier = Modifier.weight(1f))
                                    Switch(checked = useService, enabled = !busy && !gaming && !RootProfileService.pending(this@MainActivity), onCheckedChange = {
                                        useService = it
                                        getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("foreground-service", it).apply()
                                    })
                                }
                                Text(if (useService) "Optional protection: connection checks, a restore notification, 45-second watchdog. Restore the profile before changing this option."
                                    else "Service off: no notification, or connection monitoring. The setting remains until Restore, reboot, or a framework reset. Nothing is reapplied at boot.", style = MaterialTheme.typography.bodySmall)
                                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                                OutlinedButton(onClick = { startProfile(restore = true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Restore automatic behavior") }
                            } }
                            item { Panel("MEASUREMENT") {
                                Text("Gateway latency", style = MaterialTheme.typography.titleLarge)
                                Text("10 ICMP probes over Wi-Fi. Routers may block ping; this is not an internet speed test.")
                                if (testing) LinearProgressIndicator(Modifier.fillMaxWidth())
                                result?.let { stats ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Metric(format(stats.average), "ms · average")
                                        Metric(format(stats.p95), "ms · p95")
                                        Metric(format(stats.loss), "% · loss")
                                    }
                                    Text("Jitter: ${format(stats.jitter)} ms · ${stats.received}/${stats.sent} replies")
                                    baseline?.let { Text("Saved baseline: ${format(it.average)} ms average · ${format(it.loss)}% loss") }
                                    TextButton(onClick = { baseline = stats; record("Current measurement saved as session baseline.") }) { Text("Use as baseline") }
                                }
                                Button(enabled = !testing && current.gateway != null, modifier = Modifier.fillMaxWidth(), onClick = {
                                    testing = true
                                    val snapshot = current
                                    scope.launch {
                                        try {
                                            result = withContext(Dispatchers.IO) { pingGateway(snapshot) }
                                            val after = connection(this@MainActivity)
                                            if (after.iface != snapshot.iface || after.gateway != snapshot.gateway || after.name != snapshot.name) { result = null; record("Connection changed during test. Result discarded.") }
                                            else record("Gateway test complete. ${result!!.received}/10 replies.")
                                        } catch (e: Exception) { record(e.message ?: "Test failed") }
                                        finally { testing = false }
                                    }
                                }) { Text(if (testing) "Measuring…" else "Run latency test") }
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
                                Text("No vendor files, boot scripts, radio power, country settings or antenna configuration are modified.")
                                Text("Root controls the framework low-latency override. Driver offloads and MLO controls remain untouched until a readable, reversible interface is verified. Chipset names alone never enable tweaks.")
                                OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }) { Text("Open Wi-Fi settings") }
                            } }
                        }
                        if (page == 2) {
                            item { OutlinedButton(onClick = {
                                val report = "WiFi Maxxer root session report\n${Build.MANUFACTURER} ${Build.MODEL}\nAPI ${Build.VERSION.SDK_INT}\nRoot authorized: $rootReady\n$rootDetail\n${RootProfileState.message}\n${RootAccess.diagnostic}\n" + features.joinToString("\n") { "${it.first}: ${it.second}" } + "\n\n" + events.joinToString("\n")
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report) }, "Export diagnostics"))
                            }) { Text("Export session report") } }
                            items(events.size) { index -> Panel(if (index == 0) "LATEST" else "SESSION EVENT") { Text(events[index]) } }
                        }
                        item { Text("ROOT POWERED  /  v0.2.1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
                    }
                }
            }
        }
    }
}

private fun format(value: Double?) = value?.let { String.format(Locale.US, "%.1f", it) } ?: "—"

@Composable private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
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
