package com.wifimaxxer

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import java.io.IOException
import java.io.InputStream

data class RootResult(val code: Int, val output: String, val error: String = "") {
    fun checkedEmpty() { check(checked().trim().lowercase() in setOf("", "success", "ok")) { "Unexpected firmware response: ${output.take(250)}" } }
    fun checked(): String {
        val detail = "$output\n$error".trim()
        check(code == 0 && !Regex("(?i)(command execution failed|permission denied|not permitted|unknown command|exception|error:)").containsMatchIn(detail)) {
            detail.take(500).ifEmpty { "Root command failed (exit $code)." }
        }
        return output
    }
}

data class RootProbe(val lowLatencyAvailable: Boolean, val detail: String)

/** Execute su rather than checking whether its file or manager package is visible. */
object SuLauncher {
    @Volatile var selected: String? = null
        private set

    internal fun launch(command: String, start: (List<String>) -> Process = { ProcessBuilder(it).start() }): Process {
        val failures = mutableListOf<String>()
        val candidates = (listOfNotNull(selected) + listOf("/system/bin/su", "su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/su/bin/su")).distinct()
        for (binary in candidates) {
            try {
                return start(listOf(binary, "-c", command)).also { selected = binary }
            } catch (e: IOException) { failures.add("$binary: ${e.message}") }
        }
        throw IOException("Cannot launch su. Enable superuser access for WiFi Maxxer (com.wifimaxxer) in your root manager, then reopen the app. ${failures.joinToString("; ").take(700)}")
    }
}

object RootAccess {
    // Only fixed, app-owned commands are accepted by callers. No user text goes into a shell.
    const val RESTORE = "/system/bin/cmd wifi force-low-latency-mode disabled"
    const val APPLY = "/system/bin/cmd wifi force-low-latency-mode enabled"
    const val PROBE = "printf 'WM_UID='; /system/bin/id -u"
    @Volatile var diagnostic: String = "Root has not been checked."
        private set

    fun run(command: String, timeoutSeconds: Long = 30): RootResult {
        val process = SuLauncher.launch(command)
        val output = StringBuffer()
        val error = StringBuffer()
        fun drain(stream: InputStream, buffer: StringBuffer) = thread(isDaemon = true, name = "root-output") {
            runCatching { stream.bufferedReader().useLines { lines -> lines.forEach { if (buffer.length < 64_000) buffer.appendLine(it) } } }
        }
        val reader = drain(process.inputStream, output)
        val errorReader = drain(process.errorStream, error)
        try {
            process.outputStream.close()
            check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "Root request timed out. Grant access in your root manager and retry." }
            reader.join(1000)
            errorReader.join(1000)
            return RootResult(process.exitValue(), output.toString(), error.toString()).also {
                diagnostic = "su: ${SuLauncher.selected}\nCommand: $command\nExit: ${it.code}\nstdout: ${it.output.take(1200)}\nstderr: ${it.error.take(1200)}"
            }
        } finally { process.destroy(); if (process.isAlive) process.destroyForcibly() }
    }

    internal fun requireRoot(result: RootResult) {
        val uid = Regex("(?m)^WM_UID=(\\d+)\\s*$").find(result.output)?.groupValues?.get(1)
        check(result.code == 0 && uid == "0") {
            if (uid != null) "su returned UID $uid instead of root (0). Check WiFi Maxxer's root-manager app profile."
            else "Superuser access was not confirmed. ${result.output.trim()} ${result.error.trim()}".take(700)
        }
    }

    internal fun wifiCapability(result: RootResult): RootProbe {
        // Help text is documentation, not a command response: don't scan it for error keywords.
        val available = result.code == 0 && Regex("(?m)^\\s*force-low-latency-mode(?:\\s|$)").containsMatchIn(result.output)
        return RootProbe(available, if (available) "Root granted (UID 0). Low-latency command available."
            else "Root granted (UID 0), but low-latency control is unavailable. ${result.error.trim().take(300)}".trim())
    }

    internal fun hiddenCommandAvailable(response: String) = Regex("(?i)argument expected after[^\\n]*force-low-latency-mode").containsMatchIn(response)

    fun probe(): RootProbe {
        requireRoot(run(PROBE, 60))
        val capability = runCatching {
            val help = wifiCapability(run("/system/bin/cmd wifi help"))
            if (help.lowLatencyAvailable) help else {
                // No argument: AOSP validates the required argument before any state change.
                val check = run("/system/bin/cmd wifi force-low-latency-mode")
                val response = "${check.output}\n${check.error}".trim()
                val available = hiddenCommandAvailable(response)
                RootProbe(available, if (available) "Root granted (UID 0). Low-latency command available (not listed in help)."
                    else "Root granted (UID 0). Wi-Fi control check: ${response.take(550).ifBlank { "No supported command response (exit ${check.code})." }}")
            }
        }
            .getOrElse { RootProbe(false, "Root granted (UID 0). Wi-Fi capability check failed: ${it.message}") }
        return capability.copy(detail = "${capability.detail}\nsu: ${SuLauncher.selected}")
    }
}

