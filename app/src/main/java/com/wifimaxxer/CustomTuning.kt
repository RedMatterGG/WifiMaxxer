package com.wifimaxxer

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.util.Locale

enum class SafetyTier { BASIC, ADVANCED }

data class Tweak(
    val id: String,
    val title: String,
    val description: String,
    val impact: String,
    val risk: String,
    val source: String,
    val tier: SafetyTier,
    val recommended: Boolean?,
    val feature: String? = null,
    val sysctl: String? = null,
    val persistsAcrossReboot: Boolean = false
)

val tweaks = listOf(
    Tweak("ipreach", "Wi-Fi reachability recovery", "Disconnect and recover when Android detects that the gateway can no longer be reached.",
        "Helps escape a connected-but-dead Wi-Fi link.", "Keeping this On is low risk. Off can leave traffic stuck on an unusable access point.",
        "Android Wi-Fi framework", SafetyTier.BASIC, true),
    Tweak("nopowersave", "Disable Wi-Fi power saving", "Keeps the Wi-Fi radio awake between packets instead of using normal 802.11 power save.",
        "Can reduce packet wake-up delay.", "Runtime-only and normally reversible, but On can use much more battery and can reduce throughput on some firmware.",
        "Android nl80211 / iw", SafetyTier.BASIC, false),
    Tweak("tcp_rcvbuf_auto", "TCP receive-buffer autotuning", "Lets Linux grow each TCP receive buffer within the kernel's existing limits to match the connection path.",
        "Usually improves throughput on fast or high-latency links without reserving the maximum buffer for every connection.",
        "Kernel-global. Keeping it On is the Linux default and low risk; Off can severely limit download throughput.",
        "Linux TCP sysctl", SafetyTier.BASIC, true, sysctl = "/proc/sys/net/ipv4/tcp_moderate_rcvbuf"),
    Tweak("rx", "RX checksum offload", "Lets the Wi-Fi driver report transport checksums it verified instead of repeating that work in the CPU.",
        "Usually lowers receive-side CPU work.", "A buggy driver or VPN/tethering path can mishandle changed offloads and disrupt traffic.",
        "Linux ethtool", SafetyTier.ADVANCED, true, feature = "rx-checksumming"),
    Tweak("tx", "TX checksum offload", "Lets the networking device complete supported transport checksums for outgoing packets.",
        "Usually lowers transmit-side CPU work.", "Other segmentation offloads can depend on it; a driver can reject or mishandle a change.",
        "Linux ethtool", SafetyTier.ADVANCED, true, feature = "tx-checksum-ip-generic"),
    Tweak("tso", "TCP segmentation offload", "Passes larger TCP buffers to the device so it can split them to the interface MTU.",
        "Can improve bulk TCP throughput and reduce CPU use.", "May interact badly with VPNs, tethering, encapsulation, or vendor drivers.",
        "Linux ethtool", SafetyTier.ADVANCED, true, feature = "tx-tcp-segmentation"),
    Tweak("gso", "Generic segmentation offload", "Lets the kernel delay packet segmentation and process larger buffers.",
        "Can improve throughput and reduce per-packet CPU work.", "Can expose driver or tunnel-path bugs and may change latency under load.",
        "Linux networking stack", SafetyTier.ADVANCED, true, feature = "generic-segmentation-offload"),
    Tweak("gro", "Generic receive offload", "Combines compatible received packets before higher network layers process them.",
        "Can improve receive throughput and reduce CPU work.", "Packet coalescing can add latency and can conflict with some forwarding or capture paths.",
        "Linux networking stack", SafetyTier.ADVANCED, true, feature = "generic-receive-offload"),
    Tweak("hwgro", "Hardware GRO", "Allows supported receive coalescing to occur in Wi-Fi hardware or its driver.",
        "Can reduce CPU use during heavy downloads.", "Vendor-specific behavior makes this less portable than software GRO.",
        "Linux ethtool / vendor driver", SafetyTier.ADVANCED, true, feature = "rx-gro-hw"),
    Tweak("tcp_autocork", "TCP auto-corking", "Coalesces consecutive small application writes while a prior packet is queued.",
        "Reduces packet count, but can trade a little latency for batching.", "Kernel-global: it affects new TCP traffic on Wi-Fi and mobile data.",
        "Linux TCP sysctl", SafetyTier.ADVANCED, true, sysctl = "/proc/sys/net/ipv4/tcp_autocorking"),
    Tweak("tcp_metrics", "Disable saved TCP metrics", "Stops Linux from reusing cached route metrics as initial conditions for later TCP connections.",
        "Can help only when stale metrics hurt repeated connections; usually caching is faster.", "Kernel-global and workload-dependent; On can slow reconnects.",
        "Linux TCP sysctl", SafetyTier.ADVANCED, false, sysctl = "/proc/sys/net/ipv4/tcp_no_metrics_save"),
    Tweak("tcp_idle", "TCP slow start after idle", "Times out the congestion window after an idle period as specified by RFC 2861 behavior.",
        "On is safer when path conditions change; Off can resume bursts faster.", "Kernel-global. Off can create a burst and loss after an idle connection resumes.",
        "Linux TCP sysctl", SafetyTier.ADVANCED, true, sysctl = "/proc/sys/net/ipv4/tcp_slow_start_after_idle"),
    Tweak("rps", "Balanced receive CPU steering (RPS)", "On distributes receive protocol processing across online CPUs; Off disables software receive steering.",
        "Can reduce a receive-CPU bottleneck on single-queue or lightly queued network drivers.",
        "May add cross-core interrupts, wake high-power cores, or duplicate hardware RSS. The best map depends on queue count, IRQ placement, cache topology, and vendor power policy.",
        "Linux network queue sysfs", SafetyTier.ADVANCED, null),
    Tweak("xps", "Balanced transmit CPU steering (XPS)", "On assigns online CPUs evenly across transmit queues; Off removes CPU-to-transmit-queue maps.",
        "Can reduce transmit queue-lock contention on multi-queue interfaces.",
        "Has no benefit on a single transmit queue and a generic map can be worse than the OEM's cache- and IRQ-aware layout.",
        "Linux network queue sysfs", SafetyTier.ADVANCED, null),
    Tweak("wifi_verbose", "Wi-Fi verbose logging", "Enables Android Wi-Fi framework verbose logging at standard level 1. Off disables it.",
        "Adds framework detail for bug reports and Wi-Fi troubleshooting; it does not improve network performance.",
        "Android persists this setting across reboot. Extra logs can reduce performance and expose sensitive connection details in privileged logs, so keep it Off outside troubleshooting.",
        "Android Wi-Fi framework", SafetyTier.ADVANCED, false, persistsAcrossReboot = true),
    Tweak("rssi_fast", "1-second RSSI polling", "Requests Android to poll Wi-Fi link statistics every 1000 ms instead of the saved OEM interval.",
        "May let framework scoring observe a changing link sooner; it does not directly lower packet latency.", "More polling can increase CPU and battery use. Off restores the exact saved OEM interval.",
        "Android Wi-Fi framework", SafetyTier.ADVANCED, false)
)

