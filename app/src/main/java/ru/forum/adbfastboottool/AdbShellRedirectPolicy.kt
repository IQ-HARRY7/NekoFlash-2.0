package ru.forum.adbfastboottool

import java.io.File
import java.util.Locale

/**
 * Host-side stdout redirection for terminal ADB commands: `adb logcat > logcat.txt`.
 *
 * An unquoted `>` or `>>` is handled on this phone, like `adb` on a PC: the device
 * command runs without it and its stdout is saved to a local file. Everything else
 * keeps going to the device unchanged: quoted or escaped `>`, `2>`, `&>`, `>&` and
 * `2>&1`. stderr is never redirected.
 *
 * Pure policy: no I/O and no Android types. The path itself is resolved by the
 * same rules as `adb pull` in MainActivity.
 */
internal object AdbShellRedirectPolicy {
    sealed class Parsed {
        data object None : Parsed()
        data class Redirect(val command: String, val target: String, val append: Boolean) : Parsed()
        data class Invalid(val reason: String) : Parsed()
    }

    /** How the device command ended, which decides what is saved and how it is reported. */
    enum class End { COMPLETED, STOPPED_BY_USER, NONZERO_EXIT, INTERRUPTED }

    private data class Operator(val start: Int, val end: Int, val append: Boolean)

    fun parse(line: String): Parsed {
        val operators = findOperators(line)
        if (operators.isEmpty()) return Parsed.None
        if (operators.size > 1) return Parsed.Invalid("Only one output redirect (>) is supported per command")

        val operator = operators.single()
        val symbol = if (operator.append) ">>" else ">"
        var targetStart = operator.end
        while (targetStart < line.length && line[targetStart].isWhitespace()) targetStart++
        if (targetStart >= line.length) return Parsed.Invalid("File name is missing after $symbol")
        if (line[targetStart] in "<>|&;") {
            return Parsed.Invalid("Unsupported redirect target near \"${line[targetStart]}\"")
        }

        val (target, targetEnd) = readWord(line, targetStart)
        if (target.isBlank()) return Parsed.Invalid("File name is missing after $symbol")
        // A command separator after the output path changes shell execution order.
        // Rather than silently moving it into the device command, reject the input.
        if (hasUnquotedCommandControl(line.substring(targetStart))) {
            return Parsed.Invalid("Unsupported command separator after > file; place pipelines before the redirect")
        }

        val command = (line.substring(0, operator.start).trimEnd() + " " +
            line.substring(targetEnd).trimStart()).trim()
        if (command.isEmpty()) {
            return Parsed.Invalid("Nothing to run before the redirect. Example: adb logcat > logcat.txt")
        }
        return Parsed.Redirect(command, target, operator.append)
    }

    /** Used only when the target is a folder: `adb logcat > /sdcard/Download/`. */
    fun defaultFileName(command: String): String {
        val word = command.trimStart().takeWhile { !it.isWhitespace() }.substringAfterLast('/')
        val safe = word.lowercase(Locale.US)
            .filter { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }
            .trim('.')
        return (safe.ifEmpty { "adb-output" }) + ".txt"
    }

    fun endOf(success: Boolean, cancelled: Boolean, exitCode: Int?): End = when {
        success -> End.COMPLETED
        cancelled -> End.STOPPED_BY_USER
        exitCode != null && exitCode != 0 -> End.NONZERO_EXIT
        else -> End.INTERRUPTED
    }

    /** A finished command always leaves its file (even empty), like a shell; a failed one only if it printed something. */
    fun shouldPublish(end: End, bytes: Long): Boolean = bytes > 0L || end == End.COMPLETED

    /** Pressing Stop on a streaming command such as logcat is a normal way to finish. */
    fun succeeded(end: End): Boolean = end == End.COMPLETED || end == End.STOPPED_BY_USER

    fun startLine(target: File, append: Boolean): String =
        "ℹ️ Output goes to a file instead of the screen (" +
            (if (append) "appending to" else "overwriting") + "): " + target.absolutePath

    fun legacyShellNote(): String =
        "ℹ️ Legacy shell: stderr is merged into the saved output and the exit code is unavailable"

    fun hintLine(): String =
        "💡 Capture in progress. Press Stop when you have enough; everything captured so far is kept."

    fun progressLine(name: String, bytes: Long, elapsedMs: Long): String =
        "📥 Catching output → $name · ${HumanReadable.bytes(bytes)} so far · ${HumanReadable.duration(elapsedMs)}" +
            rateSuffix(bytes, elapsedMs)

    fun progressDetail(bytes: Long, elapsedMs: Long): String =
        "Saving output · ${HumanReadable.bytes(bytes)} · ${HumanReadable.duration(elapsedMs)}"

    fun savedLine(
        end: End,
        target: File,
        appended: Boolean,
        bytes: Long,
        totalBytes: Long,
        elapsedMs: Long,
        exitCode: Int?
    ): String {
        val path = target.absolutePath
        val size = HumanReadable.bytes(bytes)
        val time = HumanReadable.duration(elapsedMs)
        return when {
            end == End.COMPLETED && bytes == 0L ->
                "⚠️ The command finished but printed nothing, so " +
                    (if (appended) "nothing was added to $path" else "$path is empty (0 B)") + "."
            end == End.COMPLETED && appended ->
                "✅ Output appended: $path · +$size (file is now ${HumanReadable.bytes(totalBytes)}) · $time"
            end == End.COMPLETED ->
                "✅ Output saved: $path · $size · $time"
            end == End.STOPPED_BY_USER ->
                "⚠️ Stopped by you. Everything captured so far was kept: $path · $size · $time"
            end == End.NONZERO_EXIT ->
                "❌ The command exited with code ${exitCode ?: "unknown"}, but its output was still saved: $path · $size · $time"
            else ->
                "⚠️ The connection ended before the command finished. Partial output was kept: $path · $size · $time"
        }
    }

