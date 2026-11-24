package ru.forum.adbfastboottool

import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import java.util.Date

internal object TerminalWelcomeMessage {
    private const val WIDTH = 37
    private const val CONTENT_WIDTH = WIDTH - 4
    const val TEXT_SIZE_SP = 9f

    internal data class Details(
        val title: String,
        val subtitle: String,
        val environment: String,
        val hostLabel: String,
        val host: String,
        val abiLabel: String,
        val abi: String,
        val workspaceLabel: String,
        val workspace: String,
        val deviceBinaryLabel: String,
        val deviceBinaryStatus: String,
        val timeLabel: String,
        val time: String,
        val dateLabel: String,
        val date: String,
        val commandSource: String,
        val hostRoute: String,
        val adbRoute: String,
        val fastbootRoute: String,
        val localAlias: String,
        val mixedFastboot: String,
        val systemShell: String,
        val enableCommand: String,
        val disableCommand: String,
        val accessLabel: String,
        val access: String,
        val help: String,
        val helpHint: String,
        val transport: String,
    )

    fun build(
        context: Context,
        now: Date = Date(),
        deviceBinariesEnabled: Boolean = false,
    ): List<String> {
        val host = context.getString(
            R.string.terminal_welcome_host_value,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
        )
        return layout(
            Details(
                title = context.getString(R.string.terminal_welcome_title),
                subtitle = context.getString(R.string.terminal_welcome_subtitle),
                environment = context.getString(R.string.terminal_welcome_environment),
                hostLabel = context.getString(R.string.terminal_welcome_host_label),
                host = host,
                abiLabel = context.getString(R.string.terminal_welcome_abi_label),
                abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                workspaceLabel = context.getString(R.string.terminal_welcome_workspace_label),
                workspace = context.getString(R.string.terminal_welcome_workspace_value),
                deviceBinaryLabel = context.getString(R.string.terminal_welcome_binary_label),
                deviceBinaryStatus = context.getString(
                    if (deviceBinariesEnabled) R.string.terminal_welcome_binary_enabled
                    else R.string.terminal_welcome_binary_disabled,
                ),
                timeLabel = context.getString(R.string.terminal_welcome_time_label),
                time = DateFormat.getTimeFormat(context).format(now),
                dateLabel = context.getString(R.string.terminal_welcome_date_label),
                date = DateFormat.getDateFormat(context).format(now),
                commandSource = context.getString(R.string.terminal_welcome_command_source),
                hostRoute = context.getString(R.string.terminal_welcome_host_route),
                adbRoute = context.getString(R.string.terminal_welcome_adb_route),
                fastbootRoute = context.getString(R.string.terminal_welcome_fastboot_route),
                localAlias = context.getString(R.string.terminal_welcome_local_alias),
                mixedFastboot = context.getString(R.string.terminal_welcome_mixed_fastboot),
                systemShell = context.getString(R.string.terminal_welcome_system_shell),
                enableCommand = context.getString(R.string.terminal_welcome_enable_command),
                disableCommand = context.getString(R.string.terminal_welcome_disable_command),
                accessLabel = context.getString(R.string.terminal_welcome_access_label),
                access = context.getString(R.string.terminal_welcome_access_value),
                help = context.getString(R.string.terminal_welcome_help),
                helpHint = context.getString(R.string.terminal_welcome_help_hint),
                transport = context.getString(R.string.terminal_welcome_transport),
            ),
        )
    }

