# GPS Emulator — запуск для MEmu
# Запускает frida-server, патчит system_server, активирует MockLocationService

$adb = "C:\Users\User\.gpsemu_build\android-sdk\platform-tools\adb.exe"
$patch = "$PSScriptRoot\patch_system_server.js"

$LAT = "55.7558"
$LON = "37.6173"
$ALT = "150.0"

Write-Host "=== GPS Emulator ==="

# 1. Ждём подключения ADB
Write-Host "[1] Ожидание ADB..."
$timeout = 30
while ($timeout -gt 0) {
    $dev = & $adb get-state 2>&1
    if ($dev -eq "device") { Write-Host "    ADB готов"; break }
    Start-Sleep -Seconds 2; $timeout -= 2
}
if ($timeout -le 0) { Write-Host "ADB не подключён"; exit 1 }

# 2. Запустить frida-server на устройстве
Write-Host "[2] Запуск frida-server..."
& $adb shell "su -c 'killall frida-server 2>/dev/null; nohup /data/local/tmp/frida-server -D > /dev/null 2>&1 &'" 2>&1 | Out-Null
Start-Sleep -Seconds 3

# 3. Патч system_server (убирает mock флаг для всех приложений)
Write-Host "[3] Патч system_server..."
$ssPid = (& $adb shell pidof system_server 2>&1).Trim()
if ($ssPid) {
    $proc = Start-Process -NoNewWindow -PassThru -FilePath "frida" `
        -ArgumentList "-U -p $ssPid -l `"$patch`"" `
        -RedirectStandardOutput "$env:TEMP\frida_ss.log" `
        -RedirectStandardError "$env:TEMP\frida_ss_err.log"
    Start-Sleep -Seconds 4
    $log = Get-Content "$env:TEMP\frida_ss.log" -ErrorAction SilentlyContinue
    if ($log -match "mock cleared") {
        Write-Host "    system_server пропатчен (mock=false для всех)"
    } else {
        Write-Host "    ПРЕДУПРЕЖДЕНИЕ: патч не применился"
        $log | Select-Object -Last 5 | ForEach-Object { Write-Host "    $_" }
    }
} else {
    Write-Host "    system_server не найден"
}

# 4. Установить mock_location_app и location_mode
Write-Host "[4] Настройка системы..."
& $adb shell "settings put secure mock_location_app com.gpsemu" 2>&1 | Out-Null
& $adb shell "settings put secure location_mode 3" 2>&1 | Out-Null

# 5. Запустить MockLocationService с координатами
Write-Host "[5] Запуск MockLocationService ($LAT, $LON)..."
& $adb shell "am startservice --es lat `"$LAT`" --es lon `"$LON`" --es alt `"$ALT`" -n com.gpsemu/.service.MockLocationService" 2>&1 | Out-Null
Start-Sleep -Seconds 2

# 6. Проверка
$locs = & $adb shell "dumpsys location | grep 'fused:'" 2>&1 | Select-Object -First 1
Write-Host "[6] Локация: $locs"

Write-Host ""
Write-Host "=== Готово. GPS установлен на Москву ($LAT, $LON) ==="
