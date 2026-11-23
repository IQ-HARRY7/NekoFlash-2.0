# NekoFlash 2.0 documentation

This directory intentionally separates **current operational documentation** from historical evidence.

## Current sources of truth

- [STATUS.md](STATUS.md) — signed release, development changes, and automated validation for the exact source snapshots.
- [HARDWARE_VALIDATION.md](HARDWARE_VALIDATION.md) — hardware results by build, device, and operation, with links to the physical evidence ledgers.
- [USB_CONNECTION_TROUBLESHOOTING.md](USB_CONNECTION_TROUBLESHOOTING.md) — current USB/OTG troubleshooting.
- [SECURITY.md](SECURITY.md) — application security/privacy model.
- [REPOSITORY_SECURITY.md](REPOSITORY_SECURITY.md) — CI, signing and GitHub administration state.
- [LEGAL_REVIEW.md](LEGAL_REVIEW.md) — licensing/distribution checklist.
- [PROFESSIONAL_RECOVERY_POLICY.md](PROFESSIONAL_RECOVERY_POLICY.md) — raw diagnostic and expert-operation policy.
- [../PRIVACY.md](../PRIVACY.md) and [../THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

## Historical evidence

- [AUDIT_2026-11-13.md](AUDIT_2026-11-13.md) — repository audit at the Alpha11 release, including GitHub settings observed on 13 November.
- [evidence/](evidence/) contains older device-specific release evidence.
- [design-approved/](design-approved/) contains historical approved mockups/decisions. They are not current implementation instructions.

Old REV/refactor/source-audit snapshots were removed from the active tree after the alpha11 release. They remain available through Git history when forensic comparison is actually needed.

## Public site

The public English/Russian project website is https://nekoflash.ncorror.chatgpt.site/ (RU: https://nekoflash.ncorror.chatgpt.site/ru/). GitHub Pages is not enabled; this website is published through the existing public Site, project `appgprj_6af7a14cc7e08191abefd1f6dcca57a1`.

Public static sources are mirrored in this directory. The Site source repository retains `site-data.json` and `scripts/render_site.py`, which regenerate the localized home/help pages and sitemap.xml. The compact home contains the download and a keyboard-accessible screen gallery; setup, compatibility and test details live at `/help/` and `/ru/help/`. Preserve that Site identity when updating it. Current release downloads and checksums must come from GitHub Releases.

[4PDA_POST_RU.bbcode](4PDA_POST_RU.bbcode) is the copyable forum draft, including the Xiaomi unlock tester request. It has not been submitted to 4PDA by the assistant.

Public guides describe shipped behavior. Documentation CI checks local links and known stale references. Search Console ownership uses `googlee972052e7ba1ecb2.html` at the public root. See the recorded sitemap submission and discovery counts in [STATUS.md](STATUS.md#public-website).
