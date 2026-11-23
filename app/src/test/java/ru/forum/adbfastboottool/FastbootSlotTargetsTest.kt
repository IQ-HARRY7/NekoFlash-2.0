package ru.forum.adbfastboottool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [FastbootSlotTargetsTest.ScriptedUsbConnection::class])
class FastbootSlotTargetsTest {
    @Test fun aOnlyPartitionStaysLiteralForEverySlotChoice() {
        assertLiteralPartition("OKAYno")
    }

    @Test fun unsupportedHasSlotDoesNotRequireSlotTopology() {
        assertLiteralPartition("FAILunknown variable")
    }

    @Test fun mixedPartitionsResolveIndependentlyAndOtherUsesTheLiveSlot() {
        withProtocol(
            "getvar:has-slot:boot" to "OKAYyes",
            "getvar:slot-count" to "OKAY2",
            "getvar:current-slot" to "OKAYa",
            "getvar:has-slot:super" to "OKAYno",
            "getvar:has-slot:boot" to "OKAYyes",
            "getvar:slot-count" to "OKAY2",
            "getvar:current-slot" to "OKAYb",
            "getvar:has-slot:boot" to "OKAYyes",
            "getvar:slot-count" to "OKAY2"
        ) { protocol ->
            assertEquals(listOf("boot_b"), protocol.resolveSlotPartitionTargets("boot", "other"))
            assertEquals(listOf("super"), protocol.resolveSlotPartitionTargets("super", "all"))
            assertEquals(listOf("boot_a"), protocol.resolveSlotPartitionTargets("boot", "other"))
            assertEquals(listOf("boot_a", "boot_b"), protocol.resolveSlotPartitionTargets("boot", "all"))
        }
    }

    private fun assertLiteralPartition(response: String) {
        val choices = listOf(null, "a", "b", "all", "other")
        val exchanges = choices.map { "getvar:has-slot:recovery" to response }.toTypedArray()
        withProtocol(*exchanges) { protocol ->
            for (choice in choices) {
                assertEquals(listOf("recovery"), protocol.resolveSlotPartitionTargets("recovery", choice))
            }
        }
    }

    private fun withProtocol(vararg exchanges: Pair<String, String>, test: (FastbootProtocol) -> Unit) {
        val connection = Shadow.newInstanceOf(UsbDeviceConnection::class.java)
        val script = Shadow.extract<ScriptedUsbConnection>(connection)
        script.exchanges.addAll(exchanges)
        val usbManager = RuntimeEnvironment.getApplication().getSystemService(UsbManager::class.java)
        val protocol = FastbootProtocol(usbManager, Shadow.newInstanceOf(UsbDevice::class.java), {})

        // Start with an open synthetic session and exercise production GETVAR parsing and sequencing.
        ReflectionHelpers.setField(protocol, "connection", connection)
        ReflectionHelpers.setField(protocol, "endpointIn", endpoint(0x81))
        ReflectionHelpers.setField(protocol, "endpointOut", endpoint(0x01))
        ReflectionHelpers.setField(protocol, "fastbootInterface", Shadow.newInstanceOf(UsbInterface::class.java))
        ReflectionHelpers.setField(protocol, "sessionState", FastbootProtocol.SessionState.IDLE)

        test(protocol)
        assertEquals(emptyList<Pair<String, String>>(), script.exchanges.toList())
        assertEquals(null, script.pendingResponse)
        assertEquals(FastbootProtocol.SessionState.IDLE, protocol.currentSessionState)
    }

    private fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
        UsbEndpoint::class.java,
        ClassParameter.from(Int::class.javaPrimitiveType, address),
        ClassParameter.from(Int::class.javaPrimitiveType, UsbConstants.USB_ENDPOINT_XFER_BULK),
        ClassParameter.from(Int::class.javaPrimitiveType, 64),
        ClassParameter.from(Int::class.javaPrimitiveType, 0)
    )

    @Implements(UsbDeviceConnection::class)
    class ScriptedUsbConnection {
        val exchanges = ArrayDeque<Pair<String, String>>()
        var pendingResponse: String? = null
            private set

        @Implementation
        protected fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int =
            bulkTransfer(endpoint, buffer, 0, length, timeout)

        @Implementation
        protected fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int, timeout: Int): Int {
            if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                check(pendingResponse == null) { "Previous USB response was not read" }
                val command = String(buffer, offset, length, Charsets.US_ASCII)
                val (expected, response) = checkNotNull(exchanges.removeFirstOrNull()) { "Unexpected USB command: $command" }
                assertEquals(expected, command)
                pendingResponse = response
                return length
            }

            val response = checkNotNull(pendingResponse) { "USB IN read without a command" }
            val bytes = response.toByteArray(Charsets.US_ASCII)
            check(bytes.size <= length)
            bytes.copyInto(buffer, destinationOffset = offset)
            pendingResponse = null
            return bytes.size
        }
    }
}
