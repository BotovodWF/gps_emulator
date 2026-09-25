# Общие настройки и помощники для тестовых скриптов.
# Подключение:  . "$PSScriptRoot\_common.ps1"
#
# ВАЖНО: все .ps1 с кириллицей сохраняй в UTF-8 С BOM. Без BOM PowerShell 5.1
# читает файл как cp1251, байты букв Б/В/Г/Д и тире превращаются в кавычки,
# и парсер падает с "The string is missing the terminator".

$ProjectRoot = Split-Path $PSScriptRoot -Parent
$ToolsDir    = "$env:USERPROFILE\.gpsemu_build"

$Adb    = "$ToolsDir\android-sdk\platform-tools\adb.exe"
$Device = "127.0.0.1:21503"
$MEmu   = "C:\MEmu\Microvirt\MEmu\MEmu.exe"

$Package      = "com.gpsemu"
$ServiceName  = "$Package/.service.MockLocationService"
$ActivityName = "$Package/.ui.MainActivity"
$YandexMaps   = "ru.yandex.yandexmaps"

function Initialize-BuildEnv {
    $env:JAVA_HOME        = "$ToolsDir\jdk17"
    $env:PATH             = "$env:JAVA_HOME\bin;$env:PATH"
    $env:ANDROID_HOME     = "$ToolsDir\android-sdk"
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    $env:GRADLE_USER_HOME = "$ToolsDir\gradle_home"
}

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "    $msg" -ForegroundColor Green }
function Fail($msg) { Write-Host "    $msg" -ForegroundColor Red }

# Простая (не advanced) функция намеренно: атрибут [Parameter] включил бы common
# parameters, и тогда PowerShell разрешает `-d` в `-Debug` и съедает аргумент,
# ломая вызовы вида `am start -d "geo:..."`.
function Invoke-Adb {
    & $Adb -s $Device @args 2>&1
}

function Get-Screenshot($name) {
    $path = "$ProjectRoot\$name.png"
    Invoke-Adb shell screencap -p /sdcard/shot.png | Out-Null
    & $Adb -s $Device pull /sdcard/shot.png $path 2>&1 | Out-Null
    Invoke-Adb shell rm /sdcard/shot.png | Out-Null
    Ok "Скриншот: $name.png"
    return $path
}

function Restart-Emulator {
    Get-Process | Where-Object { $_.Name -match "MEmu|memu" } | Stop-Process -Force
    Start-Sleep -Seconds 4
    Start-Process $MEmu
    Write-Host "    Ждём загрузки Android..."
    Start-Sleep -Seconds 25
    & $Adb connect $Device 2>&1 | Out-Null
    for ($i = 0; $i -lt 20; $i++) {
        if ("$(Invoke-Adb shell getprop sys.boot_completed)".Trim() -eq "1") {
            Ok "Android готов"
            return
        }
        Start-Sleep -Seconds 3
    }
    Fail "Android не загрузился"
}

function Install-Apk {
    $apk = (Get-ChildItem "$ProjectRoot\app\build\outputs\apk\debug\*.apk" | Select-Object -First 1).FullName
    Invoke-Adb uninstall $Package | Out-Null
    Invoke-Adb install $apk | Select-String "Success|Failure"
    # Разрешение на подмену геолокации — иначе setTestProviderLocation бросит SecurityException
    Invoke-Adb shell appops set $Package android:mock_location allow | Out-Null
}

<#
.SYNOPSIS
    Телепортирует эмулятор в заданную точку.
.DESCRIPTION
    Координаты передаются строками (--es), а не числами: у `am` нет флага для
    double, а его `--ef` кладёт float, на котором getDoubleExtra молча вернёт
    значение по умолчанию — и телепорт уходил в точку 0°N 0°E.
#>
function Set-MockLocation($lat, $lon, $alt = 0) {
    Invoke-Adb shell am startforegroundservice -n $ServiceName `
        --es lat "$lat" --es lon "$lon" --es alt "$alt" | Out-Null
    Start-Sleep -Seconds 3
}

<#
.SYNOPSIS
    Сверяет координаты, которые система реально отдаёт приложениям, с заданными.
.DESCRIPTION
    Читает провайдер из `dumpsys location`. Это проверка на уровне системы:
    что бы ни рисовали карты, здесь видно, какие координаты получит любое
    приложение. Порог 1e-5 градуса — примерно метр.
#>
function Test-MockAccuracy($lat, $lon) {
    $dump = Invoke-Adb shell "dumpsys location 2>/dev/null"
    $line = ($dump | Select-String "gps.*mock" | Select-Object -First 1).ToString()

    if ($line -notmatch '(\d+),(\d+)\s*,\s*(\d+),(\d+)') {
        Fail "Не удалось прочитать mock-локацию"
        return $false
    }

    $gotLat = [double]"$($Matches[1]).$($Matches[2])"
    $gotLon = [double]"$($Matches[3]).$($Matches[4])"
    $offLat = [math]::Abs($gotLat - $lat)
    $offLon = [math]::Abs($gotLon - $lon)

    if ($offLat -lt 1e-5 -and $offLon -lt 1e-5) {
        Ok "ТОЧНО: задано $lat,$lon -> получено $gotLat,$gotLon"
        return $true
    }

    $metres = [math]::Round([math]::Max($offLat * 111000, $offLon * 111000 * 0.6), 1)
    Fail "ОТКЛОНЕНИЕ ${metres} м: задано $lat,$lon -> получено $gotLat,$gotLon"
    return $false
}

function Open-YandexMaps {
    Invoke-Adb shell monkey -p $YandexMaps -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Seconds 6
}

<#
.SYNOPSIS
    Открывает Яндекс Карты с эталонным маркером в заданной точке.
.DESCRIPTION
    geo-intent ставит маркер ровно в переданные координаты — это эталон, с которым
    сверяется позиция mock GPS. Сам по себе он НЕ проверяет геолокацию: маркер
    рисуется там, куда просили, независимо от того, где «находится» устройство.
#>
function Open-GeoPoint($lat, $lon) {
    # Запущенные Карты игнорируют новый geo-intent и остаются на прежнем виде,
    # поэтому закрываем их перед каждым переходом
    Invoke-Adb shell am force-stop $YandexMaps | Out-Null
    Start-Sleep -Seconds 1
    Invoke-Adb shell am start -a android.intent.action.VIEW `
        -d "geo:$lat,$lon?q=$lat,$lon" -p $YandexMaps | Out-Null
    Start-Sleep -Seconds 7
}

function Remove-Screenshots {
    Remove-Item "$ProjectRoot\*.png" -Force -ErrorAction SilentlyContinue
}
