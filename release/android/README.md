# Local signing of the unsigned Mieru Release

The GitHub artifact is **unsigned**. Signing stays on the Windows PC. Never upload
a keystore/password, create GitHub signing secrets, or generate a replacement key.

Requirements: PowerShell 7.5+ with .NET 9+, an existing JDK (`JAVA_HOME` or `java.exe`
on PATH), and installed Android SDK build-tools containing `aapt2.exe`,
`zipalign.exe` and `lib/apksigner.jar`. The script reuses these tools; it downloads
nothing. Optional `-BuildToolsVersion` selects a specific installed build-tools
version; otherwise the newest installed stable version is used.

Extract the artifact ZIP first. Choose the **existing** keystore path and key alias
from the local signing configuration; pass them explicitly or use the existing
`HUSI_KEYSTORE_FILE` and `ALIAS_NAME` environment variables. There is no fallback to
the repository's `release.keystore` or to an assumed Android debug keystore.
The reference must be the previously verified, locally signed Release APK.

```powershell
pwsh -File ./release/android/sign-mieru.ps1 `
  -InputApk 'D:/Downloads/husi-2.3.1-mieru-3.38.1-arm64-v8a-unsigned.apk' `
  -OutputApk 'D:/Downloads/husi-2.3.1-mieru-3.38.1-arm64-v8a-signed.apk' `
  -ReferenceApk 'D:/CLI/Husi/build/androidApp-mieru-20261020/outputs/apk/foss/release/husi-2.3.1-arm64-v8a.apk' `
  -VersionName '2.3.1' -VersionCode 548 `
  -SdkRoot 'D:/CLI/Husi/build/toolchains/android-sdk' `
  -KeystorePath '<existing-local-keystore>' -KeyAlias '<existing-key-alias>'
```

Use the version/code printed by the verified CI run, not the example values for a
future version. The output directory must exist; the output APK must not exist.
Input and reference are never overwritten.

If `KEYSTORE_PASS`/`ALIAS_PASS` are already provided securely in the local process
environment, signing is non-interactive. Otherwise the script prompts using
`Read-Host -AsSecureString`. Do not put passwords in command arguments, scripts,
logs or shell history. Passwords are passed to SDK apksigner only through temporary
environment variables and cleared afterward. No credentials are printed.

The script checks every ZIP entry's CRC and length, manifest/package/version,
ARM64-only libraries and embedded Mieru; rejects existing signature material and
unexpected apksigner errors; aligns with 16 KiB native-library alignment; signs;
then repeats content/alignment checks and verifies v2/v3 signatures. It promotes
the result to the requested output path only when the signer SHA256 matches the
reference certificate. A malformed APK is not accepted as merely unsigned.

For the current reference, the expected certificate SHA256 is:
`a12fff11259b0b5f4b251909d242c3823a64cbe35ec3a1bd6e2f444db94bacc4`
(Android Debug certificate, not a production identity). Keep the same key for
updates of `fr.husi.mieru`; do not reduce versionCode. Equal versionCode is useful
for reinstalling this same version, but future updates should increment it.

On success, record the reported output path, byte/MiB size, APK SHA256, embedded
Mieru SHA256 and certificate SHA256. Alignment/signing must preserve Mieru's hash.
This procedure does not by itself claim local signing was executed; only
a successful actual run establishes signed-APK validation.
