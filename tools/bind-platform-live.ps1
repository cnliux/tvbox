# 将 platform_live_go 编译为 Android AAR（gomobile bind）。
#
# 前置条件：
# - 本机安装 Go（go.exe 可在 PATH，或用 -GoBin 指定完整路径）；
# - Android SDK 存在 NDK（从 local.properties 的 sdk.dir 或 ANDROID_SDK_ROOT 探测）。
# 产物：app/libs/platformlive.aar（APK 通过 implementation(files(...)) 依赖）。

param(
    [string]$GoBin = "",
    [string]$SdkDir = "",
    [string]$OutputAar = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$moduleDir = Join-Path $repoRoot "platform_live_go"

function Find-Go {
    param([string]$Preferred)
    if ($Preferred -and (Test-Path $Preferred)) { return $Preferred }
    foreach ($candidate in @(
        (Get-Command "go.exe" -ErrorAction SilentlyContinue).Source,
        "C:\Program Files\Go\bin\go.exe",
        "C:\Go\bin\go.exe"
    )) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    throw "未找到 go.exe，请先安装 Go 或通过 -GoBin 指定路径。"
}

function Find-AndroidSdk {
    param([string]$Preferred)
    if ($Preferred -and (Test-Path $Preferred)) { return $Preferred }
    $localProps = Join-Path $repoRoot "local.properties"
    if (Test-Path $localProps) {
        foreach ($line in Get-Content $localProps) {
            if ($line -match '^sdk\.dir=(.+)$') {
                $sdk = $matches[1].Replace('\\', '\').Replace('=\:', ':\').Trim()
                # gradle properties 用 \: 转义冒号（G\:\\GAndroidSDK）
                $sdk = $sdk.Replace('\:', ':')
                if (Test-Path $sdk) { return $sdk }
            }
        }
    }
    foreach ($envName in @("ANDROID_SDK_ROOT", "ANDROID_HOME")) {
        $value = [Environment]::GetEnvironmentVariable($envName)
        if ($value -and (Test-Path $value)) { return $value }
    }
    throw "未找到 Android SDK，请通过 -SdkDir 指定或设置 sdk.dir。"
}

function Find-Ndk {
    param([string]$Sdk)
    $envNdks = $env:ANDROID_NDK_HOME
    if ($envNdks -and (Test-Path $envNdks)) { return $envNdks }
    $ndkRoot = Join-Path $Sdk "ndk"
    if (-not (Test-Path $ndkRoot)) { throw "Android SDK 下没有 ndk 目录，请先用 sdkmanager 安装 NDK。" }
    $candidates = Get-ChildItem $ndkRoot -Directory | Where-Object { $_.Name -ne "latest" } | Sort-Object { [version]($_.Name) } -Descending
    if (-not $candidates) { throw "Android SDK 下没有可用的 NDK 版本。" }
    return $candidates[0].FullName
}

if (-not $OutputAar) {
    $OutputAar = Join-Path $repoRoot "app\libs\platformlive.aar"
}

$goExe = Find-Go -Preferred $GoBin
$goDir = Split-Path $goExe -Parent
$goPathRaw = & $goExe env GOPATH
if ($LASTEXITCODE -ne 0) { throw "go env GOPATH 失败。" }
$goBinDir = Join-Path $goPathRaw "bin"

if (-not (Get-Command "gomobile.exe" -ErrorAction SilentlyContinue)) {
    $gomobileExe = Join-Path $goBinDir "gomobile.exe"
} else {
    $gomobileExe = (Get-Command "gomobile.exe").Source
}
if (-not (Test-Path $gomobileExe)) {
    Write-Host "未找到 gomobile，执行：go install golang.org/x/mobile/cmd/gomobile@latest"
    & $goExe install golang.org/x/mobile/cmd/gomobile@latest
    if ($LASTEXITCODE -ne 0) { throw "gomobile 安装失败。" }
    $gomobileExe = Join-Path $goBinDir "gomobile.exe"
}

$androidSdk = Find-AndroidSdk -Preferred $SdkDir
$ndkDir = Find-Ndk -Sdk $androidSdk

Write-Host "go=$goExe"
Write-Host "gomobile=$gomobileExe"
Write-Host "ndk=$ndkDir"

New-Item -ItemType Directory -Force -Path (Split-Path $OutputAar) | Out-Null

pushd $moduleDir
try {
    & $goExe build ./...
    if ($LASTEXITCODE -ne 0) { throw "go build ./... 失败。" }
    & $goExe test ./...
    if ($LASTEXITCODE -ne 0) { throw "go test ./... 失败。" }

    $env:PATH = "$goDir;$goBinDir;$env:PATH"
    $env:ANDROID_NDK_HOME = $ndkDir
    $env:ANDROID_SDK_ROOT = $androidSdk

    $targetAar = Join-Path $moduleDir "..\app\libs\platformlive.aar"
    & $gomobileExe bind `
        -target "android/arm,android/arm64,android/amd64" `
        -androidapi 24 `
        -o $targetAar `
        .
    if ($LASTEXITCODE -ne 0) { throw "gomobile bind 失败。" }
}
finally {
    popd
}

if ((Split-Path $OutputAar -Leaf) -ne "platformlive.aar") {
    Move-Item (Join-Path $repoRoot "app\libs\platformlive.aar") $OutputAar -Force
}
Write-Host "已生成 AAR：$OutputAar"
