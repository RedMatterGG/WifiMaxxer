package com.wifimaxxer

import org.junit.Assert.*
import org.junit.Test

class PingTest {
    @Test fun samplesLossAndJitter() {
        val result = parsePing("64 bytes time=2.0 ms\n64 bytes time=4.0 ms\n64 bytes time<1 ms", 4)
        assertEquals(3, result.received)
        assertEquals(25.0, result.loss, .001)
        assertEquals(4.0, result.p95!!, .001)
        assertEquals(2.5, result.jitter!!, .001)
        assertNull(parsePing("100% packet loss", 10).average)
    }
}
