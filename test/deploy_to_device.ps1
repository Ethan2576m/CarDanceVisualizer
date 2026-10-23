<#
    律动舞蹈 App —— 笔记本侧一键部署脚本（模拟器 / 真机通用）

    用法：
        1) 先把云端打包产物 app-debug.apk 下载下来，放到本脚本上一级目录，
           或用 -Apk 指定完整路径
        2) 启动好模拟器（AVD / 雷电 / MuMu）或用数据线连上手机并已开启 USB 调试
        3) 在本目录执行：
             powershell -ExecutionPolicy Bypass -File .\deploy_to_device.ps1
           指定 APK 路径：
             powershell -ExecutionPolicy Bypass -File .\deploy_to_device.ps1 -Apk "D:\xxx\app-debug.apk"

    脚本会做四件事：装 APK → 推送测试音轨 → 授予录音权限 → 拉起 App
#>

param(
    [string]$Apk  = "",
    [string]$Wav  = "$PSScriptRoot\test_track.wav",
    [string]$Ser  = "",
    [string]$Pkg  = "com.cardance.visualizer"
)

$ErrorActionPreference = "Stop"

# ---------- 0. 定位 adb ----------
$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe"
    ) | Where-Object { $_ -and (Test-Path $_) }
    if ($candidates.Count -gt 0) {
        $adb = $candidates[0]
    } else {
        Write-Host "[X] 找不到 adb。" -ForegroundColor Red
        Write-Host "    方案 A：安装 Android Studio（自带 platform-tools）"
        Write-Host "    方案 B：单独下载 platform-tools 解压后把 adb.exe 所在目录加入 PATH"
        Write-Host "    方案 C：第三方模拟器（雷电/MuMu）自带 adb，在模拟器安装目录里"
        exit 1
    }
}
Write-Host "[OK] adb = $adb" -ForegroundColor Green

# ---------- 1. 找设备 ----------
& $adb start-server | Out-Null

# 没有设备时，尝试连接本机第三方模拟器的常见 adb 端口
# 雷电/MuMu 7555、通用 5555/5554、夜神 62001、BlueStacks/逍遥 21503
if (-not ((& $adb devices) | Select-String "device$")) {
    Write-Host "[i] 未发现设备，尝试连接本机模拟器端口..."
    foreach ($p in @(7555, 5555, 5554, 62001, 21503, 62025)) {
        & $adb connect "127.0.0.1:$p" 2>$null | Out-Null
    }
}

$devices = (& $adb devices) | Select-String "device$" | ForEach-Object { ($_ -split "\s+")[0] }
if (-not $devices) {
    Write-Host "[X] 没有检测到设备。" -ForegroundColor Red
    Write-Host "    · 官方 AVD：启动 Android Studio 模拟器，等它完全开机再跑本脚本" -ForegroundColor Yellow
    Write-Host "    · 第三方模拟器：雷电/MuMu 需开启 adb 调试，常见端口 7555 或 5555" -ForegroundColor Yellow
    Write-Host "    · 真机：USB 连接并开启 USB 调试，手机上点「允许」" -ForegroundColor Yellow
    exit 1
}

if ($Ser) {
    if ($devices -notcontains $Ser) { Write-Host "[X] 指定的设备 $Ser 不在线" -ForegroundColor Red; exit 1 }
    $dev = $Ser
} else {
    $dev = $devices[0]
    if ($devices.Count -gt 1) { Write-Host "[i] 检测到多台设备，默认用第一台: $dev" }
}
Write-Host "[OK] 目标设备 = $dev" -ForegroundColor Green

# ---------- 2. 安装 APK ----------
if (-not $Apk) {
    $Apk = Join-Path (Split-Path $PSScriptRoot -Parent) "app-debug.apk"
}
if (-not (Test-Path $Apk)) {
    Write-Host "[X] 找不到 APK: $Apk" -ForegroundColor Red
    Write-Host "    请先从 Actions → Artifacts 下载 car-dance-apk 并解压到 CarDanceVisualizer 目录，或用 -Apk 指定路径。" -ForegroundColor Red
    exit 1
}
Write-Host "[..] 安装 APK: $Apk"
& $adb -s $dev install -r $Apk
if ($LASTEXITCODE -ne 0) { Write-Host "[X] 安装失败" -ForegroundColor Red; exit 1 }

# ---------- 3. 推送测试音轨 ----------
if (Test-Path $Wav) {
    Write-Host "[..] 推送测试音轨 -> /sdcard/Music/test_track.wav"
    & $adb -s $dev push $Wav "/sdcard/Music/test_track.wav" | Out-Null
    Write-Host "[OK] 音轨已推送" -ForegroundColor Green
} else {
    Write-Host "[!] 没找到 $Wav，跳过音轨推送" -ForegroundColor Yellow
}

# ---------- 4. 授权 + 启动 ----------
Write-Host "[..] 授予录音权限"
& $adb -s $dev shell pm grant $Pkg android.permission.RECORD_AUDIO 2>$null
& $adb -s $dev shell pm grant $Pkg android.permission.MODIFY_AUDIO_SETTINGS 2>$null
& $adb -s $dev shell pm grant $Pkg android.permission.WAKE_LOCK 2>$null

Write-Host "[..] 拉起 App"
& $adb -s $dev shell am start -n "$Pkg/.MainActivity" | Out-Null

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host " 部署完成，接下来在设备屏幕上做两步：" -ForegroundColor Cyan
Write-Host " 1) 长按屏幕 -> 选「文档/Documents」-> 内部存储 -> Music -> test_track.wav" -ForegroundColor Cyan
Write-Host " 2) 播放后观察：底部 BPM 数字、顶部「副歌 · 律动爆发」标签、舞者是否破框" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
