package ru.forum.adbfastboottool

import io.github.rhythmcache.adb.crypto.X509Generator
import io.github.rhythmcache.adb.pairing.ConnectionRole
import io.github.rhythmcache.adb.pairing.PairingConnection
import io.github.rhythmcache.adb.pairing.PairingIdentity
import io.github.rhythmcache.adb.pairing.PairingTlsSocket
import io.github.rhythmcache.adb.pairing.PeerInfo
import io.github.rhythmcache.adb.pairing.PeerInfoBuilder
import io.github.rhythmcache.adb.pairing.PeerInfoType
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

private const val HANDSHAKE_TIMEOUT_MS = 10_000

/** Blocking Android 11+ Wireless debugging pairing. Call on an I/O thread. */
class AdbWirelessClient(private val keyStore: AdbKeyStore) {
    fun pair(host: String, port: Int, code: String): String {
        val password = code.toByteArray(Charsets.UTF_8)
        try {
            val identity = wirelessIdentity(keyStore)
            val publicKey = PeerInfo(PeerInfoType.ADB_RSA_PUB_KEY, keyStore.publicKeyPayload())
            return Socket().use { socket ->
                socket.connectForAdb(host, port)
                val result = PairingConnection.run(socket, ConnectionRole.CLIENT, password, publicKey, identity)
                    .getOrThrow()
                PeerInfoBuilder.extractDeviceGuid(result.peerInfo)
            }
        } finally {
            password.fill(0)
        }
    }
}

/** One ADB packet byte stream. Only CNXN and STLS are sent before upgrading to TLS. */
internal class AdbWirelessTransport(private val host: String, private val port: Int) : Closeable {
    private val socket = Socket()
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream

    fun connect() {
        socket.connectForAdb(host, port)
        socket.keepAlive = true
        input = DataInputStream(socket.getInputStream())
        output = socket.getOutputStream()
    }

    fun upgradeToTls(keyStore: AdbKeyStore) {
        val identity = wirelessIdentity(keyStore)
        // AOSP's ADB host accepts the device's self-signed certificate on this socket.
        // The daemon authenticates the enrolled host RSA public key in the TLS client certificate.
        val tls = PairingTlsSocket.connectAsClient(socket, identity.certPem, identity.privateKeyPem)
        input = DataInputStream(tls.inputStream())
        output = tls.outputStream()
    }

    fun finishHandshake() {
        // Idle connections may have no traffic. Closing the socket wakes the single reader.
        socket.soTimeout = 0
    }

    fun readFully(buffer: ByteArray) = input.readFully(buffer)

    fun write(header: ByteArray, payload: ByteArray) {
        output.write(header)
        if (payload.isNotEmpty()) output.write(payload)
        output.flush()
    }

    override fun close() = socket.close()
}

private fun wirelessIdentity(keyStore: AdbKeyStore): PairingIdentity {
    val keyPair = keyStore.getOrCreateKeyPair()
    return PairingIdentity(
        keyPair,
        X509Generator.toPem(X509Generator.generate(keyPair)),
        X509Generator.privateKeyToPem(keyPair.private)
    )
}

private fun Socket.connectForAdb(host: String, port: Int) {
    val address = InetSocketAddress(host, port)
    if (address.isUnresolved) throw IOException("Cannot resolve Wireless debugging host")
    connect(address, HANDSHAKE_TIMEOUT_MS)
    soTimeout = HANDSHAKE_TIMEOUT_MS
    tcpNoDelay = true
}
