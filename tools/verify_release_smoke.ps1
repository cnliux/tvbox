# T4-26: Windows release smoke gates (ASCII-only so PowerShell 5.1 reads it correctly).
# Usage: powershell -ExecutionPolicy Bypass -File tools/verify_release_smoke.ps1 [apk path]
param([string]$Apk = "")

Set-Location (Split-Path -Parent $PSScriptRoot)
if (-not $Apk) {
    $candidate = Get-ChildItem 'app\build\outputs\apk\release' -Filter '*.apk' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($candidate) { $Apk = $candidate.FullName }
}
if (-not $Apk -or -not (Test-Path $Apk)) {
    Write-Host "FAIL: no release APK; run .\gradlew.bat assembleRelease first"
    exit 2
}
Write-Host "APK = $Apk"

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
try {
    $dexEntries = $zip.Entries | Where-Object { $_.FullName -like 'classes*.dex' }
    $ms = New-Object System.IO.MemoryStream
    foreach ($entry in $dexEntries) {
        $s = $entry.Open(); $s.CopyTo($ms); $s.Dispose()
    }
    $blob = $ms.ToArray(); $ms.Dispose()
    $text = [System.Text.Encoding]::ASCII.GetString($blob)
    $pass = 0; $fail = 0; $skip = 0

    function Gate([string]$name, [bool]$ok, [string]$msg) {
        if ($ok) { Write-Host "PASS $name"; $script:pass++ }
        else { Write-Host "FAIL $name - $msg"; $script:fail++ }
    }

    $okBridge = [bool]($text -match 'com/tvbox/platformlive|platformlive/|go/')
    $okSerializer = [bool]($text.Contains('$$serializer'))
    $okCleartext = [bool](-not (Select-String -Path 'app\src\main\AndroidManifest.xml' -Pattern 'usesCleartextTraffic' -Quiet))
    $okSecret = [bool](-not ($text -match 'sk-[A-Za-z0-9]{20,}|AIza[0-9A-Za-z_-]{35}'))
    Gate "gomobile bridge classes" $okBridge "bridge symbols missing in dex"
    Gate "kotlinx serializer" $okSerializer "serializers stripped: subscriptions would lose sources"
    Gate "no blanket cleartext flag" $okCleartext "AndroidManifest still has usesCleartextTraffic"
    Gate "no plaintext secret in dex" $okSecret "possible API key in dex"

    # Focus is Compose-drawn today, so there is no drawable focus resource to check.
    Write-Host "SKIP focus drawable - focus is drawn by Compose, no drawable resource"
    $skip++
} finally {
    $zip.Dispose()
}

Write-Host "release smoke: $pass passed / $skip skipped / $fail failed -> $Apk"
if ($fail -gt 0) { exit 1 }
