# Быстрый телепорт в точку без пересборки APK.
# Запуск:  .\scripts\teleport.ps1 -lat 53.1880 -lon 50.1800
#          .\scripts\teleport.ps1 -lat 53.1880 -lon 50.1800 -Screenshot

param(
    [double]$lat = 53.1880,
    [double]$lon = 50.1800,
    [string]$label = "teleport",
    [switch]$Screenshot   # снять экран Яндекс Карт с эталонным маркером
)

. "$PSScriptRoot\_common.ps1"

Step "Телепорт: $lat, $lon"
Set-MockLocation $lat $lon
Test-MockAccuracy $lat $lon | Out-Null

if ($Screenshot) {
    Open-GeoPoint $lat $lon
    Get-Screenshot $label | Out-Null
}
