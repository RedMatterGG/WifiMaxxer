package com.wifimaxxer

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RootLeaseTest {
    private fun fixture(failApply: Boolean = false, failRestore: Boolean = false, hiddenHelp: Boolean = false): Pair<RootLease, File> {
        val bash = listOf("C:/Program Files/Git/bin/bash.exe", "/bin/bash").firstOrNull { File(it).exists() }
        assumeTrue("Lease integration tests require Bash", bash != null)
        val log = File.createTempFile("wifi-lease-", ".log").apply { deleteOnExit() }
        val path = log.absolutePath.replace('\\', '/').replace("'", "'\\''")
        val fixture = """
            id() { echo 0; }
            timeout() { shift; "${'$'}@"; }
            cmd() {
                if [ "${'$'}2" = help ]; then
                    if [ '$hiddenHelp' = false ]; then echo force-low-latency-mode; fi
                    return
                fi
                if [ "${'$'}#" = 2 ]; then echo 'Argument expected after force-low-latency-mode'; return 1; fi
                echo "${'$'}3" >> '$path'
                if [ "${'$'}3" = enabled ] && [ '$failApply' = true ]; then echo 'Command execution failed'; fi
                if [ "${'$'}3" = disabled ] && [ '$failRestore' = true ]; then echo 'Command execution failed'; fi
            }
        """.trimIndent()
        // Windows command-line quoting changes embedded Bash quotes; execute a file instead.
        val script = File.createTempFile("wifi-lease-", ".sh").apply {
            deleteOnExit()
            writeText(fixture + "\n" + RootLease.SCRIPT.replace("-t 45", "-t 1"))
        }
        return RootLease(listOf(bash!!, script.absolutePath.replace('\\', '/'))) to log
    }

    @Test fun appliesAndRestoresOnExplicitStop() {
        val (lease, log) = fixture()
        lease.start(); lease.apply()
        assertTrue(lease.stop())
        assertEquals(listOf("enabled", "disabled"), log.readLines())
    }

    @Test fun acceptsCommandOmittedFromFirmwareHelp() {
        val (lease, log) = fixture(hiddenHelp = true)
        lease.start(); lease.apply()
        assertTrue(lease.stop())
        assertEquals(listOf("enabled", "disabled"), log.readLines())
    }

    @Test fun missedHeartbeatRestoresWithoutAppCommand() {
        val (lease, log) = fixture()
        lease.start(); lease.apply()
        Thread.sleep(1600)
        assertTrue(lease.stop())
        assertEquals(listOf("enabled", "disabled"), log.readLines())
    }

    @Test fun rejectedApplyStillAttemptsRollback() {
        val (lease, log) = fixture(failApply = true)
        lease.start()
        assertTrue(runCatching { lease.apply() }.isFailure)
        assertTrue(lease.stop())
        assertEquals(listOf("enabled", "disabled"), log.readLines())
    }

    @Test fun rejectedRestoreIsNotReportedAsSuccess() {
        val (lease, _) = fixture(failRestore = true)
        lease.start(); lease.apply()
        assertFalse(lease.stop())
    }
}