data class TweakState(val value: Boolean?, val original: Boolean?, val reason: String, val writable: Boolean = false)
data class Offload(val enabled: Boolean, val fixed: Boolean)
data class WifiVerboseLogging(val level: Int, val exact: Boolean)

fun parseOffloads(text: String): Map<String, Offload> = Regex("(?m)^\\s*([a-z0-9-]+): (on|off)([^\\r\\n]*)$").findAll(text)
    .associate { it.groupValues[1] to Offload(it.groupValues[2] == "on", it.groupValues[3].contains("[fixed]") || it.groupValues[3].contains("[requested")) }

fun parseIpReachability(text: String): Boolean? = Regex("(?i)IPREACH_DISCONNECT state is (true|false)")
    .find(text)?.groupValues?.get(1)?.lowercase()?.toBooleanStrictOrNull()

fun parseRssiPollInterval(text: String): Int? = Regex("(?im)^.*(?:PollRssi|RSSI polls).*?=\\s*(\\d+)\\s*$")
    .find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

fun parseWifiVerboseLogging(text: String): WifiVerboseLogging? {
    Regex("(?i)mVerboseLoggingLevel\\s*[=:]\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        ?.let { return WifiVerboseLogging(it, true) }
    return Regex("(?im)^(?:WM_VERBOSE=)?(enabled|disabled)\\s*$").find(text)?.groupValues?.get(1)?.lowercase()?.let {
        WifiVerboseLogging(if (it == "enabled") 1 else 0, it == "disabled")
    }
}

fun parseCpuList(text: String): List<Int> = text.trim().split(',').flatMap { part ->
    val bounds = part.trim().split('-')
    when (bounds.size) {
        1 -> bounds[0].toIntOrNull()?.takeIf { it in 0..4095 }?.let(::listOf).orEmpty()
        2 -> {
            val first = bounds[0].toIntOrNull()
            val last = bounds[1].toIntOrNull()
            if (first != null && last != null && first in 0..4095 && last in first..4095) (first..last).toList() else emptyList()
        }
        else -> emptyList()
    }
}.distinct().sorted()

fun cpuMask(cpus: Collection<Int>): String {
    if (cpus.isEmpty()) return "0"
    require(cpus.all { it in 0..4095 })
    val chunks = MutableList(cpus.maxOrNull()!! / 32 + 1) { 0L }
    cpus.forEach { cpu -> chunks[cpu / 32] = chunks[cpu / 32] or (1L shl (cpu % 32)) }
    return chunks.asReversed().mapIndexed { index, value ->
        value.toString(16).padStart(8, '0').let { if (index == 0) it.trimStart('0').ifEmpty { "0" } else it }
    }.joinToString(",")
}

fun normalizeCpuMask(mask: String): String {
    val chunks = mask.lowercase(Locale.US).split(',').map { it.trimStart('0').ifEmpty { "0" } }.dropWhile { it == "0" }
    if (chunks.isEmpty()) return "0"
    return chunks.mapIndexed { index, chunk -> if (index == 0) chunk else chunk.padStart(8, '0') }.joinToString(",")
}

fun balancedCpuMasks(queues: Collection<String>, cpus: List<Int>): Map<String, String> {
    val sortedQueues = queues.sortedBy { Regex("(\\d+)$").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }
    if (sortedQueues.isEmpty() || cpus.isEmpty()) return emptyMap()
    val assignments = sortedQueues.associateWith { mutableListOf<Int>() }
    cpus.forEachIndexed { index, cpu -> assignments.getValue(sortedQueues[index % sortedQueues.size]).add(cpu) }
    return assignments.mapValues { cpuMask(it.value) }
}

fun parseCpuSteering(text: String, type: String): Map<String, String> {
    require(type in setOf("rps", "xps"))
    return Regex("(?m)^WM_STEER_${type}=((?:rx|tx)-\\d+):([0-9a-fA-F,]+)$").findAll(text)
        .associate { it.groupValues[1] to normalizeCpuMask(it.groupValues[2]) }
}

fun expireCustomSessionAfterReboot(context: Context) {
    val boot = RootToggle.boot(context)
    listOf("custom-baseline", "custom-baseline-cellular", "origami-network-baseline").forEach { name ->
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        if (prefs.getInt("observedBoot", -1) != boot) {
            // Android persists verbose Wi-Fi logging; retain only that recovery entry after reboot.
            val retained = prefs.getStringSet("dirty", emptySet()).orEmpty().filterTo(mutableSetOf()) { name == "custom-baseline" && it == "wifi_verbose" }
            prefs.edit().remove("latencyLast").putStringSet("dirty", retained).putInt("observedBoot", boot).commit()
        }
    }
    refreshTuningDirtyState(context)
}

fun refreshTuningDirtyState(context: Context) {
    RootControlState.customChanged = listOf("custom-baseline", "custom-baseline-cellular", "origami-network-baseline").any { name ->
        context.getSharedPreferences(name, Context.MODE_PRIVATE).getStringSet("dirty", emptySet()).orEmpty().isNotEmpty()
    }
}

class CustomTuning(private val context: Context, private val root: Boolean, private val cellular: Boolean = false) {
    private val prefs = context.getSharedPreferences(if (cellular) "custom-baseline-cellular" else "custom-baseline", Context.MODE_PRIVATE)
    private val tcpPrefs = context.getSharedPreferences("tcp-baseline", Context.MODE_PRIVATE)
    private val frameworkPrefs = context.getSharedPreferences("wifi-framework-baseline", Context.MODE_PRIVATE)
    private var iface = ""
    private var initial = JSONObject()
    private var tcpInitial = JSONObject()
    private var currentVerboseLevel: Int? = null
    private var currentOnlineCpus: List<Int> = emptyList()
    private var currentRpsMasks: Map<String, String> = emptyMap()
    private var currentXpsMasks: Map<String, String> = emptyMap()
    var lastReport: String = ""
        private set
    private fun run(command: String): RootResult = if (root) RootAccess.run(command) else RootResult(1, "This setting requires root.")
    private fun steeringKey(id: String) = "${id}Masks"
    private fun savedMasks(id: String): Map<String, String> {
        val json = initial.optJSONObject(steeringKey(id)) ?: return emptyMap()
        return json.keys().asSequence().associateWith { json.getString(it) }
    }
    private fun saveMasks(id: String, masks: Map<String, String>) {
        val json = JSONObject()
        masks.toSortedMap().forEach { (queue, mask) -> json.put(queue, mask) }
        initial.put(steeringKey(id), json)
    }
    private fun readSteering(id: String): Map<String, String> {
        val (queuePattern, attribute) = when (id) {
            "rps" -> "rx-*" to "rps_cpus"
            "xps" -> "tx-*" to "xps_cpus"
            else -> error("Unknown steering type: $id")
        }
        val output = run("""
            for file in /sys/class/net/$iface/queues/$queuePattern/$attribute; do
                [ -r "${'$'}file" ] || continue
                queue=${'$'}{file%/*}; queue=${'$'}{queue##*/}
                value=${'$'}(/system/bin/cat "${'$'}file" 2>/dev/null) || continue
                case "${'$'}value" in ''|*[!0-9a-fA-F,]*) continue;; esac
                printf 'WM_STEER_$id=%s:%s\n' "${'$'}queue" "${'$'}value"
            done
        """.trimIndent()).output
        return parseCpuSteering(output, id)
    }
    private fun steeringSummary(masks: Map<String, String>) = masks.toSortedMap().entries.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { "unavailable" }
    fun pending() = prefs.getStringSet("dirty", emptySet()).orEmpty().isNotEmpty()
    private fun mark(ids: Set<String>) {
        val localIds = if (cellular) ids - "wifi_verbose" else ids
        check(prefs.edit().putStringSet("dirty", localIds).commit()) { "Cannot save recovery journal." }
        if (cellular) {
            // Wi-Fi logging is framework-global and persistent, regardless of the selected interface target.
            val wifiPrefs = context.getSharedPreferences("custom-baseline", Context.MODE_PRIVATE)
            val wifiDirty = wifiPrefs.getStringSet("dirty", emptySet()).orEmpty().toMutableSet()
            if ("wifi_verbose" in ids) wifiDirty.add("wifi_verbose") else wifiDirty.remove("wifi_verbose")
            check(wifiPrefs.edit().putStringSet("dirty", wifiDirty).commit()) { "Cannot save Wi-Fi logging recovery journal." }
        }
        refreshTuningDirtyState(context)
    }

    fun scan(captureDefaults: Boolean = false): Map<String, TweakState> {
        val detectedInterface = requireNotNull(networkTarget(context, cellular)?.iface) {
            if (cellular) "Enable mobile data before scanning cellular controls." else "Connect to Wi-Fi before scanning controls."
        }
        require(Regex("[a-zA-Z0-9_.-]{1,32}").matches(detectedInterface)) { "Unexpected Wi-Fi interface name." }
        val savedInterface = prefs.getString("interface", null)
        check(savedInterface == null || savedInterface == detectedInterface) { "Saved defaults belong to $savedInterface, not $detectedInterface." }
        check(!prefs.getBoolean("captured", false) || prefs.getInt("schema", 0) == 2) {
            "The old baseline is not trusted by this safety build. Reinstall or clear app storage after reboot."
        }
        val savedFingerprint = prefs.getString("fingerprint", null)
        check(savedFingerprint == null || savedFingerprint == Build.FINGERPRINT) {
            "The ROM changed since defaults were captured. Reinstall or clear app storage after reboot."
        }
        expireCustomSessionAfterReboot(context)
        iface = detectedInterface
        refreshTuningDirtyState(context)
        initial = JSONObject(prefs.getString("values", "{}")!!)
        var initialUpdated = false
        check(!tcpPrefs.getBoolean("captured", false) || tcpPrefs.getString("fingerprint", null) == Build.FINGERPRINT) {
            "The ROM changed since TCP defaults were captured. Reinstall or clear app storage after reboot."
        }
        check(!frameworkPrefs.getBoolean("captured", false) || frameworkPrefs.getString("fingerprint", null) == Build.FINGERPRINT) {
            "The ROM changed since Wi-Fi framework defaults were captured. Reinstall or clear app storage after reboot."
        }
        tcpInitial = JSONObject(tcpPrefs.getString("values", "{}")!!)
        check(prefs.getInt("schema", 0) == 2 || !prefs.contains("values")) {
            "The old baseline is not trusted by this safety build. Reinstall or clear app storage after reboot."
        }
        val capturing = captureDefaults && !prefs.getBoolean("captured", false)
        // An upgrade retains its existing snapshot, including any pending recovery values.
        val fresh = capturing && initial.length() == 0
        check(!fresh || (!RootControlState.active && !WllService.pending(context) && !pending())) {
            "Restore active runtime controls before first-run defaults can be captured."
        }
        val offloadResult = if (root) run("/system/bin/ethtool -k $iface") else RootResult(1, "Driver controls require root.")
        val offloads = if (offloadResult.code == 0) parseOffloads(offloadResult.output) else emptyMap()
        val sysctlCommand = tweaks.filter { it.sysctl != null }.joinToString("\n") { tweak ->
            "if [ -r '${tweak.sysctl}' ]; then value=\$(/system/bin/cat '${tweak.sysctl}' 2>/dev/null); case \"\$value\" in 0|1) [ -w '${tweak.sysctl}' ] && writable=1 || writable=0; printf 'WM_SYSCTL_${tweak.id}=%s:%s\\n' \"\$value\" \"\$writable\";; esac; fi"
        }
        val sysctlResult = if (root) run(sysctlCommand) else RootResult(1, "Root required")
        val sysctls = Regex("(?m)^WM_SYSCTL_([a-z0-9_]+)=([01]):([01])$").findAll(sysctlResult.output)
            .associate { it.groupValues[1] to ((it.groupValues[2] == "1") to (it.groupValues[3] == "1")) }
        if (captureDefaults) {
            var tcpUpdated = false
            tweaks.filter { it.sysctl != null && !tcpInitial.has(it.id) }.forEach { tweak ->
                sysctls[tweak.id]?.first?.let { tcpInitial.put(tweak.id, it); tcpUpdated = true }
            }
            if (tcpUpdated) check(tcpPrefs.edit().putString("values", tcpInitial.toString())
                .putString("fingerprint", Build.FINGERPRINT).putBoolean("captured", true).commit()) { "Cannot save first-run TCP defaults." }
        }
        val power = if (root && !cellular) run("/system/bin/iw dev $iface get power_save") else RootResult(1, "Wi-Fi only")
        val powerValue = if (power.code == 0) Regex("(?i)Power save: (on|off)").find(power.output)?.groupValues?.get(1)?.let { it == "off" } else null
        val ipReachValue = if (root) parseIpReachability(run("/system/bin/cmd wifi get-ipreach-disconnect").output) else null
        val rssiPollValue = if (root) parseRssiPollInterval(run("/system/bin/cmd wifi get-poll-rssi-interval-msecs").output) else null
        val verboseLogging = if (root) parseWifiVerboseLogging(run("printf 'WM_VERBOSE='; /system/bin/cmd wifi is-verbose-logging; /system/bin/dumpsys wifi | /system/bin/grep -m 1 mVerboseLoggingLevel").output) else null
        currentVerboseLevel = verboseLogging?.level
        currentOnlineCpus = if (root) parseCpuList(run("/system/bin/cat /sys/devices/system/cpu/online").output) else emptyList()
        currentRpsMasks = if (root) readSteering("rps") else emptyMap()
        currentXpsMasks = if (root) readSteering("xps") else emptyMap()
        if (captureDefaults) {
            if (!initial.has(steeringKey("rps")) && currentRpsMasks.isNotEmpty()) { saveMasks("rps", currentRpsMasks); initialUpdated = true }
            if (!initial.has(steeringKey("xps")) && currentXpsMasks.isNotEmpty()) { saveMasks("xps", currentXpsMasks); initialUpdated = true }
        }
        val frameworkValueMissing = (!frameworkPrefs.contains("ipreach") && ipReachValue != null) ||
            (!frameworkPrefs.contains("rssiPollMs") && rssiPollValue != null) ||
            (!frameworkPrefs.contains("verboseLoggingLevel") && verboseLogging != null)
        if (captureDefaults && frameworkValueMissing) {
            val edit = frameworkPrefs.edit().putString("fingerprint", Build.FINGERPRINT).putBoolean("captured", true)
            if (!frameworkPrefs.contains("ipreach")) ipReachValue?.let { edit.putBoolean("ipreach", it) }
            if (!frameworkPrefs.contains("rssiPollMs")) rssiPollValue?.let { edit.putInt("rssiPollMs", it) }
            if (!frameworkPrefs.contains("verboseLoggingLevel")) verboseLogging?.let { edit.putInt("verboseLoggingLevel", it.level) }
            check(edit.commit()) { "Cannot save first-run Wi-Fi framework defaults." }
        }
        val states = tweaks.associate { tweak ->
            val feature = offloads[tweak.feature]
            val steering = when (tweak.id) { "rps" -> currentRpsMasks; "xps" -> currentXpsMasks; else -> emptyMap() }
            val value = when {
                tweak.feature != null -> feature?.enabled
                tweak.sysctl != null -> sysctls[tweak.id]?.first
                tweak.id == "nopowersave" -> powerValue
                tweak.id == "ipreach" -> ipReachValue
                tweak.id == "rssi_fast" -> rssiPollValue?.let { it == 1000 }
                tweak.id == "wifi_verbose" -> verboseLogging?.level?.let { it > 0 }
                tweak.id in setOf("rps", "xps") -> steering.takeIf { it.isNotEmpty() }?.let { it == balancedCpuMasks(it.keys, currentOnlineCpus) }
                else -> null
            }
            val supported = when {
                tweak.feature != null -> feature != null && !feature.fixed
                tweak.sysctl != null -> sysctls[tweak.id]?.second == true
                tweak.id == "nopowersave" -> value != null
                tweak.id in setOf("ipreach", "rssi_fast", "wifi_verbose") -> value != null
                tweak.id in setOf("rps", "xps") -> steering.isNotEmpty()
                else -> false
            }
            if (fresh && value != null && tweak.sysctl == null && tweak.id !in setOf("ipreach", "rssi_fast", "wifi_verbose", "rps", "xps")) initial.put(tweak.id, value)
            val original = when {
                tweak.sysctl != null && tcpInitial.has(tweak.id) -> tcpInitial.getBoolean(tweak.id)
                tweak.id == "ipreach" && frameworkPrefs.contains("ipreach") -> frameworkPrefs.getBoolean("ipreach", true)
                tweak.id == "rssi_fast" && frameworkPrefs.contains("rssiPollMs") -> frameworkPrefs.getInt("rssiPollMs", 3000) == 1000
                tweak.id == "wifi_verbose" && frameworkPrefs.contains("verboseLoggingLevel") -> frameworkPrefs.getInt("verboseLoggingLevel", 0) > 0
                tweak.id in setOf("rps", "xps") && savedMasks(tweak.id).isNotEmpty() ->
                    savedMasks(tweak.id) == balancedCpuMasks(savedMasks(tweak.id).keys, currentOnlineCpus)
                tweak.sysctl == null && initial.has(tweak.id) -> initial.getBoolean(tweak.id)
                else -> null
            }
            val writable = supported && original != null
            val reason = when {
                supported && !writable -> "Current value detected; no immutable first-run value was available"
                tweak.sysctl != null && writable -> "Checked · root · kernel-global TCP control; affects Wi-Fi and mobile data"
                tweak.id == "ipreach" && writable -> "Checked · Android framework control · first-run value saved"
                tweak.id == "rssi_fast" && writable -> "Current interval: ${rssiPollValue} ms · first-run interval: ${frameworkPrefs.getInt("rssiPollMs", 0)} ms"
                tweak.id == "wifi_verbose" && writable -> "Current level: ${verboseLogging?.level} · saved original level: ${frameworkPrefs.getInt("verboseLoggingLevel", 0)}${if (verboseLogging?.exact == false) " · current level inferred from On/Off response" else ""}"
                tweak.id in setOf("rps", "xps") && writable -> "Current masks: ${steeringSummary(steering)} · saved OEM masks: ${steeringSummary(savedMasks(tweak.id))} · online CPUs: ${currentOnlineCpus.joinToString()}"
                writable -> "Current and first-run values detected · apply verifies readable results"
                feature?.fixed == true -> "Driver reports fixed or pending; Apply can still request the standard setting"
                tweak.id == "nopowersave" -> "Current value is unavailable; Apply will request the standard iw setting"
                tweak.id in setOf("ipreach", "rssi_fast", "wifi_verbose") -> "This Android build does not expose the framework command response"
                tweak.id in setOf("rps", "xps") -> "No readable queue mask is exposed; Apply will still request the standard Linux sysfs control"
                tweak.feature != null -> "Current value is not exposed; Apply will request the standard ethtool setting"
                tweak.sysctl != null -> "Current value is not exposed; Apply will request the standard kernel TCP path"
                else -> "Current value unavailable"
            }
            tweak.id to TweakState(value, original, reason, writable)
        }
        if (capturing) {
            check(initial.length() > 0 || tcpInitial.length() > 0) { "Defaults not readable yet. Connect the selected network and grant access; capture will retry automatically." }
            check(prefs.edit().putString("values", initial.toString()).putString("interface", detectedInterface)
                .putString("fingerprint", Build.FINGERPRINT).putInt("schema", 2)
                .putBoolean("captured", true).commit()) { "Cannot save first-run defaults." }
        } else if (initialUpdated) {
            check(prefs.edit().putString("values", initial.toString()).commit()) { "Cannot save original CPU steering masks." }
        }
        return states
    }

    private fun writeSteering(id: String, enabled: Boolean, exactMasks: Map<String, String>? = null) {
        val current = if (id == "rps") currentRpsMasks else currentXpsMasks
        check(current.isNotEmpty()) { "No ${id.uppercase(Locale.US)} queue control is exposed for $iface." }
        val attribute = if (id == "rps") "rps_cpus" else "xps_cpus"
        val target = exactMasks ?: if (enabled) balancedCpuMasks(current.keys, currentOnlineCpus)
            else current.keys.associateWith { "0" }
        check(target.isNotEmpty()) { "No online CPU map could be calculated." }
        check(target.keys.all { it in current.keys && Regex("(?:rx|tx)-\\d+").matches(it) }) { "The saved queue layout no longer matches $iface." }
        check(target.values.all { Regex("[0-9a-fA-F]+(?:,[0-9a-fA-F]+)*").matches(it) }) { "Invalid saved CPU mask." }
        val command = target.toSortedMap().entries.joinToString(" && ") { (queue, mask) ->
            "printf '$mask' > '/sys/class/net/$iface/queues/$queue/$attribute'"
        }
        run(command).checkedEmpty()
    }

    private fun write(id: String, enabled: Boolean, verboseLevelOverride: Int? = null, steeringOverride: Map<String, String>? = null) {
        val tweak = tweaks.first { it.id == id }
        if (id in setOf("rps", "xps")) {
            writeSteering(id, enabled, steeringOverride)
            return
        }
        val command = when {
            tweak.feature != null -> "/system/bin/ethtool -K $iface ${tweak.feature} ${if (enabled) "on" else "off"}"
            tweak.sysctl != null -> "printf '${if (enabled) "1" else "0"}' > '${tweak.sysctl}'"
            id == "nopowersave" -> if (cellular) error("Wi-Fi power saving does not apply to a cellular interface")
                else "/system/bin/iw dev $iface set power_save ${if (enabled) "off" else "on"}"
            id == "ipreach" -> "/system/bin/cmd wifi set-ipreach-disconnect ${if (enabled) "enabled" else "disabled"}"
            id == "rssi_fast" -> "/system/bin/cmd wifi set-poll-rssi-interval-msecs ${if (enabled) 1000 else frameworkPrefs.getInt("rssiPollMs", 3000)}"
            id == "wifi_verbose" -> (verboseLevelOverride ?: if (enabled) 1 else 0).let { level ->
                if (level == 0) "/system/bin/cmd wifi set-verbose-logging disabled"
                else if (level == 1) "/system/bin/cmd wifi set-verbose-logging enabled"
                else "/system/bin/cmd wifi set-verbose-logging enabled -l $level"
            }
            else -> error("No verified setter for $id")
        }
        val response = run(command)
        if (id in setOf("ipreach", "rssi_fast", "wifi_verbose")) response.checkedEmpty() else response.checked()
    }

    private fun original(id: String): Boolean {
        val tweak = tweaks.first { it.id == id }
        return when {
            tweak.sysctl != null -> tcpInitial.getBoolean(id)
            id == "ipreach" -> frameworkPrefs.getBoolean("ipreach", true)
            id == "rssi_fast" -> frameworkPrefs.getInt("rssiPollMs", 3000) == 1000
            id == "wifi_verbose" -> frameworkPrefs.getInt("verboseLoggingLevel", 0) > 0
            id in setOf("rps", "xps") -> savedMasks(id) == balancedCpuMasks(savedMasks(id).keys, currentOnlineCpus)
            else -> initial.getBoolean(id)
        }
    }

    fun apply(values: Map<String, Boolean>): Map<String, TweakState> {
        check(prefs.getBoolean("captured", false)) { "Waiting for automatic first-run defaults." }
        if (root) RootAccess.requireRoot(RootAccess.run(RootAccess.PROBE, 60))
        val before = scan()
        val beforeVerboseLevel = currentVerboseLevel
        val beforeRpsMasks = currentRpsMasks
        val beforeXpsMasks = currentXpsMasks
        check(values.isNotEmpty()) { "No setting was selected." }
        values.forEach { (id, _) -> check(tweaks.any { it.id == id }) { "Unknown control: $id" } }
        val changed = values.filter { (id, value) -> before[id]?.value != value }.toMutableMap()
        val previousDirty = prefs.getStringSet("dirty", emptySet()).orEmpty()
        val dirty = previousDirty.toMutableSet()
        // Offload dependencies can alter siblings; keep every mutable offload in the recovery set.
        if (changed.keys.any { id -> tweaks.first { it.id == id }.feature != null })
            dirty.addAll(tweaks.filter { it.feature != null && before[it.id]?.writable == true }.map { it.id })
        dirty.addAll(changed.keys)
        mark(dirty)
        try {
            val rejected = linkedMapOf<String, String>()
            changed.forEach { (id, value) ->
                runCatching { write(id, value) }.onFailure { rejected[id] = it.message ?: "command rejected" }
            }
            val after = scan()
            values.forEach { (id, value) -> after[id]?.value?.let { if (it != value) rejected[id] = "readback remained ${if (it) "On" else "Off"}" } }
            val accepted = changed.keys - rejected.keys
            lastReport = when {
                changed.isEmpty() -> "The setting already had that value."
                rejected.isEmpty() -> "All requested values were accepted."
                else -> "Accepted ${accepted.size}/${changed.size}. Rejected: ${rejected.entries.joinToString(" · ") { "${it.key}: ${it.value}" }.take(700)}"
            }
            check(changed.isEmpty() || accepted.isNotEmpty()) { lastReport }
            val finalDirty = previousDirty.toMutableSet().apply {
                addAll(accepted)
                addAll(rejected.keys.filter { before[it]?.value != after[it]?.value && after[it]?.value != null })
                if (accepted.any { id -> tweaks.first { it.id == id }.feature != null })
                    addAll(tweaks.filter { it.feature != null && before[it.id]?.original != null }.map { it.id })
            }
            mark(finalDirty)
            return after
        } catch (error: Exception) {
            val restored = runCatching {
                dirty.forEach { id -> runCatching { before[id]?.value?.let {
                    write(id, it, if (id == "wifi_verbose") beforeVerboseLevel else null,
                        when (id) { "rps" -> beforeRpsMasks; "xps" -> beforeXpsMasks; else -> null })
                } } }
                val after = scan()
                check(dirty.all { before[it]?.value != null && after[it]?.value == before[it]?.value })
            }.isSuccess
            if (restored) mark(previousDirty)
            throw IllegalStateException("${error.message} ${if (restored) "Previous values restored." else "Recovery incomplete; use Restore defaults."}")
        }
    }

    fun restore(): Map<String, TweakState> {
        check(prefs.getBoolean("captured", false)) { "No first-run defaults were captured." }
        if (root) RootAccess.requireRoot(RootAccess.run(RootAccess.PROBE, 60))
        val before = scan()
        val dirty = prefs.getStringSet("dirty", emptySet()).orEmpty() +
            before.filter { (id, state) -> state.writable && (initial.has(id) || tcpInitial.has(id) ||
                (id == "ipreach" && frameworkPrefs.contains("ipreach")) ||
                (id == "rssi_fast" && frameworkPrefs.contains("rssiPollMs")) ||
                (id == "wifi_verbose" && frameworkPrefs.contains("verboseLoggingLevel")) ||
                (id in setOf("rps", "xps") && savedMasks(id).isNotEmpty())) }.keys
        mark(dirty)
        val failed = mutableListOf<String>()
        dirty.forEach { id ->
            val saved = runCatching { original(id) }.getOrNull()
            if (saved == null) failed.add(id) else runCatching {
                if (id in setOf("rps", "xps")) writeSteering(id, saved, savedMasks(id))
                else write(id, saved, if (id == "wifi_verbose") frameworkPrefs.getInt("verboseLoggingLevel", 0) else null)
            }.onFailure { failed.add(id) }
        }
        val after = scan()
        dirty.forEach { id -> runCatching { original(id) }.getOrNull()?.let { if (after[id]?.value != it) failed.add(id) } }
        mark(failed.toSet())
        check(failed.isEmpty()) { "Could not restore: ${failed.distinct().joinToString()}. Reconnect with the original access method and retry." }
        return after
    }
}
