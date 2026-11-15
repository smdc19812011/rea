package app.switchboard.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class DevicesTest {
    @Test fun hexAndTextEncoding() {
        assertArrayEquals(byteArrayOf(0x01, 0xA0.toByte(), 0xFF.toByte()), decode("01 a0 FF", "hex"))
        assertArrayEquals(byteArrayOf(0x0D, 0x0A), decode("0x0D,0x0A", "hex"))
        assertArrayEquals("AT\r\n".toByteArray(), decode("AT\r\n", null))
        assertTrue(runCatching { decode("ABC", "hex") }.isFailure)
        assertEquals("Received 3 bytes (hex):\n01 A0 FF", describeBytes(byteArrayOf(1, 0xA0.toByte(), 0xFF.toByte())))
        assertEquals("Received 2 bytes (text):\nOK", describeBytes("OK".toByteArray()))
        assertEquals("Nothing received within the timeout.", describeBytes(ByteArray(0)))
    }

    @Test fun lanAddressesCountAsLocal() {
        listOf("192.168.1.20", "10.0.0.5", "172.20.1.1", "169.254.3.4", "esp32.local", "localhost").forEach { assertTrue(it, isLocalHost(it)) }
        listOf("8.8.8.8", "172.32.0.1", "example.com", "192.169.1.1").forEach { assertFalse(it, isLocalHost(it)) }
    }

    @Test fun tcpRoundTrip() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) { server.accept().use { s -> val b = ByteArray(64); val n = s.getInputStream().read(b); s.getOutputStream().write("echo:".toByteArray() + b.copyOf(n)) } }
        val t = TcpSession(InetSocketAddress("127.0.0.1", server.localPort))
        t.write("PING\n".toByteArray(), null)
        assertEquals("echo:PING\n", t.read(2000, 4096, null).decodeToString())
        assertEquals(0, t.read(200, 4096, null).size) // quiet line returns empty after timeout
        t.close(); server.close()
    }

    @Test fun udpRoundTrip() = runBlocking {
        val server = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) { val p = DatagramPacket(ByteArray(64), 64); server.receive(p); server.send(DatagramPacket(byteArrayOf(0x42) + p.data.copyOf(p.length), p.length + 1, p.socketAddress)) }
        val u = UdpSession(InetSocketAddress("127.0.0.1", server.localPort))
        u.write(byteArrayOf(1, 2, 3), null)
        assertArrayEquals(byteArrayOf(0x42, 1, 2, 3), u.read(2000, 4096, null))
        u.close(); server.close()
    }
}
