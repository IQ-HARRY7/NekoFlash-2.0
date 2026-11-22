package ru.forum.adbfastboottool

import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import java.util.Date

internal object TerminalWelcomeMessage {
    private const val WIDTH = 48
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
        val timeLabel: String,
        val time: String,
        val dateLabel: String,
        val date: String,
        val commandSource: String,
        val defaultLabel: String,
        val appManaged: String,
        val deviceLabel: String,
        val deviceTools: String,
        val enableLabel: String,
        val restoreLabel: String,
        val fallbackLabel: String,
        val fallback: String,
        val help: String,
        val helpHint: String,
        val transport: String,
        val accessLabel: String,
        val access: String,
    )

    fun build(context: Context, now: Date = Date()): List<String> {
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
                timeLabel = context.getString(R.string.terminal_welcome_time_label),
                time = DateFormat.getTimeFormat(context).format(now),
                dateLabel = context.getString(R.string.terminal_welcome_date_label),
                date = DateFormat.getDateFormat(context).format(now),
                commandSource = context.getString(R.string.terminal_welcome_command_source),
                defaultLabel = context.getString(R.string.terminal_welcome_default_label),
                appManaged = context.getString(R.string.terminal_welcome_app_managed),
                deviceLabel = context.getString(R.string.terminal_welcome_device_label),
                deviceTools = context.getString(R.string.terminal_welcome_device_tools),
                enableLabel = context.getString(R.string.terminal_welcome_enable_label),
                restoreLabel = context.getString(R.string.terminal_welcome_restore_label),
                fallbackLabel = context.getString(R.string.terminal_welcome_fallback_label),
                fallback = context.getString(R.string.terminal_welcome_fallback_value),
                help = context.getString(R.string.terminal_welcome_help),
                helpHint = context.getString(R.string.terminal_welcome_help_hint),
                transport = context.getString(R.string.terminal_welcome_transport),
                accessLabel = context.getString(R.string.terminal_welcome_access_label),
                access = context.getString(R.string.terminal_welcome_access_value),
            ),
        )
    }

    internal fun layout(details: Details): List<String> {
        fun row(value: String): String = "║ ${value.take(CONTENT_WIDTH).padEnd(CONTENT_WIDTH)} ║"
        fun centered(value: String): String {
            val text = value.take(CONTENT_WIDTH)
            val left = (CONTENT_WIDTH - text.length) / 2
            return row(" ".repeat(left) + text)
        }
        fun section(title: String): String {
            val prefix = "╟─ $title "
            return prefix + "─".repeat((WIDTH - prefix.length - 1).coerceAtLeast(0)) + "╢"
        }
        val top = "╔${"═".repeat(WIDTH - 2)}╗"
        val bottom = "╚${"═".repeat(WIDTH - 2)}╝"
        val sectionBottom = "╰${"─".repeat(WIDTH - 2)}╯"
        val divider = "=".repeat(WIDTH)
        return listOf(
            top,
            centered("✦ ${details.title}"),
            centered(details.subtitle),
            bottom,
            divider,
            section(details.environment),
            row("📱 ${details.hostLabel} = ${details.host}"),
            row("🧩 ${details.abiLabel} = ${details.abi}"),
            row("📁 ${details.workspaceLabel} = ${details.workspace}"),
            row("🕒 ${details.timeLabel} = ${details.time}"),
            row("📅 ${details.dateLabel} = ${details.date}"),
            sectionBottom,
            divider,
            section(details.commandSource),
            row("✓ ${details.defaultLabel} = ${details.appManaged}"),
            row("⚙ ${details.deviceLabel} = ${details.deviceTools}"),
            row("↗ ${details.enableLabel}"),
            row("  export NEKO_USE_DEVICE_BINARIES=1"),
            row("↺ ${details.restoreLabel}"),
            row("  export NEKO_USE_DEVICE_BINARIES=0"),
            row("↳ ${details.fallbackLabel} = ${details.fallback}"),
            row("🔐 ${details.accessLabel} = ${details.access}"),
            sectionBottom,
            divider,
            section(details.help),
            row("💡 ${details.helpHint}"),
            centered("local help"),
            row("⇄ ${details.transport}"),
            sectionBottom,
            divider,
        )
    }

    internal fun isWelcomeLine(line: String): Boolean =
        line.length == WIDTH && (
            line == "=".repeat(WIDTH) ||
                (line.startsWith("╔") && line.endsWith("╗")) ||
                (line.startsWith("╚") && line.endsWith("╝")) ||
                (line.startsWith("╟") && line.endsWith("╢")) ||
                (line.startsWith("╰") && line.endsWith("╯")) ||
                (line.startsWith("║ ") && line.endsWith(" ║"))
            )
}
