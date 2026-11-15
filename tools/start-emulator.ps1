# Pleos Connect 에뮬레이터를 PC 마이크를 쓰도록 켠다(쉼표에 말로 대답하기).
# 사용: powershell -ExecutionPolicy Bypass -File tools\start-emulator.ps1
# 에뮬레이터는 기본으로 PC 마이크 입력을 0으로 막는다. -allow-host-audio가 그 막음을 푼다.
param([string]$Avd = "Pleos_Connect_2_1")
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$emulator = Join-Path $sdk "emulator\emulator.exe"
if (-not (Test-Path $emulator)) { Write-Error "에뮬레이터를 찾지 못했어요: $emulator"; exit 1 }
Start-Process $emulator -ArgumentList "-avd", $Avd, "-no-snapshot-load", "-gpu", "host", "-allow-host-audio"
Write-Host "에뮬레이터를 켰어요($Avd, PC 마이크 사용). 윈도우 설정 > 개인정보 > 마이크에서 '데스크톱 앱이 마이크에 액세스' 가 켜져 있어야 해요."
