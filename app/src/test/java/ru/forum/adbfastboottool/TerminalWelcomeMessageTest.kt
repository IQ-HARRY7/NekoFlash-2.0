package ru.forum.adbfastboottool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalWelcomeMessageTest {
    private fun details() = TerminalWelcomeMessage.Details(
        title = "Welcome to NekoFlash-2.0",
        subtitle = "LOCAL TERMINAL",
        environment = "ENVIRONMENT",
        hostLabel = "HOST", host = "Android 16 · API 36",
        abiLabel = "ABI", abi = "arm64-v8a",
        workspaceLabel = "WORKSPACE", workspace = "Download/NekoFlash (~)",
        timeLabel = "TIME", time = "21:56",
        dateLabel = "DATE", date = "22 Nov 2026",
        commandSource = "COMMAND SOURCE",
        defaultLabel = "DEFAULT", appManaged = "App-managed commands",
        deviceLabel = "DEVICE", deviceTools = "Optional Android utilities",
        enableLabel = "ENABLE DEVICE BINARIES", restoreLabel = "RESTORE APP COMMANDS",
        fallbackLabel = "FALLBACK", fallback = "App command if unavailable",
        help = "QUICK HELP", helpHint = "For syntax and examples, run:",
        transport = "ADB / FASTBOOT keep existing routes",
        accessLabel = "ACCESS", access = "App permissions apply; no root assumed",
    )

    @Test fun everyWelcomeRowFitsTheFixedTerminalWidth() {
        val lines = TerminalWelcomeMessage.layout(details())
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.length == 48 })
    }

    @Test fun welcomeTitleAndHelpAreProminentWithoutListingEveryCommand() {
        val lines = TerminalWelcomeMessage.layout(details())
        assertTrue(lines[1].contains("Welcome to NekoFlash-2.0"))
        assertTrue(lines.any { it.contains("local help") })
        assertTrue(lines.any { it.contains("NEKO_USE_DEVICE_BINARIES=1") })
        assertTrue(lines.any { it.contains("NEKO_USE_DEVICE_BINARIES=0") })
        assertFalse(lines.any { it.contains("local ls") || it.contains("local cat") || it.contains("local grep") })
    }

    @Test fun framedRowsUseSingleCellSymbolsSoBordersStayAligned() {
        val lines = TerminalWelcomeMessage.layout(details())
        val framed = lines.filter { it.startsWith("║ ") }
        assertTrue(framed.isNotEmpty())
        assertTrue(framed.all { line ->
            line.none { Character.isHighSurrogate(it) || Character.isLowSurrogate(it) }
        })
        assertTrue(framed.all { it.first() == '║' && it.last() == '║' })
    }

    @Test fun visualRowsAreRecognizedForSemanticColouring() {
        val lines = TerminalWelcomeMessage.layout(details())
        assertTrue(lines.all(TerminalWelcomeMessage::isWelcomeLine))
        assertFalse(TerminalWelcomeMessage.isWelcomeLine("[Local] command completed"))
        assertFalse(TerminalWelcomeMessage.isWelcomeLine("===="))
    }
}
