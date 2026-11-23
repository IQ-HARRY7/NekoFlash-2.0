package ru.forum.adbfastboottool

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object AdbDeviceDirectory {
    data class Entry(val name: String, val isDirectory: Boolean, val isSymlink: Boolean = false)

    fun parent(path: String): String? =
        path.trimEnd('/').takeIf { it.isNotEmpty() }?.substringBeforeLast('/')?.ifEmpty { "/" }

    fun child(path: String, name: String): String = "${path.trimEnd('/')}/$name"

    /** Read the ADB SYNC LIST response. DONE has the same fixed body as DENT. */
    fun read(readExact: (Int) -> ByteArray?): List<Entry> {
        fun next(size: Int): ByteArray = readExact(size)
            ?: throw EOFException("ADB directory listing ended before DONE")

        val entries = mutableListOf<Entry>()
        while (true) {
            val header = next(8)
            val id = String(header, 0, 4, Charsets.US_ASCII)
            val value = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            when (id) {
                "DENT" -> {
                    val body = ByteBuffer.wrap(next(12)).order(ByteOrder.LITTLE_ENDIAN)
                    val name = String(next(checkLength(body.getInt(8))), Charsets.UTF_8)
                    if (name.isEmpty() || '/' in name || '\u0000' in name) {
                        throw IOException("Invalid ADB directory entry name")
                    }
                    if (name != "." && name != "..") {
                        val type = value and 0xf000
                        entries.add(Entry(name, isDirectory = type == 0x4000, isSymlink = type == 0xa000))
                    }
                }
                "DONE" -> {
                    next(12)
                    return entries.sortedWith(compareByDescending<Entry> { it.isDirectory }.thenBy { it.name })
                }
                "FAIL" -> throw IOException(String(next(checkLength(value)), Charsets.UTF_8))
                else -> throw IOException("Unexpected ADB directory response: $id")
            }
        }
    }

    private fun checkLength(length: Int): Int {
        if (length !in 0..(1024 * 1024)) throw IOException("Invalid ADB directory response length: $length")
        return length
    }
}
