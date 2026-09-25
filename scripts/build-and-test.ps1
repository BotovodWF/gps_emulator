# Полный цикл: сборка APK -> перезапуск MEmu -> установка -> проверка точности.
# Запуск:  .\scripts\build-and-test.ps1

. "$PSScriptRoot\_common.ps1"

Initialize-BuildEnv
Set-Location $ProjectRoot

Step "Сборка APK"
.\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD|error:" | Select-Object -Last 5
if ($LASTEXITCODE -ne 0) { Fail "Сборка не прошла"; exit 1 }

Step "Перезагрузка MEmu"
Restart-Emulator

Step "Установка APK"
Install-Apk

# Карта приложения и Яндекс Карты на одних координатах должны показать одно место.
# Расхождение здесь означает ошибку в проекции: тайлы Яндекса — эллиптический
# Меркатор (EPSG:3395), а не сферический.
Step "Наша карта: 53.1880, 50.1800"
Invoke-Adb shell am start -n $ActivityName | Out-Null
Start-Sleep -Seconds 8
Get-Screenshot "a1_nasha_karta" | Out-Null

Step "Яндекс Карты (эталон): 53.1880, 50.1800"
Open-GeoPoint 53.1880 50.1800
Get-Screenshot "a2_yandex_etalon" | Out-Null

Step "Точность телепортации"
$points = @(
    @(53.1880, 50.1800),   # Самара, Аэродромная
    @(53.1858, 50.1440),   # Самара, Ж/Д
    @(55.7558, 37.6176)    # Москва, Красная площадь
)
$failed = 0
foreach ($p in $points) {
    Set-MockLocation $p[0] $p[1]
    if (-not (Test-MockAccuracy $p[0] $p[1])) { $failed++ }
}

Write-Host ""
if ($failed -eq 0) {
    Ok "Все точки переданы без отклонений"
} else {
    Fail "Точек с отклонением: $failed"
}
Write-Host "Сравни a1_nasha_karta.png и a2_yandex_etalon.png — должно быть одно место." -ForegroundColor Yellow
