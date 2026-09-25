# GPS Emulator — установка на телефон
# Запустить один раз. После этого просто открывать приложение.

$adb = "C:\Users\User\.gpsemu_build\android-sdk\platform-tools\adb.exe"
$apk = "$PSScriptRoot\GPS_Emulator.apk"

Write-Host "=== GPS Emulator — установка ==="

# Ждём подключения
Write-Host "[1] Ожидание телефона по ADB..."
$t = 30
while ($t -gt 0) {
    if ((& $adb get-state 2>&1) -eq "device") { break }
    Start-Sleep 2; $t -= 2
}
if ($t -le 0) { Write-Host "Телефон не подключён. Включи USB Debugging."; exit 1 }
Write-Host "    Телефон подключён."

# Устанавливаем APK
Write-Host "[2] Установка APK..."
& $adb install -r $apk 2>&1 | Select-String -Pattern "Success|Failure|Error" | ForEach-Object { Write-Host "    $_" }

# Выдаём WRITE_SECURE_SETTINGS — это позволяет приложению
# самому установить себя как mock location provider без ручных настроек
Write-Host "[3] Выдача разрешения..."
& $adb shell pm grant com.gpsemu android.permission.WRITE_SECURE_SETTINGS 2>&1 | Out-Null
Write-Host "    Готово."

# Первый запуск сервиса
Write-Host "[4] Запуск GPS (Москва)..."
& $adb shell am startservice `
    --es lat "55.7558" --es lon "37.6173" --es alt "150.0" `
    -n "com.gpsemu/.service.MockLocationService" 2>&1 | Out-Null

Write-Host ""
Write-Host "=== Установка завершена ==="
Write-Host "Теперь просто открывай приложение GPS Emulator на телефоне."
Write-Host "Режим разработчика и USB Debugging можно выключить."
