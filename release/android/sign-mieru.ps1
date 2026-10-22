#Requires -Version 7.5
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InputApk,
    [Parameter(Mandatory)][string]$OutputApk,
    [Parameter(Mandatory)][string]$ReferenceApk,
    [Parameter(Mandatory)][string]$VersionName,
    [Parameter(Mandatory)][ValidateRange(1, 2147483647)][int]$VersionCode,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$BuildToolsVersion,
    [string]$KeystorePath = $env:HUSI_KEYSTORE_FILE,
    [string]$KeyAlias = $env:ALIAS_NAME
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if (-not $IsWindows) { throw 'This script requires Windows.' }
if (-not [IO.Compression.ZipArchiveEntry].GetProperty('Crc32')) {
    throw 'Use PowerShell with .NET 9 or newer (ZipArchiveEntry.Crc32 is required).'
}

function Resolve-ExistingFile([string]$Path) {
    $item = Get-Item -LiteralPath $Path
    if ($item.PSIsContainer) { throw "Expected a file: $Path" }
    return $item.FullName
}

function Invoke-ApkSigner([string[]]$Arguments) {
    $lines = @(& $script:java -jar $script:apksigner @Arguments 2>&1)
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Text = ($lines -join "`n") }
}

if (-not ('HusiLocalApkIntegrity' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Text;

public static class HusiLocalApkIntegrity {
    private static readonly uint[] Table = BuildTable();
    private static uint[] BuildTable() {
        var table = new uint[256];
        for (uint index = 0; index < table.Length; index++) {
            uint value = index;
            for (int bit = 0; bit < 8; bit++)
                value = (value >> 1) ^ ((value & 1) == 0 ? 0 : 0xedb88320u);
            table[index] = value;
        }
        return table;
    }
    public static string[] Check(string path) {
        var names = new HashSet<string>(StringComparer.Ordinal);
        using (var archive = ZipFile.OpenRead(path)) {
            if (archive.Entries.Count == 0) throw new InvalidDataException("Empty APK ZIP.");
            var buffer = new byte[65536];
            foreach (var entry in archive.Entries) {
                if (!names.Add(entry.FullName)) throw new InvalidDataException("Duplicate ZIP entry.");
                uint crc = 0xffffffffu;
                long length = 0;
                using (var input = entry.Open()) {
                    int count;
                    while ((count = input.Read(buffer, 0, buffer.Length)) != 0) {
                        length += count;
                        for (int index = 0; index < count; index++)
                            crc = Table[(crc ^ buffer[index]) & 255] ^ (crc >> 8);
                    }
                }
                if (length != entry.Length || (crc ^ 0xffffffffu) != entry.Crc32)
                    throw new InvalidDataException("ZIP CRC/length mismatch: " + entry.FullName);
            }
        }
        return new List<string>(names).ToArray();
    }
    public static bool HasApkSigningBlock(string path) {
        using (var stream = File.OpenRead(path))
        using (var reader = new BinaryReader(stream)) {
            long first = Math.Max(0, stream.Length - 65557);
            for (long position = stream.Length - 22; position >= first; position--) {
                stream.Position = position;
                if (reader.ReadUInt32() != 0x06054b50) continue;
                stream.Position = position + 20;
                if (position + 22 + reader.ReadUInt16() != stream.Length) continue;
                stream.Position = position + 4;
                if (reader.ReadUInt16() != 0 || reader.ReadUInt16() != 0)
                    throw new InvalidDataException("Multi-disk APK is not supported.");
                stream.Position = position + 16;
                uint directory = reader.ReadUInt32();
                if (directory == uint.MaxValue || directory > position)
                    throw new InvalidDataException("Invalid or unsupported ZIP64 APK.");
                if (directory < 24) return false;
                stream.Position = directory - 16;
                return Encoding.ASCII.GetString(reader.ReadBytes(16)) == "APK Sig Block 42";
            }
            throw new InvalidDataException("ZIP end-of-central-directory is missing.");
        }
    }
}
'@
}

function Test-ApkContents([string]$Path, [switch]$ExactVersion) {
    $entries = @([HusiLocalApkIntegrity]::Check($Path))
    if ($entries -notcontains 'AndroidManifest.xml') { throw 'AndroidManifest.xml is missing.' }
    if ($entries -notcontains 'lib/arm64-v8a/libmieru.so') { throw 'Embedded ARM64 Mieru is missing.' }
    if (@($entries | Where-Object { $_ -match '^lib/' -and $_ -notmatch '^lib/arm64-v8a/' }).Count) {
        throw 'APK contains a native ABI other than arm64-v8a.'
    }
    $badging = @(& $script:aapt2 dump badging $Path 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'aapt2 rejected the Android manifest.' }
    $package = [regex]::Match($badging, "(?m)^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'")
    if (-not $package.Success -or $package.Groups[1].Value -ne 'fr.husi.mieru') { throw 'Unexpected APK package.' }
    if ($badging -notmatch "(?m)^native-code: 'arm64-v8a'\r?$") { throw 'Unexpected APK native-code declaration.' }
    if ($ExactVersion -and ($package.Groups[2].Value -ne "$VersionCode" -or $package.Groups[3].Value -ne $VersionName)) {
        throw 'APK version does not match the expected CI version.'
    }
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $stream = $archive.GetEntry('lib/arm64-v8a/libmieru.so').Open()
        $hash = [Security.Cryptography.SHA256]::Create()
        try { $mieruHash = [Convert]::ToHexString($hash.ComputeHash($stream)).ToLowerInvariant() }
        finally { $hash.Dispose(); $stream.Dispose() }
    } finally { $archive.Dispose() }
    return [pscustomobject]@{ Entries = $entries; VersionCode = [int]$package.Groups[2].Value; MieruSHA256 = $mieruHash }
}

