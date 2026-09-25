# GPS Emulator — полная автоматическая сборка APK
# Запускай: правой кнопкой -> "Выполнить с PowerShell"
# При первом запуске скачает ~450 MB (JDK + Android SDK + Gradle)
# Повторная сборка занимает ~10 секунд

Set-StrictMode -Off
$ErrorActionPreference = "Stop"

$ProjectRoot = $PSScriptRoot
$ToolsDir    = "$env:USERPROFILE\.gpsemu_build"
$JdkDir      = "$ToolsDir\jdk17"
$SdkDir      = "$ToolsDir\android-sdk"
$GradleDir   = "$ToolsDir\gradle-8.7"
$DesktopApk  = "$env:USERPROFILE\Desktop\GpsEmulator.apk"

function Write-Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    OK: $msg" -ForegroundColor Green }
function Write-Err($msg)  {
    Write-Host "`n!!! ОШИБКА: $msg" -ForegroundColor Red
    Write-Host "Нажми Enter для выхода..."
    $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
    exit 1
}

function Download-Bits($url, $dest, $name) {
    if ((Test-Path $dest) -and (Get-Item $dest).Length -gt 1000000) { return }
    Remove-Item $dest -Force -ErrorAction SilentlyContinue
    Write-Host "    Загружаю $name ..."
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Start-BitsTransfer -Source $url -Destination $dest
    Write-Host "    Готово: $('{0:N0}' -f (Get-Item $dest).Length) байт" -ForegroundColor Green
}

New-Item -ItemType Directory -Force $ToolsDir | Out-Null

# ── 1. JDK 17 ────────────────────────────────────────────────────────────────
Write-Step "Шаг 1/5: JDK 17"

$javaExe = $null
# Check PATH
if ((Get-Command java -ErrorAction SilentlyContinue)) {
    $ver = java -version 2>&1 | Select-String "version" | Select-Object -First 1
    if ("$ver" -match '"1[7-9]|"2[0-9]') { $javaExe = (Get-Command java).Source }
}
# Check Android Studio JBR
if (!$javaExe) {
    foreach ($p in @("$env:ProgramFiles\Android\Android Studio\jbr\bin\java.exe",
                     "$env:LOCALAPPDATA\Programs\Android Studio\jbr\bin\java.exe")) {
        if (Test-Path $p) { $javaExe = $p; break }
    }
}
# Check cached download
if (!$javaExe -and (Test-Path "$JdkDir\bin\java.exe")) {
    $javaExe = "$JdkDir\bin\java.exe"
}
# Download Temurin JDK 17
if (!$javaExe) {
    $jdkZip = "$ToolsDir\jdk17.zip"
    Download-Bits "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.11%2B9/OpenJDK17U-jdk_x64_windows_hotspot_17.0.11_9.zip" $jdkZip "JDK 17 Temurin (~190 MB)"
    $ex = "$ToolsDir\jdk17_ex"; Expand-Archive $jdkZip $ex -Force
    $inner = Get-ChildItem $ex -Directory | Select-Object -First 1
    if (!(Test-Path $JdkDir)) { Move-Item $inner.FullName $JdkDir }
    $javaExe = "$JdkDir\bin\java.exe"
}
Write-Ok "Java: $javaExe"
$env:JAVA_HOME = Split-Path (Split-Path $javaExe)
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# ── 2. Android SDK packages ──────────────────────────────────────────────────
Write-Step "Шаг 2/5: Android SDK"

$packages = @(
    @{ Name="platform-35";    Zip="$ToolsDir\platform-35.zip";    Url="https://dl.google.com/android/repository/platform-35_r02.zip";            CheckDir="$SdkDir\platforms\android-35" }
    @{ Name="build-tools-35"; Zip="$ToolsDir\build-tools-35.zip"; Url="https://dl.google.com/android/repository/build-tools_r35_windows.zip";    CheckDir="$SdkDir\build-tools\35.0.0" }
    @{ Name="platform-tools"; Zip="$ToolsDir\platform-tools.zip"; Url="https://dl.google.com/android/repository/platform-tools_r37.0.1-win.zip"; CheckDir="$SdkDir\platform-tools" }
)

foreach ($p in $packages) {
    if (Test-Path $p.CheckDir) { Write-Ok "$($p.Name) уже установлен"; continue }
    Download-Bits $p.Url $p.Zip $p.Name
    $ex = "$ToolsDir\$($p.Name)_ex"
    Expand-Archive $p.Zip $ex -Force
    $inner = Get-ChildItem $ex -Directory | Select-Object -First 1
    if ($p.Name -eq "platform-35") {
        New-Item -ItemType Directory -Force "$SdkDir\platforms" | Out-Null
        Move-Item $inner.FullName "$SdkDir\platforms\android-35"
    } elseif ($p.Name -eq "build-tools-35") {
        New-Item -ItemType Directory -Force "$SdkDir\build-tools" | Out-Null
        Move-Item $inner.FullName "$SdkDir\build-tools\35.0.0"
    } else {
        Move-Item $inner.FullName "$SdkDir\platform-tools"
    }
    Write-Ok "$($p.Name) установлен"
}