/** A root-owned lease: no heartbeat / closed stdin / signal -> release the override.
 * No boot hooks or detached root daemon. Commands are bounded with Android's timeout tool.
 */
class RootLease internal constructor(private val command: List<String>? = null) {
    private var process: Process? = null
    private val messages = LinkedBlockingQueue<String>()
    private val diagnostics = StringBuffer()

    fun start() {
        val child = if (command == null) SuLauncher.launch("export PATH=/system/bin:/system/xbin:\${PATH}; exec /system/bin/sh -c " + shellQuote(SCRIPT))
            else ProcessBuilder(command).redirectErrorStream(true).start()
        process = child
        thread(isDaemon = true, name = "root-lease-errors") {
            runCatching { child.errorStream.bufferedReader().useLines { lines -> lines.forEach { if (diagnostics.length < 1000) diagnostics.appendLine(it) } } }
        }
        thread(isDaemon = true, name = "root-lease-output") {
            runCatching { child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { if (it in setOf("READY", "APPLIED", "RESTORED", "RESTORE_FAILED", "APPLY_FAILED")) messages.offer(it)
                    else if (diagnostics.length < 1000) diagnostics.appendLine(it) }
            } }
            messages.offer("CLOSED")
        }
        check(messages.poll(60, TimeUnit.SECONDS) == "READY") { "Root watchdog could not start; WLL was not requested. $diagnostics" }
    }

    @Synchronized fun send(message: String) {
        require(message in setOf("apply", "keep", "stop"))
        val child = checkNotNull(process)
        check(child.isAlive) { "Root watchdog exited." }
        child.outputStream.write("$message\n".toByteArray())
        child.outputStream.flush()
    }

    fun apply() {
        send("apply")
        check(messages.poll(15, TimeUnit.SECONDS) == "APPLIED") { "Firmware rejected the low-latency request. Recovery is required. $diagnostics" }
    }

    fun isAlive() = process?.isAlive == true

    fun stop(): Boolean {
        runCatching { send("stop") }
        // Closing stdin also tells the shell to clean up if a write failed.
        runCatching { process?.outputStream?.close() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18)
        while (System.nanoTime() < deadline) {
            when (messages.poll(1, TimeUnit.SECONDS)) {
                "RESTORED" -> return true
                "RESTORE_FAILED", "CLOSED" -> return false
            }
        }
        return false // Keep the recovery journal; never report a failed restore as success.
    }

    companion object {
        internal fun shellQuote(text: String) = "'" + text.replace("'", "'\\''") + "'"
        val SCRIPT = """
            [ "${'$'}(id -u)" = 0 ] || exit 1
            command -v timeout >/dev/null 2>&1 || exit 1
            if ! timeout 8 cmd wifi help | grep -q force-low-latency-mode; then
                probe=${'$'}(timeout 8 cmd wifi force-low-latency-mode 2>&1)
                case "${'$'}probe" in
                    *"Argument expected after"*"force-low-latency-mode"*) ;;
                    *) echo "${'$'}probe" >&2; exit 1 ;;
                esac
            fi
            changed=0
            cleanup() {
                trap - EXIT HUP INT TERM
                if [ "${'$'}changed" = 1 ]; then
                    result=${'$'}(timeout 8 cmd wifi force-low-latency-mode disabled 2>&1)
                    code=${'$'}?
                    if [ "${'$'}code" = 0 ] && { [ -z "${'$'}result" ] || [ "${'$'}result" = Success ] || [ "${'$'}result" = success ] || [ "${'$'}result" = OK ] || [ "${'$'}result" = ok ]; }; then
                        echo RESTORED
                    else
                        echo "${'$'}result" >&2
                        echo RESTORE_FAILED
                    fi
                else
                    echo RESTORED
                fi
            }
            trap cleanup EXIT
            trap 'exit 1' HUP INT TERM
            echo READY
            while IFS= read -r -t 45 action; do
                case "${'$'}action" in
                    apply)
                        [ "${'$'}changed" = 0 ] || exit 1
                        changed=1
                        result=${'$'}(timeout 8 cmd wifi force-low-latency-mode enabled 2>&1)
                        code=${'$'}?
                        if [ "${'$'}code" = 0 ] && { [ -z "${'$'}result" ] || [ "${'$'}result" = Success ] || [ "${'$'}result" = success ] || [ "${'$'}result" = OK ] || [ "${'$'}result" = ok ]; }; then
                            echo APPLIED
                        else
                            echo "${'$'}result" >&2
                            echo APPLY_FAILED
                            exit 1
                        fi
                        ;;
                    keep) ;;
                    stop) exit 0 ;;
                    *) exit 1 ;;
                esac
            done
        """.trimIndent()
    }
}
