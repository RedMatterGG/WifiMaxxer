package com.wifimaxxer

import android.content.Context
import android.os.Build
import org.json.JSONObject

data class KernelValueOption(val value: String, val label: String, val explanation: String)
data class KernelValueRange(val min: Int, val max: Int, val step: Int, val unit: String)

data class OrigamiNetworkSetting(
    val id: String,
    val title: String,
    val path: String,
    val description: String,
    val effect: String,
    val risk: String,
    val recommended: String?,
    val options: List<KernelValueOption> = emptyList(),
    val range: KernelValueRange? = null,
    val dynamicCongestionAlgorithms: Boolean = false
)

data class OrigamiNetworkState(
    val current: String?,
    val original: String?,
    val writable: Boolean,
    val available: List<String> = emptyList(),
    val reason: String
)

val origamiNetworkSettings = listOf(
    OrigamiNetworkSetting(
        "tcp_congestion", "TCP congestion-control algorithm", "/proc/sys/net/ipv4/tcp_congestion_control",
        "Selects the Linux congestion-control algorithm used by new TCP connections.",
        "The algorithm decides how quickly TCP grows or reduces its sending rate as congestion changes.",
        "Kernel-global and workload-dependent. An unsuitable algorithm can reduce throughput, increase queueing, or be rejected when its module is unavailable.",
        null, dynamicCongestionAlgorithms = true
    ),
    OrigamiNetworkSetting(
        "tcp_max_syn_backlog", "TCP maximum SYN backlog", "/proc/sys/net/ipv4/tcp_max_syn_backlog",
        "Sets how many incomplete incoming TCP connection requests each listener may remember.",
        "A larger queue can help a busy server absorb bursts of connection attempts; it normally does little for a phone acting only as a client.",
        "Higher values can consume more kernel memory. Lower values can drop legitimate incoming connections during a burst.",
        null, range = KernelValueRange(128, 32400, 2, "pending requests")
    ),
    OrigamiNetworkSetting(
        "tcp_keepalive_time", "TCP keepalive idle time", "/proc/sys/net/ipv4/tcp_keepalive_time",
        "Sets the idle time before Linux starts keepalive probes for sockets whose apps enabled TCP keepalive.",
        "A shorter time detects dead peers sooner; a longer time avoids needless radio wakeups and traffic.",
        "Kernel-global. Short intervals can increase mobile-data use and battery drain; this value does nothing for sockets that did not enable keepalive.",
        null, range = KernelValueRange(128, 32400, 2, "seconds")
    ),
    OrigamiNetworkSetting(
        "tcp_syncookies", "TCP SYN cookies", "/proc/sys/net/ipv4/tcp_syncookies",
        "Controls the SYN-cookie fallback used when an incoming TCP SYN backlog overflows.",
        "Value 1 helps a listener continue accepting connections during a SYN flood without retaining every incomplete request.",
        "Disabling it removes a flood-protection fallback. SYN cookies are a fallback and are not a substitute for sizing a legitimately overloaded server.",
        "1", options = listOf(
            KernelValueOption("0", "0 · Disabled", "Never send SYN cookies."),
            KernelValueOption("1", "1 · Overflow fallback", "Send SYN cookies when a SYN backlog overflows; the usual Linux default.")
        )
    ),
    OrigamiNetworkSetting(
        "tcp_tw_reuse", "TCP TIME-WAIT socket reuse", "/proc/sys/net/ipv4/tcp_tw_reuse",
        "Controls when Linux may reuse TIME-WAIT sockets for new outgoing connections when protocol-safe.",
        "Reuse can reduce pressure from very high rates of short-lived outgoing TCP connections.",
        "Linux explicitly advises against changing this without expert guidance. Global reuse can cause hard-to-diagnose failures with NAT, timestamps, or unusual peers.",
        "2", options = listOf(
            KernelValueOption("0", "0 · Disabled", "Do not reuse TIME-WAIT sockets."),
            KernelValueOption("1", "1 · Global", "Allow protocol-safe reuse for all connections."),
            KernelValueOption("2", "2 · Loopback only", "Allow reuse only for local loopback traffic; the upstream Linux default.")
        )
    ),
    OrigamiNetworkSetting(
        "tcp_ecn", "TCP Explicit Congestion Notification", "/proc/sys/net/ipv4/tcp_ecn",
        "Controls TCP negotiation of ECN, which lets routers mark congestion instead of dropping a packet.",
        "ECN can reduce loss and latency on compatible paths.",
        "Some old or broken network equipment mishandles ECN. The setting is global and affects negotiation for new TCP connections.",
        "2", options = listOf(
            KernelValueOption("0", "0 · Disabled", "Do not request ECN and do not accept incoming ECN negotiation."),
            KernelValueOption("1", "1 · Active and passive", "Request ECN on outgoing connections and accept it on incoming connections."),
            KernelValueOption("2", "2 · Passive only", "Accept incoming ECN negotiation but do not request it on outgoing connections; the upstream default.")
        )
    ),
    OrigamiNetworkSetting(
        "tcp_fastopen", "TCP Fast Open", "/proc/sys/net/ipv4/tcp_fastopen",
        "Uses a bit mask to control sending application data during the TCP opening handshake.",
        "On supported clients and servers it can remove one round trip before useful data is sent.",
        "Requires application and peer support. Early data can be replayed and some middleboxes reject it; server mode also changes listening behavior.",
        "1", options = listOf(
            KernelValueOption("0", "0 · Disabled", "Disable client and server TCP Fast Open."),
            KernelValueOption("1", "1 · Client", "Allow clients to send data in the opening SYN when they have a valid cookie."),
            KernelValueOption("2", "2 · Server", "Allow servers to accept data in the opening SYN."),
            KernelValueOption("3", "3 · Client + server", "Enable both bit 1 and bit 2 behavior.")
        )
    ),
    OrigamiNetworkSetting(
        "tcp_sack", "TCP selective acknowledgements", "/proc/sys/net/ipv4/tcp_sack",
        "Controls TCP SACK support, which tells a sender which non-contiguous blocks arrived after packet loss.",
        "Keeping SACK enabled normally recovers multiple losses with fewer retransmissions.",
        "Disabling it can sharply reduce throughput on lossy links. A kernel security fix should be used instead of disabling SACK as a permanent workaround.",
        "1", options = listOf(
            KernelValueOption("0", "0 · Disabled", "Use cumulative acknowledgements without SACK blocks."),
            KernelValueOption("1", "1 · Enabled", "Permit selective acknowledgement negotiation and processing.")
        )
    ),
    OrigamiNetworkSetting(
        "tcp_timestamps", "TCP timestamps", "/proc/sys/net/ipv4/tcp_timestamps",
        "Controls RFC 1323 TCP timestamps used for round-trip measurement and protection against old duplicate segments.",
        "Timestamps improve RTT sampling and PAWS behavior on fast connections.",
        "They add TCP option bytes and may reveal timing information. Disabling them can reduce performance or protection on high-speed paths.",
        "1", options = listOf(
            KernelValueOption("0", "0 · Disabled", "Do not negotiate TCP timestamps."),
            KernelValueOption("1", "1 · Randomized offset", "Enable timestamps with a random per-connection offset; the upstream default."),
            KernelValueOption("2", "2 · No random offset", "Enable timestamps without random offsets.")
        )
    ),
    OrigamiNetworkSetting(
        "bpf_jit_harden", "BPF JIT hardening", "/proc/sys/net/core/bpf_jit_harden",
        "Controls constant blinding in the BPF just-in-time compiler to make JIT-spraying attacks harder.",
        "Hardening improves resistance to attacks that abuse predictable generated BPF machine code.",
        "This is a device-wide security/performance control, not a speed tweak. Stronger hardening can add compilation and execution overhead; weakening an OEM value reduces defense in depth.",
        null, options = listOf(
            KernelValueOption("0", "0 · Disabled", "Disable BPF JIT constant blinding."),
            KernelValueOption("1", "1 · Unprivileged users", "Harden programs loaded by unprivileged users."),
            KernelValueOption("2", "2 · All users", "Harden programs loaded by both privileged and unprivileged users.")
        )
    )
)

