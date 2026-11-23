package ru.forum.adbfastboottool

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class LocalShell(private val availableSpace: (File) -> Long) {
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

    fun prompt(directory: File? = cwd): String {
        val root = home ?: return "~"
        val current = directory ?: root
        val relative = root.toPath().relativize(current.toPath()).toString()
        return if (relative.isEmpty()) "~" else "~/$relative"
    }

    fun quickOutput(line: String, help: List<String> = HELP): Output? {
        require(!hasUnsupportedShellSyntax(line)) {
            "Redirection, command separators, command substitution and backticks are not supported."
        }
        val words = tokenize(line)
        if (line.contains("&&") || line.contains('|')) return null
        val command = words.firstOrNull()?.value.orEmpty()
        val args = words.drop(1)
        return when (command) {
            "" , "help" -> Output(cwd, help, deviceBinariesEnabled = deviceBinariesEnabled)
            "echo" -> Output(cwd, listOf(args.joinToString(" ") { it.value }), deviceBinariesEnabled = deviceBinariesEnabled)
            "clear" -> {
                require(args.isEmpty()) { "Usage: clear" }
                Output(cwd, emptyList(), clearTerminal = true, deviceBinariesEnabled = deviceBinariesEnabled)
            }
            "export" -> {
                require(args.size == 1) { "Usage: export NEKO_USE_DEVICE_BINARIES=1 (or 0)" }
                val assignment = args.single().value
                require(assignment == "NEKO_USE_DEVICE_BINARIES=1" || assignment == "NEKO_USE_DEVICE_BINARIES=0") {
                    "Only NEKO_USE_DEVICE_BINARIES=1 or 0 is supported"
                }
                deviceBinariesEnabled = assignment.endsWith("=1")
                Output(cwd, listOf(
                    if (deviceBinariesEnabled) "Device diagnostics enabled; only approved read-only Android commands may run."
                    else "Device diagnostics disabled; app-managed implementations are used."
                ), deviceBinariesEnabled = deviceBinariesEnabled)
            }
            in COMMANDS -> null
            in BLOCKED_EXECUTABLES -> null
            else -> if (deviceBinariesEnabled) null else Output(
                cwd,
                listOf("Unknown local command: $command") + help,
                deviceBinariesEnabled = deviceBinariesEnabled,
            )
        }
    }

    fun requiresWorkspace(line: String): Boolean {
        val clauses = splitOutsideQuotes(line, "&&")
        return clauses.any { clause ->
            splitOutsideQuotes(clause, "|").any { stage ->
                val tokens = tokenize(stage.trim()).let { values ->
                    if (values.firstOrNull()?.value == "local") values.drop(1) else values
                }
                val name = tokens.firstOrNull()?.value.orEmpty()
                val args = tokens.drop(1)
                when (name) {
                    "pwd", "ls", "cd", "mkdir", "cp", "mv", "rm" -> true
                    "grep" -> args.size == 2
                    "cat" -> args.isNotEmpty()
                    else -> false
                }
            }
        }
    }

    private fun tokenize(line: String): List<FastbootCommandLine.Token> = try {
        FastbootCommandLine.tokenize(line)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("Unclosed quote or trailing escape in local command", error)
    }

    suspend fun execute(
        line: String,
        workspace: suspend () -> File?,
        cancellation: ImportCancellation = ImportCancellation(),
        onCopyProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
        confirmDelete: suspend (File) -> Boolean = { false },
        confirmOverwrite: suspend (File) -> Boolean,
    ): Output? = lock.withLock {
        var committedOutput: Output? = null
        try {
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                val checkCancelled = {
                    context.ensureActive()
                    cancellation.check()
                }
                checkCancelled()
                require(!hasUnsupportedShellSyntax(line)) {
                    "Redirection, command separators, command substitution and backticks are not supported."
                }

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

                val clauses = splitOutsideQuotes(line, "&&")
                require(clauses.none(String::isBlank)) { "Missing command around &&" }
                var previousExitCode = 0
                val outputLines = mutableListOf<String>()
                var clearTerminal = false

                for (clause in clauses) {
                    checkCancelled()
                    if (previousExitCode != 0) break
                    val stages = splitOutsideQuotes(clause, "|")
                    require(stages.none(String::isBlank)) { "Missing command around |" }
                    var input: List<String>? = null
                    var finalCommand = ""

                    for (stage in stages) {
                        checkCancelled()
                        val tokens = tokenize(stage.trim()).toMutableList()
                        if (tokens.firstOrNull()?.value == "local") tokens.removeAt(0)
                        val name = tokens.firstOrNull()?.value.orEmpty()
                        require(name.isNotEmpty()) { "Missing local command" }
                        val args = tokens.drop(1)
                        finalCommand = name

                        if (stages.size > 1 && name in STATEFUL_COMMANDS) {
                            throw IllegalArgumentException("'$name' cannot be used in a pipeline")
                        }

                        val needsWorkspace = when (name) {
                            "pwd", "ls", "cd", "mkdir", "cp", "mv", "rm" -> true
                            "grep" -> args.size == 2
                            "cat" -> args.isNotEmpty()
                            else -> false
                        }
                        val activeRoot = if (needsWorkspace) {
                            workspaceRoot() ?: return@withContext null
                        } else root
                        val directory = cwd ?: activeRoot
                        val result = executeCommand(
                            name = name,
                            args = args,
                            input = input,
                            root = activeRoot,
                            directory = directory,
                            cancellation = cancellation,
                            checkCancelled = checkCancelled,
                            onCopyProgress = onCopyProgress,
                            confirmOverwrite = confirmOverwrite,
                            confirmDelete = confirmDelete,
                            onCommitted = { committedOutput = it },
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
                        outputLines += "🔎 No matches"
                    } else {
                        outputLines += input.orEmpty()
                    }
                }
                Output(cwd, outputLines, clearTerminal, deviceBinariesEnabled)
            }
        } catch (cancelled: CancellationException) {
            committedOutput ?: throw cancelled
        }
    }

    private suspend fun executeCommand(
        name: String,
        args: List<FastbootCommandLine.Token>,
        input: List<String>?,
        root: File?,
        directory: File?,
        cancellation: ImportCancellation,
        checkCancelled: () -> Unit,
        onCopyProgress: (Long, Long) -> Unit,
        confirmOverwrite: suspend (File) -> Boolean,
        confirmDelete: suspend (File) -> Boolean,
        onCommitted: (Output) -> Unit,
    ): CommandResult {
        if (name in BLOCKED_EXECUTABLES) {
            return CommandResult(126, listOf(
                "'$name' is blocked. Shell execution, privilege escalation, and app-identity switching are not exposed."
            ))
        }

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
                if (deviceBinariesEnabled) "Device diagnostics enabled; only approved read-only Android commands may run."
                else "Device diagnostics disabled; app-managed implementations are used."
            ))
        }

        if (name == "clear") {
            require(input == null && args.isEmpty()) { "Usage: clear" }
            return CommandResult(0, clearTerminal = true)
        }

        if (name == "echo") return CommandResult(0, listOf(args.joinToString(" ") { it.value }))
        if (name == "help") {
            require(args.isEmpty()) { "Usage: help" }
            return CommandResult(0, HELP)
        }

        if (name in DEVICE_DIAGNOSTIC_COMMANDS) {
            if (!deviceBinariesEnabled) {
                return CommandResult(127, listOf(
                    "Device diagnostics are disabled. Run: export NEKO_USE_DEVICE_BINARIES=1"
                ))
            }
            val safeArgs = validateDeviceArguments(name, args)
            val binary = resolveDeviceCommand(name)
                ?: return CommandResult(127, listOf("Approved Android diagnostic is unavailable: $name"))
            return try {
                runDeviceCommand(binary + safeArgs, input, directory)
            } catch (error: DeviceCommandUnavailable) {
                CommandResult(126, listOf("Could not start Android diagnostic '$name': ${error.message}"))
            }
        }

        if (name !in COMMANDS) {
            return if (deviceBinariesEnabled) {
                CommandResult(126, listOf(
                    "Command '$name' is not allowed. Device mode exposes read-only diagnostics only: ${DEVICE_DIAGNOSTIC_COMMANDS.sorted().joinToString(", ")}."
                ))
            } else {
                CommandResult(127, listOf("Unknown local command: $name") + HELP)
            }
        }

        val workspace = requireNotNull(root) { "Workspace is unavailable" }
        val current = requireNotNull(directory)
        fun path(token: FastbootCommandLine.Token) = resolve(workspace, current, token)

        checkCancelled()
        return when (name) {
            "pwd" -> {
                require(input == null && args.isEmpty()) { "Usage: pwd" }
                CommandResult(0, listOf(current.path))
            }
            "cd" -> {
                require(input == null && args.size <= 1) { "Usage: cd [directory]" }
                val next = args.firstOrNull()?.let(::path) ?: workspace
                require(next.isDirectory) { "Not a directory: $next" }
                cwd = next
                CommandResult(0)
            }
            "ls" -> {
                require(input == null && args.size <= 1) { "Usage: ls [path]" }
                val item = args.firstOrNull()?.let(::path) ?: inside(workspace, current)
                CommandResult(0, listEntries(item, checkCancelled))
            }
            "grep" -> {
                require(args.size in 1..2) { "Usage: grep TEXT [FILE] (or pipe input into grep)" }
                val matches = if (args.size == 2) {
                    require(input == null) { "grep FILE mode does not accept pipeline input" }
                    readTextLines(path(args[1]), args[0].value, checkCancelled)
                        .takeUnless { it == listOf("🔎 No matches") }.orEmpty()
                } else {
                    requireNotNull(input) { "Usage: grep TEXT FILE (or pipe input into grep)" }
                        .filter { checkCancelled(); it.contains(args[0].value) }
                        .take(MAX_OUTPUT_LINES)
                }
                CommandResult(if (matches.isEmpty()) 1 else 0, matches)
            }
            "cat" -> {
                require(args.size <= 1) { "Usage: cat [FILE] (or pipe input into cat)" }
                val lines = when {
                    args.isNotEmpty() -> {
                        require(input == null) { "cat FILE mode does not accept pipeline input" }
                        readTextLines(path(args.single()), null, checkCancelled)
                    }
                    input != null -> input.take(MAX_OUTPUT_LINES)
                    else -> throw IllegalArgumentException("Usage: cat FILE (or pipe input into cat)")
                }
                CommandResult(0, lines)
            }
            "mkdir" -> {
                require(input == null && args.size == 1) { "Usage: mkdir DIR" }
                val folder = path(args.single())
                requireMutablePath(workspace, folder)
                val output = Output(cwd, listOf("✅ Created directory: ${folder.path}"))
                cancellation.commit {
                    checkCancelled()
                    Files.createDirectory(folder.toPath())
                }
                onCommitted(output)
                CommandResult(0, output.lines)
            }
            "cp", "mv" -> {
                require(input == null && args.size == 2) { "Usage: $name SOURCE DEST" }
                val output = transfer(
                    move = name == "mv",
                    root = workspace,
                    source = path(args[0]),
                    destination = path(args[1]),
                    cancellation = cancellation,
                    checkCancelled = checkCancelled,
                    onCopyProgress = onCopyProgress,
                    confirmOverwrite = confirmOverwrite,
                    onCommitted = onCommitted,
                )
                CommandResult(if (output.lines.any { it.contains("Cancelled") }) 1 else 0, output.lines)
            }
            "rm" -> {
                require(input == null && args.size == 1 && !args.single().value.startsWith('-')) {
                    "Usage: rm FILE (directories and options are not supported)"
                }
                val file = path(args.single())
                requireMutablePath(workspace, file)
                val before = attributes(file)
                require(before != null && before.isRegularFile) {
                    "Not a regular file: $file (directories and symbolic links are not removed)"
                }
                if (!confirmDelete(file)) return CommandResult(1, listOf("↩️ Cancelled; file was left untouched"))
                checkCancelled()
                val now = attributes(file)
                require(now != null && now.isRegularFile && now.fileKey() == before.fileKey() &&
                    now.lastModifiedTime() == before.lastModifiedTime() && now.size() == before.size()
                ) { "File changed before deletion. Run the command again." }
                val output = Output(cwd, listOf("✅ Removed ${file.name}"))
                cancellation.commit {
                    checkCancelled()
                    Files.delete(file.toPath())
                }
                onCommitted(output)
                CommandResult(0, output.lines)
            }
            else -> error("Command dispatch is incomplete: $name")
        }
    }

    private fun listEntries(item: File, checkCancelled: () -> Unit): List<String> {
        if (item.isFile) return listOf(item.name)
        require(item.isDirectory) { "No such file or directory: $item" }
        val names = Files.newDirectoryStream(item.toPath()).use { entries ->
            entries.asSequence().take(MAX_OUTPUT_LINES + 1).map {
                checkCancelled()
                it.fileName.toString()
            }.toList()
        }
        val listed = names.take(MAX_OUTPUT_LINES).sorted()
        return if (names.size > MAX_OUTPUT_LINES) listed + "… Directory listing limited to $MAX_OUTPUT_LINES entries."
        else listed.ifEmpty { listOf("📂 (empty directory)") }
    }

    private fun readTextLines(file: File, query: String?, checkCancelled: () -> Unit): List<String> {
        require(file.isFile) { "Not a file: $file" }
        val lines = mutableListOf<String>()
        val outputLimit = if (query == null) "… Cat output limit reached." else "… Grep output limit reached."
        val literal = query.orEmpty()
        var outputChars = 0
        val preview = StringBuilder()
        val prefixes = literalPrefixes(literal)
        var matched = literal.isEmpty()
        var matchedOutsidePreview = false
        var abbreviated = false
        var prefixLength = 0

        fun collectLine(): Boolean {
            if (!matched) return true
            val display = if (!abbreviated) preview.toString() else buildString {
                if (matchedOutsidePreview) {
                    val excerpt = literal.take(MAX_TEXT_PREVIEW_CHARS)
                    val openingLength = MAX_TEXT_PREVIEW_CHARS - excerpt.length
                    append(preview, 0, openingLength)
                    if (openingLength > 0) append(" … ")
                    append(excerpt)
                } else {
                    append(preview)
                }
                append(" … [line abbreviated]")
            }
            if (lines.size == MAX_OUTPUT_LINES || outputChars + display.length > MAX_TEXT_OUTPUT_CHARS) return false
            lines.add(display)
            outputChars += display.length
            return true
        }

        file.reader().use { reader ->
            val chunk = CharArray(4_096)
            var previousWasCr = false
            while (true) {
                checkCancelled()
                val count = reader.read(chunk)
                if (count < 0) break
                for (index in 0 until count) {
                    val char = chunk[index]
                    if (char == '\n' && previousWasCr) {
                        previousWasCr = false
                        continue
                    }
                    previousWasCr = char == '\r'
                    if (char == '\n' || previousWasCr) {
                        if (!collectLine()) return lines + outputLimit
                        preview.clear()
                        matched = literal.isEmpty()
                        matchedOutsidePreview = false
                        abbreviated = false
                        prefixLength = 0
                    } else {
                        if (preview.length < MAX_TEXT_PREVIEW_CHARS) preview.append(char)
                        else abbreviated = true
                        if (!matched) {
                            while (prefixLength > 0 && char != literal[prefixLength]) {
                                prefixLength = prefixes[prefixLength - 1]
                            }
                            if (char == literal[prefixLength]) prefixLength++
                            if (prefixLength == literal.length) {
                                matched = true
                                matchedOutsidePreview = abbreviated
                            }
                        }
                    }
                }
            }
        }
        if (preview.isNotEmpty() && !collectLine()) return lines + outputLimit
        return lines.ifEmpty { listOf(if (query == null) "📄 (empty file)" else "🔎 No matches") }
    }

    private fun literalPrefixes(query: String): IntArray {
        val prefixes = IntArray(query.length)
        for (index in 1 until query.length) {
            var length = prefixes[index - 1]
            while (length > 0 && query[index] != query[length]) length = prefixes[length - 1]
            if (query[index] == query[length]) length++
            prefixes[index] = length
        }
        return prefixes
    }

    private suspend fun transfer(
        move: Boolean,
        root: File,
        source: File,
        destination: File,
        cancellation: ImportCancellation,
        checkCancelled: () -> Unit,
        onCopyProgress: (Long, Long) -> Unit,
        confirmOverwrite: suspend (File) -> Boolean,
        onCommitted: (Output) -> Unit,
    ): Output {
        require(source.isFile) { "Not a file: $source" }
        val target = if (destination.isDirectory) inside(root, File(destination, source.name)) else destination
        val targetDirectory = target.parentFile?.takeIf { it.isDirectory }
            ?: throw IllegalArgumentException("Destination folder does not exist: $target")
        if (move) requireMutablePath(root, source)
        requireMutablePath(root, target)
        val before = attributes(target)
        if (before != null) {
            require(before.isRegularFile && !Files.isSameFile(source.toPath(), target.toPath())) {
                "Invalid destination: $target"
            }
            if (!confirmOverwrite(target)) {
                return Output(cwd, listOf("↩️ Cancelled; existing file was left untouched"))
            }
        }

        checkCancelled()
        require(inside(root, source) == source && inside(root, target) == target) {
            "A path changed. Run the command again."
        }
        val staged = if (move) source else FilePublication.temporarySibling(target)
        try {
            if (!move) {
                source.inputStream().use { input ->
                    val size = input.channel.size()
                    onCopyProgress(0L, size)
                    staged.outputStream().use { output ->
                        LocalFileCopy.copy(
                            input = input,
                            output = output,
                            directory = targetDirectory,
                            expectedSize = size,
                            checkCancelled = checkCancelled,
                            onProgress = { copied -> onCopyProgress(copied, size) },
                            availableSpace = availableSpace,
                        )
                    }
                }
            }
            checkCancelled()
            require(inside(root, target) == target) { "Destination path changed: $target" }
            if (before != null) {
                val now = attributes(target)
                require(now != null && now.isRegularFile && now.fileKey() == before.fileKey() &&
                    now.lastModifiedTime() == before.lastModifiedTime() && now.size() == before.size()
                ) { "Destination changed. Run the command again: $target" }
            }
            val output = Output(cwd, listOf("✅ ${if (move) "Moved" else "Copied"} ${source.name} → ${target.path}"))
            cancellation.commit {
                checkCancelled()
                when {
                    before == null -> FilePublication.publishNew(staged, target)
                    move -> Files.move(staged.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    else -> FilePublication.replace(staged, target)
                }
            }
            onCommitted(output)
            return output
        } finally {
            if (!move) staged.delete()
        }
    }

    private fun requireMutablePath(root: File, path: File) {
        val canonical = path.canonicalFile.toPath()
        val relative = root.toPath().relativize(canonical)
        val logs = File(root, "logs").canonicalFile.toPath()
        require(!canonical.startsWith(logs) && relative.none { it.toString().startsWith(".nekoflash-") }) {
            "Cannot modify an app-managed workspace path: $path"
        }
    }

    private suspend fun runDeviceCommand(
        command: List<String>,
        input: List<String>?,
        directory: File?,
    ): CommandResult = coroutineScope {
        val process = try {
            ProcessBuilder(command).apply {
                directory?.takeIf { it.isDirectory }?.let { this.directory(it) }
                environment()["PATH"] = SYSTEM_PATHS.joinToString(":")
                environment()["LANG"] = "C"
            }.start()
        } catch (error: IOException) {
            throw DeviceCommandUnavailable(error)
        } catch (error: SecurityException) {
            throw DeviceCommandUnavailable(IOException(error.message ?: "execution denied", error))
        }

        try {
            val stdout = async(Dispatchers.IO) { readProcessOutput(process.inputStream) }
            val stderr = async(Dispatchers.IO) { readProcessOutput(process.errorStream) }
            val stdin = async(Dispatchers.IO) {
                try {
                    process.outputStream.bufferedWriter().use { writer -> input?.forEach { writer.appendLine(it) } }
                } catch (error: IOException) {
                    if (process.isAlive) throw error
                    Unit
                }
            }
            val waiter = async(Dispatchers.IO) { process.waitFor() }
            val exitCode = withTimeoutOrNull(COMMAND_TIMEOUT_MS) { waiter.await() }
                ?: throw IOException("Android diagnostic timed out after ${COMMAND_TIMEOUT_MS / 1000}s")
            stdin.await()
            val output = stdout.await()
            val errors = stderr.await().map { "stderr: $it" }
            CommandResult(exitCode, output, diagnostics = errors)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun readProcessOutput(stream: java.io.InputStream): List<String> {
        val lines = mutableListOf<String>()
        var totalChars = 0
        var truncated = false
        stream.bufferedReader().use { reader ->
            while (true) {
                val line = StringBuilder()
                var abbreviated = false
                var readAny = false
                var lineTerminated = false
                while (true) {
                    val code = reader.read()
                    if (code < 0) break
                    if (code == '\n'.code) {
                        lineTerminated = true
                        break
                    }
                    readAny = true
                    if (code == '\r'.code) continue
                    if (line.length < MAX_TEXT_PREVIEW_CHARS) line.append(code.toChar())
                    else abbreviated = true
                }
                if (!readAny && !lineTerminated) break
                val safeLine = line.toString() + if (abbreviated) " … [line abbreviated]" else ""
                if (lines.size < MAX_OUTPUT_LINES && totalChars + safeLine.length <= MAX_TEXT_OUTPUT_CHARS) {
                    lines += safeLine
                    totalChars += safeLine.length
                } else {
                    truncated = true
                }
            }
        }
        if (truncated && lines.size < MAX_OUTPUT_LINES) lines += "… Device output truncated."
        return lines
    }

    private fun resolveDeviceCommand(name: String): List<String>? {
        if (name !in DEVICE_DIAGNOSTIC_COMMANDS) return null
        SYSTEM_PATHS.forEach { directory ->
            val binary = File(directory, name)
            if (binary.isFile && binary.canExecute()) {
                val canonical = binary.canonicalFile
                if (SYSTEM_PATHS.any { canonical.toPath().startsWith(File(it).toPath()) }) {
                    return listOf(canonical.absolutePath)
                }
            }
        }
        if (name in TOYBOX_DIAGNOSTIC_APPLETS) {
            val toybox = File("/system/bin/toybox")
            if (toybox.isFile && toybox.canExecute()) return listOf(toybox.absolutePath, name)
        }
        return null
    }

    private fun validateDeviceArguments(
        name: String,
        args: List<FastbootCommandLine.Token>,
    ): List<String> {
        val values = args.map { it.value }
        val valid = when (name) {
            "getprop" -> values.size <= 1 && values.all { it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")) }
            "id" -> values.isEmpty() || (values.size == 1 && values.single() in SAFE_ID_OPTIONS)
            "uname" -> values.isEmpty() || (values.size == 1 && values.single() in SAFE_UNAME_OPTIONS)
            "df" -> values.isEmpty() || values == listOf("-h")
            "ps" -> values.isEmpty() || values == listOf("-A")
            "uptime", "whoami", "date" -> values.isEmpty()
            else -> false
        }
        require(valid) { "Arguments are not allowed for read-only diagnostic '$name'" }
        return values
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
        require(resolved.toPath().startsWith(root.toPath())) {
            "Path is outside the NekoFlash workspace: $candidate"
        }
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
        const val MAX_OUTPUT_LINES = 200
        const val MAX_TEXT_PREVIEW_CHARS = 8_192
        const val MAX_TEXT_OUTPUT_CHARS = 65_536
        const val COMMAND_TIMEOUT_MS = 30_000L
        val SYSTEM_PATHS = listOf(
            "/system/bin", "/system/xbin", "/system_ext/bin", "/vendor/bin",
            "/vendor/xbin", "/product/bin", "/odm/bin",
        )
        val COMMANDS = setOf("pwd", "ls", "cd", "grep", "cp", "mv", "mkdir", "cat", "rm")
        val STATEFUL_COMMANDS = setOf("cd", "export", "clear")
        val BLOCKED_EXECUTABLES = setOf(
            "sh", "bash", "mksh", "ash", "dash", "zsh", "ksh", "toybox", "busybox", "su", "run-as",
        )
        val DEVICE_DIAGNOSTIC_COMMANDS = setOf("getprop", "id", "uname", "uptime", "whoami", "df", "ps", "date")
        val TOYBOX_DIAGNOSTIC_APPLETS = setOf("id", "uname", "uptime", "df", "ps", "date")
        val SAFE_ID_OPTIONS = setOf("-u", "-g", "-G", "-n", "-r")
        val SAFE_UNAME_OPTIONS = setOf("-a", "-s", "-r", "-m", "-n", "-v", "-p", "-o")
        val HELP = listOf(
            "Commands: echo, pwd, ls, cd, grep, cp, mv, mkdir, cat, rm, clear",
            "Use 'local' for local commands; quote paths containing spaces.",
            "&& runs the next command only after success; | passes output to the next command.",
            "App-managed file commands stay inside Download/NekoFlash; rm removes files only and asks first.",
            "Logs and .nekoflash-* staging files can be read but not modified.",
            "Device diagnostics default to off; enable: export NEKO_USE_DEVICE_BINARIES=1",
            "Allowed diagnostics: getprop, id, uname, uptime, whoami, df, ps, date.",
            "System diagnostics run without a shell and with the app's Android UID; su and run-as are blocked.",
            "No shell interpreters, arbitrary executables, scripts, redirection, or root escalation are exposed.",
        )
    }
}

internal fun extractLocalCommand(raw: String): String? {
    val clean = raw.trimStart()
    val separator = clean.indexOfFirst(Char::isWhitespace)
    val first = if (separator < 0) clean else clean.substring(0, separator)
    if (first == "local") return if (separator < 0) "" else clean.substring(separator).trimStart()
    if (hasUnsupportedShellSyntax(clean)) return null
    return when (first) {
        "echo", "clear" -> clean
        "export" -> clean.takeIf { it.startsWith("export NEKO_USE_DEVICE_BINARIES=") }
        else -> null
    }
}

private fun hasUnsupportedShellSyntax(line: String): Boolean {
    var quote: Char? = null
    var escaped = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            escaped -> escaped = false
            char == '\\' -> escaped = true
            quote != null -> if (char == quote) quote = null
            char == '\'' || char == '"' -> quote = char
            char == '>' || char == '<' || char == ';' || char == '`' -> return true
            line.startsWith("||", index) -> return true
            line.startsWith("&&", index) -> index++
            char == '&' -> return true
            line.startsWith("$(", index) -> return true
        }
        index++
    }
    return false
}
