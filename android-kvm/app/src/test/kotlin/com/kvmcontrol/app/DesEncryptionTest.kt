package com.kvmcontrol.app

import com.kvmcontrol.app.network.Des
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DesEncryptionTest {

    @Test
    fun `encrypt produces 16-byte output for 16-byte challenge`() {
        val key = "password".toByteArray()
        val challenge = ByteArray(16) { it.toByte() }
        val result = Des.encrypt(key, challenge)
        assertEquals(16, result.size)
    }

    @Test
    fun `encrypt is deterministic`() {
        val key = "testkey!".toByteArray()
        val challenge = ByteArray(16) { (it * 3).toByte() }
        val r1 = Des.encrypt(key, challenge)
        val r2 = Des.encrypt(key, challenge)
        assert(r1.contentEquals(r2))
    }

    @Test
    fun `different keys produce different output`() {
        val challenge = ByteArray(16) { it.toByte() }
        val r1 = Des.encrypt("password".toByteArray(), challenge)
        val r2 = Des.encrypt("passw0rd".toByteArray(), challenge)
        assertNotEquals(r1.toList(), r2.toList())
    }

    @Test
    fun `different challenges produce different output`() {
        val key = "mykey123".toByteArray()
        val r1 = Des.encrypt(key, ByteArray(16) { it.toByte() })
        val r2 = Des.encrypt(key, ByteArray(16) { (it + 1).toByte() })
        assertNotEquals(r1.toList(), r2.toList())
    }
}