fun congestionAlgorithmExplanation(value: String) = when (value.lowercase()) {
    "reno" -> "Classic loss-based Reno; conservative and always available in upstream Linux."
    "cubic" -> "Loss-based CUBIC; designed for good utilization across a wide range of bandwidth and delay."
    "bbr", "bbr2", "bbr3" -> "Model-based BBR family; estimates bottleneck bandwidth and round-trip propagation time. Exact behavior depends on the kernel version."
    "westwood" -> "Loss-based control with bandwidth estimation intended to improve recovery on variable or wireless links."
    "vegas" -> "Delay-sensitive control that slows before loss when round-trip delay rises."
    else -> "Kernel-provided algorithm. Behavior depends on that algorithm's implementation in this ROM."
}

fun origamiValueError(setting: OrigamiNetworkSetting, value: String, available: List<String> = emptyList()): String? {
    setting.range?.let { range ->
        val number = value.toIntOrNull() ?: return "Enter a whole number."
        if (number !in range.min..range.max) return "Use ${range.min}–${range.max} ${range.unit}."
        if ((number - range.min) % range.step != 0) return "Use increments of ${range.step}."
        return null
    }
    if (setting.dynamicCongestionAlgorithms) {
        if (!Regex("[A-Za-z0-9_-]{1,32}").matches(value)) return "Enter a valid kernel algorithm name."
        if (available.isNotEmpty() && value !in available) return "Choose an algorithm reported by this kernel."
        return null
    }
    return if (setting.options.any { it.value == value }) null else "Choose one of the listed values."
}

