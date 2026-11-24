package ru.forum.adbfastboottool

/**
 * Keeps host-shell input separate from explicit ADB/Fastboot commands.
 * An active interactive ADB shell remains the default destination until the
 * user explicitly selects the host with the optional `local` prefix.
 */
internal object TerminalCommandRoutingPolicy {
    sealed interface Route {
        data class Local(val command: String) : Route
        data class Adb(val command: String) : Route
        data class Fastboot(val command: String) : Route
        data class FastbootShell(val command: String) : Route
        data class InteractiveAdb(val raw: String, val type: String, val command: String) : Route
        data class Context(val raw: String) : Route
    }

    fun resolve(
        raw: String,
        currentContext: String,
        interactiveAdbShellActive: Boolean,
    ): Route {
        val clean = raw.trim()
        val localCommand = extractLocalCommand(clean)
        val implicitLocalCommand = extractImplicitLocalCommand(clean)
        val explicitLocal = clean == "local" ||
            (clean.startsWith("local") && clean.getOrNull(5)?.isWhitespace() == true)

        if (explicitLocal && localCommand != null) return Route.Local(localCommand)

        val explicitAdb = clean.startsWith("adb ", ignoreCase = true)
        val explicitFastboot = clean.startsWith("fastboot ", ignoreCase = true)
        val explicitType = when {
            explicitAdb -> "adb"
            explicitFastboot -> "fastboot"
            else -> currentContext
        }
        val explicitCommand = if (explicitAdb || explicitFastboot) {
            clean.substringAfter(' ').trim()
        } else {
            clean
        }

        // An explicit Fastboot prefix must never be sent as text to an already
        // open interactive ADB shell. Mixed shell expressions use the existing
        // confirmed Fastboot bridge; a single Fastboot command keeps native routing.
        if (explicitFastboot) {
            return if (hasUnquotedShellControlOperator(clean)) {
                Route.FastbootShell("sh -c ${quoteForShellArgument(clean)}")
            } else {
                Route.Fastboot(explicitCommand)
            }
        }

        if (interactiveAdbShellActive) {
            return Route.InteractiveAdb(clean, explicitType, explicitCommand)
        }

        if (explicitAdb) return Route.Adb(explicitCommand)

        if (implicitLocalCommand != null) return Route.Local(implicitLocalCommand)

        return when (currentContext) {
            "adb" -> Route.Adb(clean)
            "fastboot" -> Route.Fastboot(clean)
            else -> Route.Context(clean)
        }
    }

    internal fun hasUnquotedShellControlOperator(command: String): Boolean {
        var quote: Char? = null
        var escaping = false
        var index = 0
        while (index < command.length) {
            val character = command[index]
            when {
                escaping -> escaping = false
                character == '\\' -> escaping = true
                quote != null -> if (character == quote) quote = null
                character == '\'' || character == '"' -> quote = character
                command.startsWith("&&", index) || command.startsWith("||", index) ||
                    command.startsWith("\$(", index) -> return true
                character == ';' || character == '|' || character == '>' || character == '<' || character == '`' -> return true
            }
            index++
        }
        return false
    }

    private fun quoteForShellArgument(value: String): String =
        "'${value.replace("'", "'\\''")}'"
}
