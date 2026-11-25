# T8-47：Windows 版环境体检。输出可复制的 PowerShell 设置命令与「还差几步」。
# 用法：powershell -ExecutionPolicy Bypass -File tools/doctor.ps1
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$OK = "✅"; $WARN = "⚠️ "; $BAD = "❌"
$missing = 0

Write-Host "── 聚合视频 本地环境体检 ─────────────────────────────"

function Test-Cmd($name, $label, $hint) {
    if (Get-Command $name -ErrorAction SilentlyContinue) {
        Write-Host "$OK $label  $((& $name --version 2>&1 | Select-Object -First 1) -join ' ')"
    } else {
        Write-Host "$BAD $label  未找到 → $hint"
        $script:missing++
    }
}

Test-Cmd "java" "JDK    " "安装 JDK 17：设置 `$env:JAVA_HOME='G:\jdk17'；`$env:PATH=`$env:JAVA_HOME\bin;`$env:PATH"

if (Get-Command javac -ErrorAction SilentlyContinue) { Write-Host "$OK javac  on PATH" }
else { Write-Host "$BAD javac  不在 PATH → `$env:PATH=`$env:JAVA_HOME\bin;`$env:PATH"; $missing++ }

$sdk = $env:ANDROID_HOME
if (-not $sdk -and (Test-Path local.properties)) {
    $line = Get-Content local.properties | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
    if ($line) { $sdk = $line.Substring($line.IndexOf('=') + 1) }
}
if ($sdk -and (Test-Path $sdk)) { Write-Host "$OK ANDROID_HOME  $sdk" }
else { Write-Host "$BAD ANDROID_HOME  未配置 → `$env:ANDROID_HOME='C:\Android\Sdk'（或 local.properties 的 sdk.dir）"; $missing++ }

$ndk = $env:ANDROID_NDK_HOME
if (-not $ndk -and $sdk -and (Test-Path (Join-Path $sdk 'ndk'))) {
    $ndk = (Get-ChildItem (Join-Path $sdk 'ndk') -Directory | Sort-Object Name | Select-Object -Last 1).FullName
}
if ($ndk -and (Test-Path $ndk)) { Write-Host "$OK NDK    $ndk" }
else { Write-Host "$WARN NDK    未找到 → sdkmanager 'ndk;26.3.11579264'（仅 bind 时需要）" }

Test-Cmd "go" "Go     " "安装 Go（版本见 platform_live_go\go.mod）"

try {
    $gopath = & go env GOPATH 2>$null
    $gomobileBin = Join-Path ($gopath.Trim()) "bin\gomobile.exe"
    if (Get-Command gomobile -ErrorAction SilentlyContinue -or (Test-Path $gomobileBin)) {
        Write-Host "$OK gomobile  installed"
    } else {
        Write-Host "$WARN gomobile  not installed → go install golang.org/x/mobile/cmd/gomobile@<tools\bind-platform-live.ps1 中的版本>"
        Write-Host "       `$env:PATH='$gomobileBin;' + `$env:PATH"
    }
} catch { Write-Host "$WARN gomobile  无法检测（Go 未就绪）" }

if (Test-Path 'app\libs\platformlive.aar') {
    $len = (Get-Item 'app\libs\platformlive.aar').Length
    Write-Host "$OK AAR     $len bytes  app\libs\platformlive.aar"
} else {
    Write-Host "$WARN AAR     缺失 → powershell -File tools\bind-platform-live.ps1（≈3min）"
}
if (Test-Path 'app\libs\platformlive.fingerprint') { Write-Host "$OK AAR 指纹  已存在" }
else { Write-Host "$WARN AAR 指纹  缺失（首次 bind 会生成）" }

if (Test-Path 'gradlew.bat') { Write-Host "$OK Gradle wrapper  ready" }
else { Write-Host "$BAD Gradle wrapper  缺失 → 从仓库恢复"; $missing++ }

Write-Host "──────────────────────────────────────────"
if ($missing -eq 0) {
    Write-Host "结论：环境齐全，可离线 assembleDebug。下一步：just test; just smoke"
    exit 0
} else {
    Write-Host "结论：还差 $missing 步就能编，预计 $($missing * 2) 分钟。补齐后重跑本脚本。"
    exit 1
}