    internal fun layout(details: Details): List<String> {
        fun frameRow(text: String): String {
            val padding = " ".repeat((CONTENT_WIDTH - displayWidth(text)).coerceAtLeast(0))
            return "| $text$padding |"
        }
        fun row(value: String): List<String> =
            wrapToDisplayWidth(value, CONTENT_WIDTH).map(::frameRow)

        fun centered(value: String): String {
            val text = takeToDisplayWidth(value, CONTENT_WIDTH)
            val left = ((CONTENT_WIDTH - displayWidth(text)) / 2).coerceAtLeast(0)
            return frameRow(" ".repeat(left) + text)
        }

        fun section(title: String): String {
            val safeTitle = takeToDisplayWidth(title, WIDTH - 6)
            val prefix = "+- $safeTitle "
            return prefix + "-".repeat((WIDTH - displayWidth(prefix) - 1).coerceAtLeast(0)) + "+"
        }

        val border = "+${"-".repeat(WIDTH - 2)}+"
        val divider = "+${"=".repeat(WIDTH - 2)}+"
        return buildList {
            add(border)
            add(centered("✨ ${details.title}"))
            add(centered(details.subtitle))
            add(border)
            add(divider)
            add(section(details.environment))
            addAll(row("📱 ${details.hostLabel} = ${details.host}"))
            addAll(row("🧩 ${details.abiLabel} = ${details.abi}"))
            addAll(row("📁 ${details.workspaceLabel} = ${details.workspace}"))
            addAll(row("${if (details.deviceBinaryStatus.startsWith("ENABLED") || details.deviceBinaryStatus.startsWith("ВКЛЮЧЕНО")) "🟢" else "🔴"} ${details.deviceBinaryLabel} = ${details.deviceBinaryStatus}"))
            addAll(row("🕒 ${details.timeLabel} = ${details.time}"))
            addAll(row("📅 ${details.dateLabel} = ${details.date}"))
            add(border)
            add(divider)
            add(section(details.commandSource))
            addAll(row("⌂ ${details.hostRoute}"))
            addAll(row("↗ ${details.adbRoute}"))
            addAll(row("⚙ ${details.fastbootRoute}"))
            addAll(row("↩ ${details.localAlias}"))
            addAll(row("🔗 ${details.mixedFastboot}"))
            addAll(row("▣ ${details.systemShell}"))
            addAll(row("  ${details.enableCommand}"))
            addAll(row("  ${details.disableCommand}"))
            addAll(row("🔐 ${details.accessLabel} = ${details.access}"))
            add(border)
            add(divider)
            add(section(details.help))
            addAll(row("💡 ${details.helpHint}"))
            add(centered("help"))
            addAll(row("🔄 ${details.transport}"))
            add(border)
            add(divider)
        }
    }

    internal fun isWelcomeLine(line: String): Boolean =
        displayWidth(line) == WIDTH && (
            (line.startsWith("+") && line.endsWith("+")) ||
                (line.startsWith("| ") && line.endsWith(" |"))
            )

    private fun wrapToDisplayWidth(value: String, maxWidth: Int): List<String> {
        val words = value.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var current = StringBuilder()

        fun appendLongWord(word: String) {
            var fragment = StringBuilder()
            var fragmentWidth = 0
            var index = 0
            while (index < word.length) {
                val codePoint = word.codePointAt(index)
                val width = codePointWidth(word, index, codePoint)
                if (fragmentWidth + width > maxWidth && fragment.isNotEmpty()) {
                    lines += fragment.toString()
                    fragment = StringBuilder()
                    fragmentWidth = 0
                }
                fragment.appendCodePoint(codePoint)
                fragmentWidth += width
                index += Character.charCount(codePoint)
            }
            if (fragment.isNotEmpty()) current = fragment
        }

        for (word in words) {
            if (current.isEmpty()) {
                if (displayWidth(word) <= maxWidth) current.append(word) else appendLongWord(word)
                continue
            }
            val candidate = "$current $word"
            if (displayWidth(candidate) <= maxWidth) {
                current.append(' ').append(word)
            } else {
                lines += current.toString()
                current = StringBuilder()
                if (displayWidth(word) <= maxWidth) current.append(word) else appendLongWord(word)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines.ifEmpty { listOf("") }
    }

    internal fun displayWidth(value: String): Int {
        var width = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            width += codePointWidth(value, index, codePoint)
            index += Character.charCount(codePoint)
        }
        return width
    }

    private fun takeToDisplayWidth(value: String, maxWidth: Int): String {
        val result = StringBuilder(value.length)
        var width = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val codePointWidth = codePointWidth(value, index, codePoint)
            if (width + codePointWidth > maxWidth) break
            result.appendCodePoint(codePoint)
            width += codePointWidth
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun codePointWidth(value: String, index: Int, codePoint: Int): Int {
        val nextIndex = index + Character.charCount(codePoint)
        val hasEmojiSelector = nextIndex < value.length && value.codePointAt(nextIndex) == 0xFE0F
        return when {
            codePoint == 0xFE0F || codePoint == 0xFE0E || codePoint == 0x200D ||
                codePoint in 0x1F3FB..0x1F3FF -> 0
            codePoint in 0x1F000..0x1FAFF || codePoint in 0x2600..0x27BF ||
                codePoint in 0x2300..0x23FF || hasEmojiSelector -> 2
            else -> 1
        }
    }
}
