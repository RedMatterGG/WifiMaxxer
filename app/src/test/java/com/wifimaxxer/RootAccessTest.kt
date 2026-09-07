package com.wifimaxxer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class RootAccessTest {
    @Test fun rootIdentityIsIndependentOfWifiHelp() {
        RootAccess.requireRoot(RootResult(0, "manager banner\nWM_UID=0\n", "informational manager message"))
        assertFalse(RootAccess.wifiCapability(RootResult(0, "Wi-Fi help without this control")).lowLatencyAvailable)
        assertTrue(RootAccess.wifiCapability(RootResult(0, "  force-low-latency-mode enabled|disabled\n  Example error: exception")).lowLatencyAvailable)
        assertFalse(RootAccess.wifiCapability(RootResult(1, "  force-low-latency-mode enabled|disabled")).lowLatencyAvailable)
        assertTrue(runCatching { RootAccess.requireRoot(RootResult(0, "WM_UID=2000\n")) }.isFailure)
        assertTrue(runCatching { RootAccess.requireRoot(RootResult(1, "WM_UID=0\n", "denied")) }.isFailure)
        assertTrue(runCatching { RootAccess.requireRoot(RootResult(0, "0\n")) }.isFailure)
        assertTrue(RootAccess.hiddenCommandAvailable("java.lang.IllegalArgumentException: Argument expected after force-low-latency-mode"))
        assertFalse(RootAccess.hiddenCommandAvailable("Unknown command: force-low-latency-mode"))
        assertFalse(RootAccess.hiddenCommandAvailable("Permission denied"))
    }

    @Test fun launcherFallsBackOnMissingExecutableButDoesNotRetryDeniedProcesses() {
        val attempts = mutableListOf<List<String>>()
        val denied = object : Process() {
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream() = ByteArrayInputStream("denied".toByteArray())
            override fun waitFor() = 1
            override fun exitValue() = 1
            override fun destroy() {}
        }
        val result = SuLauncher.launch("id") { args ->
            attempts.add(args)
            if (attempts.size == 1) throw IOException("No such file")
            denied
        }
        assertSame(denied, result)
        assertEquals(2, attempts.size)
        assertEquals(listOf("-c", "id"), attempts.last().drop(1))
    }

    @Test fun harmlessStderrDoesNotTurnSuccessfulWifiCommandIntoFailure() {
        RootResult(0, "", "manager: starting shell").checkedEmpty()
        RootResult(0, "Success\n").checkedEmpty()
        assertTrue(runCatching { RootResult(0, "", "Permission denied").checkedEmpty() }.isFailure)
        assertTrue(runCatching { RootResult(0, "Command execution failed").checkedEmpty() }.isFailure)
    }
    @Test fun rejectsShellFailuresEvenWithZeroExitCode() {
        for (result in listOf(RootResult(0, "Command execution failed"), RootResult(0, "Permission denied"),
            RootResult(0, "java.lang.SecurityException"), RootResult(127, "su: not found"))) {
            assertTrue(runCatching { result.checked() }.isFailure)
        }
        assertEquals("", RootResult(0, "").checked())
    }

    @Test fun protocolRejectsUntrustedShellInput() {
        assertTrue(runCatching { RootLease().send("keep; reboot") }.exceptionOrNull() is IllegalArgumentException)
    }
}