fun parseOrigamiNetworkScan(text: String): Pair<Map<String, Pair<String, Boolean>>, List<String>> {
    val values = Regex("(?m)^WM_ORIGAMI_([a-z0-9_]+)=([01]):([A-Za-z0-9_-]{1,32})$").findAll(text)
        .associate { it.groupValues[1] to (it.groupValues[3] to (it.groupValues[2] == "1")) }
    val available = Regex("(?m)^WM_ORIGAMI_AVAILABLE=([A-Za-z0-9_ -]*)$").find(text)?.groupValues?.get(1)
        ?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
    return values to available
}

class OrigamiNetworkTuning(private val context: Context, private val root: Boolean) {
    private val prefs = context.getSharedPreferences("origami-network-baseline", Context.MODE_PRIVATE)
    private var originals = JSONObject()
    var lastReport = ""
        private set

    private fun mark(ids: Set<String>) {
        check(prefs.edit().putStringSet("dirty", ids).commit()) { "Cannot save the advanced network recovery journal." }
        refreshTuningDirtyState(context)
    }

    fun pending() = prefs.getStringSet("dirty", emptySet()).orEmpty().isNotEmpty()

    fun scan(captureDefaults: Boolean = false): Map<String, OrigamiNetworkState> {
        expireCustomSessionAfterReboot(context)
        val savedFingerprint = prefs.getString("fingerprint", null)
        check(savedFingerprint == null || savedFingerprint == Build.FINGERPRINT) {
            "The ROM changed since advanced network defaults were captured. Reinstall or clear app storage after reboot."
        }
        originals = JSONObject(prefs.getString("values", "{}")!!)
        val body = origamiNetworkSettings.joinToString("\n") { setting ->
            """if [ -r '${setting.path}' ]; then v=${'$'}(/system/bin/cat '${setting.path}' 2>/dev/null); case "${'$'}v" in ''|*[!A-Za-z0-9_-]*) printf 'WM_ORIGAMI_MISSING_${setting.id}\n';; *) [ -w '${setting.path}' ] && w=1 || w=0; printf 'WM_ORIGAMI_${setting.id}=%s:%s\n' "${'$'}w" "${'$'}v";; esac; else printf 'WM_ORIGAMI_MISSING_${setting.id}\n'; fi"""
        }
        val output = if (root) RootAccess.run(
            """$body
                if [ -r '/proc/sys/net/ipv4/tcp_available_congestion_control' ]; then v=${'$'}(/system/bin/cat '/proc/sys/net/ipv4/tcp_available_congestion_control' 2>/dev/null); printf 'WM_ORIGAMI_AVAILABLE=%s\n' "${'$'}v"; fi
            """.trimIndent()
        ).output else ""
        val (read, available) = parseOrigamiNetworkScan(output)
        var changed = false
        if (captureDefaults) read.forEach { (id, pair) ->
            if (!originals.has(id)) { originals.put(id, pair.first); changed = true }
        }
        if (changed || (captureDefaults && !prefs.getBoolean("captured", false))) {
            check(prefs.edit().putString("values", originals.toString()).putString("fingerprint", Build.FINGERPRINT)
                .putBoolean("captured", true).putInt("observedBoot", RootToggle.boot(context)).commit()) { "Cannot save advanced network defaults." }
        }
        return origamiNetworkSettings.associate { setting ->
            val found = read[setting.id]
            setting.id to OrigamiNetworkState(
                current = found?.first,
                original = originals.optString(setting.id).takeIf { originals.has(setting.id) },
                writable = found?.second == true,
                available = if (setting.dynamicCongestionAlgorithms) available else emptyList(),
                reason = when {
                    !root -> "Root is required."
                    found == null -> "Current value unavailable. Apply remains available and the kernel will report whether it accepts the write."
                    found.second -> "Current value read; sysctl reports writable."
                    else -> "Current value read; sysctl did not report writable. A root write may still be rejected by this ROM."
                }
            )
        }
    }

