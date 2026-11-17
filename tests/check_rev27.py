#!/usr/bin/env python3
"""REV27 wiring guards: `adb <cmd> > file` is saved where `adb pull` saves. JVM tests cover behavior."""
from pathlib import Path
import subprocess
root = Path(__file__).resolve().parents[1]
subprocess.run(['python3', str(root/'tests/check_rev26.py')], cwd=root, check=True)
java = root/'app/src/main/java/ru/forum/adbfastboottool'
main = (java/'MainActivity.kt').read_text()
vm = (java/'DeviceViewModel.kt').read_text()
adb = (java/'AdbProtocol.kt').read_text()
policy = (java/'AdbShellRedirectPolicy.kt').read_text()
publisher = (java/'AdbShellRedirectPublisher.kt').read_text()
human = (java/'HumanReadable.kt').read_text()

# Terminal: every shell-producing branch goes through one redirect-aware factory.
factory = main.split('private fun adbShellAction')[1].split('private fun parseAdbInstallMultiple')[0]
assert 'AdbShellRedirectPolicy.parse(' in factory
assert 'resolveTerminalOutputFile(' in factory, 'redirect target must resolve exactly like an adb pull destination'
parse_adb = main.split('private fun parseAdbCommand')[1].split('private fun adbShellAction')[0]
assert 'TerminalAction.AdbShell(' not in parse_adb, 'shell branches must use adbShellAction'
assert parse_adb.count('adbShellAction(') == 4
assert 'resolveTerminalOutputFile(tokens.getOrNull(2).orEmpty(), defaultName)' in parse_adb, 'adb pull path rules must stay untouched'
assert 'viewModel.runAdbShell(action.command, action.redirect)' in main
assert 'val redirect: AdbProtocol.ShellRedirect? = null' in main
assert 'redirect = redirect' in vm and 'redirect.target.name' in vm

# Protocol: stdout goes to the file, stderr keeps showing, evidence stays, publish happens once.
assert 'if (logOutput && redirect == null) ShellOutputTextRenderer(false, onLog)' in adb
assert adb.count('if (logOutput && redirect == null) ShellOutputTextRenderer(false, onLog)') == 2
assert 'if (logOutput) ShellOutputTextRenderer(true, onLog)' in adb, 'stderr must never be redirected'
assert adb.count('publishShellRedirect(') == 2  # call + definition
assert adb.count('AdbShellRedirectPublisher.publish(') == 1
assert adb.count('redirectProgress?.accept(data.size)') == 2
assert 'return redirect == null && startInteractiveShell()' in adb
assert 'catch (error: LocalFileCopy.NotEnoughSpace)' in adb and 'rawKeptLine(raw)' in adb

# Publisher: atomic replace like pull, bounded copy, source evidence is read-only.
assert 'FilePublication.replace(staged, target)' in publisher
assert 'LocalFileCopy.copy(' in publisher and '.copyTo(' not in publisher
assert 'source.delete' not in publisher and 'renameTo' not in publisher and 'Files.move' not in publisher
assert 'setLength(originalLength)' in publisher

# Pure policy: no Android types, no I/O.
assert 'import android' not in policy and 'FileOutputStream' not in policy and 'readText' not in policy
assert 'import android' not in human
