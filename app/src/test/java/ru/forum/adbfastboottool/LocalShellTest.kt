package ru.forum.adbfastboottool

import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LocalShellTest {
    private fun newShell() = LocalShell { Files.getFileStore(it.toPath()).usableSpace }

    private fun withWorkspace(test: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("nekoflash-local").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    @Test fun echoAndHelpDoNotRequestStorage() = runBlocking {
        val shell = newShell()
        val noWorkspace: suspend () -> File? = { error("workspace must not be requested") }
        val echo = shell.execute("echo \"hello world\"", noWorkspace) { false }
        val help = shell.execute("help", noWorkspace) { false }
        assertEquals(listOf("hello world"), echo?.lines)
        assertTrue(help?.lines?.any { it.contains("echo, pwd, ls, cd, grep, cp, mv") } == true)
    }

    @Test fun quickOutputUsesLocalizedHelpAndLeavesFileCommandsToTheWorker() {
        val shell = newShell()
        val help = listOf("Справка по локальным командам", "local mkdir \"новая папка\"")
        assertEquals(help, shell.quickOutput("help", help)?.lines)
        assertEquals(help, shell.quickOutput("", help)?.lines)
        assertEquals(help, shell.quickOutput("another-command", help)?.lines?.drop(1))
        assertEquals(listOf("hello world"), shell.quickOutput("echo \"hello world\"", help)?.lines)
        assertNull(shell.quickOutput("mkdir \"новая папка\"", help))
        assertNull(shell.quickOutput("cat \"flash log.txt\"", help))
        assertTrue(runCatching { shell.quickOutput("echo safe > file.txt", help) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun quotedPathsCdPwdAndTildeStayInWorkspace() = withWorkspace { root ->
        val folder = File(root, "ROM files").apply { mkdir() }
        File(folder, "flash log.txt").writeText("ERROR: failed\nOK\n")
        val shell = newShell()
        val workspace: suspend () -> File? = { root }

        assertEquals(listOf("ERROR: failed"), shell.execute("grep ERROR \"ROM files/flash log.txt\"", workspace) { false }?.lines)
        shell.execute("cd \"ROM files\"", workspace) { false }
        assertEquals("~/ROM files", shell.prompt())
        assertEquals(folder.canonicalPath, shell.execute("pwd", workspace) { false }?.lines?.single())
        assertEquals(listOf("flash log.txt"), shell.execute("ls", workspace) { false }?.lines)
        assertEquals(listOf("flash log.txt"), shell.execute("ls ~/ROM\\ files", workspace) { false }?.lines)
        shell.execute("cd ~", workspace) { false }
    }

    @Test fun copyMoveAndOverwriteRequireConfirmation() = withWorkspace { root ->
        val source = File(root, "boot image.img").apply { writeText("new") }
        val destination = File(root, "backup").apply { mkdir() }
        File(destination, source.name).writeText("old")
        val shell = newShell()
        val workspace: suspend () -> File? = { root }

        val cancelled = shell.execute("cp \"boot image.img\" backup", workspace) { false }
        assertTrue(cancelled?.lines?.single()?.contains("Cancelled") == true)
        assertEquals("old", File(destination, source.name).readText())

        shell.execute("cp \"boot image.img\" backup", workspace) { true }
        assertEquals("new", File(destination, source.name).readText())
        val moveTarget = File(root, "move-target.img").apply { writeText("old move") }
        val moveCancelled = shell.execute("mv \"boot image.img\" move-target.img", workspace) { false }
        assertTrue(moveCancelled?.lines?.single()?.contains("Cancelled") == true)
        assertTrue(source.exists())
        assertEquals("old move", moveTarget.readText())

        val moveCompleted = shell.execute("mv \"boot image.img\" move-target.img", workspace) { true }
        assertTrue(moveCompleted?.lines?.single()?.contains("Moved") == true)
        assertFalse(source.exists())
        assertEquals("new", moveTarget.readText())
    }

    @Test fun copyReportsProgressAndWritesExactBytesIntoNewDirectory() = withWorkspace { root ->
        val bytes = ByteArray(2 * 1024 * 1024 + 31) { (it % 251).toByte() }
        val source = File(root, "test image.img").apply { writeBytes(bytes) }
        val shell = newShell()
        val updates = mutableListOf<Pair<Long, Long>>()
        val size = bytes.size.toLong()

        shell.execute("mkdir \"backup folder\"", { root }) { error("Unexpected confirmation") }
        shell.execute("cp \"${source.name}\" \"backup folder\"", { root }, onCopyProgress = { copied, total ->
            updates.add(copied to total)
        }) { error("Unexpected confirmation") }

        assertEquals(0L to size, updates.first())
        assertEquals(size to size, updates.last())
        assertTrue(updates.size > 2)
        assertTrue(updates.all { (_, total) -> total == size })
        assertTrue(updates.zipWithNext().all { (previous, current) -> previous.first < current.first })
        assertArrayEquals(bytes, File(root, "backup folder/${source.name}").readBytes())
    }

    @Test fun pathsCannotEscapeWorkspace() = withWorkspace { root ->
        val shell = newShell()
        val error = try {
            shell.execute("cd ..", { root }) { false }
            null
        } catch (failure: Exception) {
            failure
        }
        assertTrue(error is IllegalArgumentException)
        assertEquals("~", shell.prompt())
    }


    @Test fun catAndPipelinesPassOutputBetweenCommands() = withWorkspace { root ->
        File(root, "flash.log").writeText("INFO start\nERROR boot failed\nINFO done\n")
        val shell = newShell()
        val workspace: suspend () -> File? = { root }

        val piped = shell.execute("local cat flash.log | grep ERROR", workspace) { false }
        assertEquals(listOf("ERROR boot failed"), piped?.lines)

        val chained = shell.execute("local echo first && echo second", workspace) { false }
        assertEquals(listOf("first", "second"), chained?.lines)

        val skipped = shell.execute("local grep MISSING flash.log && echo should-not-run", workspace) { false }
        assertEquals(listOf("🔎 No matches"), skipped?.lines)

        val completedPipeline = shell.execute("local grep MISSING flash.log | cat && echo continued", workspace) { false }
        assertEquals(listOf("continued"), completedPipeline?.lines)
        val redirection = runCatching { shell.execute("echo unsafe > flash.log", workspace) { false } }.exceptionOrNull()
        assertTrue(redirection is IllegalArgumentException)
    }

    @Test fun clearAndDeviceBinaryPreferenceAreSessionScoped() = runBlocking {
        val shell = newShell()
        val noWorkspace: suspend () -> File? = { error("workspace must not be requested") }

        val enabled = shell.execute("export NEKO_USE_DEVICE_BINARIES=1", noWorkspace) { false }
        assertTrue(enabled?.deviceBinariesEnabled == true)
        assertTrue(enabled?.lines?.single()?.contains("enabled") == true)

        val clear = shell.execute("clear", noWorkspace) { false }
        assertTrue(clear?.clearTerminal == true)
        assertTrue(clear?.deviceBinariesEnabled == true)

        val disabled = shell.execute("export NEKO_USE_DEVICE_BINARIES=0", noWorkspace) { false }
        assertFalse(disabled?.deviceBinariesEnabled == true)
    }

    @Test fun removeRequiresConfirmationAndCannotDeleteDirectories() = withWorkspace { root ->
        val file = File(root, "old.img").apply { writeText("data") }
        val directory = File(root, "folder").apply { mkdir() }
        val shell = newShell()
        val workspace: suspend () -> File? = { root }

        val cancelled = shell.execute(
            "local rm old.img", workspace,
            confirmDelete = { false }, confirmOverwrite = { error("Unexpected overwrite confirmation") },
        )
        assertTrue(file.exists())
        assertTrue(cancelled?.lines?.single()?.contains("Cancelled") == true)

        val removed = shell.execute(
            "local rm old.img", workspace,
            confirmDelete = { true }, confirmOverwrite = { error("Unexpected overwrite confirmation") },
        )
        assertFalse(file.exists())
        assertTrue(removed?.lines?.single()?.contains("Removed") == true)

        val error = runCatching {
            shell.execute(
                "local rm folder", workspace,
                confirmDelete = { true }, confirmOverwrite = { error("Unexpected overwrite confirmation") },
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(directory.exists())
    }

    @Test fun operatorsInsideQuotesAreNotParsedAsShellOperators() = runBlocking {
        val shell = newShell()
        val noWorkspace: suspend () -> File? = { error("workspace must not be requested") }
        val output = shell.execute("echo \"a && b | c\"", noWorkspace) { false }
        assertEquals(listOf("a && b | c"), output?.lines)
    }

    @Test fun terminalRecognizesOnlyLocalPrefixAndBareEcho() {
        assertEquals("ls \"ROM files\"", extractLocalCommand("local ls \"ROM files\""))
        assertEquals("echo hello", extractLocalCommand("local echo hello"))
        assertEquals("", extractLocalCommand("local"))
        assertNull(extractLocalCommand("echo hello > output.txt"))
        assertNull(extractLocalCommand("fastboot reboot"))
        assertNull(extractLocalCommand("adb shell ls"))
        assertEquals("clear", extractLocalCommand("clear"))
        assertEquals("export NEKO_USE_DEVICE_BINARIES=1", extractLocalCommand("export NEKO_USE_DEVICE_BINARIES=1"))
    }


    @Test fun shellInterpretersRemainUnavailableEvenWithDeviceBinariesEnabled() = runBlocking {
        val shell = newShell()
        val noWorkspace: suspend () -> File? = { error("workspace must not be requested") }
        shell.execute("export NEKO_USE_DEVICE_BINARIES=1", noWorkspace) { false }
        val result = shell.execute("local sh -c echo", noWorkspace) { false }
        assertTrue(result?.lines?.single()?.contains("blocked") == true)
    }

    @Test fun privilegeEscalationAndArbitraryBinariesStayBlocked() = runBlocking {
        val shell = newShell()
        val noWorkspace: suspend () -> File? = { error("workspace must not be requested") }
        shell.execute("export NEKO_USE_DEVICE_BINARIES=1", noWorkspace) { false }
        for (command in listOf("sh", "bash", "toybox", "busybox", "su", "run-as")) {
            val result = shell.execute("local $command", noWorkspace) { false }
            assertTrue("$command must be blocked", result?.lines?.single()?.contains("blocked") == true)
        }
        val arbitrary = shell.execute("local chmod", noWorkspace) { false }
        assertTrue(arbitrary?.lines?.single()?.contains("not allowed") == true)
        val optionLikeProperty = runCatching { shell.execute("local getprop --", noWorkspace) { false } }.exceptionOrNull()
        assertTrue(optionLikeProperty is IllegalArgumentException)
    }

    @Test fun localTokenizerErrorsDoNotMentionFastboot() = runBlocking {
        val shell = newShell()
        val error = try {
            shell.execute("grep \"unterminated file.txt", { null }) { false }
            null
        } catch (failure: IllegalArgumentException) {
            failure
        }
        assertEquals("Unclosed quote or trailing escape in local command", error?.message)
    }

    @Test fun grepContinuesPastLongLinesAndFindsMatchesAcrossChunks() = withWorkspace { root ->
        val file = File(root, "input.txt")
        // The first match starts two characters before the end of a read chunk.
        file.writeText("x".repeat(12_286) + "match late\r\n" + "y".repeat(20_000) + "\rmatch later")
        val output = requireNotNull(newShell().execute("grep match input.txt", { root }) { false }).lines
        assertEquals(2, output.size)
        assertTrue(output.first().contains("match"))
        assertTrue(output.first().endsWith("[line abbreviated]"))
        assertTrue(output.first().length < 8_300)
        assertEquals("match later", output.last())
    }

    @Test fun grepCapsTotalOutput() = withWorkspace { root ->
        val file = File(root, "input.txt")
        val shell = newShell()
        val workspace: suspend () -> File? = { root }

        file.writeText(("match " + "x".repeat(2_000) + "\n").repeat(100))
        val capped = requireNotNull(shell.execute("grep match input.txt", workspace) { false }).lines
        assertTrue(capped.dropLast(1).isNotEmpty())
        assertTrue(capped.dropLast(1).sumOf { it.length } <= 65_536)
        assertTrue(capped.last().contains("output limit reached"))
    }

    @Test fun catReadsQuotedFileAsLiteralTextAndPreservesBlankLines() = withWorkspace { root ->
        File(root, "flash log.txt").writeText("Готово | ready\r\n\r\nlast line")
        val output = newShell().execute("cat \"flash log.txt\"", { root }) { false }
        assertEquals(listOf("Готово | ready", "", "last line"), output?.lines)
    }

    @Test fun catReportsAnEmptyFile() = withWorkspace { root ->
        File(root, "empty.txt").writeText("")
        assertEquals(listOf("📄 (empty file)"), newShell().execute("cat empty.txt", { root }) { false }?.lines)
    }

    @Test fun catContinuesAfterAbbreviatingALongLineAndNamesTheOutputLimit() = withWorkspace { root ->
        File(root, "input.txt").writeText("x".repeat(20_000) + "\nfollowing line\n" + "other line\n".repeat(200))
        val lines = requireNotNull(newShell().execute("cat input.txt", { root }) { false }).lines
        assertTrue(lines.first().endsWith("[line abbreviated]"))
        assertTrue(lines.first().length < 8_300)
        assertEquals("following line", lines[1])
        assertEquals(200, lines.dropLast(1).size)
        assertEquals("… Cat output limit reached.", lines.last())
    }

    @Test fun directoryListingCapsNumberOfItems() = withWorkspace { root ->
        repeat(215) { File(root, "item-$it").createNewFile() }
        val lines = requireNotNull(newShell().execute("ls", { root }) { false }).lines
        assertEquals(200, lines.dropLast(1).size)
        assertTrue(lines.last().contains("listing limited"))
    }

    @Test fun appManagedFilesMayBeReadAndCopiedOutButNotMutated() = withWorkspace { root ->
        val log = File(root, "logs/session.txt").apply { parentFile!!.mkdir(); writeText("session") }
        val stage = File(root, "nested/.nekoflash-import-test/pending.part").apply {
            parentFile!!.mkdirs()
            writeText("staged")
        }
        val shell = newShell()
        val workspace: suspend () -> File? = { root }
        assertEquals(listOf("session"), shell.execute("grep session logs/session.txt", workspace) { false }?.lines)
        shell.execute("cp logs/session.txt copied.txt", workspace) { false }
        assertEquals("session", File(root, "copied.txt").readText())
        val commands = listOf(
            "mv logs/session.txt moved.txt",
            "cp copied.txt logs/session.txt",
            "mv nested/.nekoflash-import-test/pending.part moved.txt",
            "cp copied.txt nested/.nekoflash-new.part"
        )
        for (command in commands) {
            val error = runCatching { shell.execute(command, workspace) { error("Must fail before confirmation") } }
                .exceptionOrNull()
            assertTrue(error is IllegalArgumentException && error.message.orEmpty().contains("app-managed"))
        }
        assertEquals("session", log.readText())
        assertEquals("staged", stage.readText())
        assertFalse(File(root, "moved.txt").exists())
    }

    @Test fun cancellationBeforePublishPreservesSourceAndDestination() = withWorkspace { root ->
        val source = File(root, "source.txt").apply { writeText("source") }
        val target = File(root, "target.txt").apply { writeText("target") }
        val cancellation = ImportCancellation()
        val error = runCatching {
            newShell().execute("mv source.txt target.txt", { root }, cancellation) {
                assertTrue(cancellation.cancel())
                true
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals("source", source.readText())
        assertEquals("target", target.readText())
    }

    @Test fun cancellationAfterPublishPreservesSuccessfulResult() = withWorkspace { root ->
        coroutineScope {
            val source = File(root, "source.txt").apply { writeText("source") }
            val target = File(root, "target.txt")
            val cancellation = ImportCancellation()
            val allowPublish = CompletableDeferred<Unit>()
            val pendingResume = CompletableDeferred<Runnable>()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    if (!pendingResume.complete(block)) block.run()
                }
            }
            var output: LocalShell.Output? = null
            val command = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                output = newShell().execute("mv source.txt target.txt", {
                    allowPublish.await()
                    root
                }, cancellation) { false }
            }
            try {
                // The caller must have suspended before IO completes and dispatches its resume.
                allowPublish.complete(Unit)
                withTimeout(10_000) { pendingResume.await() }
                assertFalse(source.exists())
                assertEquals("source", target.readText())
                assertFalse(cancellation.cancel())
            } finally {
                command.cancel()
                // Release a captured resume or allow a delayed dispatch to run immediately.
                pendingResume.complete(Runnable {})
                withContext(NonCancellable) { pendingResume.await().run() }
            }
            command.join()
            assertTrue(output?.lines?.single()?.contains("Moved") == true)
        }
    }
}
