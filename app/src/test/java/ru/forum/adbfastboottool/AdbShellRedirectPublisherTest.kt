package ru.forum.adbfastboottool

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AdbShellRedirectPublisherTest {
    private fun withDirectory(test: (File) -> Unit) {
        val dir = Files.createTempDirectory("redirect-test").toFile()
        try { test(dir) } finally { dir.deleteRecursively() }
    }

    private val plenty: (File) -> Long = { Long.MAX_VALUE }

    @Test fun overwriteReplacesTheFileAndKeepsTheEvidence() = withDirectory { dir ->
        val raw = File(dir, "raw.bin").apply { writeBytes(byteArrayOf(0, 1, 2, -1, 10)) }
        val target = File(dir, "out.txt").apply { writeText("old") }
        val result = AdbShellRedirectPublisher.publish(raw, target, append = false, availableSpace = plenty)
        assertArrayEquals(byteArrayOf(0, 1, 2, -1, 10), target.readBytes())
        assertEquals(5L, result.copiedBytes)
        assertEquals(5L, result.targetBytes)
        assertTrue(raw.isFile)
        assertEquals(setOf("raw.bin", "out.txt"), dir.list()!!.toSet())
    }

    @Test fun overwriteCreatesMissingFileAndEmptyOutputIsAnEmptyFile() = withDirectory { dir ->
        val raw = File(dir, "raw.bin").apply { writeBytes(ByteArray(0)) }
        val target = File(dir, "empty.txt")
        AdbShellRedirectPublisher.publish(raw, target, append = false, availableSpace = plenty)
        assertTrue(target.isFile)
        assertEquals(0L, target.length())
    }

    @Test fun appendAddsToTheEndAndReportsTheNewTotal() = withDirectory { dir ->
        val raw = File(dir, "raw.bin").apply { writeText("world") }
        val target = File(dir, "out.txt").apply { writeText("hello ") }
        val result = AdbShellRedirectPublisher.publish(raw, target, append = true, availableSpace = plenty)
        assertEquals("hello world", target.readText())
        assertEquals(5L, result.copiedBytes)
        assertEquals(11L, result.targetBytes)
    }

    @Test fun appendCreatesTheFileWhenItDoesNotExist() = withDirectory { dir ->
        val raw = File(dir, "raw.bin").apply { writeText("abc") }
        val target = File(dir, "new.txt")
        val result = AdbShellRedirectPublisher.publish(raw, target, append = true, availableSpace = plenty)
        assertEquals("abc", target.readText())
        assertEquals(3L, result.targetBytes)
    }

    @Test fun overwriteKeepsThePreviousFileWhenThereIsNoSpace() = withDirectory { dir ->
        val raw = File(dir, "raw.bin").apply { writeText("0123456789") }
        val target = File(dir, "out.txt").apply { writeText("previous") }
        val error = assertThrows(LocalFileCopy.NotEnoughSpace::class.java) {
            AdbShellRedirectPublisher.publish(raw, target, append = false, availableSpace = { 3L })
        }
        assertEquals(10L, error.required)
        assertEquals(3L, error.available)
        assertEquals("previous", target.readText())
        assertEquals(setOf("raw.bin", "out.txt"), dir.list()!!.toSet())
    }

    @Test fun appendRollsBackToTheOriginalLengthWhenThereIsNoSpace() = withDirectory { dir ->
        val raw = File(dir, "raw.bin").apply { writeText("0123456789") }
        val target = File(dir, "out.txt").apply { writeText("keep") }
        assertThrows(LocalFileCopy.NotEnoughSpace::class.java) {
            AdbShellRedirectPublisher.publish(raw, target, append = true, availableSpace = { 3L })
        }
        assertEquals("keep", target.readText())
    }

    @Test fun failedAppendDoesNotLeaveAnEmptyFileBehind() = withDirectory { dir ->
        val raw = File(dir, "raw.bin").apply { writeText("0123456789") }
        val target = File(dir, "new.txt")
        assertThrows(LocalFileCopy.NotEnoughSpace::class.java) {
            AdbShellRedirectPublisher.publish(raw, target, append = true, availableSpace = { 3L })
        }
        assertFalse(target.exists())
    }

    @Test fun largeOutputIsCopiedAcrossBufferBoundaries() = withDirectory { dir ->
        val payload = ByteArray(2_500_000) { (it % 251).toByte() }
        val raw = File(dir, "raw.bin").apply { writeBytes(payload) }
        val target = File(dir, "big.bin")
        val result = AdbShellRedirectPublisher.publish(raw, target, append = false, availableSpace = plenty)
        assertEquals(payload.size.toLong(), result.copiedBytes)
        assertArrayEquals(payload, target.readBytes())
    }
}
