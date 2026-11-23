#!/usr/bin/env python3
from pathlib import Path
from xml.etree import ElementTree as ET

root = Path(__file__).resolve().parents[1]
java = root / 'app/src/main/java/ru/forum/adbfastboottool'
vm = (java / 'DeviceViewModel.kt').read_text(encoding='utf-8')
welcome = (java / 'TerminalWelcomeMessage.kt').read_text(encoding='utf-8')
adapter = (java / 'ConsoleLogAdapter.kt').read_text(encoding='utf-8')
activity = (java / 'MainActivity.kt').read_text(encoding='utf-8')
dock = (java / 'ConsoleDockController.kt').read_text(encoding='utf-8')

assert 'TerminalWelcomeMessage.build(application).forEach(::append)' in vm
assert 'private const val WIDTH = 48' in welcome
assert 'centered("✨ ${details.title}")' in welcome
assert 'displayWidth(line) == WIDTH' in welcome
assert 'takeToDisplayWidth(value, CONTENT_WIDTH)' in welcome
assert all(emoji in welcome for emoji in ('📱', '🧩', '📁', '🕒', '📅', '✅', '⚙️', '↗️', '♻️', '↩️', '🔐', '💡', '🔄'))
assert 'value.startsWith("📱")' in adapter and 'value.startsWith("💡")' in adapter
assert 'local help' in welcome
assert 'local ls' not in welcome and 'local cat' not in welcome and 'local grep' not in welcome
assert 'TerminalWelcomeMessage.isWelcomeLine(item.text)' in adapter
assert 'textSizeSp)' in adapter
assert 'var textSizeSp = initialTextSizeSp' in adapter
assert 'fun setTextSizeSp(' not in adapter, 'Kotlin property setter already generates setTextSizeSp(Float)'
assert 'TerminalWelcomeMessage.TEXT_SIZE_SP else textSizeSp' in adapter
assert 'textView.setSingleLine(isWelcome)' in adapter
assert 'TextUtils.TruncateAt.END' in adapter
assert 'stackFromEnd = false' in activity
assert 'pinToTop = showWelcome' in activity and 'pinToBottom = !showWelcome' in activity
assert 'pinToTop -> rvConsoleOutput.doOnNextLayout' in activity
assert 'scrollOutputToBottom' not in dock
assert 'doOnNextLayout' in activity

keys = {
    'terminal_welcome_title', 'terminal_welcome_subtitle',
    'terminal_welcome_environment', 'terminal_welcome_host_label',
    'terminal_welcome_host_value', 'terminal_welcome_abi_label',
    'terminal_welcome_workspace_label', 'terminal_welcome_workspace_value',
    'terminal_welcome_time_label', 'terminal_welcome_date_label',
    'terminal_welcome_command_source', 'terminal_welcome_default_label',
    'terminal_welcome_app_managed', 'terminal_welcome_device_label',
    'terminal_welcome_device_tools', 'terminal_welcome_enable_label',
    'terminal_welcome_restore_label', 'terminal_welcome_fallback_label',
    'terminal_welcome_fallback_value', 'terminal_welcome_help',
    'terminal_welcome_help_hint', 'terminal_welcome_transport',
    'terminal_welcome_access_label', 'terminal_welcome_access_value',
}
for locale in ('values', 'values-ru'):
    strings = ET.parse(root / f'app/src/main/res/{locale}/strings.xml').getroot()
    found = [item.attrib['name'] for item in strings.findall('string')]
    missing = keys - set(found)
    assert not missing, f'{locale}: missing terminal welcome resources: {sorted(missing)}'
    assert len(found) == len(set(found)), f'{locale}: duplicate string resource name'
    ready = strings.find("string[@name='system_terminal_ready']")
    assert ready is not None and '\n' not in (ready.text or ''), f'{locale}: stale multiline terminal banner'

print('PASS terminal welcome: fixed-width rows, top-first opening, semantic colour renderer, EN/RU resources')
