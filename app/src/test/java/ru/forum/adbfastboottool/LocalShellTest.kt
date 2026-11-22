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

    @Test fun terminalRecognizesOnlyLocalPrefixAndBareEcho() {
        assertEquals("ls \"ROM files\"", extractLocalCommand("local ls \"ROM files\""))
        assertEquals("echo hello", extractLocalCommand("echo hello"))
        assertNull(extractLocalCommand("fastboot reboot"))
        assertNull(extractLocalCommand("adb shell ls"))
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
