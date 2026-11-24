package ru.forum.adbfastboottool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCommandRoutingPolicyTest {
    @Test fun bareHostCommandsWinOverTheSelectedAdbOrFastbootTab() {
        assertEquals(
            TerminalCommandRoutingPolicy.Route.Local("ls"),
            TerminalCommandRoutingPolicy.resolve("ls", "adb", interactiveAdbShellActive = false),
        )
        assertEquals(
            TerminalCommandRoutingPolicy.Route.Local("cd \"ROM files\""),
            TerminalCommandRoutingPolicy.resolve("cd \"ROM files\"", "fastboot", interactiveAdbShellActive = false),
        )
        assertEquals(
            TerminalCommandRoutingPolicy.Route.Local("pwd"),
            TerminalCommandRoutingPolicy.resolve("pwd", "adb", interactiveAdbShellActive = false),
        )
        assertEquals(
            TerminalCommandRoutingPolicy.Route.Local("help"),
            TerminalCommandRoutingPolicy.resolve("help", "fastboot", interactiveAdbShellActive = false),
        )
        assertEquals(
            TerminalCommandRoutingPolicy.Route.Local("ls && pwd"),
            TerminalCommandRoutingPolicy.resolve("ls && pwd", "adb", interactiveAdbShellActive = false),
        )
    }

    @Test fun explicitAdbAndFastbootPrefixesRemainAuthoritative() {
        assertEquals(
            TerminalCommandRoutingPolicy.Route.Adb("shell ls"),
            TerminalCommandRoutingPolicy.resolve("adb shell ls", "fastboot", interactiveAdbShellActive = false),
        )
        assertEquals(
            TerminalCommandRoutingPolicy.Route.Fastboot("flash boot boot.img"),
            TerminalCommandRoutingPolicy.resolve("fastboot flash boot boot.img", "adb", interactiveAdbShellActive = false),
        )
    }

    @Test fun explicitLocalPrefixCanStillForceHostCommands() {
        assertEquals(
            TerminalCommandRoutingPolicy.Route.Local("cd /storage/emulated/0"),
            TerminalCommandRoutingPolicy.resolve("local cd /storage/emulated/0", "adb", interactiveAdbShellActive = true),
        )
    }

    @Test fun ambiguousDeviceDiagnosticsKeepTheSelectedAdbContextUnlessForcedLocal() {
        assertEquals(
            TerminalCommandRoutingPolicy.Route.Adb("getprop ro.build.version.release"),
            TerminalCommandRoutingPolicy.resolve("getprop ro.build.version.release", "adb", false),
        )
        assertEquals(
            TerminalCommandRoutingPolicy.Route.Local("getprop ro.build.version.release"),
            TerminalCommandRoutingPolicy.resolve("local getprop ro.build.version.release", "adb", false),
        )
        assertEquals(
            TerminalCommandRoutingPolicy.Route.Adb("getprop ro.build.version.release"),
            TerminalCommandRoutingPolicy.resolve("getprop ro.build.version.release", "adb", true),
        )
    }

    @Test fun anActiveInteractiveAdbShellKeepsBareInputOnTheDevice() {
        assertEquals(
            TerminalCommandRoutingPolicy.Route.InteractiveAdb("ls", "adb", "ls"),
            TerminalCommandRoutingPolicy.resolve("ls", "adb", interactiveAdbShellActive = true),
        )
    }

    @Test fun explicitFastbootCommandsDoNotLeakIntoAnInteractiveAdbShell() {
        assertEquals(
            TerminalCommandRoutingPolicy.Route.Fastboot("reboot"),
            TerminalCommandRoutingPolicy.resolve("fastboot reboot", "adb", interactiveAdbShellActive = true),
        )
    }

    @Test fun mixedFastbootCommandsUseTheExistingConfirmedScriptBridge() {
        val route = TerminalCommandRoutingPolicy.resolve(
            "fastboot flash boot boot.img && echo \"Done, now boot into ROM\"",
            "fastboot",
            interactiveAdbShellActive = false,
        )
        assertTrue(route is TerminalCommandRoutingPolicy.Route.FastbootShell)
        assertEquals(
            "sh -c 'fastboot flash boot boot.img && echo \"Done, now boot into ROM\"'",
            (route as TerminalCommandRoutingPolicy.Route.FastbootShell).command,
        )
    }

    @Test fun operatorsInsideQuotesDoNotConvertOrdinaryFastbootArgumentsIntoScripts() {
        assertEquals(
            TerminalCommandRoutingPolicy.Route.Fastboot("oem \"label && keep\""),
            TerminalCommandRoutingPolicy.resolve(
                "fastboot oem \"label && keep\"",
                "fastboot",
                interactiveAdbShellActive = false,
            ),
        )
    }

    @Test fun adbRemoteShellChainsRemainDeviceSide() {
        assertEquals(
            TerminalCommandRoutingPolicy.Route.Adb("shell ls && echo done"),
            TerminalCommandRoutingPolicy.resolve("adb shell ls && echo done", "fastboot", false),
        )
    }
}
