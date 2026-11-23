# NekoFlash 2.0 — current status

**Updated:** 23 November 2026

## Signed release

- Release: [v6.0.0-alpha15](https://github.com/Ncorror/NekoFlash-2.0/releases/tag/v6.0.0-alpha15)
- versionCode: `238`
- applicationId: `ru.forum.adbfastboottool`
- Release commit: `ce33b0aba8d1810ef8cbc227e1cb106f2e146f5a`
- APK SHA-256: `b0e1562921f43adc69a88f73669f98f7fffd0cebe311ea6bb5706ea8e9dfbe8b`
- Signing certificate SHA-256: `3d9d8ec98ff5a0aa5e72a257f02babe19c0ff91f0af431955aa9afe8901cf5b2`
- [Release workflow](https://github.com/Ncorror/NekoFlash-2.0/actions/runs/44412579509): SUCCESS; published, non-draft, normal/latest release.
- Exact-commit [main CI](https://github.com/Ncorror/NekoFlash-2.0/actions/runs/44412580069): SUCCESS. 471 JVM tests and 58 installed-app tests (29 each on API 26/34), independent ext4/F2FS verification and Android Lint with no findings.

Public assets are the signed APK, its SHA-256 file and the required filesystem corresponding-source archive. Alpha11 through alpha14 remain available as historical signed releases.

## Development snapshot

The local terminal and its review are in `main` through PRs [#107](https://github.com/Ncorror/NekoFlash-2.0/pull/107), [#108](https://github.com/Ncorror/NekoFlash-2.0/pull/108) and [#110](https://github.com/Ncorror/NekoFlash-2.0/pull/110). [GitHub Actions passed on `a665837`](https://github.com/Ncorror/NekoFlash-2.0/actions/runs/45174261292).

PR [#111](https://github.com/Ncorror/NekoFlash-2.0/pull/111) added the ADB Wi-Fi, device-file browser and multi-image Mass Flash changes to `main` at `2c919c3`. Follow-up PR [#112](https://github.com/Ncorror/NekoFlash-2.0/pull/112) fixed stale async directory results in the device-file browser and added a Robolectric regression for API 26/34; it was merged to `main` at `677d45e`. [PR #112 GitHub Actions run 861](https://github.com/Ncorror/NekoFlash-2.0/actions/runs/45256287638) passed build, installed mkfs API 26/34 and verified tester-APK packaging. This is **automated verification**, not physical-device qualification of the new workflows. The signed public release remains Alpha15.

### UI review — 23–24 November 2026 (merged, not yet in the signed release)

PR [#113](https://github.com/Ncorror/NekoFlash-2.0/pull/113) was merged at `c9e4c04`. [GitHub Actions #875](https://github.com/Ncorror/NekoFlash-2.0/actions/runs/45278414841): **PASS** (538/538 JVM tests; Android Lint zero warnings/errors; Android API 26/34 installed-app tests; verified tester APK packaging). The Android instrumentation includes large-font portrait/landscape screen coverage. Real phone tests of these new workflows remain pending.

- Mass Flash slot selection is a full-width highlighted action fixed above the scrolling image queue in portrait. In short landscape viewports, the same highlighted selector shares the fixed bottom action row with Execute so the RecyclerView has room for its editable form. This preserves the selected slot and keeps both actions accessible.
- Wireless ADB pairing/connection and remote Device files were moved from the Sideload page to Home. The existing handlers, connection checks, and file transfers are unchanged.
- Sideload now only displays Recovery mode guidance, the selected ZIP with Clear, Choose ZIP and Start Sideload. The repeated result disclaimer and redundant Import button were removed from this page.
- Workspace Import remains directly reachable under Settings → Service, as a fallback for file providers that cannot supply a seekable ZIP; the older Fastboot-only import button is in an inactive legacy card. Actual device transfer and installation status still depend on Recovery evidence.
- Automated Android CI and emulator layout validation passed; manual tests on the donor/target phones remain pending. The signed public Alpha15 is unchanged.

### Unreleased workflows added in PR #111

- Mass Flash can select multiple Android files, map images to queue rows, and select a slot for the queue. The **Plan** action reads targets from the connected Fastboot session and shows the resulting order, repeated targets, and unresolved rows. Explicit partition names such as `boot_a` and `xbl_ab` stay literal. Quick Flash resolves the selected slot at execution and uses explicit slot suffixes as written.
- The ADB device file browser lists remote folders, pulls files or folders into the workspace, and sends Android documents to the selected remote folder. Terminal Push and Pull accept multiple sources with a common destination.
- ADB over Wi-Fi pairs with the target's Android 11+ Wireless debugging service. Pairing and connection use the ports shown on the target. When a device disconnects during an accepted reboot, the Wi-Fi connection ends and the operation records the accepted reboot.

Earlier local verification of `a291271` passed 536 JVM tests, built the Debug APK and the instrumentation APK, and completed Android Lint with zero errors and 14 warnings. The JVM suite includes a loopback fixture for ADB over TLS and cases for the resolved flash plan. The merged PR #111 includes later lint fixes. The verified PR #112 tester kit is available as an Actions artifact; real-device qualification of Wireless debugging pairing/connect/reconnect, file-browser Push/Pull, and Mass Flash's target preview/non-destructive plan is **pending**. Device and build coverage is recorded in [HARDWARE_VALIDATION.md](HARDWARE_VALIDATION.md).

## Public website

PR #103 integrated donor-phone ADB stdout redirection by @IQ-HARRY7, preserving the original contributor commit. PR #104 included the feature in alpha15. A tester APK passed `>` and `>>`, large `logcat` captures and cancellation with partial output over USB OTG from POCO F7 to POCO X3 Pro.

PRs #97–#99 (audit, Settings community links and vector brand icons) and PRs #100–#101 (Xiaomi Unlock diagnostics and completion on the final device response) are merged.

- Public website: [English](https://nekoflash.ncorror.chatgpt.site/) · [Русский](https://nekoflash.ncorror.chatgpt.site/ru/).
- **Published 19 November:** Site version 9 (source `54ecb0700fcd44b352dec7962fe0470aec421134`) updated the English and Russian download and help pages to alpha15. Static checks passed for all 18 canonical routes and the localized APK, SHA-256 and release links. Native publication returned `succeeded`.
- **Published 18 November:** Site version 8 (source `c0f32bd24b944ed5de689b3347df661fba71a501`) points EN/RU download and help pages to the verified Alpha14 APK and SHA-256. Both Xiaomi guides describe completion on the final device response without a mandatory return to Fastboot. Static checks passed for all 18 sitemap URLs, canonical URLs, local links and Google verification; native publication returned `succeeded`.
- **Published 15 November:** Site version 7 (source `56896c9291e1229543cddbb5ee8867351589230d`) points both localized download pages to the verified alpha13 APK and SHA-256.
- **Published 14 November:** the existing public Site moved to **version 6**, hosting source `307639a924a50751e9cbbbaf71493f6a73e453fb`. The approved banner is the first content below the header. The repeated large promotional headline, eyebrow and introductory paragraph below it have been replaced by a compact download block with a functional heading, APK/GitHub actions, compatibility and release/checksum information. EN/RU narrow layouts and both published home pages were checked. The existing project, public address and audience were preserved; the native publication returned `succeeded`.
- The GitHub `docs/` mirror and live Site now include **18 localized URLs**: both home/help pages, guide indexes and six workflows per language (Android flashing without a PC, ADB without a PC, Fastboot from Android, Recovery/TWRP, ADB Sideload and Xiaomi unlock without a PC).
- **Live browser validation:** all 18 sitemap pages render with matching canonical URLs, headings and the approved Open Graph image. The public sitemap lists 18 unique URLs. The Google verification URL returns the exact expected verification text; `googlee972052e7ba1ecb2.html` remains in the deployed root. Direct HTTP crawling from the executor returned 403, so status/header crawling is not claimed.
- **Gallery and branding:** the approved promotional WebP banner is published on both home pages. Six real English `.dev` screenshots are explicitly identified as development-build captures; four original Russian alpha12 screenshots are retained. English tab selection and language switching were checked live. The initial Home tab now displays the Home image and matching alt text. The existing `site-data.json` / `scripts/render_site.py` workflow preserves these changes when regenerated.
- **Responsive and SEO checks:** narrow EN/RU layouts, the banner, gallery navigation and guide links were checked in the browser. The 1200×630 public `/assets/og-image.png` loads. Local internal-link/fragment checks, sitemap uniqueness, canonical/image metadata checks and JavaScript syntax validation passed. Both READMEs retain the approved banner without the redundant icon above the heading; links now lead directly to the published new guides.
- **README polish (14 November):** both localized READMEs now provide six repository-native SVG action buttons (direct signed APK, SHA-256 file, releases, website, Telegram and guides), three-step connection instructions, a capabilities table, real localized screenshots and six workflow guides. Release-integrity and validation details are collapsible. Button download targets were verified against the alpha12 release assets; local SVG/image/relative-link checks passed. The site banner loads eagerly as the first main section, and the narrow EN/RU layout keeps the download action within the first screen.
- **Google Search Console:** URL-prefix ownership was confirmed on 14 November. After version 4 publication, `sitemap.xml` was resubmitted and Google displayed **“Файл Sitemap отправлен”**. The report still showed **12 discovered pages** from the previous processing pass; processing all 18 URLs and actual search-engine inclusion are **not yet confirmed**. Performance/indexing overview reports are still processing data.
- The earlier manual indexing request for `/ru/` was rejected with “Quota exceeded” on 14 November. No new manual indexing request was sent during this publication.
- **Next step:** check Search Console's discovered-page and indexing reports after Google processes the updated sitemap. Prepared 4PDA/Telegram promotion remains a separate action; no forum post or group message was sent during publication.
- The [4PDA BBCode draft](4PDA_POST_RU.bbcode) requests additional hardware coverage for Xiaomi Unlock. The POCO F5 run that followed the earlier `20036` response is now documented below.

## Mi Unlock result handling — 17 November 2026

Starting with Alpha14, NekoFlash completes Mi Unlock on the final OKAY response to oem unlock, after the unlock payload has been accepted. That terminal receipt is persisted before cleanup; a subsequent USB detach or service shutdown cannot replace it with cancellation. The user can let the phone erase data and boot Android without returning to Fastboot for the normal success path.

An absent final response remains unconfirmed; an explicit FAIL keeps the complete device reason and stage. Xiaomi response codes/text, server-provided waiting hours (including 20036), region guidance and session-expiration handling are retained. The optional later Fastboot check is only for unresolved/legacy attempts, is bound to device identity, and cannot overwrite another task's GUI result. The unlock request and device command are never automatically replayed.

This change first shipped in Alpha14. It passed on POCO F5 in the signed Alpha15 build on 22 November: `oem unlock` returned final `OKAY` before USB detach and the app reported success. Later diagnostic Fastboot attachment confirmed that `unlocked` had changed from `no` to `yes`.

## Hardware disposition

These field results apply to the builds and devices documented in [HARDWARE_VALIDATION.md](HARDWARE_VALIDATION.md).

| Area | Result |
| --- | --- |
| vayu `fastboot format userdata` | FIELD PASS; Android subsequently booted |
| vayu `fastboot -w` including metadata/cache | FIELD PASS; Android setup screen reached |
| Fastboot handshake/reboot/Mass Flash on tested rigs | FIELD PASS |
| Recovery/Sideload transport and accepted status handling | FIELD PASS within documented verification boundary |
| X3 Pro host → X3 Pro target 128 MiB Pull/Push and SHA-256 | FIELD PASS |
| POCO F7/onyx host long inbound ADB | Intermittent host-specific USB receive failure; checkpoint/resume robustness implemented |
| Xiaomi sign-in/session workflow | FIELD PASS for login/session acquisition; unlock itself was not executed in the cited sign-in test |
| Xiaomi Unlock on POCO F5 (`marble`) | FIELD PASS on signed Alpha15 over USB OTG, with final `OKAY` and confirmed `unlocked=yes` |
| Quick Flash Recovery on POCO F5 (`marble`) | FIELD PASS for two `flash:recovery` writes and `reboot-recovery`. With “All slots” selected, the bootloader returned `FAILGetVar Variable Not found` for `has-slot:recovery` and the app used `flash:recovery`. A/B recovery writes remain unverified. |
| Terminal rotation/input/header | FIELD PASS for the reproduced regression scenario |

## Known limitations

- Hardware qualification applies only to tested device/firmware combinations.
- POCO F7/onyx can intermittently fail long inbound USB transfers as host. Power/VBUS sensitivity is plausible but not proven because voltage/current were not instrumented.
- Raw diagnostics are intentionally complete and may contain sensitive identifiers/paths.
- Xiaomi WebView authentication cookies persist until explicit Logout; this is intentional session behavior and must be considered on shared host phones.

See [REPOSITORY_SECURITY.md](REPOSITORY_SECURITY.md) for release approval and GitHub settings evidence.

## Next development rule

Do not reopen old REV/TestBook/audit checklists as current work. New work starts from current `main`, current issues and reproducible evidence.
