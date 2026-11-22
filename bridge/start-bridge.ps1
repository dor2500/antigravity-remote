# Antigravity Remote Bridge PowerShell Launcher
$ErrorActionPreference = "Stop"
$BridgeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $BridgeDir

$Port = 8765
$RuleName = "Antigravity Remote Bridge (TCP $Port)"

Write-Host "[Bridge] Checking Node.js installation..." -ForegroundColor Cyan
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Host "[ERROR] Node.js is not installed or not in PATH. Install Node.js 18+ first." -ForegroundColor Red
    exit 1
}

if (-not (Test-Path (Join-Path $BridgeDir "node_modules"))) {
    Write-Host "[Bridge] Installing npm dependencies..." -ForegroundColor Cyan
    npm install
}

Write-Host "[Bridge] Building TypeScript server..." -ForegroundColor Cyan
npm run build

# Attempt to open Windows Firewall port if not already present
$existingRule = Get-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue
if (-not $existingRule) {
    try {
        New-NetFirewallRule -DisplayName $RuleName -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Private,Domain -ErrorAction Stop | Out-Null
        Write-Host "[Bridge] Opened Windows Firewall TCP port $Port." -ForegroundColor Green
    } catch {
        Write-Host "[WARN] Could not auto-add Windows Firewall rule (run as Administrator if phone cannot connect)." -ForegroundColor Yellow
    }
}

Write-Host "[Bridge] Starting Antigravity Remote Bridge on port $Port..." -ForegroundColor Green
node dist/index.js --port $Port
