package com.wifimaxxer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

data class Connection(val name: String = "Not connected", val rssi: Int? = null, val speed: Int? = null,
    val frequency: Int? = null, val gateway: String? = null, val iface: String? = null,
    val rxSpeed: Int? = null, val txSpeed: Int? = null, val maxRxSpeed: Int? = null, val maxTxSpeed: Int? = null)

data class NetworkTarget(val network: Network, val iface: String, val cellular: Boolean,
    val downstreamMbps: Int? = null, val upstreamMbps: Int? = null)

fun networkTarget(context: Context, cellular: Boolean): NetworkTarget? {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val transport = if (cellular) NetworkCapabilities.TRANSPORT_CELLULAR else NetworkCapabilities.TRANSPORT_WIFI
    return cm.allNetworks.asSequence().mapNotNull { network ->
        val capabilities = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
        if (!capabilities.hasTransport(transport) || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
        val iface = cm.getLinkProperties(network)?.interfaceName ?: return@mapNotNull null
        if (!Regex("[a-zA-Z0-9_.-]{1,32}").matches(iface)) return@mapNotNull null
        NetworkTarget(network, iface, cellular,
            capabilities.linkDownstreamBandwidthKbps.takeIf { it > 0 }?.let { (it + 999) / 1000 },
            capabilities.linkUpstreamBandwidthKbps.takeIf { it > 0 }?.let { (it + 999) / 1000 })
    }.firstOrNull()
}

@Suppress("DEPRECATION")
fun connection(context: Context): Connection {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val network = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        ?: return Connection()
    val info = (cm.getNetworkCapabilities(network)?.transportInfo as? WifiInfo)
        ?: context.getSystemService(WifiManager::class.java).connectionInfo
    val links = cm.getLinkProperties(network)
    val rxSpeed = if (Build.VERSION.SDK_INT >= 29) info.rxLinkSpeedMbps.takeIf { it > 0 } else info.linkSpeed.takeIf { it > 0 }
    val txSpeed = if (Build.VERSION.SDK_INT >= 29) info.txLinkSpeedMbps.takeIf { it > 0 } else info.linkSpeed.takeIf { it > 0 }
    val maxRxSpeed = if (Build.VERSION.SDK_INT >= 30) info.maxSupportedRxLinkSpeedMbps.takeIf { it > 0 } else rxSpeed
    val maxTxSpeed = if (Build.VERSION.SDK_INT >= 30) info.maxSupportedTxLinkSpeedMbps.takeIf { it > 0 } else txSpeed
    return Connection(info.ssid?.takeUnless { it == "<unknown ssid>" }?.trim('"') ?: "Wi-Fi connected · name unavailable",
        info.rssi.takeIf { it in -126..-1 }, info.linkSpeed.takeIf { it > 0 }, info.frequency.takeIf { it > 0 },
        links?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway?.hostAddress, links?.interfaceName,
        rxSpeed, txSpeed, maxRxSpeed, maxTxSpeed)
}

fun capabilities(wifi: WifiManager): List<Pair<String, String>> {
    fun read(block: () -> Boolean) = runCatching { if (block()) "Supported" else "Not supported" }.getOrDefault("Unavailable")
    return listOf(
        "5 GHz" to read { wifi.is5GHzBandSupported },
        "6 GHz" to if (Build.VERSION.SDK_INT >= 30) read { wifi.is6GHzBandSupported } else "Requires Android 11",
        "Wi-Fi 6" to if (Build.VERSION.SDK_INT >= 30) read { wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX) } else "Unknown",
        "Wi-Fi 7" to if (Build.VERSION.SDK_INT >= 33) read { wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE) } else "Unknown",
        "Wi-Fi 7 TID-to-link mapping" to if (Build.VERSION.SDK_INT >= 34) read { wifi.isTidToLinkMappingNegotiationSupported } else "Requires Android 14",
        "Dual-band simultaneous" to if (Build.VERSION.SDK_INT >= 35) read { wifi.isDualBandSimultaneousSupported } else "Not reported before Android 15",
        "Make-before-break roaming" to if (Build.VERSION.SDK_INT >= 31) read { wifi.isMakeBeforeBreakWifiSwitchingSupported } else "Unknown",
        "Preferred network offload" to read { wifi.isPreferredNetworkOffloadSupported },
        "WPA3 Personal" to read { wifi.isWpa3SaeSupported },
        "Enhanced Open (OWE)" to read { wifi.isEnhancedOpenSupported },
        "TDLS" to read { wifi.isTdlsSupported },
        "Low latency hardware" to "Command acceptance tested through root; radio effect is firmware-dependent",
        "Concurrent local connection" to if (Build.VERSION.SDK_INT >= 31) read { wifi.isStaConcurrencyForLocalOnlyConnectionsSupported } else "Unknown",
        "Concurrent internet connections" to if (Build.VERSION.SDK_INT >= 33) read { wifi.isStaConcurrencyForMultiInternetSupported } else "Unknown"
    )
}

data class PingStats(val received: Int, val sent: Int, val average: Double?, val p95: Double?, val jitter: Double?) {
    val loss: Double get() = 100.0 * (sent - received) / sent
}

fun parsePing(output: String, sent: Int): PingStats {
    require(sent > 0)
    val transmitted = Regex("(\\d+) packets transmitted").find(output)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..sent } ?: sent
    val samples = Regex("time[=<]([0-9]+(?:\\.[0-9]+)?)\\s*ms").findAll(output)
        .map { it.groupValues[1].toDouble() }.take(transmitted).toList()
    val jitter = samples.zipWithNext { a, b -> kotlin.math.abs(b - a) }.takeIf { it.isNotEmpty() }?.average()
    return PingStats(samples.size, transmitted, samples.takeIf { it.isNotEmpty() }?.average(),
        samples.sorted().getOrNull(ceil(samples.size * .95).toInt() - 1), jitter)
}

fun pingGateway(c: Connection, root: Boolean = false): PingStats {
    val gateway = requireNotNull(c.gateway) { "No IPv4 Wi-Fi gateway available." }
    val iface = requireNotNull(c.iface) { "Wi-Fi interface unavailable." }
    require(Regex("[a-zA-Z0-9_.-]{1,32}").matches(iface) && gateway.split('.').size == 4 && gateway.split('.').all { it.toIntOrNull() in 0..255 }) { "Invalid Wi-Fi interface or gateway." }
    if (root) {
        val result = RootAccess.run("/system/bin/ping -n -I $iface -c 10 -w 15 -W 1 $gateway", 25)
        check(result.code in 0..1) { "Gateway test failed: ${result.output.take(200)} ${result.error.take(200)}" }
        return parsePing(result.output, 10)
    }
    val process = ProcessBuilder("/system/bin/ping", "-n", "-I", iface, "-c", "10", "-w", "15", "-W", "1", gateway).redirectErrorStream(true).start()
    try {
        check(process.waitFor(18, TimeUnit.SECONDS)) { "Gateway test timed out." }
        val output = process.inputStream.bufferedReader().readText()
        check(process.exitValue() in 0..1) { "Ping unavailable: ${output.take(160)}" }
        return parsePing(output, 10)
    } finally { process.destroy() }
}