    fun apply(id: String, value: String): Map<String, OrigamiNetworkState> {
        val setting = requireNotNull(origamiNetworkSettings.find { it.id == id }) { "Unknown advanced network setting." }
        val before = scan(captureDefaults = true)
        origamiValueError(setting, value, before[id]?.available.orEmpty())?.let { error(it) }
        if (before[id]?.current == value) {
            lastReport = "Already set to $value."
            return before
        }
        val previousDirty = prefs.getStringSet("dirty", emptySet()).orEmpty()
        mark(previousDirty + id)
        try {
            check(root) { "This setting requires root." }
            RootAccess.run("printf '%s' '$value' > '${setting.path}'").checked()
            val after = scan()
            check(after[id]?.current == value) { "Kernel readback did not confirm $value." }
            lastReport = "Value $value written and verified."
            return after
        } catch (error: Exception) {
            val prior = before[id]?.current
            val rolledBack = prior != null && runCatching {
                RootAccess.run("printf '%s' '$prior' > '${setting.path}'").checked()
                scan()[id]?.current == prior
            }.getOrDefault(false)
            if (rolledBack) mark(previousDirty)
            throw IllegalStateException("${error.message} ${if (rolledBack) "Previous value restored." else "No confirmed change was restored; reboot resets these runtime sysctls."}")
        }
    }

    fun restore(): Map<String, OrigamiNetworkState> {
        check(root) { "This setting requires root." }
        originals = JSONObject(prefs.getString("values", "{}")!!)
        val dirty = prefs.getStringSet("dirty", emptySet()).orEmpty()
        val failed = mutableSetOf<String>()
        dirty.forEach { id ->
            val setting = origamiNetworkSettings.find { it.id == id }
            val original = originals.optString(id).takeIf { originals.has(id) }
            if (setting == null || original == null || runCatching {
                    RootAccess.run("printf '%s' '$original' > '${setting.path}'").checked()
                }.isFailure) failed += id
        }
        val after = scan()
        dirty.forEach { id -> originals.optString(id).takeIf { originals.has(id) }?.let { if (after[id]?.current != it) failed += id } }
        mark(failed)
        check(failed.isEmpty()) { "Could not restore: ${failed.joinToString()}. Reboot resets these runtime sysctls." }
        lastReport = if (dirty.isEmpty()) "No advanced network values were pending." else "Saved advanced network values restored and verified."
        return after
    }
}