function Get-VerifiedSigner([string]$Path, [switch]$RequireModernSchemes) {
    $result = Invoke-ApkSigner @('verify', '--verbose', '--print-certs', $Path)
    if ($result.ExitCode -ne 0) { throw 'APK signature verification failed.' }
    $digests = @([regex]::Matches($result.Text,
        '(?m)^(?:Signer #[0-9]+ certificate|V[0-9.]+ Signer: certificate) SHA-256 digest: ([a-fA-F0-9]{64})\r?$') |
        ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() } | Select-Object -Unique)
    if ($result.Text -notmatch '(?m)^Number of signers: 1\r?$' -or $digests.Count -ne 1) {
        throw 'Expected exactly one APK signing certificate.'
    }
    if ($RequireModernSchemes -and ($result.Text -notmatch '(?m)^Verified using v2 scheme[^\r\n]*: true\r?$' -or
            $result.Text -notmatch '(?m)^Verified using v3 scheme[^\r\n]*: true\r?$')) {
        throw 'Final APK must verify with both v2 and v3 signature schemes.'
    }
    return $digests[0]
}

function Test-Unsigned([string]$Path, [string[]]$Entries) {
    if (@($Entries | Where-Object { $_ -match '(?i)^META-INF/([^/]+\.(RSA|DSA|EC|SF)|SIG-[^/]+)$' }).Count -or
            [HusiLocalApkIntegrity]::HasApkSigningBlock($Path)) {
        throw 'Input contains signature material; expected a strictly unsigned APK.'
    }
    $result = Invoke-ApkSigner @('verify', '--verbose', '--print-certs', $Path)
    if ($result.ExitCode -eq 0) { throw 'Input APK already has a valid signature.' }
    $errors = @($result.Text -split "`n" | Where-Object { $_ -match '^ERROR:' })
    if (-not $errors.Count -or @($errors | Where-Object {
                $_ -notmatch '^ERROR: (Missing META-INF/MANIFEST\.MF|No JAR signatures)\.?\r?$'
            }).Count) {
        throw 'apksigner failed for a reason other than a missing signature; refusing to sign.'
    }
}

function Set-PasswordEnvironment([string]$Source, [string]$Destination, [string]$Prompt) {
    $value = [Environment]::GetEnvironmentVariable($Source, 'Process')
    if (-not [string]::IsNullOrEmpty($value)) {
        [Environment]::SetEnvironmentVariable($Destination, $value, 'Process')
        return
    }
    $secure = Read-Host -Prompt $Prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        [Environment]::SetEnvironmentVariable($Destination, [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer), 'Process')
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        $secure.Dispose()
    }
}

$InputApk = Resolve-ExistingFile $InputApk
$ReferenceApk = Resolve-ExistingFile $ReferenceApk
$OutputApk = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputApk)
if ($InputApk -eq $ReferenceApk -or $OutputApk -eq $InputApk -or $OutputApk -eq $ReferenceApk -or
        (Test-Path -LiteralPath $OutputApk)) { throw 'Input/reference must differ; output must be a new file.' }
if ([string]::IsNullOrWhiteSpace($KeystorePath) -or [string]::IsNullOrWhiteSpace($KeyAlias)) {
    throw 'Select the existing identity with -KeystorePath/-KeyAlias or HUSI_KEYSTORE_FILE/ALIAS_NAME. No default keystore is used.'
}
$KeystorePath = Resolve-ExistingFile $KeystorePath
if ([string]::IsNullOrWhiteSpace($SdkRoot)) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if ([string]::IsNullOrWhiteSpace($SdkRoot)) { throw 'Specify -SdkRoot or ANDROID_HOME.' }
$toolsRoot = Join-Path $SdkRoot 'build-tools'
if ($BuildToolsVersion) {
    $tools = Join-Path $toolsRoot $BuildToolsVersion
} else {
    $tools = Get-ChildItem -LiteralPath $toolsRoot -Directory | Where-Object {
        $_.Name -match '^\d+\.\d+\.\d+$' -and (Test-Path (Join-Path $_.FullName 'lib/apksigner.jar'))
    } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1 -ExpandProperty FullName
    if (-not $tools) { throw 'No installed stable Android build-tools found.' }
}
$script:aapt2 = Resolve-ExistingFile (Join-Path $tools 'aapt2.exe')
$zipalign = Resolve-ExistingFile (Join-Path $tools 'zipalign.exe')
$script:apksigner = Resolve-ExistingFile (Join-Path $tools 'lib/apksigner.jar')
$script:java = if ($env:JAVA_HOME) { Resolve-ExistingFile (Join-Path $env:JAVA_HOME 'bin/java.exe') } else { (Get-Command java.exe -ErrorAction Stop).Source }

