package com.wifimaxxer

import org.junit.Assert.*
import org.junit.Test

class CustomTuningTest {
    @Test fun offloadParserDoesNotTreatFixedOrRequestedFeaturesAsWritable() {
        val parsed = parseOffloads("Features for wlan0:\nrx-checksumming: on\n  tx-tcp-segmentation: off [fixed]\ngeneric-receive-offload: off [requested on]\nrx-gro-hw: off\n")
        assertEquals(4, parsed.size)
        assertEquals(Offload(true, false), parsed["rx-checksumming"])
        assertTrue(parsed.getValue("tx-tcp-segmentation").fixed)
        assertTrue(parsed.getValue("generic-receive-offload").fixed)
        assertFalse(parsed.getValue("rx-gro-hw").enabled)
        assertTrue(parseOffloads("Cannot get device feature names: Permission denied").isEmpty())
    }

    @Test fun controlsAreConservativelyGroupedAndHaveGuidance() {
        assertEquals(setOf("ipreach", "nopowersave", "tcp_rcvbuf_auto"), tweaks.filter { it.tier == SafetyTier.BASIC }.map { it.id }.toSet())
        assertTrue(tweaks.filter { it.tier == SafetyTier.ADVANCED }.all { it.risk.isNotBlank() && it.source.isNotBlank() })
    }

    @Test fun frameworkResponsesAreParsedWithoutGuessing() {
        assertEquals(true, parseIpReachability("IPREACH_DISCONNECT state is true"))
        assertEquals(false, parseIpReachability("IPREACH_DISCONNECT state is false"))
        assertNull(parseIpReachability("unknown"))
        assertEquals(3000, parseRssiPollInterval("WifiGlobals.getPollRssiIntervalMillis() = 3000"))
        assertEquals(1000, parseRssiPollInterval("ClientModeImpl.mPollRssiIntervalMsecs = 1000"))
        assertNull(parseRssiPollInterval("permission denied"))
        assertEquals(WifiVerboseLogging(2, true), parseWifiVerboseLogging("mVerboseLoggingLevel=2"))
        assertEquals(WifiVerboseLogging(1, false), parseWifiVerboseLogging("WM_VERBOSE=enabled"))
        assertEquals(WifiVerboseLogging(0, true), parseWifiVerboseLogging("disabled"))
        assertNull(parseWifiVerboseLogging("permission denied"))
    }

    @Test fun advancedControlsHaveRealBackends() {
        val tcp = tweaks.filter { it.sysctl != null }
        assertEquals(setOf("tcp_rcvbuf_auto", "tcp_autocork", "tcp_metrics", "tcp_idle"), tcp.map { it.id }.toSet())
        assertTrue(tweaks.all { it.feature != null || it.sysctl != null || it.id in setOf("ipreach", "nopowersave", "rssi_fast", "wifi_verbose", "rps", "xps") })
        assertTrue(tweaks.single { it.id == "wifi_verbose" }.persistsAcrossReboot)
        assertNull(tweaks.single { it.id == "rps" }.recommended)
        assertNull(tweaks.single { it.id == "xps" }.recommended)
    }

    @Test fun cpuListsAndQueueMasksArePortableAcrossCoreCounts() {
        assertEquals(listOf(0, 1, 2, 3, 6, 8, 9), parseCpuList("0-3,6,8-9"))
        assertEquals("1,80000001", cpuMask(listOf(0, 31, 32)))
        assertEquals("f", normalizeCpuMask("00000000,0000000f"))
        assertEquals(mapOf("rx-0" to "55", "rx-1" to "aa"), balancedCpuMasks(listOf("rx-1", "rx-0"), (0..7).toList()))
        assertEquals(mapOf("rx-0" to "f", "rx-1" to "0"), parseCpuSteering("WM_STEER_rps=rx-0:0000000f\nWM_STEER_rps=rx-1:0\n", "rps"))
    }

    @Test fun bondingParserReadsExactOemKeysAndUsesLastValue() {
        val values = parseBondingConfig("""
            # gChannelBondingMode24GHz=1
            gChannelBondingMode24GHz=0
            gChannelBondingMode5GHz=1
            gChannelBondingMode24GHz=1 # override
        """.trimIndent())
        assertEquals(true to true, values)
        assertEquals(null to null, parseBondingConfig("BandCapability=0"))
    }

    @Test fun bondingEditBacksUpAndVerifiesBeforeWritingTheLiveFile() {
        val script = bondingApplyScript(true, false)
        val firstBackupVerification = script.indexOf("cmp -s \"\$file\" \"\$temp\"")
        val firstLiveWrite = script.indexOf("cp -p \"\$stage\" \"\$file\"")
        assertTrue(firstBackupVerification >= 0)
        assertTrue(firstLiveWrite > firstBackupVerification)
        assertTrue(script.contains("/data/adb/wifimaxxer/bonding"))
        assertTrue(script.contains("sha256sum"))
        assertTrue(script.contains("cp -p \"\$pre\" \"\$file\""))
        assertFalse(script.contains("persist."))
    }

