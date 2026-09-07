package com.wifimaxxer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

data class Connection(val name: String = "Not connected", val rssi: Int? = null, val speed: Int? = null,
    val frequency: Int? = null, val gateway: String? = null, val iface: String? = null)

@Suppress("DEPRECATION")
fun connection(context: Context): Connection {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val network = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        ?: return Connection()
    val info = (cm.getNetworkCapabilities(network)?.transportInfo as? WifiInfo)
        ?: context.getSystemService(WifiManager::class.java).connectionInfo
    val links = cm.getLinkProperties(network)
    return Connection(info.ssid?.takeUnless { it == "<unknown ssid>" }?.trim('"') ?: "Wi-Fi connected · name unavailable",
        info.rssi.takeIf { it in -126..-1 }, info.linkSpeed.takeIf { it > 0 }, info.frequency.takeIf { it > 0 },
        links?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway?.hostAddress, links?.interfaceName)
}

fun capabilities(wifi: WifiManager): List<Pair<String, String>> {
    fun read(block: () -> Boolean) = runCatching { if (block()) "Supported" else "Not supported" }.getOrDefault("Unavailable")
    return listOf(
        "5 GHz" to read { wifi.is5GHzBandSupported },
        "6 GHz" to if (Build.VERSION.SDK_INT >= 30) read { wifi.is6GHzBandSupported } else "Requires Android 11",
        "Wi-Fi 6" to if (Build.VERSION.SDK_INT >= 30) read { wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX) } else "Unknown",
        "Wi-Fi 7" to if (Build.VERSION.SDK_INT >= 33) read { wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE) } else "Unknown",
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
    val samples = Regex("time[=<]([0-9]+(?:\\.[0-9]+)?)\\s*ms").findAll(output)
        .map { it.groupValues[1].toDouble() }.take(sent).toList()
    val jitter = samples.zipWithNext { a, b -> kotlin.math.abs(b - a) }.takeIf { it.isNotEmpty() }?.average()
    return PingStats(samples.size, sent, samples.takeIf { it.isNotEmpty() }?.average(),
        samples.sorted().getOrNull(ceil(samples.size * .95).toInt() - 1), jitter)
}

fun pingGateway(c: Connection): PingStats {
    val gateway = requireNotNull(c.gateway) { "No IPv4 Wi-Fi gateway available." }
    val iface = requireNotNull(c.iface) { "Wi-Fi interface unavailable." }
    val process = ProcessBuilder("/system/bin/ping", "-n", "-I", iface, "-c", "10", "-W", "1", gateway).redirectErrorStream(true).start()
    try {
        check(process.waitFor(18, TimeUnit.SECONDS)) { "Gateway test timed out." }
        val output = process.inputStream.bufferedReader().readText()
        check(process.exitValue() in 0..1) { "Ping unavailable: ${output.take(160)}" }
        return parsePing(output, 10)
    } finally { process.destroy() }
}
