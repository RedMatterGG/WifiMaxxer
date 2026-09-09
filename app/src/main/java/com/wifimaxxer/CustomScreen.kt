package com.wifimaxxer

import android.content.Context
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable
fun TuningScreen(root: Boolean, log: (String) -> Unit) {
    val context = LocalContext.current
    val settingsPrefs = remember { context.getSharedPreferences("preferences", Context.MODE_PRIVATE) }
    var cellular by remember { mutableStateOf(settingsPrefs.getBoolean("mobile-data-mode", false)) }
    val selectedInterface = networkTarget(context, cellular)?.iface
    val scope = rememberCoroutineScope()
    var engine by remember { mutableStateOf<CustomTuning?>(null) }
    var states by remember { mutableStateOf<Map<String, TweakState>>(emptyMap()) }
    var origamiEngine by remember { mutableStateOf<OrigamiNetworkTuning?>(null) }
    var origamiStates by remember { mutableStateOf<Map<String, OrigamiNetworkState>>(emptyMap()) }
    var bonding by remember { mutableStateOf<BondingState?>(null) }
    var desiredBond24 by remember { mutableStateOf<Boolean?>(null) }
    var desiredBond5 by remember { mutableStateOf<Boolean?>(null) }
    var bondingAction by remember { mutableStateOf<String?>(null) }
    var bondingUnderstood by remember { mutableStateOf(false) }
    var pendingSetting by remember { mutableStateOf<Pair<Tweak, Boolean>?>(null) }
    var pendingOrigamiSetting by remember { mutableStateOf<Pair<OrigamiNetworkSetting, String>?>(null) }
    var advancedUnderstood by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("Original values are captured automatically once and retained for Restore.") }
    val busy = RootControlState.busy
    val blocked = busy || RootControlState.active || RootControlState.serviceRunning || WllService.pending(context)

    fun task(action: suspend () -> Unit) {
        if (RootControlState.busy) return
        RootControlState.busy = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { action() }
            catch (e: Exception) { info = e.message ?: "Operation failed"; log(info) }
            finally { RootControlState.busy = false }
        }
    }

    LaunchedEffect(root, cellular, selectedInterface) {
        if (root) {
            while (RootControlState.busy) delay(100)
            task {
                bonding = withContext(Dispatchers.IO) { detectBonding() }.also {
                    desiredBond24 = it.band24
                    desiredBond5 = it.band5
                }
                val kernel = OrigamiNetworkTuning(context.applicationContext, true)
                origamiStates = withContext(NonCancellable + Dispatchers.IO) { kernel.scan(captureDefaults = true) }
                origamiEngine = kernel
                if (selectedInterface == null) {
                    info = (if (cellular) "Enable mobile data to read cellular controls." else "Connect to Wi-Fi to read Wi-Fi controls.") +
                        " Global kernel controls were still checked."
                    return@task
                }
                val next = CustomTuning(context.applicationContext, true, cellular)
                states = withContext(NonCancellable + Dispatchers.IO) { next.scan(captureDefaults = true) }
                engine = next
                info = "Current values checked. Readable original values retained."
            }
        }
    }

    pendingSetting?.let { (tweak, enabled) ->
        val advanced = tweak.tier == SafetyTier.ADVANCED
        AlertDialog(
            onDismissRequest = { pendingSetting = null },
            title = { Text("Set ${tweak.title} ${if (enabled) "On" else "Off"}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tweak.description)
                    Text(tweak.risk, color = if (advanced) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (tweak.persistsAcrossReboot)
                        "Android persists this setting across reboot. Restore writes the exact saved original level when available."
                    else "This is a runtime change. Restore writes the saved original; reboot is the fallback if the driver stops exposing it.")
                    if (advanced) Row {
                        Checkbox(checked = advancedUnderstood, onCheckedChange = { advancedUnderstood = it })
                        Text("I understand this can reduce speed or connection stability.", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !advanced || advancedUnderstood, onClick = {
                    pendingSetting = null
                    task {
                        val currentEngine = checkNotNull(engine) { "Connect the selected network first." }
                        states = withContext(NonCancellable + Dispatchers.IO) { currentEngine.apply(mapOf(tweak.id to enabled)) }
                        info = "${tweak.title}: ${currentEngine.lastReport}"
                        log(info)
                    }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { pendingSetting = null }) { Text("Cancel") } }
        )
    }

    pendingOrigamiSetting?.let { (setting, value) ->
        AlertDialog(
            onDismissRequest = { pendingOrigamiSetting = null },
            title = { Text("Set ${setting.title} to $value?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(setting.description)
                    val valueExplanation = setting.options.find { it.value == value }?.explanation
                        ?: if (setting.dynamicCongestionAlgorithms) congestionAlgorithmExplanation(value)
                        else setting.range?.let { "Selected value: $value ${it.unit}." }
                    valueExplanation?.let { Text(it) }
                    Text(setting.risk, color = MaterialTheme.colorScheme.error)
                    Text("This is a device-wide runtime sysctl. Restore writes the exact saved original when readable; reboot is the fallback.")
                    Text("Kernel path: ${setting.path}", style = MaterialTheme.typography.labelSmall)
                    Row {
                        Checkbox(checked = advancedUnderstood, onCheckedChange = { advancedUnderstood = it })
                        Text("I understand this can affect every app and connection.", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = advancedUnderstood, onClick = {
                    pendingOrigamiSetting = null
                    task {
                        val currentEngine = checkNotNull(origamiEngine) { "Root kernel controls have not been checked yet." }
                        origamiStates = withContext(NonCancellable + Dispatchers.IO) { currentEngine.apply(setting.id, value) }
                        info = "${setting.title}: ${currentEngine.lastReport}"
                        log(info)
                    }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { pendingOrigamiSetting = null }) { Text("Cancel") } }
        )
    }

    bondingAction?.let { action ->
        val restoring = action == "restore"
        AlertDialog(
            onDismissRequest = { bondingAction = null },
            title = { Text(if (restoring) "Restore the Qualcomm file?" else "Directly edit the Qualcomm file?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (restoring) {
                        Text("The app will verify its saved byte-for-byte original, copy it back to ${bonding?.source ?: "the Qualcomm path"}, and verify the restored file.")
                    } else {
                        Text("This is the only reboot-persistent control in WiFi Maxxer. It writes gChannelBondingMode24GHz and gChannelBondingMode5GHz in the real WCNSS_qcom_cfg.ini file.")
                        Text("The complete original file is copied to /data/adb/wifimaxxer/bonding and checksum-verified first. A wrong firmware setting can stop Wi-Fi, and changing a verified partition can stop some devices from booting.")
                    }
                    Text("A reboot is required for the Wi-Fi driver to reload the file. Keep the backup until Wi-Fi has been tested.")
                    Row {
                        Checkbox(checked = bondingUnderstood, onCheckedChange = { bondingUnderstood = it })
                        Text(if (restoring) "Restore the saved original file." else "I accept the boot and Wi-Fi risk.", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = bondingUnderstood, onClick = {
                    bondingAction = null
                    task {
                        val result = withContext(NonCancellable + Dispatchers.IO) {
                            if (restoring) restoreBonding() else applyBonding(
                                desiredBond24.takeIf { bonding?.band24 != null },
                                desiredBond5.takeIf { bonding?.band5 != null }
                            )
                        }
                        bonding = withContext(Dispatchers.IO) { detectBonding() }.also {
                            desiredBond24 = it.band24
                            desiredBond5 = it.band5
                        }
                        info = if (restoring) "Original Qualcomm file restored and verified. Reboot to reload it. Backup retained at ${result.backup}."
                            else "Qualcomm bonding values written and verified. Reboot to apply them. Original saved at ${result.backup}."
                        log(info)
                    }
                }) { Text(if (restoring) "Restore file" else "Edit file") }
            },
            dismissButton = { TextButton(onClick = { bondingAction = null }) { Text("Cancel") } }
        )
    }

    fun request(tweak: Tweak, enabled: Boolean) {
        advancedUnderstood = false
        pendingSetting = tweak to enabled
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Basic & advanced tuning", style = MaterialTheme.typography.headlineSmall)
        Text("Controls apply one at a time. Current-value detection is informational and never hides an action.")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Mobile data target", style = MaterialTheme.typography.titleMedium)
                        Text(if (cellular) "Cellular interface selected" else "Wi-Fi interface selected", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = cellular, enabled = !blocked && !RootControlState.customChanged, onCheckedChange = {
                        cellular = it
                        settingsPrefs.edit().putBoolean("mobile-data-mode", it).apply()
                        states = emptyMap()
                        engine = null
                        info = if (it) "Waiting for an active cellular interface…" else "Waiting for an active Wi-Fi interface…"
                    })
                }
                Text("Interface offloads and CPU steering use the selected Wi-Fi or cellular interface. TCP controls are kernel-global. Power saving and Qualcomm bonding are Wi-Fi-only; Android reachability, RSSI, and logging controls belong to the Wi-Fi framework.", style = MaterialTheme.typography.bodySmall)
                if (RootControlState.customChanged) Text("Restore active runtime settings before changing the network target.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(selectedInterface?.let { "Selected interface: $it" } ?: if (cellular) "No active cellular interface detected." else "No active Wi-Fi interface detected.",
                    color = if (selectedInterface == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        if (!root) Text("Root unavailable. Tuning controls require root.")
        if (RootControlState.active) Text("Restore the separate WLL control before changing these settings.")
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(info, color = MaterialTheme.colorScheme.secondary)
        OutlinedButton(enabled = !blocked && (engine != null || origamiEngine != null), onClick = {
            task {
                val failures = mutableListOf<String>()
                engine?.let { current ->
                    try { states = withContext(NonCancellable + Dispatchers.IO) { current.restore() } }
                    catch (error: Exception) { failures += "interface controls: ${error.message}" }
                }
                origamiEngine?.let { current ->
                    try { origamiStates = withContext(NonCancellable + Dispatchers.IO) { current.restore() } }
                    catch (error: Exception) { failures += "advanced network sysctls: ${error.message}" }
                }
                check(failures.isEmpty()) { "Restore incomplete. ${failures.joinToString(" · ")}" }
                info = "Saved runtime values restored and verified."
                log(info)
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Restore all runtime defaults") }

        Text("BASIC", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("Low-risk, standard runtime controls. They still have battery or connectivity trade-offs.", style = MaterialTheme.typography.bodySmall)
        tweaks.filter { it.tier == SafetyTier.BASIC }.forEach { tweak ->
            TweakCard(tweak, states[tweak.id], root && !blocked && engine != null, ::request)
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text("Advanced controls can reduce throughput, wake extra CPU cores, break VPN or tethering paths, affect every TCP connection, or make Wi-Fi unstable. Change one setting at a time and use Restore before testing another.",
                modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
        Text("ADVANCED", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
        tweaks.filter { it.tier == SafetyTier.ADVANCED }.forEach { tweak ->
            TweakCard(tweak, states[tweak.id], root && !blocked && engine != null, ::request)
        }

        Text("ADVANCED NETWORK SYSCTLS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
        Text("These controls are kernel-global, runtime-only, and remain visible when the current ROM does not expose a path.", style = MaterialTheme.typography.bodySmall)
        origamiNetworkSettings.forEach { setting ->
            OrigamiNetworkCard(setting, origamiStates[setting.id], root && !blocked && origamiEngine != null) { chosen ->
                advancedUnderstood = false
                pendingOrigamiSetting = setting to chosen
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Qualcomm channel bonding", style = MaterialTheme.typography.titleLarge)
                Text("HIGH RISK · PERSISTENT", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                Text("The legacy Qualcomm WCNSS keys request wider channels. They do not combine simultaneous 2.4 GHz and 5 GHz connections. Modern Wi-Fi 7 MLO is controlled by Android and vendor firmware instead.", style = MaterialTheme.typography.bodySmall)
                fun label(current: Boolean?, desired: Boolean?) = when {
                    current == null -> "Key unavailable"
                    desired == current -> if (current) "Enabled in file" else "Disabled in file"
                    desired == true -> "Will be enabled"
                    else -> "Will be disabled"
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text("2.4 GHz"); Text("${label(bonding?.band24, desiredBond24)} · Recommended: OEM", style = MaterialTheme.typography.bodySmall) }
                    Switch(checked = desiredBond24 ?: false, onCheckedChange = { desiredBond24 = it }, enabled = root && !blocked && bonding?.editable == true && bonding?.band24 != null)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text("5 GHz"); Text("${label(bonding?.band5, desiredBond5)} · Recommended: OEM", style = MaterialTheme.typography.bodySmall) }
                    Switch(checked = desiredBond5 ?: false, onCheckedChange = { desiredBond5 = it }, enabled = root && !blocked && bonding?.editable == true && bonding?.band5 != null)
                }
                Text(bonding?.detail ?: if (root) "Checking the Qualcomm configuration…" else "Root is required for bonding control.", style = MaterialTheme.typography.bodySmall)
                bonding?.source?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                bonding?.backup?.let { Text("Original backup: $it", style = MaterialTheme.typography.labelSmall) }
                Text("Direct vendor/ODM/system modification persists across reboot and can affect verified boot. The app blocks ambiguous files and verifies its backup before writing.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                val bondingChanged = (bonding?.band24 != null && desiredBond24 != bonding?.band24) || (bonding?.band5 != null && desiredBond5 != bonding?.band5)
                Button(enabled = root && !blocked && bonding?.editable == true && bondingChanged, onClick = {
                    bondingUnderstood = false
                    bondingAction = "apply"
                }, modifier = Modifier.fillMaxWidth()) { Text("Save backup and edit file") }
                OutlinedButton(enabled = root && !blocked && bonding?.backup != null && bonding?.modifiedSinceBackup == true, onClick = {
                    bondingUnderstood = false
                    bondingAction = "restore"
                }, modifier = Modifier.fillMaxWidth()) { Text("Restore original Qualcomm file") }
            }
        }
    }
}

@Composable
private fun TweakCard(tweak: Tweak, state: TweakState?, enabled: Boolean, request: (Tweak, Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tweak.title, style = MaterialTheme.typography.titleMedium)
            Text(if (tweak.tier == SafetyTier.BASIC) "LOW RISK" else if (tweak.persistsAcrossReboot) "USE CAUTION · PERSISTENT" else "USE CAUTION",
                color = if (tweak.tier == SafetyTier.BASIC) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium)
            Text(tweak.description)
            Text("Expected effect: ${tweak.impact}", style = MaterialTheme.typography.bodySmall)
            Text("Risk: ${tweak.risk}", style = MaterialTheme.typography.bodySmall,
                color = if (tweak.tier == SafetyTier.ADVANCED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            val recommendation = when (tweak.recommended) { true -> "On"; false -> "Off"; null -> "OEM" }
            Text("Current: ${state?.value?.let { if (it) "On" else "Off" } ?: "Unknown"} · Original: ${state?.original?.let { if (it) "On" else "Off" } ?: "Not saved"} · Recommended: $recommendation", style = MaterialTheme.typography.bodySmall)
            Text(state?.reason ?: "Not checked yet", style = MaterialTheme.typography.bodySmall)
            Text("Source: ${tweak.source}", style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { request(tweak, true) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Set On") }
                OutlinedButton(onClick = { request(tweak, false) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Set Off") }
            }
        }
    }
}

@Composable
private fun OrigamiNetworkCard(
    setting: OrigamiNetworkSetting,
    state: OrigamiNetworkState?,
    enabled: Boolean,
    request: (String) -> Unit
) {
    val initial = state?.current?.takeIf { origamiValueError(setting, it, state.available) == null }
        ?: setting.recommended
        ?: state?.available?.firstOrNull()
        ?: setting.options.firstOrNull()?.value
        ?: ""
    var desired by remember(setting.id, state?.current, state?.available) { mutableStateOf(initial) }
    val error = origamiValueError(setting, desired, state?.available.orEmpty())
    val recommended = setting.recommended?.let { value -> setting.options.find { it.value == value }?.label ?: value } ?: "OEM"

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(setting.title, style = MaterialTheme.typography.titleMedium)
            Text("USE CAUTION · KERNEL-GLOBAL", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            Text(setting.description)
            Text("Expected effect: ${setting.effect}", style = MaterialTheme.typography.bodySmall)
            Text("Risk: ${setting.risk}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Text("Current: ${state?.current ?: "Unknown"} · Original: ${state?.original ?: "Not saved"} · Recommended: $recommended",
                style = MaterialTheme.typography.bodySmall)
            Text(state?.reason ?: "Not checked yet", style = MaterialTheme.typography.bodySmall)

            setting.range?.let { range ->
                OutlinedTextField(
                    value = desired,
                    onValueChange = { candidate -> desired = candidate.filter(Char::isDigit).take(5) },
                    label = { Text("${range.min}–${range.max} ${range.unit}") },
                    supportingText = { Text(error ?: "Whole-number increments of ${range.step}.") },
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            val choices = if (setting.dynamicCongestionAlgorithms) state?.available.orEmpty().map {
                KernelValueOption(it, it, congestionAlgorithmExplanation(it))
            } else setting.options
            if (setting.dynamicCongestionAlgorithms && choices.isEmpty()) {
                OutlinedTextField(
                    value = desired,
                    onValueChange = { desired = it.filter { char -> char.isLetterOrDigit() || char == '_' || char == '-' }.take(32) },
                    label = { Text("Kernel algorithm name") },
                    supportingText = { Text(error ?: "The kernel did not report an available-algorithm list; a rejected write will be shown.") },
                    isError = error != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else choices.forEach { option ->
                Row(Modifier.fillMaxWidth()) {
                    RadioButton(selected = desired == option.value, onClick = { desired = option.value }, enabled = enabled)
                    Column(Modifier.weight(1f).padding(top = 10.dp)) {
                        Text(option.label, style = MaterialTheme.typography.labelLarge)
                        Text(option.explanation, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text("Reference: Linux kernel sysctl documentation", style = MaterialTheme.typography.labelSmall)
            Text("Kernel path: ${setting.path}", style = MaterialTheme.typography.labelSmall)
            Button(onClick = { request(desired) }, enabled = enabled && error == null, modifier = Modifier.fillMaxWidth()) {
                Text("Apply value ${desired.ifEmpty { "…" }}")
            }
        }
    }
}