    @Test fun bondingRestoreUsesOnlyTheChecksumVerifiedOriginal() {
        assertTrue(bondingRestoreScript.indexOf("sha256sum") < bondingRestoreScript.indexOf("cp -p \"\$backup/original.ini\" \"\$file\""))
        assertTrue(bondingRestoreScript.contains("cmp -s \"\$backup/original.ini\" \"\$file\""))
    }

    @Test fun bandwidthSliderUsesFullRangeAndPreservesLowSpeedControl() {
        assertEquals(0f, limitToSlider(5, 5, 9608), 0.0001f)
        assertEquals(1f, limitToSlider(9608, 5, 9608), 0.0001f)
        assertEquals(5, sliderToLimit(0f, 5, 9608))
        assertEquals(9608, sliderToLimit(1f, 5, 9608))
        assertTrue(sliderToLimit(0.25f, 5, 9608) < 100)
    }

    @Test fun bandwidthScriptsOnlyOwnTheirFiltersAndValidateTheInterface() {
        val script = BandwidthLimiter.applyScript("wlan0", 25, 8)
        assertTrue(script.contains("ingress pref 62001"))
        assertTrue(script.contains("egress pref 62002"))
        assertTrue(script.contains("rate 25mbit"))
        assertTrue(script.contains("rate 8mbit"))
        assertTrue(script.contains("matchall"))
        assertTrue(script.contains("u32 match u32 0 0"))
        assertTrue(script.contains("trap cleanup"))
        try {
            BandwidthLimiter.applyScript("wlan0; reboot", 5, 1)
            fail("Unsafe interface name was accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun wifiAndCellularBandwidthFiltersUseIndependentPriorities() {
        val wifi = BandwidthLimiter.applyScript("wlan0", 25, 8, BandwidthTarget.WIFI)
        val cellular = BandwidthLimiter.applyScript("rmnet_data0", 40, 10, BandwidthTarget.CELLULAR)
        assertTrue(wifi.contains("ingress pref 62001"))
        assertTrue(wifi.contains("egress pref 62002"))
        assertTrue(cellular.contains("ingress pref 62003"))
        assertTrue(cellular.contains("egress pref 62004"))
        assertFalse(cellular.contains("pref 62001"))
        assertTrue(BandwidthLimiter.restoreScript("rmnet_data0", false, BandwidthTarget.CELLULAR).contains("pref 62003"))
    }

    @Test fun persistentLogPrefixesEveryPhysicalLineWithTheSameTimestamp() {
        assertEquals(
            listOf("[2026-09-07 10:11:12.123 +03:00] first", "[2026-09-07 10:11:12.123 +03:00] second"),
            formatLogLines("first\r\nsecond", "2026-09-07 10:11:12.123 +03:00")
        )
    }

    @Test fun origamiNetworkControlsMatchTheSourcePathsAndValueMenus() {
        assertEquals(10, origamiNetworkSettings.size)
        assertEquals("/proc/sys/net/ipv4/tcp_congestion_control", origamiNetworkSettings.first().path)
        assertEquals(setOf("0", "1", "2"), origamiNetworkSettings.single { it.id == "tcp_ecn" }.options.map { it.value }.toSet())
        assertEquals(setOf("0", "1", "2", "3"), origamiNetworkSettings.single { it.id == "tcp_fastopen" }.options.map { it.value }.toSet())
        assertEquals(setOf("0", "1", "2"), origamiNetworkSettings.single { it.id == "bpf_jit_harden" }.options.map { it.value }.toSet())
        assertEquals(KernelValueRange(128, 32400, 2, "seconds"), origamiNetworkSettings.single { it.id == "tcp_keepalive_time" }.range)
    }

    @Test fun origamiValuesAreValidatedBeforeEnteringARootCommand() {
        val congestion = origamiNetworkSettings.single { it.id == "tcp_congestion" }
        assertNull(origamiValueError(congestion, "cubic", listOf("reno", "cubic")))
        assertNotNull(origamiValueError(congestion, "cubic;reboot", emptyList()))
        assertNotNull(origamiValueError(congestion, "bbr", listOf("reno", "cubic")))
        val backlog = origamiNetworkSettings.single { it.id == "tcp_max_syn_backlog" }
        assertNull(origamiValueError(backlog, "128"))
        assertNull(origamiValueError(backlog, "32400"))
        assertNotNull(origamiValueError(backlog, "127"))
        assertNotNull(origamiValueError(backlog, "129"))
    }

    @Test fun origamiScanParserKeepsValuesAndWritableStateSeparate() {
        val (values, available) = parseOrigamiNetworkScan("""
            WM_ORIGAMI_tcp_congestion=1:cubic
            WM_ORIGAMI_tcp_ecn=0:2
            WM_ORIGAMI_AVAILABLE=reno cubic bbr
        """.trimIndent())
        assertEquals("cubic" to true, values["tcp_congestion"])
        assertEquals("2" to false, values["tcp_ecn"])
        assertEquals(listOf("reno", "cubic", "bbr"), available)
    }
}
