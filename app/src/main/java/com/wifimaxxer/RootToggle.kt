package com.wifimaxxer

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.*

/** One-shot root writes survive Activity recreation; no service or ongoing monitoring. */
object RootToggle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun load(context: Context) {
        if (RootProfileState.busy || RootProfileState.serviceRunning) return
        val prefs = context.getSharedPreferences("root-session", Context.MODE_PRIVATE)
        if (prefs.getInt("boot", -1) != boot(context)) {
            prefs.edit().clear().commit()
            RootProfileState.active = false
        } else if (prefs.getString("mode", "") == "toggle" && prefs.getBoolean("applied", false)) {
            RootProfileState.active = true
            RootProfileState.message = "Gaming was applied this boot. No background monitoring; use Restore to clear it."
        }
    }

    fun boot(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun set(context: Context, enabled: Boolean) {
        if (RootProfileState.busy) return
        val app = context.applicationContext
        RootProfileState.busy = true
        scope.launch {
            val prefs = app.getSharedPreferences("root-session", Context.MODE_PRIVATE)
            try {
                withContext(Dispatchers.IO) {
                    if (enabled) {
                        check(!prefs.getBoolean("pending", false)) { "Restore the previous profile before applying again." }
                        val probe = RootAccess.probe()
                        check(probe.lowLatencyAvailable) { probe.detail }
                        check(prefs.edit().putBoolean("pending", true).putBoolean("applied", false)
                            .putString("mode", "toggle").putInt("boot", boot(app)).commit()) { "Cannot save recovery state." }
                    }
                    RootAccess.run(if (enabled) RootAccess.APPLY else RootAccess.RESTORE).checkedEmpty()
                    check(prefs.edit().putBoolean("pending", enabled).putBoolean("applied", enabled).commit()) { "Could not save profile result; Restore is available." }
                }
                RootProfileState.active = enabled
                RootProfileState.message = if (enabled) "Gaming applied. You can close the app; Restore or reboot clears the override."
                    else "Wi-Fi returned to framework automatic behavior."
            } catch (e: Exception) {
                val restored = if (enabled && prefs.getBoolean("pending", false)) withContext(Dispatchers.IO) {
                    runCatching { RootAccess.run(RootAccess.RESTORE).checkedEmpty(); true }.getOrDefault(false)
                } else false
                if (restored) prefs.edit().putBoolean("pending", false).putBoolean("applied", false).commit()
                else if (!enabled) prefs.edit().putBoolean("pending", true).putString("mode", "toggle").putInt("boot", boot(app)).commit()
                RootProfileState.active = prefs.getBoolean("applied", false)
                RootProfileState.message = "${e.message ?: "Root operation failed."} " +
                    if (restored) "Returned to automatic behavior." else "Use Restore to retry, or reboot."
            } finally { RootProfileState.busy = false }
        }
    }
}
