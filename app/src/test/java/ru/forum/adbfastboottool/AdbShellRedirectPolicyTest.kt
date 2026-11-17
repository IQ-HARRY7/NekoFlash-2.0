package ru.forum.adbfastboottool

import java.io.File
import ru.forum.adbfastboottool.AdbShellRedirectPolicy.End
import org.junit.Assert.*
import org.junit.Test

class AdbShellRedirectPolicyTest {
    private fun redirect(line: String): AdbShellRedirectPolicy.Parsed.Redirect =
        AdbShellRedirectPolicy.parse(line) as AdbShellRedirectPolicy.Parsed.Redirect

    private fun invalid(line: String): String =
        (AdbShellRedirectPolicy.parse(line) as AdbShellRedirectPolicy.Parsed.Invalid).reason

    @Test fun plainRedirectSplitsCommandAndTarget() {
        val parsed = redirect("logcat > logcat.txt")
        assertEquals("logcat", parsed.command)
        assertEquals("logcat.txt", parsed.target)
        assertFalse(parsed.append)
    }

    @Test fun spacingAroundOperatorIsOptional() {
        assertEquals("logcat", redirect("logcat>out.txt").command)
        assertEquals("out.txt", redirect("logcat>out.txt").target)
        assertEquals("out.txt", redirect("logcat   >    out.txt").target)
    }

    @Test fun appendOperatorIsRecognised() {
        val parsed = redirect("logcat -d >> /sdcard/Download/a.log")
        assertEquals("logcat -d", parsed.command)
        assertEquals("/sdcard/Download/a.log", parsed.target)
        assertTrue(parsed.append)
    }

    @Test fun argumentsAfterTheTargetStayInTheCommand() {
        assertEquals("logcat -d -b all", redirect("logcat > a.txt -d -b all").command)
    }

    @Test fun quotedAndEscapedTargetsAreUnwrapped() {
        assertEquals("my log.txt", redirect("logcat > \"my log.txt\"").target)
        assertEquals("my log.txt", redirect("logcat > 'my log.txt'").target)
        assertEquals("my log.txt", redirect("logcat > my\\ log.txt").target)
    }

    @Test fun pipelineKeepsRunningOnTheDeviceAndOnlyTheEndIsSaved() {
        val parsed = redirect("logcat -d | grep ActivityManager > am.txt")
        assertEquals("logcat -d | grep ActivityManager", parsed.command)
        assertEquals("am.txt", parsed.target)
    }

    @Test fun explicitStdoutDescriptorIsConsumed() {
        val parsed = redirect("dumpsys battery 1> battery.txt")
        assertEquals("dumpsys battery", parsed.command)
        assertEquals("battery.txt", parsed.target)
    }

    @Test fun quotedOrEscapedOperatorStaysOnTheDevice() {
        assertEquals(AdbShellRedirectPolicy.Parsed.None, AdbShellRedirectPolicy.parse("sh -c 'echo a > /sdcard/a'"))
        assertEquals(AdbShellRedirectPolicy.Parsed.None, AdbShellRedirectPolicy.parse("sh -c \"echo a > /sdcard/a\""))
        assertEquals(AdbShellRedirectPolicy.Parsed.None, AdbShellRedirectPolicy.parse("echo a \\> b"))
    }

    @Test fun deviceFileDescriptorRedirectsAreNotTouched() {
        listOf(
            "logcat 2>/dev/null",
            "logcat 2> err.txt",
            "logcat 2>>err.txt",
            "logcat 2>&1",
            "logcat >&2",
            "logcat &> all.txt"
        ).forEach { assertEquals(it, AdbShellRedirectPolicy.Parsed.None, AdbShellRedirectPolicy.parse(it)) }
    }

    @Test fun hostRedirectCanSitNextToADeviceStderrRedirect() {
        val parsed = redirect("logcat 2>/dev/null > out.txt")
        assertEquals("logcat 2>/dev/null", parsed.command)
        assertEquals("out.txt", parsed.target)
    }

    @Test fun digitsInsideAWordAreNotADescriptor() {
        val parsed = redirect("getprop ro.build.v2> props.txt")
        assertEquals("getprop ro.build.v2", parsed.command)
    }