# SDK license files (hashes known; same as sdkmanager --licenses accepts)
$lic = "$SdkDir\licenses"
New-Item -ItemType Directory -Force $lic | Out-Null
"8933bad161af4178b1185d1a37fbf41ea5269c55`nd56f5187479451eabf01fb78af6dfcb131a6481e`n24333f8a63b6825ea9c5514f83c2829b004d1fee" |
    Set-Content "$lic\android-sdk-license" -Encoding UTF8 -NoNewline
"84831b9409646a918e30573bab4c9c91346d8abd" |
    Set-Content "$lic\android-sdk-preview-license" -Encoding UTF8 -NoNewline
"859f317696f67ef3d7f30a50a5560e7834b43903" |
    Set-Content "$lic\android-sdk-arm-dbt-license" -Encoding UTF8 -NoNewline

$env:ANDROID_HOME     = $SdkDir
$env:ANDROID_SDK_ROOT = $SdkDir

# ── 3. Gradle 8.7 ────────────────────────────────────────────────────────────
Write-Step "Шаг 3/5: Gradle 8.7"

if (!(Test-Path "$GradleDir\bin\gradle.bat")) {
    $gz = "$ToolsDir\gradle-8.7-bin.zip"
    Download-Bits "https://services.gradle.org/distributions/gradle-8.7-bin.zip" $gz "Gradle 8.7 (~130 MB)"
    $ex = "$ToolsDir\gradle_ex"; Expand-Archive $gz $ex -Force
    $inner = Get-ChildItem $ex -Directory | Select-Object -First 1
    Move-Item $inner.FullName $GradleDir
}
Write-Ok "Gradle: $GradleDir"

# ── 4. Gradle wrapper ────────────────────────────────────────────────────────
Write-Step "Шаг 4/5: Настройка проекта"

"sdk.dir=$($SdkDir.Replace('\','\\'))" | Set-Content "$ProjectRoot\local.properties" -Encoding UTF8
Write-Ok "local.properties создан"

$env:GRADLE_USER_HOME = "$ToolsDir\gradle_home"

if (!(Test-Path "$ProjectRoot\gradle\wrapper\gradle-wrapper.jar")) {
    Write-Host "    Генерирую Gradle wrapper..."
    Push-Location $ProjectRoot
    & "$GradleDir\bin\gradle.bat" wrapper --gradle-version=8.7 --distribution-type=bin | Out-Null
    Pop-Location
    Write-Ok "Gradle wrapper создан"
} else {
    Write-Ok "Gradle wrapper уже есть"
}

# ── 5. Сборка APK ────────────────────────────────────────────────────────────
Write-Step "Шаг 5/5: Сборка APK"
Write-Host "    Первая сборка ~2 минуты, повторная ~10 секунд..."

Push-Location $ProjectRoot
& ".\gradlew.bat" assembleDebug
$exitCode = $LASTEXITCODE
Pop-Location

if ($exitCode -ne 0) { Write-Err "Gradle завершился с кодом $exitCode. Проверь вывод выше." }

$apkFile = Get-ChildItem "$ProjectRoot\app\build\outputs\apk\debug\*.apk" | Select-Object -First 1
if (!$apkFile) { Write-Err "APK файл не найден после успешной сборки" }

Copy-Item $apkFile.FullName $DesktopApk -Force

# ── 6. Установка в MEmu (опционально) ───────────────────────────────────────
$adb = "$SdkDir\platform-tools\adb.exe"
if (Test-Path $adb) {
    Write-Step "Шаг 6/6: Установка в MEmu"
    $connected = & $adb connect 127.0.0.1:21503 2>&1
    Write-Host "    $connected"
    $devices = & $adb devices 2>&1
    if ("$devices" -match "127.0.0.1:21503\s+device") {
        Write-Host "    Устанавливаю APK в MEmu..."
        & $adb -s 127.0.0.1:21503 install -r $apkFile.FullName
        if ($LASTEXITCODE -eq 0) {
            Write-Ok "Установлено в MEmu"
            & $adb -s 127.0.0.1:21503 shell am force-stop com.gpsemu | Out-Null
            & $adb -s 127.0.0.1:21503 shell am start -n com.gpsemu/.ui.MainActivity | Out-Null
            Write-Ok "Приложение запущено в MEmu"
        }
    } else {
        Write-Host "    MEmu не обнаружен — пропускаю автоустановку" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "╔════════════════════════════════════════════════════╗" -ForegroundColor Green
Write-Host "║  ГОТОВО!  APK сохранён на рабочий стол:           ║" -ForegroundColor Green
Write-Host "║                                                    ║" -ForegroundColor Green
Write-Host "║  GpsEmulator.apk                                  ║" -ForegroundColor Green
Write-Host "╚════════════════════════════════════════════════════╝" -ForegroundColor Green

Write-Host "`nНажми Enter для выхода..."
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
