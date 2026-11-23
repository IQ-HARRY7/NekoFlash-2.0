package ru.forum.adbfastboottool

import io.github.rhythmcache.adb.crypto.X509Generator
import io.github.rhythmcache.adb.pairing.ConnectionRole
import io.github.rhythmcache.adb.pairing.PairingConnection
import io.github.rhythmcache.adb.pairing.PairingIdentity
import io.github.rhythmcache.adb.pairing.PairingTlsSocket
import io.github.rhythmcache.adb.pairing.PeerInfo
import io.github.rhythmcache.adb.pairing.PeerInfoType
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbWirelessClientTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pairsOpensAdbShellAndPushesDocumentsOverTls() {
        val hostKeys = AdbKeyStore(temporary.newFolder("host")) {}
        val daemonKeys = AdbKeyStore(temporary.newFolder("daemon")) {}.getOrCreateKeyPair()
        val daemonIdentity = PairingIdentity(
            daemonKeys,
            X509Generator.toPem(X509Generator.generate(daemonKeys)),
            X509Generator.privateKeyToPem(daemonKeys.private)
        )
        val host = "127.0.0.1"
        val address = InetAddress.getByName(host)
        val data = byteArrayOf(0x00, 0x34, 0x77, 0xff.toByte())
        val directory = "/sdcard/my folder"
        val worker = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, address).use { server ->
                val device = worker.submit<PeerInfo> {
                    server.acceptWithTimeout().use { socket ->
                        PairingConnection.run(
                            socket, ConnectionRole.SERVER, "012345".toByteArray(),
                            PeerInfo(PeerInfoType.ADB_DEVICE_GUID, "adb-test-device".toByteArray()), daemonIdentity
                        ).getOrThrow().peerInfo
                    }
                }
                assertEquals("adb-test-device", AdbWirelessClient(hostKeys).pair(host, server.localPort, "012345"))
                val peer = device.get(10, TimeUnit.SECONDS)
                assertEquals(PeerInfoType.ADB_RSA_PUB_KEY, peer.type)
                val expectedKey = hostKeys.publicKeyPayload()
                assertArrayEquals(expectedKey, peer.data.copyOf(expectedKey.size))
            }
            ServerSocket(0, 1, address).use { server ->
                val device = worker.submit {
                    server.acceptWithTimeout().use { socket ->
                        val plain = DataInputStream(socket.getInputStream())
                        assertEquals("CNXN", plain.readPacket().command)
                        socket.getOutputStream().sendPacket("STLS", arg0 = 0x01000000)
                        assertEquals("STLS", plain.readPacket().command)
                        val tls = PairingTlsSocket.connectAsServer(socket, daemonIdentity.certPem, daemonIdentity.privateKeyPem)
                        try {
                            val input = DataInputStream(tls.inputStream())
                            val output = tls.outputStream()
                            output.sendPacket("CNXN", 0x01000001, 4096, "device::\u0000")
                            val open = input.readPacket()
                            assertEquals("OPEN", open.command)
                            assertEquals("shell:echo wifi\u0000", open.payload)
                            output.sendPacket("OKAY", 1, open.arg0)
                            output.sendPacket("WRTE", 1, open.arg0, "wifi\n")
                            assertEquals("OKAY", input.readPacket().command)
                            output.sendPacket("CLSE", 1, open.arg0)
                            assertEquals("CLSE", input.readPacket().command)
                            assertSyncPush(input, output, 2, "$directory/фото с пробелом.bin", data)
                            assertSyncPush(input, output, 3, "$directory/empty.txt", byteArrayOf())
                        } finally {
                            tls.close()
                        }
                    }
                }
                val logs = CopyOnWriteArrayList<String>()
                val protocol = AdbProtocol(hostKeys, host, server.localPort, { logs.add(it) }, rawShellLogsDir = temporary.newFolder("logs"))
                try {
                    assertTrue(logs.joinToString("\n"), protocol.connect())
                    assertEquals(AdbProtocol.PeerMode.DEVICE, protocol.peerMode)
                    assertEquals("wifi\n", protocol.captureShellCommand("echo wifi").stdout)
                    val documents = listOf(
                        AdbProtocol.PushSource("фото с пробелом.bin", null, 10) { ByteArrayInputStream(data) },
                        AdbProtocol.PushSource("empty.txt", 0, 10) { ByteArrayInputStream(byteArrayOf()) }
                    )
                    assertTrue(logs.joinToString("\n"), protocol.pushDocuments(documents, directory) {})
                    device.get(10, TimeUnit.SECONDS)
                } finally {
                    protocol.disconnect()
                }
            }
        } finally {
            worker.shutdownNow()
        }
    }

    private fun assertSyncPush(
        input: DataInputStream, output: OutputStream, streamId: Int, name: String, data: ByteArray
    ) {
        val open = input.readPacket()
        assertEquals("OPEN", open.command)
        assertEquals("sync:\u0000", open.payload)
        output.sendPacket("OKAY", streamId, open.arg0)
        val send = input.readPacket()
        assertEquals("WRTE", send.command)
        val path = "$name,420".toByteArray()
        assertArrayEquals(syncMessage("SEND", path.size, path), send.rawPayload)
        output.sendPacket("OKAY", streamId, open.arg0)
        if (data.isNotEmpty()) {
            val chunk = input.readPacket()
            assertEquals("WRTE", chunk.command)
            assertArrayEquals(syncMessage("DATA", data.size, data), chunk.rawPayload)
            output.sendPacket("OKAY", streamId, open.arg0)
        }
        val done = input.readPacket()
        assertEquals("WRTE", done.command)
        assertArrayEquals(syncMessage("DONE", 10), done.rawPayload)
        output.sendPacket("OKAY", streamId, open.arg0)
        output.sendPacket("WRTE", streamId, open.arg0, "OKAY\u0000\u0000\u0000\u0000")
        assertEquals("OKAY", input.readPacket().command)
        assertEquals("CLSE", input.readPacket().command)
    }

    private fun syncMessage(command: String, value: Int, data: ByteArray = byteArrayOf()): ByteArray =
        ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(command.toByteArray()).putInt(value).put(data).array()

    private fun ServerSocket.acceptWithTimeout(): Socket = accept().apply { soTimeout = 10_000 }

    private data class Packet(val command: String, val arg0: Int, val rawPayload: ByteArray) {
        val payload: String get() = rawPayload.toString(Charsets.UTF_8)
    }

    private fun DataInputStream.readPacket(): Packet {
        val bytes = ByteArray(24).also(::readFully)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val arg0 = header.getInt(4)
        val payload = ByteArray(header.getInt(12)).also(::readFully)
        return Packet(String(bytes, 0, 4, Charsets.US_ASCII), arg0, payload)
    }

    private fun OutputStream.sendPacket(command: String, arg0: Int, arg1: Int = 0, body: String = "") {
        val payload = body.toByteArray()
        val code = ByteBuffer.wrap(command.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).int
        write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(code).putInt(arg0).putInt(arg1).putInt(payload.size).putInt(0).putInt(code.inv()).array())
        write(payload)
        flush()
    }

    @Test fun rebootClosesWirelessTransportAfterServiceAcknowledgement() {
        checkRebootDisconnect(acknowledgeService = true)
    }

    @Test fun rebootClosesWirelessTransportBeforeServiceAcknowledgement() {
        checkRebootDisconnect(acknowledgeService = false)
    }

    private fun checkRebootDisconnect(acknowledgeService: Boolean) {
        val serviceReturned = CountDownLatch(1)
        withWirelessDaemon(
            serve = { input, output ->
                val reboot = input.readPacket()
                assertEquals("OPEN", reboot.command)
                assertEquals("reboot:\u0000", reboot.payload)
                if (acknowledgeService) {
                    output.sendPacket("OKAY", 1, reboot.arg0)
                    output.sendPacket("CLSE", 1, reboot.arg0)
                    assertEquals("CLSE", input.readPacket().command)
                    assertTrue(serviceReturned.await(8, TimeUnit.SECONDS))
                }
            },
            exercise = { protocol ->
                val expectedDisconnect = CountDownLatch(1)
                val failures = CopyOnWriteArrayList<String>()
                protocol.onExpectedRebootDisconnect = { expectedDisconnect.countDown() }
                protocol.onTransportFailure = { code, detail -> failures.add("$code: $detail") }
                try {
                    assertTrue(protocol.runService("reboot:"))
                } finally {
                    serviceReturned.countDown()
                }
                assertTrue("Reboot EOF was not reported as expected: $failures", expectedDisconnect.await(8, TimeUnit.SECONDS))
                assertFalse(protocol.isConnected)
                assertTrue(failures.isEmpty())
            }
        )
    }

    @Test fun silentMetadataProbeReturnsBeforeTheFollowingCommand() {
        withWirelessDaemon(
            serve = { input, output ->
                val metadata = input.readPacket()
                assertEquals("OPEN", metadata.command)
                assertEquals("shell:getprop ro.build.version.release\u0000", metadata.payload)
                val next = input.readPacket()
                assertEquals("OPEN", next.command)
                assertEquals("shell:echo ready\u0000", next.payload)
                output.sendPacket("OKAY", 2, next.arg0)
                output.sendPacket("WRTE", 2, next.arg0, "ready\n")
                assertEquals("OKAY", input.readPacket().command)
                output.sendPacket("CLSE", 2, next.arg0)
                assertEquals("CLSE", input.readPacket().command)
            },
            exercise = { protocol ->
                assertFalse(protocol.captureShellCommand("getprop ro.build.version.release", readBudgetMs = 100).success)
                val next = protocol.captureShellCommand("echo ready")
                assertTrue(next.success)
                assertEquals("ready\n", next.stdout)
            }
        )
    }

    private fun withWirelessDaemon(
        serve: (DataInputStream, OutputStream) -> Unit,
        exercise: (AdbProtocol) -> Unit
    ) {
        val hostKeys = AdbKeyStore(temporary.newFolder()) {}
        val daemonKey = AdbKeyStore(temporary.newFolder()) {}.getOrCreateKeyPair()
        val certificate = X509Generator.toPem(X509Generator.generate(daemonKey))
        val privateKey = X509Generator.privateKeyToPem(daemonKey.private)
        val address = InetAddress.getByName("127.0.0.1")
        val worker = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, address).use { server ->
                val device = worker.submit {
                    server.acceptWithTimeout().use { socket ->
                        val plain = DataInputStream(socket.getInputStream())
                        assertEquals("CNXN", plain.readPacket().command)
                        socket.getOutputStream().sendPacket("STLS", 0x01000000)
                        assertEquals("STLS", plain.readPacket().command)
                        val tls = PairingTlsSocket.connectAsServer(socket, certificate, privateKey)
                        try {
                            val output = tls.outputStream()
                            output.sendPacket("CNXN", 0x01000001, 4096, "device::\u0000")
                            serve(DataInputStream(tls.inputStream()), output)
                        } finally {
                            tls.close()
                        }
                    }
                }
                val logs = CopyOnWriteArrayList<String>()
                val protocol = AdbProtocol(
                    hostKeys, address.hostAddress!!, server.localPort, logs::add, rawShellLogsDir = temporary.newFolder()
                )
                try {
                    assertTrue(logs.joinToString("\n"), protocol.connect())
                    exercise(protocol)
                    device.get(10, TimeUnit.SECONDS)
                } finally {
                    protocol.disconnect()
                }
            }
        } finally {
            worker.shutdownNow()
        }
    }
}
