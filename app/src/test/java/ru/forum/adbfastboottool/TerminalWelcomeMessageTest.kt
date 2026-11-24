package ru.forum.adbfastboottool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalWelcomeMessageTest {
    private fun assertWrappedTextRetained(lines: List<String>, expected: String) {
        val visibleRows = lines.filter { it.startsWith("| ") }
            .joinToString(" ") { it.substring(2, it.length - 2).trim() }
        var offset = 0
        expected.split(Regex("\\s+")).filter(String::isNotEmpty).forEach { word ->
            val index = visibleRows.indexOf(word, offset)
            assertTrue("Missing or reordered welcome text: $word", index >= 0)
            offset = index + word.length
        }
    }

    private fun details() = TerminalWelcomeMessage.Details(
        title = "Welcome to NekoFlash-2.0",
        subtitle = "HOST + DEVICE TERMINAL",
        environment = "ENVIRONMENT",
        hostLabel = "HOST", host = "Android 16 · API 36",
        abiLabel = "ABI", abi = "arm64-v8a",
        workspaceLabel = "HOME", workspace = "Private HOME + public shared link",
        deviceBinaryLabel = "SYSTEM SHELL", deviceBinaryStatus = "ENABLED · SAVED",
        timeLabel = "TIME", time = "21:56",
        dateLabel = "DATE", date = "22 Nov 2026",
        commandSource = "COMMAND ROUTING",
        hostRoute = "HOST = ls · cd · pwd · clear",
        adbRoute = "ADB = adb shell <command>",
        fastbootRoute = "FASTBOOT = fastboot <command>",
        localAlias = "local <command> = force host routing",
        mixedFastboot = "FASTBOOT chain = shell bridge (confirmation)",
        systemShell = "Scripts require opt-in and confirmation",
        enableCommand = "export NEKO_USE_DEVICE_BINARIES=1",
        disableCommand = "export NEKO_USE_DEVICE_BINARIES=0",
        accessLabel = "ACCESS", access = "App UID; not root",
        help = "QUICK HELP", helpHint = "For syntax and examples, execute:",
        transport = "Interactive ADB shell stays on device; local forces host",
    )

    @Test fun everyWelcomeRowFitsTheFixedTerminalWidth() {
        val lines = TerminalWelcomeMessage.layout(details())
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { TerminalWelcomeMessage.displayWidth(it) == 37 })
    }

    @Test fun welcomeTitleAndHelpAreProminentWithoutListingEveryCommand() {
        val lines = TerminalWelcomeMessage.layout(details())
        assertTrue(lines[1].contains("Welcome to NekoFlash-2.0"))
        assertTrue(lines.any { it.contains("help") })
        assertTrue(lines.any { it.contains("HOST = ls") })
        assertTrue(lines.any { it.contains("ADB = adb shell") })
        assertTrue(lines.any { it.contains("FASTBOOT = fastboot") })
        assertTrue(lines.any { it.contains("FASTBOOT chain") })
        assertTrue(lines.any { it.contains("NEKO_USE_DEVICE_BINARIES=1") })
        assertTrue(lines.any { it.contains("NEKO_USE_DEVICE_BINARIES=0") })
        assertTrue(lines.any { it.contains("SYSTEM SHELL") && it.contains("ENABLED") })
        assertFalse(lines.any { it.contains("local ls") || it.contains("local cat") || it.contains("local grep") })
    }

    @Test fun framedRowsRestoreEmojiAndKeepBordersAligned() {
        val lines = TerminalWelcomeMessage.layout(details())
        val framed = lines.filter { it.startsWith("| ") }
        assertTrue(framed.isNotEmpty())
        assertTrue(framed.all { it.first() == '|' && it.last() == '|' })
        assertTrue(framed.all { TerminalWelcomeMessage.displayWidth(it) == 37 })
        listOf("✨", "📱", "🧩", "📁", "🟢", "🕒", "📅", "⌂", "↗", "⚙", "↩", "🔗", "▣", "🔐", "💡", "🔄")
            .forEach { emoji -> assertTrue("Missing $emoji", lines.any { it.contains(emoji) }) }
        assertFalse(framed.any { it.contains("@ ") || it.contains("# ") || it.contains("-> ") })
        assertWrappedTextRetained(lines, details().workspace)
        assertWrappedTextRetained(lines, details().mixedFastboot)
        assertWrappedTextRetained(lines, details().transport)
        assertWrappedTextRetained(lines, details().enableCommand)
        assertWrappedTextRetained(lines, details().disableCommand)
    }

    @Test fun visualRowsAreRecognizedForSemanticColouring() {
        val lines = TerminalWelcomeMessage.layout(details())
        assertTrue(lines.all(TerminalWelcomeMessage::isWelcomeLine))
        assertFalse(TerminalWelcomeMessage.isWelcomeLine("[Local] command completed"))
        assertFalse(TerminalWelcomeMessage.isWelcomeLine("===="))
        assertTrue(TerminalWelcomeMessage.layout(details()).filter { it.startsWith("+") }.all { it.length == 37 })
    }
}
