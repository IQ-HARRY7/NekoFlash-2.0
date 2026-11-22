package ru.forum.adbfastboottool

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class LocalShell {
    data class Output(
        val directory: File?,
        val lines: List<String>,
        val clearTerminal: Boolean = false,
        val deviceBinariesEnabled: Boolean = false,
    )

    private data class CommandResult(
        val exitCode: Int,
        val lines: List<String> = emptyList(),
        val clearTerminal: Boolean = false,
        val diagnostics: List<String> = emptyList(),
    )

    private val lock = Mutex()
    @Volatile private var home: File? = null
    @Volatile private var cwd: File? = null
    @Volatile private var deviceBinariesEnabled = false
    @Volatile private var cachedToyboxApplets: Set<String>? = null

    fun prompt(directory: File? = cwd): String {
        val root = home ?: return "~"
        val current = directory ?: root
        val relative = root.toPath().relativize(current.toPath()).toString()
        return if (relative.isEmpty()) "~" else "~/$relative"
    }

    suspend fun execute(
        line: String,
        workspace: suspend () -> File?,
        confirmOverwrite: suspend (File) -> Boolean,
    ): Output? = execute(line, workspace, confirmOverwrite, { false })

    suspend fun execute(
        line: String,
        workspace: suspend () -> File?,
        confirmOverwrite: suspend (File) -> Boolean,
        confirmDelete: suspend (File) -> Boolean,
    ): Output? = lock.withLock {
        withContext(Dispatchers.IO) {
            val clauses = splitOutsideQuotes(line, "&&")
            require(clauses.none(String::isBlank)) { "Missing command around &&" }

            var root: File? = null
            suspend fun workspaceRoot(): File? {
                if (root == null) {
                    root = workspace()?.canonicalFile ?: return null
                    require(root!!.isDirectory) { "Workspace folder is unavailable: $root" }
                    if (home != root) {
                        home = root
                        cwd = root
                    }
                }
                return root
            }

            var previousExitCode = 0
            val outputLines = mutableListOf<String>()
            var clearTerminal = false
            for (clause in clauses) {
                if (previousExitCode != 0) break
                val stages = splitOutsideQuotes(clause, "|")
                require(stages.none(String::isBlank)) { "Missing command around |" }
                var input: List<String>? = null
                var finalCommand = ""
                for (stage in stages) {
                    val tokens = FastbootCommandLine.tokenize(stage.trim()).toMutableList()
                    if (tokens.firstOrNull()?.value == "local") tokens.removeAt(0)
                    val name = tokens.firstOrNull()?.value.orEmpty()
                    finalCommand = name
                    val args = tokens.drop(1)
                    if (name.isEmpty()) return@withContext Output(cwd, HELP, clearTerminal, deviceBinariesEnabled)

                    if (stages.size > 1 && name in STATEFUL_COMMANDS) {
                        throw IllegalArgumentException("'$name' cannot be used in a pipeline")
                    }
                    val usesWorkspaceAlias = args.any { token ->
                        token.literal.startsWith("~") && (token.value == "~" || token.value.startsWith("~/"))
                    }
                    val needsWorkspace = name !in WORKSPACE_FREE_COMMANDS &&
                        (!deviceBinariesEnabled || name in COMMANDS || usesWorkspaceAlias)
                    val activeRoot = if (needsWorkspace) workspaceRoot() ?: return@withContext null else root
                    val directory = cwd ?: activeRoot
                    val result = executeCommand(
                        name = name,
                        args = args,
                        input = input,
                        root = activeRoot,
                        directory = directory,
                        confirmOverwrite = confirmOverwrite,
                        confirmDelete = confirmDelete,
                    )
                    input = result.lines
                    previousExitCode = result.exitCode
                    if (result.clearTerminal) {
                        clearTerminal = true
                        outputLines.clear()
                    }
                    outputLines += result.diagnostics
                }
                if (input.isNullOrEmpty() && previousExitCode == 1 && finalCommand == "grep") {
                    outputLines += "No matches"
                } else {
                    outputLines += input.orEmpty()
                }
            }
            Output(cwd, outputLines, clearTerminal, deviceBinariesEnabled)
        }
    }

    private suspend fun executeCommand(
        name: String,
        args: List<FastbootCommandLine.Token>,
        input: List<String>?,
        root: File?,
        directory: File?,
        confirmOverwrite: suspend (File) -> Boolean,
        confirmDelete: suspend (File) -> Boolean,
    ): CommandResult {
        if (name == "export") {
            require(input == null && args.size == 1) {
                "Usage: export NEKO_USE_DEVICE_BINARIES=1 (or 0)"
            }
            val assignment = args.single().value
            require(assignment == "NEKO_USE_DEVICE_BINARIES=1" || assignment == "NEKO_USE_DEVICE_BINARIES=0") {
                "Only NEKO_USE_DEVICE_BINARIES=1 or 0 is supported"
            }
            deviceBinariesEnabled = assignment.endsWith("=1")
            return CommandResult(0, listOf(
                if (deviceBinariesEnabled) "Device binaries enabled; system implementations are preferred when available."
                else "Device binaries disabled; app-managed implementations are preferred."
            ))
        }

        if (name == "clear") {
            require(input == null && args.isEmpty()) { "Usage: clear" }
            return CommandResult(0, clearTerminal = true)
        }

        if (name in SHELL_INTERPRETERS) {
            return CommandResult(126, listOf("Shell interpreters and script execution are not available in this terminal."))
        }

        if (deviceBinariesEnabled && name !in setOf("cd", "export", "clear", "help")) {
            val executable = resolveDeviceCommand(name)
            if (executable != null) {
                val approved = approveDeviceMutation(name, args, root, directory, confirmOverwrite, confirmDelete)
                if (approved == null) return CommandResult(1, listOf("Cancelled; no files were changed"))
                try {
                    return runDeviceCommand(executable + approved, input, directory)
                } catch (error: DeviceCommandUnavailable) {
                    val fallback = runBuiltIn(
                        name, args, input, root, directory,
                        confirmOverwrite = { true }, confirmDelete = { true },
                    )
                    if (fallback != null) {
                        return fallback.copy(diagnostics = listOf(
                            "Device command could not be started; using the app implementation."
                        ) + fallback.diagnostics)
                    }
                    throw error.cause ?: error
                }
            }
            val fallback = runBuiltIn(name, args, input, root, directory, confirmOverwrite, confirmDelete)
            if (fallback != null) {
                return fallback.copy(diagnostics = listOf(
                    "No matching Android binary; using the app implementation."
                ) + fallback.diagnostics)
            }
        }

        return runBuiltIn(name, args, input, root, directory, confirmOverwrite, confirmDelete)
            ?: if (deviceBinariesEnabled) {
                CommandResult(127, listOf("Command not found in the Android system paths: $name"))
            } else {
                CommandResult(127, listOf("Unknown local command: $name", *HELP.toTypedArray()))
            }
    }

    private suspend fun runBuiltIn(
        command: String,
        args: List<FastbootCommandLine.Token>,
        input: List<String>?,
        root: File?,
        directory: File?,
        confirmOverwrite: suspend (File) -> Boolean,
        confirmDelete: suspend (File) -> Boolean,
    ): CommandResult? {
        if (command == "echo") return CommandResult(0, listOf(args.joinToString(" ") { it.value }))
        if (command == "help") return CommandResult(0, HELP)
        if (command !in COMMANDS) return null
        val workspace = requireNotNull(root) { "Workspace is unavailable" }
        val current = requireNotNull(directory)
        fun path(token: FastbootCommandLine.Token) = resolve(workspace, current, token)

        return when (command) {
            "pwd" -> {
                require(args.isEmpty()) { "Usage: pwd" }
                CommandResult(0, listOf(current.path))
            }
            "cd" -> {
                require(args.size <= 1) { "Usage: cd [directory]" }
                val next = args.firstOrNull()?.let(::path) ?: workspace
                require(next.isDirectory) { "Not a directory: $next" }
                cwd = next
                CommandResult(0)
            }
            "ls" -> {
                require(args.size <= 1) { "Usage: ls [path]" }
                val item = args.firstOrNull()?.let(::path) ?: inside(workspace, current)
                require(item.exists()) { "No such file or directory: $item" }
                val entries = if (item.isFile) listOf(item.name)
                else requireNotNull(item.list()) { "Cannot list: $item" }.sorted().take(MAX_OUTPUT_LINES)
                CommandResult(0, entries)
            }
            "grep" -> {
                require(args.size in 1..2) { "Usage: grep TEXT [FILE]" }
                val matches = if (args.size == 2) {
                    val file = path(args[1])
                    require(file.isFile) { "Not a file: $file" }
                    val job = currentCoroutineContext()
                    file.useLines { source ->
                        source.filter { job.ensureActive(); it.contains(args[0].value) }
                            .take(MAX_OUTPUT_LINES).toList()
                    }
                } else {
                    (input ?: throw IllegalArgumentException("Usage: grep TEXT FILE (or pipe input into grep)"))
                        .filter { it.contains(args[0].value) }.take(MAX_OUTPUT_LINES)
                }
                CommandResult(if (matches.isEmpty()) 1 else 0, matches)
            }
            "cat" -> {
                require(args.size <= 1) { "Usage: cat [file]" }
                if (args.isEmpty()) {
                    CommandResult(0, input ?: throw IllegalArgumentException("Usage: cat FILE (or pipe input into cat)"))
                } else {
                    val file = path(args.single())
                    require(file.isFile) { "Not a file: $file" }
                    val lines = mutableListOf<String>()
                    file.bufferedReader().use { reader ->
                        while (lines.size < MAX_OUTPUT_LINES) {
                            currentCoroutineContext().ensureActive()
                            val line = reader.readLine() ?: break
                            lines += line
                        }
                        if (reader.readLine() != null) lines += "[output truncated at $MAX_OUTPUT_LINES lines]"
                    }
                    CommandResult(0, lines)
                }
            }
            "cp", "mv" -> {
                require(input == null) { "$command does not accept pipeline input" }
                require(args.size == 2) { "Usage: $command SOURCE DEST" }
                val message = transfer(command == "mv", workspace, path(args[0]), path(args[1]), confirmOverwrite)
                CommandResult(if (message.startsWith("Cancelled")) 1 else 0, listOf(message))
            }
            "rm" -> {
                require(input == null && args.size == 1) { "Usage: rm FILE" }
                val file = path(args.single())
                require(file.isFile) { "Not a regular file: $file (directories are not removed)" }
                val before = attributes(file) ?: throw IllegalArgumentException("No such file: $file")
                if (!confirmDelete(file)) return CommandResult(1, listOf("Cancelled; file was left untouched"))
                val now = attributes(file)
                require(now != null && now.isRegularFile && now.fileKey() == before.fileKey() &&
                    now.lastModifiedTime() == before.lastModifiedTime() && now.size() == before.size()
                ) { "File changed before deletion. Run the command again." }
                Files.delete(file.toPath())
                CommandResult(0, listOf("Removed ${file.name}"))
            }
            else -> null
        }
    }

    private suspend fun transfer(
        move: Boolean,
        root: File,
        source: File,
        destination: File,
        confirmOverwrite: suspend (File) -> Boolean,
    ): String {
        require(source.isFile) { "Not a file: $source" }
        val target = if (destination.isDirectory) inside(root, File(destination, source.name)) else destination
        require(target.parentFile?.isDirectory == true) { "Destination folder does not exist: $target" }
        val before = attributes(target)
        if (before != null) {
            require(before.isRegularFile && !Files.isSameFile(source.toPath(), target.toPath())) {
                "Invalid destination: $target"
            }
            if (!confirmOverwrite(target)) return "Cancelled; existing file was left untouched"
        }

        val job = currentCoroutineContext()
        job.ensureActive()
        require(inside(root, source) == source && inside(root, target) == target) {
            "A path changed. Run the command again."
        }
        val staged = if (move) source else FilePublication.temporarySibling(target)
        try {
            if (!move) {
                source.inputStream().buffered().use { input ->
                    staged.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            job.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            job.ensureActive()
            require(inside(root, target) == target) { "Destination path changed: $target" }
            if (before != null) {
                val now = attributes(target)
                require(now != null && now.isRegularFile && now.fileKey() == before.fileKey() &&
                    now.lastModifiedTime() == before.lastModifiedTime() && now.size() == before.size()
                ) { "Destination changed. Run the command again: $target" }
            }
            when {
                before == null -> FilePublication.publishNew(staged, target)
                move -> Files.move(staged.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                else -> FilePublication.replace(staged, target)
            }
        } finally {
            if (!move) staged.delete()
        }
        return "${if (move) "Moved" else "Copied"} ${source.name} → ${target.path}"
    }

    private suspend fun runDeviceCommand(
        command: List<String>,
        input: List<String>?,
        directory: File?,
    ): CommandResult = coroutineScope {
        val process = try {
            ProcessBuilder(command).apply {
                directory?.let(::directory)
                environment()["PATH"] = SYSTEM_PATHS.joinToString(":")
                directory?.let { environment()["HOME"] = it.absolutePath }
            }.start()
        } catch (error: IOException) {
            throw DeviceCommandUnavailable(error)
        } catch (error: SecurityException) {
            throw DeviceCommandUnavailable(IOException(error.message ?: "execution denied", error))
        }
        try {
            val stdout = async(Dispatchers.IO) { readOutput(process.inputStream) }
            val stderr = async(Dispatchers.IO) { readOutput(process.errorStream) }
            val stdin = async(Dispatchers.IO) {
                try {
                    process.outputStream.bufferedWriter().use { writer ->
                        input?.forEach { writer.appendLine(it) }
                    }
                } catch (error: IOException) {
                    if (process.isAlive) throw error
                    Unit
                }
            }
            val waiter = async(Dispatchers.IO) { process.waitFor() }
            val exitCode = withTimeoutOrNull(COMMAND_TIMEOUT_MS) { waiter.await() }
                ?: throw IOException("Device command timed out after ${COMMAND_TIMEOUT_MS / 1000}s")
            stdin.await()
            val output = stdout.await()
            val errors = stderr.await().map { "stderr: $it" }
            CommandResult(exitCode, output, diagnostics = errors)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun readOutput(stream: java.io.InputStream): List<String> {
        val lines = mutableListOf<String>()
        var truncated = false
        stream.bufferedReader().useLines { source ->
            source.forEach { line ->
                if (lines.size < MAX_OUTPUT_LINES) lines += line else truncated = true
            }
        }
        if (truncated) lines += "[output truncated at $MAX_OUTPUT_LINES lines]"
        return lines
    }

    private suspend fun approveDeviceMutation(
        name: String,
        args: List<FastbootCommandLine.Token>,
        root: File?,
        directory: File?,
        confirmOverwrite: suspend (File) -> Boolean,
        confirmDelete: suspend (File) -> Boolean,
    ): List<String>? {
        if (name !in setOf("cp", "mv", "rm")) {
            return args.map { token ->
                val tildePath = token.literal.startsWith("~") &&
                    (token.value == "~" || token.value.startsWith("~/"))
                if (tildePath && root != null && directory != null) resolve(root, directory, token).absolutePath
                else token.value
            }
        }
        val workspace = requireNotNull(root)
        val current = requireNotNull(directory)
        fun path(token: FastbootCommandLine.Token) = resolve(workspace, current, token)
        return when (name) {
            "rm" -> {
                require(args.size == 1 && !args[0].value.startsWith("-")) { "Usage: rm FILE" }
                val file = path(args.single())
                require(file.isFile) { "Not a regular file: $file (directories are not removed)" }
                if (!confirmDelete(file)) null else listOf(file.absolutePath)
            }
            "cp", "mv" -> {
                require(args.size == 2 && args.none { it.value.startsWith("-") }) { "Usage: $name SOURCE DEST" }
                val source = path(args[0])
                val destination = path(args[1])
                require(source.isFile) { "Not a file: $source" }
                val target = if (destination.isDirectory) inside(workspace, File(destination, source.name)) else destination
                require(target.parentFile?.isDirectory == true) { "Destination folder does not exist: $target" }
                val existing = attributes(target)
                if (existing != null) {
                    require(existing.isRegularFile && !Files.isSameFile(source.toPath(), target.toPath())) {
                        "Invalid destination: $target"
                    }
                    if (!confirmOverwrite(target)) return null
                }
                listOf(source.absolutePath, destination.absolutePath)
            }
            else -> args.map { it.value }
        }
    }

    private fun resolveDeviceCommand(name: String): List<String>? {
        if (!name.matches(Regex("[A-Za-z0-9_+.-]+"))) return null
        SYSTEM_PATHS.forEach { directory ->
            val binary = File(directory, name)
            if (binary.isFile && binary.canExecute()) return listOf(binary.absolutePath)
        }
        val toybox = File("/system/bin/toybox")
        if (toybox.isFile && toybox.canExecute() && name in toyboxApplets()) {
            return listOf(toybox.absolutePath, name)
        }
        return null
    }

    private fun toyboxApplets(): Set<String> {
        cachedToyboxApplets?.let { return it }
        val found = runCatching {
            val binary = File("/system/bin/toybox")
            val process = ProcessBuilder(binary.absolutePath).start()
            if (!process.waitFor(TOYBOX_PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                emptySet()
            } else if (process.exitValue() == 0) {
                process.inputStream.bufferedReader().use { it.readText() }
                    .split(Regex("\\s+"))
                    .filterTo(mutableSetOf()) { it.matches(Regex("[A-Za-z0-9_+.-]+")) }
            } else emptySet()
        }.getOrDefault(emptySet())
        cachedToyboxApplets = found
        return found
    }

    private fun attributes(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun resolve(root: File, directory: File, token: FastbootCommandLine.Token): File {
        val value = token.value
        require(value.isNotEmpty()) { "Path is empty" }
        val path = when {
            token.literal.startsWith("~") && value == "~" -> root.toPath()
            token.literal.startsWith("~") && value.startsWith("~/") -> root.toPath().resolve(value.drop(2))
            else -> directory.toPath().resolve(value)
        }
        return inside(root, path.toFile())
    }

    private fun inside(root: File, candidate: File): File {
        val resolved = candidate.canonicalFile
        require(resolved.toPath().startsWith(root.toPath())) { "Path is outside the NekoFlash workspace: $candidate" }
        return resolved
    }

    private fun splitOutsideQuotes(input: String, operator: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var quote: Char? = null
        var escaped = false
        var index = 0
        while (index < input.length) {
            val ch = input[index]
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                quote != null -> if (ch == quote) quote = null
                ch == '\'' || ch == '"' -> quote = ch
                input.startsWith(operator, index) -> {
                    parts += input.substring(start, index).trim()
                    index += operator.length
                    start = index
                    continue
                }
            }
            index++
        }
        require(quote == null && !escaped) { "Unclosed quote or trailing escape in local command" }
        parts += input.substring(start).trim()
        return parts
    }

    private class DeviceCommandUnavailable(cause: IOException) : IOException(cause.message, cause)

    private companion object {
        const val MAX_OUTPUT_LINES = 2_000
        const val COMMAND_TIMEOUT_MS = 30_000L
        const val TOYBOX_PROBE_TIMEOUT_MS = 2_000L
        val SYSTEM_PATHS = listOf(
            "/system/bin", "/system/xbin", "/system_ext/bin", "/vendor/bin",
            "/vendor/xbin", "/product/bin", "/odm/bin",
        )
        val COMMANDS = setOf("pwd", "ls", "cd", "grep", "cp", "mv", "cat", "rm")
        val SHELL_INTERPRETERS = setOf("sh", "bash", "mksh", "ash", "dash", "zsh", "ksh", "toybox", "busybox")
        val STATEFUL_COMMANDS = setOf("cd", "export", "clear")
        val WORKSPACE_FREE_COMMANDS = setOf("echo", "help", "export", "clear") + SHELL_INTERPRETERS
        val HELP = listOf(
            "Commands: echo, pwd, ls, cd, grep, cp, mv, cat, rm, clear",
            "Use 'local' for host commands; quote paths containing spaces.",
            "&& runs the next command after success; | passes output to the next command.",
            "App-managed file commands stay inside Download/NekoFlash; rm removes files only and asks first.",
            "App implementations are preferred by default.",
            "Enable device binaries: export NEKO_USE_DEVICE_BINARIES=1",
            "Restore app implementations: export NEKO_USE_DEVICE_BINARIES=0",
            "Unavailable device tools fall back to app implementations where available.",
            "Device commands use Android app permissions; they do not gain root access.",
            "Shell interpreters and script execution are not exposed."
        )
    }
}

internal fun extractLocalCommand(raw: String): String? {
    val clean = raw.trimStart()
    val separator = clean.indexOfFirst(Char::isWhitespace)
    val first = if (separator < 0) clean else clean.substring(0, separator)
    return when (first) {
        "local" -> if (separator < 0) "" else clean.substring(separator).trimStart()
        "echo", "clear" -> clean
        "export" -> clean.takeIf { it.startsWith("export NEKO_USE_DEVICE_BINARIES=") }
        else -> null
    }
}
