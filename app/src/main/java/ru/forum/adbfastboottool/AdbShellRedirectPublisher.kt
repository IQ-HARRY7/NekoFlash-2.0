package ru.forum.adbfastboottool

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Publishes the captured stdout of a redirected shell command to its target file.
 *
 * The source is the raw evidence file written by [AdbShellOutputRecord]. It is only
 * read, never moved or deleted, so the evidence stays intact whatever happens here.
 * Overwrite goes through a staged sibling and an atomic replace, so a failure keeps the
 * previous file. Append rolls the file back to its original length on failure.
 */
internal object AdbShellRedirectPublisher {
    data class Result(val copiedBytes: Long, val targetBytes: Long)

    fun publish(
        source: File,
        target: File,
        append: Boolean,
        availableSpace: (File) -> Long
    ): Result {
        val size = source.length()
        val directory = requireNotNull(target.absoluteFile.parentFile) {
            "Destination must have a parent directory"
        }
        return if (append) appendTo(source, size, target, directory, availableSpace)
        else replaceTarget(source, size, target, directory, availableSpace)
    }

    private fun replaceTarget(
        source: File,
        size: Long,
        target: File,
        directory: File,
        availableSpace: (File) -> Long
    ): Result {
        val staged = FilePublication.temporarySibling(target)
        try {
            copy(source, size, FileOutputStream(staged), directory, availableSpace)
            FilePublication.replace(staged, target)
        } finally {
            // Already moved after a successful replace; removes the staged copy otherwise.
            staged.delete()
        }
        return Result(size, size)
    }

    private fun appendTo(
        source: File,
        size: Long,
        target: File,
        directory: File,
        availableSpace: (File) -> Long
    ): Result {
        val existed = target.exists()
        val originalLength = if (existed) target.length() else 0L
        try {
            copy(source, size, FileOutputStream(target, true), directory, availableSpace)
        } catch (error: Exception) {
            runCatching {
                if (existed) RandomAccessFile(target, "rw").use { it.setLength(originalLength) }
                else target.delete()
            }
            throw error
        }
        return Result(size, originalLength + size)
    }

    private fun copy(
        source: File,
        size: Long,
        output: FileOutputStream,
        directory: File,
        availableSpace: (File) -> Long
    ) {
        FileInputStream(source).use { input ->
            output.use { out ->
                LocalFileCopy.copy(
                    input, out, directory, expectedSize = size,
                    // Not cancellable: this runs after Stop and must keep what was captured.
                    checkCancelled = {}, availableSpace = availableSpace
                )
                out.fd.sync()
            }
        }
    }
}
