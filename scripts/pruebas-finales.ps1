$ErrorActionPreference = "Stop"

Write-Host "=== Banco XYZ - pruebas finales ===" -ForegroundColor Cyan

function Get-OAuthToken($clientId, $secret, $scope) {
    $pair = "$clientId`:$secret"
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
    $response = Invoke-RestMethod -Method Post `
        -Uri "http://localhost:9000/oauth2/token" `
        -Headers @{ Authorization = "Basic $basic" } `
        -ContentType "application/x-www-form-urlencoded" `
        -Body "grant_type=client_credentials&scope=$([uri]::EscapeDataString($scope))"
    return $response.access_token
}

$apiToken = Get-OAuthToken "bank-api-client" "api-secret-demo" "bank.read bank.write batch.execute events.read events.write"
$atmToken = Get-OAuthToken "bank-atm-client" "atm-secret-demo" "atm.read atm.write"

Write-Host "[OK] OAuth2 tokens obtenidos" -ForegroundColor Green

$web = Invoke-RestMethod -Uri "http://localhost:8091/api/web/dashboard/1/101" -Headers @{Authorization="Bearer $apiToken"}
Write-Host "[OK] BFF Web" -ForegroundColor Green
$web | ConvertTo-Json -Depth 8

$mobile = Invoke-RestMethod -Uri "http://localhost:8092/api/mobile/summary/101" -Headers @{Authorization="Bearer $apiToken"}
Write-Host "[OK] BFF Mobile" -ForegroundColor Green
$mobile | ConvertTo-Json -Depth 8

$saldoAntes = Invoke-RestMethod -Uri "http://localhost:8093/api/atm/balance/101" -Headers @{Authorization="Bearer $atmToken"}
Write-Host "[OK] ATM saldo inicial" -ForegroundColor Green
$saldoAntes | ConvertTo-Json -Depth 8

$body = @{customerId=1;accountId=101;amount=10000} | ConvertTo-Json
$retiro = Invoke-RestMethod -Method Post -Uri "http://localhost:8093/api/atm/withdrawal" -Headers @{Authorization="Bearer $atmToken"} -ContentType "application/json" -Body $body
Write-Host "[OK] ATM retiro" -ForegroundColor Green
$retiro | ConvertTo-Json -Depth 8

Start-Sleep -Seconds 2
$saldoDespues = Invoke-RestMethod -Uri "http://localhost:8093/api/atm/balance/101" -Headers @{Authorization="Bearer $atmToken"}
Write-Host "[OK] ATM saldo posterior (evento Kafka aplicado)" -ForegroundColor Green
$saldoDespues | ConvertTo-Json -Depth 8

foreach ($job in @("transactions","interests","statements")) {
    $result = Invoke-RestMethod -Method Post -Uri "http://localhost:8085/api/batch/$job" -Headers @{Authorization="Bearer $apiToken"}
    if ($result.status -eq "COMPLETED") {
        Write-Host "[OK] Batch $job - COMPLETED" -ForegroundColor Green
    } else {
        Write-Host "[ERROR] Batch $job - $($result.status)" -ForegroundColor Red
        $result | ConvertTo-Json -Depth 8
        throw "El job $job no termino correctamente"
    }
    $result | ConvertTo-Json -Depth 8
}

Start-Sleep -Seconds 2
$notifications = Invoke-RestMethod -Uri "http://localhost:8084/api/notifications" -Headers @{Authorization="Bearer $apiToken"}
Write-Host "[OK] Kafka / Notification consumer" -ForegroundColor Green
$notifications | ConvertTo-Json -Depth 8

Write-Host "=== Pruebas principales completadas ===" -ForegroundColor Cyan
