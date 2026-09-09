package com.wifimaxxer

import android.content.Context
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

data class BandwidthLimitState(
    val downloadMbps: Int? = null,
    val uploadMbps: Int? = null,
    val iface: String? = null,
    val ownsClsact: Boolean = false
) {
    val active get() = downloadMbps != null || uploadMbps != null
}

enum class BandwidthTarget(
    val displayName: String,
    internal val prefsName: String,
    internal val downloadPreference: Int,
    internal val uploadPreference: Int
) {
    WIFI("Wi-Fi", "bandwidth-limit-wifi", 62001, 62002),
    CELLULAR("Mobile data", "bandwidth-limit-cellular", 62003, 62004)
}

object BandwidthLimiter {
    const val MIN_DOWNLOAD_MBPS = 5
    const val MIN_UPLOAD_MBPS = 1

    fun load(context: Context, target: BandwidthTarget = BandwidthTarget.WIFI): BandwidthLimitState {
        val prefs = preferences(context, target)
        if (prefs.getInt("boot", -1) != RootToggle.boot(context)) {
            prefs.edit().clear().apply()
            return BandwidthLimitState()
        }
        return BandwidthLimitState(
            prefs.getInt("download", 0).takeIf { it >= MIN_DOWNLOAD_MBPS },
            prefs.getInt("upload", 0).takeIf { it >= MIN_UPLOAD_MBPS },
            prefs.getString("iface", null),
            prefs.getBoolean("owns-clsact", false)
        )
    }

    fun apply(context: Context, target: BandwidthTarget, iface: String, downloadMbps: Int?, uploadMbps: Int?): BandwidthLimitState {
        require(validIface(iface)) { "Invalid ${target.displayName.lowercase()} interface." }
        require(downloadMbps == null || downloadMbps >= MIN_DOWNLOAD_MBPS) { "Download limit must be at least $MIN_DOWNLOAD_MBPS Mbps." }
        require(uploadMbps == null || uploadMbps >= MIN_UPLOAD_MBPS) { "Upload limit must be at least $MIN_UPLOAD_MBPS Mbps." }
        if (downloadMbps == null && uploadMbps == null) return restore(context, target)

        val previous = load(context, target)
        if (previous.active && previous.iface != null && previous.iface != iface) restoreInterface(target, previous)
        val result = RootAccess.run(applyScript(iface, downloadMbps, uploadMbps, target))
        var appliedOwnsClsact = previous.iface == iface && previous.ownsClsact
        try {
            val output = result.checked()
            appliedOwnsClsact = appliedOwnsClsact || Regex("(?m)^WM_OWNS_CLSACT=(0|1)$").find(output)?.groupValues?.get(1) == "1"
            val state = BandwidthLimitState(downloadMbps, uploadMbps, iface, appliedOwnsClsact)
            check(preferences(context, target).edit()
                .putInt("download", downloadMbps ?: 0).putInt("upload", uploadMbps ?: 0)
                .putString("iface", iface).putBoolean("owns-clsact", appliedOwnsClsact)
                .putInt("boot", RootToggle.boot(context)).commit()) { "Could not save bandwidth-limit state." }
            return state
        } catch (e: Exception) {
            runCatching { RootAccess.run(restoreScript(iface, appliedOwnsClsact, target)).checked() }
            preferences(context, target).edit().clear().apply()
            throw e
        }
    }

    fun restore(context: Context, target: BandwidthTarget = BandwidthTarget.WIFI): BandwidthLimitState {
        val previous = load(context, target)
        if (previous.iface != null) RootAccess.run(restoreScript(previous.iface, previous.ownsClsact, target)).checked()
        check(preferences(context, target).edit().clear().commit()) { "Could not clear bandwidth-limit state." }
        return BandwidthLimitState()
    }

    private fun restoreInterface(target: BandwidthTarget, state: BandwidthLimitState) {
        RootAccess.run(restoreScript(requireNotNull(state.iface), state.ownsClsact, target)).checked()
    }