    @Test fun commandWithoutOperatorIsUntouched() {
        assertEquals(AdbShellRedirectPolicy.Parsed.None, AdbShellRedirectPolicy.parse("logcat -d"))
        assertEquals(AdbShellRedirectPolicy.Parsed.None, AdbShellRedirectPolicy.parse(""))
    }

    @Test fun malformedRedirectsAreRejectedWithAReason() {
        assertTrue(invalid("logcat >").contains("File name is missing"))
        assertTrue(invalid("logcat >> ").contains(">>"))
        assertTrue(invalid("logcat > \"\"").contains("File name is missing"))
        assertTrue(invalid("logcat > a.txt > b.txt").contains("Only one"))
        assertTrue(invalid("logcat > | grep x").contains("Unsupported"))
        assertTrue(invalid("> out.txt").contains("Nothing to run"))
    }

    @Test fun defaultFileNameComesFromTheCommand() {
        assertEquals("logcat.txt", AdbShellRedirectPolicy.defaultFileName("logcat -d"))
        assertEquals("getprop.txt", AdbShellRedirectPolicy.defaultFileName("  getprop ro.product.model"))
        assertEquals("dumpsys.txt", AdbShellRedirectPolicy.defaultFileName("/system/bin/dumpsys battery"))
        assertEquals("adb-output.txt", AdbShellRedirectPolicy.defaultFileName("$(%)"))
    }

    @Test fun endClassificationPrefersTheRealResult() {
        val p = AdbShellRedirectPolicy
        assertEquals(End.COMPLETED, p.endOf(success = true, cancelled = false, exitCode = 0))
        assertEquals(End.COMPLETED, p.endOf(success = true, cancelled = true, exitCode = 0))
        assertEquals(End.STOPPED_BY_USER, p.endOf(success = false, cancelled = true, exitCode = null))
        assertEquals(End.NONZERO_EXIT, p.endOf(success = false, cancelled = false, exitCode = 1))
        assertEquals(End.INTERRUPTED, p.endOf(success = false, cancelled = false, exitCode = null))
    }

    @Test fun failedCommandsOnlyLeaveAFileWhenTheyPrintedSomething() {
        val p = AdbShellRedirectPolicy
        assertTrue(p.shouldPublish(End.COMPLETED, 0L))
        assertTrue(p.shouldPublish(End.NONZERO_EXIT, 1L))
        assertTrue(p.shouldPublish(End.STOPPED_BY_USER, 10L))
        assertFalse(p.shouldPublish(End.NONZERO_EXIT, 0L))
        assertFalse(p.shouldPublish(End.STOPPED_BY_USER, 0L))
        assertFalse(p.shouldPublish(End.INTERRUPTED, 0L))
    }

    @Test fun stoppingAStreamIsNotAFailureButARealErrorIs() {
        val p = AdbShellRedirectPolicy
        assertTrue(p.succeeded(End.COMPLETED))
        assertTrue(p.succeeded(End.STOPPED_BY_USER))
        assertFalse(p.succeeded(End.NONZERO_EXIT))
        assertFalse(p.succeeded(End.INTERRUPTED))
    }

    @Test fun statusLinesStartWithTheMarkerThatSetsTheirLogLevel() {
        val p = AdbShellRedirectPolicy
        val target = File("/sdcard/Download/NekoFlash/logcat.txt")
        fun level(line: String) = DiagnosticLogPolicy.classify(line).level
        fun saved(end: End, bytes: Long, append: Boolean = false) =
            p.savedLine(end, target, append, bytes, bytes, 42_000L, 1)

        assertEquals(DiagnosticLogPolicy.Level.SUCCESS, level(saved(End.COMPLETED, 3_586_949L)))
        assertEquals(DiagnosticLogPolicy.Level.SUCCESS, level(saved(End.COMPLETED, 10L, append = true)))
        assertEquals(DiagnosticLogPolicy.Level.WARNING, level(saved(End.COMPLETED, 0L)))
        assertEquals(DiagnosticLogPolicy.Level.WARNING, level(saved(End.STOPPED_BY_USER, 10L)))
        assertEquals(DiagnosticLogPolicy.Level.WARNING, level(saved(End.INTERRUPTED, 10L)))
        assertEquals(DiagnosticLogPolicy.Level.ERROR, level(saved(End.NONZERO_EXIT, 10L)))
        assertEquals(DiagnosticLogPolicy.Level.WARNING, level(p.skippedLine(End.STOPPED_BY_USER, target, null)))
        assertEquals(DiagnosticLogPolicy.Level.ERROR, level(p.skippedLine(End.NONZERO_EXIT, target, 1)))
        assertEquals(DiagnosticLogPolicy.Level.ERROR, level(p.skippedLine(End.INTERRUPTED, target, null)))
        assertEquals(DiagnosticLogPolicy.Level.ERROR, level(p.saveFailedLine(target, "disk full")))
        assertEquals(DiagnosticLogPolicy.Level.INFO, level(p.startLine(target, false)))
        assertEquals(DiagnosticLogPolicy.Level.INFO, level(p.rawKeptLine(target)))
    }

