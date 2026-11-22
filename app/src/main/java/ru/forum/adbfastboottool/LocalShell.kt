package ru.forum.adbfastboottool

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class LocalShell {
    data class Output(val directory: File?, val lines: List<String>)

    private val lock = Mutex()
    @Volatile private var home: File? = null
    @Volatile private var cwd: File? = null

    fun prompt(directory: File? = cwd): String {
        val root = home ?: return "~"
        val current = directory ?: root
        val relative = root.toPath().relativize(current.toPath()).toString()
        return if (relative.isEmpty()) "~" else "~/$relative"
    }

    fun quickOutput(line: String, help: List<String> = HELP): Output? = quickOutput(tokenize(line), help)

    private fun tokenize(line: String): List<FastbootCommandLine.Token> = try {
        FastbootCommandLine.tokenize(line)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("Unclosed quote or trailing escape in local command", error)
    }

    private fun quickOutput(words: List<FastbootCommandLine.Token>, help: List<String>): Output? {
        val command = words.firstOrNull()?.value.orEmpty()
        return when (command) {
            "echo" -> Output(cwd, listOf(words.drop(1).joinToString(" ") { it.value }))
            "", "help" -> Output(cwd, help)
            in COMMANDS -> null
            else -> Output(cwd, listOf("Unknown local command: $command") + help)
        }
    }

    suspend fun execute(
        line: String,
        workspace: suspend () -> File?,
        cancellation: ImportCancellation = ImportCancellation(),
        onCopyProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
        confirmOverwrite: suspend (File) -> Boolean
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
                val words = tokenize(line)
                quickOutput(words, HELP)?.let { return@withContext it }
                val command = words.first().value
                val args = words.drop(1)

                val root = workspace()?.canonicalFile ?: return@withContext null
                checkCancelled()
                require(root.isDirectory) { "Workspace folder is unavailable: $root" }
                if (home != root) {
                    home = root
                    cwd = root
                }
                val directory = checkNotNull(cwd)
                fun path(token: FastbootCommandLine.Token) = resolve(root, directory, token)

                val lines = when (command) {
                    "pwd" -> {
                        require(args.isEmpty()) { "Usage: local pwd" }
                        listOf(directory.path)
                    }
                    "cd" -> {
                        require(args.size <= 1) { "Usage: local cd [directory]" }
                        val next = args.firstOrNull()?.let(::path) ?: root
                        require(next.isDirectory) { "Not a directory: $next" }
                        cwd = next
                        emptyList()
                    }
                    "ls" -> {
                        require(args.size <= 1) { "Usage: local ls [path]" }
                        listEntries(args.firstOrNull()?.let(::path) ?: inside(root, directory), checkCancelled)
                    }
                    "grep" -> {
                        require(args.size == 2) { "Usage: local grep TEXT FILE" }
                        grep(path(args[1]), args[0].value, checkCancelled)
                    }
                    "mkdir" -> {
                        require(args.size == 1) { "Usage: local mkdir DIR" }
                        val folder = path(args.single())
                        requireMutablePath(root, folder)
                        val output = Output(cwd, listOf("✅ Created directory: ${folder.path}"))
                        cancellation.commit {
                            checkCancelled()
                            Files.createDirectory(folder.toPath())
                        }
                        committedOutput = output
                        return@withContext output
                    }
                    else -> {
                        require(args.size == 2) { "Usage: local $command SOURCE DEST" }
                        return@withContext transfer(command == "mv", root, path(args[0]), path(args[1]),
                            cancellation, checkCancelled, onCopyProgress, confirmOverwrite) { committedOutput = it }
                    }
                }
                Output(cwd, lines)
            }
        } catch (cancelled: CancellationException) {
            // A late coroutine cancellation can discard withContext's result after a workspace change.
            committedOutput ?: throw cancelled
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

    private fun grep(file: File, query: String, checkCancelled: () -> Unit): List<String> {
        require(file.isFile) { "Not a file: $file" }
        val matches = mutableListOf<String>()
        var outputChars = 0
        val preview = StringBuilder()
        val prefixes = literalPrefixes(query)
        var matched = query.isEmpty()
        var matchedOutsidePreview = false
        var abbreviated = false
        var prefixLength = 0
        fun collectLine(): Boolean {
            if (!matched) return true
            val display = if (!abbreviated) preview.toString() else buildString {
                if (matchedOutsidePreview) {
                    val excerpt = query.take(MAX_GREP_PREVIEW_CHARS)
                    val openingLength = MAX_GREP_PREVIEW_CHARS - excerpt.length
                    append(preview, 0, openingLength)
                    if (openingLength > 0) append(" … ")
                    append(excerpt)
                } else {
                    append(preview)
                }
                append(" … [line abbreviated]")
            }
            if (matches.size == MAX_OUTPUT_LINES || outputChars + display.length > MAX_GREP_OUTPUT_CHARS) return false
            matches.add(display)
            outputChars += display.length
            return true
        }

        file.reader().use { input ->
            val chunk = CharArray(4_096)
            var previousWasCr = false
            while (true) {
                checkCancelled()
                val count = input.read(chunk)
                if (count < 0) break
                for (index in 0 until count) {
                    val char = chunk[index]
                    if (char == '\n' && previousWasCr) {
                        previousWasCr = false
                        continue
                    }
                    previousWasCr = char == '\r'
                    if (char == '\n' || previousWasCr) {
                        if (!collectLine()) return matches + GREP_OUTPUT_LIMIT
                        preview.clear()
                        matched = query.isEmpty()
                        matchedOutsidePreview = false
                        abbreviated = false
                        prefixLength = 0
                    } else {
                        if (preview.length < MAX_GREP_PREVIEW_CHARS) preview.append(char)
                        else abbreviated = true
                        if (!matched) {
                            while (prefixLength > 0 && char != query[prefixLength]) {
                                prefixLength = prefixes[prefixLength - 1]
                            }
                            if (char == query[prefixLength]) prefixLength++
                            if (prefixLength == query.length) {
                                matched = true
                                matchedOutsidePreview = abbreviated
                            }
                        }
                    }
                }
            }
        }
        if (preview.isNotEmpty() && !collectLine()) return matches + GREP_OUTPUT_LIMIT
        return matches.ifEmpty { listOf("🔎 No matches") }
    }

    // Keep partial literal matches as the reader advances without retaining complete lines.
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
        onCommitted: (Output) -> Unit
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
            if (!confirmOverwrite(target)) return Output(cwd, listOf("↩️ Cancelled; existing file was left untouched"))
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
                            input, output, targetDirectory, size, checkCancelled,
                            onProgress = { copied -> onCopyProgress(copied, size) },
                            availableSpace = { it.usableSpace }
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
        val canonical = path.toPath()
        val relative = root.toPath().relativize(canonical)
        val logs = File(root, "logs").canonicalFile.toPath()
        require(!canonical.startsWith(logs) && relative.none { it.toString().startsWith(".nekoflash-") }) {
            "Cannot modify an app-managed workspace path: $path"
        }
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

    private companion object {
        const val MAX_OUTPUT_LINES = 200
        const val MAX_GREP_PREVIEW_CHARS = 8_192
        const val MAX_GREP_OUTPUT_CHARS = 65_536
        const val GREP_OUTPUT_LIMIT = "… Grep output limit reached."
        val COMMANDS = setOf("pwd", "ls", "cd", "grep", "cp", "mv", "mkdir")
        val HELP = listOf(
            "Local commands: echo, pwd, ls, cd, grep, cp, mv, mkdir",
            "local ls",
            "local cd \"ROM files\"",
            "local grep ERROR \"flash log.txt\"",
            "local mkdir \"new folder\"",
            "~ is Download/NekoFlash. File operations stay inside this workspace.",
            "cp/mv handle individual files and ask before replacing an existing file.",
            "Logs and NekoFlash staging files cannot be moved or overwritten."
        )
    }
}

internal fun extractLocalCommand(raw: String): String? {
    val clean = raw.trimStart()
    val separator = clean.indexOfFirst(Char::isWhitespace)
    val first = if (separator < 0) clean else clean.substring(0, separator)
    if (first != "local") return null
    return if (separator < 0) "" else clean.substring(separator).trimStart()
}
