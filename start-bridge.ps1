# Antigravity Remote - bridge launcher for Windows.
# Installs Node.js dependencies, builds the bridge, opens the firewall port and starts the server.
param(
    [int]$Port = 8765,
    [string]$Workspace = "",
    [string]$Name = "",
    [switch]$ResetToken,
    [switch]$SkipFirewall
)

$ErrorActionPreference = "Stop"
$RepoDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BridgeDir = Join-Path $RepoDir "bridge"
$RuleName = "Antigravity Remote Bridge (TCP $Port)"

function Write-Step([string]$Message) { Write-Host "[bridge] $Message" -ForegroundColor Cyan }
function Write-Warn([string]$Message) { Write-Host "[bridge] $Message" -ForegroundColor Yellow }

function Update-SessionPath {
    $machine = [Environment]::GetEnvironmentVariable("Path", "Machine")
    $user = [Environment]::GetEnvironmentVariable("Path", "User")
    $env:Path = "$machine;$user"
}

# 1. Node.js 18 or newer
$node = Get-Command node -ErrorAction SilentlyContinue
if (-not $node) {
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        Write-Step "Node.js not found. Installing Node.js LTS with winget..."
        winget install --id OpenJS.NodeJS.LTS --exact --silent --accept-package-agreements --accept-source-agreements
        Update-SessionPath
        $node = Get-Command node -ErrorAction SilentlyContinue
    }
    if (-not $node) {
        Write-Host "[bridge] Node.js 18+ is required. Install it from https://nodejs.org and run this again." -ForegroundColor Red
        exit 1
    }
}
$nodeMajor = [int]((& node --version).TrimStart("v").Split(".")[0])
if ($nodeMajor -lt 18) {
    Write-Host "[bridge] Node.js $(& node --version) is too old. Install Node.js 18 or newer." -ForegroundColor Red
    exit 1
}

Set-Location $BridgeDir

# 2. Dependencies (reinstalled when package-lock.json changes)
$lockFile = Join-Path $BridgeDir "package-lock.json"
$stampFile = Join-Path $BridgeDir "node_modules\.install-stamp"
$needInstall = -not (Test-Path $stampFile)
if (-not $needInstall -and (Test-Path $lockFile)) {
    $needInstall = (Get-Item $lockFile).LastWriteTimeUtc -gt (Get-Item $stampFile).LastWriteTimeUtc
}
if ($needInstall) {
    Write-Step "Installing dependencies..."
    if (Test-Path $lockFile) { npm ci --no-audit --no-fund } else { npm install --no-audit --no-fund }
    if ($LASTEXITCODE -ne 0) { Write-Host "[bridge] npm install failed." -ForegroundColor Red; exit 1 }
    New-Item -ItemType File -Force -Path $stampFile | Out-Null
}

# 3. Build when sources are newer than the compiled output
$entry = Join-Path $BridgeDir "dist\index.js"
$needBuild = -not (Test-Path $entry)
if (-not $needBuild) {
    $builtAt = (Get-Item $entry).LastWriteTimeUtc
    $newest = Get-ChildItem (Join-Path $BridgeDir "src") -Recurse -File | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    $needBuild = $newest -and $newest.LastWriteTimeUtc -gt $builtAt
}
if ($needBuild) {
    Write-Step "Building bridge..."
    npm run build
    if ($LASTEXITCODE -ne 0) { Write-Host "[bridge] Build failed." -ForegroundColor Red; exit 1 }
}

# 4. Firewall rule (needs one UAC prompt the first time). Limited to the local subnet and Tailscale.
$rule = Get-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue
if (-not $rule -and -not $SkipFirewall) {
    Write-Step "Opening Windows Firewall port $Port (approve the UAC prompt)..."
    $ruleCommand = "New-NetFirewallRule -DisplayName '$RuleName' -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port -RemoteAddress LocalSubnet,100.64.0.0/10 -Profile Any | Out-Null"
    try {
        $elevated = Start-Process powershell.exe -Verb RunAs -Wait -PassThru -WindowStyle Hidden -ArgumentList @("-NoProfile", "-Command", $ruleCommand)
        if ($elevated.ExitCode -eq 0) {
            Write-Step "Firewall rule added."
        } else {
            Write-Warn "Firewall rule was not added (exit $($elevated.ExitCode)). The phone may not be able to connect."
        }
    } catch {
        Write-Warn "Firewall rule skipped: $($_.Exception.Message). Run this script again and accept the UAC prompt if the phone cannot connect."
    }
}

# 5. Start
$nodeArgs = @("dist\index.js", "--port", "$Port")
if ($Workspace) { $nodeArgs += @("--workspace", $Workspace) }
if ($Name) { $nodeArgs += @("--name", $Name) }
if ($ResetToken) { $nodeArgs += "--reset-token" }
Write-Step "Starting bridge on port $Port..."
& node @nodeArgs
exit $LASTEXITCODE
