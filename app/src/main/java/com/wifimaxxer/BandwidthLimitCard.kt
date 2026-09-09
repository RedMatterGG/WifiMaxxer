package com.wifimaxxer

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun BandwidthLimitCard(
    rootReady: Boolean,
    target: BandwidthTarget,
    iface: String?,
    reportedDownloadMax: Int?,
    reportedUploadMax: Int?,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current.applicationContext
    val settings = remember(target) { context.getSharedPreferences("bandwidth-limit-ui-${target.name.lowercase()}", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var applied by remember(target) { mutableStateOf(BandwidthLimiter.load(context, target)) }
    var busy by remember { mutableStateOf(false) }
    var downloadEnabled by remember { mutableStateOf(applied.downloadMbps != null) }
    var uploadEnabled by remember { mutableStateOf(applied.uploadMbps != null) }
    var download by remember { mutableIntStateOf(settings.getInt("download", applied.downloadMbps ?: 0)) }
    var upload by remember { mutableIntStateOf(settings.getInt("upload", applied.uploadMbps ?: 0)) }

    val downloadMaximum = reportedDownloadMax?.coerceAtLeast(BandwidthLimiter.MIN_DOWNLOAD_MBPS)
    val uploadMaximum = reportedUploadMax?.coerceAtLeast(BandwidthLimiter.MIN_UPLOAD_MBPS)
    LaunchedEffect(downloadMaximum) {
        downloadMaximum?.let { maximum ->
            download = if (download <= 0) recommendedLimit(BandwidthLimiter.MIN_DOWNLOAD_MBPS, maximum)
                else download.coerceIn(BandwidthLimiter.MIN_DOWNLOAD_MBPS, maximum)
        }
    }
    LaunchedEffect(uploadMaximum) {
        uploadMaximum?.let { maximum ->
            upload = if (upload <= 0) recommendedLimit(BandwidthLimiter.MIN_UPLOAD_MBPS, maximum)
                else upload.coerceIn(BandwidthLimiter.MIN_UPLOAD_MBPS, maximum)
        }
    }

    val mobile = target == BandwidthTarget.CELLULAR
    Panel(if (mobile) "MOBILE DATA BANDWIDTH LIMITS" else "WI-FI BANDWIDTH LIMITS") {
        Text(if (mobile)
            "Limit only the active cellular interface. Android's estimated first-hop mobile bandwidth sets each slider ceiling and can change as the modem changes network or signal."
        else "Limit only the active Wi-Fi interface. Android's reported Wi-Fi link maxima set the slider ceilings; they are radio rates, not measured internet throughput.")
        Text("Leave headroom for calls and games while downloads or uploads run in the background.")
        if (applied.active && applied.iface != iface) Text("Limits are active on ${applied.iface}. Reapply after this ${target.displayName.lowercase()} interface change.", color = MaterialTheme.colorScheme.error)
        LimitSlider(
            title = "Download limit",
            enabled = downloadEnabled,
            value = download.coerceAtLeast(BandwidthLimiter.MIN_DOWNLOAD_MBPS),
            minimum = BandwidthLimiter.MIN_DOWNLOAD_MBPS,
            maximum = downloadMaximum,
            activeValue = applied.downloadMbps,
            direction = "RX",
            networkName = target.displayName,
            onEnabledChange = { downloadEnabled = it },
            onValueChange = {
                download = it
                settings.edit().putInt("download", it).apply()
            }
        )
        LimitSlider(
            title = "Upload limit",
            enabled = uploadEnabled,
            value = upload.coerceAtLeast(BandwidthLimiter.MIN_UPLOAD_MBPS),
            minimum = BandwidthLimiter.MIN_UPLOAD_MBPS,
            maximum = uploadMaximum,
            activeValue = applied.uploadMbps,
            direction = "TX",
            networkName = target.displayName,
            onEnabledChange = { uploadEnabled = it },
            onValueChange = {
                upload = it
                settings.edit().putInt("upload", it).apply()
            }
        )
        Text("Start near 85–90% of measured internet throughput, then test latency under load. Phone-side limiting drops excess packets." +
            if (mobile) " Carrier-side queues remain outside the phone's control." else " Router SQM can control bufferbloat more effectively.", style = MaterialTheme.typography.bodySmall)
        Text("Minimums are 5 Mbps down and 1 Mbps up, the FCC baseline for 4G LTE coverage. These ${target.displayName.lowercase()} limits last until Restore, reboot, or interface reset.", style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(
            onClick = {
                val selectedInterface = iface ?: return@Button
                scope.launch {
                    busy = true
                    try {
                        applied = withContext(Dispatchers.IO) {
                            BandwidthLimiter.apply(context, target, selectedInterface,
                                download.takeIf { downloadEnabled }, upload.takeIf { uploadEnabled })
                        }
                        onMessage(buildString {
                            append("${target.displayName} limits applied to $selectedInterface: ")
                            append(applied.downloadMbps?.let { "download $it Mbps" } ?: "download unrestricted")
                            append(", ")
                            append(applied.uploadMbps?.let { "upload $it Mbps" } ?: "upload unrestricted")
                            append('.')
                        })
                    } catch (e: Exception) {
                        applied = BandwidthLimitState()
                        onMessage(e.message ?: "Could not apply ${target.displayName.lowercase()} bandwidth limits.")
                    } finally { busy = false }
                }
            },
            enabled = rootReady && !busy && iface != null &&
                (!downloadEnabled || downloadMaximum != null) && (!uploadEnabled || uploadMaximum != null),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Apply bandwidth limits") }
        OutlinedButton(
            onClick = {
                scope.launch {
                    busy = true
                    try {
                        applied = withContext(Dispatchers.IO) { BandwidthLimiter.restore(context, target) }
                        downloadEnabled = false
                        uploadEnabled = false
                        onMessage("${target.displayName} bandwidth limits removed.")
                    } catch (e: Exception) { onMessage(e.message ?: "Could not remove ${target.displayName.lowercase()} bandwidth limits.") }
                    finally { busy = false }
                }
            },
            enabled = rootReady && !busy && applied.active,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Restore unrestricted bandwidth") }
    }
}

@Composable
private fun LimitSlider(
    title: String,
    enabled: Boolean,
    value: Int,
    minimum: Int,
    maximum: Int?,
    activeValue: Int?,
    direction: String,
    networkName: String,
    onEnabledChange: (Boolean) -> Unit,
    onValueChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        if (maximum == null) {
            Text("Connect to $networkName to read Android's reported $direction bandwidth.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("$value Mbps" + if (activeValue != null) " · active: $activeValue Mbps" else "")
            Slider(
                value = limitToSlider(value, minimum, maximum),
                onValueChange = { onValueChange(sliderToLimit(it, minimum, maximum)) },
                valueRange = 0f..1f,
                enabled = enabled && maximum > minimum
            )
            Text("Range $minimum–$maximum Mbps · reported $direction maximum", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun recommendedLimit(minimum: Int, maximum: Int) = (maximum * 0.88).roundToInt().coerceIn(minimum, maximum)