    @Test fun savedLineShowsPathSizeAndTime() {
        val line = AdbShellRedirectPolicy.savedLine(
            End.COMPLETED,
            File("/sdcard/Download/NekoFlash/logcat.txt"),
            appended = false, bytes = 3_586_949L, totalBytes = 3_586_949L, elapsedMs = 42_000L, exitCode = 0
        )
        assertEquals("✅ Output saved: /sdcard/Download/NekoFlash/logcat.txt · 3.42 MB · 42s", line)
    }

    @Test fun emptyOutputIsReportedPlainly() {
        val target = File("/sdcard/Download/NekoFlash/quiet.txt")
        assertEquals(
            "⚠️ The command finished but printed nothing, so /sdcard/Download/NekoFlash/quiet.txt is empty (0 B).",
            AdbShellRedirectPolicy.savedLine(AdbShellRedirectPolicy.End.COMPLETED, target, false, 0L, 0L, 300L, 0)
        )
        assertEquals(
            "⚠️ The command finished but printed nothing, so nothing was added to /sdcard/Download/NekoFlash/quiet.txt.",
            AdbShellRedirectPolicy.savedLine(AdbShellRedirectPolicy.End.COMPLETED, target, true, 0L, 10L, 300L, 0)
        )
    }

    @Test fun appendedLineShowsWhatWasAddedAndTheNewTotal() {
        val line = AdbShellRedirectPolicy.savedLine(
            End.COMPLETED,
            File("/sdcard/Download/NekoFlash/a.log"),
            appended = true, bytes = 2_048L, totalBytes = 10_240L, elapsedMs = 900L, exitCode = 0
        )
        assertEquals("✅ Output appended: /sdcard/Download/NekoFlash/a.log · +2.00 KB (file is now 10.00 KB) · 900 ms", line)
    }

    @Test fun progressLineShowsRateOnlyOnceItIsMeaningful() {
        val early = AdbShellRedirectPolicy.progressLine("logcat.txt", 500L, 400L)
        assertEquals("📥 Catching output → logcat.txt · 500 B so far · 400 ms", early)
        val later = AdbShellRedirectPolicy.progressLine("logcat.txt", 1_048_576L, 20_000L)
        assertEquals("📥 Catching output → logcat.txt · 1.00 MB so far · 20s · 51.20 KB/s", later)
        assertEquals("Saving output · 1.00 MB · 20s", AdbShellRedirectPolicy.progressDetail(1_048_576L, 20_000L))
    }

    @Test fun notEnoughSpaceIsExplainedInHumanUnits() {
        assertEquals(
            "not enough free space (needs 120.00 MB, only 40.00 MB left)",
            AdbShellRedirectPolicy.notEnoughSpaceReason(120L * 1024 * 1024, 40L * 1024 * 1024)
        )
    }

    @Test fun progressMeterThrottlesAndHintsOnce() {
        val meter = AdbShellRedirectPolicy.ProgressMeter(startedMs = 0L, logIntervalMs = 10_000L, uiIntervalMs = 1_000L)
        val first = meter.tick(500L)
        assertFalse(first.log); assertFalse(first.ui); assertFalse(first.hint)
        val ui = meter.tick(1_000L)
        assertTrue(ui.ui); assertFalse(ui.log)
        assertFalse(meter.tick(1_500L).ui)
        val log = meter.tick(10_000L)
        assertTrue(log.log); assertTrue(log.hint)
        val next = meter.tick(20_000L)
        assertTrue(next.log); assertFalse(next.hint)
    }
}
