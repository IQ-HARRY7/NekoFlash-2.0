# NekoFlash 2.0 — security model

## Principles

NekoFlash is a professional device-service tool. Security policy must not silently remove legitimate expert functionality or diagnostic evidence.

- Failed destructive Fastboot transfers are fail-closed; ambiguous payloads are not blindly replayed.
- Failed ADB packets are not silently replayed.
- Large Pull resumes only from fully completed durable checkpoints and publishes the destination only after source recheck and SHA-256 equality.
- Raw diagnostic evidence remains complete.
- Sanitized/shareable output is a separate representation and never mutates the raw source.
- Release signing secrets are available only inside the protected release environment.

## ADB host identity

USB ADB and Wireless debugging use separate RSA host identities in application-private directories. New private-key publication uses same-directory staging, owner-only permissions, fsync and atomic rename. A corrupt existing identity is preserved rather than silently rotated.

The public `adbkey.pub` is derived from the private identity and is not secret.

## Wireless debugging

The development source can pair with the Android 11+ target at the operator-specified address using its Wireless debugging code. The connection port negotiates ADB's TLS upgrade before commands and file transfers. The daemon authenticates the paired host RSA public key. The saved dialog draft contains the address and ports.

## Diagnostic privacy

Raw logs can contain commands, serials, paths, USB metadata, Wireless debugging endpoints and operation context. Do not publish them blindly.

The shareable sanitizer covers structured serial/account/device fields, sensitive properties and explicit ADB/Fastboot `-s SERIAL` selectors. The historical signed alpha11 APK predates the selector-specific hardening, so alpha11 users should still manually review sanitized material before publication.

## Xiaomi session

The optional Xiaomi flow uses official Xiaomi account/unlock endpoints. WebView cookies can persist across launches and are reused when valid. Explicit Logout clears the WebView cookies. Back/cancel is not equivalent to Logout.

This persistence is intentional behavior, not an unresolved release blocker.

## Workspace/imports

Firmware, logs and imported files may live in shared `Download/NekoFlash` storage. Shared storage is not a secret vault. Import staging uses a reserved same-filesystem directory, path validation, a lock, free-space checks, fsync and atomic publication.

## Release chain

The release workflow:
1. requires exact version/tag agreement;
2. requires the release commit to be exact current `main`;
3. waits for successful exact-SHA main CI before tag creation/signing;
4. exposes signing secrets only through `nekoflash-release`;
5. verifies the expected certificate before build and the APK certificate after build;
6. publishes APK + SHA-256 + required filesystem corresponding source.

See [REPOSITORY_SECURITY.md](REPOSITORY_SECURITY.md) for GitHub administrative controls and the remaining ruleset gap.
