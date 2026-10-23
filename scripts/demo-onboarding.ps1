#Requires -Version 5.1
[CmdletBinding()]
param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $bytes = New-Object byte[] 32
    $random.GetBytes($bytes)
    $token = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}
finally { $random.Dispose() }
$headers = @{ Authorization = "Bearer $token"; 'Idempotency-Key' = [guid]::NewGuid().ToString() }
$channel = 'demo_' + [guid]::NewGuid().ToString('N')
$startBody = @{ country = 'PE'; channel = $channel } | ConvertTo-Json

$wallet = Invoke-RestMethod "$BaseUrl/api/wallets" -Method Post -Headers $headers -ContentType 'application/json' -Body $startBody
$repeat = Invoke-RestMethod "$BaseUrl/api/wallets" -Method Post -Headers $headers -ContentType 'application/json' -Body $startBody
if ($repeat.walletId -ne $wallet.walletId) { throw 'El reintento creo otra wallet.' }
$walletUrl = "$BaseUrl/api/wallets/$($wallet.walletId)"
Write-Host "Wallet mock creada: $($wallet.walletId); paso: $($wallet.currentStep)"

try {
    $null = Invoke-RestMethod "$walletUrl/onboarding/profile" -Method Put -Headers $headers -ContentType 'application/json' `
        -Body '{"displayName":"","email":"correo-invalido"}'
    throw 'La API acepto datos invalidos.'
}
catch {
    if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 400) { throw }
    Write-Host 'OK: perfil invalido bloqueado con HTTP 400.'
}
$progress = Invoke-RestMethod "$walletUrl/onboarding" -Headers $headers
if ($progress.currentStep -ne 'PROFILE') { throw 'Se avanzo con datos invalidos.' }

$profile = @{ displayName = 'Usuario Demo'; email = ([guid]::NewGuid().ToString('N') + '@wallet.test') } | ConvertTo-Json
$progress = Invoke-RestMethod "$walletUrl/onboarding/profile" -Method Put -Headers $headers -ContentType 'application/json' -Body $profile
Write-Host "Perfil valido guardado; paso actual: $($progress.currentStep)"
$progress = Invoke-RestMethod "$walletUrl/onboarding/verification" -Method Post -Headers $headers
if ($progress.currentStep -ne 'CONSENT') {
    throw 'La verificacion no fue aprobada. Revisa WALLET_MOCK_KYC_ENABLED/RESULT; la demo necesita APPROVED.'
}
$progress = Invoke-RestMethod "$walletUrl/onboarding/consent" -Method Put -Headers $headers -ContentType 'application/json' -Body '{"accepted":true}'
if ($progress.status -ne 'ACTIVE') { throw 'No se activo la wallet mock.' }
$entry = Invoke-RestMethod "$walletUrl/payments/entry" -Headers $headers
if (-not $entry.available -or $entry.mode -ne 'MOCK') { throw 'Entrada de pagos mock no disponible.' }

$activityHeaders = @{ Authorization = "Bearer $token"; 'Idempotency-Key' = [guid]::NewGuid().ToString() }
$activity = Invoke-RestMethod "$walletUrl/activity" -Method Post -Headers $activityHeaders
$repeatedActivity = Invoke-RestMethod "$walletUrl/activity" -Method Post -Headers $activityHeaders
if ($activity.eventId -ne $repeatedActivity.eventId) { throw 'El reintento duplico el evento de actividad.' }
Write-Host 'OK: wallet ACTIVE, pagos MOCK y actividad idempotente.'

if ($env:WALLET_ANALYTICS_KEY) {
    $adminHeaders = @{ 'X-Analytics-Key' = $env:WALLET_ANALYTICS_KEY; 'Idempotency-Key' = [guid]::NewGuid().ToString() }
    $month = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow, 'SA Pacific Standard Time').ToString('yyyy-MM')
    $cost = @{ month = $month; channel = $channel; amountCents = 1000 } | ConvertTo-Json
    $null = Invoke-RestMethod "$BaseUrl/api/analytics/acquisition-costs" -Method Post -Headers $adminHeaders -ContentType 'application/json' -Body $cost
    $report = Invoke-RestMethod "$BaseUrl/api/analytics/monthly?month=$month&channel=$channel" -Headers $adminHeaders
    if ($report.activatedWallets -ne 1 -or $report.acquisitionSpendCents -ne 1000) {
        throw 'El reporte no coincide con la cohorte ficticia de esta demo.'
    }
    if ($report.monthlyActiveUsers.available -or $report.acquisitionCost.available) {
        throw 'MAU y CAC deben figurar como no disponibles hasta que Producto apruebe definiciones y fuentes.'
    }
    $report | Select-Object month, channel, dataMode, activatedWallets, medianOnboardingSeconds, acquisitionSpendCents | Format-List
    Write-Host "MAU: $($report.monthlyActiveUsers.reason)"
    Write-Host "CAC: $($report.acquisitionCost.reason)"
    Write-Host 'OK: analitica con una activacion y gasto ficticio de 1000 centimos; MAU/CAC no disponibles; no demuestra las metas reales del MVP.'
}
else {
    Write-Host 'Analitica omitida: configura la misma WALLET_ANALYTICS_KEY en servidor y esta terminal si deseas comprobarla.'
}
Write-Host 'Demo finalizada. Datos ficticios persistidos en el archivo configurado; no se procesaron pagos reales.'