package com.wifimaxxer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*

object RootProfileState {
    var serviceRunning by mutableStateOf(false)
    var active by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf("Grant root access to enable runtime profiles.")
}

class RootProfileService : Service() {
    override fun onCreate() { super.onCreate(); RootProfileState.serviceRunning = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lease: RootLease? = null
    private var monitor: Job? = null
    private var operation: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showNotification("Preparing root Wi-Fi control…")
        if (intent?.action == RESTORE || intent == null) {
            val previous = operation
            operation = scope.launch { previous?.cancelAndJoin(); release(force = true) }
        } else if (operation?.isActive != true && !RootProfileState.active) {
            operation = scope.launch { apply() }
        }
        // Restart into recovery only, never silently reapply after process death.
        return START_STICKY
    }

    private suspend fun apply() {
        RootProfileState.busy = true
        try {
            check(!pending(this)) { "Restore the interrupted session before applying a new profile." }
            val before = connection(this)
            check(before.iface != null && before.gateway != null) { "Connect to Wi-Fi with an IPv4 gateway first." }
            val worker = RootLease()
            lease = worker
            withContext(Dispatchers.IO) { worker.start() }
            // Disk journal must succeed before the first privileged write.
            check(getSharedPreferences("root-session", MODE_PRIVATE).edit().putBoolean("pending", true)
                .putString("mode", "service").putInt("boot", RootToggle.boot(this)).commit()) { "Cannot save recovery state." }
            withContext(Dispatchers.IO) { worker.apply() }
            RootProfileState.message = "Root override accepted; checking Wi-Fi connection…"
            showNotification("Checking Wi-Fi connection…")
            repeat(6) {
                delay(2000)
                val after = connection(this)
                check(after.iface == before.iface && after.gateway == before.gateway) { "Wi-Fi changed during verification." }
                withContext(Dispatchers.IO) { worker.send("keep") }
            }
            RootProfileState.active = true
            RootProfileState.message = "Gaming active through root. Framework accepted the request; throughput gains are not assumed."
            showNotification("Gaming active · tap Restore to end")
            monitor = scope.launch {
                while (isActive) {
                    delay(10_000)
                    val connected = runCatching { connection(this@RootProfileService).iface != null }.getOrDefault(false)
                    if (!connected || !worker.isAlive()) {
                        release("Wi-Fi disconnected or watchdog exited.")
                        break
                    }
                    try { withContext(Dispatchers.IO) { worker.send("keep") } }
                    catch (_: Exception) { release("Root watchdog communication failed."); break }
                }
            }
        } catch (e: CancellationException) {
            throw e // The explicit restore operation owns cleanup.
        } catch (e: Exception) {
            release(e.message ?: "Root profile failed.")
        } finally { RootProfileState.busy = false }
    }

    private suspend fun release(reason: String = "Session ended.", force: Boolean = false) {
        RootProfileState.busy = true
        // A monitor can call release itself; do not cancel its own cleanup coroutine.
        val caller = currentCoroutineContext()[Job]
        if (monitor != caller) monitor?.cancel()
        monitor = null
        val worker = lease
        lease = null
        val restored = withContext(Dispatchers.IO) {
            val stopped = worker?.stop() == true
            if (stopped || (!force && !pending(this@RootProfileService))) true else runCatching {
                RootAccess.run(RootAccess.RESTORE).checkedEmpty()
                true
            }.getOrDefault(false)
        }
        RootProfileState.active = false
        val cleared = restored && getSharedPreferences("root-session", MODE_PRIVATE).edit().putBoolean("pending", false).putBoolean("applied", false).commit()
        if (!cleared) getSharedPreferences("root-session", MODE_PRIVATE).edit().putBoolean("pending", true).commit()
        RootProfileState.message = if (cleared) "$reason Wi-Fi returned to framework automatic behavior."
            else "$reason Restore could not be confirmed. Grant root and retry Restore, or reboot."
        RootProfileState.busy = false
        if (cleared) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else showNotification("Recovery needed · tap Restore or reboot")
    }

    private fun showNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("root-profile", "Root Wi-Fi session", NotificationManager.IMPORTANCE_LOW))
        val restore = PendingIntent.getService(this, 1, Intent(this, RootProfileService::class.java).setAction(RESTORE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(7, Notification.Builder(this, "root-profile").setSmallIcon(com.wifimaxxer.R.drawable.ic_launcher)
            .setContentTitle("WiFi Maxxer").setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Restore", restore).build()).build())
    }

    override fun onDestroy() {
        // Closing stdin lets the root shell trap release the override independently.
        // Never block the main thread waiting for su or a firmware command.
        lease?.let { worker -> Thread { worker.stop() }.start() }
        scope.cancel()
        RootProfileState.active = false
        RootProfileState.busy = false
        RootProfileState.serviceRunning = false
        super.onDestroy()
    }

    companion object {
        const val RESTORE = "com.wifimaxxer.RESTORE"
        fun pending(context: Context) = context.getSharedPreferences("root-session", MODE_PRIVATE).getBoolean("pending", false)
        fun start(context: Context, restore: Boolean = false) {
            RootProfileState.busy = true
            try { context.startForegroundService(Intent(context, RootProfileService::class.java).apply { if (restore) action = RESTORE }) }
            catch (e: Exception) { RootProfileState.busy = false; throw e }
        }
    }
}
