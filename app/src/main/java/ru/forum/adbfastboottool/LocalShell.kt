package ru.forum.adbfastboottool

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
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

    suspend fun execute(
        line: String,
        workspace: suspend () -> File?,
        confirmOverwrite: suspend (File) -> Boolean
    ): Output? = lock.withLock {
        withContext(Dispatchers.IO) {
            val words = try {
                FastbootCommandLine.tokenize(line)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Unclosed quote or trailing escape in local command", error)
            }
            val command = words.firstOrNull()?.value.orEmpty()
            val args = words.drop(1)
            if (command == "echo") return@withContext Output(cwd, listOf(args.joinToString(" ") { it.value }))
            if (command == "help" || command.isEmpty()) return@withContext Output(cwd, HELP)
            if (command !in COMMANDS) {
                return@withContext Output(cwd, listOf("Unknown local command: $command", *HELP.toTypedArray()))
            }

            val root = workspace()?.canonicalFile ?: return@withContext null
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
                    val item = args.firstOrNull()?.let(::path) ?: inside(root, directory)
                    require(item.exists()) { "No such file or directory: $item" }
                    if (item.isFile) listOf(item.name)
                    else requireNotNull(item.list()) { "Cannot list: $item" }.sorted()
                        .ifEmpty { listOf("📂 (empty directory)") }
                }
                "grep" -> {
                    require(args.size == 2) { "Usage: local grep TEXT FILE" }
                    val file = path(args[1])
                    require(file.isFile) { "Not a file: $file" }
                    val job = currentCoroutineContext()
                    file.useLines { source ->
                        source.filter { job.ensureActive(); it.contains(args[0].value) }
                            .take(200).toList()
                    }.ifEmpty { listOf("🔎 No matches") }
                }
                else -> {
                    require(args.size == 2) { "Usage: local $command SOURCE DEST" }
                    listOf(transfer(command == "mv", root, path(args[0]), path(args[1]), confirmOverwrite))
                }
            }
            Output(cwd, lines)
        }
    }

    private suspend fun transfer(
        move: Boolean,
        root: File,
        source: File,
        destination: File,
        confirmOverwrite: suspend (File) -> Boolean
    ): String {
        require(source.isFile) { "Not a file: $source" }
        val target = if (destination.isDirectory) inside(root, File(destination, source.name)) else destination
        require(target.parentFile?.isDirectory == true) { "Destination folder does not exist: $target" }
        val before = attributes(target)
        if (before != null) {
            require(before.isRegularFile && !Files.isSameFile(source.toPath(), target.toPath())) {
                "Invalid destination: $target"
            }
            if (!confirmOverwrite(target)) return "↩️ Cancelled; existing file was left untouched"
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
        return "✅ ${if (move) "Moved" else "Copied"} ${source.name} → ${target.path}"
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
        val COMMANDS = setOf("pwd", "ls", "cd", "grep", "cp", "mv")
        val HELP = listOf(
            "Local commands: echo, pwd, ls, cd, grep, cp, mv",
            "Use: local ls | local cd \"ROM files\" | local grep ERROR \"flash log.txt\"",
            "~ is Download/NekoFlash. File operations stay inside this workspace.",
            "cp/mv handle individual files and ask before replacing an existing file."
        )
    }
}

internal fun extractLocalCommand(raw: String): String? {
    val clean = raw.trimStart()
    val separator = clean.indexOfFirst(Char::isWhitespace)
    val first = if (separator < 0) clean else clean.substring(0, separator)
    return when (first) {
        "local" -> if (separator < 0) "" else clean.substring(separator).trimStart()
        "echo" -> clean
        else -> null
    }
}