    /** No file is written, and an existing file is never touched, when a failed command printed nothing. */
    fun skippedLine(end: End, target: File, exitCode: Int?): String {
        val path = target.absolutePath
        return when (end) {
            End.STOPPED_BY_USER -> "⚠️ Stopped before anything was printed, so no file was written."
            End.NONZERO_EXIT ->
                "❌ The command exited with code ${exitCode ?: "unknown"} and printed nothing; $path was left untouched."
            else -> "❌ The connection ended before any output arrived; $path was left untouched."
        }
    }

    fun saveFailedLine(target: File, reason: String): String =
        "❌ Could not save the output to ${target.absolutePath}: $reason"

    fun notEnoughSpaceReason(required: Long, available: Long): String =
        "not enough free space (needs ${HumanReadable.bytes(required)}, only ${HumanReadable.bytes(available)} left)"

    fun rawKeptLine(raw: File): String = "ℹ️ The raw output is still kept: ${raw.absolutePath}"

    private fun rateSuffix(bytes: Long, elapsedMs: Long): String =
        if (elapsedMs >= 1_000L && bytes > 0L) " · ${HumanReadable.rate(bytes * 1_000.0 / elapsedMs)}" else ""

    /** Finds unquoted, unescaped stdout redirects aimed at this phone, in command order. */
    private fun findOperators(line: String): List<Operator> {
        val found = ArrayList<Operator>()
        var quote: Char? = null
        var escaping = false
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            when {
                escaping -> escaping = false
                ch == '\\' -> escaping = true
                quote != null -> if (ch == quote) quote = null
                ch == '\'' || ch == '"' -> quote = ch
                ch == '>' -> {
                    val append = line.getOrNull(index + 1) == '>'
                    val end = index + if (append) 2 else 1
                    hostRedirectStart(line, index, end)?.let { found += Operator(it, end, append) }
                    index = end - 1
                }
            }
            index++
        }
        return found
    }

    /**
     * Start of the redirect including a leading `1`, or null when the operator belongs
     * to the device shell: `&>`, `>&` (`2>&1`) and any other file descriptor such as `2>`.
     */
    private fun hostRedirectStart(line: String, operatorStart: Int, operatorEnd: Int): Int? {
        val before = line.getOrNull(operatorStart - 1)
        if (before == '&' || line.getOrNull(operatorEnd) == '&') return null
        if (before != null && before.isDigit()) {
            var digitsStart = operatorStart - 1
            while (digitsStart > 0 && line[digitsStart - 1].isDigit()) digitsStart--
            val standalone = digitsStart == 0 || line[digitsStart - 1].isWhitespace()
            if (standalone) return if (line.substring(digitsStart, operatorStart) == "1") digitsStart else null
        }
        return operatorStart
    }

    /** Detect unquoted shell separators in the target or in trailing arguments. */
    private fun hasUnquotedCommandControl(text: String): Boolean {
        var quote: Char? = null
        var escaping = false
        for (ch in text) {
            when {
                escaping -> escaping = false
                ch == '\\' -> escaping = true
                quote != null -> if (ch == quote) quote = null
                ch == '\'' || ch == '"' -> quote = ch
                ch == '|' || ch == ';' || ch == '&' -> return true
            }
        }
        return false
    }

    /** Same quote and escape rules as the terminal tokenizer in MainActivity. */
    private fun readWord(line: String, from: Int): Pair<String, Int> {
        val word = StringBuilder()
        var quote: Char? = null
        var escaping = false
        var index = from
        while (index < line.length) {
            val ch = line[index]
            when {
                escaping -> { word.append(ch); escaping = false }
                ch == '\\' -> escaping = true
                quote != null -> if (ch == quote) quote = null else word.append(ch)
                ch == '\'' || ch == '"' -> quote = ch
                ch.isWhitespace() -> return word.toString() to index
                else -> word.append(ch)
            }
            index++
        }
        return word.toString() to index
    }

    /** Throttles log lines and UI updates while a long command streams into the file. */
    class ProgressMeter(
        startedMs: Long,
        private val logIntervalMs: Long = DiagnosticLogPolicy.progressLogIntervalMs(false),
        private val uiIntervalMs: Long = DiagnosticLogPolicy.uiProgressIntervalMs()
    ) {
        data class Tick(val log: Boolean, val ui: Boolean, val hint: Boolean)

        private var lastLogMs = startedMs
        private var lastUiMs = startedMs
        private var hinted = false

        fun tick(nowMs: Long): Tick {
            val log = nowMs - lastLogMs >= logIntervalMs
            val ui = nowMs - lastUiMs >= uiIntervalMs
            if (log) lastLogMs = nowMs
            if (ui) lastUiMs = nowMs
            val hint = log && !hinted
            if (hint) hinted = true
            return Tick(log, ui, hint)
        }
    }
}
