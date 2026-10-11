param([switch]$ProbarResiliencia, [switch]$ProbarCola)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (!(Test-Path .env)) { throw 'Primero ejecuta: Copy-Item .env.example .env' }
$settings = @{}
Get-Content .env | ForEach-Object {
    if ($_ -match '^\s*([A-Z_]+)=(.*)$') { $settings[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'") }
}
New-Item -ItemType Directory -Force docs/evidencias | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
Start-Transcript -Path "docs/evidencias/prueba-$stamp.txt" | Out-Null
function Token($id, $secret, $scope) {
    $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${id}:${secret}"))
    $r = Invoke-RestMethod -Uri 'http://localhost:9000/oauth2/token' -Method Post -Headers @{Authorization="Basic $basic"} -Body @{grant_type='client_credentials';scope=$scope}
    return $r.access_token
}
function Request($method, $url, $token, $expected) {
    $argsCurl = @('-sS','--max-time','20','-X',$method,'-w',"`n%{http_code}",$url)
    if ($token) { $argsCurl += @('-H',"Authorization: Bearer $token") }
    $raw = & curl.exe @argsCurl
    if ($LASTEXITCODE -ne 0) { throw "Fallo de conexion: $url" }
    $lines = ($raw -join "`n") -split "`n"
    $code = [int]$lines[-1]
    $body = ($lines | Select-Object -SkipLast 1) -join "`n"
    Write-Host "$method $url -> $code (esperado $expected)"
    if ($code -ne $expected) { throw "HTTP inesperado: $body" }
    if ($body) { return ($body | ConvertFrom-Json) }
}
function Wait-Bank {
    for ($i=0;$i -lt 90;$i++) {
        try { $r=Invoke-RestMethod 'http://localhost:8081/actuator/health'; if($r.status -eq 'UP'){return} } catch {}
        Start-Sleep 2
    }
    throw 'El servicio bancario no quedo listo en 180 segundos'
}
function Wait-Event($id,$token) {
    for($i=0;$i -lt 30;$i++) {
        try {
            $events=Invoke-RestMethod 'http://localhost:8082/api/notifications' -Headers @{Authorization="Bearer $token"}
            $found=$events | Where-Object {$_.eventId -eq $id}
            if($found){ $found | ConvertTo-Json;return }
        } catch {}
        Start-Sleep 1
    }
    throw "El consumidor no recibio el evento $id"
}
try {
    docker compose ps
    if($LASTEXITCODE -ne 0){throw 'Docker Compose no disponible'}
    foreach($port in @(8888,8761,9000,8081,8082)) {
        Request GET "http://localhost:$port/actuator/health" '' 200 | ConvertTo-Json
    }
    $api=Token 'bank-api-client' $settings['API_CLIENT_SECRET'] 'bank.read events.write events.read'
    $atm=Token 'bank-atm-client' $settings['ATM_CLIENT_SECRET'] 'atm.read atm.write'
    Write-Host 'OAuth2: tokens emitidos para API y ATM (se omiten los secretos del registro).'
    Request GET 'http://localhost:8081/api/accounts/106' '' 401 | Out-Null
    Request GET 'http://localhost:8081/api/accounts/106' 'invalid' 401 | Out-Null
    Request GET 'http://localhost:8081/api/accounts/106' $atm 403 | Out-Null
    Request GET 'http://localhost:8081/api/atm/cuentas/106/saldo' $api 403 | Out-Null
    Request GET 'http://localhost:8081/api/accounts/106' $api 200 | ConvertTo-Json -Depth 8
    Request GET 'http://localhost:8081/api/atm/cuentas/106/saldo' $atm 200 | ConvertTo-Json
    Request GET 'http://localhost:8081/api/accounts/106/migration-report' $api 200 | ConvertTo-Json -Depth 8
    Request GET 'http://localhost:8082/api/notifications' '' 401 | Out-Null
    Request GET 'http://localhost:8082/api/notifications' $atm 403 | Out-Null
    $event=Request POST 'http://localhost:8081/api/events/migration/106' $api 202
    $event | ConvertTo-Json
    Wait-Event $event.eventId $api
    if($ProbarCola) {
        try {
            docker compose stop notification-service
            if($LASTEXITCODE -ne 0){throw 'No se pudo detener el consumidor'}
            $queued=Request POST 'http://localhost:8081/api/events/migration/106' $api 202
            Write-Host "Evento encolado con consumidor detenido: $($queued.eventId)"
        } finally {
            docker compose start notification-service
        }
        Start-Sleep 15
        Wait-Event $queued.eventId $api
    }
    if($ProbarResiliencia) {
        $previous=$env:FORCE_LEGACY_AUDIT_FAILURE
        try {
            $env:FORCE_LEGACY_AUDIT_FAILURE='true'
            docker compose up -d --no-deps --force-recreate bank-account-service
            if($LASTEXITCODE -ne 0){throw 'No se pudo recrear el servicio'}
            Wait-Bank
            1..4 | ForEach-Object {
                $r=Request GET 'http://localhost:8081/api/accounts/106/migration-report' $api 200
                if($r.estado -ne 'VALIDATED_WITH_FALLBACK'){throw 'No se activo el fallback'}
                $r | ConvertTo-Json -Depth 6
            }
            $circuit=Request GET 'http://localhost:8081/actuator/circuitbreakers' $api 200
            $circuit | ConvertTo-Json -Depth 10
            if($circuit.circuitBreakers.legacyAudit.state -ne 'OPEN'){throw 'El circuito legacyAudit no quedo OPEN'}
            Request GET 'http://localhost:8081/actuator/circuitbreakerevents' $api 200 | ConvertTo-Json -Depth 10
        } finally {
            if($null -eq $previous){Remove-Item Env:FORCE_LEGACY_AUDIT_FAILURE -ErrorAction SilentlyContinue}else{$env:FORCE_LEGACY_AUDIT_FAILURE=$previous}
            docker compose up -d --no-deps --force-recreate bank-account-service
            Wait-Bank
        }
        $recovered=Request GET 'http://localhost:8081/api/accounts/106/migration-report' $api 200
        $recovered | ConvertTo-Json -Depth 6
        $effectiveForce=$settings['FORCE_LEGACY_AUDIT_FAILURE']
        if($previous){$effectiveForce=$previous}
        if($effectiveForce -ne 'true' -and $recovered.estado -ne 'VALIDATED'){throw 'El reporte no se recupero tras desactivar el fallo'}
    }
    docker compose logs --no-color --tail 40 config-server discovery-server auth-server bank-account-service notification-service artemis
    Write-Host 'PRUEBAS COMPLETADAS'
} finally { Stop-Transcript | Out-Null }