    internal fun applyScript(iface: String, downloadMbps: Int?, uploadMbps: Int?, target: BandwidthTarget = BandwidthTarget.WIFI): String {
        require(validIface(iface))
        val downloadPref = target.downloadPreference
        val uploadPref = target.uploadPreference
        val download = downloadMbps?.let { policeCommand(iface, "ingress", downloadPref, it) }.orEmpty()
        val upload = uploadMbps?.let { policeCommand(iface, "egress", uploadPref, it) }.orEmpty()
        return """
            set -eu
            [ "${'$'}(id -u)" = 0 ] || { echo "Root required" >&2; exit 1; }
            tc_bin="${'$'}(command -v tc 2>/dev/null || true)"
            [ -n "${'$'}tc_bin" ] || { echo "Traffic control is unavailable on this Android build" >&2; exit 1; }
            [ -d "/sys/class/net/$iface" ] || { echo "${target.displayName} interface disappeared" >&2; exit 1; }
            owns=0
            if ! "${'$'}tc_bin" qdisc show dev "$iface" | grep -q 'qdisc clsact '; then
                "${'$'}tc_bin" qdisc add dev "$iface" clsact
                owns=1
            fi
            cleanup() {
                "${'$'}tc_bin" filter del dev "$iface" ingress pref $downloadPref protocol all >/dev/null 2>&1 || true
                "${'$'}tc_bin" filter del dev "$iface" egress pref $uploadPref protocol all >/dev/null 2>&1 || true
                if [ "${'$'}owns" = 1 ]; then "${'$'}tc_bin" qdisc del dev "$iface" clsact >/dev/null 2>&1 || true; fi
            }
            trap cleanup EXIT HUP INT TERM
            "${'$'}tc_bin" filter del dev "$iface" ingress pref $downloadPref protocol all >/dev/null 2>&1 || true
            "${'$'}tc_bin" filter del dev "$iface" egress pref $uploadPref protocol all >/dev/null 2>&1 || true
            $download
            $upload
            trap - EXIT HUP INT TERM
            echo "WM_OWNS_CLSACT=${'$'}owns"
        """.trimIndent()
    }

    internal fun restoreScript(iface: String, ownsClsact: Boolean, target: BandwidthTarget = BandwidthTarget.WIFI): String {
        require(validIface(iface))
        val downloadPref = target.downloadPreference
        val uploadPref = target.uploadPreference
        return """
            set -eu
            [ "${'$'}(id -u)" = 0 ] || exit 1
            tc_bin="${'$'}(command -v tc 2>/dev/null || true)"
            [ -n "${'$'}tc_bin" ] || exit 0
            [ -d "/sys/class/net/$iface" ] || exit 0
            "${'$'}tc_bin" filter del dev "$iface" ingress pref $downloadPref protocol all >/dev/null 2>&1 || true
            "${'$'}tc_bin" filter del dev "$iface" egress pref $uploadPref protocol all >/dev/null 2>&1 || true
            ${if (ownsClsact) "if [ -z \"${'$'}(\"${'$'}tc_bin\" filter show dev \"$iface\" ingress)${'$'}(\"${'$'}tc_bin\" filter show dev \"$iface\" egress)\" ]; then \"${'$'}tc_bin\" qdisc del dev \"$iface\" clsact >/dev/null 2>&1 || true; fi" else ":"}
        """.trimIndent()
    }

    private fun policeCommand(iface: String, direction: String, preference: Int, rateMbps: Int): String {
        val burstKb = (rateMbps * 4).coerceIn(128, 16_384)
        val common = "action police rate ${rateMbps}mbit burst ${burstKb}kb conform-exceed drop/ok"
        return "if ! \"${'$'}tc_bin\" filter add dev \"$iface\" $direction pref $preference protocol all matchall $common 2>/dev/null; then " +
            "\"${'$'}tc_bin\" filter del dev \"$iface\" $direction pref $preference protocol all >/dev/null 2>&1 || true; " +
            "\"${'$'}tc_bin\" filter add dev \"$iface\" $direction pref $preference protocol all u32 match u32 0 0 $common; fi"
    }

    private fun preferences(context: Context, target: BandwidthTarget) =
        context.getSharedPreferences(target.prefsName, Context.MODE_PRIVATE).also { current ->
            if (target == BandwidthTarget.WIFI && !current.contains("boot")) {
                val legacy = context.getSharedPreferences("bandwidth-limit", Context.MODE_PRIVATE)
                if (legacy.contains("boot")) {
                    current.edit().putInt("download", legacy.getInt("download", 0))
                        .putInt("upload", legacy.getInt("upload", 0)).putString("iface", legacy.getString("iface", null))
                        .putBoolean("owns-clsact", legacy.getBoolean("owns-clsact", false))
                        .putInt("boot", legacy.getInt("boot", -1)).commit()
                    legacy.edit().clear().apply()
                }
            }
        }

    private fun validIface(value: String) = Regex("[a-zA-Z0-9_.-]{1,32}").matches(value)
}

internal fun limitToSlider(limit: Int, minimum: Int, maximum: Int): Float {
    if (maximum <= minimum) return 0f
    return (ln(limit.coerceIn(minimum, maximum).toDouble() / minimum) / ln(maximum.toDouble() / minimum)).toFloat()
}

internal fun sliderToLimit(position: Float, minimum: Int, maximum: Int): Int {
    if (maximum <= minimum) return minimum
    return (minimum * exp(position.coerceIn(0f, 1f) * ln(maximum.toDouble() / minimum))).roundToInt().coerceIn(minimum, maximum)
}
