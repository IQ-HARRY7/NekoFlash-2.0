package ru.forum.adbfastboottool

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AdbDeviceDirectoryTest {
    @Test fun readsImmediateEntriesWithOriginalNamesAndFoldersFirst() {
        val response = dent("фото с пробелом.jpg", directory = false) +
            dent(".", directory = true) + dent("..", directory = true) +
            dent("downloads", directory = true) + dent("a'picture.png", directory = false) +
            dent("sdcard", directory = false, symlink = true) + done()

        assertEquals(
            listOf(
                AdbDeviceDirectory.Entry("downloads", true),
                AdbDeviceDirectory.Entry("a'picture.png", false),
                AdbDeviceDirectory.Entry("sdcard", isDirectory = false, isSymlink = true),
                AdbDeviceDirectory.Entry("фото с пробелом.jpg", false)
            ),
            read(response)
        )
    }

    @Test fun distinguishesDoneFromAnInterruptedOrRejectedListing() {
        val emptyDirectory = dent(".", directory = true) + dent("..", directory = true) + done()
        assertEquals(emptyList<AdbDeviceDirectory.Entry>(), read(emptyDirectory))
        assertThrows(IOException::class.java) { read(done()) }
        assertThrows(EOFException::class.java) { read(dent("one.txt", directory = false)) }
        val message = "Permission denied"
        val fail = buffer(8).put("FAIL".toByteArray()).putInt(message.length).array() + message.toByteArray()
        assertEquals(message, assertThrows(IOException::class.java) { read(fail) }.message)
    }

    @Test fun navigationPreservesNamesAndStopsAtDeviceRoot() {
        assertNull(AdbDeviceDirectory.parent("/"))
        assertEquals("/", AdbDeviceDirectory.parent("/sdcard"))
        assertEquals("/sdcard", AdbDeviceDirectory.parent("/sdcard/Download"))
        assertEquals("/sdcard", AdbDeviceDirectory.child("/", "sdcard"))
        assertEquals("/sdcard/my folder", AdbDeviceDirectory.child("/sdcard", "my folder"))
    }

    private fun read(response: ByteArray): List<AdbDeviceDirectory.Entry> {
        val input = ByteArrayInputStream(response)
        return AdbDeviceDirectory.read { size ->
            input.readNBytes(size).takeIf { it.size == size }
        }
    }

    private fun dent(name: String, directory: Boolean, symlink: Boolean = false): ByteArray {
        val bytes = name.toByteArray(Charsets.UTF_8)
        val mode = when {
            directory -> 0x4000
            symlink -> 0xa000
            else -> 0x8000
        }
        return buffer(20).put("DENT".toByteArray()).putInt(mode)
            .putInt(0).putInt(0).putInt(bytes.size).array() + bytes
    }

    private fun done(): ByteArray = buffer(20).put("DONE".toByteArray()).array()

    private fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
