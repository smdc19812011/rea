package com.kvmcontrol.app

import com.kvmcontrol.app.data.ConnectionState
import com.kvmcontrol.app.data.ConnectionType
import com.kvmcontrol.app.data.KvmDevice
import com.kvmcontrol.app.data.KvmStats
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {

    @Test
    fun `KvmStats ramPercent calculates correctly`() {
        val stats = KvmStats(ramUsedMb = 4096, ramTotalMb = 8192)
        assertEquals(50f, stats.ramPercent, 0.01f)
    }

    @Test
    fun `KvmStats ramPercent returns 0 when total is 0`() {
        val stats = KvmStats(ramUsedMb = 100, ramTotalMb = 0)
        assertEquals(0f, stats.ramPercent, 0.001f)
    }

    @Test
    fun `KvmDevice default port is 5900`() {
        val device = KvmDevice("1", "My PC", "192.168.1.100", ConnectionType.NETWORK)
        assertEquals(5900, device.port)
    }

    @Test
    fun `KvmStats default connectionState is DISCONNECTED`() {
        val stats = KvmStats()
        assertEquals(ConnectionState.DISCONNECTED, stats.connectionState)
    }

    @Test
    fun `KvmStats ramPercent is 100 when fully used`() {
        val stats = KvmStats(ramUsedMb = 8192, ramTotalMb = 8192)
        assertEquals(100f, stats.ramPercent, 0.01f)
    }
}
