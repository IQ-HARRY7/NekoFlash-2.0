package ru.forum.adbfastboottool

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalShellTest {
    private fun withWorkspace(test: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("nekoflash-local").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    @Test fun echoAndHelpDoNotRequestStorage() = runBlocking {
        val shell = LocalShell()
        val noWorkspace: suspend () -> File? = { error("workspace must not be requested") }
        val echo = shell.execute("echo \"hello world\"", noWorkspace) { false }
        val help = shell.execute("help", noWorkspace) { false }
        assertEquals(listOf("hello world"), echo?.lines)
        assertTrue(help?.lines?.any { it.contains("echo, pwd, ls, cd, grep, cp, mv") } == true)
    }

    @Test fun quotedPathsCdPwdAndTildeStayInWorkspace() = withWorkspace { root ->
        val folder = File(root, "ROM files").apply { mkdir() }
        File(folder, "flash log.txt").writeText("ERROR: failed\nOK\n")
        val shell = LocalShell()
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
        val shell = LocalShell()
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

    @Test fun pathsCannotEscapeWorkspace() = withWorkspace { root ->
        val shell = LocalShell()
        val error = try {
            shell.execute("cd ..", { root }, { false })
            null
        } catch (failure: Exception) {
            failure
        }
        assertTrue(error is IllegalArgumentException)
        assertEquals("~", shell.prompt())
    }


    @Test fun catAndPipelinesPassOutputBetweenCommands() = withWorkspace { root ->
        File(root, "flash.log").writeText("INFO start\nERROR boot failed\nINFO done\n")
        val shell = LocalShell()
        val workspace: suspend () -> File? = { root }

        val piped = shell.execute("local cat flash.log | grep ERROR", workspace) { false }
        assertEquals(listOf("ERROR boot failed"), piped?.lines)

        val chained = shell.execute("local echo first && echo second", workspace) { false }
        assertEquals(listOf("first", "second"), chained?.lines)

        val skipped = shell.execute("local grep MISSING flash.log && echo should-not-run", workspace) { false }
        assertEquals(listOf("No matches"), skipped?.lines)

        val completedPipeline = shell.execute("local grep MISSING flash.log | cat && echo continued", workspace) { false }
        assertEquals(listOf("continued"), completedPipeline?.lines)
    }

    @Test fun clearAndDeviceBinaryPreferenceAreSessionScoped() = runBlocking {
        val shell = LocalShell()
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
        val shell = LocalShell()
        val workspace: suspend () -> File? = { root }

        val cancelled = shell.execute("local rm old.img", workspace, { false }, { false })
        assertTrue(file.exists())
        assertTrue(cancelled?.lines?.single()?.contains("Cancelled") == true)

        val removed = shell.execute("local rm old.img", workspace, { false }, { true })
        assertFalse(file.exists())
        assertTrue(removed?.lines?.single()?.contains("Removed") == true)

        val error = runCatching {
            shell.execute("local rm folder", workspace, { false }, { true })
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(directory.exists())
    }

    @Test fun operatorsInsideQuotesAreNotParsedAsShellOperators() = runBlocking {
        val shell = LocalShell()
        val noWorkspace: suspend () -> File? = { error("workspace must not be requested") }
        val output = shell.execute("echo \"a && b | c\"", noWorkspace) { false }
        assertEquals(listOf("a && b | c"), output?.lines)
    }

    @Test fun terminalRecognizesOnlyLocalPrefixAndBareEcho() {
        assertEquals("ls \"ROM files\"", extractLocalCommand("local ls \"ROM files\""))
        assertEquals("echo hello", extractLocalCommand("echo hello"))
        assertNull(extractLocalCommand("fastboot reboot"))
        assertNull(extractLocalCommand("adb shell ls"))
        assertEquals("clear", extractLocalCommand("clear"))
        assertEquals("export NEKO_USE_DEVICE_BINARIES=1", extractLocalCommand("export NEKO_USE_DEVICE_BINARIES=1"))
    }


    @Test fun shellInterpretersRemainUnavailableEvenWithDeviceBinariesEnabled() = runBlocking {
        val shell = LocalShell()
        val noWorkspace: suspend () -> File? = { error("workspace must not be requested") }
        shell.execute("export NEKO_USE_DEVICE_BINARIES=1", noWorkspace) { false }
        val result = shell.execute("local sh -c echo", noWorkspace) { false }
        assertTrue(result?.lines?.single()?.contains("script execution are not available") == true)
    }

    @Test fun localTokenizerErrorsDoNotMentionFastboot() = runBlocking {
        val shell = LocalShell()
        val error = try {
            shell.execute("grep \"unterminated file.txt", { null }, { false })
            null
        } catch (failure: IllegalArgumentException) {
            failure
        }
        assertEquals("Unclosed quote or trailing escape in local command", error?.message)
    }
}