$inputHash = (Get-FileHash -LiteralPath $InputApk -Algorithm SHA256).Hash
$referenceHash = (Get-FileHash -LiteralPath $ReferenceApk -Algorithm SHA256).Hash
$inputInfo = Test-ApkContents $InputApk -ExactVersion
Test-Unsigned $InputApk $inputInfo.Entries
$referenceInfo = Test-ApkContents $ReferenceApk
if ($VersionCode -lt $referenceInfo.VersionCode) { throw 'versionCode would downgrade the installed application.' }
$referenceSigner = Get-VerifiedSigner $ReferenceApk

$outputDirectory = [IO.Path]::GetDirectoryName($OutputApk)
if (-not (Test-Path -LiteralPath $outputDirectory -PathType Container)) { throw 'Output directory must already exist.' }
$temporary = Join-Path $outputDirectory ('.husi-sign-' + [guid]::NewGuid().ToString('N'))
$storePasswordVariable = 'HUSI_LOCAL_STORE_' + [guid]::NewGuid().ToString('N')
$keyPasswordVariable = 'HUSI_LOCAL_KEY_' + [guid]::NewGuid().ToString('N')
[void][IO.Directory]::CreateDirectory($temporary)
try {
    $aligned = Join-Path $temporary 'aligned-unsigned.apk'
    $signed = Join-Path $temporary 'signed.apk'
    & $zipalign -P 16 4 $InputApk $aligned | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed.' }
    $alignedInfo = Test-ApkContents $aligned -ExactVersion
    Test-Unsigned $aligned $alignedInfo.Entries
    if ($alignedInfo.MieruSHA256 -ne $inputInfo.MieruSHA256) { throw 'Alignment changed embedded Mieru.' }
    Set-PasswordEnvironment 'KEYSTORE_PASS' $storePasswordVariable 'Existing keystore password'
    Set-PasswordEnvironment 'ALIAS_PASS' $keyPasswordVariable 'Existing private-key password'
    $sign = Invoke-ApkSigner @('sign', '--ks', $KeystorePath, '--ks-key-alias', $KeyAlias,
        '--ks-pass', "env:$storePasswordVariable", '--key-pass', "env:$keyPasswordVariable",
        '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
        '--v4-signing-enabled', 'false', '--out', $signed, $aligned)
    if ($sign.ExitCode -ne 0) { throw 'apksigner signing failed; no output promoted.' }
    $finalSigner = Get-VerifiedSigner $signed -RequireModernSchemes
    if ($finalSigner -ne $referenceSigner) { throw 'Signing identity differs from the reference APK; no output promoted.' }
    $finalInfo = Test-ApkContents $signed -ExactVersion
    if ($finalInfo.MieruSHA256 -ne $inputInfo.MieruSHA256) { throw 'Signing changed embedded Mieru.' }
    & $zipalign -c -P 16 4 $signed | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Final APK alignment verification failed.' }
    if ((Get-FileHash -LiteralPath $InputApk -Algorithm SHA256).Hash -ne $inputHash -or
            (Get-FileHash -LiteralPath $ReferenceApk -Algorithm SHA256).Hash -ne $referenceHash) {
        throw 'Input or reference changed during signing.'
    }
    [IO.File]::Move($signed, $OutputApk)
    [pscustomobject]@{
        Path = $OutputApk
        SizeBytes = (Get-Item -LiteralPath $OutputApk).Length
        SizeMiB = [math]::Round((Get-Item -LiteralPath $OutputApk).Length / 1MB, 2)
        SHA256 = (Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256).Hash.ToLowerInvariant()
        MieruSHA256 = $finalInfo.MieruSHA256
        CertificateSHA256 = $finalSigner
        Package = 'fr.husi.mieru'
        VersionName = $VersionName
        VersionCode = $VersionCode
        ABI = 'arm64-v8a'
        Signature = 'Verified v2/v3; certificate matches reference'
    }
} finally {
    [Environment]::SetEnvironmentVariable($storePasswordVariable, $null, 'Process')
    [Environment]::SetEnvironmentVariable($keyPasswordVariable, $null, 'Process')
    # This is only the newly created, explicitly scoped temporary directory.
    if ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($temporary)) -eq $outputDirectory -and
            [IO.Path]::GetFileName($temporary) -match '^\.husi-sign-[a-f0-9]{32}$') {
        Remove-Item -LiteralPath $temporary -Recurse -Force
    }
}
